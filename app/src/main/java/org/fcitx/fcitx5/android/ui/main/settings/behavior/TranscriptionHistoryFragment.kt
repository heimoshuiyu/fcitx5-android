/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
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
    private val diffCallback = object : DiffUtil.ItemCallback<TranscriptionRecord>() {
        override fun areItemsTheSame(
            oldItem: TranscriptionRecord,
            newItem: TranscriptionRecord,
        ) = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: TranscriptionRecord,
            newItem: TranscriptionRecord,
        ) = oldItem == newItem
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

        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: android.view.MenuInflater) {
                    menuInflater.inflate(R.menu.transcription_history_menu, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (menuItem.itemId != R.id.action_clear_history) return false
                    showClearHistoryDialog()
                    return true
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.STARTED,
        )

        loadRecords()
    }

    private fun loadRecords() {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                TranscriptionHistoryManager.getPage(100, 0)
            }
            adapter.submitList(records)
            emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showClearHistoryDialog() {
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
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    inner class TranscriptionAdapter(
        private val onClick: (TranscriptionRecord) -> Unit
    ) : ListAdapter<TranscriptionRecord, TranscriptionAdapter.ViewHolder>(diffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transcription_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val timeText: TextView = view.findViewById(R.id.time_text)
            private val backendText: TextView = view.findViewById(R.id.backend_text)
            private val resultText: TextView = view.findViewById(R.id.result_text)
            private val durationText: TextView = view.findViewById(R.id.duration_text)
            private val statusIndicator: View = view.findViewById(R.id.status_indicator)

            fun bind(record: TranscriptionRecord) {
                timeText.text = dateFormat.format(Date(record.timestamp))
                backendText.text = if (record.editMode) {
                    getString(R.string.transcription_backend_edit, record.backendType)
                } else {
                    record.backendType
                }
                durationText.text = getString(R.string.transcription_duration_ms, record.durationMs)

                if (record.success) {
                    resultText.text = record.resultText.ifBlank {
                        getString(R.string.transcription_value_empty)
                    }
                    statusIndicator.setBackgroundResource(R.drawable.bg_status_success)
                } else {
                    resultText.text = record.errorMessage.ifBlank {
                        getString(R.string.transcription_failed)
                    }
                    statusIndicator.setBackgroundResource(R.drawable.bg_status_failed)
                }

                itemView.setOnClickListener { onClick(record) }
            }
        }
    }
}
