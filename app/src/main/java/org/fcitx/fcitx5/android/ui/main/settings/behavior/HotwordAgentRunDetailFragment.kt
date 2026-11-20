/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.HotwordAgentEventLog
import org.fcitx.fcitx5.android.data.voice.HotwordAgentManager
import org.fcitx.fcitx5.android.data.voice.db.HotwordAgentRun
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HotwordAgentRunDetailFragment : Fragment() {

    private val args by lazyRoute<SettingsRoute.HotwordAgentRunDetail>()

    private val runId: Long by lazy { args.runId }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_hotword_agent_run_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadRun(view)
    }

    override fun onResume() {
        super.onResume()
        // A run may have finished (or been retried) while we were away.
        view?.let { loadRun(it) }
    }

    private fun loadRun(view: View) {
        lifecycleScope.launch {
            val run = HotwordAgentManager.getById(runId)

            if (run == null) {
                view.findViewById<TextView>(R.id.text_error)?.apply {
                    setText(R.string.hotword_agent_run_not_found)
                    visibility = View.VISIBLE
                }
                view.findViewById<ScrollView>(R.id.scroll_view)?.visibility = View.GONE
                return@launch
            }

            view.findViewById<ScrollView>(R.id.scroll_view)?.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.text_error)?.visibility = View.GONE

            view.findViewById<TextView>(R.id.text_time)?.text =
                dateFormat.format(Date(run.createdAt))

            view.findViewById<TextView>(R.id.text_status)?.text = statusText(run)

            view.findViewById<TextView>(R.id.text_model)?.text =
                getString(R.string.hotword_agent_run_model, run.modelId.ifBlank { "-" })

            view.findViewById<TextView>(R.id.text_meta)?.text =
                getString(R.string.hotword_agent_run_meta, run.attemptCount, run.durationMs)

            view.findViewById<TextView>(R.id.text_original)?.text =
                run.originalText.ifBlank { getString(R.string.hotword_agent_run_value_empty) }

            view.findViewById<TextView>(R.id.text_final)?.text =
                run.finalText.ifBlank { getString(R.string.hotword_agent_run_value_empty) }

            renderEvents(
                view.findViewById<LinearLayout>(R.id.events_container),
                parseEvents(run.eventsJson),
            )

            view.findViewById<TextView>(R.id.text_words)?.text =
                run.submittedWords.ifBlank { getString(R.string.hotword_agent_run_words_none) }

            // Shows both run failures and deferral reasons on pending rows.
            val errorSection = view.findViewById<View>(R.id.section_error)
            if (run.error.isNotBlank()) {
                errorSection?.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.text_error_detail)?.text = run.error
            } else {
                errorSection?.visibility = View.GONE
            }

            val runButton = view.findViewById<Button>(R.id.button_run)
            val retryable = run.status == HotwordAgentRun.STATUS_PENDING ||
                run.status == HotwordAgentRun.STATUS_FAILED
            runButton?.visibility = if (retryable) View.VISIBLE else View.GONE
            runButton?.setText(
                if (run.status == HotwordAgentRun.STATUS_FAILED) R.string.hotword_agent_run_action_retry
                else R.string.hotword_agent_run_action_run
            )
            runButton?.setOnClickListener {
                lifecycleScope.launch {
                    HotwordAgentManager.runAndWait(runId)
                    loadRun(view)
                }
            }
        }
    }

    private fun statusText(run: HotwordAgentRun): String = when (run.status) {
        HotwordAgentRun.STATUS_PENDING -> getString(R.string.hotword_agent_run_status_pending)
        HotwordAgentRun.STATUS_RUNNING -> getString(R.string.hotword_agent_run_status_running)
        HotwordAgentRun.STATUS_SUCCEEDED -> getString(R.string.hotword_agent_run_status_succeeded)
        else -> getString(R.string.hotword_agent_run_status_failed)
    }

    private fun parseEvents(eventsJson: String): List<HotwordAgentEventLog> {
        if (eventsJson.isBlank()) return emptyList()
        return try {
            json.decodeFromString(eventsJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun renderEvents(container: LinearLayout?, events: List<HotwordAgentEventLog>) {
        container ?: return
        container.removeAllViews()
        if (events.isEmpty()) {
            addEventLine(
                container,
                getString(R.string.hotword_agent_run_events_none),
                secondary = true,
            )
            return
        }
        for (event in events) {
            val label = when (event.kind) {
                HotwordAgentEventLog.KIND_REASONING ->
                    getString(R.string.hotword_agent_event_reasoning, event.step)
                HotwordAgentEventLog.KIND_TEXT ->
                    getString(R.string.hotword_agent_event_text, event.step)
                HotwordAgentEventLog.KIND_TOOL_CALL ->
                    getString(R.string.hotword_agent_event_tool_call, event.step)
                HotwordAgentEventLog.KIND_TOOL_RESULT ->
                    getString(R.string.hotword_agent_event_tool_result, event.step)
                HotwordAgentEventLog.KIND_USAGE ->
                    getString(R.string.hotword_agent_event_usage, event.step)
                HotwordAgentEventLog.KIND_FINISHED ->
                    getString(R.string.hotword_agent_event_finished, event.step)
                else -> event.kind
            }
            addEventLine(container, label, secondary = true)
            when (event.kind) {
                HotwordAgentEventLog.KIND_TOOL_CALL -> {
                    val name = event.toolName.ifBlank { "submit_hotwords" }
                    val arguments = prettyJson(event.argumentsJson)
                    addEventLine(
                        container,
                        if (arguments.isBlank()) name else "$name $arguments",
                        monospace = true,
                    )
                }
                HotwordAgentEventLog.KIND_USAGE -> {
                    addEventLine(container, prettyJson(event.usageJson), monospace = true)
                }
                HotwordAgentEventLog.KIND_FINISHED -> {
                    if (event.content.isNotBlank()) {
                        addEventLine(container, event.content, monospace = true)
                    }
                }
                else -> {
                    if (event.content.isNotBlank()) {
                        addEventLine(container, event.content)
                    }
                }
            }
        }
    }

    private fun addEventLine(
        container: LinearLayout,
        text: String,
        secondary: Boolean = false,
        monospace: Boolean = false,
    ) {
        val textView = TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(requireContext(), android.R.style.TextAppearance_DeviceDefault_Small)
            if (secondary) setTextColor(resolveSecondaryColor())
            if (monospace) typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        container.addView(textView)
    }

    /** A theme attribute may resolve to a color resource or a literal; the
     * picker rows in VoiceInputSettingsFragment resolve it the same way. */
    private fun resolveSecondaryColor(): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun prettyJson(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val pretty = Json { prettyPrint = true }
            val element = Json.parseToJsonElement(raw)
            pretty.encodeToString(JsonElement.serializer(), element)
        } catch (_: Exception) {
            raw
        }
    }
}
