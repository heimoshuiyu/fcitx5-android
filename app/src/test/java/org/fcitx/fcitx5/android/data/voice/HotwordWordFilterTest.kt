/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class HotwordWordFilterTest {

    @Test
    fun filtersLengthAndDuplicates() {
        val result = HotwordWordFilter.filter(
            candidates = listOf("OpenBao", "   ", "a", "x".repeat(65), "OpenBao", "GLM", "GLM"),
            manualWords = setOf("GLM"),
            existingAutoWords = emptySet(),
        )
        assertEquals(listOf("OpenBao"), result.added)
        assertEquals(
            listOf("empty", "too_short", "too_long", "duplicate_batch", "duplicate_manual", "duplicate_batch"),
            result.skipped.map { it.reason },
        )
    }

    @Test
    fun deduplicatesAgainstExistingAutoList() {
        val result = HotwordWordFilter.filter(
            candidates = listOf("OpenBao", "NewWord"),
            manualWords = emptySet(),
            existingAutoWords = setOf("OpenBao"),
        )
        assertEquals(listOf("NewWord"), result.added)
        assertEquals(listOf("duplicate_auto"), result.skipped.map { it.reason })
    }

    @Test
    fun capsWordsPerCall() {
        val many = (1..60).map { "word$it" }
        val result = HotwordWordFilter.filter(many, emptySet(), emptySet())
        assertEquals(HotwordWordFilter.MAX_WORDS_PER_CALL, result.added.size)
        assertEquals(10, result.skipped.count { it.reason == "too_many" })
    }

    @Test
    fun capsTotalAutoListSize() {
        val existing = (1..500).map { "old$it" }.toSet()
        val result = HotwordWordFilter.filter(
            listOf("brand", "new"),
            manualWords = emptySet(),
            existingAutoWords = existing,
        )
        assertEquals(0, result.added.size)
        assertEquals(2, result.skipped.count { it.reason == "list_full" })
    }
}
