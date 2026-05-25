/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.util.Base64
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.ScreenTextProvider
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom

class VoiceInputSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voiceInput) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val context = screen.context

        // Transcription History entry point — add at top (order = -1)
        val historyPref = Preference(context).apply {
            key = "voice_input_history"
            isPersistent = false
            isSelectable = true
            title = context.getString(R.string.voice_input_history)
            summary = context.getString(R.string.voice_input_history_summary)
            order = -1
            setOnPreferenceClickListener {
                findNavController().navigateWithAnim(SettingsRoute.TranscriptionHistory)
                true
            }
        }
        screen.addPreference(historyPref)

        // Subscription entry — single item, order = 0
        val subPref = Preference(context).apply {
            key = "subscription_entry"
            isPersistent = false
            isSelectable = true
            order = 0
        }
        screen.addPreference(subPref)
        updateSubscriptionPref(subPref)

        // Screen text context
        val category = PreferenceCategory(context).apply {
            title = "Screen text context"
            order = 1000
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

    private fun updateSubscriptionPref(pref: Preference) {
        val context = pref.context
        val prefs = AppPrefs.getInstance().voiceInput
        val token = prefs.subscriptionAccessToken.getValue()

        if (token.isNotEmpty()) {
            pref.title = context.getString(R.string.subscription_status_authorized)
            pref.summary = context.getString(R.string.subscription_status_authorized_summary)
            pref.setOnPreferenceClickListener {
                // Open account page on gateway (usage stats, plan, etc.)
                val gatewayUrl = prefs.subscriptionGatewayUrl.getValue().trimEnd('/')
                val authUrl = "$gatewayUrl/account?token=$token"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                true
            }
        } else {
            pref.title = context.getString(R.string.subscription_status_not_authorized)
            pref.summary = context.getString(R.string.subscription_sign_in_summary)
            pref.setOnPreferenceClickListener {
                val gatewayUrl = prefs.subscriptionGatewayUrl.getValue().trimEnd('/')

                // Generate PKCE code_verifier (RFC 7636 §4.1: 43-128 chars, base64url)
                val secureRandom = SecureRandom()
                val verifierBytes = ByteArray(32)
                secureRandom.nextBytes(verifierBytes)
                val codeVerifier = Base64.encodeToString(
                    verifierBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
                // code_challenge = BASE64URL(SHA256(code_verifier))
                val digest = MessageDigest.getInstance("SHA-256")
                val challengeBytes = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
                val codeChallenge = Base64.encodeToString(
                    challengeBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )

                // Generate random state for CSRF protection
                val stateBytes = ByteArray(16)
                secureRandom.nextBytes(stateBytes)
                val state = Base64.encodeToString(
                    stateBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )

                // Save verifier and state for use in callback
                prefs.oauthCodeVerifier.setValue(codeVerifier)
                prefs.oauthState.setValue(state)

                val authUrl = "$gatewayUrl/oauth/authorize?redirect_uri=fcitx5://oauth/callback&response_type=code&code_challenge=$codeChallenge&code_challenge_method=S256&state=$state"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                true
            }
        }
    }

    private fun updateStatusPref(pref: Preference) {
        val context = pref.context
        val enabled = ScreenTextProvider.isServiceEnabledInSettings(context)

        if (enabled) {
            pref.title = "Screen text service: Enabled"
            pref.summary = "On-screen text is being read to improve voice transcription accuracy."
            pref.setOnPreferenceClickListener {
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
        // Refresh subscription status when returning from OAuth or browser
        val subPref = preferenceScreen.findPreference<Preference>("subscription_entry")
        if (subPref != null) {
            updateSubscriptionPref(subPref)
        }
        val statusPref = preferenceScreen.findPreference<Preference>("screen_text_accessibility_status")
        if (statusPref != null) {
            updateStatusPref(statusPref)
        }
    }
}
