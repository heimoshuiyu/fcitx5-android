/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Subscription backend — sends requests to the subscription gateway.
 * Compatible with OpenCode Voice Transcription SDK format, but only
 * sends audio, context (prompt), and instruction.
 * System prompt and voice overrides are controlled server-side.
 *
 * Token lifecycle:
 * - Access token: 1 hour (server-configured)
 * - Refresh token: 30 days
 * - On 401 / invalid_token, automatically refreshes using refresh_token
 *   and retries the original request once.
 */
class SubscriptionBackend(
    private val getGatewayUrl: () -> String?,
    private val getAccessToken: () -> String?,
    private val getRefreshToken: () -> String?,
    private val onTokensUpdated: (accessToken: String, refreshToken: String) -> Unit,
    private val onSessionExpired: () -> Unit,
) : VoiceBackend {

    override val name = "Subscription"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Attempt to refresh tokens using the stored refresh_token.
     * Returns new token pair on success, null on failure.
     */
    private suspend fun refreshTokens(baseUrl: String): TokenRefreshResult? {
        val refreshToken = getRefreshToken()?.takeIf { it.isNotBlank() }
        if (refreshToken == null) {
            Timber.w("[$name] No refresh token available, cannot refresh")
            return null
        }

        return try {
            val formBody = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/oauth/token")
                .post(formBody)
                .build()

            httpClient.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("[$name] Token refresh failed: ${response.code}")
                    return null
                }

                val body = response.body.string()
                if (body.isEmpty()) return null
                val tokenResponse = json.decodeFromString<TokenResponse>(body)
                TokenRefreshResult(
                    accessToken = requireConfigured(
                        tokenResponse.access_token,
                        "Refreshed access token",
                    ),
                    refreshToken = tokenResponse.refresh_token
                        ?.takeIf { it.isNotBlank() }
                        ?: refreshToken,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("[$name] Token refresh failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Parse error from response body. Returns error code and message.
     */
    private fun parseError(errorBody: String?, statusCode: Int): Pair<String?, String> {
        return try {
            val wrapped = json.decodeFromString<SubscriptionErrorWrapper>(errorBody ?: "")
            val detail = wrapped.detail
            when {
                detail != null && detail.error == "quota_exceeded" ->
                    detail.error to "Monthly usage limit exceeded. Please upgrade your plan."
                detail != null && detail.error == "invalid_token" ->
                    detail.error to "Session expired. Please sign in again."
                detail != null && detail.error != null ->
                    detail.error to "Server error: $statusCode"
                else -> null to "Server error: $statusCode"
            }
        } catch (_: Exception) {
            null to "Server error: $statusCode"
        }
    }

    override suspend fun transcribe(
        audioBytes: ByteArray,
        mime: String,
        prompt: String?,
        selectedText: String?,
        imageBase64: String?
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runTranscriptionCatching {
            val token = getAccessToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw TranscriptionException("Not authorized. Please sign in to your subscription.")
            val baseUrl = requireSecureBaseUrl(
                getGatewayUrl(),
                "Subscription gateway",
            )
            val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            // Build instruction for edit mode
            val instruction = if (selectedText != null) {
                "Execute the voice instruction on the selected text. Output ONLY the edited text, nothing else."
            } else null

            // Build prompt: in edit mode, wrap context + selected text
            val requestPrompt = when {
                selectedText != null -> {
                    val parts = mutableListOf<String>()
                    if (!prompt.isNullOrBlank()) {
                        parts.add("<TRANSCRIPTION_CONTEXT>\n$prompt\n</TRANSCRIPTION_CONTEXT>")
                    }
                    parts.add("<SELECTED_TEXT>\n$selectedText\n</SELECTED_TEXT>")
                    parts.joinToString("\n")
                }
                else -> prompt
            }

            val request = SubscriptionTranscribeRequest(
                audio = audioBase64,
                mime = mime,
                prompt = requestPrompt,
                instruction = instruction,
                images = if (!imageBase64.isNullOrEmpty()) listOf(imageBase64) else null,
            )

            val requestBody = json.encodeToString(request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("$baseUrl/voice/transcribe")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val (errorCode, errorMessage) = httpClient
                .newCall(httpRequest)
                .executeAsync()
                .use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body.string()
                        if (responseBody.isEmpty()) {
                            throw TranscriptionException("Empty response from server")
                        }

                        val result = json.decodeFromString<TranscribeResponse>(responseBody)
                        return@runTranscriptionCatching TranscriptionResult(
                            text = result.text,
                        )
                    }

                    parseError(response.body.string(), response.code)
                }

            // Auto-refresh on invalid_token (expired access token)
            if (errorCode == "invalid_token") {
                Timber.d("[$name] Access token expired, attempting refresh...")
                val refreshed = refreshTokens(baseUrl)
                if (refreshed != null) {
                    Timber.d("[$name] Token refreshed successfully, retrying request...")
                    // Persist new tokens
                    onTokensUpdated(refreshed.accessToken, refreshed.refreshToken)

                    // Retry the original request with new access token
                    val retryRequest = Request.Builder()
                        .url("$baseUrl/voice/transcribe")
                        .post(requestBody)
                        .addHeader("Authorization", "Bearer ${refreshed.accessToken}")
                        .build()

                    return@runTranscriptionCatching httpClient
                        .newCall(retryRequest)
                        .executeAsync()
                        .use { retryResponse ->
                            if (!retryResponse.isSuccessful) {
                                val (_, retryErrorMessage) = parseError(
                                    retryResponse.body.string(),
                                    retryResponse.code,
                                )
                                throw TranscriptionException(retryErrorMessage)
                            }

                            val retryBody = retryResponse.body.string()
                            if (retryBody.isEmpty()) {
                                throw TranscriptionException("Empty response from server")
                            }
                            val result = json.decodeFromString<TranscribeResponse>(retryBody)
                            TranscriptionResult(text = result.text)
                        }
                }

                // Refresh failed — session truly expired, clear tokens
                Timber.w("[$name] Token refresh failed, session expired")
                onSessionExpired()
            }

            throw TranscriptionException(errorMessage)
        }
    }
}

/** Internal result of a successful token refresh */
private data class TokenRefreshResult(
    val accessToken: String,
    val refreshToken: String,
)

@kotlinx.serialization.Serializable
private data class TokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val token_type: String = "Bearer",
    val expires_in: Int = 3600,
)

@kotlinx.serialization.Serializable
data class SubscriptionTranscribeRequest(
    val audio: String,
    val mime: String,
    val prompt: String? = null,
    val instruction: String? = null,
    val images: List<String>? = null,
)

@kotlinx.serialization.Serializable
data class SubscriptionErrorDetail(
    val error: String? = null,
    val error_description: String? = null,
    val message: String? = null,
)

@kotlinx.serialization.Serializable
data class SubscriptionErrorWrapper(
    val detail: SubscriptionErrorDetail? = null,
)

@kotlinx.serialization.Serializable
data class SubscriptionError(
    val error: String? = null,
    val error_description: String? = null,
    val detail: String? = null,
)
