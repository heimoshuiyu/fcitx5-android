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
        runCatching {
            val url = getUrl()
                ?: throw TranscriptionException("Whisper API URL not configured")
            val apiKey = getApiKey()
                ?: throw TranscriptionException("Whisper API key not configured")

            val baseUrl = url.trimEnd('/')
            val extension = if (mime.contains("mp3")) "mp3" else "wav"
            val audioMediaType = mime.toMediaType()

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.$extension",
                    audioBytes.toRequestBody(audioMediaType)
                )
                .addFormDataPart("model", getModel())
                .addFormDataPart("response_format", "json")

            prompt?.let { bodyBuilder.addFormDataPart("prompt", it) }
            getLanguage()?.let { bodyBuilder.addFormDataPart("language", it) }

            val httpRequest = Request.Builder()
                .url("$baseUrl/audio/transcriptions")
                .post(bodyBuilder.build())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw TranscriptionException(
                    "Whisper error: ${response.code} - ${errorBody?.take(200)}"
                )
            }

            val responseBody = response.body?.string()
                ?: throw TranscriptionException("Empty response from Whisper")

            Timber.d("[$name] Response body: $responseBody")

            val result = json.decodeFromString<WhisperResponse>(responseBody)
            TranscriptionResult(
                text = result.text ?: "",
                rawResponseBody = responseBody,
            )
        }
    }
}

@Serializable
data class WhisperResponse(
    val text: String? = null,
)
