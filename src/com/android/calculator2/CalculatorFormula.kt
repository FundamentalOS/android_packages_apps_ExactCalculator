/*
 * SPDX-FileCopyrightText: 2015 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView

import androidx.appcompat.widget.AppCompatEditText

import kotlin.math.min

/**
 * The formula. It is an EditText, so that it has a blinking cursor and the standard text
 * selection toolbar, but one whose text is only ever changed by the calculator: the keys, not
 * the keyboard, edit the expression, at the cursor (see Calculator.beginEdit()). There is no
 * soft keyboard and no input connection, and key events fall through to the activity, which
 * maps them to the keys. Pasting inserts into the expression; cutting is offered for the whole
 * formula only.
 */
class CalculatorFormula @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs), ClipboardManager.OnPrimaryClipChangedListener {

    private val alignment = CapHeightAlignment()

    // Temporary paint for use in layout methods.
    private val tempPaint = TextPaint()

    val maximumTextSize: Float
    val minimumTextSize: Float
    private val stepTextSize: Float

    private val clipboardManager = context.systemService<ClipboardManager>()

    private var widthConstraint = -1
    // The width constraint the text size was last fitted to. The display is measured again on
    // every frame of the sheet animations; at the same width the fit is a no-op.
    private var fittedWidthConstraint = -1
    private var actionMode: ActionMode? = null

    /**
     * Trims the system's selection and insertion toolbars down to copy, paste, select all and
     * cut, and adds memory recall. The system items are acted on by [onTextContextMenuItem].
     */
    private val actionModeCallback = object : ActionMode.Callback {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.tag = TAG_ACTION_MODE
            actionMode = mode
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            REMOVED_MENU_ITEMS.forEach(menu::removeItem)
            // The expression can only be shortened from its end, so cutting part of it is
            // impossible; cutting all of it clears the calculator.
            menu.findItem(android.R.id.cut)?.isVisible = isWholeFormulaSelected
            if (menu.findItem(R.id.memory_recall) == null) mode.menuInflater.inflate(R.menu.menu_formula, menu)
            menu.findItem(R.id.memory_recall)?.isVisible = isMemoryEnabled
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.memory_recall) return false
            onContextMenuClickListener?.onMemoryRecall()
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }
    }

    var onTextSizeChangeListener: OnTextSizeChangeListener? = null
    var onContextMenuClickListener: OnFormulaContextMenuClickListener? = null
    var onDisplayMemoryOperationsListener: Calculator.OnDisplayMemoryOperationsListener? = null

    init {
        // Disable any included font padding by default.
        includeFontPadding = false

        context.obtainStyledAttributes(attrs, R.styleable.CalculatorFormula, 0, 0).run {
            maximumTextSize = getDimension(R.styleable.CalculatorFormula_maxTextSize, textSize)
            minimumTextSize = getDimension(R.styleable.CalculatorFormula_minTextSize, textSize)
            stepTextSize = getDimension(
                R.styleable.CalculatorFormula_stepTextSize, (maximumTextSize - minimumTextSize) / 3
            )
            recycle()
        }

        // A single line of plain text, edited by the keys alone.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        showSoftInputOnFocus = false
        // The activity restores the expression itself.
        isSaveEnabled = false
        customSelectionActionModeCallback = actionModeCallback
        customInsertionActionModeCallback = actionModeCallback
    }

    /** No input method: the keys edit the expression. */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null

    // Key events are the activity's, which maps them to the keys of the pads.
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = false

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isLaidOut) {
            // Prevent shrinking/resizing with our variable textSize.
            setTextSizeInternal(maximumTextSize, false /* notifyListener */)
            minimumHeight = lineHeight + compoundPaddingBottom + compoundPaddingTop
            fittedWidthConstraint = -1
        }

        // Ensure we are at least as big as our parent.
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (minimumWidth != width) minimumWidth = width

        // Re-calculate our textSize based on new width.
        widthConstraint = width - paddingLeft - paddingRight
        if (widthConstraint != fittedWidthConstraint) {
            val textSize = getVariableTextSize(text)
            if (this.textSize != textSize) setTextSizeInternal(textSize, false /* notifyListener */)
            fittedWidthConstraint = widthConstraint
        }

        alignment.measure(paint, paddingTop, paddingBottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun getCompoundPaddingTop() = super.getCompoundPaddingTop() - alignment.topPaddingOffset

    override fun getCompoundPaddingBottom() = super.getCompoundPaddingBottom() - alignment.bottomPaddingOffset

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clipboardManager.addPrimaryClipChangedListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        setTextSizePx(getVariableTextSize(text.toString()))
        fittedWidthConstraint = widthConstraint
    }

    private fun setTextSizeInternal(sizePx: Float, notifyListener: Boolean) {
        val oldTextSize = textSize
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        if (notifyListener && textSize != oldTextSize) {
            onTextSizeChangeListener?.onTextSizeChanged(this, oldTextSize)
        }
    }

    override fun setTextSize(unit: Int, size: Float) {
        val oldTextSize = textSize
        super.setTextSize(unit, size)
        if (textSize != oldTextSize) onTextSizeChangeListener?.onTextSizeChanged(this, oldTextSize)
    }

    fun getVariableTextSize(text: CharSequence?): Float {
        // Not measured, bail early.
        if (widthConstraint < 0 || maximumTextSize <= minimumTextSize) return textSize

        // Capture current paint state.
        tempPaint.set(paint)

        // Step through increasing text sizes until the text would no longer fit.
        var lastFitTextSize = minimumTextSize
        while (lastFitTextSize < maximumTextSize) {
            tempPaint.textSize = min(lastFitTextSize + stepTextSize, maximumTextSize)
            if (Layout.getDesiredWidth(text, tempPaint) > widthConstraint) break
            lastFitTextSize = tempPaint.textSize
        }
        return lastFitTextSize
    }

    /**
     * Functionally equivalent to setText(), but explicitly announce changes, and put the cursor
     * at [selection]. If the new text is an extension of the old one, announce the addition.
     * Otherwise, e.g. after deletion, announce the entire new text.
     */
    fun changeTextTo(newText: CharSequence, selection: Int) {
        val separator = KeyMaps.translateResult(",")[0]
        val added = newText.extensionIgnoring(text ?: "", separator)
        when {
            added == null -> announceForAccessibility(newText)
            // The algorithm for pronouncing a single character doesn't seem
            // to respect our hints.  Don't give it the choice.
            added.length == 1 -> announceForAccessibility(
                KeyMaps.toDescriptiveString(context, KeyMaps.keyForChar(added[0]))
                    ?: added.toString()
            )
            added.isNotEmpty() -> announceForAccessibility(added)
        }
        setText(newText)
        setSelection(selection.coerceIn(0, length()))
    }

    fun stopActionModeOrContextMenu(): Boolean = actionMode?.let { it.finish(); true } ?: false

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.paste, android.R.id.pasteAsPlainText -> {
            // Pasted text is appended to the expression rather than inserted here.
            clipboardManager.primaryClip?.let { onContextMenuClickListener?.onPaste(it) }
            stopActionModeOrContextMenu()
            true
        }
        android.R.id.cut -> {
            // Only offered with the whole formula selected, but reachable by shortcut too.
            val whole = isWholeFormulaSelected
            super.onTextContextMenuItem(android.R.id.copy)
            if (whole) onContextMenuClickListener?.onCut()
            true
        }
        else -> super.onTextContextMenuItem(id)
    }

    override fun onPrimaryClipChanged() {
        actionMode?.invalidate()
    }

    fun onMemoryStateChanged() {
        actionMode?.invalidate()
    }

    private val isWholeFormulaSelected: Boolean
        get() = length() > 0 && selectionStart == 0 && selectionEnd == length()

    private val isMemoryEnabled: Boolean
        get() = onDisplayMemoryOperationsListener?.shouldDisplayMemory() == true

    fun interface OnTextSizeChangeListener {
        fun onTextSizeChanged(textView: TextView, oldSize: Float)
    }

    interface OnFormulaContextMenuClickListener {
        fun onPaste(clip: ClipData): Boolean
        fun onMemoryRecall()

        /** The whole formula was cut: it has been copied, and the expression is to be cleared. */
        fun onCut()
    }

    companion object {
        const val TAG_ACTION_MODE = "ACTION_MODE"

        /** The system's toolbar items that make no sense for a formula. */
        private val REMOVED_MENU_ITEMS = intArrayOf(
            android.R.id.shareText,
            android.R.id.textAssist,
            android.R.id.autofill,
            android.R.id.replaceText,
            android.R.id.pasteAsPlainText
        )
    }
}
