/*
 * SPDX-FileCopyrightText: 2015 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.color.MaterialColors

/**
 * The third-party components the app ships with, as a grouped list: one rounded block of rows,
 * each opening the component's notice in [LicenseReader].
 */
class Licenses : AppCompatActivity() {

    /** A component and the raw text resource holding its notice. */
    private class Notice(val name: String, @RawRes val text: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_license)

        // Edge-to-edge: the toolbar clears the status bar, the list scrolls under the other bars.
        requireViewById<View>(R.id.license_root).applySystemBarInsets(left = true, right = true)
        requireViewById<Toolbar>(R.id.toolbar).apply {
            applyStatusBarInsetToToolbar()
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        requireViewById<RecyclerView>(R.id.notices).apply {
            applySystemBarInsets(bottom = true)
            adapter = NoticeAdapter(NOTICES) { notice ->
                startActivity(
                    Intent(this@Licenses, LicenseReader::class.java)
                        .putExtra(LicenseReader.EXTRA_NAME, notice.name)
                        .putExtra(LicenseReader.EXTRA_TEXT, notice.text)
                )
            }
            addItemDecoration(GapDecoration(resources.getDimensionPixelSize(R.dimen.license_item_gap)))
        }
    }

    /** Leaves a gap between rows, but none around the block. */
    private class GapDecoration(private val gap: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            if (parent.getChildAdapterPosition(view) > 0) outRect.top = gap
        }
    }

    private class NoticeAdapter(
        private val notices: List<Notice>,
        private val onClick: (Notice) -> Unit
    ) : RecyclerView.Adapter<NoticeAdapter.ViewHolder>() {

        class ViewHolder(val name: TextView) : RecyclerView.ViewHolder(name)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.license_item, parent, false) as TextView
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val notice = notices[position]
            holder.name.text = notice.name
            holder.name.background = rowBackground(holder.name, position)
            holder.name.setOnClickListener { onClick(notice) }
        }

        override fun getItemCount() = notices.size

        /**
         * The rows share the block's large corners at its top and bottom and have small ones
         * where they meet, on the surface bright colour, with a state layer for presses.
         */
        private fun rowBackground(view: View, position: Int): Drawable {
            val resources = view.resources
            val outer = resources.getDimension(R.dimen.license_list_corner_radius)
            val inner = resources.getDimension(R.dimen.license_item_corner_radius)
            val top = if (position == 0) outer else inner
            val bottom = if (position == itemCount - 1) outer else inner
            val shape = GradientDrawable().apply {
                setColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceBright))
                cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            }
            val highlight = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight)
            return RippleDrawable(ColorStateList.valueOf(highlight), shape, shape)
        }
    }

    companion object {
        private val NOTICES = listOf(
            Notice("CRCalc", R.raw.notice_crcalc),
            Notice("Google Sans Flex", R.raw.notice_google_sans_flex)
        )
    }
}
