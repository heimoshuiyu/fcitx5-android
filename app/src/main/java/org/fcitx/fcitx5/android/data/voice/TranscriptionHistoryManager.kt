/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionDao
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionDatabase
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionRecord
import timber.log.Timber

object TranscriptionHistoryManager : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {

    private lateinit var db: TranscriptionDatabase
    private lateinit var dao: TranscriptionDao

    fun init(context: Context) {
        db = Room
            .databaseBuilder(context, TranscriptionDatabase::class.java, "transcription_db")
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        dao = db.transcriptionDao()
        Timber.d("TranscriptionHistoryManager initialized")
    }

    suspend fun insert(record: TranscriptionRecord): Long {
        return withContext(Dispatchers.IO) {
            val id = dao.insert(record)
            Timber.d("Saved transcription record id=$id backend=${record.backendType} success=${record.success} duration=${record.durationMs}ms")
            id
        }
    }

    suspend fun getAll(): List<TranscriptionRecord> {
        return withContext(Dispatchers.IO) { dao.getAll() }
    }

    suspend fun getPage(limit: Int, offset: Int): List<TranscriptionRecord> {
        return withContext(Dispatchers.IO) { dao.getPage(limit, offset) }
    }

    suspend fun getById(id: Long): TranscriptionRecord? {
        return withContext(Dispatchers.IO) { dao.getById(id) }
    }

    suspend fun count(): Int {
        return withContext(Dispatchers.IO) { dao.count() }
    }

    suspend fun deleteById(id: Long) {
        withContext(Dispatchers.IO) { dao.deleteById(id) }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) { dao.deleteAll() }
    }
}
