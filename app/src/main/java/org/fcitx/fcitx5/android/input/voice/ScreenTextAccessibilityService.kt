/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Optional accessibility service that reads visible on-screen text
 * and captures screenshots to provide richer context for voice transcription.
 *
 * Uses [getWindows] to traverse ALL visible windows (not just the active one),
 * filtering out the IME window itself so we capture the app content behind
 * the keyboard.
 *
 * Shares data with the IME via [ScreenTextProvider] singleton
 * (same process, no IPC needed).
 */
class ScreenTextAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTraversal: Runnable? = null

    private companion object {
        const val DEBOUNCE_MS = 500L
        const val MAX_DEPTH = 30
        const val MAX_TEXT_NODES = 200
        const val SCREENSHOT_QUALITY = 60
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenTextProvider.onServiceEnabled(this)
        Timber.d("ScreenTextAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleTraversal()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        pendingTraversal?.let { handler.removeCallbacks(it) }
        pendingTraversal = null
        ScreenTextProvider.onServiceDisabled()
        Timber.d("ScreenTextAccessibilityService destroyed")
    }

    private fun scheduleTraversal() {
        pendingTraversal?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { traverseAndCollect() }
        pendingTraversal = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun traverseAndCollect() {
        pendingTraversal = null
        val texts = mutableListOf<String>()

        // Use getWindows() to iterate all visible windows,
        // not getRootInActiveWindow() which returns the keyboard when IME is visible.
        val windows = windows
        if (windows.isNullOrEmpty()) {
            Timber.d("ScreenText: no windows returned")
            return
        }

        Timber.d("ScreenText: ${windows.size} windows")
        for (window in windows) {
            val type = window.type
            // Skip IME windows (type 2) and system windows we don't care about
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Timber.d("ScreenText: skipping IME window")
                window.recycle()
                continue
            }
            // Only process application windows
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                Timber.d("ScreenText: skipping window type=$type")
                window.recycle()
                continue
            }

            val root = window.root
            if (root != null) {
                val before = texts.size
                collectTextRecursive(root, texts, depth = 0)
                Timber.d("ScreenText: app window contributed ${texts.size - before} text nodes")
                root.recycle()
            }
            window.recycle()
        }

        val joined = texts.filter { it.isNotBlank() }.joinToString("\n")
        Timber.d("ScreenText collected ${texts.size} text nodes, ${joined.length} chars total")
        if (joined.isNotBlank()) {
            Timber.d("ScreenText content:\n$joined")
        }
        ScreenTextProvider.updateText(joined)
    }

    private fun collectTextRecursive(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        if (texts.size > MAX_TEXT_NODES) return

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            texts.add(it)
        }

        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            texts.add(it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                texts.add(it)
            }
        }

        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { child ->
                    collectTextRecursive(child, texts, depth + 1)
                    child.recycle()
                }
            } catch (_: Exception) {
                // Skip inaccessible children
            }
        }
    }

    /**
     * Capture a screenshot and store it as base64 JPEG in [ScreenTextProvider].
     * Returns true if screenshot was captured successfully.
     *
     * Must be called on the main thread (handler post).
     */
    fun takeScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.d("Screenshot: API ${Build.VERSION.SDK_INT} < 30, not supported")
            return false
        }

        return try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                { handler.post(it) },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )?.let { hwBitmap ->
                                // Convert to software bitmap for JPEG compression
                                Bitmap.createBitmap(hwBitmap)
                            }

                            if (bitmap == null) {
                                Timber.w("Screenshot: failed to create bitmap")
                                ScreenTextProvider.updateScreenshot(null)
                                return
                            }

                            // Compress to JPEG (no scaling — original resolution)
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, stream)
                            val bmpWidth = bitmap.width
                            val bmpHeight = bitmap.height
                            bitmap.recycle()

                            val jpegBytes = stream.toByteArray()
                            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                            Timber.d("Screenshot: captured ${jpegBytes.size} bytes JPEG, ${base64.length} chars base64, ${bmpWidth}x${bmpHeight}")
                            ScreenTextProvider.updateScreenshot("data:image/jpeg;base64,$base64")
                        } catch (e: Exception) {
                            Timber.e(e, "Screenshot: processing failed")
                            ScreenTextProvider.updateScreenshot(null)
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errorName = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
                            else -> "UNKNOWN_$errorCode"
                        }
                        Timber.w("Screenshot: failed with $errorName ($errorCode)")
                        ScreenTextProvider.updateScreenshot(null)
                    }
                }
            )
            true
        } catch (e: Exception) {
            Timber.e(e, "Screenshot: exception")
            false
        }
    }
}
