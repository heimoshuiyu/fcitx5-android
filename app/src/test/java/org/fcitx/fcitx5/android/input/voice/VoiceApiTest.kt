/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceApiTest {

    @Test
    fun normalRequestOmitsNullFields() {
        val body = voiceApiJson.encodeToString(
            VoiceTranscribeRequest(audio = "QUJD", mime = "audio/wav")
        )
        assertEquals("""{"audio":"QUJD","mime":"audio/wav"}""", body)
    }

    @Test
    fun editRequestSendsLalmOverrideWithContextPrompt() {
        val request = VoiceTranscribeRequest(
            audio = "QUJD",
            mime = "audio/wav",
            prompt = buildVoicePrompt("take a note", "hello world"),
            voice = buildEditVoiceSettings(),
        )
        val body = voiceApiJson.encodeToString(request)
        assertEquals(
            """{"audio":"QUJD","mime":"audio/wav",""" +
                """"prompt":"<TRANSCRIPTION_CONTEXT>\ntake a note\n</TRANSCRIPTION_CONTEXT>\n""" +
                """<SELECTED_TEXT>\nhello world\n</SELECTED_TEXT>",""" +
                """"voice":{"type":"lalm","lalm":{"system":"$VOICE_EDIT_SYSTEM_PROMPT",""" +
                """"instruction":"$VOICE_EDIT_INSTRUCTION"}}}""",
            body,
        )
    }

    @Test
    fun lalmModelIsASingleProviderSlashModelString() {
        val body = voiceApiJson.encodeToString(
            VoiceTranscribeRequest(
                audio = "QUJD",
                mime = "audio/wav",
                voice = VoiceSettings(lalm = LalmSettings(model = "openrouter/xiaomi/mimo-v2.5")),
            )
        )
        assertEquals(
            """{"audio":"QUJD","mime":"audio/wav",""" +
                """"voice":{"lalm":{"model":"openrouter/xiaomi/mimo-v2.5"}}}""",
            body,
        )
    }

    @Test
    fun editOverrideKeepsModelOnlyWhenItHasAProvider() {
        assertNull(buildEditVoiceSettings("whisper-1").lalm?.model)
        assertEquals(
            "google/gemini-2.5-flash",
            buildEditVoiceSettings("google/gemini-2.5-flash").lalm?.model,
        )
    }

    @Test
    fun responseEnvelopeIsUnwrapped() {
        val response = voiceApiJson.decodeFromString<VoiceTranscribeResponse>(
            """{"location":{"directory":"/srv"},"data":{"text":"你好","usage":{"input_tokens":422,"output_tokens":80}}}"""
        )
        assertEquals("你好", response.data?.text)
        assertEquals(422L, response.data?.usage?.inputTokens)
        assertEquals(80L, response.data?.usage?.outputTokens)
    }

    @Test
    fun errorShapeIsTagAndMessage() {
        val error = voiceApiJson.decodeFromString<VoiceErrorResponse>(
            """{"_tag":"QuotaExceededError","message":"No credits remaining."}"""
        )
        assertEquals("QuotaExceededError", error.tag)
        assertEquals("No credits remaining.", error.message)
    }
}
