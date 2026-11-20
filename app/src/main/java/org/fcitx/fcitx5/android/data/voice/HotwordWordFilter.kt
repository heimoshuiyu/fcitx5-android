/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import kotlinx.serialization.Serializable

/**
 * Pure validation and deduplication for words proposed by the hotword
 * learning agent. No Android dependencies so it can be unit-tested on the
 * JVM; the manager owns the actual list write.
 */
object HotwordWordFilter {

    /** Words shorter than this are ignored (single CJK chars, stray letters). */
    const val MIN_WORD_LENGTH = 2

    /** Words longer than this are ignored (they are likely whole sentences). */
    const val MAX_WORD_LENGTH = 64

    /** Cap on accepted words per tool call. */
    const val MAX_WORDS_PER_CALL = 50

    /** Cap on the total auto hotword list; new words beyond it are skipped. */
    const val MAX_AUTO_LIST_SIZE = 500

    @Serializable
    data class Skipped(val word: String, val reason: String)

    /**
     * The decision for one tool call. `added` holds words that entered the
     * auto hotword list; `skipped` documents every rejection with a stable
     * reason so the agent (and the records UI) can see what happened.
     */
    @Serializable
    data class Result(
        val added: List<String> = emptyList(),
        val skipped: List<Skipped> = emptyList(),
    )

    fun filter(
        candidates: List<String>,
        manualWords: Set<String>,
        existingAutoWords: Set<String>,
        autoListSize: Int = existingAutoWords.size,
    ): Result {
        val added = mutableListOf<String>()
        val skipped = mutableListOf<Skipped>()
        val batch = mutableSetOf<String>()
        for (raw in candidates) {
            val word = raw.trim()
            when {
                word.isEmpty() -> skipped += Skipped(raw, "empty")
                word.length < MIN_WORD_LENGTH -> skipped += Skipped(word, "too_short")
                word.length > MAX_WORD_LENGTH -> skipped += Skipped(word, "too_long")
                !batch.add(word) -> skipped += Skipped(word, "duplicate_batch")
                word in manualWords -> skipped += Skipped(word, "duplicate_manual")
                word in existingAutoWords -> skipped += Skipped(word, "duplicate_auto")
                autoListSize + added.size >= MAX_AUTO_LIST_SIZE ->
                    skipped += Skipped(word, "list_full")
                added.size >= MAX_WORDS_PER_CALL -> skipped += Skipped(word, "too_many")
                else -> added += word
            }
        }
        return Result(added, skipped)
    }
}
