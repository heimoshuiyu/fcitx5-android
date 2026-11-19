/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Audio recorder that captures PCM audio from the microphone
 * and provides it as WAV-formatted bytes.
 *
 * Designed for voice recognition:
 * - Sample rate: 16000 Hz
 * - Channel: Mono
 * - Encoding: 16-bit PCM
 */
class AudioRecorder {

    companion object {
        private const val TAG = "VoiceAudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_FACTOR = 2
        private const val MAX_RECORDING_SECONDS = 60
        private const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_RECORDING_SECONDS
    }

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL,
        ENCODING,
    ) * BUFFER_FACTOR

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    private val _isRecordingFlow = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow

    /**
     * Start recording. Returns when stopRecording() is called.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun recordUntil(): ByteArray {
        return withContext(Dispatchers.IO) {
            val pcmBuffer = ByteArrayOutputStream()

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufferSize,
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    throw AudioRecordException("Failed to initialize AudioRecord")
                }

                audioRecord?.startRecording()
                isRecording = true
                _isRecordingFlow.value = true

                val readBuffer = ShortArray(bufferSize / 2)

                while (isActive && isRecording) {
                    val readCount = audioRecord?.read(readBuffer, 0, readBuffer.size)
                        ?: break

                    if (readCount > 0) {
                        for (i in 0 until readCount) {
                            val sample = readBuffer[i]
                            pcmBuffer.write(sample.toInt() and 0xFF)
                            pcmBuffer.write((sample.toInt() shr 8) and 0xFF)
                        }

                        val rms = calculateRms(readBuffer, readCount)
                        _amplitude.value = rms
                        if (pcmBuffer.size() >= MAX_PCM_BYTES) {
                            isRecording = false
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Recording error")
                throw e
            } finally {
                stopRecording()
            }

            val pcmData = pcmBuffer.toByteArray()
            if (pcmData.isEmpty()) ByteArray(0) else pcmToWav(pcmData)
        }
    }

    fun stopRecording() {
        isRecording = false
        _isRecordingFlow.value = false
        _amplitude.value = 0f

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Timber.w(e, "Error stopping AudioRecord")
        }
        audioRecord = null
    }

    private fun calculateRms(buffer: ShortArray, count: Int): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        val rms = Math.sqrt(sum / count)
        return (rms / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val wavBuffer = ByteArrayOutputStream()
        val totalDataLen = pcmData.size
        val totalFileLen = totalDataLen + 36

        wavBuffer.write("RIFF".toByteArray())
        writeInt(wavBuffer, totalFileLen)
        wavBuffer.write("WAVE".toByteArray())

        wavBuffer.write("fmt ".toByteArray())
        writeInt(wavBuffer, 16)
        writeShort(wavBuffer, 1.toShort())
        writeShort(wavBuffer, 1.toShort())
        writeInt(wavBuffer, SAMPLE_RATE)
        writeInt(wavBuffer, SAMPLE_RATE * 2)
        writeShort(wavBuffer, 2.toShort())
        writeShort(wavBuffer, 16.toShort())

        wavBuffer.write("data".toByteArray())
        writeInt(wavBuffer, totalDataLen)
        wavBuffer.write(pcmData)

        return wavBuffer.toByteArray()
    }

    private fun writeInt(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
        stream.write((value shr 16) and 0xFF)
        stream.write((value shr 24) and 0xFF)
    }

    private fun writeShort(stream: ByteArrayOutputStream, value: Short) {
        stream.write(value.toInt() and 0xFF)
        stream.write((value.toInt() shr 8) and 0xFF)
    }
}

class AudioRecordException(message: String) : Exception(message)
