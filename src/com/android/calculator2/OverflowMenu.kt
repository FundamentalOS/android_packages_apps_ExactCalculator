/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.appcompat.widget.PopupMenu

/**
 * The calculator's overflow menu: a card of icon-and-label rows that grows out of the top end
 * corner of the button that opened it, and shrinks back into it when dismissed. The rows are
 * built once, and the window is animated by the window manager rather than on the main thread.
 */
class OverflowMenu(
    private val anchor: View,
    @MenuRes menuRes: Int,
    private val onItemSelected: (MenuItem) -> Boolean
) {
    private val context = anchor.context
    private val content: View = LayoutInflater.from(context).inflate(R.layout.overflow_menu, null)
    private val shadow = context.resources.getDimensionPixelSize(R.dimen.overflow_menu_shadow)
    private val window = PopupWindow(
        content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
    )

    init {
        // Let the menu inflater resolve the titles and icons.
        val menu = PopupMenu(context, anchor).also { it.menuInflater.inflate(menuRes, it.menu) }.menu
        val rows = content.findViewById<ViewGroup>(R.id.overflow_menu_rows)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            val row = LayoutInflater.from(context).inflate(R.layout.overflow_menu_item, rows, false)
            row.findViewById<ImageView>(R.id.overflow_menu_icon).setImageDrawable(item.icon)
            row.findViewById<TextView>(R.id.overflow_menu_title).text = item.title
            row.setOnClickListener {
                window.dismiss()
                onItemSelected(item)
            }
            rows.addView(row)
        }
        window.animationStyle = R.style.Animation_Calculator_OverflowMenu
        // A background is what makes taps outside the window dismiss it.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.isOutsideTouchable = true
    }

    /**
     * Open the menu with the card's top end corner at the anchor's bottom end corner; the
     * offsets cancel out the room left around the card for its shadow.
     */
    fun show() = window.showAsDropDown(anchor, shadow, -shadow, Gravity.END)
}
