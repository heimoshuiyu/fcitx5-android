/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TranscriptionRecord::class],
    version = 2,
    exportSchema = true,
)
abstract class TranscriptionDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao
}
