/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM smokes for the Google generate-content wire: the request the backend
 * would send, built by the same pure function production uses.
 */
class ConsoleGoogleWireTest {

    private fun resolved(overlay: JsonObject = JsonObject(emptyMap())) = ConsoleCatalog.Resolved(
        model = "gemini-3.8-flash",
        protocol = ConsoleApi.GENERATE_CONTENT_PROTOCOL,
        baseUrl = "https://opencode.ai/inference/google/v1beta",
        headers = emptyMap(),
        bodyOverlay = overlay,
    )

    @Test
    fun googleBodyCarriesSystemInstructionAudioAndTranslatedOverlay() {
        val body = ConsoleBackend.buildGoogleBody(
            resolved(
                buildJsonObject {
                    put("thinkingConfig", buildJsonObject { put("thinkingLevel", "high") })
                    // Unknown keys must not leak into the wire: Google rejects
                    // unrecognized top-level fields.
                    put("totally-unknown", "x")
                }
            ),
            audioBase64 = "QUJD",
            prompt = "take a note",
            selectedText = null,
            imageBase64 = "data:image/jpeg;base64,Zm9v",
        )

        val system = body["systemInstruction"]!!
            .jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(ConsoleBackend.TRANSCRIPTION_SYSTEM_PROMPT, system)

        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        // Context, screenshot as inline data, audio markers, WAV, reminder.
        assertEquals("<TRANSCRIPTION_CONTEXT>\ntake a note\n</TRANSCRIPTION_CONTEXT>", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        val image = parts[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", image["mimeType"]!!.jsonPrimitive.content)
        assertEquals("Zm9v", image["data"]!!.jsonPrimitive.content)
        assertEquals("<audio-starts>", parts[2].jsonObject["text"]!!.jsonPrimitive.content)
        val audio = parts[3].jsonObject["inlineData"]!!.jsonObject
        assertEquals("audio/wav", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("QUJD", audio["data"]!!.jsonPrimitive.content)
        assertEquals("<audio-ends>", parts[4].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            "<system-reminder>\n${ConsoleBackend.TRANSCRIPTION_INSTRUCTION}\n</system-reminder>",
            parts[5].jsonObject["text"]!!.jsonPrimitive.content,
        )

        // Variant overlay lands in the only wire location Google accepts it.
        val generationConfig = body["generationConfig"]!!.jsonObject
        assertEquals(
            "high",
            generationConfig["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
        )
        org.junit.Assert.assertNull(body.jsonObject["totally-unknown"])

        // Transcription must be verbatim: the four categories are fully off.
        val safety = body["safetySettings"]!!.jsonArray
        assertEquals(4, safety.size)
        assertEquals(
            "OFF",
            safety[0].jsonObject["threshold"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun modelSegmentIsSafelyEncoded() {
        assertEquals("gemini-3.8-flash", ConsoleBackend.encodeModelSegment("gemini-3.8-flash"))
        assertEquals("a%2Fb%3Fc%3Ad%20e", ConsoleBackend.encodeModelSegment("a/b?c:d e"))
    }
}
