/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Displays a rolling time window of microphone levels.
 *
 * New samples enter from the right and move continuously toward the left. Audio updates only
 * replace the latest RMS value; sampling and drawing run on a stable visual clock so devices with
 * different AudioRecord buffer sizes produce the same animation speed.
 */
class WaveformView(context: Context) : View(context) {

    companion object {
        private const val WINDOW_DURATION_MS = 3_000f
        private const val TARGET_BAR_STEP_DP = 6f
        private const val MIN_BAR_COUNT = 48
        private const val MAX_BAR_COUNT = 120
        private const val MIN_LEVEL_DB = -60f
        private const val MAX_LEVEL_DB = -12f
        private const val ATTACK_DURATION_MS = 45f
        private const val RELEASE_DURATION_MS = 180f
        private const val MAX_FRAME_DELTA_MS = 250f
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()

    private var history = FloatArray(MIN_BAR_COUNT)
    private var writeIndex = 0
    private var sampleIntervalMs = WINDOW_DURATION_MS / MIN_BAR_COUNT
    private var sampleElapsedMs = 0f
    private var lastFrameTimeMs = 0L

    private var latestAmplitude = 0f
    private var smoothedLevel = 0f
    private var isRecording = false

    private var historyColor = 0x994CAF50.toInt()
    private var liveColor = 0xFF4CAF50.toInt()

    fun setBarColors(historyColor: Int, liveColor: Int) {
        this.historyColor = historyColor
        this.liveColor = liveColor
        invalidate()
    }

    /** Updates the latest normalized RMS value from AudioRecorder. */
    fun setAmplitude(amplitude: Float) {
        if (!isRecording) return
        latestAmplitude = amplitude.coerceIn(0f, 1f)
    }

    fun setRecording(recording: Boolean) {
        if (isRecording == recording) return
        isRecording = recording
        latestAmplitude = 0f
        smoothedLevel = 0f
        sampleElapsedMs = 0f
        lastFrameTimeMs = SystemClock.uptimeMillis()
        writeIndex = 0
        history.fill(0f)
        if (recording) {
            postInvalidateOnAnimation()
        } else {
            invalidate()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val barCount = (contentWidth / TARGET_BAR_STEP_DP.dp)
            .roundToInt()
            .coerceIn(MIN_BAR_COUNT, MAX_BAR_COUNT)
        if (barCount != history.size) {
            history = FloatArray(barCount)
            writeIndex = 0
        }
        sampleIntervalMs = WINDOW_DURATION_MS / barCount
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scrollProgress = if (isRecording) updateLevels() else 0f
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val contentWidth = right - left
        if (contentWidth <= 0f || history.size < 2) return

        val step = contentWidth / (history.size - 1)
        val barWidth = min(4f.dp, step * 0.58f).coerceAtLeast(2f.dp)
        val halfBarWidth = barWidth / 2f

        for (position in history.indices) {
            val centerX = left + position * step - scrollProgress * step
            if (centerX + halfBarWidth < left || centerX - halfBarWidth > right) {
                continue
            }
            val sampleIndex = (writeIndex + position) % history.size
            val freshness = position.toFloat() / (history.size - 1)
            val color = ColorUtils.blendARGB(historyColor, liveColor, freshness * freshness)
            val alpha = (0.3f + 0.7f * freshness) * (0.7f + 0.3f * history[sampleIndex])
            drawBar(canvas, centerX, barWidth, history[sampleIndex], color, alpha)
        }

        val liveCenterX = right + (1f - scrollProgress) * step
        if (liveCenterX - halfBarWidth <= right) {
            drawBar(canvas, liveCenterX, barWidth, smoothedLevel, liveColor, 1f)
        }

        if (isRecording) {
            postInvalidateOnAnimation()
        }
    }

    private fun updateLevels(): Float {
        val now = SystemClock.uptimeMillis()
        val deltaMs = (now - lastFrameTimeMs).toFloat().coerceIn(0f, MAX_FRAME_DELTA_MS)
        lastFrameTimeMs = now

        val targetLevel = normalizeAmplitude(latestAmplitude)
        val responseDuration = if (targetLevel > smoothedLevel) {
            ATTACK_DURATION_MS
        } else {
            RELEASE_DURATION_MS
        }
        val response = 1f - exp((-deltaMs / responseDuration).toDouble()).toFloat()
        smoothedLevel += (targetLevel - smoothedLevel) * response

        sampleElapsedMs += deltaMs
        while (sampleElapsedMs >= sampleIntervalMs) {
            history[writeIndex] = smoothedLevel
            writeIndex = (writeIndex + 1) % history.size
            sampleElapsedMs -= sampleIntervalMs
        }
        return (sampleElapsedMs / sampleIntervalMs).coerceIn(0f, 1f)
    }

    private fun normalizeAmplitude(amplitude: Float): Float {
        if (amplitude <= 0f) return 0f
        val decibels = 20f * log10(amplitude.toDouble()).toFloat()
        val normalized = ((decibels - MIN_LEVEL_DB) / (MAX_LEVEL_DB - MIN_LEVEL_DB))
            .coerceIn(0f, 1f)
        return normalized
    }

    private fun drawBar(
        canvas: Canvas,
        centerX: Float,
        barWidth: Float,
        level: Float,
        color: Int,
        alpha: Float,
    ) {
        val contentHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(0f)
        val minHeight = 4f.dp
        val maxHeight = (contentHeight * 0.88f).coerceAtLeast(minHeight)
        val barHeight = minHeight + (maxHeight - minHeight) * level
        val centerY = paddingTop + contentHeight / 2f
        val halfWidth = barWidth / 2f
        val halfHeight = barHeight / 2f

        barPaint.color = color
        barPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        barRect.set(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight,
        )
        canvas.drawRoundRect(barRect, halfWidth, halfWidth, barPaint)
    }

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}
