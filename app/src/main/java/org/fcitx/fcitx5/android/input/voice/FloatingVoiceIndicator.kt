/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

/** A full-keyboard voice input surface for recording, transcription, and retry states. */
class FloatingVoiceIndicator(
    private val context: android.content.Context,
    private val theme: Theme,
) {

    enum class State {
        RECORDING,
        TRANSCRIBING,
        RETRY_AVAILABLE,
        HIDDEN,
    }

    private var state = State.HIDDEN
    private val disableAnimation = AppPrefs.getInstance().advanced.disableAnimation.getValue()

    var onRetryClicked: (() -> Unit)? = null
    var onCancelClicked: (() -> Unit)? = null
    var onCancelTranscriptionClicked: (() -> Unit)? = null

    val waveformView = WaveformView(context).apply {
        setBarColors(theme.altKeyTextColor, theme.accentKeyBackgroundColor)
        setPadding(context.dp(4), 0, context.dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply {
            topMargin = context.dp(6)
            bottomMargin = context.dp(6)
        }
    }

    private val recordingLabel = TextView(context).apply {
        text = context.getString(R.string.voice_input_listening)
        setTextColor(theme.keyTextColor)
        textSize = 15f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private val releaseHint = TextView(context).apply {
        text = context.getString(R.string.voice_input_release_to_transcribe)
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private val recordingContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(12))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(recordingLabel)
        addView(waveformView)
        addView(releaseHint)
    }

    private val progressBar = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(theme.accentKeyBackgroundColor)
        val size = context.dp(32)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(12)
        }
    }

    private val statusLabel = TextView(context).apply {
        setTextColor(theme.keyTextColor)
        textSize = 15f
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = context.dp(16)
        }
    }

    private fun createActionButton(textResource: Int, onClick: () -> Unit) = Button(context).apply {
        text = context.getString(textResource)
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        setPadding(context.dp(14), 0, context.dp(14), 0)
        minWidth = 0
        minimumWidth = 0
        background = GradientDrawable().apply {
            cornerRadius = context.dp(10).toFloat()
            setStroke(context.dp(1), theme.altKeyTextColor)
            setColor(0x00000000)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            context.dp(38),
        ).apply {
            marginStart = context.dp(4)
            marginEnd = context.dp(4)
        }
        setOnClickListener { onClick() }
    }

    private val retryButton = createActionButton(R.string.voice_input_retry) {
        onRetryClicked?.invoke()
    }

    private val cancelButton = createActionButton(R.string.voice_input_cancel) {
        onCancelClicked?.invoke()
    }

    private val cancelTranscriptionButton = createActionButton(R.string.voice_input_cancel) {
        onCancelTranscriptionClicked?.invoke()
    }

    private val retryButtonGroup = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(retryButton)
        addView(cancelButton)
    }

    private val stateContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(progressBar)
        addView(statusLabel)
        addView(cancelTranscriptionButton)
        addView(retryButtonGroup)
        visibility = View.GONE
    }

    private val backgroundDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(theme.keyboardColor, theme.backgroundColor),
    )

    val view: View = FrameLayout(context).apply {
        background = backgroundDrawable
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(recordingContent)
        addView(stateContent)
        visibility = View.GONE
    }

    fun showRecording() {
        state = State.RECORDING
        recordingContent.visibility = View.VISIBLE
        stateContent.visibility = View.GONE
        waveformView.setRecording(true)
        showPanel()
    }

    fun showTranscribing() {
        state = State.TRANSCRIBING
        waveformView.setRecording(false)
        recordingContent.visibility = View.GONE
        stateContent.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        cancelTranscriptionButton.visibility = View.VISIBLE
        retryButtonGroup.visibility = View.GONE
        statusLabel.text = context.getString(R.string.voice_input_transcribing)
        showPanel()
    }

    fun showRetry(message: String? = null) {
        state = State.RETRY_AVAILABLE
        waveformView.setRecording(false)
        recordingContent.visibility = View.GONE
        stateContent.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        cancelTranscriptionButton.visibility = View.GONE
        retryButtonGroup.visibility = View.VISIBLE
        statusLabel.text = message ?: context.getString(R.string.voice_input_error)
        showPanel()
    }

    fun hide() {
        if (state == State.HIDDEN) return
        state = State.HIDDEN
        waveformView.setRecording(false)
        view.animate().cancel()
        if (disableAnimation || !view.isAttachedToWindow) {
            view.visibility = View.GONE
            resetTransform()
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                if (state == State.HIDDEN) {
                    view.visibility = View.GONE
                    resetTransform()
                }
            }
            .start()
    }

    fun isShowing(): Boolean = state != State.HIDDEN

    private fun showPanel() {
        view.animate().cancel()
        if (view.visibility == View.VISIBLE || disableAnimation) {
            view.visibility = View.VISIBLE
            resetTransform()
            return
        }
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.scaleX = 0.985f
        view.scaleY = 0.96f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140)
            .start()
    }

    private fun resetTransform() {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }
}
