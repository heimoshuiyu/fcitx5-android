/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class VoiceBackendType(override val stringRes: Int) : ManagedPreferenceEnum {
    OpenCode(R.string.voice_backend_opencode),
    Whisper(R.string.voice_backend_whisper),
    LALM(R.string.voice_backend_lalm),
}
