/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Shared singleton that holds the latest screen text and screenshot
 * collected by [ScreenTextAccessibilityService].
 *
 * Both the AccessibilityService and the IME run in the same process,
 * so direct singleton access works without IPC.
 *
 * Gracefully degrades when the accessibility service is unavailable.
 */
object ScreenTextProvider {

    private const val MAX_LENGTH = 4000

    @Volatile
    private var screenTextByPackage: Map<String, String> = emptyMap()

    fun getScreenText(packageName: String?): String {
        return packageName?.let(screenTextByPackage::get).orEmpty()
    }

    /**
     * The most recently captured screenshot as a data URI
     * (e.g. "data:image/jpeg;base64,...").
     * Null if screenshot is unavailable or capture failed.
     */
    @Volatile
    private var screenshotBase64: String? = null

    @Volatile
    private var screenshotRequestId = 0L

    private var screenshotDeferred = CompletableDeferred<String?>()

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
        screenTextByPackage = emptyMap()
        clearScreenshot()
        service = null
    }

    /** Called by [ScreenTextAccessibilityService] after a traversal. */
    fun updateText(values: Map<String, String>) {
        screenTextByPackage = values.mapValues { (_, text) ->
            if (text.length > MAX_LENGTH) text.take(MAX_LENGTH) else text
        }
    }

    /** Called by [ScreenTextAccessibilityService] after a screenshot attempt. */
    @Synchronized
    fun updateScreenshot(requestId: Long, dataUri: String?) {
        if (requestId != screenshotRequestId) return
        screenshotBase64 = dataUri
        screenshotDeferred.complete(dataUri)
        if (dataUri != null) {
            Timber.d("ScreenTextProvider: screenshot updated, ${dataUri.length} chars")
        } else {
            Timber.d("ScreenTextProvider: screenshot cleared")
        }
    }

    /**
     * Request a screenshot from the accessibility service.
     * Must be called from the main thread.
     * Returns the request ID if the screenshot request was dispatched.
     */
    @Synchronized
    fun requestScreenshot(): Long? {
        screenshotDeferred.complete(null)
        screenshotDeferred = CompletableDeferred()
        screenshotRequestId += 1
        screenshotBase64 = null
        val requestId = screenshotRequestId
        val svc = service
        if (svc == null) {
            Timber.d("ScreenTextProvider: no service, cannot take screenshot")
            screenshotDeferred.complete(null)
            return null
        }
        return if (svc.takeScreenshot(requestId)) {
            requestId
        } else {
            screenshotDeferred.complete(null)
            null
        }
    }

    suspend fun awaitScreenshot(requestId: Long?): String? {
        val deferred = synchronized(this) {
            if (requestId == null || requestId != screenshotRequestId) null
            else screenshotDeferred
        } ?: return null
        return withTimeoutOrNull(1_000L) { deferred.await() }
    }

    @Synchronized
    fun clearScreenshot() {
        screenshotDeferred.complete(null)
        screenshotDeferred = CompletableDeferred()
        screenshotRequestId += 1
        screenshotBase64 = null
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
