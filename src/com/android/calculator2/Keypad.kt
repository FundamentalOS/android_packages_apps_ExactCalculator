/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.util.Xml
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.widget.Button

import androidx.annotation.IdRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

import com.google.android.material.color.MaterialColors

import org.xmlpull.v1.XmlPullParser

import java.util.Locale

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A pad of calculator keys drawn as a single view.
 *
 * The keys are described by an xml resource (see `attrs.xml`): a grid of equally sized cells,
 * each key naming its cell, its label or icon, its accessibility description and the theme
 * overlay that gives it its colours. Several keys may share a cell (the inverse functions sit
 * on top of the plain ones) and are shown one at a time.
 *
 * Drawing every key here rather than as a button of its own is what keeps the display sheet's
 * animations smooth: they resize the pads on every frame, and measuring, laying out and
 * re-recording thirty-odd buttons each time cost more than a frame. Here a frame costs one
 * `onDraw` of round rects and labels.
 */
class Keypad @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** One key: its cell, its looks and its state. */
    private inner class Key(
        @IdRes val id: Int,
        val row: Int,
        val column: Int,
        label: CharSequence?,
        private val allCaps: Boolean,
        val icon: Drawable?,
        var contentDescription: CharSequence?,
        val longClickable: Boolean,
        val background: Drawable,
        val labelColor: Int,
        val selectedLabelColor: Int,
        var visible: Boolean
    ) {
        var label: CharSequence? = label
            set(value) {
                field = value
                displayLabel = value?.toString()?.let { if (allCaps) it.uppercase(Locale.getDefault()) else it } ?: ""
            }
        var displayLabel = ""
            private set
        val bounds = Rect()
        var selected = false
        var pressed = false

        init {
            this.label = label
        }
    }

    var onKeyClick: ((Int) -> Unit)? = null

    /** Returns whether the long press was consumed; if not, lifting the finger still clicks. */
    var onKeyLongClick: ((Int) -> Boolean)? = null

    private val keys: List<Key>
    private val columnCount: Int
    private val rowCount: Int
    private val keyMargin: Int

    // Label sizes are rounded to whole sp: rasterizing a label's glyphs at a new size is the
    // most expensive part of drawing it, and the sheet animations resize the pad every frame.
    private val textSizeStep = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.google_sans_flex_medium)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val fontMetrics = Paint.FontMetrics()
    // From the centre of a key to the baseline of its label, so that the label's line box is
    // centred, as a TextView centres its text.
    private var labelBaselineOffset = 0f
    private var iconSize = 0

    private var pressedKey: Key? = null
    private var longPressHandled = false
    private val longPress = Runnable {
        val key = pressedKey ?: return@Runnable
        if (onKeyLongClick?.invoke(key.id) == true) {
            longPressHandled = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val touchHelper: ExploreByTouchHelper

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.Keypad, defStyleAttr, 0)
        val keysRes: Int
        try {
            keysRes = a.getResourceId(R.styleable.Keypad_keys, 0)
            keyMargin = a.getDimensionPixelSize(R.styleable.Keypad_keyMargin, 0)
        } finally {
            a.recycle()
        }
        val grid = parseKeys(keysRes)
        columnCount = grid.columnCount
        rowCount = grid.rowCount
        keys = grid.keys
        keys.forEach { it.background.callback = this }

        isFocusable = true
        touchHelper = TouchHelper()
        ViewCompat.setAccessibilityDelegate(this, touchHelper)
    }

    private class Grid(val columnCount: Int, val rowCount: Int, val keys: List<Key>)

    private fun parseKeys(res: Int): Grid {
        var columnCount = 1
        var rowCount = 1
        val keys = mutableListOf<Key>()
        val parser = resources.getXml(res)
        val attrSet = Xml.asAttributeSet(parser)
        try {
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "keypad" -> {
                            val a = context.obtainStyledAttributes(attrSet, R.styleable.KeypadGrid)
                            columnCount = max(1, a.getInt(R.styleable.KeypadGrid_columnCount, 1))
                            rowCount = max(1, a.getInt(R.styleable.KeypadGrid_rowCount, 1))
                            a.recycle()
                        }
                        "key" -> keys += parseKey(attrSet)
                    }
                }
            }
        } finally {
            parser.close()
        }
        return Grid(columnCount, rowCount, keys)
    }

    private fun parseKey(attrSet: AttributeSet): Key {
        val a = context.obtainStyledAttributes(attrSet, R.styleable.Key)
        try {
            // The key's theme overlay names its container and content colours; the background
            // drawable and the state layer in it resolve them from the same context.
            val themeRes = a.getResourceId(R.styleable.Key_android_theme, 0)
            val themed = if (themeRes != 0) ContextThemeWrapper(context, themeRes) else context
            val labelColor = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnContainer, 0)
            val background = checkNotNull(AppCompatResources.getDrawable(themed, R.drawable.pad_button_background)).mutate()
            val icon = a.getResourceId(R.styleable.Key_android_src, 0).takeIf { it != 0 }?.let {
                checkNotNull(AppCompatResources.getDrawable(themed, it)).mutate().apply { setTint(labelColor) }
            }
            return Key(
                id = a.getResourceId(R.styleable.Key_android_id, NO_ID),
                row = a.getInt(R.styleable.Key_row, 0),
                column = a.getInt(R.styleable.Key_column, 0),
                label = a.getText(R.styleable.Key_android_text),
                allCaps = a.getBoolean(R.styleable.Key_android_textAllCaps, false),
                icon = icon,
                contentDescription = a.getText(R.styleable.Key_android_contentDescription),
                longClickable = a.getBoolean(R.styleable.Key_android_longClickable, false),
                background = background,
                labelColor = labelColor,
                selectedLabelColor = MaterialColors.getColor(themed, androidx.appcompat.R.attr.colorPrimary, labelColor),
                visible = a.getInt(R.styleable.Key_android_visibility, VISIBLE) == VISIBLE
            )
        } finally {
            a.recycle()
        }
    }

    private fun keyIndex(@IdRes id: Int): Int =
        keys.indexOfFirst { it.id == id }.also { require(it >= 0) { "No key with id $id" } }

    /** Show or hide a key; the keys sharing its cell are unaffected. */
    fun setKeyVisible(@IdRes id: Int, visible: Boolean) {
        val index = keyIndex(id)
        val key = keys[index]
        if (key.visible == visible) return
        key.visible = visible
        if (!visible && pressedKey === key) release(key)
        invalidate()
        touchHelper.invalidateRoot()
    }

    /** A selected key draws its label in the primary colour; used for the INV toggle. */
    fun setKeySelected(@IdRes id: Int, selected: Boolean) {
        val index = keyIndex(id)
        if (keys[index].selected == selected) return
        keys[index].selected = selected
        invalidate()
        touchHelper.invalidateVirtualView(index)
    }

    fun setKeyLabel(@IdRes id: Int, label: CharSequence) {
        val index = keyIndex(id)
        keys[index].label = label
        invalidate()
        touchHelper.invalidateVirtualView(index)
    }

    fun setKeyContentDescription(@IdRes id: Int, contentDescription: CharSequence?) {
        val index = keyIndex(id)
        keys[index].contentDescription = contentDescription
        touchHelper.invalidateVirtualView(index)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys(w, h)
    }

    /** Cells are computed arithmetically; rounding every edge keeps neighbours flush. */
    private fun layoutKeys(width: Int, height: Int) {
        val cellWidth = (width - paddingLeft - paddingRight).toFloat() / columnCount
        val cellHeight = (height - paddingTop - paddingBottom).toFloat() / rowCount
        for (key in keys) {
            val left = (paddingLeft + key.column * cellWidth).roundToInt() + keyMargin
            val top = (paddingTop + key.row * cellHeight).roundToInt() + keyMargin
            val right = (paddingLeft + (key.column + 1) * cellWidth).roundToInt() - keyMargin
            val bottom = (paddingTop + (key.row + 1) * cellHeight).roundToInt() - keyMargin
            key.bounds.set(left, top, max(left, right), max(top, bottom))
            key.background.bounds = key.bounds
        }

        // Labels and icons scale with the key height.
        val keyHeight = max(0f, cellHeight - 2 * keyMargin)
        val textSize = (keyHeight * TEXT_SIZE_RATIO / textSizeStep).roundToInt() * textSizeStep
        if (textSize != labelPaint.textSize) {
            labelPaint.textSize = textSize
            labelPaint.getFontMetrics(fontMetrics)
            labelBaselineOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2
        }
        iconSize = (keyHeight * ICON_SIZE_RATIO).roundToInt()
        touchHelper.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        val focusedIndex = touchHelper.keyboardFocusedVirtualViewId
        keys.forEachIndexed { index, key ->
            if (!key.visible || key.bounds.isEmpty) return@forEachIndexed
            key.background.setState(
                when {
                    key.pressed -> STATE_PRESSED
                    index == focusedIndex -> STATE_FOCUSED
                    else -> STATE_DEFAULT
                }
            )
            key.background.draw(canvas)

            val centerX = key.bounds.exactCenterX()
            val centerY = key.bounds.exactCenterY()
            val icon = key.icon
            if (icon != null) {
                val left = (centerX - iconSize / 2f).roundToInt()
                val top = (centerY - iconSize / 2f).roundToInt()
                icon.setBounds(left, top, left + iconSize, top + iconSize)
                icon.draw(canvas)
            } else if (labelPaint.textSize > 0f) {
                labelPaint.color = if (key.selected) key.selectedLabelColor else key.labelColor
                canvas.drawText(key.displayLabel, centerX, centerY + labelBaselineOffset, labelPaint)
            }
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        super.verifyDrawable(who) || keys.any { it.background === who || it.icon === who }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        keys.forEach { it.background.jumpToCurrentState() }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val px = x.toInt()
        val py = y.toInt()
        return keys.firstOrNull { it.visible && it.bounds.contains(px, py) }
    }

    @SuppressLint("ClickableViewAccessibility") // Keys are clicked through their own handling and exposed to accessibility as virtual views.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val key = keyAt(event.x, event.y) ?: return false
                press(key, event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding off a key gives up on it, as sliding off a button does.
                val key = pressedKey ?: return false
                if (!key.bounds.contains(event.x.toInt(), event.y.toInt())) release(key)
            }
            MotionEvent.ACTION_UP -> {
                val key = pressedKey ?: return false
                val click = !longPressHandled
                release(key)
                if (click) click(key)
            }
            MotionEvent.ACTION_CANCEL -> pressedKey?.let { release(it) }
        }
        return true
    }

    private fun press(key: Key, x: Float, y: Float) {
        pressedKey = key
        longPressHandled = false
        key.pressed = true
        key.background.setHotspot(x, y)
        // The pad does not scroll, so the press shows and is felt immediately.
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (key.longClickable) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
        invalidate()
    }

    private fun release(key: Key) {
        removeCallbacks(longPress)
        key.pressed = false
        if (pressedKey === key) pressedKey = null
        invalidate()
    }

    private fun click(key: Key) {
        playSoundEffect(SoundEffectConstants.CLICK)
        touchHelper.sendEventForVirtualView(keys.indexOf(key), AccessibilityEvent.TYPE_VIEW_CLICKED)
        onKeyClick?.invoke(key.id)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        touchHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The helper moves keyboard focus between the keys and clicks the focused one.
        if (touchHelper.dispatchKeyEvent(event)) {
            invalidate()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        touchHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    /** Exposes the keys to accessibility services and keyboard focus as buttons. */
    private inner class TouchHelper : ExploreByTouchHelper(this@Keypad) {

        override fun getVirtualViewAt(x: Float, y: Float): Int =
            keyAt(x, y)?.let { keys.indexOf(it) } ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            keys.forEachIndexed { index, key -> if (key.visible && !key.bounds.isEmpty) virtualViewIds += index }
        }

        @Suppress("DEPRECATION") // The helper computes the bounds on screen from the ones in the parent.
        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val key = keys[virtualViewId]
            node.className = Button::class.java.name
            node.text = key.displayLabel
            node.contentDescription = key.contentDescription
            node.isSelected = key.selected
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
            if (key.longClickable) {
                node.isLongClickable = true
                node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK)
            }
            node.setBoundsInParent(key.bounds)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val key = keys[virtualViewId]
            return when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    click(key)
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> onKeyLongClick?.invoke(key.id) == true
                else -> false
            }
        }
    }

    companion object {
        /** Label size as a fraction of the key height. */
        private const val TEXT_SIZE_RATIO = 0.4f

        /** Icon size as a fraction of the key height: about as tall as a digit would be. */
        private const val ICON_SIZE_RATIO = 0.45f

        private val STATE_DEFAULT = intArrayOf(android.R.attr.state_enabled)
        private val STATE_PRESSED = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
        private val STATE_FOCUSED = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
    }
}
