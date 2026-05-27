/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.input.voice.VoiceBackendType

/**
 * A single transcription history record.
 * Stores all data from a voice transcription attempt for debugging and analysis.
 */
@Entity(tableName = TranscriptionRecord.TABLE_NAME)
data class TranscriptionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Timestamp when the transcription was initiated (epoch millis) */
    val timestamp: Long = System.currentTimeMillis(),

    /** Which backend was used */
    val backendType: String,

    /** The prompt sent to the backend (may be null) */
    @ColumnInfo(defaultValue = "")
    val prompt: String = "",

    /** Whether voice edit mode was active */
    @ColumnInfo(defaultValue = "0")
    val editMode: Boolean = false,

    /** The selected text (only non-null in edit mode) */
    @ColumnInfo(defaultValue = "")
    val selectedText: String = "",

    /** Transcription result text (empty if failed) */
    @ColumnInfo(defaultValue = "")
    val resultText: String = "",

    /** Whether the transcription succeeded */
    val success: Boolean,

    /** Error message if failed */
    @ColumnInfo(defaultValue = "")
    val errorMessage: String = "",

    /** Time taken for the transcription API call in milliseconds */
    @ColumnInfo(defaultValue = "0")
    val durationMs: Long = 0,

    /** Raw response body from the backend API (JSON string) */
    @ColumnInfo(defaultValue = "")
    val rawResponseBody: String = "",

    /** Audio duration in seconds (approximate, from recording) */
    @ColumnInfo(defaultValue = "0")
    val audioDurationSec: Float = 0f,

    /** Audio size in bytes */
    @ColumnInfo(defaultValue = "0")
    val audioSizeBytes: Long = 0,

    /** Screenshot as data URI (e.g. "data:image/jpeg;base64,..."), empty if none */
    @ColumnInfo(defaultValue = "")
    val screenshotBase64: String = "",
) {
    companion object {
        const val TABLE_NAME = "transcription_record"
    }
}
