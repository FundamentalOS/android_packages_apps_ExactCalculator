/*
 * SPDX-FileCopyrightText: 2015 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

import androidx.appcompat.widget.AppCompatTextView

/**
 * Extended [TextView] that supports ascent/baseline alignment; see [CapHeightAlignment].
 */
open class AlignedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val alignment = CapHeightAlignment()

    init {
        // Disable any included font padding by default.
        includeFontPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        alignment.measure(paint, paddingTop, paddingBottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun getCompoundPaddingTop() = super.getCompoundPaddingTop() - alignment.topPaddingOffset

    override fun getCompoundPaddingBottom() = super.getCompoundPaddingBottom() - alignment.bottomPaddingOffset
}
