/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.agent

import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.input.voice.ConsoleApi
import org.fcitx.fcitx5.android.input.voice.ConsoleBackend
import org.fcitx.fcitx5.android.input.voice.ConsoleCatalog
import org.fcitx.fcitx5.android.input.voice.ConsoleSession
import org.fcitx.fcitx5.android.input.voice.TranscriptionException

/**
 * One observable moment of the agent loop. [step] is the 1-based model turn
 * the event belongs to; a manager that only records progress can ignore the
 * extra kinds and handle [Reasoning], [Text], [ToolCall] and [ToolResult].
 */
sealed class AgentEvent {
    abstract val step: Int

    /** A piece of the model's visible reasoning, as it streamed. */
    data class Reasoning(override val step: Int, val text: String) : AgentEvent()

    /** A piece of the model's visible answer, as it streamed. */
    data class Text(override val step: Int, val text: String) : AgentEvent()

    /** One tool call the model made, with its arguments fully assembled. */
    data class ToolCall(override val step: Int, val id: String, val name: String, val arguments: String) : AgentEvent()

    /** The content recorded for one tool call; an error content is visible as such. */
    data class ToolResult(override val step: Int, val callId: String, val content: String) : AgentEvent()

    /** The turn's reported token usage, when the stream carried one. */
    data class Usage(override val step: Int, val promptTokens: Int, val completionTokens: Int) : AgentEvent()

    /** The turn's terminal reason (`stop`, `tool_calls`, …). */
    data class Finished(override val step: Int, val finishReason: String?) : AgentEvent()
}

/** A failed agent run with a user-presentable message. */
class AgentException(message: String) : Exception(message)

/**
 * The hotword agent client: a bounded streaming tool loop over the Console's
 * managed OpenAI-compatible endpoint. Every channel — managed, Go and BYOK —
 * is reached on the one fixed Chat Completions URL; the gateway translates
 * to whatever protocol the selected model's endpoints speak, so the client
 * never switches endpoint or protocol.
 *
 * [run] compares the two texts, lets the model call `submit_hotwords` (the
 * callback records the words and returns the tool result content), feeds the
 * result back for the model to finish, and returns normally once the model
 * stops calling tools. Failures — HTTP, stream and session — throw
 * [AgentException] for the caller to record; cancellation propagates.
 */
class ConsoleAgentClient(
    private val getAccessToken: () -> String?,
    private val getRefreshToken: () -> String?,
    private val getOrgId: () -> String?,
    private val getModel: () -> String?,
    private val getVariant: () -> String?,
    private val onTokensUpdated: (String, String) -> Unit,
    private val onSessionExpired: () -> Unit,
    /** The Chat Completions base URL; fixed to the managed endpoint in production. */
    internal val chatBase: String = ConsoleApi.DEFAULT_CHAT_BASE,
    /** The agent model catalog; the per-channel catalogs in production. */
    internal val fetchCatalog: suspend (accessToken: String, orgId: String?) -> List<ConsoleCatalog.ModelEntry> =
        { token, orgId -> ConsoleCatalog.ensure(token, orgId, ConsoleCatalog.Kind.Agent) },
) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Run the hotword agent once. Emits [AgentEvent]s to [onEvent] as the
     * model streams; calls [submitHotwords] with each tool call's words and
     * feeds its returned string back as the tool result (a throwing callback
     * becomes a visible tool error the model can see, never a swallowed
     * cancellation). Returns when the model finishes without calling tools;
     * throws [AgentException] on failure.
     */
    suspend fun run(
        originalText: String,
        finalText: String,
        existingHotwords: List<String>,
        onEvent: (AgentEvent) -> Unit,
        submitHotwords: suspend (List<String>) -> String,
    ) {
        val sessionId = ConsoleBackend.newSessionId()
        try {
            withTimeout(TOTAL_TIMEOUT_MS) {
                val target = resolveTarget()
                val messages = mutableListOf(
                    systemMessage(HotwordPrompt.SYSTEM_PROMPT),
                    userMessage(HotwordPrompt.userContent(originalText, finalText, existingHotwords)),
                )
                var step = 0
                while (true) {
                    step++
                    val turn = ConsoleSession.withRefresh(
                        requireToken(),
                        getRefreshToken,
                        onTokensUpdated,
                    ) { token ->
                        executeTurn(token, target, sessionId, messages, step, onEvent)
                    }
                    val calls = turn.assembledCalls()
                    // No tool call: the turn is the answer, whatever its reason.
                    if (calls.isEmpty()) return@withTimeout
                    messages.add(assistantMessage(turn, calls))
                    for (call in calls) {
                        val (content, isError) = executeTool(call, step, onEvent, submitHotwords)
                        messages.add(toolMessage(call.id, content, isError))
                    }
                    // The words are recorded by the callback, so ending at the
                    // turn bound loses nothing but the model's goodbye.
                    if (step >= HotwordPrompt.MAX_MODEL_TURNS) return@withTimeout
                }
            }
        } catch (e: ConsoleSession.SessionExpiredException) {
            onSessionExpired()
            throw AgentException("The OpenCode session expired. Sign in again in voice settings.")
        } catch (e: ConsoleApi.ConsoleHttpException) {
            // withRefresh already rotated the token once; a 401 that survives
            // the retry means the session itself is broken.
            if (e.status == 401) onSessionExpired()
            throw AgentException(
                if (e.status == 401) "The OpenCode session expired. Sign in again in voice settings."
                else e.message ?: "Request failed (HTTP ${e.status}).",
            )
        } catch (e: java.io.IOException) {
            // Turns convert their own stream errors; one reaching here leaked
            // from the catalog or config fetch, and is a network failure the
            // same way. Cancellation still propagates untouched.
            currentCoroutineContext().ensureActive()
            throw AgentException("Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: TimeoutCancellationException) {
            // An outer cancellation must stay a cancellation, not a timeout.
            currentCoroutineContext().ensureActive()
            throw AgentException("The hotword agent timed out. Please try again.")
        }
    }

    /** The model entry the persisted selection names, on the fixed endpoint. */
    private suspend fun resolveTarget(): Target = ConsoleSession.withRefresh(
        requireToken(),
        getRefreshToken,
        onTokensUpdated,
    ) { token ->
        val orgId = getOrgId()?.takeIf { it.isNotBlank() }
        val selected = getModel()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AgentException("No hotword agent model selected. Open voice settings to choose one.")
        val entry = ConsoleCatalog.findById(fetchCatalog(token, orgId), selected)
            ?: throw AgentException("Model \"$selected\" is not available. Open voice settings to choose another one.")
        val resolved = try {
            ConsoleCatalog.resolve(entry, getVariant().orEmpty())
        } catch (e: TranscriptionException) {
            throw AgentException(e.message ?: "The selected model variant is not available.")
        }
        Target(
            wireModel = ConsoleCatalog.agentWireModel(entry),
            url = chatBase.trimEnd('/') + "/chat/completions",
            orgId = orgId,
            headers = resolved.headers,
            bodyOverlay = resolved.bodyOverlay,
        )
    }

    /** Stream one model turn and return its aggregated collector. */
    private suspend fun executeTurn(
        token: String,
        target: Target,
        sessionId: String,
        messages: List<JsonObject>,
        step: Int,
        onEvent: (AgentEvent) -> Unit,
    ): TurnCollector {
        val body = ConsoleCatalog.mergeConsoleBody(
            buildJsonObject {
                put("model", target.wireModel)
                put("messages", buildJsonArray { messages.forEach { add(it) } })
                put("tools", buildJsonArray { add(HotwordPrompt.toolSchema()) })
                put("tool_choice", "auto")
                put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
            },
            target.bodyOverlay,
        )
        val request = Request.Builder()
            .url(target.url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                // The channel's own headers first; the gateway's headers are
                // set again after them, so a BYOK or Go overlay naming the
                // same header can never replace the credential this request
                // rides on.
                target.headers.forEach { (name, value) -> header(name, value) }
                header("Authorization", "Bearer $token")
                header("User-Agent", "fcitx5-android/${BuildConfig.VERSION_NAME}")
                header("x-opencode-client", "fcitx5-android")
                header("x-opencode-project", "global")
                header("x-opencode-session", sessionId)
                target.orgId?.let { header("x-org-id", it) }
            }
            .build()
        val call = httpClient.newCall(request)
        val collector = TurnCollector(step)
        try {
            withContext(Dispatchers.IO) {
                call.executeAsync().use { response ->
                    if (response.code == 401) throw ConsoleApi.ConsoleHttpException(401, "Unauthorized")
                    if (!response.isSuccessful) {
                        throw ConsoleApi.ConsoleHttpException(
                            response.code,
                            parseError(response.body.string(), response.code),
                        )
                    }
                    consumeStream(response.body.source(), collector, onEvent)
                }
            }
        } catch (e: java.io.IOException) {
            // A cancelled call — coroutine cancellation or the loop's timeout —
            // surfaces as an IOException from the blocked read; let the actual
            // cancellation propagate instead of masking it as a network error.
            currentCoroutineContext().ensureActive()
            throw AgentException("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
        if (collector.finished() == null) {
            throw AgentException("The model stream ended without a completed response.")
        }
        return collector
    }

    /** Read the SSE stream to its `[DONE]` or EOF, driving the collector. */
    private fun consumeStream(
        source: okio.BufferedSource,
        collector: TurnCollector,
        onEvent: (AgentEvent) -> Unit,
    ) {
        val parser = ChatSse.Parser()
        var done = false
        while (!done) {
            val line = source.readUtf8Line() ?: break
            for (event in parser.feed(line)) {
                if (handleEvent(event, collector, onEvent)) {
                    done = true
                    break
                }
            }
        }
        if (!done) {
            for (event in parser.finish()) {
                if (handleEvent(event, collector, onEvent)) break
            }
        }
    }

    /** Handle one dispatched event; true when the stream is over ([DONE]). */
    private fun handleEvent(
        event: ChatSse.Event,
        collector: TurnCollector,
        onEvent: (AgentEvent) -> Unit,
    ): Boolean {
        if (event.data == "[DONE]") return true
        val chunk = runCatching { ConsoleApi.json.decodeFromString<ChatChunk>(event.data) }.getOrNull()
            ?: throw AgentException("The model stream sent a malformed chunk.")
        chunk.error?.let { error ->
            throw AgentException(error.message?.takeIf { it.isNotBlank() } ?: "The model stream reported an error.")
        }
        collector.apply(chunk).forEach(onEvent)
        return false
    }

    /** Run one tool call; returns the result content and whether it failed. */
    private suspend fun executeTool(
        call: TurnCollector.AssembledCall,
        step: Int,
        onEvent: (AgentEvent) -> Unit,
        submitHotwords: suspend (List<String>) -> String,
    ): Pair<String, Boolean> {
        val (content, isError) = when {
            call.name != HotwordPrompt.TOOL_NAME ->
                toolError("Unknown tool \"${call.name}\"; only ${HotwordPrompt.TOOL_NAME} is available.") to true
            else -> {
                val words = HotwordPrompt.parseWords(call.arguments)
                if (words == null) {
                    toolError(
                        "Arguments must name a bounded word list: " +
                            "{\"words\": [\"…\"]} with 1..${HotwordPrompt.MAX_WORDS} non-empty words " +
                            "of at most ${HotwordPrompt.MAX_WORD_LENGTH} characters.",
                    ) to true
                } else {
                    try {
                        submitHotwords(words) to false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        toolError(e.message ?: "The hotword tool failed.") to true
                    }
                }
            }
        }
        onEvent(AgentEvent.ToolResult(step, call.id, content))
        return content to isError
    }

    /** The assistant turn the next request replays, with its continuation fields. */
    private fun assistantMessage(turn: TurnCollector, calls: List<TurnCollector.AssembledCall>): JsonObject =
        buildJsonObject {
            put("role", "assistant")
            if (turn.text.isNotEmpty()) put("content", turn.text.toString())
            put("tool_calls", buildJsonArray {
                calls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            })
                        },
                    )
                }
            })
            turn.replayDetails?.let { put("reasoning_details", it) }
            turn.replayMetadata?.let { put("provider_metadata", it) }
        }

    private fun toolMessage(callId: String, content: String, isError: Boolean): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", callId)
        put("content", content)
        if (isError) put("is_error", true)
    }

    private fun systemMessage(text: String): JsonObject = buildJsonObject {
        put("role", "system")
        put("content", text)
    }

    private fun userMessage(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    private fun toolError(message: String): String = buildJsonObject { put("error", message) }.toString()

    private fun parseError(body: String, status: Int): String {
        val error = runCatching { ConsoleApi.json.decodeFromString<WireError>(body).error }.getOrNull()
        return error?.message?.takeIf { it.isNotBlank() } ?: "Request failed (HTTP $status)"
    }

    private fun requireToken(): String =
        getAccessToken()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AgentException("Not signed in. Open voice settings to sign in to OpenCode Console.")

    /** Everything one turn needs to reach the fixed endpoint. */
    private class Target(
        val wireModel: String,
        val url: String,
        val orgId: String?,
        val headers: Map<String, String>,
        val bodyOverlay: JsonObject,
    )

    companion object {
        /** The whole loop's wall clock, all turns and tool callbacks included. */
        const val TOTAL_TIMEOUT_MS = 180_000L
    }
}
