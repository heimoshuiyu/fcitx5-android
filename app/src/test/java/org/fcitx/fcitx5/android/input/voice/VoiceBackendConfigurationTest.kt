/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBackendConfigurationTest {

    @Test
    fun acceptsCleartextBaseUrl() {
        assertEquals(
            "http://example.com",
            requireBaseUrl("http://example.com", "Test URL"),
        )
    }

    @Test
    fun consoleRejectsMissingTokenBeforeRequest() = runTest {
        val result = ConsoleBackend(
            getAccessToken = { "" },
            getRefreshToken = { "" },
            getOrgId = { "" },
            getModel = { "openai/gpt-audio" },
            getVariant = { "" },
            onTokensUpdated = { _, _ -> },
            onSessionExpired = {},
        ).transcribe(ByteArray(0), "audio/wav", null, null, null)

        assertTrue(result.exceptionOrNull() is TranscriptionException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Not signed in") == true)
    }

    @Test
    fun consoleRejectsMissingModelBeforeRequest() = runTest {
        val result = ConsoleBackend(
            getAccessToken = { "token" },
            getRefreshToken = { "" },
            getOrgId = { "" },
            getModel = { "" },
            getVariant = { "" },
            onTokensUpdated = { _, _ -> },
            onSessionExpired = {},
        ).transcribe(ByteArray(0), "audio/wav", null, null, null)

        assertTrue(result.exceptionOrNull() is TranscriptionException)
        assertTrue(result.exceptionOrNull()?.message?.contains("No model selected") == true)
    }

    @Test
    fun whisperRejectsBlankApiKeyBeforeRequest() = runTest {
        val result = WhisperBackend(
            getUrl = { "https://example.com" },
            getApiKey = { "" },
            getModel = { "whisper-1" },
            getLanguage = { "" },
        ).transcribe(ByteArray(0), "audio/wav", null, null, null)

        assertTrue(result.exceptionOrNull() is TranscriptionException)
        assertTrue(result.exceptionOrNull()?.message?.contains("API key") == true)
    }

    @Test
    fun lalmRejectsBlankModelBeforeRequest() = runTest {
        val result = LALMBackend(
            getUrl = { "https://example.com" },
            getApiKey = { "test-key" },
            getModel = { "" },
            getSystemPrompt = { "" },
            getAudioFormat = { LalmAudioFormat.InputAudio },
            getTemperature = { "" },
        ).transcribe(ByteArray(0), "audio/wav", null, null, null)

        assertTrue(result.exceptionOrNull() is TranscriptionException)
        assertTrue(result.exceptionOrNull()?.message?.contains("model") == true)
    }
}
