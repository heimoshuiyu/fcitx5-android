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
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.ScreenTextProvider
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import java.security.MessageDigest
import java.security.SecureRandom

class VoiceInputSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voiceInput) {

    private val prefs = AppPrefs.getInstance().voiceInput

    private val subscriptionChangeListener =
        org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<String> { _, _ ->
            view?.post {
                preferenceScreen.findPreference<Preference>("subscription_entry")?.let {
                    updateSubscriptionPref(it)
                }
            }
        }

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
            title = context.getString(R.string.screen_text_context)
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

        val screenTextPref = SwitchPreference(context).apply {
            key = "voice_input_screen_text"
            title = context.getString(R.string.voice_input_screen_text)
            summary = context.getString(R.string.voice_input_screen_text_summary)
            order = 1
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, value ->
                if (value == false) ScreenTextProvider.updateText(emptyMap())
                true
            }
        }
        category.addPreference(screenTextPref)

        val screenshotPref = SwitchPreference(context).apply {
            key = "voice_input_screen_screenshot"
            title = context.getString(R.string.voice_input_screen_screenshot)
            summary = context.getString(R.string.voice_input_screen_screenshot_summary)
            order = 2
            setDefaultValue(false)
        }
        category.addPreference(screenshotPref)
    }

    private fun updateSubscriptionPref(pref: Preference) {
        val context = pref.context
        val prefs = AppPrefs.getInstance().voiceInput
        val token = prefs.subscriptionAccessToken.getValue()

        if (token.isNotBlank()) {
            pref.title = context.getString(R.string.subscription_status_authorized)
            pref.summary = context.getString(R.string.subscription_status_authorized_summary)
            pref.setOnPreferenceClickListener {
                val gatewayUri = secureGatewayUri() ?: return@setOnPreferenceClickListener true
                AlertDialog.Builder(context)
                    .setTitle(R.string.subscription_status_authorized)
                    .setItems(arrayOf(
                        context.getString(R.string.subscription_manage_account),
                        context.getString(R.string.subscription_sign_out)
                    )) { _, which ->
                        when (which) {
                            0 -> {
                                val accountUrl = gatewayUri.buildUpon()
                                    .appendPath("account")
                                    .build()
                                startActivity(Intent(Intent.ACTION_VIEW, accountUrl))
                            }
                            1 -> {
                                prefs.subscriptionAccessToken.setValue("")
                                prefs.subscriptionRefreshToken.setValue("")
                                updateSubscriptionPref(pref)
                                Toast.makeText(context, R.string.subscription_signed_out, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        } else {
            pref.title = context.getString(R.string.subscription_status_not_authorized)
            pref.summary = context.getString(R.string.subscription_sign_in_summary)
            pref.setOnPreferenceClickListener {
                val gatewayUri = secureGatewayUri() ?: return@setOnPreferenceClickListener true

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

                val authUrl = gatewayUri.buildUpon()
                    .appendPath("oauth")
                    .appendPath("authorize")
                    .appendQueryParameter("redirect_uri", "fcitx5://oauth/callback")
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("code_challenge", codeChallenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("state", state)
                    .build()
                startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                true
            }
        }
    }

    private fun secureGatewayUri(): Uri? {
        val uri = Uri.parse(prefs.subscriptionGatewayUrl.getValue().trimEnd('/'))
        if (uri.scheme == "https" && !uri.host.isNullOrEmpty()) return uri
        Toast.makeText(requireContext(), R.string.subscription_gateway_invalid, Toast.LENGTH_LONG).show()
        return null
    }

    private fun updateStatusPref(pref: Preference) {
        val context = pref.context
        val enabled = ScreenTextProvider.isServiceEnabledInSettings(context)

        if (enabled) {
            pref.title = context.getString(R.string.screen_text_enabled)
            pref.summary = context.getString(R.string.screen_text_enabled_summary)
            pref.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }
        } else {
            pref.title = context.getString(R.string.screen_text_not_enabled)
            pref.summary = context.getString(R.string.screen_text_not_enabled_summary)
            pref.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.subscriptionAccessToken.registerOnChangeListener(subscriptionChangeListener)
        // Also refresh immediately in case the change happened while we were paused
        val subPref = preferenceScreen.findPreference<Preference>("subscription_entry")
        if (subPref != null) {
            updateSubscriptionPref(subPref)
        }
        val statusPref = preferenceScreen.findPreference<Preference>("screen_text_accessibility_status")
        if (statusPref != null) {
            updateStatusPref(statusPref)
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.subscriptionAccessToken.unregisterOnChangeListener(subscriptionChangeListener)
    }
}
