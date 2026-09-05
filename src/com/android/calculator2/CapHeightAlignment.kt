/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

import kotlin.math.ceil
import kotlin.math.min

/**
 * The padding adjustments that align a text view's text on the height of a capital letter at
 * the top and on the baseline at the bottom, rather than on the font's full ascent and descent.
 * Shared by [AlignedTextView] and [CalculatorFormula], which subtract the offsets from their
 * compound paddings.
 */
class CapHeightAlignment {
    private val tempRect = Rect()

    // The font the offsets were measured for; the display is measured on every frame of the
    // sheet animations, and the measurements only depend on the font.
    private var measuredTextSize = 0f
    private var measuredTypeface: Typeface? = null
    private var capTopOffset = 0
    private var descentOffset = 0

    /** How much less than its padding the view reserves above its text. */
    var topPaddingOffset = 0
        private set

    /** How much less than its padding the view reserves below its text. */
    var bottomPaddingOffset = 0
        private set

    /** Call from the view's onMeasure(), once the text size is final. */
    fun measure(paint: Paint, paddingTop: Int, paddingBottom: Int) {
        if (paint.textSize != measuredTextSize || paint.typeface !== measuredTypeface) {
            measuredTextSize = paint.textSize
            measuredTypeface = paint.typeface
            paint.getTextBounds(LATIN_CAPITAL_LETTER, 0, 1, tempRect)
            capTopOffset = ceil(tempRect.top - paint.ascent()).toInt()
            descentOffset = ceil(paint.descent()).toInt()
        }
        topPaddingOffset = min(paddingTop, capTopOffset)
        bottomPaddingOffset = min(paddingBottom, descentOffset)
    }

    companion object {
        private const val LATIN_CAPITAL_LETTER = "H"
    }
}
