/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBackendConfigurationTest {

    @Test
    fun rejectsCleartextBaseUrl() {
        val error = runCatching {
            requireSecureBaseUrl("http://example.com", "Test URL")
        }.exceptionOrNull()

        assertTrue(error is TranscriptionException)
        assertTrue(error?.message?.contains("HTTPS") == true)
    }

    @Test
    fun subscriptionRejectsBlankTokenBeforeRequest() = runTest {
        val result = SubscriptionBackend(
            getGatewayUrl = { "https://example.com" },
            getAccessToken = { "" },
            getRefreshToken = { "" },
            onTokensUpdated = { _, _ -> },
            onSessionExpired = {},
        ).transcribe(ByteArray(0), "audio/wav", null, null, null)

        assertTrue(result.exceptionOrNull() is TranscriptionException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Not authorized") == true)
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
