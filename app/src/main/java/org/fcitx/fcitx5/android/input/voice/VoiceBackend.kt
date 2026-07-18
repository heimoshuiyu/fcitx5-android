/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Result of a voice transcription call.
 *
 * @param text the transcribed text
 */
data class TranscriptionResult(
    val text: String,
)

/**
 * Common interface for voice transcription backends.
 */
interface VoiceBackend {

    /**
     * Transcribe audio bytes to text.
     *
     * @param audioBytes raw audio data (WAV format)
     * @param mime MIME type of the audio (e.g. "audio/wav")
     * @param prompt optional context prompt for the transcription
     * @param selectedText if non-null, the backend should treat the voice as
     *   an instruction to edit this text (instead of transcribing to new text)
     * @param imageBase64 optional screenshot as data URI (e.g. "data:image/jpeg;base64,...")
     * @return Result with [TranscriptionResult] on success
     */
    suspend fun transcribe(
        audioBytes: ByteArray,
        mime: String,
        prompt: String?,
        selectedText: String? = null,
        imageBase64: String? = null
    ): Result<TranscriptionResult>

    /** Human-readable name for error messages */
    val name: String
}

internal suspend fun <T> runTranscriptionCatching(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

internal fun requireConfigured(value: String?, label: String): String {
    return value?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw TranscriptionException("$label not configured")
}

internal fun requireSecureBaseUrl(value: String?, label: String): String {
    val configured = requireConfigured(value, label).trimEnd('/')
    val url = configured.toHttpUrlOrNull()
        ?: throw TranscriptionException("Invalid $label")
    if (!url.isHttps) {
        throw TranscriptionException("$label must use HTTPS")
    }
    return configured
}
