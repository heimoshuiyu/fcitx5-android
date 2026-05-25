/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Subscription backend — sends requests to the subscription gateway.
 * Compatible with OpenCode Voice Transcription SDK format, but only
 * sends audio, context (prompt), and instruction.
 * System prompt and voice overrides are controlled server-side.
 */
class SubscriptionBackend(
    private val getGatewayUrl: () -> String?,
    private val getAccessToken: () -> String?,
) : VoiceBackend {

    override val name = "Subscription"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

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
        selectedText: String?
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val gatewayUrl = getGatewayUrl()
                ?: throw TranscriptionException("Subscription gateway not configured")

            val token = getAccessToken()
                ?: throw TranscriptionException("Not authorized. Please sign in to your subscription.")

            val baseUrl = gatewayUrl.trimEnd('/')
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
            )

            val requestBody = json.encodeToString(request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("$baseUrl/voice/transcribe")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                val errorMessage = try {
                    // Try wrapped format: {"detail": {"error": "...", "error_description": "..."}}
                    val wrapped = json.decodeFromString<SubscriptionErrorWrapper>(errorBody ?: "")
                    val detail = wrapped.detail
                    when {
                        detail != null && detail.error == "quota_exceeded" -> "Monthly usage limit exceeded. Please upgrade your plan."
                        detail != null && detail.error == "invalid_token" -> "Session expired. Please sign in again."
                        detail != null && detail.error_description != null -> detail.error_description
                        detail != null && detail.error != null -> detail.error
                        else -> "Server error: ${response.code}"
                    }
                } catch (_: Exception) {
                    "Server error: ${response.code} - ${errorBody?.take(200)}"
                }
                throw TranscriptionException(errorMessage)
            }

            val responseBody = response.body?.string()
                ?: throw TranscriptionException("Empty response from server")

            Timber.d("[$name] Response body: $responseBody")

            val result = json.decodeFromString<TranscribeResponse>(responseBody)
            TranscriptionResult(text = result.text, rawResponseBody = responseBody)
        }
    }
}

@kotlinx.serialization.Serializable
data class SubscriptionTranscribeRequest(
    val audio: String,
    val mime: String,
    val prompt: String? = null,
    val instruction: String? = null,
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
