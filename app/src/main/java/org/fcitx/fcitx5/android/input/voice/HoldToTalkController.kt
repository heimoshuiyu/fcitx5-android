/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.text.InputType
import android.view.inputmethod.InputConnection
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.TranscriptionHistoryManager
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionRecord
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

    private val openCodeBackend by lazy {
        OpenCodeBackend(
            getServerUrl = { prefs.serverUrl.getValue() },
            getAuthUsername = { prefs.authUsername.getValue() },
            getAuthPassword = { prefs.authPassword.getValue() },
            getModel = { prefs.opencodeModel.getValue() },
        )
    }
    private val whisperBackend by lazy {
        WhisperBackend(
            getUrl = { prefs.whisperUrl.getValue() },
            getApiKey = { prefs.whisperApiKey.getValue() },
            getModel = { prefs.whisperModel.getValue() },
            getLanguage = { prefs.whisperLanguage.getValue() },
        )
    }
    private val lalmBackend by lazy {
        LALMBackend(
            getUrl = { prefs.lalmUrl.getValue() },
            getApiKey = { prefs.lalmApiKey.getValue() },
            getModel = { prefs.lalmModel.getValue() },
            getSystemPrompt = { prefs.lalmSystemPrompt.getValue() },
            getAudioFormat = { prefs.lalmAudioFormat.getValue() },
            getTemperature = { prefs.lalmTemperature.getValue() },
        )
    }
    private val subscriptionBackend by lazy {
        SubscriptionBackend(
            getGatewayUrl = { prefs.subscriptionGatewayUrl.getValue() },
            getAccessToken = { prefs.subscriptionAccessToken.getValue() },
            getRefreshToken = { prefs.subscriptionRefreshToken.getValue() },
            onTokensUpdated = { access, refresh ->
                prefs.subscriptionAccessToken.setValue(access)
                prefs.subscriptionRefreshToken.setValue(refresh)
            },
            onSessionExpired = {
                prefs.subscriptionAccessToken.setValue("")
                prefs.subscriptionRefreshToken.setValue("")
            },
        )
    }

    /** Create backend based on current settings */
    private fun createBackend(): VoiceBackend {
        return when (prefs.backendType.getValue()) {
            VoiceBackendType.OpenCode -> openCodeBackend
            VoiceBackendType.Whisper -> whisperBackend
            VoiceBackendType.LALM -> lalmBackend
            VoiceBackendType.Subscription -> subscriptionBackend
        }
    }

    private var activeOperationJob: Job? = null

    enum class StartResult {
        STARTED, ALREADY_ACTIVE, NO_PERMISSION, NO_INPUT, SENSITIVE_INPUT
    }

    private data class InputTarget(
        val connection: InputConnection,
        val packageName: String?,
        val inputType: Int,
        val hintText: String?,
    )

    private var cachedTarget: InputTarget? = null
    private var cachedScreenshotRequestId: Long? = null

    fun startRecording(): StartResult {
        if (activeOperationJob?.isActive == true) {
            Timber.w("HoldToTalk already active")
            return StartResult.ALREADY_ACTIVE
        }

        if (service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Microphone permission not granted")
            return StartResult.NO_PERMISSION
        }

        if (isSensitiveInput()) return StartResult.SENSITIVE_INPUT

        val connection = service.currentInputConnection ?: return StartResult.NO_INPUT
        val editorInfo = service.currentInputEditorInfo
        val target = InputTarget(
            connection,
            editorInfo.packageName,
            editorInfo.inputType,
            editorInfo.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )

        // Clear previous cache when starting a new recording
        cachedAudio = null
        cachedTarget = target
        _state.value = State.RECORDING

        // Trigger screenshot capture while recording (async, will be ready by transcription time)
        // Only if the user has enabled the screenshot setting
        val screenshotRequestId = if (prefs.screenScreenshot.getValue()) {
            ScreenTextProvider.requestScreenshot()
        } else {
            ScreenTextProvider.clearScreenshot()
            null
        }
        cachedScreenshotRequestId = screenshotRequestId

        activeOperationJob = service.lifecycleScope.launch {
            try {
                val wavBytes = audioRecorder.recordUntil()
                if (wavBytes.isNotEmpty()) {
                    transcribeAudio(wavBytes, target, screenshotRequestId)
                } else {
                    resetToIdle()
                }
            } catch (e: CancellationException) {
                Timber.d("HoldToTalk recording cancelled")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "HoldToTalk recording error")
                resetToIdle()
            }
        }
        return StartResult.STARTED
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
    }

    fun cancel() {
        activeOperationJob?.cancel()
        activeOperationJob = null
        audioRecorder.stopRecording()
        resetToIdle()
    }

    /**
     * Retry transcription using cached audio.
     * Returns false if no cached audio is available.
     */
    fun retry(): Boolean {
        val audio = cachedAudio ?: return false
        val target = cachedTarget ?: return false
        if (!isCurrentTarget(target)) {
            dismissRetry()
            return false
        }
        val screenshotRequestId = cachedScreenshotRequestId
        if (activeOperationJob?.isActive == true) return false
        activeOperationJob = service.lifecycleScope.launch {
            try {
                transcribeAudio(audio, target, screenshotRequestId)
            } catch (e: CancellationException) {
                Timber.d("HoldToTalk retry cancelled")
                throw e
            }
        }
        return true
    }

    /** Discard cached audio and return to idle */
    fun dismissRetry() {
        resetToIdle()
    }

    /** Cancel an in-progress transcription */
    fun cancelTranscription() {
        activeOperationJob?.cancel()
        activeOperationJob = null
        resetToIdle()
        Timber.d("Transcription cancelled by user")
    }

    private suspend fun transcribeAudio(
        audioBytes: ByteArray,
        target: InputTarget,
        screenshotRequestId: Long?,
    ) {
        _state.value = State.TRANSCRIBING
        val startTime = System.currentTimeMillis()
        val audioSize = audioBytes.size.toLong()
        // Rough estimate: WAV at 16kHz 16-bit mono ≈ 32KB/sec, subtract 44-byte header
        val audioDurationSec = ((audioSize - 44).toFloat() / 32000f).coerceAtLeast(0f)

        try {
            if (!isCurrentTarget(target)) {
                resetToIdle()
                return
            }
            val backend = createBackend()
            val ic = target.connection
            val selectedText = if (prefs.surroundingTextContext.getValue()) when (backend) {
                is LALMBackend -> if (prefs.lalmVoiceEdit.getValue()) {
                    ic.getSelectedText(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } else null
                is OpenCodeBackend -> if (prefs.opencodeVoiceEdit.getValue()) {
                    ic.getSelectedText(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } else null
                is SubscriptionBackend -> ic.getSelectedText(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            } else null
            Timber.d("editMode: backend=${backend.name}, selectedText=${if (selectedText != null) "${selectedText.length} chars" else "null"}")
            val prompt = buildPrompt(selectedText != null, target)
            val screenshot = if (prefs.screenScreenshot.getValue()) {
                ScreenTextProvider.awaitScreenshot(screenshotRequestId)
            } else null
            Timber.d("screenshot: ${if (screenshot != null) "${screenshot.length} chars" else "null"}")
            val result = backend.transcribe(audioBytes, "audio/wav", prompt, selectedText, screenshot)
            currentCoroutineContext().ensureActive()
            val durationMs = System.currentTimeMillis() - startTime

            result.onSuccess { transcriptionResult ->
                if (!isCurrentTarget(target)) {
                    resetToIdle()
                    return@onSuccess
                }
                val text = transcriptionResult.text
                // Save successful transcription record
                saveRecord(
                    backendType = backend.name,
                    editMode = selectedText != null,
                    resultText = text,
                    success = true,
                    errorMessage = "",
                    durationMs = durationMs,
                    audioDurationSec = audioDurationSec,
                    audioSizeBytes = audioSize,
                )

                if (text.isNotBlank()) {
                    // Record transcription position for auto hotword detection
                    val cursorPos = service.currentInputSelection.start
                    lastTranscribedText = text
                    lastTranscribeStart = cursorPos
                    lastTranscribeEnd = cursorPos + text.length
                    cachedCurrentText = null
                    Timber.d("autoHotword: recorded transcription range [$lastTranscribeStart, $lastTranscribeEnd]")

                    service.commitText(text)
                    resetToIdle()
                    listener?.onSuccess(text)
                } else {
                    // Empty transcription — cache for retry
                    cachedAudio = audioBytes
                    cachedTarget = target
                    cachedScreenshotRequestId = screenshotRequestId
                    _state.value = State.RETRY_AVAILABLE
                    listener?.onEmptyResult()
                }
            }.onFailure { error ->
                if (!isCurrentTarget(target)) {
                    resetToIdle()
                    return@onFailure
                }
                val errorMessage = userFacingError(error)
                Timber.w("Transcription failed (${backend.name}): ${error.javaClass.simpleName}")
                // Save failed transcription record
                saveRecord(
                    backendType = backend.name,
                    editMode = selectedText != null,
                    resultText = "",
                    success = false,
                    errorMessage = errorMessage,
                    durationMs = durationMs,
                    audioDurationSec = audioDurationSec,
                    audioSizeBytes = audioSize,
                )
                // Cache for retry
                cachedAudio = audioBytes
                cachedTarget = target
                cachedScreenshotRequestId = screenshotRequestId
                _state.value = State.RETRY_AVAILABLE
                listener?.onError(errorMessage)
            }
        } catch (e: CancellationException) {
            Timber.d("Transcription cancelled")
            throw e
        } catch (e: Exception) {
            if (!isCurrentTarget(target)) {
                resetToIdle()
                return
            }
            val durationMs = System.currentTimeMillis() - startTime
            val errorMessage = userFacingError(e)
            Timber.w("Transcription error: ${e.javaClass.simpleName}")
            saveRecord(
                backendType = prefs.backendType.getValue().name,
                editMode = false,
                resultText = "",
                success = false,
                errorMessage = errorMessage,
                durationMs = durationMs,
                audioDurationSec = audioDurationSec,
                audioSizeBytes = audioSize,
            )
            cachedAudio = audioBytes
            cachedTarget = target
            cachedScreenshotRequestId = screenshotRequestId
            _state.value = State.RETRY_AVAILABLE
            listener?.onError(errorMessage)
        }
    }

    private fun saveRecord(
        backendType: String,
        editMode: Boolean,
        resultText: String,
        success: Boolean,
        errorMessage: String,
        durationMs: Long,
        audioDurationSec: Float,
        audioSizeBytes: Long,
    ) {
        if (!prefs.saveHistory.getValue()) return
        try {
            val record = TranscriptionRecord(
                backendType = backendType,
                prompt = "",
                editMode = editMode,
                selectedText = "",
                resultText = resultText,
                success = success,
                errorMessage = errorMessage,
                durationMs = durationMs,
                rawResponseBody = "",
                audioDurationSec = audioDurationSec,
                audioSizeBytes = audioSizeBytes,
                screenshotBase64 = "",
            )
            service.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    TranscriptionHistoryManager.insert(record)
                } catch (e: Exception) {
                    Timber.w("Failed to save transcription record: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to save transcription record")
        }
    }

    private fun describeInputFieldType(type: Int): String? {
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

    private fun getAppName(packageName: String?): String? {
        packageName ?: return null
        return try {
            val appInfo = service.packageManager.getApplicationInfo(packageName, 0)
            service.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPrompt(editMode: Boolean, target: InputTarget): String? {
        val ic = target.connection
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
            parts.add("<hotwords>${allHotwords.joinToString(" ")}</hotwords>")
        }

        var surrounding = ""
        if (prefs.surroundingTextContext.getValue()) {
            getAppName(target.packageName)?.let { parts.add("<app>$it</app>") }
            target.hintText?.let { parts.add("<hint>$it</hint>") }
            describeInputFieldType(target.inputType)?.let {
                parts.add("<input_field_type>$it</input_field_type>")
            }
            val before = ic.getTextBeforeCursor(1000, 0)?.toString()?.trim()
            val selected = if (editMode) null else ic.getSelectedText(0)?.toString()?.trim()
            val after = ic.getTextAfterCursor(500, 0)?.toString()?.trim()
            surrounding = listOfNotNull(before, selected, after)
                .filter { it.isNotEmpty() }
                .joinToString("")
            if (surrounding.isNotEmpty()) {
                parts.add("<surrounding_text>\n$surrounding\n</surrounding_text>")
            }
        }

        // Add screen context from accessibility service (if enabled)
        if (prefs.screenTextContext.getValue()) {
            val screenText = ScreenTextProvider.getScreenText(target.packageName)
            if (screenText.isNotBlank() && screenText != surrounding) {
                parts.add("<visible_screen_text>\n$screenText\n</visible_screen_text>")
            }
        }

        // Add clipboard content as context
        if (prefs.clipboardContext.getValue()) {
            val clipboardEntry = ClipboardManager.lastEntry
            val clipboardText = clipboardEntry?.takeUnless { it.sensitive }?.text?.trim()
            if (!clipboardText.isNullOrEmpty()) {
                parts.add("<clipboard_content>\n${clipboardText.take(1000)}\n</clipboard_content>")
            }
        }

        val result = if (parts.isNotEmpty()) parts.joinToString("\n") else null
        Timber.d("buildPrompt: ${parts.size} parts, totalLen=${result?.length ?: 0}")
        return result
    }

    private fun isCurrentTarget(target: InputTarget): Boolean {
        return service.currentInputConnection === target.connection &&
            service.currentInputEditorInfo.packageName == target.packageName
    }

    private fun resetToIdle() {
        cachedAudio = null
        cachedTarget = null
        cachedScreenshotRequestId = null
        ScreenTextProvider.clearScreenshot()
        _state.value = State.IDLE
    }

    private fun userFacingError(error: Throwable): String {
        return (error as? TranscriptionException)?.message?.takeIf { it.isNotBlank() }
            ?: service.getString(R.string.voice_input_error)
    }

    private fun isSensitiveInput(): Boolean {
        val info = service.currentInputEditorInfo
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            info.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        ) {
            return true
        }
        val inputType = info.inputType
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> true
                else -> false
            }
            InputType.TYPE_CLASS_NUMBER ->
                inputType and InputType.TYPE_MASK_VARIATION ==
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
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
        Timber.d("autoHotword: comparing original and edited text")

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
        listener = null
        cancel()
    }

    interface Listener {
        fun onSuccess(text: String) {}
        fun onError(message: String) {}
        fun onEmptyResult() {}
    }
}
