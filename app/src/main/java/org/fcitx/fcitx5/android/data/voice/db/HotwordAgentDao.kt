/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HotwordAgentDao {
    @Insert
    suspend fun insert(run: HotwordAgentRun): Long

    /**
     * Atomically claim a pending/failed run for execution. Returns the number
     * of affected rows: 0 means the row is already running or not retryable.
     */
    @Query(
        "UPDATE ${HotwordAgentRun.TABLE_NAME} SET status = 'running', " +
            "attemptCount = attemptCount + 1 " +
            "WHERE id = :id AND status IN ('pending', 'failed')"
    )
    suspend fun claim(id: Long): Int

    @Query(
        "UPDATE ${HotwordAgentRun.TABLE_NAME} SET status = :status, " +
            "eventsJson = :eventsJson, submittedWords = :submittedWords, " +
            "error = :error, modelId = :modelId, durationMs = :durationMs " +
            "WHERE id = :id"
    )
    suspend fun finish(
        id: Long,
        status: String,
        eventsJson: String,
        submittedWords: String,
        error: String,
        modelId: String,
        durationMs: Long,
    )

    /** Sweep rows left running by a dead process back to pending. */
    @Query("UPDATE ${HotwordAgentRun.TABLE_NAME} SET status = 'pending' WHERE status = 'running'")
    suspend fun resetStaleRunning(): Int

    @Query(
        "SELECT * FROM ${HotwordAgentRun.TABLE_NAME} " +
            "ORDER BY createdAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getPage(limit: Int, offset: Int): List<HotwordAgentRun>

    @Query("SELECT * FROM ${HotwordAgentRun.TABLE_NAME} WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HotwordAgentRun?

    @Query("DELETE FROM ${HotwordAgentRun.TABLE_NAME} WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ${HotwordAgentRun.TABLE_NAME}")
    suspend fun deleteAll()

    /**
     * Retention only ever touches terminal rows; pending/running runs are
     * never auto-deleted. The outer DELETE repeats the terminal filter so a
     * non-terminal row outside the keep-set can never be removed.
     */
    @Query(
        "DELETE FROM ${HotwordAgentRun.TABLE_NAME} " +
            "WHERE status IN ('succeeded', 'failed') AND createdAt < :cutoff"
    )
    suspend fun deleteTerminalOlderThan(cutoff: Long)

    @Query(
        "DELETE FROM ${HotwordAgentRun.TABLE_NAME} " +
            "WHERE status IN ('succeeded', 'failed') AND id NOT IN " +
            "(SELECT id FROM ${HotwordAgentRun.TABLE_NAME} " +
            "WHERE status IN ('succeeded', 'failed') " +
            "ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun trimTerminalTo(keep: Int)

    /**
     * Keep a not-yet-run row as-is and record why it was not attempted.
     * No status change and no attempt counted; [finish] overwrites the
     * reason once a real run happens.
     */
    @Query(
        "UPDATE ${HotwordAgentRun.TABLE_NAME} SET error = :error " +
            "WHERE id = :id AND status IN ('pending', 'failed')"
    )
    suspend fun defer(id: Long, error: String)
}
