/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * Serializes every read-modify-write cycle on the auto hotword preference.
 * The raw lines remain untouched unless a mutation explicitly changes them.
 */
class AutoHotwordStore(
    private val readRaw: () -> String,
    private val writeRaw: (String) -> Unit,
    private val readManualRaw: () -> String,
) {

    data class EntryId(val text: String, val occurrence: Int)

    data class Entry(val id: EntryId, val index: Int, val text: String)

    data class OperationResult(val entries: List<Entry>, val error: String? = null)

    private data class ParsedEntry(
        val text: String,
        val separatorBefore: String,
        val separatorAfter: String,
    )

    private val mutex = Mutex()

    fun snapshot(): List<Entry> = parse(readRaw())

    suspend fun add(rawWord: String): OperationResult = mutate { raw ->
        val parsed = parseLines(raw)
        val lines = toEntries(parsed)
        val autoWords = lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        val result = HotwordWordFilter.filter(
            candidates = listOf(rawWord),
            manualWords = manualWords(),
            existingAutoWords = autoWords.toSet(),
            autoListSize = autoWords.size,
        )
        val added = result.added.singleOrNull()
        if (added == null) {
            OperationResult(lines, result.skipped.firstOrNull()?.reason ?: "empty") to null
        } else {
            val newRaw = appendRaw(raw, parsed, listOf(added))
            OperationResult(parse(newRaw)) to newRaw
        }
    }

    suspend fun edit(id: EntryId, rawWord: String): OperationResult = mutate { raw ->
        val parsed = parseLines(raw)
        val lines = toEntries(parsed)
        val target = lines.firstOrNull { it.id == id }
        if (target == null) {
            OperationResult(lines, ERROR_NOT_FOUND) to null
        } else {
            val existing = lines.filterIndexed { index, _ -> index != target.index }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val result = HotwordWordFilter.filter(
                candidates = listOf(rawWord),
                manualWords = manualWords(),
                existingAutoWords = existing,
                // Replacing an entry never increases the list size. This also
                // lets a legacy over-limit or invalid value be repaired.
                autoListSize = 0,
            )
            val replacement = result.added.singleOrNull()
            if (replacement == null) {
                OperationResult(lines, result.skipped.firstOrNull()?.reason ?: "empty") to null
            } else {
                val newLines = parsed.toMutableList()
                newLines[target.index] = newLines[target.index].copy(text = replacement)
                val newRaw = serialize(newLines)
                OperationResult(parse(newRaw)) to newRaw
            }
        }
    }

    suspend fun delete(ids: Set<EntryId>): List<Entry> {
        if (ids.isEmpty()) return snapshot()
        return mutate { raw ->
            val parsed = parseLines(raw)
            val lines = toEntries(parsed)
            val remaining = parsed.filterIndexed { index, _ -> lines[index].id !in ids }
            val newRaw = serialize(remaining)
            parse(newRaw) to newRaw
        }
    }

    suspend fun clear(): List<Entry> = mutate {
        emptyList<Entry>() to ""
    }

    /** Filter a normalized view without normalizing any stored legacy line. */
    suspend fun applyAgentCandidates(candidates: List<String>): HotwordWordFilter.Result =
        mutate { raw ->
            val parsed = parseLines(raw)
            val autoWords = parsed.map { it.text.trim() }.filter { it.isNotEmpty() }
            val result = HotwordWordFilter.filter(
                candidates = candidates,
                manualWords = manualWords(),
                existingAutoWords = autoWords.toSet(),
            )
            if (result.added.isEmpty()) {
                result to null
            } else {
                result to appendRaw(raw, parsed, result.added)
            }
        }

    private fun manualWords(): Set<String> = readManualRaw()
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private suspend fun <T> mutate(block: (String) -> Pair<T, String?>): T = mutex.withLock {
        val oldRaw = readRaw()
        val (result, newRaw) = block(oldRaw)
        if (newRaw != null && newRaw != oldRaw) writeRaw(newRaw)
        result
    }

    companion object {
        const val ERROR_NOT_FOUND = "not_found"

        val shared: AutoHotwordStore by lazy {
            AutoHotwordStore(
                readRaw = { AppPrefs.getInstance().voiceInput.autoHotwordsList.getValue() },
                writeRaw = { AppPrefs.getInstance().voiceInput.autoHotwordsList.setValue(it) },
                readManualRaw = { AppPrefs.getInstance().voiceInput.hotwords.getValue() },
            )
        }

        fun visibleEntries(entries: List<Entry>, query: String): List<Entry> {
            if (query.isEmpty()) return entries
            return entries.filter { it.text.contains(query, ignoreCase = true) }
        }

        private fun parse(raw: String): List<Entry> = toEntries(parseLines(raw))

        private fun toEntries(lines: List<ParsedEntry>): List<Entry> {
            val occurrences = mutableMapOf<String, Int>()
            return lines.mapIndexed { index, line ->
                val occurrence = occurrences[line.text] ?: 0
                occurrences[line.text] = occurrence + 1
                Entry(EntryId(line.text, occurrence), index, line.text)
            }
        }

        /**
         * A terminal separator belongs to the preceding real entry; the
         * sentinel after it is not another entry. Empty text before an actual
         * separator is a real, independently addressable blank line.
         */
        private fun parseLines(raw: String): List<ParsedEntry> {
            if (raw.isEmpty()) return emptyList()
            val lines = mutableListOf<ParsedEntry>()
            var separatorBefore = ""
            var lineStart = 0
            var index = 0
            while (index < raw.length) {
                val separatorLength = when {
                    raw[index] == '\r' && index + 1 < raw.length && raw[index + 1] == '\n' -> 2
                    raw[index] == '\r' || raw[index] == '\n' -> 1
                    else -> 0
                }
                if (separatorLength == 0) {
                    index++
                    continue
                }
                val separatorAfter = raw.substring(index, index + separatorLength)
                lines += ParsedEntry(
                    raw.substring(lineStart, index),
                    separatorBefore,
                    separatorAfter,
                )
                separatorBefore = separatorAfter
                index += separatorLength
                lineStart = index
            }
            if (lineStart < raw.length) {
                lines += ParsedEntry(raw.substring(lineStart), separatorBefore, "")
            }
            return lines
        }

        /**
         * Between two entries, use the later entry's original separatorBefore.
         * Only the new last entry emits separatorAfter. If deletion makes two
         * independent separators newly adjacent, a pending LF after a lone CR
         * becomes CR: changing only the pending segment preserves two physical
         * separators instead of producing a CRLF. Original CRLF segments are
         * emitted atomically and existing output bytes are never rewritten.
         */
        private fun serialize(lines: List<ParsedEntry>): String = buildString {
            var loneCrBeforeEmptyText = false
            fun appendSeparator(segment: String) {
                if (segment.isEmpty()) return
                val adjusted = if (loneCrBeforeEmptyText && segment == "\n") "\r" else segment
                append(adjusted)
                loneCrBeforeEmptyText = adjusted == "\r"
            }

            fun appendText(text: String) {
                append(text)
                if (text.isNotEmpty()) loneCrBeforeEmptyText = false
            }

            lines.forEachIndexed { index, line ->
                if (index > 0) appendSeparator(line.separatorBefore)
                appendText(line.text)
                if (index == lines.lastIndex) appendSeparator(line.separatorAfter)
            }
        }

        /** Keep the complete old raw value as a byte-for-byte prefix. */
        private fun appendRaw(
            raw: String,
            lines: List<ParsedEntry>,
            words: List<String>,
        ): String {
            if (words.isEmpty()) return raw
            val suffix = words.joinToString("\n")
            if (raw.isEmpty()) return suffix
            val separator = if (lines.last().separatorAfter.isEmpty()) "\n" else ""
            return raw + separator + suffix
        }
    }
}

/** Monotonic view-refresh generation; calls are confined to the main thread. */
internal class AutoHotwordRefreshGate {
    private var generation = 0L

    fun next(): Long = ++generation

    fun isCurrent(token: Long): Boolean = token == generation

    fun invalidate() {
        generation++
    }
}
