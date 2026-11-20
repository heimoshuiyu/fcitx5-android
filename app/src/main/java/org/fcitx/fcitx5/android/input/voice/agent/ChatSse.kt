/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice.agent

/**
 * Minimal SSE reader for the Chat Completions stream. The gateway writes
 * each event as one `data:` line, but this follows the SSE framing rules
 * anyway so a proxy splitting reads or joining lines cannot corrupt a
 * frame: `data:` lines accumulate until a blank line dispatches one event
 * (joined with newlines), `:` comment lines such as the gateway's
 * `: keep-alive` are skipped, and every other field name is ignored.
 *
 * Line terminators: the gateway emits `\n` (and CRLF proxies are covered by
 * `BufferedSource.readUtf8Line`, which strips a trailing `\r`); a bare-CR
 * stream is not something this gateway produces, so it is not handled.
 */
object ChatSse {

    /** One dispatched SSE event; only `data` events exist on this stream. */
    class Event(val data: String)

    class Parser {
        private var buffer: StringBuilder? = null

        /**
         * Feed one line without its terminator; returns the events the line
         * completed (the blank line after a run of `data:` lines).
         */
        fun feed(line: String): List<Event> {
            if (line.isEmpty()) return dispatch()
            val separator = line.indexOf(':')
            val field = if (separator == -1) line else line.substring(0, separator)
            if (field != "data") return emptyList()
            // A single leading space after the colon is not part of the value.
            var value = if (separator == -1) "" else line.substring(separator + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            (buffer ?: StringBuilder().also { buffer = it }).append(value).append('\n')
            return emptyList()
        }

        /** Flush a trailing event left without its blank line at EOF. */
        fun finish(): List<Event> = dispatch()

        private fun dispatch(): List<Event> {
            val buffer = this.buffer ?: return emptyList()
            this.buffer = null
            // Multiple data lines join with a newline, per the SSE spec.
            val payload = buffer.toString().removeSuffix("\n")
            return if (payload.isEmpty()) emptyList() else listOf(Event(payload))
        }
    }
}
