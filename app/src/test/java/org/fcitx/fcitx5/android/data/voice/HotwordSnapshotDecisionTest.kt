/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotwordSnapshotDecisionTest {

    @Test
    fun wipedAfterEditLearnsTheEditButNotClearsWithoutCorrection() {
        // edited → field cleared: the last valid correction is still valuable.
        assertEquals("msgs", HotwordSnapshotDecision.wipedSnapshotToLearn("msg", "msgs"))
        // original → field cleared: nothing was corrected, skip.
        assertNull(HotwordSnapshotDecision.wipedSnapshotToLearn("msg", "msg"))
        assertNull(HotwordSnapshotDecision.wipedSnapshotToLearn("msg", null))
        assertNull(HotwordSnapshotDecision.wipedSnapshotToLearn(null, "msgs"))
        // blank or letter-free snapshots are never a valid correction.
        assertNull(HotwordSnapshotDecision.wipedSnapshotToLearn("msg", "  "))
        // punctuation-only edit: no letter/digit change, skip.
        assertNull(HotwordSnapshotDecision.wipedSnapshotToLearn("msg", "msg?!"))
    }

    @Test
    fun blankOrLetterFreeFinalNeverRuns() {
        assertEquals("msgs", HotwordSnapshotDecision.flushFinalToLearn("msg", "msgs"))
        assertNull(HotwordSnapshotDecision.flushFinalToLearn("msg", "msg"))
        assertNull(HotwordSnapshotDecision.flushFinalToLearn("msg", null))
        // An effectively-empty final must not reach the agent even though it
        // differs from the original.
        assertNull(HotwordSnapshotDecision.flushFinalToLearn("msg", "  "))
        assertNull(HotwordSnapshotDecision.flushFinalToLearn("msg", "?!"))
    }
}
