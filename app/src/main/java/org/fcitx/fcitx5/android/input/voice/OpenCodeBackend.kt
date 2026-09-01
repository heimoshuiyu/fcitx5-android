/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit

/**
 * OpenCode v2 server backend — POST to /api/voice/transcribe with Basic Auth.
 * The server routes to its configured backend (Whisper or LALM internally).
 */
class OpenCodeBackend(
    private val getServerUrl: () -> String?,
    private val getAuthUsername: () -> String,
    private val getAuthPassword: () -> String?,
    private val getModel: () -> String?,
) : VoiceBackend {

    override val name = "OpenCode"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun transcribe(
        audioBytes: ByteArray,
        mime: String,
        prompt: String?,
        selectedText: String?,
        imageBase64: String?
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runTranscriptionCatching {
            val baseUrl = requireSecureBaseUrl(getServerUrl(), "Server URL")
            val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val modelStr = getModel()?.trim()

            val voice = if (selectedText != null) {
                // Edit mode: override system and instruction for text editing
                buildEditVoiceSettings(modelStr)
            } else if (!modelStr.isNullOrEmpty()) {
                // Normal mode: "provider/model" selects a LALM model, a bare
                // name selects a Whisper model.
                if (modelStr.contains('/')) {
                    VoiceSettings(lalm = LalmSettings(model = modelStr))
                } else {
                    VoiceSettings(whisper = WhisperSettings(model = modelStr))
                }
            } else null

            val request = VoiceTranscribeRequest(
                audio = audioBase64,
                mime = mime,
                prompt = buildVoicePrompt(prompt, selectedText),
                voice = voice,
                images = if (!imageBase64.isNullOrEmpty()) listOf(imageBase64) else null,
            )

            val requestBody = voiceApiJson.encodeToString(request)
                .toRequestBody("application/json".toMediaType())

            val authHeader = buildAuthHeader()

            val httpRequest = Request.Builder()
                .url("$baseUrl/api/voice/transcribe")
                .post(requestBody)
                .apply {
                    if (authHeader != null) {
                        addHeader("Authorization", authHeader)
                    }
                }
                .build()

            httpClient.newCall(httpRequest).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    throw TranscriptionException(
                        parseErrorMessage(response.body.string(), response.code)
                    )
                }

                val responseBody = response.body.string()
                if (responseBody.isEmpty()) {
                    throw TranscriptionException("Empty response from server")
                }

                val result = voiceApiJson.decodeFromString<VoiceTranscribeResponse>(responseBody)
                val text = result.data?.text
                    ?: throw TranscriptionException("Malformed response from server")
                TranscriptionResult(text = text)
            }
        }
    }

    private fun buildAuthHeader(): String? {
        val password = getAuthPassword()?.takeIf { it.isNotBlank() } ?: return null
        val username = getAuthUsername()
        return Credentials.basic(username, password)
    }

    private fun parseErrorMessage(errorBody: String, statusCode: Int): String {
        val message = runCatching {
            voiceApiJson.decodeFromString<VoiceErrorResponse>(errorBody).message
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "Server error: $statusCode"
    }
}
