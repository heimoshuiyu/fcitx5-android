/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.HotwordAgentEventLog
import org.fcitx.fcitx5.android.data.voice.HotwordAgentManager
import org.fcitx.fcitx5.android.data.voice.db.HotwordAgentRun
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.resources.styledColorSL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HotwordAgentRunDetailFragment : Fragment() {

    private val args by lazyRoute<SettingsRoute.HotwordAgentRunDetail>()

    private val runId: Long by lazy { args.runId }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

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
        var round: LinearLayout = container
        var renderedStep: Int? = null
        var index = 0
        while (index < events.size) {
            val event = events[index++]
            var content = event.content
            if (event.kind == HotwordAgentEventLog.KIND_REASONING ||
                event.kind == HotwordAgentEventLog.KIND_TEXT
            ) {
                // A delta can be only whitespace, so check for blank content after joining.
                content = buildString {
                    append(event.content)
                    while (index < events.size) {
                        val next = events[index]
                        if (next.step != event.step || next.kind != event.kind) break
                        append(next.content)
                        index++
                    }
                }
                if (content.isBlank()) continue
            }

            if (renderedStep != event.step) {
                round = addRound(container, event.step)
                renderedStep = event.step
            }
            when (event.kind) {
                HotwordAgentEventLog.KIND_REASONING ->
                    addDisclosure(round, getString(R.string.hotword_agent_event_reasoning), content)
                HotwordAgentEventLog.KIND_TEXT ->
                    round.addView(traceText(content, 17f).apply {
                        setPadding(0, context.dp(4), 0, context.dp(8))
                        setTextIsSelectable(true)
                    })
                HotwordAgentEventLog.KIND_TOOL_CALL -> {
                    val name = event.toolName.ifBlank { "submit_hotwords" }
                    val arguments = prettyJson(event.argumentsJson)
                    addDisclosure(
                        round,
                        toolInputTitle(name),
                        if (arguments.isBlank()) name else "$name\n$arguments",
                        code = true,
                    )
                }
                HotwordAgentEventLog.KIND_TOOL_RESULT -> {
                    val payload = parseJson(content)
                    val result = payload as? JsonObject
                    val failed = (result?.get("error") as? JsonPrimitive)?.isString == true
                    val title = when {
                        failed -> R.string.hotword_agent_event_tool_error
                        result?.isHotwordResult() == true -> R.string.hotword_agent_event_tool_success
                        else -> R.string.hotword_agent_event_tool_result
                    }
                    addDisclosure(
                        round,
                        getString(title),
                        prettyJson(content, payload?.takeIf { it is JsonObject || it is JsonArray }),
                        code = true,
                        error = failed,
                    )
                }
                HotwordAgentEventLog.KIND_USAGE -> {
                    val payload = parseJson(event.usageJson)
                    val usage = payload as? JsonObject
                    val input = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull
                    val output = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull
                    val title = if (input != null && output != null) {
                        getString(R.string.hotword_agent_event_usage_summary, input, output)
                    } else {
                        getString(R.string.hotword_agent_event_usage)
                    }
                    addDisclosure(
                        round, title, prettyJson(event.usageJson, payload), code = true, quiet = true,
                    )
                }
                HotwordAgentEventLog.KIND_FINISHED -> {
                    val label = if (content.isBlank()) {
                        getString(R.string.hotword_agent_event_finished)
                    } else {
                        getString(R.string.hotword_agent_event_finish_reason, content)
                    }
                    round.addView(traceText(label, 12f, secondary = true).apply {
                        setTextIsSelectable(true)
                    })
                }
                else -> {
                    round.addView(traceText(event.kind, 13f, secondary = true))
                    if (content.isNotBlank()) {
                        round.addView(traceText(content, 15f).apply { setTextIsSelectable(true) })
                    }
                }
            }
        }
        if (container.childCount == 0) {
            container.addView(traceText(getString(R.string.hotword_agent_run_events_none), 14f, secondary = true))
        }
    }

    private fun addRound(container: LinearLayout, step: Int): LinearLayout {
        val context = container.context
        val track = LinearLayout(context).apply { setBaselineAligned(false) }
        track.addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.hotword_agent_trace_rail))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(context.dp(12), 0, 0, 0)
        }
        track.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(track, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { if (container.childCount > 0) topMargin = context.dp(18) })
        val title = if (step != 0) {
            getString(R.string.hotword_agent_event_round, step)
        } else {
            getString(R.string.hotword_agent_event_legacy_round)
        }
        body.addView(traceText(title, 15f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.hotword_agent_trace_accent))
            setPadding(0, 0, 0, context.dp(6))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        return body
    }

    private fun addDisclosure(
        round: LinearLayout,
        title: CharSequence,
        content: String,
        code: Boolean = false,
        error: Boolean = false,
        quiet: Boolean = false,
    ) {
        val context = round.context
        val color = if (error) {
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.hotword_agent_trace_error))
        } else {
            context.styledColorSL(if (quiet || !code) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary)
        }
        if (content.isBlank()) {
            round.addView(traceText(title, if (quiet) 12f else 14f).apply { setTextColor(color) })
            return
        }

        val toggle = AppCompatButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            text = title
            textSize = if (quiet) 12f else 14f
            setTextColor(color)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPaddingRelative(0, context.dp(4), context.dp(36), context.dp(4))
            minHeight = context.dp(48)
            minimumHeight = minHeight
        }
        val arrow = ImageView(context).apply {
            imageTintList = color
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = FrameLayout(context).apply {
            addView(toggle, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(arrow, FrameLayout.LayoutParams(
                context.dp(24), context.dp(24), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = context.dp(4) })
        }
        val details = traceText(content, if (code) 13f else 14f, secondary = !code).apply {
            visibility = View.GONE
            setTextIsSelectable(true)
            if (code) {
                typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.hotword_agent_trace_payload))
                    cornerRadius = context.dp(6).toFloat()
                }
                val padding = context.dp(10)
                setPadding(padding, padding, padding, padding)
            } else {
                setPadding(0, 0, context.dp(4), context.dp(8))
            }
        }
        fun setExpanded(expanded: Boolean) {
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            arrow.setImageResource(
                if (expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24
            )
            ViewCompat.setStateDescription(toggle, getString(
                if (expanded) R.string.hotword_agent_event_expanded else R.string.hotword_agent_event_collapsed
            ))
        }
        setExpanded(false)
        toggle.setOnClickListener { setExpanded(details.visibility != View.VISIBLE) }
        round.addView(row)
        round.addView(details)
    }

    private fun toolInputTitle(name: String): CharSequence = SpannableStringBuilder().apply {
        val context = requireContext()
        append(getString(R.string.hotword_agent_event_tool_call))
        setSpan(RelativeSizeSpan(0.86f), 0, length, SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(context.styledColor(android.R.attr.textColorSecondary)),
            0, length, SPAN_EXCLUSIVE_EXCLUSIVE)
        append('\n')
        val start = length
        append(name)
        setSpan(TypefaceSpan("monospace"), start, length, SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun traceText(value: CharSequence, size: Float, secondary: Boolean = false) =
        TextView(requireContext()).apply {
            text = value
            textSize = size
            setTextColor(context.styledColorSL(
                if (secondary) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary
            ))
            setLineSpacing(context.dp(2).toFloat(), 1f)
        }

    /** Only recognize the result shape emitted by the hotword filter as success. */
    private fun JsonObject.isHotwordResult(): Boolean = isNotEmpty() && all { (key, value) ->
        value is JsonArray && when (key) {
            "added" -> value.all { (it as? JsonPrimitive)?.isString == true }
            "skipped" -> value.all {
                it is JsonObject && (it["word"] as? JsonPrimitive)?.isString == true &&
                    (it["reason"] as? JsonPrimitive)?.isString == true
            }
            else -> false
        }
    }

    private fun parseJson(raw: String): JsonElement? =
        if (raw.isBlank()) null else runCatching { Json.parseToJsonElement(raw) }.getOrNull()

    private fun prettyJson(raw: String, element: JsonElement? = parseJson(raw)): String =
        element?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: raw
}
