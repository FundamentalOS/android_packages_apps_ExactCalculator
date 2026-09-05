/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView

import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import java.math.BigInteger

// ---------------------------------------------------------------------------------------------
// Views
// ---------------------------------------------------------------------------------------------

/** Visibility as a boolean; `false` maps to [View.GONE]. */
var View.isVisible: Boolean
    get() = visibility == View.VISIBLE
    set(value) {
        visibility = if (value) View.VISIBLE else View.GONE
    }

/** Invisibility as a boolean; `false` maps to [View.VISIBLE]. */
var View.isInvisible: Boolean
    get() = visibility == View.INVISIBLE
    set(value) {
        visibility = if (value) View.INVISIBLE else View.VISIBLE
    }

/** The direct children of this view group. */
val ViewGroup.children: Sequence<View>
    get() = (0 until childCount).asSequence().map(::getChildAt)

/** Sets the text size in pixels. */
fun TextView.setTextSizePx(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, size)

/** This view's hit rectangle in its parent's coordinates. */
fun View.hitRect(): Rect = Rect().also(::getHitRect)

/** Run [action] now if this view has been laid out, otherwise once it first has been. */
fun View.doOnceLaidOut(action: () -> Unit) {
    if (isLaidOut) {
        action()
        return
    }
    viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            viewTreeObserver.removeOnGlobalLayoutListener(this)
            action()
        }
    })
}

/** Type safe [Context.getSystemService]. */
inline fun <reified T : Any> Context.systemService(): T =
    checkNotNull(getSystemService(T::class.java)) { "No ${T::class.java.simpleName} service" }

/** Sets the padding, keeping the sides that are not specified. */
fun View.updatePadding(
    left: Int = paddingLeft,
    top: Int = paddingTop,
    right: Int = paddingRight,
    bottom: Int = paddingBottom
) = setPadding(left, top, right, bottom)

// ---------------------------------------------------------------------------------------------
// Edge-to-edge
// ---------------------------------------------------------------------------------------------

/**
 * Deliver the system bar and display cutout insets to [listener] whenever they change.
 * The insets are not consumed, so siblings and children still receive them.
 */
fun View.onSystemBarInsets(listener: (Insets) -> Unit) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        listener(
            windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        )
        windowInsets
    }
}

/**
 * Keep a toolbar's content clear of the status bar and display cutout: the toolbar grows by the
 * inset and pads its content below it, so that its content area stays exactly as tall as the
 * fixed height it was inflated with. A toolbar merely padded and left to wrap its content ends
 * up with a content area shorter than its minimum height, which is what the navigation button
 * is centred on, while the title is centred on the content area: they fall out of line.
 */
fun View.applyStatusBarInsetToToolbar() {
    val initialHeight = layoutParams.height
    val initialTop = paddingTop
    onSystemBarInsets { insets ->
        updatePadding(top = initialTop + insets.top)
        layoutParams = layoutParams.apply { height = initialHeight + insets.top }
    }
}

/**
 * Keep this view's content clear of the system bars and display cutout on the given sides by
 * adding the corresponding insets to the padding the view was inflated with. The view's own
 * background still extends under the bars, which is what makes the layout edge-to-edge.
 */
fun View.applySystemBarInsets(
    left: Boolean = false,
    top: Boolean = false,
    right: Boolean = false,
    bottom: Boolean = false
) {
    val initial = Insets.of(paddingLeft, paddingTop, paddingRight, paddingBottom)
    onSystemBarInsets { insets ->
        setPadding(
            initial.left + if (left) insets.left else 0,
            initial.top + if (top) insets.top else 0,
            initial.right + if (right) insets.right else 0,
            initial.bottom + if (bottom) insets.bottom else 0
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Threads
// ---------------------------------------------------------------------------------------------

/**
 * Wait on this monitor until [condition] holds. Must be called while holding the monitor.
 * Interrupts are deferred until the condition holds and then re-asserted on the current thread.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
fun Object.waitUntil(condition: () -> Boolean) {
    var interrupted = false
    while (!condition()) {
        try {
            wait()
        } catch (e: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) {
        Thread.currentThread().interrupt()
    }
}

// ---------------------------------------------------------------------------------------------
// Strings
// ---------------------------------------------------------------------------------------------

/**
 * Return a copy of the substring [begin, end) with commas added every three digits.
 * The substring is assumed to contain only a whole number, with no decimal point; leading
 * minus signs and blanks are passed through.
 * Inserting a digit separator every 3 digits appears to be at least somewhat acceptable,
 * though not necessarily preferred, everywhere. The grouping separator is NOT localized.
 */
fun String.addCommas(begin: Int, end: Int): String {
    // Resist the temptation to use Java's NumberFormat, which converts to long or double
    // and hence doesn't handle very large numbers.
    val s = this
    return buildString {
        var current = begin
        while (current < end && (s[current] == '-' || s[current] == ' ')) ++current
        append(s, begin, current)
        while (current < end) {
            append(s[current++])
            if ((end - current) % 3 == 0 && end != current) append(',')
        }
    }
}

/**
 * Ignoring all occurrences of [c] in both strings, check whether [prefix] is a prefix of this
 * sequence. If so, return the remainder of this sequence, otherwise null.
 */
fun CharSequence.extensionIgnoring(prefix: CharSequence, c: Char): CharSequence? {
    var wIndex = 0
    var pIndex = 0
    while (true) {
        while (pIndex < prefix.length && prefix[pIndex] == c) ++pIndex
        while (wIndex < length && this[wIndex] == c) ++wIndex
        if (pIndex == prefix.length) break
        if (wIndex == length || this[wIndex] != prefix[pIndex]) return null
        ++pIndex
        ++wIndex
    }
    while (wIndex < length && this[wIndex] == c) ++wIndex
    return subSequence(wIndex, length)
}

/**
 * Format this non-negative integer, assumed to be scaled by 10^[fractionDigits], as a decimal
 * string with exactly [fractionDigits] digits to the right of the decimal point.
 */
fun BigInteger.toScaledDecimalString(fractionDigits: Int): String {
    val digits = toString().padStart(fractionDigits + 1, '0')
    val split = digits.length - fractionDigits
    return digits.substring(0, split) + "." + digits.substring(split)
}

// ---------------------------------------------------------------------------------------------
// Arithmetic
// ---------------------------------------------------------------------------------------------

val BigInteger.isOne: Boolean get() = this == BigInteger.ONE

val BigInteger.isOdd: Boolean get() = testBit(0)

// Arithmetic on possibly unknown rationals: null stands for "too big to track", and propagates.

operator fun BoundedRational?.plus(other: BoundedRational?): BoundedRational? =
    BoundedRational.add(this, other)

operator fun BoundedRational?.times(other: BoundedRational?): BoundedRational? =
    BoundedRational.multiply(this, other)

operator fun BoundedRational?.div(other: BoundedRational?): BoundedRational? =
    BoundedRational.divide(this, other)

operator fun BoundedRational?.unaryMinus(): BoundedRational? = BoundedRational.negate(this)
