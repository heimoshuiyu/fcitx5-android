/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OpenCode v2 voice transcription wire models, shared by the direct OpenCode
 * backend and the subscription (VoiceHub gateway) backend. Both speak the same
 * `POST /api/voice/transcribe` contract.
 */

val voiceApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/** LALM system prompt used when the voice input edits the currently selected text. */
const val VOICE_EDIT_SYSTEM_PROMPT =
    "You are a text editing assistant. The user has selected some text and will give you a voice instruction on how to edit it. " +
        "Execute the instruction precisely. Output ONLY the edited result text, no explanations, no markdown formatting, no quotes."

/** LALM instruction used when the voice input edits the currently selected text. */
const val VOICE_EDIT_INSTRUCTION =
    "Execute the voice instruction on the selected text. Output ONLY the edited text, nothing else."

@Serializable
data class VoiceTranscribeRequest(
    val audio: String,
    val mime: String,
    val prompt: String? = null,
    @SerialName("contextSessionID")
    val contextSessionId: String? = null,
    val images: List<String>? = null,
    val voice: VoiceSettings? = null,
)

@Serializable
data class VoiceSettings(
    val type: String? = null,
    val lalm: LalmSettings? = null,
    val whisper: WhisperSettings? = null,
)

@Serializable
data class LalmSettings(
    /** Model reference as `"provider/model"`, e.g. `"google/gemini-2.5-flash"`. */
    val model: String? = null,
    val system: String? = null,
    val instruction: String? = null,
    @SerialName("audio_input_format")
    val audioInputFormat: String? = null,
)

@Serializable
data class WhisperSettings(
    val model: String? = null,
    val language: String? = null,
)

/** v2 success envelope: `{location, data: {text, usage?}}`. */
@Serializable
data class VoiceTranscribeResponse(
    val data: VoiceTranscribeData? = null,
)

@Serializable
data class VoiceTranscribeData(
    val text: String,
    val usage: VoiceUsage? = null,
)

@Serializable
data class VoiceUsage(
    @SerialName("input_tokens")
    val inputTokens: Long? = null,
    @SerialName("output_tokens")
    val outputTokens: Long? = null,
)

/** v2 error shape: `{_tag, message}`. */
@Serializable
data class VoiceErrorResponse(
    @SerialName("_tag")
    val tag: String? = null,
    val message: String? = null,
)

/**
 * Build the transcription prompt. In edit mode the context and the selected
 * text are wrapped into tagged sections so the model can tell them apart.
 */
fun buildVoicePrompt(prompt: String?, selectedText: String?): String? = when {
    selectedText != null -> {
        val parts = mutableListOf<String>()
        if (!prompt.isNullOrBlank()) {
            parts.add("<TRANSCRIPTION_CONTEXT>\n$prompt\n</TRANSCRIPTION_CONTEXT>")
        }
        parts.add("<SELECTED_TEXT>\n$selectedText\n</SELECTED_TEXT>")
        parts.joinToString("\n")
    }
    else -> prompt?.takeIf { it.isNotBlank() }
}

/** LALM override that turns transcription into a text-editing instruction. */
fun buildEditVoiceSettings(model: String? = null): VoiceSettings = VoiceSettings(
    type = "lalm",
    lalm = LalmSettings(
        model = model?.takeIf { it.contains('/') },
        system = VOICE_EDIT_SYSTEM_PROMPT,
        instruction = VOICE_EDIT_INSTRUCTION,
    ),
)
