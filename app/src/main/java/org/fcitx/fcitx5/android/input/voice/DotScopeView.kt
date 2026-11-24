/*
 * SPDX-License-Identifier: MIT
 * SPDX-FileCopyrightText: Copyright (c) 2025 opencode
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * The dot braid is ported from OpenCode's VoiceScope
 * (packages/app/src/composer/voice-scope.tsx). Its notice, verbatim:
 *
 * MIT License
 *
 * Copyright (c) 2025 opencode
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A braid of two strands drawn as a grid of dots: the strip shown while recording.
 *
 * One level per distance from the center, newest at the center and older toward both ends, so
 * the braid widens around the middle as the voice rises. A running peak keeps quiet speech as
 * lively as loud speech, and a floor that anchors to the ambient noise keeps a loud room from
 * filling the strip, leaving the swing visible headroom it can grow into.
 */
class DotScopeView(context: Context, private val animated: Boolean) : View(context) {

    companion object {
        // The full-width bar fills the strip it is given: as many rows as fit, on a pitch that
        // only grows past the minimum so a tall strip keeps a bounded dot count.
        private const val MIN_PITCH_DP = 4f
        private const val MIN_ROWS = 9
        private const val MAX_ROWS = 33
        private const val MIN_COLUMNS = 11
        private const val HISTORY_SIZE = 21
        private const val HISTORY_STEP_SECONDS = 0.022f
        private const val MAX_FRAME_DELTA_SECONDS = 0.06f
        private const val INITIAL_PEAK_DB = -32f
        private const val INITIAL_FLOOR_DB = -35f
        // The ambient floor anchors to the first reading, follows quieter readings within a
        // fraction of a second, and climbs back only at a bounded rate under sustained sound.
        private const val FLOOR_FALL_SECONDS = 0.2f
        private const val FLOOR_RISE_DB_PER_SECOND = 6f
        // Keeps the peak-relative span usable when peak and floor converge under constant noise.
        private const val MIN_SPAN_DB = 12f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Levels by distance from the center: [0] is the newest.
    private val history = FloatArray(HISTORY_SIZE)
    private var tick = 0f
    private var peakDb = INITIAL_PEAK_DB
    private var floorDb = INITIAL_FLOOR_DB
    private var floorAnchored = false
    private var level = 0f
    private var time = 0f

    private var latestAmplitude = 0f
    private var isRecording = false
    private var lastFrameTimeMs = 0L

    private var pitch = 0f
    private var rows = MIN_ROWS
    private var columns = MIN_COLUMNS
    private var gridLeft = 0f
    private var gridTop = 0f

    fun setInk(color: Int) {
        paint.color = color
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
        peakDb = INITIAL_PEAK_DB
        floorDb = INITIAL_FLOOR_DB
        floorAnchored = false
        level = 0f
        tick = 0f
        time = 0f
        history.fill(0f)
        lastFrameTimeMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        // Fill the strip vertically: as many rows as the height allows, growing the pitch
        // past the minimum once that would exceed MAX_ROWS so the dot count stays bounded.
        pitch = max(MIN_PITCH_DP, contentHeight / MAX_ROWS / resources.displayMetrics.density).dp
        columns = (contentWidth / pitch).toInt().coerceAtLeast(MIN_COLUMNS)
        gridLeft = paddingLeft + (contentWidth - columns * pitch) / 2f
        rows = (contentHeight / pitch).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
        gridTop = paddingTop + (contentHeight - rows * pitch) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val animating = isRecording && animated
        if (animating) {
            val now = SystemClock.uptimeMillis()
            val deltaSeconds = ((now - lastFrameTimeMs) / 1000f)
                .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
            lastFrameTimeMs = now
            time += deltaSeconds
            updateLevels(deltaSeconds)
        }

        val centerColumn = (columns - 1) / 2f
        val centerRow = (rows - 1) / 2f
        // The reference braid is sized for 9 rows; keep its swing proportional to this strip's height.
        val swing = centerRow / 4f
        for (column in 0 until columns) {
            val x = (column - centerColumn) / centerColumn
            val distance = abs(column - centerColumn) / centerColumn * (HISTORY_SIZE - 1)
            val envelope = history[distance.roundToInt()]
            val width = (0.65f + envelope * 3.4f) * swing * (1f - 0.45f * x * x)
            val phase = x * 6f - time * 4f
            val strandA = width * sin(phase)
            val strandB = -width * sin(phase + 0.65f)
            val centerX = gridLeft + (column + 0.5f) * pitch

            for (row in 0 until rows) {
                val offset = row - centerRow
                val strandDistance = min(abs(offset - strandA), abs(offset - strandB))
                val light = exp(-strandDistance * strandDistance * 1.6f)
                paint.alpha =
                    ((0.1f + light * (0.52f + envelope * 0.35f)) * 255f).roundToInt()
                canvas.drawCircle(
                    centerX,
                    gridTop + (row + 0.5f) * pitch,
                    (0.65f + light * 0.45f) * pitch / 3f,
                    paint,
                )
            }
        }

        if (animating) {
            postInvalidateOnAnimation()
        }
    }

    private fun updateLevels(deltaSeconds: Float) {
        val rms = latestAmplitude.toDouble()
        val decibels = (10.0 * log10(rms * rms + 1e-10)).toFloat()
        peakDb = max(-40f, max(decibels, peakDb - deltaSeconds * 5f))
        // The floor tracks the room instead of trailing the peak: it anchors to the first
        // real reading, drops to quieter readings within a fraction of a second, and climbs
        // back at a bounded rate. The short gaps inside speech keep it at the ambient level,
        // so sustained noise settles into the baseline instead of pinning the braid at full
        // width, while a voice above the room still swings across the whole strip.
        // Silence carries no information about the room: the recorder emits 0 until its
        // first 20 ms window, and the amplitude flow replays that 0 before any real sample,
        // so the floor waits for a real RMS and the target below clamps to zero meanwhile;
        // anchoring to silence would re-create the pegged start in a loud room.
        if (rms > 0.0) {
            if (!floorAnchored) {
                floorDb = decibels.coerceIn(-55f, -20f)
                floorAnchored = true
            } else if (decibels < floorDb) {
                floorDb += (decibels - floorDb) * (1f - exp(-deltaSeconds / FLOOR_FALL_SECONDS))
            } else {
                floorDb = min(decibels, floorDb + deltaSeconds * FLOOR_RISE_DB_PER_SECOND)
            }
        }
        val target =
            ((decibels - floorDb) / max(peakDb - floorDb, MIN_SPAN_DB)).coerceIn(0f, 1f)
        val responseSeconds = if (target > level) 0.035f else 0.095f
        level += (target - level) * (1f - exp(-deltaSeconds / responseSeconds))

        tick += deltaSeconds
        while (tick >= HISTORY_STEP_SECONDS) {
            history.copyInto(history, 1, 0, HISTORY_SIZE - 1)
            tick -= HISTORY_STEP_SECONDS
        }
        history[0] = level
    }

    private val Float.dp: Float
        get() = this * resources.displayMetrics.density
}
