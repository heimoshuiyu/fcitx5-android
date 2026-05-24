/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import timber.log.Timber

/**
 * Optional accessibility service that reads visible on-screen text
 * to provide richer context for voice transcription.
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenTextProvider.onServiceEnabled()
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
}
