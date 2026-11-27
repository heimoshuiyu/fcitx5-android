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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.voice.AutoHotwordRefreshGate
import org.fcitx.fcitx5.android.data.voice.AutoHotwordStore
import org.fcitx.fcitx5.android.data.voice.HotwordWordFilter
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.positiveButton
import splitties.resources.styledColor

class AutoHotwordsFragment : Fragment() {

    private val store = AutoHotwordStore.shared
    private val preference = AppPrefs.getInstance().voiceInput.autoHotwordsList

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText
    private lateinit var adapter: HotwordAdapter

    private var allEntries: List<AutoHotwordStore.Entry> = emptyList()
    private val selectedIds = mutableSetOf<AutoHotwordStore.EntryId>()
    private var selectionMode = false
    private var backCallback: OnBackPressedCallback? = null
    private var activeDialog: AlertDialog? = null
    private val refreshGate = AutoHotwordRefreshGate()

    private val preferenceChangeListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        val owner = viewLifecycleOwnerLiveData.value ?: return@OnChangeListener
        owner.lifecycleScope.launch {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) requestRefresh()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_auto_hotwords, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recycler_view)
        emptyView = view.findViewById(R.id.empty_view)
        searchInput = view.findViewById(R.id.search_input)

        recyclerView.applyNavBarInsetsBottomPadding()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = HotwordAdapter(
            onClick = { entry ->
                if (selectionMode) toggleSelection(entry.id) else showEditor(entry)
            },
            onLongClick = { entry -> toggleSelection(entry.id) },
        )
        recyclerView.adapter = adapter

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitMultiSelect()
            }
        }.also {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
        }

        searchInput.doAfterTextChanged {
            // A selection must never survive into a different filtered view.
            if (selectionMode) exitMultiSelect(render = false)
            renderList()
        }

        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: android.view.MenuInflater) {
                    val tint = requireActivity().styledColor(android.R.attr.colorControlNormal)
                    if (selectionMode) {
                        menu.item(
                            R.string.auto_hotwords_delete_selected,
                            R.drawable.ic_baseline_delete_24,
                            tint,
                            true,
                        ) { confirmDeleteSelected() }
                    } else {
                        menu.item(R.string.add, R.drawable.ic_baseline_plus_24, tint, true) {
                            showEditor(null)
                        }
                        if (allEntries.isNotEmpty()) {
                            menu.item(R.string.auto_hotwords_clear) { confirmClear() }
                        }
                    }
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
            },
            viewLifecycleOwner,
            Lifecycle.State.STARTED,
        )
        requestRefresh()
    }

    override fun onStart() {
        super.onStart()
        preference.registerOnChangeListener(preferenceChangeListener)
    }

    override fun onResume() {
        super.onResume()
        requestRefresh()
    }

    override fun onStop() {
        preference.unregisterOnChangeListener(preferenceChangeListener)
        refreshGate.invalidate()
        exitMultiSelect()
        dismissActiveDialog()
        super.onStop()
    }

    override fun onDestroyView() {
        refreshGate.invalidate()
        dismissActiveDialog()
        backCallback?.isEnabled = false
        recyclerView.adapter = null
        super.onDestroyView()
    }

    private fun requestRefresh() {
        refreshFromStore(refreshGate.next())
    }

    /** Only the newest requested refresh may commit a newly read snapshot. */
    private fun refreshFromStore(generation: Long): Boolean {
        if (!refreshGate.isCurrent(generation)) return false
        val owner = viewLifecycleOwnerLiveData.value ?: return false
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        refresh(store.snapshot())
        return true
    }

    private fun refresh(entries: List<AutoHotwordStore.Entry>) {
        if (selectionMode && entries != allEntries) exitMultiSelect(render = false)
        allEntries = entries
        renderList()
    }

    private fun renderList() {
        val visible = AutoHotwordStore.visibleEntries(allEntries, searchInput.text.toString())
        adapter.submitList(visible.map {
            HotwordListItem(it, selectionMode, it.id in selectedIds)
        })
        emptyView.setText(
            if (allEntries.isEmpty()) R.string.auto_hotwords_empty
            else R.string.auto_hotwords_no_results
        )
        emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        requireActivity().invalidateMenu()
    }

    private fun toggleSelection(id: AutoHotwordStore.EntryId) {
        if (!selectionMode) {
            selectionMode = true
            selectedIds.clear()
            backCallback?.isEnabled = true
        }
        if (!selectedIds.add(id)) selectedIds.remove(id)
        renderList()
    }

    private fun exitMultiSelect(render: Boolean = true) {
        if (!selectionMode) return
        selectionMode = false
        selectedIds.clear()
        backCallback?.isEnabled = false
        if (render) renderList()
    }

    private fun confirmDeleteSelected() {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        val owner = viewLifecycleOwner
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.auto_hotwords_delete_title)
            .setMessage(
                resources.getQuantityString(R.plurals.auto_hotwords_delete_message, ids.size, ids.size)
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (activeDialog === dialog &&
                    owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    val generation = refreshGate.next()
                    owner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { store.delete(ids) }
                        if (refreshGate.isCurrent(generation)) {
                            exitMultiSelect(render = false)
                            refreshFromStore(generation)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        trackDialog(dialog)
    }

    private fun confirmClear() {
        val owner = viewLifecycleOwner
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.auto_hotwords_clear)
            .setMessage(getString(R.string.auto_hotwords_clear_message, allEntries.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (activeDialog === dialog &&
                    owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    val generation = refreshGate.next()
                    owner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { store.clear() }
                        refreshFromStore(generation)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        trackDialog(dialog)
    }

    private fun showEditor(target: AutoHotwordStore.Entry?) {
        val owner = viewLifecycleOwner
        val content = layoutInflater.inflate(R.layout.dialog_auto_hotword_edit, null)
        val input = content.findViewById<EditText>(R.id.hotword_input)
        input.setText(target?.text.orEmpty())
        input.setSelection(input.text.length)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (target == null) R.string.auto_hotwords_add_title else R.string.auto_hotwords_edit_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.setCanceledOnTouchOutside(false)
        trackDialog(dialog)
        dialog.positiveButton.setOnClickListener {
            if (activeDialog !== dialog ||
                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return@setOnClickListener
            val rawWord = input.text.toString()
            val generation = refreshGate.next()
            dialog.positiveButton.isEnabled = false
            owner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (target == null) store.add(rawWord) else store.edit(target.id, rawWord)
                }
                refreshFromStore(generation)
                if (activeDialog !== dialog || !dialog.isShowing) return@launch
                if (result.error == null) {
                    dialog.dismiss()
                } else {
                    input.error = validationMessage(result.error)
                    dialog.positiveButton.isEnabled = true
                    input.requestFocus()
                }
            }
        }
        input.requestFocus()
    }

    private fun trackDialog(dialog: AlertDialog) {
        dismissActiveDialog()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
    }

    private fun dismissActiveDialog() {
        val dialog = activeDialog ?: return
        activeDialog = null
        dialog.setOnDismissListener(null)
        dialog.dismiss()
    }

    private fun validationMessage(reason: String): String = when (reason) {
        "empty" -> getString(R.string.auto_hotword_error_empty)
        "too_short" -> getString(
            R.string.auto_hotword_error_too_short,
            HotwordWordFilter.MIN_WORD_LENGTH,
        )
        "too_long" -> getString(
            R.string.auto_hotword_error_too_long,
            HotwordWordFilter.MAX_WORD_LENGTH,
        )
        "duplicate_auto", "duplicate_batch" -> getString(R.string.auto_hotword_error_duplicate)
        "duplicate_manual" -> getString(R.string.auto_hotword_error_duplicate_manual)
        "list_full" -> getString(
            R.string.auto_hotword_error_full,
            HotwordWordFilter.MAX_AUTO_LIST_SIZE,
        )
        AutoHotwordStore.ERROR_NOT_FOUND -> getString(R.string.auto_hotword_error_missing)
        else -> getString(R.string.invalid_value)
    }

    data class HotwordListItem(
        val entry: AutoHotwordStore.Entry,
        val selectionMode: Boolean,
        val selected: Boolean,
    )

    class HotwordAdapter(
        private val onClick: (AutoHotwordStore.Entry) -> Unit,
        private val onLongClick: (AutoHotwordStore.Entry) -> Unit,
    ) : ListAdapter<HotwordListItem, HotwordAdapter.ViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_auto_hotword, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val checkbox: CheckBox = view.findViewById(R.id.selection_checkbox)
            private val text: TextView = view.findViewById(R.id.hotword_text)

            fun bind(item: HotwordListItem) {
                val entry = item.entry
                text.text = entry.text.ifEmpty {
                    text.context.getString(R.string.hotword_agent_run_value_empty)
                }
                checkbox.visibility = if (item.selectionMode) View.VISIBLE else View.GONE
                checkbox.isChecked = item.selected
                itemView.setOnClickListener { onClick(entry) }
                itemView.setOnLongClickListener {
                    onLongClick(entry)
                    true
                }
                checkbox.setOnClickListener { onClick(entry) }
            }
        }

        private object DiffCallback : DiffUtil.ItemCallback<HotwordListItem>() {
            override fun areItemsTheSame(oldItem: HotwordListItem, newItem: HotwordListItem) =
                oldItem.entry.id == newItem.entry.id

            override fun areContentsTheSame(oldItem: HotwordListItem, newItem: HotwordListItem) =
                oldItem == newItem
        }
    }
}
