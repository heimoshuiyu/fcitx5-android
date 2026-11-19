/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.os.Bundle
import android.util.Base64
import android.graphics.BitmapFactory
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.TranscriptionHistoryManager
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TranscriptionDetailFragment : Fragment() {

    private val args by lazyRoute<SettingsRoute.TranscriptionDetail>()

    private val recordId: Long by lazy { args.recordId }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_transcription_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadRecord(view)
    }

    private fun loadRecord(view: View) {
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) {
                TranscriptionHistoryManager.getById(recordId)
            }

            if (record == null) {
                view.findViewById<TextView>(R.id.text_error)?.apply {
                    setText(R.string.transcription_record_not_found)
                    visibility = View.VISIBLE
                }
                return@launch
            }

            val scrollView = view.findViewById<ScrollView>(R.id.scroll_view)
            scrollView.visibility = View.VISIBLE

            view.findViewById<TextView>(R.id.text_time)?.text =
                dateFormat.format(Date(record.timestamp))

            view.findViewById<TextView>(R.id.text_backend)?.text =
                if (record.editMode) {
                    getString(R.string.transcription_backend_edit, record.backendType)
                } else {
                    record.backendType
                }

            view.findViewById<TextView>(R.id.text_duration)?.text =
                getString(R.string.transcription_duration_ms, record.durationMs)

            view.findViewById<TextView>(R.id.text_audio_info)?.text =
                getString(
                    R.string.transcription_audio_info,
                    record.audioDurationSec.toInt(),
                    record.audioSizeBytes / 1024,
                )

            view.findViewById<TextView>(R.id.text_status)?.text =
                if (record.success) {
                    getString(R.string.transcription_success)
                } else {
                    record.errorMessage.ifBlank { getString(R.string.transcription_failed) }
                }

            view.findViewById<TextView>(R.id.text_result)?.text =
                record.resultText.ifBlank { getString(R.string.transcription_value_empty) }

            view.findViewById<TextView>(R.id.text_prompt)?.text =
                record.prompt.ifBlank { getString(R.string.transcription_value_none) }

            if (record.selectedText.isNotEmpty()) {
                view.findViewById<View>(R.id.section_selected_text)?.visibility = View.VISIBLE
                view.findViewById<TextView>(R.id.text_selected_text)?.text = record.selectedText
            }

            // Screenshot
            if (record.screenshotBase64.isNotEmpty()) {
                try {
                    val section = view.findViewById<View>(R.id.section_screenshot)
                    val imageView = view.findViewById<ImageView>(R.id.image_screenshot)
                    // Strip data URI prefix: "data:image/jpeg;base64,"
                    val base64Data = record.screenshotBase64.substringAfter("base64,")
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        section?.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    // Ignore screenshot decode errors
                }
            }

            view.findViewById<TextView>(R.id.text_raw_response)?.text =
                try {
                    val prettyJson = Json { prettyPrint = true }
                    val element = Json.parseToJsonElement(record.rawResponseBody)
                    prettyJson.encodeToString(JsonObject.serializer(), element as JsonObject)
                } catch (_: Exception) {
                    record.rawResponseBody.ifBlank {
                        getString(R.string.transcription_value_not_available)
                    }
                }
        }
    }
}
