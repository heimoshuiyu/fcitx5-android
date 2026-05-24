/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.ScreenTextAccessibilityService
import org.fcitx.fcitx5.android.input.voice.ScreenTextProvider

class VoiceInputSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voiceInput) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val context = screen.context

        val category = PreferenceCategory(context).apply {
            title = "Screen text context"
            order = 1000 // place after all managed prefs
        }
        screen.addPreference(category)

        val statusPref = Preference(context).apply {
            key = "screen_text_accessibility_status"
            isPersistent = false
            isSelectable = true
            order = 0
        }
        category.addPreference(statusPref)

        updateStatusPref(statusPref)
    }

    private fun updateStatusPref(pref: Preference) {
        val context = pref.context
        val enabled = ScreenTextProvider.isServiceEnabledInSettings(context)

        if (enabled) {
            pref.title = "Screen text service: Enabled"
            pref.summary = "On-screen text is being read to improve voice transcription accuracy."
            pref.setOnPreferenceClickListener {
                // Navigate to accessibility settings to disable
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }
        } else {
            pref.title = "Screen text service: Not enabled"
            pref.summary = "Enable the accessibility service to read on-screen text as voice transcription context. " +
                "This requires a separate permission grant in system Settings > Accessibility."
            pref.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Refresh status when returning from accessibility settings
        val pref = preferenceScreen.findPreference<Preference>("screen_text_accessibility_status")
        if (pref != null) {
            updateStatusPref(pref)
        }
    }
}
