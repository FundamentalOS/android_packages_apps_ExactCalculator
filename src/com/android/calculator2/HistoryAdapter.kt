/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

import java.util.Calendar

/**
 * Adapter for RecyclerView of HistoryItems.
 */
class HistoryAdapter(var dataSet: MutableList<HistoryItem?>) :
    RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    lateinit var evaluator: Evaluator

    private val calendar: Calendar = Calendar.getInstance()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == HISTORY_VIEW_TYPE) {
            HistoryViewHolder(inflater.inflate(R.layout.history_item, parent, false))
        } else {
            EmptyViewHolder(inflater.inflate(R.layout.empty_history_view, parent, false))
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        if (item.isEmptyView || holder !is HistoryViewHolder) return

        holder.formula.text = item.formula
        // Note: HistoryItems that are not the current expression will always have interesting ops.
        holder.result.setEvaluator(evaluator, item.evaluatorIndex)
        if (item.evaluatorIndex == Evaluator.HISTORY_MAIN_INDEX) return
        // If the previous item occurred on the same date, the current item does not need
        // a date header.
        if (shouldShowHeader(position, item)) {
            holder.date.text = item.dateString
            // Special case -- very first item should not have a divider above it.
            holder.divider.isVisible = position != itemCount - 1
        } else {
            holder.date.isVisible = false
            holder.divider.isInvisible = true
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (holder !is HistoryViewHolder) return
        evaluator.cancel(holder.itemId, true)

        holder.date.isVisible = true
        holder.divider.isVisible = true
        holder.date.text = null
        holder.formula.text = null
        holder.result.text = null

        super.onViewRecycled(holder)
    }

    override fun getItemId(position: Int) = getItem(position).evaluatorIndex

    override fun getItemViewType(position: Int) =
        if (getItem(position).isEmptyView) EMPTY_VIEW_TYPE else HISTORY_VIEW_TYPE

    override fun getItemCount() = dataSet.size

    private fun getEvaluatorIndex(position: Int) = evaluator.getMaxIndex() - position

    private fun shouldShowHeader(position: Int, item: HistoryItem): Boolean {
        // First/oldest element should always show the header.
        if (position == itemCount - 1) return true
        // We need to use Calendars to determine this because of Daylight Savings.
        return dayOf(item.timeInMillis) != dayOf(getItem(position + 1).timeInMillis)
    }

    /** Year and day of year of the given time, in the local time zone. */
    private fun dayOf(timeInMillis: Long): Pair<Int, Int> {
        calendar.timeInMillis = timeInMillis
        return calendar.get(Calendar.YEAR) to calendar.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Gets the HistoryItem from dataSet, lazy-filling the dataSet if necessary.
     */
    private fun getItem(position: Int): HistoryItem = dataSet[position] ?: run {
        val evaluatorIndex = getEvaluatorIndex(position)
        HistoryItem(
            evaluatorIndex,
            evaluator.getTimeStamp(evaluatorIndex),
            evaluator.getExprAsSpannable(evaluatorIndex)
        ).also { dataSet[position] = it }
    }

    sealed class ViewHolder(v: View) : RecyclerView.ViewHolder(v)

    class EmptyViewHolder(v: View) : ViewHolder(v)

    class HistoryViewHolder(v: View) : ViewHolder(v) {
        val date: TextView = v.findViewById(R.id.history_date)
        val formula: AlignedTextView = v.findViewById(R.id.history_formula)
        val result: CalculatorResult = v.findViewById(R.id.history_result)
        val divider: View = v.findViewById(R.id.history_divider)
    }

    companion object {
        private const val EMPTY_VIEW_TYPE = 0
        const val HISTORY_VIEW_TYPE = 1
    }
}
