/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING

class HistoryFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private lateinit var adapter: HistoryAdapter

    private var evaluator: Evaluator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = HistoryAdapter(ArrayList())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_history, container, false /* attachToRoot */).apply {
        recyclerView = findViewById<RecyclerView>(R.id.history_recycler_view).apply {
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == SCROLL_STATE_DRAGGING) stopActionModeOrContextMenu()
                    super.onScrollStateChanged(recyclerView, newState)
                }
            })
            // The size of the RecyclerView is not affected by the adapter's contents.
            setHasFixedSize(true)
            // Rows are kept, not dropped, when the panel closes, so that reopening it binds
            // existing views instead of inflating new ones mid-pull.
            recycledViewPool.setMaxRecycledViews(HistoryAdapter.HISTORY_VIEW_TYPE, RECYCLED_ROWS)
            adapter = this@HistoryFragment.adapter
        }

        // Edge-to-edge: the history panel starts at the very top of the screen. (The side insets
        // are handled by the calculator's root view.) The toolbar's height is otherwise fixed: a
        // wrap_content toolbar would get squeezed, and its title would jump, whenever the panel
        // is pulled shorter than the toolbar.
        findViewById<Toolbar>(R.id.history_toolbar).applyStatusBarInsetToToolbar()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter.evaluator = Evaluator.getInstance(requireActivity()).also { evaluator = it }
    }

    /**
     * Reload the list from the evaluator. The panel stays attached while it is closed, so this
     * is called as the sheet starts to reveal it: results may have been added, or the history
     * cleared, meanwhile. Items are filled in lazily as rows are bound; an empty history shows a
     * single placeholder item.
     */
    fun refresh() {
        val evaluator = evaluator ?: return
        adapter.dataSet = MutableList<HistoryItem?>(evaluator.getMaxIndex().toInt()) { null }
            .ifEmpty { mutableListOf(HistoryItem()) }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        recyclerView?.scrollToPosition(0)
    }

    /** The panel has been closed: stop computing the results of the rows that were showing. */
    fun onClosed() {
        evaluator?.cancelNonMain()
    }

    override fun onDestroy() {
        super.onDestroy()
        evaluator?.cancelNonMain()
    }

    fun stopActionModeOrContextMenu(): Boolean {
        val recyclerView = recyclerView ?: return false
        return recyclerView.children.any {
            (recyclerView.getChildViewHolder(it) as? HistoryAdapter.HistoryViewHolder)
                ?.result?.stopActionModeOrContextMenu() == true
        }
    }

    companion object {
        const val TAG = "HistoryFragment"
        const val CLEAR_DIALOG_TAG = "clear"

        /** Row views kept in the recycled pool while the panel is closed. */
        private const val RECYCLED_ROWS = 20
    }
}
