/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.voice

import kotlinx.serialization.Serializable

/**
 * One entry of the serialized agent run log stored in
 * [org.fcitx.fcitx5.android.data.voice.db.HotwordAgentRun.eventsJson].
 *
 * The protocol core emits its own [org.fcitx.fcitx5.android.input.voice.agent.AgentEvent]
 * values; the manager converts them to this stable, JSON-serializable form
 * so the records UI never depends on the protocol classes.
 */
@Serializable
data class HotwordAgentEventLog(
    val step: Int = 0,
    val kind: String,
    val content: String = "",
    val toolName: String = "",
    val argumentsJson: String = "",
    val usageJson: String = "",
) {
    companion object {
        const val KIND_REASONING = "reasoning"
        const val KIND_TEXT = "text"
        const val KIND_TOOL_CALL = "tool_call"
        const val KIND_TOOL_RESULT = "tool_result"
        const val KIND_USAGE = "usage"
        const val KIND_FINISHED = "finished"
    }
}
