/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleChoiceDialogTest {

    @Test
    fun modelRowsKeepSectionsBusinessIdsAndExactLabels() {
        val longName = "DeepSeek V4.1 Flash with an exceptionally long descriptive model name"
        val rows = consoleChoiceRows(listOf(
            ConsoleChoiceEntry("opencode/gpt", "GPT", "OpenCode"),
            ConsoleChoiceEntry("manhattan/kimi", "Kimi K3.1 Pro", "Manhattan Project"),
            ConsoleChoiceEntry("manhattan/deepseek", longName, "Manhattan Project"),
            ConsoleChoiceEntry("other/kimi", "Kimi K3.1 Pro", "Other providers"),
        ))

        assertEquals(
            listOf("OpenCode", "Manhattan Project", "Other providers"),
            rows.filterIsInstance<ConsoleChoiceRow.Header>().map { it.text },
        )
        val options = rows.filterIsInstance<ConsoleChoiceRow.Option>()
        assertEquals("manhattan/kimi", options[1].id)
        assertEquals("manhattan/kimi", consoleChoiceSelectedId(rows, "manhattan/kimi"))
        assertNull(consoleChoiceSelectedId(rows, "missing/model"))
        assertEquals("other/kimi", options.last().id)
        assertEquals(longName, options[2].text)
    }

    @Test
    fun variantRowsHaveNoHeadersAndDefaultKeepsEmptyId() {
        val rows = consoleChoiceRows(listOf(
            ConsoleChoiceEntry("", "Default"),
            ConsoleChoiceEntry("high", "high"),
        ))

        assertTrue(rows.none { it is ConsoleChoiceRow.Header })
        assertEquals("", (rows.first() as ConsoleChoiceRow.Option).id)
        assertEquals("", consoleChoiceSelectedId(rows, ""))
        assertEquals(
            listOf("", "high"),
            rows.map { (it as ConsoleChoiceRow.Option).id },
        )
    }

    @Test
    fun dialogRowsRejectDuplicateIdsAndKeepSnapshot() {
        val source = mutableListOf<ConsoleChoiceRow>(
            ConsoleChoiceRow.Header("OpenCode"),
            ConsoleChoiceRow.Option("opencode/gpt", "GPT"),
        )
        val snapshot = consoleChoiceSnapshot(source)
        source += ConsoleChoiceRow.Option("new/model", "New")

        assertEquals(2, snapshot.size)
        assertEquals(
            listOf("opencode/gpt"),
            snapshot.filterIsInstance<ConsoleChoiceRow.Option>().map { it.id },
        )
        val duplicateFailure = runCatching {
            consoleChoiceSnapshot(listOf(
                ConsoleChoiceRow.Option("duplicate", "First"),
                ConsoleChoiceRow.Option("duplicate", "Second"),
            ))
        }.exceptionOrNull()
        assertTrue(duplicateFailure is IllegalArgumentException)
    }
}
