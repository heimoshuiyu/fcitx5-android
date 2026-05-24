/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A custom View that displays real-time audio waveform bars.
 *
 * Displays a row of rounded bars whose heights respond to the microphone amplitude.
 * When idle, bars show a gentle breathing animation.
 * When recording, bars jump to reflect the actual audio level with smooth decay.
 *
 * Inspired by Siri / WeChat voice input waveform visualizers.
 */
class WaveformView(context: Context) : View(context) {

    companion object {
        private const val BAR_COUNT = 28
        private const val MIN_BAR_HEIGHT_RATIO = 0.08f
        private const val MAX_BAR_HEIGHT_RATIO = 0.85f
        private const val BAR_RADIUS_RATIO = 0.5f // fully rounded ends
        private const val DECAY_SPEED = 0.25f // per frame, how fast bars fall
        private const val RISE_SPEED = 0.55f // per frame, how fast bars rise
        private const val IDLE_BREATH_AMP = 0.12f // amplitude of idle breathing
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Current normalized height of each bar [0, 1] */
    private val barHeights = FloatArray(BAR_COUNT) { MIN_BAR_HEIGHT_RATIO }

    /** Target height for each bar (set by amplitude) */
    private val barTargets = FloatArray(BAR_COUNT) { MIN_BAR_HEIGHT_RATIO }

    private var barColor: Int = 0xFF4CAF50.toInt() // default green, overridden by theme
    private var barGap: Float = 0f
    private var barWidth: Float = 0f
    private var idleTime: Float = 0f
    private var isRecording = false
    private var isAttached = false

    private var breathAnimator: ValueAnimator? = null

    fun setBarColor(color: Int) {
        barColor = color
        barPaint.color = color
        invalidate()
    }

    /**
     * Called on every amplitude update from AudioRecorder.
     * @param amplitude normalized [0, 1]
     */
    fun setAmplitude(amplitude: Float) {
        if (!isRecording) return
        // Amplify and apply nonlinear curve so quiet speech is also visible.
        // Raw RMS from 16-bit PCM is typically 0.01–0.15 for normal speech;
        // multiplying by 6 then taking pow(0.5) maps it to 0.25–0.95.
        val a = ((amplitude * 39f).coerceIn(0f, 1f)).let { sqrt(it) }

        for (i in 0 until BAR_COUNT) {
            val center = (BAR_COUNT - 1) / 2f
            val dist = (i - center) / center // [-1, 1]
            val bellFactor = 1f - dist * dist * 0.4f
            val variation = 0.7f + 0.3f * ((i * 7 + 3) % BAR_COUNT) / BAR_COUNT.toFloat()
            val target = MIN_BAR_HEIGHT_RATIO + (MAX_BAR_HEIGHT_RATIO - MIN_BAR_HEIGHT_RATIO) * a * bellFactor * variation
            barTargets[i] = target.coerceIn(MIN_BAR_HEIGHT_RATIO, MAX_BAR_HEIGHT_RATIO)
        }
        invalidate()
    }

    fun setRecording(recording: Boolean) {
        isRecording = recording
        if (recording) {
            breathAnimator?.cancel()
            // Reset bars to minimum
            for (i in 0 until BAR_COUNT) {
                barTargets[i] = MIN_BAR_HEIGHT_RATIO
            }
            invalidate() // kick off the redraw loop
        } else {
            // Smoothly return to idle
            for (i in 0 until BAR_COUNT) {
                barTargets[i] = MIN_BAR_HEIGHT_RATIO
            }
            startIdleAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        if (!isRecording) {
            startIdleAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached = false
        breathAnimator?.cancel()
    }

    private fun startIdleAnimation() {
        if (!isAttached) return
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0f, Float.MAX_VALUE).apply {
            duration = 100_000 // long running
            interpolator = null // linear for custom calculation
            addUpdateListener {
                idleTime = it.animatedFraction * it.duration / 1000f
                updateIdleBars()
                invalidate()
            }
            start()
        }
    }

    private fun updateIdleBars() {
        val t = idleTime
        for (i in 0 until BAR_COUNT) {
            // Each bar oscillates with a phase offset for a wave effect
            val phase = i * 0.4f
            val wave = sin((t * 2.5f + phase).toDouble()).toFloat()
            val target = MIN_BAR_HEIGHT_RATIO + IDLE_BREATH_AMP * (0.5f + 0.5f * wave)
            barTargets[i] = target
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val totalGapSpace = (BAR_COUNT - 1) * 3.dp.toFloat()
        barGap = 3.dp.toFloat()
        barWidth = (w - totalGapSpace) / BAR_COUNT.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Smoothly interpolate current heights toward targets
        for (i in 0 until BAR_COUNT) {
            val diff = barTargets[i] - barHeights[i]
            val speed = if (diff > 0) RISE_SPEED else DECAY_SPEED
            barHeights[i] += diff * speed
        }

        val h = height.toFloat()
        val w = width.toFloat()
        val rect = RectF()

        for (i in 0 until BAR_COUNT) {
            val barH = barHeights[i] * h
            val left = i * (barWidth + barGap)
            val top = (h - barH) / 2f
            val radius = (barWidth / 2f) * BAR_RADIUS_RATIO

            rect.set(left, top, left + barWidth, top + barH)

            // Slightly vary alpha per bar for depth
            val alpha = 0.6f + 0.4f * barHeights[i]
            barPaint.color = barColor
            barPaint.alpha = (alpha * 255).toInt()

            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }

        if (isRecording) {
            // Keep animating during recording
            invalidate()
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
