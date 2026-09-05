/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.text.Spannable
import android.text.format.DateUtils

/**
 * One row of the history list. An item without a [formula] is the "empty history" placeholder.
 */
class HistoryItem(
    val evaluatorIndex: Long = 0L,
    /** Date in millis */
    val timeInMillis: Long = 0L,
    val formula: Spannable? = null
) {
    /** This is true only for the "empty history" view. */
    val isEmptyView: Boolean get() = formula == null

    /** "n days ago"; for n > 7, the date. */
    val dateString: CharSequence
        get() = DateUtils.getRelativeTimeSpanString(
            timeInMillis, System.currentTimeMillis(),
            DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        )
}
