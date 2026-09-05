/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec.UNSPECIFIED
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView

import kotlin.math.max

class CalculatorScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) =
        measureChildCompat(child, parentWidthMeasureSpec, parentHeightMeasureSpec, 0, 0)

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int
    ) {
        val lp = child.layoutParams as MarginLayoutParams
        measureChildCompat(
            child, parentWidthMeasureSpec, parentHeightMeasureSpec,
            lp.leftMargin + lp.rightMargin, lp.topMargin + lp.bottomMargin
        )
    }

    private fun measureChildCompat(
        child: View,
        parentWidthMeasureSpec: Int,
        parentHeightMeasureSpec: Int,
        horizontalMargins: Int,
        verticalMargins: Int
    ) {
        // Allow child to be as wide as they want.
        val widthSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(parentWidthMeasureSpec), UNSPECIFIED)
        val lp = child.layoutParams
        child.measure(
            getChildMeasureSpecCompat(widthSpec, horizontalMargins, lp.width),
            getChildMeasureSpecCompat(
                parentHeightMeasureSpec, paddingTop + paddingBottom + verticalMargins, lp.height
            )
        )
    }

    companion object {
        private fun getChildMeasureSpecCompat(spec: Int, padding: Int, childDimension: Int): Int =
            if (MeasureSpec.getMode(spec) == UNSPECIFIED &&
                (childDimension == MATCH_PARENT || childDimension == WRAP_CONTENT)
            ) {
                MeasureSpec.makeMeasureSpec(max(0, MeasureSpec.getSize(spec) - padding), UNSPECIFIED)
            } else {
                ViewGroup.getChildMeasureSpec(spec, padding, childDimension)
            }
    }
}
