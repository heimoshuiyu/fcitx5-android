/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCatalogTest {

    private fun model(
        id: String,
        input: List<String>? = listOf("text", "audio/wav"),
        api: Map<String, kotlinx.serialization.json.JsonObject>? = mapOf(
            "chat-completions" to buildJsonObject { put("base_url", "https://example.com") }
        ),
        // models.dev v2 publishes `type: "chat"` for chat models.
        type: String? = "chat",
        status: String? = null,
    ) = ConsoleApi.InferenceModel(
        id = id,
        name = "Model $id",
        type = type,
        status = status,
        modalities = ConsoleApi.InferenceModel.Modalities(input = input),
        api = api,
    )

    private fun config(vararg models: Pair<String, ConsoleApi.ConsoleModel>) = ConsoleApi.ConfigDocument(
        providers = mapOf(
            "opencode" to ConsoleApi.ConsoleProvider(
                name = "OpenCode",
                settings = buildJsonObject { put("baseURL", "https://opencode.ai/inference/openai/v1") },
                headers = mapOf("X-Provider" to "one"),
                models = models.toMap(),
            )
        )
    )

    @Test
    fun catalogListsUnconfiguredModelsAndExcludesDisabled() {
        val config = config(
            "openai/gpt-audio" to ConsoleApi.ConsoleModel(modelID = "openai/gpt-audio"),
            "openai/disabled" to ConsoleApi.ConsoleModel(
                modelID = "openai/disabled",
                disabled = true,
            ),
        )
        val entries = ConsoleCatalog.buildEntries(
            config,
            listOf(
                model("openai/gpt-audio"),
                model("openai/text-only", input = listOf("text")),
                model("openai/no-chat-api", api = emptyMap()),
                model("openai/realtime", type = "realtime"),
                model("openai/legacy", status = "deprecated"),
                model("openai/disabled"),
                // A member's config carries overlays, not the full catalog:
                // this qualified model is offered without any config entry.
                model("openai/unconfigured"),
            ),
        )
        assertEquals(
            listOf("openai/gpt-audio", "openai/unconfigured"),
            entries.map { it.id },
        )
        val configured = entries.first { it.id == "openai/gpt-audio" }
        assertEquals("https://opencode.ai/inference/openai/v1", configured.baseUrl)
        val unconfigured = entries.first { it.id == "openai/unconfigured" }
        // No config entry: the models.dev route for the protocol wins, with
        // no overlays and only the default variant.
        assertEquals("https://example.com", unconfigured.baseUrl)
        assertEquals(emptyList<String>(), unconfigured.variants)
        assertEquals(emptyMap<String, String>(), ConsoleCatalog.resolve(unconfigured, "").headers)
    }

    @Test
    fun catalogIgnoresByokProviderForManagedModels() {
        val config = ConsoleApi.ConfigDocument(
            providers = mapOf(
                // A workspace BYOK Google connection: its own route, credentials
                // and an entry marking the same model disabled there. The
                // Console emits `disabled: true` whenever a model is not
                // enabled on that connection.
                "console-google" to ConsoleApi.ConsoleProvider(
                    name = "Google",
                    settings = buildJsonObject { put("baseURL", "https://byok.example.com/google/v1beta") },
                    headers = mapOf("x-goog-api-key" to "byok-key"),
                    body = buildJsonObject { put("temperature", 0.9) },
                    models = mapOf(
                        "gemini-3.8-flash" to ConsoleApi.ConsoleModel(
                            modelID = "gemini-3.8-flash",
                            disabled = true,
                        )
                    ),
                ),
                // The managed connection: no overlay for the model.
                "opencode" to ConsoleApi.ConsoleProvider(name = "OpenCode"),
            )
        )
        val entries = ConsoleCatalog.buildEntries(
            config,
            listOf(
                model(
                    "gemini-3.8-flash",
                    api = mapOf(
                        "generate-content" to buildJsonObject {
                            put("base_url", "https://opencode.ai/inference/google/v1beta")
                        }
                    ),
                )
            ),
        )
        // The BYOK entry does not hide the managed model.
        assertEquals(listOf("gemini-3.8-flash"), entries.map { it.id })
        val resolved = ConsoleCatalog.resolve(entries.single(), "")
        // The managed route wins: the models.dev base URL, not the BYOK one.
        assertEquals("https://opencode.ai/inference/google/v1beta", resolved.baseUrl)
        // No BYOK headers or body leak into the managed request.
        assertEquals(emptyMap<String, String>(), resolved.headers)
        assertTrue(resolved.bodyOverlay.isEmpty())
    }

    @Test
    fun catalogDrivesGoogleGenerateContentModels() {
        val config = config()
        val entries = ConsoleCatalog.buildEntries(
            config,
            listOf(
                // Google-native: only the generate-content API is served, with
                // the models.dev base_url for that route.
                model(
                    "gemini-3.8-flash",
                    api = mapOf(
                        "generate-content" to buildJsonObject {
                            put("base_url", "https://opencode.ai/inference/google/v1beta")
                        }
                    ),
                ),
                // Both APIs served: the app prefers OpenAI-compatible chat.
                model(
                    "google/dual-protocol",
                    api = mapOf(
                        "chat-completions" to buildJsonObject { put("base_url", "https://example.com/chat") },
                        "generate-content" to buildJsonObject { put("base_url", "https://example.com/google") },
                    ),
                ),
                // Audio model with neither drivable protocol: excluded.
                model("google/other-protocol", api = mapOf("messages" to buildJsonObject { })),
                // Google-native without a published base_url: protocol default.
                model("gemini-no-base", api = mapOf("generate-content" to buildJsonObject { })),
            ),
        )
        val byId = entries.associateBy { it.id }

        val gemini = byId.getValue("gemini-3.8-flash")
        assertEquals(ConsoleApi.GENERATE_CONTENT_PROTOCOL, gemini.protocol)
        assertEquals("https://opencode.ai/inference/google/v1beta", gemini.baseUrl)

        val dual = byId.getValue("google/dual-protocol")
        assertEquals(ConsoleApi.CHAT_COMPLETIONS_PROTOCOL, dual.protocol)
        assertEquals("https://example.com/chat", dual.baseUrl)

        val noBase = byId.getValue("gemini-no-base")
        assertEquals(ConsoleApi.DEFAULT_GOOGLE_BASE, noBase.baseUrl)

        org.junit.Assert.assertNull(byId["google/other-protocol"])
    }

    @Test
    fun variantOverlaysWinOverProviderAndModel() {
        val config = config(
            "openai/gpt-audio" to ConsoleApi.ConsoleModel(
                modelID = "openai/gpt-audio",
                headers = mapOf("X-Custom" to "model"),
                body = buildJsonObject {
                    put("temperature", 0.1)
                    put("reasoning", buildJsonObject {
                        put("effort", "low")
                        put("toggle", true)
                    })
                },
                variants = listOf(
                    ConsoleApi.ConsoleVariant(
                        id = "high",
                        headers = mapOf("x-custom" to "variant"),
                        body = buildJsonObject {
                            put("temperature", 0.9)
                            put("reasoning", buildJsonObject { put("effort", "high") })
                        },
                    )
                ),
            )
        )
        val entries = ConsoleCatalog.buildEntries(config, listOf(model("openai/gpt-audio")))
        val entry = entries.single()

        val default = ConsoleCatalog.resolve(entry, "")
        assertEquals(mapOf("X-Provider" to "one", "X-Custom" to "model"), default.headers)
        assertEquals(
            0.1,
            (default.bodyOverlay["temperature"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(),
            0.0,
        )

        val high = ConsoleCatalog.resolve(entry, "high")
        // Header merge is case-insensitive with the winning entry's casing kept.
        assertEquals(mapOf("X-Provider" to "one", "x-custom" to "variant"), high.headers)
        assertEquals(
            0.9,
            (high.bodyOverlay["temperature"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(),
            0.0,
        )
        // Body merge is recursive: the overlay's `reasoning` object merges
        // into the base's instead of dropping its sibling keys.
        val reasoning = high.bodyOverlay["reasoning"] as kotlinx.serialization.json.JsonObject
        assertEquals(
            "high",
            (reasoning["effort"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals(
            true,
            (reasoning["toggle"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean(),
        )
    }

    @Test
    fun staleVariantIdIsRejected() {
        val config = config(
            "openai/gpt-audio" to ConsoleApi.ConsoleModel(
                modelID = "openai/gpt-audio",
                variants = listOf(ConsoleApi.ConsoleVariant(id = "high")),
            )
        )
        val entry = ConsoleCatalog.buildEntries(config, listOf(model("openai/gpt-audio"))).single()
        val error = runCatching { ConsoleCatalog.resolve(entry, "removed-variant") }.exceptionOrNull()
        assertTrue(error is TranscriptionException)
        assertTrue(error?.message?.contains("not available") == true)
    }
}
