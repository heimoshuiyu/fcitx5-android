/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

/**
 * Pure snapshot decisions for the auto hotword learning input. No Android
 * dependencies so the learning gates can be unit-tested on the JVM.
 *
 * Invariants enforced here:
 * - a final text without any letters or digits never reaches the agent
 *   (an app clearing the input after send, or a misaligned region read,
 *   must not look like the user deleted everything they said);
 * - a wiped region still learns from the last valid edited snapshot — the
 *   correction stays valuable even though the user (or the app) later
 *   cleared the field;
 * - an unchanged or punctuation-only snapshot never triggers a run.
 */
object HotwordSnapshotDecision {

    /** The letter-or-digit content of a snapshot, the comparison basis. */
    fun content(text: String): String = text.filter { it.isLetterOrDigit() }

    /**
     * The final text to learn when the transcribed region was wiped (the app
     * cleared the input after send, or the user deleted everything):
     * the last valid edited snapshot if it still carries a correction,
     * else null.
     */
    fun wipedSnapshotToLearn(original: String?, snapshot: String?): String? {
        if (original == null || snapshot == null) return null
        if (snapshot == original) return null
        val snapshotContent = content(snapshot)
        if (snapshotContent.isEmpty()) return null
        if (snapshotContent == content(original)) return null
        return snapshot
    }

    /**
     * The final text a flush may hand to the agent, or null to skip.
     * An effectively-empty final (blank or letter/digit-free) is never
     * learned, even though it differs from the original.
     */
    fun flushFinalToLearn(original: String?, current: String?): String? {
        if (original == null || current == null) return null
        if (current == original) return null
        val origContent = content(original)
        val currContent = content(current)
        if (currContent == origContent) return null
        if (currContent.isEmpty()) return null
        return current
    }
}
