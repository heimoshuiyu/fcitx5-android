/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single hotword learning agent run.
 *
 * Created at flush time with the original transcription and the final edited
 * text; the run itself may execute later (manual run/retry), so the row is
 * persisted before the agent is invoked.
 */
@Entity(tableName = HotwordAgentRun.TABLE_NAME)
data class HotwordAgentRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Timestamp when the run was enqueued (epoch millis) */
    val createdAt: Long = System.currentTimeMillis(),

    /** pending | running | succeeded | failed */
    val status: String,

    /** How many times the run was claimed for execution */
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,

    /** The transcription text as it was originally inserted */
    val originalText: String,

    /** The edited text captured from the transcribed region at flush time */
    val finalText: String,

    /** Serialized list of agent events (reasoning/text/tool calls/results) */
    @ColumnInfo(defaultValue = "")
    val eventsJson: String = "",

    /** Words that actually entered the auto hotword list (one per line) */
    @ColumnInfo(defaultValue = "")
    val submittedWords: String = "",

    /** Human-readable failure reason, empty on success */
    @ColumnInfo(defaultValue = "")
    val error: String = "",

    /** The configured agent model id at run time, for display */
    @ColumnInfo(defaultValue = "")
    val modelId: String = "",

    /** Wall-clock duration of the last attempt in milliseconds */
    @ColumnInfo(defaultValue = "0")
    val durationMs: Long = 0,
) {
    companion object {
        const val TABLE_NAME = "hotword_agent_run"

        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"
    }
}
