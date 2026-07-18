/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * OpenCode server backend — POST to /voice/transcribe with Basic Auth.
 * The server routes to its configured backend (Whisper or LALM internally).
 */
class OpenCodeBackend(
    private val getServerUrl: () -> String?,
    private val getAuthUsername: () -> String,
    private val getAuthPassword: () -> String?,
    private val getModel: () -> String?,
) : VoiceBackend {

    override val name = "OpenCode"

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
                // Edit mode: override system, prompt, and instruction for text editing
                val editSystem = "You are a text editing assistant. The user has selected some text and will give you a voice instruction on how to edit it. " +
                    "Execute the instruction precisely. Output ONLY the edited result text, no explanations, no markdown formatting, no quotes."
                val editPrompt = "<SELECTED_TEXT>\n$selectedText\n</SELECTED_TEXT>"
                val editInstruction = "Execute the voice instruction on the selected text. Output ONLY the edited text, nothing else."
                val lalmOverride = LalmVoiceOverride(
                    system = editSystem,
                    prompt = editPrompt,
                    instruction = editInstruction,
                    model = if (!modelStr.isNullOrEmpty()) {
                        val slashIdx = modelStr.indexOf('/')
                        if (slashIdx > 0) LalmModelOverride(modelStr.substring(0, slashIdx), modelStr.substring(slashIdx + 1))
                        else null
                    } else null,
                )
                VoiceOverride(type = "lalm", lalm = lalmOverride)
            } else if (!modelStr.isNullOrEmpty()) {
                // Normal mode: just override model
                val slashIdx = modelStr.indexOf('/')
                if (slashIdx > 0) {
                    VoiceOverride(lalm = LalmVoiceOverride(model = LalmModelOverride(modelStr.substring(0, slashIdx), modelStr.substring(slashIdx + 1))))
                } else {
                    VoiceOverride(whisper = WhisperVoiceOverride(model = modelStr))
                }
            } else null

            // In edit mode, wrap context into the prompt field
            val requestPrompt = if (selectedText != null && !prompt.isNullOrBlank()) {
                "<TRANSCRIPTION_CONTEXT>\n$prompt\n</TRANSCRIPTION_CONTEXT>"
            } else prompt

            val request = TranscribeRequest(
                audio = audioBase64,
                mime = mime,
                prompt = requestPrompt,
                voice = voice,
                images = if (!imageBase64.isNullOrEmpty()) listOf(imageBase64) else null,
            )

            val requestBody = json.encodeToString(request)
                .toRequestBody("application/json".toMediaType())

            val authHeader = buildAuthHeader()

            val httpRequest = Request.Builder()
                .url("$baseUrl/voice/transcribe")
                .post(requestBody)
                .apply {
                    if (authHeader != null) {
                        addHeader("Authorization", authHeader)
                    }
                }
                .build()

            httpClient.newCall(httpRequest).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    throw TranscriptionException("Server error: ${response.code}")
                }

                val responseBody = response.body.string()
                if (responseBody.isEmpty()) {
                    throw TranscriptionException("Empty response from server")
                }

                val result = json.decodeFromString<TranscribeResponse>(responseBody)
                TranscriptionResult(text = result.text)
            }
        }
    }

    private fun buildAuthHeader(): String? {
        val password = getAuthPassword()?.takeIf { it.isNotBlank() } ?: return null
        val username = getAuthUsername()
        return Credentials.basic(username, password)
    }
}

// === OpenCode API Models ===

@kotlinx.serialization.Serializable
data class TranscribeRequest(
    val audio: String,
    val mime: String,
    val prompt: String? = null,
    @kotlinx.serialization.SerialName("sessionID")
    val sessionID: String? = null,
    val voice: VoiceOverride? = null,
    val images: List<String>? = null,
)

@kotlinx.serialization.Serializable
data class VoiceOverride(
    val type: String? = null,
    val whisper: WhisperVoiceOverride? = null,
    val lalm: LalmVoiceOverride? = null,
)

@kotlinx.serialization.Serializable
data class WhisperVoiceOverride(
    val url: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val language: String? = null,
)

@kotlinx.serialization.Serializable
data class LalmVoiceOverride(
    val model: LalmModelOverride? = null,
    val prompt: String? = null,
    val system: String? = null,
    val instruction: String? = null,
    val audioInputFormat: String? = null,
)

@kotlinx.serialization.Serializable
data class LalmModelOverride(
    val providerID: String,
    val modelID: String,
)

@kotlinx.serialization.Serializable
data class TranscribeResponse(
    val text: String,
)

@kotlinx.serialization.Serializable
data class TranscribeError(
    val name: String,
    val data: ErrorData? = null,
)

@kotlinx.serialization.Serializable
data class ErrorData(
    val message: String,
)
