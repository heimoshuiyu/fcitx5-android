/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TranscriptionDao {
    @Insert
    suspend fun insert(record: TranscriptionRecord): Long

    @Query("SELECT * FROM ${TranscriptionRecord.TABLE_NAME} ORDER BY timestamp DESC")
    suspend fun getAll(): List<TranscriptionRecord>

    @Query("SELECT * FROM ${TranscriptionRecord.TABLE_NAME} ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<TranscriptionRecord>

    @Query("SELECT * FROM ${TranscriptionRecord.TABLE_NAME} WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TranscriptionRecord?

    @Query("SELECT COUNT(*) FROM ${TranscriptionRecord.TABLE_NAME}")
    suspend fun count(): Int

    @Query("DELETE FROM ${TranscriptionRecord.TABLE_NAME} WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ${TranscriptionRecord.TABLE_NAME}")
    suspend fun deleteAll()

    @Query("DELETE FROM ${TranscriptionRecord.TABLE_NAME} WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
