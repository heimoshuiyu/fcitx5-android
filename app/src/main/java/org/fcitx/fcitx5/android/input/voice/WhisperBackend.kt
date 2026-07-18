/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Whisper backend — POST multipart/form-data to an OpenAI-compatible
 * /v1/audio/transcriptions endpoint.
 */
class WhisperBackend(
    private val getUrl: () -> String?,
    private val getApiKey: () -> String?,
    private val getModel: () -> String,
    private val getLanguage: () -> String?,
) : VoiceBackend {

    override val name = "Whisper"

    private val json = Json { ignoreUnknownKeys = true }

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
        @Suppress("UNUSED_PARAMETER") imageBase64: String?
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runTranscriptionCatching {
            val apiKey = requireConfigured(getApiKey(), "Whisper API key")
            val model = requireConfigured(getModel(), "Whisper model")
            val baseUrl = requireSecureBaseUrl(getUrl(), "Whisper API URL")
            val extension = if (mime.contains("mp3")) "mp3" else "wav"
            val audioMediaType = mime.toMediaType()

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.$extension",
                    audioBytes.toRequestBody(audioMediaType)
                )
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")

            prompt?.let { bodyBuilder.addFormDataPart("prompt", it) }
            getLanguage()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                bodyBuilder.addFormDataPart("language", it)
            }

            val httpRequest = Request.Builder()
                .url("$baseUrl/audio/transcriptions")
                .post(bodyBuilder.build())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            httpClient.newCall(httpRequest).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    throw TranscriptionException(
                        "Whisper error: ${response.code}"
                    )
                }

                val responseBody = response.body.string()
                if (responseBody.isEmpty()) {
                    throw TranscriptionException("Empty response from Whisper")
                }

                val result = json.decodeFromString<WhisperResponse>(responseBody)
                TranscriptionResult(
                    text = result.text ?: "",
                )
            }
        }
    }
}

@Serializable
data class WhisperResponse(
    val text: String? = null,
)
