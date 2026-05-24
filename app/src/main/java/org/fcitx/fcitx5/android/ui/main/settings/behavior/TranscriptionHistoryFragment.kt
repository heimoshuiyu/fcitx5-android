/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.TranscriptionHistoryManager
import org.fcitx.fcitx5.android.data.voice.db.TranscriptionRecord
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TranscriptionHistoryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: TranscriptionAdapter
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_transcription_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_view)
        emptyView = view.findViewById(R.id.empty_view)
        recyclerView.applyNavBarInsetsBottomPadding()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = TranscriptionAdapter { record ->
            findNavController().navigateWithAnim(SettingsRoute.TranscriptionDetail(record.id))
        }
        recyclerView.adapter = adapter

        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                TranscriptionHistoryManager.getAll()
            }
            adapter.submitList(records)
            emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.transcription_history_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_history -> {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.transcription_history_clear_title)
                    .setMessage(R.string.transcription_history_clear_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                TranscriptionHistoryManager.deleteAll()
                            }
                            loadRecords()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    inner class TranscriptionAdapter(
        private val onClick: (TranscriptionRecord) -> Unit
    ) : RecyclerView.Adapter<TranscriptionAdapter.ViewHolder>() {

        private var records: List<TranscriptionRecord> = emptyList()

        fun submitList(newRecords: List<TranscriptionRecord>) {
            records = newRecords
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transcription_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(records[position])
        }

        override fun getItemCount() = records.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val timeText: TextView = view.findViewById(R.id.time_text)
            private val backendText: TextView = view.findViewById(R.id.backend_text)
            private val resultText: TextView = view.findViewById(R.id.result_text)
            private val durationText: TextView = view.findViewById(R.id.duration_text)
            private val statusIndicator: View = view.findViewById(R.id.status_indicator)

            fun bind(record: TranscriptionRecord) {
                timeText.text = dateFormat.format(Date(record.timestamp))
                backendText.text = record.backendType
                durationText.text = "${record.durationMs}ms"

                if (record.success) {
                    resultText.text = record.resultText.ifBlank { "(empty)" }
                    statusIndicator.setBackgroundResource(R.drawable.bg_status_success)
                } else {
                    resultText.text = "✗ ${record.errorMessage}".takeIf { record.errorMessage.isNotEmpty() }
                        ?: "Failed"
                    statusIndicator.setBackgroundResource(R.drawable.bg_status_failed)
                }

                if (record.editMode) {
                    backendText.text = "${record.backendType} (edit)"
                }

                itemView.setOnClickListener { onClick(record) }
            }
        }
    }
}
