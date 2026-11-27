/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.R

internal sealed interface ConsoleChoiceRow {
    val text: String

    data class Header(
        override val text: String,
    ) : ConsoleChoiceRow

    data class Option(
        val id: String,
        override val text: String,
    ) : ConsoleChoiceRow
}

internal data class ConsoleChoiceEntry(
    val id: String,
    val text: String,
    val section: String? = null,
)

internal fun consoleChoiceRows(entries: List<ConsoleChoiceEntry>): List<ConsoleChoiceRow> = buildList {
    var section: String? = null
    entries.forEach { entry ->
        if (entry.section != section) {
            entry.section?.let { add(ConsoleChoiceRow.Header(it)) }
            section = entry.section
        }
        add(ConsoleChoiceRow.Option(entry.id, entry.text))
    }
}

internal fun consoleChoiceSnapshot(rows: List<ConsoleChoiceRow>): List<ConsoleChoiceRow> {
    val optionIds = rows.filterIsInstance<ConsoleChoiceRow.Option>().map { it.id }
    require(optionIds.size == optionIds.toSet().size) {
        "Console choice option ids must be unique"
    }
    return rows.toList()
}

internal fun consoleChoiceSelectedId(rows: List<ConsoleChoiceRow>, selectedId: String?): String? =
    selectedId?.takeIf { id -> rows.any { it is ConsoleChoiceRow.Option && it.id == id } }

/**
 * Show a grouped single-choice dialog. [selectedId] controls the initial check;
 * [onPicked] runs only after the user taps an option and confirms with OK.
 */
internal fun showConsoleChoiceDialog(
    context: Context,
    @StringRes title: Int,
    rows: List<ConsoleChoiceRow>,
    selectedId: String?,
    onPicked: (String) -> Unit,
): AlertDialog {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_console_choice, null)
    val recyclerView = view.findViewById<RecyclerView>(R.id.console_choice_list)
    val validSelectedId = consoleChoiceSelectedId(rows, selectedId)
    val adapter = ConsoleChoiceAdapter(rows, validSelectedId, recyclerView)
    recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
    recyclerView.adapter = adapter
    recyclerView.itemAnimator = null
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(view)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            adapter.pickedId?.let(onPicked)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.show()
    return dialog
}

private class ConsoleChoiceAdapter(
    rows: List<ConsoleChoiceRow>,
    selectedId: String?,
    private val recyclerView: RecyclerView,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // The dialog never changes this snapshot's structure, so positions are
    // stable, globally unique Long IDs. Business IDs only drive checked state.
    private val rows = consoleChoiceSnapshot(rows)

    var pickedId: String? = null
        private set

    private var selectedId = selectedId

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = rows.size

    override fun getItemId(position: Int) = position.toLong()

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is ConsoleChoiceRow.Header -> TYPE_HEADER
        is ConsoleChoiceRow.Option -> TYPE_OPTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                inflater.inflate(R.layout.item_console_choice_header, parent, false),
            )
            else -> OptionHolder(
                inflater.inflate(R.layout.item_console_choice_option, parent, false)
                        as ConsoleChoiceOptionLayout,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ConsoleChoiceRow.Header -> {
                holder as HeaderHolder
                holder.text.text = row.text
                val density = holder.itemView.resources.displayMetrics.density
                val top = ((if (position == 0) 8 else 20) * density).roundToInt()
                ViewCompat.setPaddingRelative(
                    holder.itemView,
                    ViewCompat.getPaddingStart(holder.itemView),
                    top,
                    ViewCompat.getPaddingEnd(holder.itemView),
                    holder.itemView.paddingBottom,
                )
            }
            is ConsoleChoiceRow.Option -> {
                holder as OptionHolder
                holder.root.contentDescription = row.text
                holder.root.setChecked(row.id == selectedId)
                holder.text.text = row.text
                holder.radio.isChecked = row.id == selectedId
                holder.root.setOnClickListener {
                    val currentPosition = holder.bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) pick(currentPosition, holder)
                }
            }
        }
    }

    private fun pick(position: Int, holder: OptionHolder) {
        val row = rows[position] as ConsoleChoiceRow.Option
        val previousPosition = rows.indexOfFirst {
            it is ConsoleChoiceRow.Option && it.id == selectedId
        }
        // View.performClick sends TYPE_VIEW_CLICKED after this listener returns.
        // Update visible checked state now so that event cannot carry stale false.
        holder.root.setChecked(true)
        holder.radio.isChecked = true
        if (previousPosition >= 0 && previousPosition != position) {
            (recyclerView.findViewHolderForAdapterPosition(previousPosition) as? OptionHolder)?.let {
                it.root.setChecked(false)
                it.radio.isChecked = false
            }
        }
        selectedId = row.id
        pickedId = row.id
        if (previousPosition >= 0 && previousPosition != position) {
            notifyItemChanged(previousPosition)
        }
        notifyItemChanged(position)
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.console_choice_header)

        init {
            view.isClickable = false
            view.isFocusable = false
            ViewCompat.setAccessibilityHeading(view, true)
        }
    }

    private class OptionHolder(
        val root: ConsoleChoiceOptionLayout,
    ) : RecyclerView.ViewHolder(root) {
        val text: TextView = root.findViewById(R.id.console_choice_text)
        val radio: RadioButton = root.findViewById(R.id.console_choice_radio)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_OPTION = 1
    }
}

/** A single accessibility node with radio semantics; child views are hidden from TalkBack. */
internal class ConsoleChoiceOptionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), Checkable {

    private var checked = false

    override fun isChecked() = checked

    override fun setChecked(checked: Boolean) {
        if (this.checked == checked) return
        this.checked = checked
        refreshDrawableState()
    }

    override fun toggle() {
        setChecked(!checked)
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checked) mergeDrawableStates(drawableState, CHECKED_STATE_SET)
        return drawableState
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = RadioButton::class.java.name
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = RadioButton::class.java.name
        event.isChecked = checked
    }

    companion object {
        private val CHECKED_STATE_SET = intArrayOf(android.R.attr.state_checked)
    }
}

/** Wraps short lists while preventing long catalogs from exceeding the dialog area. */
internal class ConsoleChoiceRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxHeight = resources.displayMetrics.heightPixels * 3 / 5
        val mode = MeasureSpec.getMode(heightSpec)
        val cappedHeightSpec = if (mode == MeasureSpec.EXACTLY) {
            heightSpec
        } else {
            val available = if (mode == MeasureSpec.UNSPECIFIED) {
                maxHeight
            } else {
                min(MeasureSpec.getSize(heightSpec), maxHeight)
            }
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthSpec, cappedHeightSpec)
    }
}
