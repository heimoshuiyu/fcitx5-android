/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.agent

import org.fcitx.fcitx5.android.input.voice.ConsoleApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE framing and one-turn aggregation, both pure: comment lines and unknown
 * fields drop, multi-line data joins, fragments across reads reassemble, and
 * the collector turns a chunk stream into ordered events with replay fields
 * kept as raw JSON.
 */
class ChatSseCollectorTest {

    @Test
    fun sseParserSkipsCommentsJoinsDataAndFlushes() {
        val parser = ChatSse.Parser()
        assertTrue(parser.feed(": keep-alive").isEmpty())
        assertTrue(parser.feed("event: ignored").isEmpty())
        // Two data lines accumulate; the blank line dispatches them joined.
        assertTrue(parser.feed("data: {\"a\":").isEmpty())
        assertTrue(parser.feed("data: 1}").isEmpty())
        assertEquals(listOf("{\"a\":\n1}"), parser.feed("").map { it.data })
        // No space after the colon, and the [DONE] sentinel.
        assertTrue(parser.feed("data:[DONE]").isEmpty())
        assertEquals(listOf("[DONE]"), parser.feed("").map { it.data })
        // A trailing event without its blank line is flushed at EOF.
        assertTrue(parser.feed("data: tail").isEmpty())
        assertEquals(listOf("tail"), parser.finish().map { it.data })
    }

    @Test
    fun collectorAggregatesDeltasToolsReasoningAndReplay() {
        val step = 2
        val collector = TurnCollector(step)
        fun chunk(payload: String) = ConsoleApi.json.decodeFromString<ChatChunk>(payload)

        val events = mutableListOf<AgentEvent>()
        // The role delta carries nothing; a null usage on a content chunk is ignored.
        events += collector.apply(chunk("""{"choices":[{"index":0,"delta":{"role":"assistant"}}],"usage":null}"""))
        events += collector.apply(chunk("""{"choices":[{"index":0,"delta":{"content":"He"}}]}"""))
        // Array-shaped content parts also carry text.
        events += collector.apply(chunk("""{"choices":[{"index":0,"delta":{"content":[{"type":"text","text":"llo"}]}}]}"""))
        events += collector.apply(chunk("""{"choices":[{"index":0,"delta":{"reasoning_content":"thinking"}}]}"""))
        // A tool call assembles across fragments: the first names it, the rest append arguments.
        events += collector.apply(
            chunk(
                """{"choices":[{"index":0,"delta":{"tool_calls":[""" +
                    """{"index":0,"id":"call_1","type":"function","function":{"name":"submit_hotwords","arguments":"{\"wo"}}]}}]}""",
            ),
        )
        events += collector.apply(
            chunk("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"rds\":[\"张三\"]}"}}]}}]}"""),
        )
        // The final delta carries the replay fields and the finish reason together.
        events += collector.apply(
            chunk(
                """{"choices":[{"index":0,"delta":{"reasoning_details":[""" +
                    """{"format":"openai-responses-v1","index":0,"type":"reasoning.text","text":"why","opencode":{"position":0}}]},""" +
                    """"finish_reason":"tool_calls"}]}""",
            ),
        )
        // The terminal usage frame has no choices.
        events += collector.apply(chunk("""{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""))

        assertEquals(
            listOf(
                AgentEvent.Text(step, "He"),
                AgentEvent.Text(step, "llo"),
                AgentEvent.Reasoning(step, "thinking"),
                AgentEvent.ToolCall(step, "call_1", "submit_hotwords", """{"words":["张三"]}"""),
                AgentEvent.Finished(step, "tool_calls"),
                AgentEvent.Usage(step, 10, 5),
            ),
            events,
        )
        // Terminal replay fields are kept as raw JSON, exactly as streamed.
        assertEquals(
            ConsoleApi.json.parseToJsonElement(
                """[{"format":"openai-responses-v1","index":0,"type":"reasoning.text","text":"why","opencode":{"position":0}}]""",
            ),
            collector.replayDetails,
        )
        assertNull(collector.replayMetadata)
        assertEquals("Hello", collector.text.toString())
        assertEquals("thinking", collector.reasoning.toString())
        val calls = collector.assembledCalls()
        assertEquals(listOf("call_1"), calls.map { it.id })
        assertEquals("""{"words":["张三"]}""", calls.single().arguments)
    }

    @Test
    fun collectorContinuesLatestCallAndEmitsFinishOnce() {
        val collector = TurnCollector(1)
        fun chunk(payload: String) = ConsoleApi.json.decodeFromString<ChatChunk>(payload)

        // A continuation fragment before any call exists is ignored.
        var events = collector.apply(
            chunk("""{"choices":[{"index":0,"delta":{"tool_calls":[{"function":{"arguments":"x"}}]}}]}"""),
        )
        assertTrue(events.isEmpty())
        assertTrue(collector.assembledCalls().isEmpty())

        // A sparse index opens the call; a fragment without index or id
        // continues the most recent actual key, not size minus one.
        events += collector.apply(
            chunk(
                """{"choices":[{"index":0,"delta":{"tool_calls":[""" +
                    """{"index":7,"id":"call_7","type":"function","function":{"name":"submit_hotwords","arguments":"a"}}]}}]}""",
            ),
        )
        events += collector.apply(
            chunk("""{"choices":[{"index":0,"delta":{"tool_calls":[{"function":{"arguments":"b"}}]}}]}"""),
        )
        // A repeated finish frame echoes nothing: the tool calls and the
        // Finished event each fire once, on the first reason.
        events += collector.apply(chunk("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""))
        events += collector.apply(chunk("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""))

        assertEquals(
            listOf(
                AgentEvent.ToolCall(1, "call_7", "submit_hotwords", "ab"),
                AgentEvent.Finished(1, "tool_calls"),
            ),
            events,
        )
        assertEquals("tool_calls", collector.finished())
        assertEquals(1, collector.assembledCalls().size)
    }
}
