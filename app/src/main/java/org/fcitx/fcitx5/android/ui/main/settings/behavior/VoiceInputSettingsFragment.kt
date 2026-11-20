/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.ConsoleApi
import org.fcitx.fcitx5.android.input.voice.ConsoleCatalog
import org.fcitx.fcitx5.android.input.voice.ConsoleSession
import org.fcitx.fcitx5.android.input.voice.ScreenTextProvider
import org.fcitx.fcitx5.android.input.voice.VoiceBackendType
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class VoiceInputSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voiceInput) {

    private val prefs = AppPrefs.getInstance().voiceInput

    private var deviceFlowJob: Job? = null
    private var deviceDialog: AlertDialog? = null
    private var catalogJob: Job? = null
    private var catalog: List<ConsoleCatalog.ModelEntry>? = null
    private var catalogLoadError: String? = null

    // Hotword learning agent: its own catalog slice and pickers.
    private var agentCatalogJob: Job? = null
    private var agentCatalog: List<ConsoleCatalog.ModelEntry>? = null
    private var agentCatalogLoadError: String? = null

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val context = screen.context

        // Setup guidance shown on top while the Console backend has no
        // usable configuration yet (same condition as the IME's pre-check).
        val guidancePref = Preference(context).apply {
            key = "voice_setup_guidance"
            isPersistent = false
            isSelectable = false
            order = -2
            title = context.getString(R.string.voice_setup_guidance_title)
            summary = context.getString(R.string.voice_setup_guidance_summary)
            isVisible = !isConsoleBackendConfigured()
        }
        screen.addPreference(guidancePref)

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

        // Hotword learning records entry
        val hotwordRunsPref = Preference(context).apply {
            key = "hotword_agent_runs"
            isPersistent = false
            isSelectable = true
            title = context.getString(R.string.hotword_agent_runs)
            summary = context.getString(R.string.hotword_agent_runs_summary)
            order = -1
            setOnPreferenceClickListener {
                findNavController().navigateWithAnim(SettingsRoute.HotwordAgentRuns)
                true
            }
        }
        screen.addPreference(hotwordRunsPref)

        // OpenCode Console section: account, model, variant
        val consoleCategory = PreferenceCategory(context).apply {
            title = context.getString(R.string.voice_backend_console)
            order = -1
        }
        screen.addPreference(consoleCategory)

        val accountPref = Preference(context).apply {
            key = "console_account"
            isPersistent = false
            isSelectable = true
            order = 0
        }
        consoleCategory.addPreference(accountPref)

        val modelPref = Preference(context).apply {
            key = "console_model"
            isPersistent = false
            isSelectable = true
            order = 1
        }
        consoleCategory.addPreference(modelPref)

        val variantPref = Preference(context).apply {
            key = "console_variant"
            isPersistent = false
            isSelectable = true
            order = 2
        }
        consoleCategory.addPreference(variantPref)

        // Hotword learning agent: its own model and variant rows.
        val agentModelPref = Preference(context).apply {
            key = "console_agent_model"
            isPersistent = false
            isSelectable = true
            order = 3
        }
        consoleCategory.addPreference(agentModelPref)

        val agentVariantPref = Preference(context).apply {
            key = "console_agent_variant"
            isPersistent = false
            isSelectable = true
            order = 4
        }
        consoleCategory.addPreference(agentVariantPref)

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

        updateConsoleEntries()

        // Seed the backend list with its effective default on first visit so
        // the summary and the selection dialog show it instead of "Not set".
        // The value equals what createBackend() already falls back to.
        screen.findPreference<ListPreference>("voice_input_backend_type")?.let { typePref ->
            if (typePref.value == null) {
                val index = typePref.findIndexOfValue(
                    AppPrefs.getInstance().voiceInput.backendType.getValue().name
                )
                if (index >= 0) typePref.setValueIndex(index)
            }
        }

        loadCatalog()
        loadAgentCatalog()
    }

    // --- OpenCode Console: account ---

    /**
     * The Console backend has a usable configuration (signed in and a model
     * selected). Mirrors the pre-check in HoldToTalkController.startRecording.
     */
    private fun isConsoleBackendConfigured(): Boolean {
        if (prefs.backendType.getValue() != VoiceBackendType.Console) return true
        return prefs.consoleAccessToken.getValue().isNotBlank() &&
            prefs.consoleModelId.getValue().isNotBlank()
    }

    private fun isSignedIn(): Boolean = prefs.consoleAccessToken.getValue().isNotBlank()

    private fun updateConsoleEntries() {
        val screen = preferenceScreen ?: return
        screen.findPreference<Preference>("voice_setup_guidance")?.let {
            it.isVisible = !isConsoleBackendConfigured()
        }
        screen.findPreference<Preference>("console_account")?.let(::updateAccountPref)
        screen.findPreference<Preference>("console_model")?.let(::updateModelPref)
        screen.findPreference<Preference>("console_variant")?.let(::updateVariantPref)
        screen.findPreference<Preference>("console_agent_model")?.let(::updateAgentModelPref)
        screen.findPreference<Preference>("console_agent_variant")?.let(::updateAgentVariantPref)
    }

    private fun updateAccountPref(pref: Preference) {
        val context = pref.context
        if (isSignedIn()) {
            pref.title = context.getString(R.string.console_status_signed_in)
            pref.summary = context.getString(R.string.console_status_signed_in_summary)
            pref.setOnPreferenceClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.console_status_signed_in)
                    .setItems(
                        arrayOf(
                            context.getString(R.string.console_open_console),
                            context.getString(R.string.console_sign_out),
                        )
                    ) { _, which ->
                        when (which) {
                            // Open the Console in the browser; the sign-in
                            // there is the user's own, no token is passed.
                            0 -> runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ConsoleApi.CONSOLE_BASE)))
                            }.onFailure {
                                Toast.makeText(context, R.string.console_open_console_failed, Toast.LENGTH_SHORT)
                                    .show()
                            }
                            1 -> {
                                // Capture first: the revoke is best-effort and must
                                // not stop or defer the local cleanup.
                                val refreshToken = prefs.consoleRefreshToken.getValue()
                                prefs.consoleAccessToken.setValue("")
                                prefs.consoleRefreshToken.setValue("")
                                // Drop the workspace too, so a future sign-in without
                                // org scope never carries the old one.
                                prefs.consoleOrgId.setValue("")
                                ConsoleCatalog.reset()
                                catalog = null
                                agentCatalog = null
                                ConsoleApi.revokeAsync(refreshToken)
                                updateConsoleEntries()
                                Toast.makeText(context, R.string.console_signed_out, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        } else {
            pref.title = context.getString(R.string.console_status_signed_out)
            pref.summary = context.getString(R.string.console_status_signed_out_summary)
            pref.setOnPreferenceClickListener {
                startDeviceFlow()
                true
            }
        }
    }

    /**
     * RFC 8628 device authorization: request a code, let the user approve it
     * in the browser, and poll the token endpoint until the grant resolves.
     * The poll runs in the view's lifecycle scope, so leaving the settings
     * screen cancels it; staying on the screen keeps it alive while the
     * browser is in the foreground.
     */
    private fun startDeviceFlow() {
        if (deviceFlowJob?.isActive == true) return
        deviceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val device = ConsoleApi.requestDeviceCode()
                showDeviceDialog(device)
                var intervalMs = device.interval.coerceAtLeast(1) * 1000L
                val deadline = System.currentTimeMillis() + device.expires_in * 1000L
                while (true) {
                    delay(intervalMs)
                    try {
                        val token = ConsoleApi.pollDeviceToken(device.device_code)
                        prefs.consoleAccessToken.setValue(token.access_token)
                        prefs.consoleRefreshToken.setValue(token.refresh_token)
                        // Absent org scope means the personal workspace; always
                        // write it so a stale org from an earlier account is gone.
                        prefs.consoleOrgId.setValue(token.org_id?.takeIf { it.isNotBlank() } ?: "")
                        ConsoleCatalog.reset()
                        dismissDeviceDialog()
                        Toast.makeText(requireContext(), R.string.console_auth_success, Toast.LENGTH_SHORT).show()
                        updateConsoleEntries()
                        loadCatalog()
                        loadAgentCatalog()
                        return@launch
                    } catch (e: ConsoleApi.DeviceSlowDownException) {
                        intervalMs += 5000L
                    } catch (e: ConsoleApi.DevicePendingException) {
                        // keep waiting for the user to approve
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        throw ConsoleApi.DeviceExpiredException(null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dismissDeviceDialog()
                val message = when (e) {
                    is ConsoleApi.DeviceExpiredException -> getString(R.string.console_device_expired)
                    is ConsoleApi.DeviceDeniedException -> getString(R.string.console_device_denied)
                    else -> e.message.orEmpty().ifEmpty { getString(R.string.console_device_failed) }
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.console_sign_in_failed, message),
                    Toast.LENGTH_LONG,
                ).show()
                updateConsoleEntries()
            }
        }
    }

    private fun showDeviceDialog(device: ConsoleApi.DeviceCodeResponse) {
        dismissDeviceDialog()
        deviceDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.console_device_dialog_title)
            .setMessage(getString(R.string.console_device_code_message, device.user_code))
            .setPositiveButton(R.string.console_device_open_page) { _, _ ->
                // The Console may answer with a root-relative path; resolve it
                // against the Console origin and refuse anything else.
                val url = ConsoleApi.resolveVerificationUrl(
                    device.verification_uri_complete ?: device.verification_uri
                )
                if (url == null) {
                    Toast.makeText(requireContext(), R.string.console_device_failed, Toast.LENGTH_SHORT).show()
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure {
                            Toast.makeText(requireContext(), R.string.console_device_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                deviceFlowJob?.cancel()
                dialog.cancel()
            }
            .setOnCancelListener { deviceFlowJob?.cancel() }
            .show()
    }

    private fun dismissDeviceDialog() {
        deviceDialog?.dismiss()
        deviceDialog = null
    }

    // --- OpenCode Console: model + variant ---

    private fun loadCatalog() {
        if (!isSignedIn() || catalog != null || catalogJob?.isActive == true) return
        // Called while the screen is still being built (onCreatePreferences),
        // where the fragment's preferenceScreen property and the view
        // lifecycle are not ready yet: the fetch runs on the fragment scope
        // and the entries render once the view exists.
        findPreference<Preference>("console_model")?.let { modelPref ->
            catalogLoadError = null
            modelPref.summary = getString(R.string.console_model_loading)
        }
        catalogJob = lifecycleScope.launch {
            try {
                catalog = ConsoleSession.withRefresh(
                    prefs.consoleAccessToken.getValue(),
                    { prefs.consoleRefreshToken.getValue() },
                    { access, refresh ->
                        prefs.consoleAccessToken.setValue(access)
                        prefs.consoleRefreshToken.setValue(refresh)
                    },
                ) { access ->
                    ConsoleCatalog.ensure(access, prefs.consoleOrgId.getValue())
                }
                updateConsoleEntries()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConsoleSession.SessionExpiredException) {
                prefs.consoleAccessToken.setValue("")
                prefs.consoleRefreshToken.setValue("")
                ConsoleCatalog.reset()
                catalog = null
                context?.let {
                    Toast.makeText(it, R.string.console_session_expired, Toast.LENGTH_LONG).show()
                }
                updateConsoleEntries()
            } catch (e: Exception) {
                catalogLoadError = e.message
                updateConsoleEntries()
            }
        }
    }

    private fun updateModelPref(pref: Preference) {
        val context = pref.context
        val selected = prefs.consoleModelId.getValue()
        when {
            !isSignedIn() -> {
                pref.title = context.getString(R.string.console_model)
                pref.summary = context.getString(R.string.console_model_require_sign_in)
                pref.setOnPreferenceClickListener {
                    updateConsoleEntries()
                    true
                }
            }
            catalog != null -> {
                val entries = catalog.orEmpty()
                pref.title = context.getString(R.string.console_model)
                when {
                    entries.isEmpty() ->
                        pref.summary = context.getString(R.string.console_model_empty)
                    selected.isBlank() ->
                        pref.summary = context.getString(R.string.console_model_none_summary)
                    else -> {
                        // The summary names the channel too: two channels can
                        // offer the same model display name.
                        val entry = ConsoleCatalog.findById(entries, selected)
                        pref.summary = entry
                            ?.let {
                                context.getString(R.string.console_model_selected, it.displayName, it.channelName)
                            }
                            ?: context.getString(R.string.console_model_unavailable)
                    }
                }
                pref.setOnPreferenceClickListener {
                    if (entries.isEmpty()) {
                        true
                    } else {
                        showModelPicker(entries, selected)
                        true
                    }
                }
            }
            catalogJob?.isActive == true -> {
                pref.title = context.getString(R.string.console_model)
                pref.summary = context.getString(R.string.console_model_loading)
                pref.setOnPreferenceClickListener { true }
            }
            else -> {
                pref.title = context.getString(R.string.console_model)
                pref.summary = context.getString(
                    if (catalogLoadError != null) R.string.console_model_error
                    else R.string.console_model_require_sign_in,
                )
                pref.setOnPreferenceClickListener {
                    catalogLoadError = null
                    loadCatalog()
                    true
                }
            }
        }
    }

    /**
     * One row of the model picker: a channel's group header ([isHeader]) or a
     * selectable model ([entry] null on header rows).
     */
    private data class PickerRow(
        val header: String? = null,
        val entry: ConsoleCatalog.ModelEntry? = null,
    ) {
        val isHeader: Boolean get() = header != null
    }

    private fun showModelPicker(entries: List<ConsoleCatalog.ModelEntry>, selected: String) {
        // A header before each channel's models, in the order buildEntries
        // produced; the entries themselves carry the channel label.
        val rows = buildList {
            var header: String? = null
            entries.forEach { entry ->
                if (entry.channelName != header) {
                    add(PickerRow(header = entry.channelName))
                    header = entry.channelName
                }
                add(PickerRow(entry = entry))
            }
        }
        val selectedId = ConsoleCatalog.findById(entries, selected)?.id
        val checked = rows.indexOfFirst { it.entry?.id == selectedId }
        var picked = -1
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = !rows[position].isHeader
            override fun getItemViewType(position: Int) = if (rows[position].isHeader) 0 else 1
            override fun getViewTypeCount() = 2
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                if (row.isHeader) {
                    val view = convertView as? TextView
                        ?: LayoutInflater.from(parent.context)
                            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                    view.text = row.header
                    TextViewCompat.setTextAppearance(
                        view,
                        android.R.style.TextAppearance_DeviceDefault_Small,
                    )
                    val typedValue = TypedValue()
                    view.context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
                    // A theme attribute may resolve to a color resource or a
                    // literal; pass the resolved color, never the resource id.
                    if (typedValue.resourceId != 0) {
                        view.setTextColor(ContextCompat.getColor(view.context, typedValue.resourceId))
                    } else {
                        view.setTextColor(typedValue.data)
                    }
                    return view
                }
                val view = convertView as? CheckedTextView
                    ?: LayoutInflater.from(parent.context)
                        .inflate(android.R.layout.simple_list_item_single_choice, parent, false) as CheckedTextView
                view.text = row.entry?.displayName
                return view
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.console_model)
            .setSingleChoiceItems(adapter, checked) { _, which ->
                picked = which
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                rows.getOrNull(picked)?.entry?.let { entry ->
                    prefs.consoleModelId.setValue(entry.id)
                    // A new model resets the variant to its default.
                    prefs.consoleVariantId.setValue("")
                    updateConsoleEntries()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateVariantPref(pref: Preference) {
        val context = pref.context
        val selectedModel = prefs.consoleModelId.getValue()
        val selectedVariant = prefs.consoleVariantId.getValue()
        val entry = catalog?.let { ConsoleCatalog.findById(it, selectedModel) }
        pref.title = context.getString(R.string.console_variant)
        when {
            !isSignedIn() || catalog == null || selectedModel.isBlank() -> {
                pref.summary = context.getString(R.string.console_variant_require_model)
                pref.setOnPreferenceClickListener { true }
            }
            entry == null -> {
                pref.summary = context.getString(R.string.console_model_unavailable)
                pref.setOnPreferenceClickListener { true }
            }
            else -> {
                pref.summary = selectedVariant.ifBlank {
                    context.getString(R.string.console_variant_default)
                }
                pref.setOnPreferenceClickListener {
                    showVariantPicker(entry, selectedVariant)
                    true
                }
            }
        }
    }

    private fun showVariantPicker(entry: ConsoleCatalog.ModelEntry, selected: String) {
        val context = requireContext()
        val ids = listOf("") + entry.variants
        val labels = ids.map {
            if (it.isBlank()) context.getString(R.string.console_variant_default) else it
        }.toTypedArray()
        val checked = ids.indexOf(selected).coerceAtLeast(0)
        var picked = -1
        AlertDialog.Builder(context)
            .setTitle(R.string.console_variant)
            .setSingleChoiceItems(labels, checked) { _, which ->
                picked = which
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                if (picked >= 0) {
                    prefs.consoleVariantId.setValue(ids[picked])
                    updateConsoleEntries()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Hotword learning agent: model + variant ---

    /**
     * The Agent-kind catalog slice. Mirrors [loadCatalog] but resolves the
     * chat-capable models for the hotword learning agent.
     */
    private fun loadAgentCatalog() {
        if (!isSignedIn() || agentCatalog != null || agentCatalogJob?.isActive == true) return
        findPreference<Preference>("console_agent_model")?.let { modelPref ->
            agentCatalogLoadError = null
            modelPref.summary = getString(R.string.console_model_loading)
        }
        agentCatalogJob = lifecycleScope.launch {
            try {
                agentCatalog = ConsoleSession.withRefresh(
                    prefs.consoleAccessToken.getValue(),
                    { prefs.consoleRefreshToken.getValue() },
                    { access, refresh ->
                        prefs.consoleAccessToken.setValue(access)
                        prefs.consoleRefreshToken.setValue(refresh)
                    },
                ) { access ->
                    ConsoleCatalog.ensure(access, prefs.consoleOrgId.getValue(), ConsoleCatalog.Kind.Agent)
                }
                updateConsoleEntries()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConsoleSession.SessionExpiredException) {
                prefs.consoleAccessToken.setValue("")
                prefs.consoleRefreshToken.setValue("")
                ConsoleCatalog.reset()
                agentCatalog = null
                context?.let {
                    Toast.makeText(it, R.string.console_session_expired, Toast.LENGTH_LONG).show()
                }
                updateConsoleEntries()
            } catch (e: Exception) {
                agentCatalogLoadError = e.message
                updateConsoleEntries()
            }
        }
    }

    private fun updateAgentModelPref(pref: Preference) {
        val context = pref.context
        val selected = prefs.consoleAgentModelId.getValue()
        when {
            !isSignedIn() -> {
                pref.title = context.getString(R.string.console_agent_model)
                pref.summary = context.getString(R.string.console_agent_model_require_sign_in)
                pref.setOnPreferenceClickListener {
                    updateConsoleEntries()
                    true
                }
            }
            agentCatalog != null -> {
                val entries = agentCatalog.orEmpty()
                pref.title = context.getString(R.string.console_agent_model)
                when {
                    entries.isEmpty() ->
                        pref.summary = context.getString(R.string.console_agent_model_empty)
                    selected.isBlank() ->
                        pref.summary = context.getString(R.string.console_agent_model_none_summary)
                    else -> {
                        // The summary names the channel too: two channels can
                        // offer the same model display name.
                        val entry = ConsoleCatalog.findById(entries, selected)
                        pref.summary = entry
                            ?.let {
                                context.getString(R.string.console_model_selected, it.displayName, it.channelName)
                            }
                            ?: context.getString(R.string.console_agent_model_unavailable)
                    }
                }
                pref.setOnPreferenceClickListener {
                    if (entries.isEmpty()) {
                        true
                    } else {
                        showAgentModelPicker(entries, selected)
                        true
                    }
                }
            }
            agentCatalogJob?.isActive == true -> {
                pref.title = context.getString(R.string.console_agent_model)
                pref.summary = context.getString(R.string.console_model_loading)
                pref.setOnPreferenceClickListener { true }
            }
            else -> {
                pref.title = context.getString(R.string.console_agent_model)
                pref.summary = context.getString(
                    if (agentCatalogLoadError != null) R.string.console_agent_model_error
                    else R.string.console_agent_model_require_sign_in,
                )
                pref.setOnPreferenceClickListener {
                    agentCatalogLoadError = null
                    loadAgentCatalog()
                    true
                }
            }
        }
    }

    /** Mirrors [showModelPicker] for the Agent-kind catalog. */
    private fun showAgentModelPicker(entries: List<ConsoleCatalog.ModelEntry>, selected: String) {
        // A header before each channel's models, in the order buildEntries
        // produced; the entries themselves carry the channel label.
        val rows = buildList {
            var header: String? = null
            entries.forEach { entry ->
                if (entry.channelName != header) {
                    add(PickerRow(header = entry.channelName))
                    header = entry.channelName
                }
                add(PickerRow(entry = entry))
            }
        }
        val selectedId = ConsoleCatalog.findById(entries, selected)?.id
        val checked = rows.indexOfFirst { it.entry?.id == selectedId }
        var picked = -1
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = !rows[position].isHeader
            override fun getItemViewType(position: Int) = if (rows[position].isHeader) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                if (row.isHeader) {
                    val view = convertView as? TextView
                        ?: LayoutInflater.from(parent.context)
                            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                    view.text = row.header
                    TextViewCompat.setTextAppearance(
                        view,
                        android.R.style.TextAppearance_DeviceDefault_Small,
                    )
                    val typedValue = TypedValue()
                    view.context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
                    // A theme attribute may resolve to a color resource or a
                    // literal; pass the resolved color, never the resource id.
                    if (typedValue.resourceId != 0) {
                        view.setTextColor(ContextCompat.getColor(view.context, typedValue.resourceId))
                    } else {
                        view.setTextColor(typedValue.data)
                    }
                    return view
                }
                val view = convertView as? CheckedTextView
                    ?: LayoutInflater.from(parent.context)
                        .inflate(android.R.layout.simple_list_item_single_choice, parent, false) as CheckedTextView
                view.text = row.entry?.displayName
                return view
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.console_agent_model)
            .setSingleChoiceItems(adapter, checked) { _, which ->
                picked = which
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                rows.getOrNull(picked)?.entry?.let { entry ->
                    prefs.consoleAgentModelId.setValue(entry.id)
                    // A new model resets the variant to its default.
                    prefs.consoleAgentVariantId.setValue("")
                    updateConsoleEntries()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAgentVariantPref(pref: Preference) {
        val context = pref.context
        val selectedModel = prefs.consoleAgentModelId.getValue()
        val selectedVariant = prefs.consoleAgentVariantId.getValue()
        val entry = agentCatalog?.let { ConsoleCatalog.findById(it, selectedModel) }
        pref.title = context.getString(R.string.console_agent_variant)
        when {
            !isSignedIn() || agentCatalog == null || selectedModel.isBlank() -> {
                pref.summary = context.getString(R.string.console_variant_require_model)
                pref.setOnPreferenceClickListener { true }
            }
            entry == null -> {
                pref.summary = context.getString(R.string.console_agent_model_unavailable)
                pref.setOnPreferenceClickListener { true }
            }
            else -> {
                pref.summary = selectedVariant.ifBlank {
                    context.getString(R.string.console_variant_default)
                }
                pref.setOnPreferenceClickListener {
                    showAgentVariantPicker(entry, selectedVariant)
                    true
                }
            }
        }
    }

    /** Mirrors [showVariantPicker] for the agent model/variant prefs. */
    private fun showAgentVariantPicker(entry: ConsoleCatalog.ModelEntry, selected: String) {
        val context = requireContext()
        val ids = listOf("") + entry.variants
        val labels = ids.map {
            if (it.isBlank()) context.getString(R.string.console_variant_default) else it
        }.toTypedArray()
        val checked = ids.indexOf(selected).coerceAtLeast(0)
        var picked = -1
        AlertDialog.Builder(context)
            .setTitle(R.string.console_agent_variant)
            .setSingleChoiceItems(labels, checked) { _, which ->
                picked = which
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                if (picked >= 0) {
                    prefs.consoleAgentVariantId.setValue(ids[picked])
                    updateConsoleEntries()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Screen text accessibility status ---

    private fun updateStatusPref(pref: Preference) {
        val context = pref.context
        val enabled = ScreenTextProvider.isServiceEnabledInSettings(context)

        if (enabled) {
            pref.title = context.getString(R.string.screen_text_enabled)
            pref.summary = context.getString(R.string.screen_text_enabled_summary)
        } else {
            pref.title = context.getString(R.string.screen_text_not_enabled)
            pref.summary = context.getString(R.string.screen_text_not_enabled_summary)
        }
        pref.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh immediately in case state changed while we were paused
        // (e.g. the device flow finished, or the accessibility service was toggled).
        updateConsoleEntries()
        preferenceScreen.findPreference<Preference>("screen_text_accessibility_status")?.let {
            updateStatusPref(it)
        }
    }

    override fun onDestroyView() {
        // The device-flow poll lives in the view's lifecycle scope; its dialog
        // belongs to the activity window and must be dismissed by hand.
        dismissDeviceDialog()
        super.onDestroyView()
    }
}
