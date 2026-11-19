/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Transparent activity that requests the RECORD_AUDIO permission on behalf of
 * the IME service (which cannot call requestPermissions() directly).
 *
 * This triggers the **system-level** permission dialog.
 * It is a one-time flow — once the user grants the permission, this activity
 * will never be launched again.
 */
class MicrophonePermissionActivity : Activity() {

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already granted (race condition) — finish immediately
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            setResult(RESULT_OK)
            finish()
            return
        }

        // Trigger the system permission dialog
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Regardless of the result, just finish — the user can retry voice input
        finish()
    }
}
