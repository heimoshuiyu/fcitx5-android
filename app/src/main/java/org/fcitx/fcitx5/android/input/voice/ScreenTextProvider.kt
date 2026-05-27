/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Shared singleton that holds the latest screen text and screenshot
 * collected by [ScreenTextAccessibilityService].
 *
 * Both the AccessibilityService and the IME run in the same process,
 * so direct singleton access works without IPC.
 *
 * Gracefully degrades: if the accessibility service is not enabled,
 * [screenText] stays empty and [screenshotBase64] stays null;
 * voice input falls back to InputConnection-based context only.
 */
object ScreenTextProvider {

    private const val MAX_LENGTH = 4000

    private val _screenText = MutableStateFlow("")
    val screenTextFlow: StateFlow<String> = _screenText.asStateFlow()

    /** The most recently collected on-screen text. */
    val screenText: String get() = _screenText.value

    /**
     * The most recently captured screenshot as a data URI
     * (e.g. "data:image/jpeg;base64,...").
     * Null if screenshot is unavailable or capture failed.
     */
    @Volatile
    var screenshotBase64: String? = null
        private set

    /** Whether the accessibility service is currently running. */
    @Volatile
    var isEnabled: Boolean = false
        private set

    /** Reference to the running accessibility service, for taking screenshots. */
    @Volatile
    private var service: ScreenTextAccessibilityService? = null

    /** Called by [ScreenTextAccessibilityService.onServiceConnected]. */
    fun onServiceEnabled(service: ScreenTextAccessibilityService) {
        isEnabled = true
        this.service = service
    }

    /** Called by [ScreenTextAccessibilityService.onUnbind]. */
    fun onServiceDisabled() {
        isEnabled = false
        _screenText.value = ""
        screenshotBase64 = null
        service = null
    }

    /** Called by [ScreenTextAccessibilityService] after a traversal. */
    fun updateText(text: String) {
        val trimmed = if (text.length > MAX_LENGTH) text.take(MAX_LENGTH) else text
        _screenText.value = trimmed
    }

    /** Called by [ScreenTextAccessibilityService] after a screenshot attempt. */
    fun updateScreenshot(dataUri: String?) {
        screenshotBase64 = dataUri
        if (dataUri != null) {
            Timber.d("ScreenTextProvider: screenshot updated, ${dataUri.length} chars")
        } else {
            Timber.d("ScreenTextProvider: screenshot cleared")
        }
    }

    /**
     * Request a screenshot from the accessibility service.
     * Must be called from the main thread.
     * Returns true if the screenshot request was dispatched.
     */
    fun requestScreenshot(): Boolean {
        val svc = service
        if (svc == null) {
            Timber.d("ScreenTextProvider: no service, cannot take screenshot")
            return false
        }
        return svc.takeScreenshot()
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
