/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TranscriptionRecord::class, HotwordAgentRun::class],
    version = 3,
    exportSchema = true,
)
abstract class TranscriptionDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun hotwordAgentDao(): HotwordAgentDao
}
