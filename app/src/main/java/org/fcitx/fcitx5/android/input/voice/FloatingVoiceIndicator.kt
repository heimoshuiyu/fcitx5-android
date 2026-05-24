/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

/**
 * A lightweight floating indicator that overlays on top of the keyboard.
 *
 * States:
 * - RECORDING: waveform visualization + "Listening..."
 * - TRANSCRIBING: spinner + "Transcribing..."
 * - RETRY_AVAILABLE: error message + [Retry] [Cancel] buttons
 * - HIDDEN: not visible
 */
class FloatingVoiceIndicator(
    private val context: android.content.Context,
    private val theme: Theme
) {

    enum class State {
        RECORDING,
        TRANSCRIBING,
        RETRY_AVAILABLE,
        HIDDEN
    }

    private var state: State = State.HIDDEN

    var onRetryClicked: (() -> Unit)? = null
    var onCancelClicked: (() -> Unit)? = null
    var onCancelTranscriptionClicked: (() -> Unit)? = null

    // === Recording: waveform ===

    val waveformView = WaveformView(context).apply {
        setBarColor(theme.altKeyTextColor)
        layoutParams = LinearLayout.LayoutParams(
            0,
            context.dp(32)
        ).apply {
            weight = 1f
        }
    }

    // === Transcribing: spinner ===

    private val progressBar = ProgressBar(context).apply {
        indeterminateTintList = android.content.res.ColorStateList.valueOf(theme.altKeyTextColor)
        visibility = View.GONE
        val s = context.dp(24)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            gravity = Gravity.CENTER
            marginStart = context.dp(4)
            marginEnd = context.dp(4)
        }
    }

    // === Status text ===

    private val statusLabel = TextView(context).apply {
        setTextColor(theme.altKeyTextColor)
        textSize = 13f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = context.dp(8)
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    // === Retry: buttons ===

    private val retryButton = Button(context).apply {
        text = context.getString(R.string.voice_input_retry)
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        setPadding(context.dp(12), 0, context.dp(12), 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(8).toFloat()
            setStroke(1, theme.altKeyTextColor)
            setColor(0x00000000) // transparent
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(32)
        ).apply {
            marginStart = context.dp(8)
            gravity = Gravity.CENTER_VERTICAL
        }
        setOnClickListener { onRetryClicked?.invoke() }
    }

    private val cancelButton = Button(context).apply {
        text = context.getString(R.string.voice_input_cancel)
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        setPadding(context.dp(12), 0, context.dp(12), 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(8).toFloat()
            setStroke(1, theme.altKeyTextColor)
            setColor(0x00000000)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(32)
        ).apply {
            marginStart = context.dp(4)
            gravity = Gravity.CENTER_VERTICAL
        }
        setOnClickListener { onCancelClicked?.invoke() }
    }

    private val retryButtonGroup = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = context.dp(8)
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(retryButton)
        addView(cancelButton)
    }

    // === Transcribing: cancel button ===

    private val cancelTranscriptionButton = Button(context).apply {
        text = context.getString(R.string.voice_input_cancel)
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        setPadding(context.dp(12), 0, context.dp(12), 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(8).toFloat()
            setStroke(1, theme.altKeyTextColor)
            setColor(0x00000000)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            context.dp(32)
        ).apply {
            marginStart = context.dp(8)
            gravity = Gravity.CENTER_VERTICAL
        }
        visibility = View.GONE
        setOnClickListener { onCancelTranscriptionClicked?.invoke() }
    }

    // === Container ===

    private val bgDrawable = GradientDrawable().apply {
        cornerRadius = context.dp(16).toFloat()
        setColor(theme.backgroundColor)
        setStroke(1, theme.altKeyTextColor)
        alpha = 230
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bgDrawable
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            context.dp(48)
        ).apply {
            gravity = Gravity.BOTTOM
            marginStart = context.dp(8)
            marginEnd = context.dp(8)
            bottomMargin = context.dp(4)
        }
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        addView(progressBar)
        addView(waveformView)
        addView(statusLabel)
        addView(cancelTranscriptionButton)
        addView(retryButtonGroup)
        visibility = View.GONE
    }

    fun showRecording() {
        state = State.RECORDING
        view.visibility = View.VISIBLE
        waveformView.visibility = View.VISIBLE
        waveformView.setRecording(true)
        progressBar.visibility = View.GONE
        cancelTranscriptionButton.visibility = View.GONE
        retryButtonGroup.visibility = View.GONE
        statusLabel.text = context.getString(R.string.voice_input_listening)
    }

    fun showTranscribing() {
        state = State.TRANSCRIBING
        waveformView.visibility = View.GONE
        waveformView.setRecording(false)
        progressBar.visibility = View.VISIBLE
        cancelTranscriptionButton.visibility = View.VISIBLE
        retryButtonGroup.visibility = View.GONE
        statusLabel.text = context.getString(R.string.voice_input_transcribing)
    }

    fun showRetry(message: String? = null) {
        state = State.RETRY_AVAILABLE
        waveformView.visibility = View.GONE
        waveformView.setRecording(false)
        progressBar.visibility = View.GONE
        cancelTranscriptionButton.visibility = View.GONE
        retryButtonGroup.visibility = View.VISIBLE
        statusLabel.text = message ?: context.getString(R.string.voice_input_error)
    }

    fun hide() {
        state = State.HIDDEN
        waveformView.setRecording(false)
        progressBar.visibility = View.GONE
        cancelTranscriptionButton.visibility = View.GONE
        retryButtonGroup.visibility = View.GONE
        view.visibility = View.GONE
    }

    fun isShowing(): Boolean = state != State.HIDDEN
}
