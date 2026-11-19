/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionDao
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionDatabase
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionRecord
import timber.log.Timber

object TranscriptionHistoryManager : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {

    private const val MAX_RECORDS = 100
    private const val RETENTION_DAYS = 30L
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    private lateinit var db: TranscriptionDatabase
    private lateinit var dao: TranscriptionDao
    private lateinit var initialization: Deferred<Unit>

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transcription_record ADD COLUMN screenshotBase64 TEXT NOT NULL DEFAULT ''")
        }
    }

    fun init(context: Context) {
        db = Room
            .databaseBuilder(context, TranscriptionDatabase::class.java, "transcription_db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        dao = db.transcriptionDao()
        initialization = async {
            if (AppPrefs.getInstance().voiceInput.saveHistory.getValue()) {
                prune()
            } else {
                withContext(Dispatchers.IO) { db.clearAllTables() }
            }
        }
        Timber.d("TranscriptionHistoryManager initialized")
    }

    suspend fun insert(record: TranscriptionRecord): Long {
        initialization.await()
        return withContext(Dispatchers.IO) {
            val id = dao.insert(record)
            prune()
            Timber.d("Saved transcription record id=$id backend=${record.backendType} success=${record.success} duration=${record.durationMs}ms")
            id
        }
    }

    suspend fun getPage(limit: Int, offset: Int): List<TranscriptionRecord> {
        initialization.await()
        return withContext(Dispatchers.IO) { dao.getPage(limit, offset) }
    }

    suspend fun getById(id: Long): TranscriptionRecord? {
        initialization.await()
        return withContext(Dispatchers.IO) { dao.getById(id) }
    }

    suspend fun count(): Int {
        initialization.await()
        return withContext(Dispatchers.IO) { dao.count() }
    }

    suspend fun deleteById(id: Long) {
        initialization.await()
        withContext(Dispatchers.IO) { dao.deleteById(id) }
    }

    suspend fun deleteAll() {
        initialization.await()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    private suspend fun prune() {
        withContext(Dispatchers.IO) {
            dao.redactSensitiveDetails()
            dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS)
            dao.trimTo(MAX_RECORDS)
        }
    }
}
