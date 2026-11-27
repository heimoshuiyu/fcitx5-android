/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHotwordStoreTest {

    private class Fixture(initialRaw: String, private val manualRaw: String = "") {
        var raw = initialRaw
        val store = AutoHotwordStore({ raw }, { raw = it }, { manualRaw })
    }

    @Test
    fun legacyBlankLinesAndDuplicatesSurviveReadAndAdd() = runTest {
        val fixture = Fixture("  legacy  \n\nsame\nsame\n")
        val initial = fixture.store.snapshot()

        assertEquals(listOf("  legacy  ", "", "same", "same"), initial.map { it.text })
        assertEquals(listOf(0, 1), initial.filter { it.text == "same" }.map { it.id.occurrence })
        assertEquals(listOf(0), initial.filter { it.text.isEmpty() }.map { it.id.occurrence })

        val result = fixture.store.add("  newWord  ")

        assertNull(result.error)
        assertEquals("  legacy  \n\nsame\nsame\nnewWord", fixture.raw)
        assertEquals(initial.map { it.id }, fixture.store.snapshot().take(initial.size).map { it.id })
    }

    @Test
    fun filteredDeleteOnlyRemovesSelectedOccurrence() = runTest {
        val fixture = Fixture("Alpha\nBeta\nAlpha\nGamma")
        val visible = AutoHotwordStore.visibleEntries(fixture.store.snapshot(), "alpha")
        assertEquals(listOf(0, 2), visible.map { it.index })

        val remaining = fixture.store.delete(setOf(visible.last().id))

        assertEquals(listOf("Alpha", "Beta", "Gamma"), remaining.map { it.text })
        assertEquals("Alpha\nBeta\nGamma", fixture.raw)
    }

    @Test
    fun addAndEditValidateWithoutBlockingLegacyRepair() = runTest {
        val fixture = Fixture("x\nyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy\nOpenCode", manualRaw = "Manual")
        val tooShort = fixture.store.add(" a ")
        val duplicate = fixture.store.add("OpenCode")
        val duplicateManual = fixture.store.add("Manual")
        assertEquals("too_short", tooShort.error)
        assertEquals("duplicate_auto", duplicate.error)
        assertEquals("duplicate_manual", duplicateManual.error)

        val invalid = fixture.store.snapshot().first { it.text.length == 65 }
        assertEquals("duplicate_auto", fixture.store.edit(invalid.id, "OpenCode").error)
        val repaired = fixture.store.edit(invalid.id, "  ValidWord  ")
        assertNull(repaired.error)
        assertEquals(listOf("x", "ValidWord", "OpenCode"), repaired.entries.map { it.text })

        val full = Fixture((1..501).joinToString("\n") { "word$it" })
        assertEquals("list_full", full.store.add("overflow").error)
        val first = full.store.snapshot().first()
        assertNull(full.store.edit(first.id, "replacement").error)
        assertEquals(501, full.store.snapshot().size)
    }

    @Test
    fun concurrentUiAndAgentMutationsDoNotLoseAppends() = runTest {
        val fixture = Fixture("")
        coroutineScope {
            (0 until 40).map { index ->
                launch(Dispatchers.Default) {
                    if (index % 2 == 0) {
                        assertNull(fixture.store.add("ui$index").error)
                    } else {
                        val result = fixture.store.applyAgentCandidates(listOf("agent$index"))
                        assertEquals(listOf("agent$index"), result.added)
                    }
                }
            }.joinAll()
        }

        val words = fixture.store.snapshot().map { it.text }
        assertEquals(40, words.size)
        assertEquals(40, words.distinct().size)
        assertTrue((0 until 40).all { index ->
            words.contains(if (index % 2 == 0) "ui$index" else "agent$index")
        })
    }

    @Test
    fun agentAppendPreservesLegacyPrefixAndSecondOccurrence() = runTest {
        val original = " foo\n\nfoo"
        val fixture = Fixture(original)
        val before = fixture.store.snapshot()
        val originalSecondFoo = before.single { it.index == 2 }
        assertEquals(AutoHotwordStore.EntryId("foo", 0), originalSecondFoo.id)

        val result = fixture.store.applyAgentCandidates(listOf("new"))

        assertEquals(listOf("new"), result.added)
        assertEquals("$original\nnew", fixture.raw)
        assertEquals(before, fixture.store.snapshot().dropLast(1))

        val remaining = fixture.store.delete(setOf(originalSecondFoo.id))
        assertEquals(listOf(" foo", "", "new"), remaining.map { it.text })
        assertEquals(" foo\n\nnew", fixture.raw)
    }

    @Test
    fun agentAppendHandlesEmptyAndTrailingSeparatorsWithoutChangingOldIds() = runTest {
        val cases = listOf(
            "" to "new",
            "foo\n" to "foo\nnew",
            "foo\r\n" to "foo\r\nnew",
            "foo\r" to "foo\rnew",
        )
        for ((original, expected) in cases) {
            val fixture = Fixture(original)
            val before = fixture.store.snapshot()

            val result = fixture.store.applyAgentCandidates(listOf("new"))

            assertEquals(listOf("new"), result.added)
            assertEquals(expected, fixture.raw)
            val after = fixture.store.snapshot()
            assertEquals(before, after.dropLast(1))
            assertEquals("new", after.last().text)
        }
    }

    @Test
    fun terminalSeparatorsAndRealBlankLinesSurviveDeletion() = runTest {
        val terminal = Fixture("foo\n")
        val terminalEntries = terminal.store.snapshot()
        assertEquals(listOf("foo"), terminalEntries.map { it.text })
        terminal.store.delete(setOf(terminalEntries.single().id))
        assertEquals("", terminal.raw)
        assertTrue(terminal.store.snapshot().isEmpty())

        val withBlank = Fixture("foo\n\n")
        val beforeBlankDelete = withBlank.store.snapshot()
        assertEquals(listOf("foo", ""), beforeBlankDelete.map { it.text })
        val blankRemaining = withBlank.store.delete(setOf(beforeBlankDelete.first().id))
        assertEquals("\n", withBlank.raw)
        assertEquals(listOf(""), blankRemaining.map { it.text })
        assertEquals(1, withBlank.store.snapshot().size)

        val blanks = Fixture("\n\n")
        val blankEntries = blanks.store.snapshot()
        assertEquals(listOf("", ""), blankEntries.map { it.text })
        val oneBlankRemaining = blanks.store.delete(setOf(blankEntries[1].id))
        assertEquals("\n", blanks.raw)
        assertEquals(listOf(""), oneBlankRemaining.map { it.text })
        assertEquals(1, blanks.store.snapshot().size)
    }

    @Test
    fun uiMutationsPreserveMixedSeparators() = runTest {
        val original = "foo\r\nbar\rbaz\nqux"
        val fixture = Fixture(original)

        val added = fixture.store.add("new")
        assertNull(added.error)
        assertEquals("$original\nnew", fixture.raw)

        val bar = added.entries.single { it.text == "bar" }
        val edited = fixture.store.edit(bar.id, "edited")
        assertNull(edited.error)
        assertEquals("foo\r\nedited\rbaz\nqux\nnew", fixture.raw)

        val baz = edited.entries.single { it.text == "baz" }
        val remaining = fixture.store.delete(setOf(baz.id))
        assertEquals("foo\r\nedited\nqux\nnew", fixture.raw)
        assertEquals(listOf("foo", "edited", "qux", "new"), remaining.map { it.text })
    }

    @Test
    fun deletingMiddleEntryCannotFuseCrAndLfAcrossBlank() = runTest {
        val fixture = Fixture("A\rX\n\nB")
        val before = fixture.store.snapshot()
        assertEquals(listOf("A", "X", "", "B"), before.map { it.text })

        val remaining = fixture.store.delete(setOf(before[1].id))

        assertEquals("A\n\nB", fixture.raw)
        assertEquals(listOf("A", "", "B"), remaining.map { it.text })
        assertEquals(listOf("A", "", "B"), fixture.store.snapshot().map { it.text })
    }

    @Test
    fun deletionSubsetsPreserveEntriesAcrossSeparatorsAndMutations() = runTest {
        val base = "A\r\n\nA\rB"
        val endings = listOf("", "\n", "\r", "\r\n")
        var deletionCases = 0
        fun expectedAppend(raw: String, word: String): String = when {
            raw.isEmpty() -> word
            raw.endsWith('\n') || raw.endsWith('\r') -> raw + word
            else -> "$raw\n$word"
        }

        for (ending in endings) {
            val raw = base + ending
            val original = Fixture(raw).store.snapshot()
            assertEquals(listOf("A", "", "A", "B"), original.map { it.text })

            for (mask in 0 until (1 shl original.size)) {
                val fixture = Fixture(raw)
                val before = fixture.store.snapshot()
                val selected = before.filterIndexed { index, _ ->
                    (mask and (1 shl index)) != 0
                }.map { it.id }.toSet()
                val expected = before.filterIndexed { index, _ ->
                    (mask and (1 shl index)) == 0
                }.map { it.text }

                val remaining = fixture.store.delete(selected)

                assertEquals("raw=$raw mask=$mask", expected, remaining.map { it.text })
                assertEquals("raw=$raw mask=$mask", expected, fixture.store.snapshot().map { it.text })
                if (expected.isEmpty()) assertEquals("", fixture.raw)
                deletionCases++
            }

            val addFixture = Fixture(raw)
            val beforeAdd = addFixture.store.snapshot()
            val added = addFixture.store.add("new")
            assertNull(added.error)
            assertEquals(expectedAppend(raw, "new"), addFixture.raw)
            assertEquals(beforeAdd, added.entries.dropLast(1))
            assertEquals(beforeAdd.map { it.text } + "new", added.entries.map { it.text })

            val editFixture = Fixture(raw)
            val target = editFixture.store.snapshot()[2]
            val edited = editFixture.store.edit(target.id, "edited")
            assertNull(edited.error)
            assertEquals("A\r\n\nedited\rB$ending", editFixture.raw)
            assertEquals(listOf("A", "", "edited", "B"), edited.entries.map { it.text })

            val agentFixture = Fixture(raw)
            val beforeAgent = agentFixture.store.snapshot()
            val agentResult = agentFixture.store.applyAgentCandidates(listOf("agent"))
            assertEquals(listOf("agent"), agentResult.added)
            assertEquals(expectedAppend(raw, "agent"), agentFixture.raw)
            assertEquals(beforeAgent, agentFixture.store.snapshot().dropLast(1))
        }
        assertEquals(64, deletionCases)
    }

    @Test
    fun deletingMiddleEntryCannotFuseRepeatedCrWithLf() = runTest {
        val fixture = Fixture("P\r\rX\nB")
        val before = fixture.store.snapshot()
        assertEquals(listOf("P", "", "X", "B"), before.map { it.text })

        val remaining = fixture.store.delete(setOf(before[2].id))

        assertEquals("P\r\rB", fixture.raw)
        assertEquals(listOf("P", "", "B"), remaining.map { it.text })
        assertEquals(listOf("P", "", "B"), fixture.store.snapshot().map { it.text })
    }

    @Test
    fun exhaustiveMixedTopologyDeletionSubsetsRoundTrip() = runTest {
        val separators = listOf("\r", "\n", "\r\n")
        val trailingSeparators = listOf("", "\r", "\n", "\r\n")
        var validShapes = 0
        var deletionCases = 0

        fun representable(
            texts: List<String>,
            between: List<String>,
            trailing: String,
        ): Boolean {
            for (index in texts.indices) {
                if (texts[index].isNotEmpty()) continue
                val before = if (index == 0) "" else between[index - 1]
                val after = if (index < texts.lastIndex) between[index] else trailing
                // A trailing sentinel is not an entry, and adjacent CR+LF is
                // parsed as one CRLF rather than two separators around a blank.
                if (after.isEmpty() || (before == "\r" && after == "\n")) return false
            }
            return true
        }

        for (length in 1..4) {
            var separatorShapes = 1
            repeat(length - 1) { separatorShapes *= separators.size }
            for (textMask in 0 until (1 shl length)) {
                val texts = List(length) { index ->
                    if ((textMask and (1 shl index)) == 0) "" else "A"
                }
                for (separatorMask in 0 until separatorShapes) {
                    var encoded = separatorMask
                    val between = mutableListOf<String>()
                    repeat(length - 1) {
                        between += separators[encoded % separators.size]
                        encoded /= separators.size
                    }
                    for (trailing in trailingSeparators) {
                        if (!representable(texts, between, trailing)) continue
                        val raw = buildString {
                            texts.forEachIndexed { index, text ->
                                append(text)
                                append(if (index < texts.lastIndex) between[index] else trailing)
                            }
                        }
                        val original = Fixture(raw).store.snapshot()
                        assertEquals("raw=$raw", texts, original.map { it.text })
                        validShapes++

                        for (deleteMask in 0 until (1 shl length)) {
                            val fixture = Fixture(raw)
                            val before = fixture.store.snapshot()
                            val selected = before.filterIndexed { index, _ ->
                                (deleteMask and (1 shl index)) != 0
                            }.map { it.id }.toSet()
                            val expected = before.filterIndexed { index, _ ->
                                (deleteMask and (1 shl index)) == 0
                            }.map { it.text }

                            val remaining = fixture.store.delete(selected)

                            val context = "raw=$raw mask=$deleteMask result=" + fixture.raw
                            assertEquals(context, expected, remaining.map { it.text })
                            assertEquals(context, expected, fixture.store.snapshot().map { it.text })
                            if (expected.isEmpty()) assertEquals(context, "", fixture.raw)
                            deletionCases++
                        }
                    }
                }
            }
        }
        assertEquals(1549, validShapes)
        assertEquals(22398, deletionCases)
    }

    @Test
    fun latestRefreshGenerationWinsAndInvalidationRejectsCommits() {
        val gate = AutoHotwordRefreshGate()
        val stale = gate.next()
        val latest = gate.next()
        var visible = "initial"
        fun commit(token: Long, value: String) {
            if (gate.isCurrent(token)) visible = value
        }

        commit(latest, "latest")
        commit(stale, "stale")
        assertEquals("latest", visible)

        gate.invalidate()
        commit(latest, "destroyed")
        assertEquals("latest", visible)
    }
}
