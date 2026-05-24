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

    // --- Auto hotword tracking ---
    /** Text from last successful transcription */
    private var lastTranscribedText: String? = null
    /** Position where transcription was inserted (start offset in input field) */
    private var lastTranscribeStart: Int = -1
    private var lastTranscribeEnd: Int = -1
    /** Latest cached text from the transcribed region (updated on each selection change) */
    private var cachedCurrentText: String? = null

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
                    // Record transcription position for auto hotword detection
                    val cursorPos = service.currentInputSelection.start
                    lastTranscribedText = text
                    lastTranscribeStart = cursorPos
                    lastTranscribeEnd = cursorPos + text.length
                    cachedCurrentText = null
                    Timber.d("autoHotword: recorded transcription '$text' at [$lastTranscribeStart, $lastTranscribeEnd]")

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

        // Hotwords — merge manual and auto-learned lists
        val manualHotwords = prefs.hotwords.getValue().trim()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val autoHotwords = if (prefs.autoHotwords.getValue()) {
            prefs.autoHotwordsList.getValue().trim()
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else emptyList()
        val allHotwords = (manualHotwords + autoHotwords).distinct()
        if (allHotwords.isNotEmpty()) {
            parts.add("Hotwords: ${allHotwords.joinToString(" ")}")
        }

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

    /**
     * Cache the current text from the transcribed region.
     * Called on every selection update while the IME is visible.
     * Actual diff/hotword extraction is deferred to flushAutoHotword().
     */
    fun cacheCurrentText(newSelStart: Int, newSelEnd: Int) {
        val original = lastTranscribedText ?: return
        val insertStart = lastTranscribeStart
        val insertEnd = lastTranscribeEnd
        if (insertStart < 0) return

        val ic = service.currentInputConnection ?: return

        // Read enough text to cover the region [insertStart, insertEnd]
        val readLen = insertEnd + 100
        val textBefore = ic.getTextBeforeCursor(readLen, 0)?.toString() ?: return

        // Locate the start of the transcribed region in textBefore
        val regionStartInBefore = textBefore.length - newSelStart + insertStart
        if (regionStartInBefore < 0 || regionStartInBefore >= textBefore.length) return

        // Only cache text within the original region's bounds.
        // Use the original length as a rough upper bound — the user's edit
        // may be slightly shorter or longer, so allow some slack (2x).
        val maxLen = original.length * 2
        cachedCurrentText = textBefore.substring(regionStartInBefore,
            minOf(regionStartInBefore + maxLen, textBefore.length))
    }

    /**
     * Called when the input view is finishing or input field is changing.
     * Compares the last cached text with the original transcription
     * and extracts hotwords from the diff.
     */
    fun flushAutoHotword() {
        val original = lastTranscribedText
        val current = cachedCurrentText
        if (original == null || current == null) {
            resetTranscriptionTracking()
            return
        }

        // Clear tracking state regardless of outcome
        resetTranscriptionTracking()

        if (!prefs.autoHotwords.getValue()) return

        // No content change
        if (current == original) return

        // Only punctuation/whitespace change
        val origContent = original.filter { it.isLetterOrDigit() }
        val currContent = current.filter { it.isLetterOrDigit() }
        if (currContent == origContent) return

        Timber.d("autoHotword: flush detected edit")
        Timber.d("autoHotword: original='$original'")
        Timber.d("autoHotword: current='$current'")

        val hotwordCandidates = extractHotwords(original, current)
        if (hotwordCandidates.isEmpty()) return

        // Append to auto-learned hotwords (separate from manual hotwords)
        val existingManual = prefs.hotwords.getValue().trim()
        val manualWords = if (existingManual.isNotEmpty()) {
            existingManual.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else emptySet()

        val existingAuto = prefs.autoHotwordsList.getValue().trim()
        val autoWords = if (existingAuto.isNotEmpty()) {
            existingAuto.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else {
            mutableSetOf()
        }

        var added = 0
        for (word in hotwordCandidates) {
            if (word.length >= 2 && !manualWords.contains(word) && !autoWords.contains(word)) {
                autoWords.add(word)
                added++
            }
        }

        if (added > 0) {
            val newAutoHotwords = autoWords.joinToString("\n")
            prefs.autoHotwordsList.setValue(newAutoHotwords)
            Timber.d("autoHotword: added $added auto hotwords, total=${autoWords.size}: $hotwordCandidates")
        }
    }

    /**
     * Extract hotword candidates from the diff between original and current text.
     *
     * Algorithm:
     * 1. Strip punctuation/whitespace from both strings, keeping index mapping
     * 2. Find common prefix and suffix on the stripped versions
     * 3. The middle part of current (corrected) text is the diff
     * 4. Extract meaningful tokens from the diff region in the original current text
     */
    private fun extractHotwords(original: String, current: String): List<String> {
        // Quick sanity: if effective lengths are too different, it's a rewrite
        val origStripped = stripToContent(original)
        val currStripped = stripToContent(current)
        val lenRatio = currStripped.text.length.toFloat() /
            origStripped.text.length.toFloat().coerceAtLeast(1f)
        if (lenRatio < 0.3f || lenRatio > 3f) return emptyList()

        // Find common prefix length on stripped text
        var prefixLen = 0
        val minLen = minOf(origStripped.text.length, currStripped.text.length)
        while (prefixLen < minLen &&
            origStripped.text[prefixLen] == currStripped.text[prefixLen]
        ) {
            prefixLen++
        }

        // Find common suffix length (don't overlap with prefix)
        var suffixLen = 0
        while (suffixLen < minLen - prefixLen &&
            origStripped.text[origStripped.text.length - 1 - suffixLen] ==
            currStripped.text[currStripped.text.length - 1 - suffixLen]
        ) {
            suffixLen++
        }

        // If everything matches on content level, it's just punctuation changes — skip
        if (prefixLen + suffixLen >= origStripped.text.length &&
            prefixLen + suffixLen >= currStripped.text.length
        ) {
            return emptyList()
        }

        val unchangedLen = prefixLen + suffixLen

        // If the changed portion is more than 50% of content, likely a rewrite
        if (origStripped.text.length - unchangedLen > origStripped.text.length * 0.5f) return emptyList()
        if (currStripped.text.length - unchangedLen > currStripped.text.length * 0.5f) return emptyList()

        // Map the changed region back to indices in the original current string
        if (prefixLen >= currStripped.strippedToOriginal.size) return emptyList()
        val currentStart = currStripped.strippedToOriginal[prefixLen]
        val endIndex = currStripped.text.length - suffixLen
        val currentEnd = if (endIndex < currStripped.strippedToOriginal.size) {
            currStripped.strippedToOriginal[endIndex]
        } else {
            current.length
        }

        val changedText = current.substring(currentStart, currentEnd).trim()
        if (changedText.isEmpty()) return emptyList()

        Timber.d("autoHotword: diff prefix=$prefixLen suffix=$suffixLen changed='$changedText'")

        // Split the changed text by whitespace into tokens, keep meaningful ones
        return changedText
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
    }

    /**
     * Strip punctuation and whitespace from text, keeping a mapping from
     * stripped character index back to original string index.
     */
    private fun stripToContent(text: String): StrippedText {
        val content = StringBuilder()
        val indexMap = mutableListOf<Int>()
        for (i in text.indices) {
            val c = text[i]
            if (c.isLetterOrDigit()) {
                indexMap.add(i)
                content.append(c)
            }
        }
        return StrippedText(content.toString(), indexMap)
    }

    private data class StrippedText(
        val text: String,
        /** strippedToOriginal[i] = index in original string where stripped char i came from */
        val strippedToOriginal: List<Int>
    )

    /** Reset tracking state */
    fun resetTranscriptionTracking() {
        lastTranscribedText = null
        lastTranscribeStart = -1
        lastTranscribeEnd = -1
        cachedCurrentText = null
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
