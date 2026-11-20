/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.db.HotwordAgentDao
import org.fcitx.fcitx5.android.data.voice.db.HotwordAgentRun
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionDatabase
import org.fcitx.fcitx5.android.input.voice.agent.AgentEvent
import org.fcitx.fcitx5.android.input.voice.agent.ConsoleAgentClient
import timber.log.Timber
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owner of the hotword learning agent runs.
 *
 * Each learning input is persisted as a pending row first, then the agent
 * run is attempted best-effort. A row that survives a process death stays
 * pending (or is swept back from `running` on the next init) and can be run
 * manually from the records UI.
 */
object HotwordAgentManager : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {

    private const val TAG = "hotwordAgent"
    private const val MAX_RUNS = 100
    private const val RETENTION_DAYS = 30L
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** Readable deferral reasons, kept in English like backend error messages. */
    private const val DEFER_SIGN_IN = "Sign in to OpenCode Console first"
    private const val DEFER_SELECT_MODEL = "Select a hotword learning model first"

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var dao: HotwordAgentDao
    private val initialized: Boolean get() = ::dao.isInitialized

    /** Serializes read-modify-write cycles on the auto hotword list. */
    private val applyMutex = Mutex()

    fun init(db: TranscriptionDatabase) {
        if (initialized) return
        dao = db.hotwordAgentDao()
        launch(Dispatchers.IO) {
            val stale = dao.resetStaleRunning()
            if (stale > 0) Timber.d("$TAG: reset $stale stale running runs to pending")
        }
    }

    /**
     * Persist the learning input as a pending row, then try the run
     * best-effort. Losing the race with process death only leaves the row
     * pending for a manual run.
     */
    fun enqueue(originalText: String, finalText: String) {
        if (!initialized) {
            Timber.d("$TAG: not initialized, dropping learning input")
            return
        }
        launch {
            val id = withContext(Dispatchers.IO) {
                dao.insert(
                    HotwordAgentRun(
                        status = HotwordAgentRun.STATUS_PENDING,
                        originalText = originalText,
                        finalText = finalText,
                    )
                )
            }
            Timber.d("$TAG: enqueued run id=$id")
            prune()
            tryRun(id)
        }
    }

    /** Best-effort automatic run; returns immediately. */
    fun tryRun(id: Long) {
        if (!initialized) return
        launch { runClaimed(id) }
    }

    /**
     * Manual run/retry from the records UI. Suspends until the attempt
     * finishes; the run lives in this manager's scope, so leaving the screen
     * only stops the waiting, not the run.
     */
    suspend fun runAndWait(id: Long) {
        if (!initialized) return
        val job = launch { runClaimed(id) }
        job.join()
    }

    suspend fun getPage(limit: Int, offset: Int): List<HotwordAgentRun> {
        if (!initialized) return emptyList()
        return withContext(Dispatchers.IO) { dao.getPage(limit, offset) }
    }

    suspend fun getById(id: Long): HotwordAgentRun? {
        if (!initialized) return null
        return withContext(Dispatchers.IO) { dao.getById(id) }
    }

    suspend fun deleteById(id: Long) {
        if (!initialized) return
        withContext(Dispatchers.IO) { dao.deleteById(id) }
    }

    suspend fun deleteAll() {
        if (!initialized) return
        withContext(Dispatchers.IO) { dao.deleteAll() }
    }

    /**
     * Runs the agent once for a claimable row. A missing Console session or
     * agent model defers the run instead of failing it: the row stays as it
     * was (pending/failed), no attempt is counted, and the reason is
     * recorded for the records UI — automatic runs produce no failed noise
     * while the feature is not configured. Process-scope cancellation is NOT
     * a failure: the row stays `running` and the next init sweeps it back
     * to pending.
     */
    private suspend fun runClaimed(id: Long) = withContext(Dispatchers.IO) {
        val prefs = AppPrefs.getInstance().voiceInput
        val token = prefs.consoleAccessToken.getValue().trim()
        val modelId = prefs.consoleAgentModelId.getValue().trim()
        if (token.isEmpty() || modelId.isEmpty()) {
            val reason = if (token.isEmpty()) DEFER_SIGN_IN else DEFER_SELECT_MODEL
            Timber.d("$TAG: run id=$id deferred ($reason)")
            dao.defer(id, reason)
            return@withContext
        }
        if (dao.claim(id) == 0) {
            Timber.d("$TAG: run id=$id not claimable")
            return@withContext
        }
        val row = dao.getById(id) ?: return@withContext
        val startedAt = System.currentTimeMillis()
        val events: MutableList<HotwordAgentEventLog> =
            Collections.synchronizedList(mutableListOf<HotwordAgentEventLog>())
        val appliedWords: MutableList<String> =
            Collections.synchronizedList(mutableListOf<String>())
        var failure: String? = null
        try {
            ConsoleAgentClient(
                getAccessToken = { prefs.consoleAccessToken.getValue() },
                getRefreshToken = { prefs.consoleRefreshToken.getValue() },
                getOrgId = { prefs.consoleOrgId.getValue() },
                getModel = { modelId },
                getVariant = { prefs.consoleAgentVariantId.getValue() },
                onTokensUpdated = { access, refresh ->
                    prefs.consoleAccessToken.setValue(access)
                    prefs.consoleRefreshToken.setValue(refresh)
                },
                onSessionExpired = {
                    prefs.consoleAccessToken.setValue("")
                    prefs.consoleRefreshToken.setValue("")
                },
            ).run(
                originalText = row.originalText,
                finalText = row.finalText,
                existingHotwords = currentHotwords(prefs),
                onEvent = { event -> events.add(toEventLog(event)) },
                submitHotwords = { words -> submitHotwords(prefs, words, appliedWords) },
            )
        } catch (e: CancellationException) {
            Timber.d("$TAG: run id=$id cancelled; leaving status=running")
            throw e
        } catch (e: Throwable) {
            failure = readableError(e)
            Timber.w(e, "$TAG: run id=$id failed")
        }
        dao.finish(
            id = id,
            status = if (failure == null) HotwordAgentRun.STATUS_SUCCEEDED
            else HotwordAgentRun.STATUS_FAILED,
            eventsJson = json.encodeToString(events.toList()),
            submittedWords = appliedWords.toList().joinToString("\n"),
            error = failure.orEmpty(),
            modelId = modelId,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /**
     * Apply words proposed by the agent. The filter is authoritative: only
     * words passing length/count checks and dedup against both hotword lists
     * ever reach [org.fcitx.fcitx5.android.data.prefs.AppPrefs.VoiceInput.autoHotwordsList].
     * Returns the decision as JSON so the agent can see what landed.
     */
    private suspend fun submitHotwords(
        prefs: AppPrefs.VoiceInput,
        candidates: List<String>,
        appliedWords: MutableList<String>,
    ): String = applyMutex.withLock {
        val manualWords = prefs.hotwords.getValue()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val autoLines = prefs.autoHotwordsList.getValue()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val result = HotwordWordFilter.filter(
            candidates = candidates,
            manualWords = manualWords,
            existingAutoWords = autoLines.toSet(),
        )
        if (result.added.isNotEmpty()) {
            autoLines.addAll(result.added)
            prefs.autoHotwordsList.setValue(autoLines.joinToString("\n"))
            appliedWords.addAll(result.added)
            Timber.d("$TAG: agent added hotwords ${result.added}")
        }
        json.encodeToString(result)
    }

    /**
     * Convert a protocol [AgentEvent] into the stable log entry. The
     * branches mirror the sealed [AgentEvent] hierarchy exactly.
     */
    private fun toEventLog(event: AgentEvent): HotwordAgentEventLog = when (event) {
        is AgentEvent.Reasoning -> HotwordAgentEventLog(
            step = event.step,
            kind = HotwordAgentEventLog.KIND_REASONING,
            content = event.text,
        )
        is AgentEvent.Text -> HotwordAgentEventLog(
            step = event.step,
            kind = HotwordAgentEventLog.KIND_TEXT,
            content = event.text,
        )
        is AgentEvent.ToolCall -> HotwordAgentEventLog(
            step = event.step,
            kind = HotwordAgentEventLog.KIND_TOOL_CALL,
            toolName = event.name,
            argumentsJson = event.arguments,
        )
        is AgentEvent.ToolResult -> HotwordAgentEventLog(
            step = event.step,
            kind = HotwordAgentEventLog.KIND_TOOL_RESULT,
            content = event.content,
        )
        is AgentEvent.Usage -> HotwordAgentEventLog(
            step = event.step,
            kind = HotwordAgentEventLog.KIND_USAGE,
            usageJson = buildJsonObject {
                put("prompt_tokens", event.promptTokens)
                put("completion_tokens", event.completionTokens)
            }.toString(),
        )
        is AgentEvent.Finished -> HotwordAgentEventLog(
            step = event.step,
            kind = HotwordAgentEventLog.KIND_FINISHED,
            content = event.finishReason.orEmpty(),
        )
    }

    private fun currentHotwords(prefs: AppPrefs.VoiceInput): List<String> {
        val manual = prefs.hotwords.getValue()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val auto = prefs.autoHotwordsList.getValue()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (manual + auto).distinct()
    }

    private suspend fun prune() {
        withContext(Dispatchers.IO) {
            dao.deleteTerminalOlderThan(System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS)
            dao.trimTerminalTo(MAX_RUNS)
        }
    }

    private fun readableError(e: Throwable): String {
        val message = e.message?.trim().orEmpty()
        return message.ifEmpty { e.javaClass.simpleName }
    }
}
