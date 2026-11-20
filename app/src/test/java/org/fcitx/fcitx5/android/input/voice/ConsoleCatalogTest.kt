/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCatalogTest {

    private fun model(
        id: String,
        name: String? = null,
        input: List<String>? = listOf("text", "audio/wav"),
        api: Map<String, kotlinx.serialization.json.JsonObject>? = mapOf(
            "chat-completions" to buildJsonObject { put("base_url", "https://example.com") }
        ),
        // models.dev v2 publishes `type: "chat"` for chat models.
        type: String? = "chat",
        status: String? = null,
        // `tools.supported`; null leaves the capabilities unpublished.
        tools: Boolean? = null,
        // `capabilities.reasoning` as models.dev publishes it, or absent.
        reasoning: ConsoleApi.InferenceModel.ReasoningSupport? = null,
    ) = ConsoleApi.InferenceModel(
        id = id,
        name = name ?: "Model $id",
        type = type,
        status = status,
        modalities = ConsoleApi.InferenceModel.Modalities(input = input),
        api = api,
        capabilities = when {
            tools == null && reasoning == null -> null
            else -> ConsoleApi.InferenceModel.Capabilities(
                tools = tools?.let { ConsoleApi.InferenceModel.ToolSupport(supported = it) },
                reasoning = reasoning,
            )
        },
    )

    private fun channel(
        providerKey: String,
        channelName: String? = null,
        vararg models: Pair<String, ConsoleApi.InferenceModel>,
    ) = ConsoleApi.ChannelModels(id = providerKey, name = channelName, models = models.toMap())

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
            mapOf(
                "opencode" to channel(
                    "opencode",
                    "My Workspace / OpenCode",
                    "openai/gpt-audio" to model("openai/gpt-audio"),
                    "openai/text-only" to model("openai/text-only", input = listOf("text")),
                    "openai/no-chat-api" to model("openai/no-chat-api", api = emptyMap()),
                    "openai/realtime" to model("openai/realtime", type = "realtime"),
                    "openai/legacy" to model("openai/legacy", status = "deprecated"),
                    "openai/disabled" to model("openai/disabled"),
                    // A member's config carries overlays, not the full catalog:
                    // this qualified model is offered without any config entry.
                    "openai/unconfigured" to model("openai/unconfigured"),
                    // A chat model without a published route falls back to the
                    // channel provider's config base URL.
                    "openai/no-base" to model(
                        "openai/no-base",
                        api = mapOf("chat-completions" to buildJsonObject {}),
                    ),
                )
            ),
        )
        assertEquals(
            listOf("opencode/openai/gpt-audio", "opencode/openai/no-base", "opencode/openai/unconfigured"),
            entries.map { it.id },
        )
        val configured = entries.first { it.id == "opencode/openai/gpt-audio" }
        // The channel's published route for the model wins over the
        // provider-level base URL.
        assertEquals("https://example.com", configured.baseUrl)
        val noBase = entries.first { it.id == "opencode/openai/no-base" }
        assertEquals("https://opencode.ai/inference/openai/v1", noBase.baseUrl)
        val unconfigured = entries.first { it.id == "opencode/openai/unconfigured" }
        // No config model entry: no model or variant overlays ride along; the
        // channel-level provider headers still do.
        assertEquals("https://example.com", unconfigured.baseUrl)
        assertEquals(emptyList<String>(), unconfigured.variants)
        assertEquals(mapOf("X-Provider" to "one"), ConsoleCatalog.resolve(unconfigured, "").headers)
    }

    @Test
    fun catalogIgnoresByokProviderForManagedModels() {
        val config = ConsoleApi.ConfigDocument(
            providers = mapOf(
                // A workspace BYOK Google connection: its own route, credentials
                // and an entry marking the same model disabled there. The
                // Console emits `disabled: true` whenever a model is not
                // enabled on that connection, and a channel catalog the server
                // publishes leaves its disabled models out — so only the
                // connection's overlays could leak into the managed request.
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
            mapOf(
                "opencode" to channel(
                    "opencode",
                    "My Workspace / OpenCode",
                    "gemini-3.8-flash" to model(
                        "gemini-3.8-flash",
                        api = mapOf(
                            "generate-content" to buildJsonObject {
                                put("base_url", "https://opencode.ai/inference/google/v1beta")
                            }
                        ),
                    )
                ),
            ),
        )
        // The BYOK entry does not hide the managed model.
        assertEquals(listOf("opencode/gemini-3.8-flash"), entries.map { it.id })
        val resolved = ConsoleCatalog.resolve(entries.single(), "")
        // The managed route wins: the published route, not the BYOK one.
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
            mapOf(
                "opencode" to channel(
                    "opencode",
                    "My Workspace / OpenCode",
                    // Google-native: only the generate-content API is served, with
                    // the models.dev base_url for that route.
                    "gemini-3.8-flash" to model(
                        "gemini-3.8-flash",
                        api = mapOf(
                            "generate-content" to buildJsonObject {
                                put("base_url", "https://opencode.ai/inference/google/v1beta")
                            }
                        ),
                    ),
                    // Both APIs served: the app prefers OpenAI-compatible chat.
                    "google/dual-protocol" to model(
                        "google/dual-protocol",
                        api = mapOf(
                            "chat-completions" to buildJsonObject { put("base_url", "https://example.com/chat") },
                            "generate-content" to buildJsonObject { put("base_url", "https://example.com/google") },
                        ),
                    ),
                    // Audio model with neither drivable protocol: excluded.
                    "google/other-protocol" to model("google/other-protocol", api = mapOf("messages" to buildJsonObject {})),
                    // Google-native without a published base_url: protocol default.
                    "gemini-no-base" to model("gemini-no-base", api = mapOf("generate-content" to buildJsonObject {})),
                )
            ),
        )
        val byId = entries.associateBy { it.id }

        val gemini = byId.getValue("opencode/gemini-3.8-flash")
        assertEquals(ConsoleApi.GENERATE_CONTENT_PROTOCOL, gemini.protocol)
        assertEquals("https://opencode.ai/inference/google/v1beta", gemini.baseUrl)

        val dual = byId.getValue("opencode/google/dual-protocol")
        assertEquals(ConsoleApi.CHAT_COMPLETIONS_PROTOCOL, dual.protocol)
        assertEquals("https://example.com/chat", dual.baseUrl)

        // A generate-content model without a published base URL falls back to
        // the protocol default, never the channel's OpenAI-compatible base.
        val noBase = byId.getValue("opencode/gemini-no-base")
        assertEquals(ConsoleApi.DEFAULT_GOOGLE_BASE, noBase.baseUrl)

        assertNull(byId["opencode/google/other-protocol"])
    }

    @Test
    fun sameNameModelOnTwoChannelsStaysDistinct() {
        val config = ConsoleApi.ConfigDocument(
            providers = mapOf(
                "opencode" to ConsoleApi.ConsoleProvider(
                    name = "OpenCode",
                    headers = mapOf("X-Provider" to "managed"),
                ),
                "console-google" to ConsoleApi.ConsoleProvider(
                    name = "Google",
                    headers = mapOf("X-Provider" to "byok"),
                    models = mapOf(
                        "kimi-k3" to ConsoleApi.ConsoleModel(
                            modelID = "kimi-k3",
                            variants = listOf(ConsoleApi.ConsoleVariant(id = "byok-variant")),
                        )
                    ),
                ),
            )
        )
        val entries = ConsoleCatalog.buildEntries(
            config,
            mapOf(
                "opencode" to channel(
                    "opencode",
                    "My Workspace / OpenCode",
                    "kimi-k3" to model("kimi-k3", name = "Kimi K3"),
                ),
                "console-google" to channel(
                    "console-google",
                    "My Workspace / Google",
                    "kimi-k3" to model(
                        "kimi-k3",
                        name = "Kimi K3",
                        api = mapOf(
                            "generate-content" to buildJsonObject {
                                put("base_url", "https://opencode.ai/inference/custom/conn_byok")
                            }
                        ),
                    ),
                ),
            ),
        )
        // The managed channel comes first; each entry's composite id names its
        // channel, so a selection can never route to the other one.
        assertEquals(
            listOf("opencode/kimi-k3", "console-google/kimi-k3"),
            entries.map { it.id },
        )
        assertEquals("My Workspace / OpenCode", entries[0].channelName)
        assertEquals("My Workspace / Google", entries[1].channelName)

        val managed = ConsoleCatalog.resolve(entries[0], "")
        val byok = ConsoleCatalog.resolve(entries[1], "byok-variant")
        // Each channel keeps its own route and overlays; nothing crosses.
        assertEquals("https://example.com", managed.baseUrl)
        assertEquals(mapOf("X-Provider" to "managed"), managed.headers)
        assertTrue(managed.bodyOverlay.isEmpty())
        assertEquals("https://opencode.ai/inference/custom/conn_byok", byok.baseUrl)
        assertEquals(mapOf("X-Provider" to "byok"), byok.headers)
        // The wire request names the bare model id on the channel's base URL.
        assertEquals("kimi-k3", managed.model)
        assertEquals("kimi-k3", byok.model)
    }

    @Test
    fun duplicateChannelNamesGetProviderKeySuffix() {
        val entries = ConsoleCatalog.buildEntries(
            ConsoleApi.ConfigDocument(),
            mapOf(
                "console-google" to channel(
                    "console-google",
                    "My Workspace / Google",
                    "kimi-k3" to model("kimi-k3"),
                ),
                "console-google-2" to channel(
                    "console-google-2",
                    "My Workspace / Google",
                    "kimi-k3" to model("kimi-k3"),
                ),
            ),
        )
        // The provider key disambiguates the same channel display name in
        // both the group headers and the selection summary.
        assertEquals(
            listOf("My Workspace / Google (console-google)", "My Workspace / Google (console-google-2)"),
            entries.map { it.channelName },
        )
    }

    @Test
    fun findByIdResolvesLegacyBareManagedId() {
        val entries = ConsoleCatalog.buildEntries(
            ConsoleApi.ConfigDocument(),
            mapOf(
                "opencode" to channel(
                    "opencode",
                    "My Workspace / OpenCode",
                    "kimi-k3" to model("kimi-k3"),
                ),
                "console-google" to channel(
                    "console-google",
                    "My Workspace / Google",
                    "kimi-k3" to model(
                        "kimi-k3",
                        api = mapOf("generate-content" to buildJsonObject {}),
                    ),
                ),
            ),
        )
        // Composite ids match exactly.
        assertEquals(
            "console-google/kimi-k3",
            ConsoleCatalog.findById(entries, "console-google/kimi-k3")?.id,
        )
        // A bare model id persisted by an older version resolves to the
        // managed channel's model of that id.
        assertEquals(
            "opencode/kimi-k3",
            ConsoleCatalog.findById(entries, "kimi-k3")?.id,
        )
        assertNull(ConsoleCatalog.findById(entries, "no-such-model"))
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
        val entries = ConsoleCatalog.buildEntries(
            config,
            mapOf("opencode" to channel("opencode", "My Workspace / OpenCode", "openai/gpt-audio" to model("openai/gpt-audio"))),
        )
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
        val entries = ConsoleCatalog.buildEntries(
            config,
            mapOf("opencode" to channel("opencode", "My Workspace / OpenCode", "openai/gpt-audio" to model("openai/gpt-audio"))),
        )
        val entry = entries.single()
        val error = runCatching { ConsoleCatalog.resolve(entry, "removed-variant") }.exceptionOrNull()
        assertTrue(error is TranscriptionException)
        assertTrue(error?.message?.contains("not available") == true)
    }

    @Test
    fun agentKindFiltersToolsAndFixesTheEndpoint() {
        val config = config()
        val channels = mapOf(
            "opencode" to channel(
                "opencode",
                "My Workspace / OpenCode",
                // Models publishing only Responses/Anthropic routes: invisible
                // to the voice flow, still agent-usable on the fixed endpoint.
                "gpt-x" to model(
                    "gpt-x",
                    input = listOf("text"),
                    tools = true,
                    api = mapOf("responses" to buildJsonObject { put("base_url", "https://example.com") }),
                ),
                "claude-x" to model(
                    "claude-x",
                    input = listOf("text"),
                    tools = true,
                    api = mapOf("messages" to buildJsonObject { put("base_url", "https://example.com") }),
                ),
                // An explicit `tools.supported: false` is excluded, audio or not.
                "no-tools" to model("no-tools", input = listOf("text"), tools = false),
                // No published capabilities: kept, mirroring the gateway's check.
                "unknown-tools" to model("unknown-tools", input = listOf("text"), tools = null),
                // The shared filters still apply to the agent.
                "realtime" to model("realtime", input = listOf("text"), type = "realtime", tools = true),
                "legacy" to model("legacy", input = listOf("text"), status = "deprecated", tools = true),
            )
        )
        val agent = ConsoleCatalog.buildEntries(config, channels, ConsoleCatalog.Kind.Agent)
        assertEquals(
            listOf("opencode/claude-x", "opencode/gpt-x", "opencode/unknown-tools"),
            agent.map { it.id },
        )
        // Every channel is driven on the fixed managed chat endpoint.
        agent.forEach {
            assertEquals(ConsoleApi.CHAT_COMPLETIONS_PROTOCOL, it.protocol)
            assertEquals(ConsoleApi.DEFAULT_CHAT_BASE, it.baseUrl)
        }
        // The same data offers nothing to the voice flow: no audio input
        // anywhere, so one kind's filter never decides the other's list.
        assertTrue(ConsoleCatalog.buildEntries(config, channels, ConsoleCatalog.Kind.Voice).isEmpty())
    }

    @Test
    fun agentWireModelNamesManagedGoAndByokChannels() {
        fun entry(providerKey: String, modelId: String) = ConsoleCatalog.ModelEntry(
            id = "$providerKey/$modelId",
            providerKey = providerKey,
            channelName = providerKey,
            modelId = modelId,
            displayName = modelId,
            protocol = ConsoleApi.CHAT_COMPLETIONS_PROTOCOL,
            variants = emptyList(),
            baseUrl = ConsoleApi.DEFAULT_CHAT_BASE,
            providerHeaders = null,
            providerBody = null,
            modelHeaders = null,
            modelBody = null,
            variantsById = emptyMap(),
        )
        // The managed connection serves bare ids.
        assertEquals("gpt-x", ConsoleCatalog.agentWireModel(entry("opencode", "gpt-x")))
        // Go and BYOK connections are named by their catalog key.
        assertEquals("opencode-go/gpt-x", ConsoleCatalog.agentWireModel(entry("opencode-go", "gpt-x")))
        // A BYOK model id may itself contain slashes; the key stays the prefix.
        assertEquals(
            "console-openrouter/anthropic/claude-x",
            ConsoleCatalog.agentWireModel(entry("console-openrouter", "anthropic/claude-x")),
        )
    }

    @Test
    fun builtInEffortVariantsFollowTheWireProtocol() {
        val config = config()
        val channels = mapOf(
            "opencode" to channel(
                "opencode",
                "My Workspace / OpenCode",
                // A Google-native voice model with published levels, as
                // models.dev lists gemini-3.8-flash today.
                "gemini-3.8-flash" to model(
                    "gemini-3.8-flash",
                    input = listOf("text", "audio/wav"),
                    api = mapOf(
                        "generate-content" to buildJsonObject {
                            put("base_url", "https://opencode.ai/inference/google/v1beta")
                        }
                    ),
                    reasoning = ConsoleApi.InferenceModel.ReasoningSupport(
                        supported = true,
                        effort = listOf("low", "medium", "high"),
                    ),
                ),
                // A chat model with published levels, as models.dev lists glm-5.3.
                "glm-5.3" to model(
                    "glm-5.3",
                    reasoning = ConsoleApi.InferenceModel.ReasoningSupport(
                        supported = true,
                        effort = listOf("low", "high", "max"),
                    ),
                ),
            )
        )
        // Voice: the Gemini levels become variants in published order, and the
        // overlay the request's Google merge places under `generationConfig`.
        val voice = ConsoleCatalog.buildEntries(config, channels)
        val gemini = voice.first { it.id == "opencode/gemini-3.8-flash" }
        assertEquals(listOf("low", "medium", "high"), gemini.variants)
        assertEquals(gemini.variants.toSet(), gemini.variantsById.keys)
        assertEquals(
            buildJsonObject {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", "medium")
                    put("includeThoughts", true)
                })
            },
            ConsoleCatalog.resolve(gemini, "medium").bodyOverlay,
        )
        // Agent: the chat model's levels become variants on the fixed chat
        // endpoint, with the top-level effort field on the wire.
        val agent = ConsoleCatalog.buildEntries(config, channels, ConsoleCatalog.Kind.Agent)
        val glm = agent.first { it.id == "opencode/glm-5.3" }
        assertEquals(listOf("low", "high", "max"), glm.variants)
        assertEquals(
            buildJsonObject { put("reasoning_effort", "high") },
            ConsoleCatalog.resolve(glm, "high").bodyOverlay,
        )
        // The same levels serve the voice flow's chat protocol too.
        val glmVoice = voice.first { it.id == "opencode/glm-5.3" }
        assertEquals(listOf("low", "high", "max"), glmVoice.variants)
        assertEquals(
            buildJsonObject { put("reasoning_effort", "low") },
            ConsoleCatalog.resolve(glmVoice, "low").bodyOverlay,
        )
    }

    @Test
    fun savedVariantsOverrideBuiltInEffortLevels() {
        val config = config(
            "glm-5.3" to ConsoleApi.ConsoleModel(
                modelID = "glm-5.3",
                variants = listOf(
                    ConsoleApi.ConsoleVariant(
                        id = "high",
                        body = buildJsonObject {
                            put("reasoning_effort", "high")
                            put("temperature", 0.2)
                        },
                    ),
                    ConsoleApi.ConsoleVariant(id = "turbo", body = buildJsonObject { put("stop", false) }),
                ),
            )
        )
        val channels = mapOf(
            "opencode" to channel(
                "opencode",
                "My Workspace / OpenCode",
                "glm-5.3" to model(
                    "glm-5.3",
                    reasoning = ConsoleApi.InferenceModel.ReasoningSupport(
                        supported = true,
                        effort = listOf("low", "high", "max"),
                    ),
                ),
                // No published levels: nothing synthetic, even though the
                // model says it reasons.
                "reason-only" to model(
                    "reason-only",
                    reasoning = ConsoleApi.InferenceModel.ReasoningSupport(supported = true),
                ),
                // Explicitly unsupported, or `default` only: no variants either.
                "reason-off" to model(
                    "reason-off",
                    reasoning = ConsoleApi.InferenceModel.ReasoningSupport(
                        supported = false,
                        effort = listOf("high"),
                    ),
                ),
                "default-only" to model(
                    "default-only",
                    reasoning = ConsoleApi.InferenceModel.ReasoningSupport(
                        supported = true,
                        effort = listOf("default", " ", "default"),
                    ),
                ),
            )
        )
        val entries = ConsoleCatalog.buildEntries(config, channels)
        val byId = entries.associateBy { it.id }

        // The saved "high" keeps its place in the published order with its own
        // body; the saved-only "turbo" follows the published levels.
        val glm = byId.getValue("opencode/glm-5.3")
        assertEquals(listOf("low", "high", "max", "turbo"), glm.variants)
        val high = ConsoleCatalog.resolve(glm, "high").bodyOverlay
        assertEquals(
            0.2,
            (high["temperature"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(),
            0.0,
        )
        assertEquals(
            "high",
            (high["reasoning_effort"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        // A built-in level still resolves to its own body, and so does the
        // saved-only one.
        assertEquals(
            buildJsonObject { put("reasoning_effort", "max") },
            ConsoleCatalog.resolve(glm, "max").bodyOverlay,
        )
        assertEquals(
            buildJsonObject { put("stop", false) },
            ConsoleCatalog.resolve(glm, "turbo").bodyOverlay,
        )
        assertEquals(emptyList<String>(), byId.getValue("opencode/reason-only").variants)
        assertEquals(emptyList<String>(), byId.getValue("opencode/reason-off").variants)
        assertEquals(emptyList<String>(), byId.getValue("opencode/default-only").variants)
    }
}
