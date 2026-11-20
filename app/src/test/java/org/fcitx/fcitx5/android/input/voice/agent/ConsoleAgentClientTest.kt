/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.agent

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.fcitx.fcitx5.android.input.voice.ConsoleApi
import org.fcitx.fcitx5.android.input.voice.ConsoleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent loop against a MockWebServer: the fixed endpoint, the request
 * shape, and the tool round trip with its replayed assistant state. SSE
 * bodies stream in small chunks so frame reassembly across reads is
 * exercised too.
 */
class ConsoleAgentClientTest {

    private fun entry(providerKey: String, modelId: String) = ConsoleCatalog.ModelEntry(
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

    private fun client(server: MockWebServer) = ConsoleAgentClient(
        getAccessToken = { "token" },
        getRefreshToken = { null },
        getOrgId = { "org_1" },
        getModel = { "opencode/agent-model" },
        getVariant = { "" },
        onTokensUpdated = { _, _ -> },
        onSessionExpired = { },
        chatBase = server.url("/openai/v1").toString().trimEnd('/'),
        fetchCatalog = { _, _ -> listOf(entry("opencode", "agent-model")) },
    )

    /** One SSE response: each frame on its own `data:` line, streamed in small chunks. */
    private fun sse(vararg frames: String) =
        MockResponse.Builder()
            .setHeader("Content-Type", "text/event-stream")
            .chunkedBody(Buffer().writeUtf8(frames.joinToString("") { "$it\n\n" }), 8)
            .build()

    @Test
    fun streamsTextAndFinishesWithoutTools() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                sse(
                    ": keep-alive",
                    """data: {"choices":[{"index":0,"delta":{"role":"assistant"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"content":"Hello"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"reasoning_content":"thinking"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                    "data: [DONE]",
                ),
            )

            val events = mutableListOf<AgentEvent>()
            client(server).run(
                originalText = "原稿",
                finalText = "终稿",
                existingHotwords = listOf("已有词"),
                onEvent = { events.add(it) },
                submitHotwords = { error("No tool call was expected in this turn") },
            )

            assertEquals(
                listOf(
                    AgentEvent.Text(1, "Hello"),
                    AgentEvent.Reasoning(1, "thinking"),
                    AgentEvent.Finished(1, "stop"),
                    AgentEvent.Usage(1, 10, 5),
                ),
                events,
            )

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            // The one fixed endpoint, whichever protocol the model publishes.
            assertEquals("/openai/v1/chat/completions", recorded.url.encodedPath)
            assertEquals("Bearer token", recorded.headers["Authorization"])
            assertEquals("org_1", recorded.headers["x-org-id"])
            assertTrue(recorded.headers["x-opencode-session"]!!.startsWith("ses_"))

            val body = ConsoleApi.json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            // The managed channel serves the bare model id; the request never
            // switches endpoint or protocol.
            assertEquals("agent-model", body["model"]!!.jsonPrimitive.content)
            assertEquals(true, (body["stream"] as JsonPrimitive).content.toBoolean())
            assertEquals(
                true,
                (body["stream_options"] as JsonObject)["include_usage"]!!.jsonPrimitive.content.toBoolean(),
            )
            assertNull(body["store"])
            assertNull(body["background"])
            assertNull(body["reasoning_effort"])
            // One declared client tool, at the model's discretion.
            val tools = body["tools"]!!.jsonArray
            assertEquals(1, tools.size)
            assertEquals(
                HotwordPrompt.TOOL_NAME,
                tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
            )
            assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
            // The comparison inputs are fenced inside the user turn.
            val messages = body["messages"]!!.jsonArray
            assertEquals(2, messages.size)
            assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
            val user = messages[1].jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(user.contains("<original_transcription>\n原稿\n</original_transcription>"))
            assertTrue(user.contains("<final_text>\n终稿\n</final_text>"))
            assertTrue(user.contains("<existing_hotwords>\n已有词\n</existing_hotwords>"))
        }
    }

    @Test
    fun runsToolTurnsAndReplaysAssistantState() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Turn 1: two tool calls in one finish; the final delta carries the
            // replay fields the next request must copy verbatim.
            server.enqueue(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"content":"Checking."}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[""" +
                        """{"index":0,"id":"call_1","type":"function","function":{"name":"submit_hotwords","arguments":"{\"words\":[\"张三\"]}"}}]}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[""" +
                        """{"index":1,"id":"call_2","type":"function","function":{"name":"submit_hotwords","arguments":"{\"words\":["}}]}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"\"李四\"]}"}}]}}]}""",
                    """data: {"choices":[{"index":0,"delta":{"provider_metadata":""" +
                        """{"protocol":"anthropic-messages","model":"claude-x","connection_id":"conn_1","content":[]}},""" +
                        """"finish_reason":"tool_calls"}]}""",
                    "data: [DONE]",
                ),
            )
            // Turn 2: the model sees the tool results and finishes.
            server.enqueue(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"content":"Recorded one."}}]}""",
                    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    "data: [DONE]",
                ),
            )

            val events = mutableListOf<AgentEvent>()
            val submitted = mutableListOf<List<String>>()
            client(server).run(
                originalText = "张三说了句话",
                finalText = "张三又说了句话",
                existingHotwords = listOf("王五"),
                onEvent = { events.add(it) },
                submitHotwords = { words ->
                    submitted.add(words)
                    if (words.contains("李四")) throw IllegalStateException("disk full")
                    """{"added":${words.size}}"""
                },
            )

            // Both calls reached the callback; the failing one became a visible tool error.
            assertEquals(listOf(listOf("张三"), listOf("李四")), submitted)
            assertEquals(
                listOf(
                    AgentEvent.Text(1, "Checking."),
                    AgentEvent.ToolCall(1, "call_1", HotwordPrompt.TOOL_NAME, """{"words":["张三"]}"""),
                    AgentEvent.ToolCall(1, "call_2", HotwordPrompt.TOOL_NAME, """{"words":["李四"]}"""),
                    AgentEvent.Finished(1, "tool_calls"),
                    AgentEvent.ToolResult(1, "call_1", """{"added":1}"""),
                    AgentEvent.ToolResult(1, "call_2", """{"error":"disk full"}"""),
                    AgentEvent.Text(2, "Recorded one."),
                    AgentEvent.Finished(2, "stop"),
                ),
                events,
            )

            server.takeRequest(5, TimeUnit.SECONDS)
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            val messages = ConsoleApi.json.parseToJsonElement(second.body!!.utf8())
                .jsonObject["messages"]!!.jsonArray
            assertEquals(5, messages.size)
            // The assistant turn replays its calls and the continuation fields.
            val assistant = messages[2].jsonObject
            assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
            assertEquals("Checking.", assistant["content"]!!.jsonPrimitive.content)
            val toolCalls = assistant["tool_calls"]!!.jsonArray
            assertEquals(listOf("call_1", "call_2"), toolCalls.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            assertEquals(
                """{"words":["李四"]}""",
                toolCalls[1].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
            )
            assertEquals(
                ConsoleApi.json.parseToJsonElement(
                    """{"protocol":"anthropic-messages","model":"claude-x","connection_id":"conn_1","content":[]}""",
                ),
                assistant["provider_metadata"],
            )
            // Each tool result answers its call; the failed one says so.
            val ok = messages[3].jsonObject
            assertEquals("tool", ok["role"]!!.jsonPrimitive.content)
            assertEquals("call_1", ok["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("""{"added":1}""", ok["content"]!!.jsonPrimitive.content)
            assertNull(ok["is_error"])
            val failed = messages[4].jsonObject
            assertEquals("call_2", failed["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("""{"error":"disk full"}""", failed["content"]!!.jsonPrimitive.content)
            assertEquals(
                true,
                (failed["is_error"] as? JsonPrimitive)?.content?.toBoolean(),
            )
        }
    }

    @Test
    fun byokOverlayHeadersCannotReplaceConsoleCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"content":"ok"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    "data: [DONE]",
                ),
            )
            // A BYOK connection whose saved config carries provider headers
            // that collide with the gateway's own.
            val byok = entry("console-openrouter", "anthropic/claude-x").copy(
                providerHeaders = mapOf(
                    "Authorization" to "Bearer byok-provider-key",
                    "x-org-id" to "spoofed-org",
                    "X-Provider" to "openrouter",
                ),
            )

            val events = mutableListOf<AgentEvent>()
            ConsoleAgentClient(
                getAccessToken = { "console-token" },
                getRefreshToken = { null },
                getOrgId = { "org_1" },
                getModel = { "console-openrouter/anthropic/claude-x" },
                getVariant = { "" },
                onTokensUpdated = { _, _ -> },
                onSessionExpired = { },
                chatBase = server.url("/openai/v1").toString().trimEnd('/'),
                fetchCatalog = { _, _ -> listOf(byok) },
            ).run(
                originalText = "a",
                finalText = "b",
                existingHotwords = emptyList(),
                onEvent = { events.add(it) },
                submitHotwords = { error("No tool call was expected in this turn") },
            )

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            // The Console credential, org and session survive the overlay's
            // same-name headers — as the one value the request carries…
            assertEquals(listOf("Bearer console-token"), recorded.headers.values("Authorization"))
            assertEquals("Bearer console-token", recorded.headers["Authorization"])
            assertEquals("org_1", recorded.headers["x-org-id"])
            assertTrue(recorded.headers["x-opencode-session"]!!.startsWith("ses_"))
            // …while a provider header that does not collide still passes through.
            assertEquals("openrouter", recorded.headers["X-Provider"])
            // The BYOK wire model is the composite id, on the fixed endpoint.
            val body = ConsoleApi.json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals("console-openrouter/anthropic/claude-x", body["model"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun catalogNetworkErrorSurfacesAsAgentException() = runBlocking {
        val client = ConsoleAgentClient(
            getAccessToken = { "token" },
            getRefreshToken = { null },
            getOrgId = { null },
            getModel = { "opencode/agent-model" },
            getVariant = { "" },
            onTokensUpdated = { _, _ -> },
            onSessionExpired = { },
            // The catalog fetch fails before any request is sent.
            fetchCatalog = { _, _ -> throw java.io.IOException("no route to host") },
        )
        val error = runCatching {
            client.run(
                originalText = "a",
                finalText = "b",
                existingHotwords = emptyList(),
                onEvent = {},
                submitHotwords = { error("No call was expected") },
            )
        }.exceptionOrNull()
        assertTrue(error is AgentException)
        assertTrue(error?.message?.contains("Network error") == true)
    }

    @Test
    fun selectedEffortLevelRidesTheRequestBody() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"content":"ok"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    "data: [DONE]",
                ),
            )
            server.enqueue(
                sse(
                    """data: {"choices":[{"index":0,"delta":{"content":"ok"}}]}""",
                    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    "data: [DONE]",
                ),
            )
            // The catalog synthesizes the model's published effort levels, as
            // it does for glm-5.3's ["low", "high", "max"] on models.dev.
            val entries = ConsoleCatalog.buildEntries(
                ConsoleApi.ConfigDocument(),
                mapOf(
                    "opencode" to ConsoleApi.ChannelModels(
                        id = "opencode",
                        models = mapOf(
                            "agent-model" to ConsoleApi.InferenceModel(
                                id = "agent-model",
                                capabilities = ConsoleApi.InferenceModel.Capabilities(
                                    tools = ConsoleApi.InferenceModel.ToolSupport(supported = true),
                                    reasoning = ConsoleApi.InferenceModel.ReasoningSupport(
                                        supported = true,
                                        effort = listOf("low", "high", "max"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                ConsoleCatalog.Kind.Agent,
            )
            fun client(variant: String) = ConsoleAgentClient(
                getAccessToken = { "token" },
                getRefreshToken = { null },
                getOrgId = { null },
                getModel = { "opencode/agent-model" },
                getVariant = { variant },
                onTokensUpdated = { _, _ -> },
                onSessionExpired = { },
                chatBase = server.url("/openai/v1").toString().trimEnd('/'),
                fetchCatalog = { _, _ -> entries },
            )
            val consume: suspend (String) -> Unit = { variant ->
                client(variant).run(
                    originalText = "a",
                    finalText = "b",
                    existingHotwords = emptyList(),
                    onEvent = {},
                    submitHotwords = { error("No tool call was expected") },
                )
            }

            // A selected level rides the request body; the blank selection
            // leaves the provider's own default untouched.
            consume("high")
            val withEffort = ConsoleApi.json
                .parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8())
                .jsonObject
            assertEquals("high", withEffort["reasoning_effort"]!!.jsonPrimitive.content)
            consume("")
            val withoutEffort = ConsoleApi.json
                .parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8())
                .jsonObject
            assertNull(withoutEffort["reasoning_effort"])
        }
    }
}
