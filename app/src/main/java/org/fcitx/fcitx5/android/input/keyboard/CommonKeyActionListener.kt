/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.HoldToTalkAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.voice.FloatingVoiceIndicator
import org.fcitx.fcitx5.android.input.voice.HoldToTalkController
import org.fcitx.fcitx5.android.input.voice.MicrophonePermissionActivity
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.constraintlayout.parentId

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler(),
    InputBroadcastReceiver {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val inputView by manager.inputView()
    private val theme by manager.theme()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private var backspaceSwipeState = Stopped

    // Hold-to-talk state
    private var holdToTalkController: HoldToTalkController? = null
    private var floatingIndicator: FloatingVoiceIndicator? = null
    private var amplitudeJob: kotlinx.coroutines.Job? = null
    private var stateObserverJob: kotlinx.coroutines.Job? = null
    private var voiceInputAllowed = true

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate, except prediction candidates
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                // preedit not empty, maybe there are candidates to select ...
                select(0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    sendKey(action.act, action.states.states, action.code)
                }
                is SymAction -> service.postFcitxJob {
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    enumerateIme()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        SpaceLongPressBehavior.HoldToTalk -> {
                            // Should not reach here — handled by HoldToTalkAction
                        }
                    }
                }
                is HoldToTalkAction -> {
                    if (action.start) {
                        startHoldToTalk()
                    } else {
                        stopHoldToTalk()
                    }
                }
                 else -> {}
            }
        }
    }

    private fun startHoldToTalk() {
        if (!voiceInputAllowed) {
            Toast.makeText(service, R.string.voice_input_sensitive_field, Toast.LENGTH_SHORT).show()
            return
        }
        // Create controller and indicator if needed
        if (holdToTalkController == null) {
            holdToTalkController = HoldToTalkController(service)
            startStateObserver()
        }
        if (floatingIndicator == null) {
            val indicator = FloatingVoiceIndicator(service, theme)
            floatingIndicator = indicator
            indicator.onRetryClicked = {
                holdToTalkController?.retry()
            }
            indicator.onCancelClicked = {
                holdToTalkController?.dismissRetry()
            }
            indicator.onCancelTranscriptionClicked = {
                holdToTalkController?.cancelTranscription()
            }
            // Cover the complete keyboard surface, including the candidate/tool bar.
            val containerView = inputView.keyboardView as ConstraintLayout
            containerView.post {
                if (floatingIndicator === indicator && indicator.view.parent == null) {
                    containerView.addView(
                        indicator.view,
                        ConstraintLayout.LayoutParams(0, 0).apply {
                            startToStart = parentId
                            endToEnd = parentId
                            topToTop = parentId
                            bottomToBottom = parentId
                        },
                    )
                    indicator.view.bringToFront()
                }
            }
        }

        val controller = holdToTalkController!!
        val indicator = floatingIndicator!!

        // Start recording
        when (val result = controller.startRecording()) {
            HoldToTalkController.StartResult.STARTED -> {
                indicator.showRecording()
                // Observe amplitude for waveform
                amplitudeJob?.cancel()
                amplitudeJob = service.lifecycleScope.launch {
                    controller.amplitude.collect { amp ->
                        indicator.waveformView.post {
                            indicator.waveformView.setAmplitude(amp)
                        }
                    }
                }
            }
            HoldToTalkController.StartResult.NO_PERMISSION -> {
                cleanupHoldToTalk()
                val intent = android.content.Intent(service, MicrophonePermissionActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
            }
            HoldToTalkController.StartResult.ALREADY_ACTIVE -> {
                // Already recording, ignore
            }
            HoldToTalkController.StartResult.NO_INPUT -> cleanupHoldToTalk()
            HoldToTalkController.StartResult.SENSITIVE_INPUT -> {
                cleanupHoldToTalk()
                Toast.makeText(
                    service,
                    R.string.voice_input_sensitive_field,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun stopHoldToTalk() {
        val controller = holdToTalkController ?: return
        val indicator = floatingIndicator ?: return

        if (controller.state.value != HoldToTalkController.State.RECORDING) return
        controller.stopRecording()
        indicator.showTranscribing()
    }

    private fun startStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = service.lifecycleScope.launch {
            holdToTalkController?.state?.collect { state ->
                val indicator = floatingIndicator ?: return@collect
                when (state) {
                    HoldToTalkController.State.IDLE -> {
                        indicator.hide()
                        amplitudeJob?.cancel()
                        amplitudeJob = null
                    }
                    HoldToTalkController.State.RECORDING -> {
                        // UI already shown by startHoldToTalk
                    }
                    HoldToTalkController.State.TRANSCRIBING -> {
                        indicator.showTranscribing()
                        amplitudeJob?.cancel()
                        amplitudeJob = null
                    }
                    HoldToTalkController.State.RETRY_AVAILABLE -> {
                        amplitudeJob?.cancel()
                        amplitudeJob = null
                    }
                }
            }
        }

        holdToTalkController?.listener = object : HoldToTalkController.Listener {
            override fun onSuccess(text: String) {
                floatingIndicator?.view?.post {
                    floatingIndicator?.hide()
                }
            }

            override fun onError(message: String) {
                floatingIndicator?.view?.post {
                    floatingIndicator?.showRetry(message)
                }
            }

            override fun onEmptyResult() {
                floatingIndicator?.view?.post {
                    floatingIndicator?.showRetry(service.getString(R.string.voice_input_empty))
                }
            }
        }
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        // Cache current text for auto hotword detection
        holdToTalkController?.cacheCurrentText(start, end)
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo, capFlags: CapabilityFlags) {
        holdToTalkController?.flushAutoHotword()
        cleanupHoldToTalk()
        voiceInputAllowed = !capFlags.has(CapabilityFlag.Password) &&
            !capFlags.has(CapabilityFlag.Sensitive)
    }

    override fun onWindowDetached(window: org.fcitx.fcitx5.android.input.wm.InputWindow) {
        // Keyboard hidden — flush auto hotword detection, then clean up
        holdToTalkController?.flushAutoHotword()
        cleanupHoldToTalk()
    }

    private fun cleanupHoldToTalk() {
        // Remove indicator view from parent to prevent leaks
        floatingIndicator?.let { indicator ->
            (indicator.view.parent as? android.view.ViewGroup)?.removeView(indicator.view)
        }
        floatingIndicator = null
        holdToTalkController?.destroy()
        holdToTalkController = null
        amplitudeJob?.cancel()
        amplitudeJob = null
        stateObserverJob?.cancel()
        stateObserverJob = null
    }
}
