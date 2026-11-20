/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One `chat.completion.chunk` frame of the agent's stream. */
@Serializable
internal data class ChatChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChatUsage? = null,
    val error: WireErrorBody? = null,
) {
    @Serializable
    data class ChunkChoice(
        val index: Int = 0,
        val delta: JsonObject? = null,
        val finish_reason: String? = null,
    )
}

/** The terminal usage frame; content chunks carry `usage: null` instead. */
@Serializable
internal data class ChatUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
)

/** The OpenAI error envelope, on an HTTP error body or an in-stream error frame. */
@Serializable
internal data class WireError(val error: WireErrorBody? = null)

@Serializable
internal data class WireErrorBody(
    val message: String? = null,
    val type: String? = null,
)

/**
 * Aggregates one model turn's stream into [AgentEvent]s and the state the
 * agent loop replays: text and reasoning deltas as they arrive, tool calls
 * assembled across fragments (the first fragment carries the call's id and
 * name, the following ones append argument text), and the continuation
 * fields the gateway puts on the final delta — `reasoning_details` or
 * `provider_metadata` — kept as raw JSON to copy verbatim into the next
 * request's assistant message.
 */
internal class TurnCollector(private val step: Int) {

    /** One assembled tool call of this turn, ready to execute and replay. */
    class AssembledCall(val id: String, val name: String, val arguments: String)

    private class PendingCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    val text = StringBuilder()
    val reasoning = StringBuilder()
    private val calls = LinkedHashMap<Int, PendingCall>()
    private var finishReason: String? = null
    private var toolCallEventsEmitted = false
    private var finishedEmitted = false
    private var usageEmitted = false

    /** `reasoning_details` from the final delta, as the stream spelled it. */
    var replayDetails: JsonElement? = null
        private set

    /** `provider_metadata` from the final delta, as the stream spelled it. */
    var replayMetadata: JsonElement? = null
        private set

    /** Apply one chunk; returns the events it produced, in arrival order. */
    fun apply(chunk: ChatChunk): List<AgentEvent> {
        val events = ArrayList<AgentEvent>()
        for (choice in chunk.choices) {
            val delta = choice.delta
            if (delta != null) applyDelta(delta, events)
            if (choice.finish_reason != null) {
                emitToolCalls(events)
                // A repeated finish frame echoes nothing: the gateway sends
                // one, and a native upstream that repeats itself must not
                // double-record the turn's terminal state.
                if (!finishedEmitted) {
                    finishedEmitted = true
                    finishReason = choice.finish_reason
                    events.add(AgentEvent.Finished(step, choice.finish_reason))
                }
            }
        }
        val usage = chunk.usage
        if (usage != null && !usageEmitted) {
            usageEmitted = true
            events.add(
                AgentEvent.Usage(step, usage.prompt_tokens ?: 0, usage.completion_tokens ?: 0),
            )
        }
        return events
    }

    /** The turn's terminal reason, or null when the stream never finished. */
    fun finished(): String? = finishReason

    /** The calls the model made this turn, in declaration order. */
    fun assembledCalls(): List<AssembledCall> = calls.values
        .filter { it.id != null && it.name != null }
        .map { AssembledCall(it.id!!, it.name!!, it.arguments.toString()) }

    private fun applyDelta(delta: JsonObject, events: MutableList<AgentEvent>) {
        deltaText(delta["content"])?.let {
            text.append(it)
            events.add(AgentEvent.Text(step, it))
        }
        (delta["reasoning_content"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            reasoning.append(it.content)
            events.add(AgentEvent.Reasoning(step, it.content))
        }
        when (val details = delta["reasoning_details"]) {
            null, is JsonNull -> {}
            else -> replayDetails = details
        }
        when (val metadata = delta["provider_metadata"]) {
            null, is JsonNull -> {}
            else -> replayMetadata = metadata
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { fragment ->
            applyToolFragment(fragment)
        }
    }

    private fun applyToolFragment(fragment: JsonElement) {
        val entry = fragment as? JsonObject ?: return
        val index = (entry["index"] as? JsonPrimitive)?.intOrNull
        val id = entry.stringOrNull("id")
        val function = entry["function"] as? JsonObject
        val name = function?.stringOrNull("name")
        val arguments = function?.stringOrNull("arguments")
        // The gateway numbers every fragment; a fragment without an index
        // opens a new call when it carries an id, else continues the most
        // recent actual key — not size minus one, which a sparse index set
        // would miss.
        val key = when {
            index != null -> index
            id != null -> calls.size
            else -> calls.keys.lastOrNull() ?: return
        }
        if (key < 0) return
        val call = calls.getOrPut(key) { PendingCall() }
        if (id != null && call.id == null) call.id = id
        if (name != null && call.name == null) call.name = name
        if (arguments != null) call.arguments.append(arguments)
    }

    private fun emitToolCalls(events: MutableList<AgentEvent>) {
        if (toolCallEventsEmitted) return
        toolCallEventsEmitted = true
        for (call in calls.values) {
            val id = call.id ?: continue
            val name = call.name ?: continue
            events.add(AgentEvent.ToolCall(step, id, name, call.arguments.toString()))
        }
    }

    /** `content` arrives as a string, or as text parts with some upstreams. */
    private fun deltaText(content: JsonElement?): String? = when (content) {
        is JsonPrimitive -> if (content.isString && content.content.isNotEmpty()) content.content else null
        is JsonArray -> content.joinToString("") { part ->
            when (part) {
                is JsonObject -> (part["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                else -> ""
            }
        }.takeIf { it.isNotEmpty() }
        else -> null
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
