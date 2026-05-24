/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.text.InputType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber

/**
 * Lightweight controller for hold-to-talk voice input.
 *
 * Caches recorded audio so the user can retry transcription
 * without re-recording.
 */
class HoldToTalkController(private val service: FcitxInputMethodService) {

    enum class State {
        IDLE, RECORDING, TRANSCRIBING, RETRY_AVAILABLE
    }

    private val prefs = AppPrefs.getInstance().voiceInput

    private val audioRecorder = AudioRecorder()
    val amplitude: StateFlow<Float> = audioRecorder.amplitude

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    var listener: Listener? = null

    /** Cached audio bytes for retry */
    private var cachedAudio: ByteArray? = null

    /** Create backend based on current settings */
    private fun createBackend(): VoiceBackend {
        return when (val type = prefs.backendType.getValue()) {
            VoiceBackendType.OpenCode -> OpenCodeBackend(
                getServerUrl = { prefs.serverUrl.getValue() },
                getAuthUsername = { prefs.authUsername.getValue() },
                getAuthPassword = { prefs.authPassword.getValue() },
                getModel = { prefs.opencodeModel.getValue() },
            )
            VoiceBackendType.Whisper -> WhisperBackend(
                getUrl = { prefs.whisperUrl.getValue() },
                getApiKey = { prefs.whisperApiKey.getValue() },
                getModel = { prefs.whisperModel.getValue() },
                getLanguage = { prefs.whisperLanguage.getValue() },
            )
            VoiceBackendType.LALM -> LALMBackend(
                getUrl = { prefs.lalmUrl.getValue() },
                getApiKey = { prefs.lalmApiKey.getValue() },
                getModel = { prefs.lalmModel.getValue() },
                getSystemPrompt = { prefs.lalmSystemPrompt.getValue() },
                getAudioFormat = { prefs.lalmAudioFormat.getValue() },
                getTemperature = { prefs.lalmTemperature.getValue() },
            )
        }
    }

    private var recordingJob: Job? = null

    enum class StartResult {
        STARTED, ALREADY_ACTIVE, NO_PERMISSION
    }

    fun startRecording(): StartResult {
        if (recordingJob != null && recordingJob?.isActive == true) {
            Timber.w("HoldToTalk already active")
            return StartResult.ALREADY_ACTIVE
        }

        if (service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Microphone permission not granted")
            return StartResult.NO_PERMISSION
        }

        // Clear previous cache when starting a new recording
        cachedAudio = null
        _state.value = State.RECORDING

        recordingJob = service.lifecycleScope.launch {
            try {
                val wavBytes = audioRecorder.recordUntil()
                if (wavBytes.isNotEmpty()) {
                    transcribeAudio(wavBytes)
                } else {
                    _state.value = State.IDLE
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Timber.d("HoldToTalk recording cancelled")
                _state.value = State.IDLE
            } catch (e: Exception) {
                Timber.e(e, "HoldToTalk recording error")
                _state.value = State.IDLE
            }
        }
        return StartResult.STARTED
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
    }

    fun cancel() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecorder.stopRecording()
        cachedAudio = null
        _state.value = State.IDLE
    }

    /**
     * Retry transcription using cached audio.
     * Returns false if no cached audio is available.
     */
    fun retry(): Boolean {
        val audio = cachedAudio ?: return false
        service.lifecycleScope.launch {
            transcribeAudio(audio)
        }
        return true
    }

    /** Discard cached audio and return to idle */
    fun dismissRetry() {
        cachedAudio = null
        _state.value = State.IDLE
    }

    /** Cancel an in-progress transcription */
    fun cancelTranscription() {
        recordingJob?.cancel()
        recordingJob = null
        cachedAudio = null
        _state.value = State.IDLE
        Timber.d("Transcription cancelled by user")
    }

    private suspend fun transcribeAudio(audioBytes: ByteArray) {
        _state.value = State.TRANSCRIBING
        try {
            val backend = createBackend()
            val ic = service.currentInputConnection
            val selectedText = when (backend) {
                is LALMBackend -> if (prefs.lalmVoiceEdit.getValue()) {
                    ic?.getSelectedText(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } else null
                is OpenCodeBackend -> if (prefs.opencodeVoiceEdit.getValue()) {
                    ic?.getSelectedText(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } else null
                else -> null
            }
            Timber.d("editMode: backend=${backend.name}, selectedText=${if (selectedText != null) "${selectedText.length} chars" else "null"}")
            val prompt = buildPrompt(selectedText != null)
            val result = backend.transcribe(audioBytes, "audio/wav", prompt, selectedText)

            result.onSuccess { text ->
                if (text.isNotBlank()) {
                    service.commitText(text)
                    cachedAudio = null
                    _state.value = State.IDLE
                    listener?.onSuccess(text)
                } else {
                    // Empty transcription — cache for retry
                    cachedAudio = audioBytes
                    _state.value = State.RETRY_AVAILABLE
                    listener?.onEmptyResult()
                }
            }.onFailure { error ->
                Timber.e(error, "Transcription failed (${backend.name})")
                // Cache for retry
                cachedAudio = audioBytes
                _state.value = State.RETRY_AVAILABLE
                listener?.onError(error.message ?: "Transcription failed")
            }
        } catch (e: Exception) {
            Timber.e(e, "Transcription error")
            cachedAudio = audioBytes
            _state.value = State.RETRY_AVAILABLE
            listener?.onError(e.message ?: "Transcription error")
        }
    }

    private fun describeInputFieldType(): String? {
        val info = service.currentInputEditorInfo ?: return null
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION

        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email address"

                InputType.TYPE_TEXT_VARIATION_URI -> "URL"
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "person name"
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "postal address"
                InputType.TYPE_TEXT_VARIATION_PHONETIC -> "phonetic text"
                InputType.TYPE_TEXT_VARIATION_FILTER -> "filter/list search"
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "web form text"
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "long message"
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "short message (SMS)"
                else -> "plain text"
            }
            InputType.TYPE_CLASS_NUMBER -> "number"
            InputType.TYPE_CLASS_PHONE -> "phone number"
            InputType.TYPE_CLASS_DATETIME -> when (variation) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> "date"
                InputType.TYPE_DATETIME_VARIATION_TIME -> "time"
                else -> "date and time"
            }
            else -> null
        }
    }

    private fun buildPrompt(editMode: Boolean = false): String? {
        val ic = service.currentInputConnection ?: return null
        val parts = mutableListOf<String>()

        val fieldType = describeInputFieldType()
        if (fieldType != null) parts.add("Input field type: $fieldType")

        val before = ic.getTextBeforeCursor(1000, 0)?.toString()?.trim()
        val selected = if (editMode) null else ic.getSelectedText(0)?.toString()?.trim()
        val after = ic.getTextAfterCursor(500, 0)?.toString()?.trim()
        val surrounding = listOfNotNull(before, selected, after)
            .filter { it.isNotEmpty() }
            .joinToString("")
        if (surrounding.isNotEmpty()) parts.add("Surrounding text:\n$surrounding")

        // Add screen context from accessibility service (if enabled)
        val screenText = ScreenTextProvider.screenText
        Timber.d("ScreenTextProvider: enabled=${ScreenTextProvider.isEnabled}, textLen=${screenText.length}, isNotBlank=${screenText.isNotBlank()}, surroundingLen=${surrounding.length}")
        if (screenText.isNotBlank() && screenText != surrounding) {
            Timber.d("ScreenText added to prompt")
            parts.add("Visible screen text:\n$screenText")
        }

        // Add clipboard content as context
        val clipboardText = ClipboardManager.lastEntry?.text?.trim()
        if (!clipboardText.isNullOrEmpty()) {
            parts.add("Clipboard content:\n${clipboardText.take(1000)}")
        }

        val result = if (parts.isNotEmpty()) parts.joinToString("\n") else null
        Timber.d("buildPrompt: ${parts.size} parts, totalLen=${result?.length ?: 0}")
        parts.forEachIndexed { i, part ->
            Timber.d("buildPrompt part[$i] len=${part.length}: ${part.take(500)}")
        }
        return result
    }

    fun destroy() {
        cancel()
    }

    interface Listener {
        fun onSuccess(text: String) {}
        fun onError(message: String) {}
        fun onEmptyResult() {}
    }
}
