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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Subscription backend — sends requests to the subscription gateway using the
 * opencode v2 voice transcription format (`POST /api/voice/transcribe`).
 * Without a voice override the gateway applies the user's server-side
 * transcription configuration; edit mode sends a LALM editing override.
 *
 * Token lifecycle:
 * - Access token: 1 hour (server-configured)
 * - Refresh token: 30 days
 * - On 401 / UnauthorizedError, automatically refreshes using refresh_token
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
                val tokenResponse = voiceApiJson.decodeFromString<TokenResponse>(body)
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
     * Parse a v2 error response `{_tag, message}`. Returns the tag and a
     * user-facing message.
     */
    private fun parseError(errorBody: String?, statusCode: Int): Pair<String?, String> {
        val error = runCatching {
            voiceApiJson.decodeFromString<VoiceErrorResponse>(errorBody ?: "")
        }.getOrNull()
        val tag = error?.tag
        return when (tag) {
            "QuotaExceededError" ->
                tag to "Monthly usage limit exceeded. Please upgrade your plan."
            "UnauthorizedError" ->
                tag to "Session expired. Please sign in again."
            null -> null to "Server error: $statusCode"
            else -> tag to (error.message?.takeIf { it.isNotBlank() } ?: "Server error: $statusCode")
        }
    }

    private fun parseSuccess(body: String): TranscriptionResult {
        if (body.isEmpty()) {
            throw TranscriptionException("Empty response from server")
        }
        val result = runCatching {
            voiceApiJson.decodeFromString<VoiceTranscribeResponse>(body)
        }.getOrNull() ?: throw TranscriptionException("Malformed response from server")
        val text = result.data?.text
            ?: throw TranscriptionException("Malformed response from server")
        return TranscriptionResult(text = text)
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

            // Edit mode sends a LALM editing override; normal mode leaves the
            // voice unset so the gateway applies the user's configuration.
            val request = VoiceTranscribeRequest(
                audio = audioBase64,
                mime = mime,
                prompt = buildVoicePrompt(prompt, selectedText),
                voice = if (selectedText != null) buildEditVoiceSettings() else null,
                images = if (!imageBase64.isNullOrEmpty()) listOf(imageBase64) else null,
            )

            val requestBody = voiceApiJson.encodeToString(request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("$baseUrl/api/voice/transcribe")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val (errorTag, errorMessage) = httpClient
                .newCall(httpRequest)
                .executeAsync()
                .use { response ->
                    if (response.isSuccessful) {
                        return@runTranscriptionCatching parseSuccess(response.body.string())
                    }

                    parseError(response.body.string(), response.code)
                }

            // Auto-refresh on UnauthorizedError (expired access token)
            if (errorTag == "UnauthorizedError") {
                Timber.d("[$name] Access token expired, attempting refresh...")
                val refreshed = refreshTokens(baseUrl)
                if (refreshed != null) {
                    Timber.d("[$name] Token refreshed successfully, retrying request...")
                    // Persist new tokens
                    onTokensUpdated(refreshed.accessToken, refreshed.refreshToken)

                    // Retry the original request with new access token
                    val retryRequest = Request.Builder()
                        .url("$baseUrl/api/voice/transcribe")
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

                            parseSuccess(retryResponse.body.string())
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

@Serializable
private data class TokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val token_type: String = "Bearer",
    val expires_in: Int = 3600,
)
