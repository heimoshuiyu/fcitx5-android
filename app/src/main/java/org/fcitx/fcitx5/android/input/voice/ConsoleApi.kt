/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import timber.log.Timber
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * OpenCode Console client: RFC 8628 device authorization, token refresh,
 * workspace config and the inference model list.
 *
 * Verified against the Console's effect-auth API and the inference gateway:
 * - device code/token: `POST {CONSOLE_BASE}/auth/device/code|token` (JSON)
 * - token errors are `{error, error_description}` with the RFC 8628 values
 * - config: `GET {CONSOLE_BASE}/api/v2/config` (Bearer + x-org-id)
 * - models: `GET {INFERENCE_BASE}/v1/models` (Bearer + x-org-id), the
 *   models.dev v2 shape including `modalities` and per-protocol `api`
 */
object ConsoleApi {

    const val CONSOLE_BASE = "https://opencode.ai/console"
    const val INFERENCE_BASE = "https://opencode.ai/inference"
    const val DEFAULT_CHAT_BASE = "$INFERENCE_BASE/openai/v1"
    const val DEFAULT_GOOGLE_BASE = "$INFERENCE_BASE/google/v1beta"
    const val CLIENT_ID = "fcitx5-android"

    /**
     * The config `providers` key of the managed OpenCode connection that
     * serves [INFERENCE_BASE]. The workspace's other config providers are
     * its own (BYOK) connections, keyed by their own ids.
     */
    const val MANAGED_PROVIDER_KEY = "opencode"

    /** The protocol key of OpenAI-compatible chat completions in models.dev v2 feeds. */
    const val CHAT_COMPLETIONS_PROTOCOL = "chat-completions"

    /** The protocol key of Google's native generate-content API in models.dev v2 feeds. */
    const val GENERATE_CONTENT_PROTOCOL = "generate-content"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A scope that outlives any view: sign-out's best-effort revoke must not
     * be cancelled by leaving the settings screen. Local credential cleanup
     * happens before this is called, so its failure is harmless either way.
     */
    private val revokeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Wire models ---

    /**
     * Typed request bodies. Mixed-value maps infer `Any` values, which have no
     * serializer and fail before any request is sent; typed classes make that
     * impossible to reintroduce.
     */
    @Serializable
    data class DeviceCodeRequest(
        val client_id: String,
        val supports_org_scope: Boolean,
    )

    @Serializable
    data class DeviceTokenRequest(
        val grant_type: String,
        val device_code: String,
        val client_id: String,
    )

    @Serializable
    data class RefreshTokenRequest(
        val grant_type: String,
        val refresh_token: String,
        val client_id: String,
    )

    @Serializable
    data class DeviceCodeResponse(
        val device_code: String,
        val user_code: String,
        val verification_uri: String,
        val verification_uri_complete: String? = null,
        val expires_in: Int = 300,
        val interval: Int = 5,
    )

    @Serializable
    data class TokenResponse(
        val access_token: String,
        val refresh_token: String,
        val token_type: String = "Bearer",
        val expires_in: Int = 3600,
        val org_id: String? = null,
    )

    /** `{error, error_description}` as the device/token endpoints return. */
    @Serializable
    data class OAuthError(
        val error: String,
        val error_description: String? = null,
    )

    /** Provider entry of the Console config document. */
    @Serializable
    data class ConsoleProvider(
        val name: String? = null,
        val canonical: String? = null,
        val settings: JsonObject? = null,
        val headers: Map<String, String>? = null,
        val body: JsonObject? = null,
        val models: Map<String, ConsoleModel>? = null,
    ) {
        val baseURL: String?
            get() = settings?.get("baseURL")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    /** Model entry of a provider, as the Console config publishes it. */
    @Serializable
    data class ConsoleModel(
        val modelID: String? = null,
        val name: String? = null,
        val disabled: Boolean = false,
        val headers: Map<String, String>? = null,
        val body: JsonObject? = null,
        val variants: List<ConsoleVariant>? = null,
    ) {
        val id: String get() = modelID ?: ""
    }

    @Serializable
    data class ConsoleVariant(
        val id: String,
        val headers: Map<String, String>? = null,
        val body: JsonObject? = null,
    )

    @Serializable
    data class ConfigDocument(
        val providers: Map<String, ConsoleProvider> = emptyMap(),
    )

    /** Entry of `GET {INFERENCE_BASE}/v1/models`: models.dev v2 with unknown keys kept out. */
    @Serializable
    data class InferenceModel(
        val id: String,
        val name: String? = null,
        val type: String? = null,
        val status: String? = null,
        val modalities: Modalities? = null,
        val api: Map<String, JsonObject>? = null,
    ) {
        @Serializable
        data class Modalities(
            val input: List<String>? = null,
            val output: List<String>? = null,
        )

        fun supportsAudioInput(): Boolean =
            modalities?.input?.any { it.startsWith("audio") } == true

        /**
         * The protocol this app can drive for the model: OpenAI-compatible
         * chat completions is preferred, Google's native generate-content is
         * the fallback. Null when neither is served.
         */
        fun protocolFor(): String? = when {
            api?.containsKey(CHAT_COMPLETIONS_PROTOCOL) == true -> CHAT_COMPLETIONS_PROTOCOL
            api?.containsKey(GENERATE_CONTENT_PROTOCOL) == true -> GENERATE_CONTENT_PROTOCOL
            else -> null
        }

        /** The models.dev `base_url` published for one of the model's APIs. */
        fun baseUrlFor(protocol: String): String? =
            api?.get(protocol)?.get("base_url")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    @Serializable
    private data class InferenceModelsResponse(
        val data: List<InferenceModel> = emptyList(),
    )

    // --- Errors ---

    /** The device grant is still awaiting user approval. */
    class DevicePendingException : Exception("authorization_pending")

    /** The server asked to slow down; callers should widen their polling interval. */
    class DeviceSlowDownException : Exception("slow_down")

    class DeviceExpiredException(description: String?) :
        Exception(description?.takeIf { it.isNotBlank() } ?: "The device code has expired")

    class DeviceDeniedException(description: String?) :
        Exception(description?.takeIf { it.isNotBlank() } ?: "The authorization request was denied")

    class ConsoleHttpException(val status: Int, message: String) : Exception(message)

    // --- Auth (RFC 8628 device flow) ---

    suspend fun requestDeviceCode(): DeviceCodeResponse =
        postJson(
            "$CONSOLE_BASE/auth/device/code",
            json.encodeToString(DeviceCodeRequest(CLIENT_ID, supports_org_scope = true)),
        ) { body -> json.decodeFromString<DeviceCodeResponse>(body) }

    /**
     * Poll the device token endpoint once. Throws
     * [DevicePendingException]/[DeviceSlowDownException] while waiting, and
     * [DeviceDeniedException]/[DeviceExpiredException]/[ConsoleHttpException]
     * when the flow can no longer succeed.
     */
    suspend fun pollDeviceToken(deviceCode: String): TokenResponse =
        postJson(
            "$CONSOLE_BASE/auth/device/token",
            json.encodeToString(
                DeviceTokenRequest(
                    grant_type = "urn:ietf:params:oauth:grant-type:device_code",
                    device_code = deviceCode,
                    client_id = CLIENT_ID,
                )
            ),
        ) { body -> decodeTokenResponse(body) }

    /**
     * Exchange a refresh token for a rotated pair. Throws [ConsoleHttpException]
     * when the grant is invalid or expired; callers treat that as signed out.
     */
    suspend fun refreshTokens(refreshToken: String): TokenResponse =
        postJson(
            "$CONSOLE_BASE/auth/device/token",
            json.encodeToString(
                RefreshTokenRequest(
                    grant_type = "refresh_token",
                    refresh_token = refreshToken,
                    client_id = CLIENT_ID,
                )
            ),
        ) { body -> decodeTokenResponse(body) }

    private fun decodeTokenResponse(body: String): TokenResponse {
        val error = runCatching { json.decodeFromString<OAuthError>(body) }.getOrNull()
        if (error != null) {
            when (error.error) {
                "authorization_pending" -> throw DevicePendingException()
                "slow_down" -> throw DeviceSlowDownException()
                "expired_token" -> throw DeviceExpiredException(error.error_description)
                "access_denied" -> throw DeviceDeniedException(error.error_description)
                else -> throw ConsoleHttpException(
                    400,
                    error.error_description?.takeIf { it.isNotBlank() }
                        ?: "Authorization failed: ${error.error}",
                )
            }
        }
        return json.decodeFromString<TokenResponse>(body)
    }

    /**
     * Best-effort RFC 7009 revocation of the refresh token. Fire-and-forget:
     * any outcome (success, unknown token, network failure) is ignored beyond
     * a log line, and local cleanup must already have happened.
     */
    fun revokeAsync(refreshToken: String) {
        if (refreshToken.isBlank()) return
        revokeScope.launch {
            try {
                val form = FormBody.Builder()
                    .add("token", refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", CLIENT_ID)
                    .build()
                val request = Request.Builder()
                    .url("$CONSOLE_BASE/auth/oauth/revoke")
                    .post(form)
                    .build()
                httpClient.newCall(request).executeAsync().use { response ->
                    Timber.d("Console token revoke: ${response.code}")
                }
            } catch (e: Exception) {
                Timber.w("Console token revoke failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Resolve a verification URL from the device-code response against the
     * Console origin, like the official client does. The response may carry
     * a root-relative path, and it must never send the user somewhere else:
     * only https on the OpenCode host, with no userinfo, is accepted.
     */
    fun resolveVerificationUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val resolved = runCatching {
            URI("$CONSOLE_BASE/").resolve(raw.trim())
        }.getOrNull() ?: return null
        if (resolved.scheme != "https") return null
        if (resolved.host != URI(CONSOLE_BASE).host) return null
        if (resolved.rawUserInfo != null) return null
        return resolved.toString()
    }

    // --- Config + models ---

    suspend fun fetchConfig(accessToken: String, orgId: String?): ConfigDocument =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$CONSOLE_BASE/api/v2/config")
                .header("Authorization", "Bearer $accessToken")
                .apply { orgId?.takeIf { it.isNotBlank() }?.let { header("x-org-id", it) } }
                .build()
            httpClient.newCall(request).executeAsync().use { response ->
                if (response.code == 404) {
                    // A workspace without a config serves 404; treat it as empty.
                    return@use ConfigDocument()
                }
                if (!response.isSuccessful) {
                    throw ConsoleHttpException(response.code, "Config request failed")
                }
                json.decodeFromString<ConfigDocument>(response.body.string())
            }
        }

    suspend fun fetchInferenceModels(accessToken: String, orgId: String?): List<InferenceModel> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$INFERENCE_BASE/v1/models")
                .header("Authorization", "Bearer $accessToken")
                .apply { orgId?.takeIf { it.isNotBlank() }?.let { header("x-org-id", it) } }
                .build()
            httpClient.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    throw ConsoleHttpException(response.code, "Model list request failed")
                }
                json.decodeFromString<InferenceModelsResponse>(response.body.string()).data
            }
        }

    private suspend fun <T> postJson(
        url: String,
        payload: String,
        decode: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).executeAsync().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                // A 400 carries the RFC 8628 `{error, error_description}` shape;
                // decode it through the normal path so pending/slow_down keep
                // their meaning instead of collapsing into a generic failure.
                try {
                    decode(body)
                } catch (e: Exception) {
                    when (e) {
                        is DevicePendingException, is DeviceSlowDownException,
                        is DeviceExpiredException, is DeviceDeniedException -> throw e
                        else -> throw ConsoleHttpException(response.code, "Request failed")
                    }
                }
                throw ConsoleHttpException(response.code, "Request failed")
            }
            decode(body)
        }
    }
}
