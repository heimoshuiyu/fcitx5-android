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
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voice.HotwordAgentManager
import org.fcitx.fcitx5.android.data.voice.db.HotwordAgentRun
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HotwordAgentRunsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: RunAdapter
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
    private val diffCallback = object : DiffUtil.ItemCallback<HotwordAgentRun>() {
        override fun areItemsTheSame(
            oldItem: HotwordAgentRun,
            newItem: HotwordAgentRun,
        ) = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: HotwordAgentRun,
            newItem: HotwordAgentRun,
        ) = oldItem == newItem
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_hotword_agent_runs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_view)
        emptyView = view.findViewById(R.id.empty_view)
        recyclerView.applyNavBarInsetsBottomPadding()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = RunAdapter { run ->
            findNavController().navigateWithAnim(SettingsRoute.HotwordAgentRunDetail(run.id))
        }
        recyclerView.adapter = adapter

        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: android.view.MenuInflater) {
                    menuInflater.inflate(R.menu.hotword_agent_runs_menu, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (menuItem.itemId != R.id.action_clear_runs) return false
                    showClearDialog()
                    return true
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.STARTED,
        )

        loadRuns()
    }

    private fun loadRuns() {
        lifecycleScope.launch {
            val runs = HotwordAgentManager.getPage(100, 0)
            adapter.submitList(runs)
            emptyView.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (runs.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showClearDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.hotword_agent_runs_clear_title)
            .setMessage(R.string.hotword_agent_runs_clear_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    HotwordAgentManager.deleteAll()
                    loadRuns()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadRuns()
    }

    private fun statusLabel(status: String): String = when (status) {
        HotwordAgentRun.STATUS_PENDING -> getString(R.string.hotword_agent_run_status_pending)
        HotwordAgentRun.STATUS_RUNNING -> getString(R.string.hotword_agent_run_status_running)
        HotwordAgentRun.STATUS_SUCCEEDED -> getString(R.string.hotword_agent_run_status_succeeded)
        else -> getString(R.string.hotword_agent_run_status_failed)
    }

    inner class RunAdapter(
        private val onClick: (HotwordAgentRun) -> Unit
    ) : ListAdapter<HotwordAgentRun, RunAdapter.ViewHolder>(diffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hotword_agent_run, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val statusIndicator: View = view.findViewById(R.id.status_indicator)
            private val timeText: TextView = view.findViewById(R.id.time_text)
            private val previewText: TextView = view.findViewById(R.id.preview_text)
            private val detailText: TextView = view.findViewById(R.id.detail_text)

            fun bind(run: HotwordAgentRun) {
                timeText.text = dateFormat.format(Date(run.createdAt))
                previewText.text =
                    getString(R.string.hotword_agent_run_preview, run.originalText, run.finalText)

                val detail = when {
                    run.status == HotwordAgentRun.STATUS_FAILED && run.error.isNotBlank() -> run.error
                    run.submittedWords.isNotBlank() ->
                        getString(R.string.hotword_agent_run_words_count, run.submittedWords.lines().size)
                    else -> statusLabel(run.status)
                }
                detailText.text = detail

                statusIndicator.setBackgroundResource(
                    when (run.status) {
                        HotwordAgentRun.STATUS_SUCCEEDED -> R.drawable.bg_status_success
                        HotwordAgentRun.STATUS_FAILED -> R.drawable.bg_status_failed
                        HotwordAgentRun.STATUS_RUNNING -> R.drawable.bg_status_running
                        else -> R.drawable.bg_status_pending
                    }
                )

                itemView.setOnClickListener { onClick(run) }
            }
        }
    }
}
