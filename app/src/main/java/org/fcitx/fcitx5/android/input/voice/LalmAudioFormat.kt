/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class LalmAudioFormat(override val stringRes: Int) : ManagedPreferenceEnum {
    InputAudio(R.string.voice_input_lalm_format_input_audio),
    AudioUrl(R.string.voice_input_lalm_format_audio_url),
}
