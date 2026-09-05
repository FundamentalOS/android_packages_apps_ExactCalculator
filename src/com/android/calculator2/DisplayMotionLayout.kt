/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

import androidx.constraintlayout.motion.widget.MotionLayout

import kotlin.math.abs

class DisplayMotionLayout @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MotionLayout(context, attributeSet, defStyleAttr) {
    private var pointerId = -1
    private var isScrolling = false
    private var previousPoint: PointF? = null
    private var previousEvent: MotionEvent? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var outOfBounds = false

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val inDisplay = findViewById<View>(R.id.display).hitRect()
                    .contains(motionEvent.x.toInt(), motionEvent.y.toInt())
                isScrolling = false
                outOfBounds = !inDisplay
                if (inDisplay) {
                    previousPoint = PointF(motionEvent.x, motionEvent.y)
                    pointerId = motionEvent.getPointerId(0)
                    saveLastMotion(motionEvent)
                } else {
                    pointerId = -1
                    clearLastMotion()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                isScrolling = false
                outOfBounds = false
                pointerId = -1
                clearLastMotion()
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = motionEvent.findPointerIndex(pointerId)
                val start = previousPoint
                if (pointerId != -1 && pointerIndex != -1 && !outOfBounds && start != null &&
                    abs(motionEvent.getY(pointerIndex) - start.y) > touchSlop
                ) {
                    isScrolling = true
                    onTouchEvent(previousEvent)
                }
            }
        }
        return super.onInterceptTouchEvent(motionEvent) || isScrolling && !outOfBounds
    }

    private fun saveLastMotion(motionEvent: MotionEvent) {
        previousEvent?.recycle()
        previousEvent = MotionEvent.obtain(motionEvent)
    }

    private fun clearLastMotion() {
        previousEvent?.recycle()
        previousEvent = null
    }
}
