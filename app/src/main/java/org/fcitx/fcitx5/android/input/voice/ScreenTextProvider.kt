/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared singleton that holds the latest screen text collected
 * by [ScreenTextAccessibilityService].
 *
 * Both the AccessibilityService and the IME run in the same process,
 * so direct singleton access works without IPC.
 *
 * Gracefully degrades: if the accessibility service is not enabled,
 * [screenText] stays empty and voice input falls back to
 * InputConnection-based context only.
 */
object ScreenTextProvider {

    private const val MAX_LENGTH = 4000

    private val _screenText = MutableStateFlow("")
    val screenTextFlow: StateFlow<String> = _screenText.asStateFlow()

    /** The most recently collected on-screen text. */
    val screenText: String get() = _screenText.value

    /** Whether the accessibility service is currently running. */
    @Volatile
    var isEnabled: Boolean = false
        private set

    /** Called by [ScreenTextAccessibilityService.onServiceConnected]. */
    fun onServiceEnabled() {
        isEnabled = true
    }

    /** Called by [ScreenTextAccessibilityService.onUnbind]. */
    fun onServiceDisabled() {
        isEnabled = false
        _screenText.value = ""
    }

    /** Called by [ScreenTextAccessibilityService] after a traversal. */
    fun updateText(text: String) {
        val trimmed = if (text.length > MAX_LENGTH) text.take(MAX_LENGTH) else text
        _screenText.value = trimmed
    }

    /**
     * Check if the accessibility service is enabled in system settings.
     * Used to guide the user to enable it.
     */
    fun isServiceEnabledInSettings(context: android.content.Context): Boolean {
        val expected = android.content.ComponentName(
            context,
            ScreenTextAccessibilityService::class.java
        )
        val enabledServices = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val str = splitter.next()
            val enabled = android.content.ComponentName.unflattenFromString(str)
            if (enabled == expected) return true
        }
        return false
    }
}
