/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.widget.Toast

import androidx.core.content.ContextCompat

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A text widget that is "infinitely" scrollable to the right,
 * and obtains the text to display via a callback to Logic.
 */
class CalculatorResult(context: Context, attrs: AttributeSet?) : AlignedTextView(context, attrs),
    MenuItem.OnMenuItemClickListener, Evaluator.EvaluationListener, Evaluator.CharMetricsInfo {

    /** How, if at all, the result should be evaluated when layout completes. */
    enum class EvaluationRequest { SHOULD_NOT_EVALUATE, SHOULD_EVALUATE, SHOULD_REQUIRE }

    /** A formatted result together with the offset of the last digit actually displayed. */
    private data class FormattedResult(val text: String, val lastDisplayedOffset: Int)

    private val scroller = OverScroller(context)
    private var index = 0L // Index of expression we are displaying.
    private lateinit var evaluator: Evaluator

    // A scrollable result is currently displayed.
    private var scrollable = false
    // The result holds a valid number (not an error message).
    private var valid = false
    // A suffix of "Pos" denotes a pixel offset.  Zero represents a scroll position
    // in which the decimal point is just barely visible on the right of the display.
    // Position of right of display relative to decimal point, in pixels.
    // Large positive values mean the decimal point is scrolled off the
    // left of the display.  Zero means decimal point is barely displayed
    // on the right.
    private var currentPos = 0
    private var lastPos = 0 // Position already reflected in display. Pixels.
    private var minPos = 0 // Minimum position to avoid unnecessary blanks on the left. Pixels.
    // Maximum position before we start displaying the infinite sequence of trailing zeroes on
    // the right. Pixels.
    private var maxPos = 0
    private var wholeLen = 0 // Length of the whole part of current result.
    // In the following, we use a suffix of Offset to denote a character position in a numeric
    // string relative to the decimal point.  Positive is to the right and negative is to
    // the left. 1 = tenths position, -1 = units.  Integer.MAX_VALUE is sometimes used
    // for the offset of the last digit in an a nonterminating decimal expansion.
    // We use the suffix "Index" to denote a zero-based index into a string representing a
    // result.
    // Character offset from decimal point of rightmost digit that should be displayed, plus
    // the length of any exponent needed to display that digit. Limited to MAX_RIGHT_SCROLL.
    // Often the same as:
    private var maxCharOffset = 0
    private var lsdOffset = 0 // Position of least-significant digit in result
    // Offset of last digit actually displayed after adding exponent.
    private var lastDisplayedOffset = 0
    private var wholePartFits = false // Scientific notation not needed for initial display.
    // Fraction of digit width saved by avoiding scientific notation. Only accessed from UI
    // thread.
    private var noExponentCredit = 0f
    // The result fits entirely in the display, even with an exponent, but not with grouping
    // separators. Since the result is not scrollable, and we do not add the exponent to max.
    // scroll position, append an exponent instead of replacing trailing digits.
    private var appendExponent = false
    // Protects the next five fields.  These fields are only updated by the UI thread, and read
    // accesses by the UI thread sometimes do not acquire the lock.
    private val widthLock = Any()
    // Our total width in pixels minus space for ellipsis. 0 ==> uninitialized.
    private var widthConstraint = 0
    // Maximum character width. For now we pretend that all characters have this width.
    // TODO: We're not really using a fixed width font.  But it appears to be close enough for
    // the characters we use that the difference is not noticeable.
    private var charWidth = 1f
    // Fraction of digit width occupied by a digit separator.
    private var groupingSeparatorWidthRatio = 0f
    // Fraction of digit width saved by replacing digit with decimal point.
    private var decimalCredit = 0f
    // Fraction of digit width saved by both replacing ellipsis with digit and avoiding
    // scientific notation.
    private var noEllipsisCredit = 0f

    // Should we evaluate when layout completes, and how?
    private var evaluationRequest = EvaluationRequest.SHOULD_REQUIRE
    // Listener to use if/when evaluation is requested.
    private var evaluationListener: Evaluator.EvaluationListener? = this
    // Width at which onLayout() last requested evaluation; the request only depends on width.
    private var evaluatedWidth = -1
    // What the width metrics were last computed for; see onMeasure().
    private var metricsWidth = -1
    private var metricsTextSize = 0f
    private var metricsTypeface: Typeface? = null

    private val exponentColorSpan = ForegroundColorSpan(
        ContextCompat.getColor(context, R.color.display_result_exponent_text_color)
    )
    private val highlightSpan = BackgroundColorSpan(highlightColor)

    private var actionMode: ActionMode? = null

    // The user requested that the result currently being evaluated should be stored to "memory".
    private var storeToMemoryRequested = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                interruptScroll()
                // Ignore scrolls of error string, etc.
                if (!scrollable) return true
                scroller.fling(currentPos, 0, -velocityX.toInt(), 0 /* horizontal only */, minPos, maxPos, 0, 0)
                postInvalidateOnAnimation()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                interruptScroll()
                if (!scrollable) return true
                val distance = (currentPos + distanceX.toInt()).coerceIn(minPos, maxPos) - currentPos
                val duration = (e1?.let { e2.eventTime - it.eventTime } ?: 10L).toInt()
                    .takeIf { it in 1..100 } ?: 10
                scroller.startScroll(currentPos, 0, distance, 0, duration)
                postInvalidateOnAnimation()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (valid) performLongClick()
            }

            /** Stop any scroll in progress, remembering where it would have ended. */
            private fun interruptScroll() {
                if (!scroller.isFinished) currentPos = scroller.finalX
                scroller.forceFinished(true)
                stopActionModeOrContextMenu()
                this@CalculatorResult.cancelLongPress()
            }
        }
    )

    /** Use ActionMode for copy/memory support. */
    private val copyActionModeCallback = object : ActionMode.Callback2() {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu) =
            createContextMenu(mode.menuInflater, menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false // Nothing is done

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) =
            onMenuItemClick(item).also { if (it) mode.finish() }

        override fun onDestroyActionMode(mode: ActionMode) {
            unhighlightResult()
            actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            super.onGetContentRect(mode, view, outRect)

            outRect.left += view.paddingLeft
            outRect.top += view.paddingTop
            outRect.right -= view.paddingRight
            outRect.bottom -= view.paddingBottom
            val width = Layout.getDesiredWidth(text, paint).toInt()
            if (width < outRect.width()) outRect.left = outRect.right - width
        }
    }

    init {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener(object : View.OnTouchListener {

            // Used to determine whether a touch event should be intercepted.
            private var initialDownX = 0f
            private var initialDownY = 0f

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialDownX = event.x
                        initialDownY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = abs(event.x - initialDownX)
                        val deltaY = abs(event.y - initialDownY)
                        // Prevent the DragLayout from intercepting horizontal scrolls.
                        if (deltaX > slop && deltaX > deltaY) parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return gestureDetector.onTouchEvent(event)
            }
        })

        setOnLongClickListener {
            valid.also { if (it) actionMode = startActionMode(copyActionModeCallback, ActionMode.TYPE_FLOATING) }
        }

        isCursorVisible = false
        isLongClickable = false
        contentDescription = context.getString(R.string.desc_result)
    }

    fun setEvaluator(evaluator: Evaluator, index: Long) {
        this.evaluator = evaluator
        this.index = index
        evaluatedWidth = -1
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isLaidOut) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // Set a minimum height so scaled error messages won't affect our layout.
            minimumHeight = lineHeight + compoundPaddingBottom + compoundPaddingTop
        }

        // The metrics only depend on the width and the font, and the sheet animations measure
        // the result again on every frame, so the text measurements are skipped when neither
        // has changed.
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val paint = paint
        if (width != metricsWidth || paint.textSize != metricsTextSize || paint.typeface !== metricsTypeface) {
            metricsWidth = width
            metricsTextSize = paint.textSize
            metricsTypeface = paint.typeface
            measureWidthMetrics(width)
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Compute the character widths and credits that the result's formatting is based on. */
    private fun measureWidthMetrics(width: Int) {
        val paint = paint
        val newCharWidth = getMaxDigitWidth(paint)
        // Digits are presumed to have no more than newCharWidth.
        // There are two instances when we know that the result is otherwise narrower than
        // expected:
        // 1. For standard scientific notation (our type 1), we know that we have a narrow decimal
        // point and no (usually wide) ellipsis symbol. We allow one extra digit
        // (SCI_NOTATION_EXTRA) to compensate, and consider that in determining available width.
        // 2. If we are using digit grouping separators and a decimal point, we give ourselves
        // a fractional extra space for those separators, the value of which depends on whether
        // there is also an ellipsis.
        //
        // Maximum extra space we need in various cases:
        // Type 1 scientific notation, assuming ellipsis, minus sign and E are wider than a digit:
        //    Two minus signs + "E" + "." - 3 digits.
        // Type 2 scientific notation:
        //    Ellipsis + "E" + "-" - 3 digits.
        // In the absence of scientific notation, we may need a little less space.
        // We give ourselves a bit of extra credit towards comma insertion and give
        // ourselves more if we have either
        //    No ellipsis, or
        //    A decimal separator.

        // Calculate extra space we need to reserve, in addition to character count.
        fun extraWidthOf(s: String) = max(Layout.getDesiredWidth(s, paint) - newCharWidth, 0.0f)
        val decimalSeparatorWidth = Layout.getDesiredWidth(context.getString(R.string.dec_point), paint)
        val minusExtraWidth = extraWidthOf(context.getString(R.string.op_sub))
        val ellipsisExtraWidth = extraWidthOf(KeyMaps.ELLIPSIS)
        val expExtraWidth = extraWidthOf(KeyMaps.translateResult("e"))
        val type1Extra = 2 * minusExtraWidth + expExtraWidth + decimalSeparatorWidth
        val type2Extra = ellipsisExtraWidth + expExtraWidth + minusExtraWidth
        val extraWidth = max(type1Extra, type2Extra)
        val intExtraWidth = ceil(extraWidth).toInt() + 1 /* to cover rounding sins */
        val newWidthConstraint = width - (paddingLeft + paddingRight) - intExtraWidth

        // Calculate other width constants we need to handle grouping separators.
        val groupingSeparatorW = Layout.getDesiredWidth(KeyMaps.translateResult(","), paint)
        // Credits in the absence of any scientific notation:
        val noExponentCredit = extraWidth - max(ellipsisExtraWidth, minusExtraWidth)
        val noEllipsisCredit = extraWidth - minusExtraWidth // includes noExponentCredit.
        val decimalCredit = max(newCharWidth - decimalSeparatorWidth, 0.0f)

        this.noExponentCredit = noExponentCredit / newCharWidth
        synchronized(widthLock) {
            widthConstraint = newWidthConstraint
            charWidth = newCharWidth
            this.noEllipsisCredit = noEllipsisCredit / newCharWidth
            this.decimalCredit = decimalCredit / newCharWidth
            groupingSeparatorWidthRatio = groupingSeparatorW / newCharWidth
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (!::evaluator.isInitialized || evaluationRequest == EvaluationRequest.SHOULD_NOT_EVALUATE) return
        val listener = evaluationListener ?: return
        // Being laid out again at the same width, e.g. every frame while the display sheet
        // slides, changes nothing about the result; don't re-request it.
        val width = right - left
        if (width == evaluatedWidth) return
        evaluatedWidth = width
        if (!evaluator.getExpr(index).hasInterestingOps()) return
        if (evaluationRequest == EvaluationRequest.SHOULD_REQUIRE) {
            evaluator.requireResult(index, listener, this)
        } else {
            evaluator.evaluateAndNotify(index, listener, this)
        }
    }

    /**
     * Specify whether we should evaluate result on layout.
     * @param request one of SHOULD_REQUIRE, SHOULD_EVALUATE, SHOULD_NOT_EVALUATE
     */
    fun setShouldEvaluateResult(request: EvaluationRequest, listener: Evaluator.EvaluationListener?) {
        evaluationListener = listener
        evaluationRequest = request
        evaluatedWidth = -1
    }

    // From Evaluator.CharMetricsInfo.
    override fun separatorChars(s: String, len: Int): Float {
        // We assume the rest consists of digits, and for consistency with the rest
        // of the code, we assume all digits have width charWidth.
        val nDigits = len - ((0 until len).firstOrNull { s[it].isDigit() } ?: len)
        // We currently insert a digit separator every three digits.
        val nSeparators = (nDigits - 1) / 3
        // Always return an upper bound, even in the presence of rounding errors.
        return synchronized(widthLock) { nSeparators * groupingSeparatorWidthRatio }
    }

    // From Evaluator.CharMetricsInfo.
    override fun getNoEllipsisCredit() = synchronized(widthLock) { noEllipsisCredit }

    // From Evaluator.CharMetricsInfo.
    override fun getDecimalCredit() = synchronized(widthLock) { decimalCredit }

    // Return the length of the exponent representation for the given exponent, in
    // characters.
    private fun expLen(exp: Int): Int {
        if (exp == 0) return 0
        val absExpDigits = ceil(log10(abs(exp.toDouble())) + 0.0000000001 /* Round whole numbers to next integer */).toInt()
        return absExpDigits + (if (exp >= 0) 1 else 2)
    }

    /**
     * Initiate display of a new result.
     * Only called from UI thread.
     * The parameters specify various properties of the result.
     * @param index Index of expression that was just evaluated. Currently ignored, since we only
     *            expect notification for the expression result being displayed.
     * @param initPrecOffset Initial display precision computed by evaluator. (1 = tenths digit)
     * @param msdIndex Position of most significant digit.  Offset from left of string.
     *            Evaluator.INVALID_MSD if unknown.
     * @param lsdOffset Position of least significant digit (1 = tenths digit)
     *            or Integer.MAX_VALUE.
     * @param truncatedWholePart Result up to but not including decimal point.
     *            Currently we only use the length.
     */
    override fun onEvaluate(index: Long, initPrecOffset: Int, msdIndex: Int, lsdOffset: Int, truncatedWholePart: String) {
        initPositions(initPrecOffset, msdIndex, lsdOffset, truncatedWholePart)

        if (storeToMemoryRequested) {
            evaluator.copyToMemory(index)
            storeToMemoryRequested = false
        }
        redisplay()
    }

    /**
     * Store the result for this index if it is available.
     * If it is unavailable, set storeToMemoryRequested to indicate that we should store
     * when evaluation is complete.
     */
    fun onMemoryStore() {
        if (evaluator.hasResult(index)) {
            evaluator.copyToMemory(index)
        } else {
            storeToMemoryRequested = true
            evaluator.requireResult(index, this /* listener */, this /* CharMetricsInfo */)
        }
    }

    /** Add the result to the value currently in memory. */
    fun onMemoryAdd() = evaluator.addToMemory(index)

    /** Subtract the result from the value currently in memory. */
    fun onMemorySubtract() = evaluator.subtractFromMemory(index)

    /**
     * Set up scroll bounds (minPos, maxPos, etc.) and determine whether the result is
     * scrollable, based on the supplied information about the result.
     * This is unfortunately complicated because we need to predict whether trailing digits
     * will eventually be replaced by an exponent.
     * Just appending the exponent during formatting would be simpler, but would produce
     * jumpier results during transitions.
     * Only called from UI thread.
     */
    private fun initPositions(initPrecOffset: Int, msdIndexIn: Int, lsdOffset: Int, truncatedWholePart: String) {
        var msdIndex = msdIndexIn
        val maxChars = getMaxChars()
        wholeLen = truncatedWholePart.length
        // Allow a tiny amount of slop for associativity/rounding differences in length
        // calculation.  If getPreferredPrec() decided it should fit, we want to make it fit, too.
        // We reserved one extra pixel, so the extra length is OK.
        val nSeparatorChars = ceil(
            separatorChars(truncatedWholePart, truncatedWholePart.length) - getNoEllipsisCredit() - 0.0001f
        ).toInt()
        wholePartFits = wholeLen + nSeparatorChars <= maxChars
        lastPos = INVALID
        this.lsdOffset = lsdOffset
        appendExponent = false
        // Prevent scrolling past initial position, which is calculated to show leading digits.
        minPos = (initPrecOffset * charWidth).roundToInt()
        currentPos = minPos
        if (msdIndex == Evaluator.INVALID_MSD) {
            // Possible zero value
            if (lsdOffset == Int.MIN_VALUE) {
                // Definite zero value.
                maxPos = minPos
                maxCharOffset = (maxPos / charWidth).roundToInt()
                scrollable = false
            } else {
                // May be very small nonzero value.  Allow user to find out.
                maxPos = MAX_RIGHT_SCROLL
                maxCharOffset = MAX_RIGHT_SCROLL
                minPos -= charWidth.toInt() // Allow for future minus sign.
                scrollable = true
            }
            return
        }
        val negative = if (truncatedWholePart[0] == '-') 1 else 0
        if (msdIndex > wholeLen && msdIndex <= wholeLen + 3) {
            // Avoid tiny negative exponent; pretend msdIndex is just to the right of decimal
            // point.
            msdIndex = wholeLen - 1
        }
        // Set to position of leftmost significant digit relative to dec. point. Usually negative.
        var minCharOffset = msdIndex - wholeLen
        if (minCharOffset > -1 && minCharOffset < MAX_LEADING_ZEROES + 2) {
            // Small number of leading zeroes, avoid scientific notation.
            minCharOffset = -1
        }
        if (lsdOffset >= MAX_RIGHT_SCROLL) {
            maxPos = MAX_RIGHT_SCROLL
            maxCharOffset = MAX_RIGHT_SCROLL
            scrollable = true
            return
        }
        maxCharOffset = lsdOffset
        if (maxCharOffset < -1 && maxCharOffset > -(MAX_TRAILING_ZEROES + 2)) maxCharOffset = -1
        // lsdOffset is positive or negative, never 0.
        // Length of required standard scientific notation exponent.
        val currentExpLen = when {
            maxCharOffset < -1 -> expLen(-minCharOffset - 1)
            // Number is either entirely to the right of decimal point, or decimal point is
            // not visible when scrolled to the right.
            minCharOffset > -1 || maxCharOffset >= maxChars -> expLen(-minCharOffset)
            else -> 0
        }
        // Exponent length does not included added decimal point.  But whenever we add a
        // decimal point, we allow an extra character (SCI_NOTATION_EXTRA).
        val separatorLength = if (wholePartFits && minCharOffset < -3) nSeparatorChars else 0
        scrollable = maxCharOffset + currentExpLen + separatorLength - minCharOffset + negative >= maxChars
        // Now adjust maxCharOffset for any required exponent.
        if (currentExpLen > 0) {
            // We'll use exponent corresponding to leastDigPos when scrolled to right.
            val newMaxCharOffset = maxCharOffset + (if (scrollable) expLen(-lsdOffset) else currentExpLen)
            maxCharOffset = if (maxCharOffset <= -1 && newMaxCharOffset > -1) {
                -1 // Very unlikely; just drop exponent.
            } else {
                min(newMaxCharOffset, MAX_RIGHT_SCROLL)
            }
            maxPos = min((maxCharOffset * charWidth).roundToInt(), MAX_RIGHT_SCROLL)
        } else if (!wholePartFits && !scrollable) {
            // Corner case in which entire number fits, but not with grouping separators.  We
            // will use an exponent in un-scrolled position, which may hide digits.  Scrolling
            // by one character will remove the exponent and reveal the last digits.  Note
            // that in the forced scientific notation case, the exponent length is not
            // factored into maxCharOffset, since we do not want such an increase to impact
            // scrolling behavior.  In the unscrollable case, we thus have to append the
            // exponent at the end using the forcePrecision argument to formatResult, in order
            // to ensure that we get the entire result.
            scrollable = maxCharOffset + expLen(-minCharOffset - 1) - minCharOffset + negative >= maxChars
            if (scrollable) {
                // Single character scroll will remove exponent and show remaining piece.
                maxPos = ceil(minPos + charWidth).toInt()
            } else {
                maxPos = minPos
                appendExponent = true
            }
        } else {
            maxPos = min((maxCharOffset * charWidth).roundToInt(), MAX_RIGHT_SCROLL)
        }
        // Position the number consistently with our assumptions to make sure it actually fits.
        if (!scrollable) currentPos = maxPos
    }

    /**
     * Display error message indicated by resourceId.
     * UI thread only.
     */
    override fun onError(index: Long, errorId: Int) {
        storeToMemoryRequested = false
        valid = false
        isLongClickable = false
        scrollable = false
        val msg = context.getString(errorId)
        val measuredWidth = Layout.getDesiredWidth(msg, paint)
        text = if (measuredWidth <= widthConstraint) {
            msg
        } else {
            // Multiply by .99 to avoid rounding effects.
            SpannableString(msg).apply {
                setSpan(RelativeSizeSpan(0.99f * widthConstraint / measuredWidth), 0, msg.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /**
     * Format a result returned by Evaluator.getString() into a single line containing ellipses
     * (if appropriate) and an exponent (if appropriate).
     * We add two distinct kinds of exponents:
     * (1) If the final result contains the leading digit we use standard scientific notation.
     * (2) If not, we add an exponent corresponding to an interpretation of the final result as
     *     an integer.
     * We add an ellipsis on the left if the result was truncated.
     * We add ellipses and exponents in a way that leaves most digits in the position they
     * would have been in had we not done so. This minimizes jumps as a result of scrolling.
     * Result is NOT internationalized, uses "E" for exponent.
     * Called only from UI thread; We sometimes omit locking for fields.
     * @param input The result string to format.
     * @param precOffset The value that was passed to getString. Identifies the significance of
     *            the rightmost digit. A value of 1 means the rightmost digits corresponds to
     *            tenths.
     * @param maxDigs The maximum number of characters in the result
     * @param truncated The in parameter was already truncated, beyond possibly removing the
     *            minus sign.
     * @param negative The in parameter represents a negative result. (Minus sign may be removed
     *            without setting truncated.)
     * @param forcePrecision If true, we make sure that the last displayed digit corresponds to
     *            precOffset, and allow maxDigs to be exceeded in adding the exponent and commas.
     * @param forceSciNotation Force scientific notation. May be set because we don't have
     *            space for grouping separators, but whole number otherwise fits.
     * @param insertCommas Insert commas (literally, not internationalized) as digit separators.
     *            We only ever do this for the integral part of a number, and only when no
     *            exponent is displayed in the initial position. The combination of which means
     *            that we only do it when no exponent is displayed.
     *            We insert commas in a way that does consider the width of the actual localized
     *            digit separator. Commas count towards maxDigs as the appropriate fraction of a
     *            digit.
     * @return the formatted text, together with the offset of the last digit actually appearing
     *            in the display.
     */
    private fun formatResult(
        input: String,
        precOffset: Int,
        maxDigs: Int,
        truncated: Boolean,
        negative: Boolean,
        forcePrecision: Boolean,
        forceSciNotation: Boolean,
        insertCommas: Boolean
    ): FormattedResult {
        val minusSpace = if (negative) 1 else 0
        val msdIndex = if (truncated) -1 else getNaiveMsdIndexOf(input) // INVALID_MSD is OK.
        var result = input
        // Ellipsis may be removed again in the type(1) scientific notation case.
        val needEllipsis = truncated || (negative && result[0] != '-')
        if (needEllipsis) result = KeyMaps.ELLIPSIS + result.substring(1)
        val decIndex = result.indexOf('.')
        var lastDisplayedOffset = precOffset
        if (forceSciNotation ||
            (decIndex == -1 || msdIndex != Evaluator.INVALID_MSD && msdIndex - decIndex > MAX_LEADING_ZEROES + 1) &&
            precOffset != -1
        ) {
            // Either:
            // 1) No decimal point displayed, and it's not just to the right of the last digit, or
            // 2) we are at the front of a number whose integral part is too large to allow
            // comma insertion, or
            // 3) we should suppress leading zeroes.
            // Add an exponent to let the user track which digits are currently displayed.
            // Start with type (2) exponent if we dropped no digits. -1 accounts for decimal
            // point. We currently never show digit separators together with an exponent.
            val initExponent = if (precOffset > 0) -precOffset else -precOffset - 1
            var exponent = initExponent
            var hasPoint = false
            if (!truncated && msdIndex < maxDigs - 1 &&
                result.length - msdIndex + 1 + minusSpace <= maxDigs + SCI_NOTATION_EXTRA
            ) {
                // Type (1) exponent computation and transformation:
                // Leading digit is in display window. Use standard calculator scientific
                // notation with one digit to the left of the decimal point. Insert decimal point
                // and delete leading zeroes.
                // We try to keep leading digits roughly in position, and never
                // lengthen the result by more than SCI_NOTATION_EXTRA.
                if (decIndex > msdIndex) {
                    // In the forceSciNotation, we can have a decimal point in the relevant digit
                    // range. Remove it. msdIndex and precOffset unaffected.
                    result = result.removeRange(decIndex, decIndex + 1)
                }
                val resLen = result.length
                result = (if (negative) "-" else "") + result[msdIndex] + "." + result.substring(msdIndex + 1)
                // Original exp was correct for decimal point at right of fraction.
                // Adjust by length of fraction.
                exponent = initExponent + resLen - msdIndex - 1
                hasPoint = true
            }
            // Exponent can't be zero.
            // Actually add the exponent of either type:
            if (!forcePrecision) {
                var dropDigits: Int // Digits to drop to make room for exponent.
                if (hasPoint) {
                    // Type (1) exponent.
                    // Drop digits even if there is room. Otherwise the scrolling gets jumpy.
                    dropDigits = expLen(exponent)
                    // Jumpy is better than no mantissa.  Probably impossible anyway.
                    if (dropDigits >= result.length - 1) dropDigits = max(result.length - 2, 0)
                } else {
                    // Type (2) exponent.
                    // Exponent depends on the number of digits we drop, which depends on
                    // exponent ...
                    dropDigits = 2
                    while (expLen(initExponent + dropDigits) > dropDigits) ++dropDigits
                    exponent = initExponent + dropDigits
                    if (precOffset - dropDigits > lsdOffset) {
                        // This can happen if e.g. result = 10^40 + 10^10
                        // It turns out we would otherwise display ...10e9 because it takes
                        // the same amount of space as ...1e10 but shows one more digit.
                        // But we don't want to display a trailing zero, even if it's free.
                        ++dropDigits
                        ++exponent
                    }
                }
                // Display too small to show meaningful result.
                if (dropDigits >= result.length - 1) {
                    return FormattedResult(KeyMaps.ELLIPSIS + "E" + KeyMaps.ELLIPSIS, lastDisplayedOffset)
                }
                result = result.dropLast(dropDigits)
                lastDisplayedOffset -= dropDigits
            }
            result = result + "E" + exponent
        } else if (insertCommas) {
            // Add commas to the whole number section, and then truncate on left to fit,
            // counting commas as a fractional digit.
            val wholeStart = if (needEllipsis) 1 else 0
            val wholeEnd = if (decIndex != -1) decIndex else result.length
            val origLength = result.length - wholeStart // Exclude ellipsis.
            val nCommaChars = separatorChars(result, wholeEnd)
            result = result.addCommas(wholeStart, wholeEnd) + result.substring(wholeEnd)
            var deletedChars = 0
            val effectiveLen = origLength + nCommaChars - (if (decIndex == -1) 0f else getDecimalCredit())
            val ellipsisAdjustment = if (needEllipsis) noExponentCredit else getNoEllipsisCredit()
            // As above, we allow for a tiny amount of extra length here, for consistency with
            // getPreferredPrec().
            if (effectiveLen - ellipsisAdjustment > (maxDigs - wholeStart).toFloat() + 0.0001f && !forcePrecision) {
                var deletedWidth = 0.0f
                while (effectiveLen - noExponentCredit - deletedWidth > (maxDigs - 1 /* for ellipsis */).toFloat()) {
                    deletedWidth += if (result[deletedChars] == ',') groupingSeparatorWidthRatio else 1.0f
                    deletedChars++
                }
            }
            if (deletedChars > 0) {
                result = KeyMaps.ELLIPSIS + result.substring(deletedChars)
            } else if (needEllipsis) {
                result = KeyMaps.ELLIPSIS + result
            }
        }
        return FormattedResult(result, lastDisplayedOffset)
    }

    /**
     * Get formatted, but not internationalized, result from evaluator.
     * @param precOffset requested position (1 = tenths) of last included digit
     * @param maxSize maximum number of characters (more or less) in result
     * @param forcePrecision Ensure that last included digit is at pos, at the expense
     *                       of treating maxSize as a soft limit.
     * @param forceSciNotation Force scientific notation, even if not required by maxSize.
     * @param insertCommas Insert commas as digit separators.
     * @return the formatted result, together with the actual offset of the last included digit,
     *                       after adjusting for exponent, etc.
     */
    private fun getFormattedResult(
        precOffset: Int,
        maxSize: Int,
        forcePrecision: Boolean,
        forceSciNotation: Boolean,
        insertCommas: Boolean
    ): FormattedResult {
        val raw = evaluator.getString(index, precOffset, maxCharOffset, maxSize, this)
        return formatResult(raw.text, raw.precOffset, maxSize, raw.truncated, raw.negative, forcePrecision, forceSciNotation, insertCommas)
    }

    /**
     * Return entire result (within reason) up to current displayed precision.
     * @param withSeparators  Add digit separators
     */
    fun getFullText(withSeparators: Boolean): String = when {
        !valid -> ""
        !scrollable -> text.toString()
        else -> KeyMaps.translateResult(
            getFormattedResult(
                lastDisplayedOffset, MAX_COPY_SIZE,
                true /* forcePrecision */, false /* forceSciNotation */, withSeparators
            ).text
        )
    }

    /**
     * Did the above produce a correct result?
     * UI thread only.
     */
    val fullTextIsExact: Boolean
        get() = !scrollable ||
            (getCharOffset(maxPos) == getCharOffset(currentPos) && maxCharOffset != MAX_RIGHT_SCROLL)

    /**
     * Get entire result up to current displayed precision, or up to MAX_COPY_EXTRA additional
     * digits, if it will lead to an exact result.
     */
    fun getFullCopyText(): String {
        val exactResult = evaluator.getResult(index)
        if (!valid || exactResult == null ||
            lsdOffset == Int.MAX_VALUE ||
            fullTextIsExact ||
            wholeLen > MAX_RECOMPUTE_DIGITS ||
            wholeLen + lsdOffset > MAX_RECOMPUTE_DIGITS ||
            lsdOffset - lastDisplayedOffset > MAX_COPY_EXTRA
        ) {
            return getFullText(false /* withSeparators */)
        }
        // It's reasonable to compute and copy the exact result instead.
        var fractionLsdOffset = max(0, lsdOffset)
        var rawResult = exactResult.toStringTruncated(fractionLsdOffset)
        if (lsdOffset <= -1) {
            // Result has trailing decimal point. Remove it.
            rawResult = rawResult.dropLast(1)
            fractionLsdOffset = -1
        }
        val formattedResult = formatResult(
            rawResult, fractionLsdOffset, MAX_COPY_SIZE,
            false, rawResult[0] == '-', true /* forcePrecision */,
            false /* forceSciNotation */, false /* insertCommas */
        )
        return KeyMaps.translateResult(formattedResult.text)
    }

    /**
     * Return the maximum number of characters that will fit in the result display.
     * May be called asynchronously from non-UI thread. From Evaluator.CharMetricsInfo.
     * Returns zero if measurement hasn't completed.
     */
    override fun getMaxChars() = synchronized(widthLock) { floor(widthConstraint / charWidth).toInt() }

    /** `true` if the currently displayed result is scrollable. */
    val isScrollable: Boolean get() = scrollable

    /**
     * Map pixel position to digit offset.
     * UI thread only.
     */
    private fun getCharOffset(pos: Int) = (pos / charWidth).roundToInt() // Lock not needed.

    fun clear() {
        valid = false
        scrollable = false
        text = ""
        isLongClickable = false
    }

    override fun onCancelled(index: Long) {
        clear()
        storeToMemoryRequested = false
    }

    /**
     * Refresh display.
     * Only called in UI thread. Index argument is currently ignored.
     */
    override fun onReevaluate(index: Long) = redisplay()

    fun redisplay() {
        val maxChars = getMaxChars()
        // Display currently too small to display a reasonable result. Punt to avoid crash.
        if (maxChars < 4) return
        if (scroller.isFinished && length() > 0) accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        val currentCharOffset = getCharOffset(currentPos)
        val formatted = getFormattedResult(
            currentCharOffset, maxChars,
            appendExponent /* forcePrecision; preserve entire result */,
            !wholePartFits && currentCharOffset == getCharOffset(minPos) /* forceSciNotation */,
            wholePartFits /* insertCommas */
        )
        val expIndex = formatted.text.indexOf('E')
        val result = KeyMaps.translateResult(formatted.text)
        text = if (expIndex > 0 && result.indexOf('.') == -1) {
            // Gray out exponent if used as position indicator
            SpannableString(result).apply {
                setSpan(exponentColorSpan, expIndex, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            result
        }
        lastDisplayedOffset = formatted.lastDisplayedOffset
        valid = true
        isLongClickable = true
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)

        if (scrollable && !scroller.isFinished) return
        if (lengthBefore == 0 && lengthAfter > 0) {
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            contentDescription = null
        } else if (lengthBefore > 0 && lengthAfter == 0) {
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
            contentDescription = context.getString(R.string.desc_result)
        }
    }

    override fun computeScroll() {
        if (!scrollable) return

        if (scroller.computeScrollOffset()) {
            currentPos = scroller.currX
            if (getCharOffset(currentPos) != getCharOffset(lastPos)) {
                lastPos = currentPos
                redisplay()
            }
        }

        if (!scroller.isFinished) {
            postInvalidateOnAnimation()
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        } else if (length() > 0) {
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    private fun createContextMenu(inflater: MenuInflater, menu: Menu): Boolean {
        inflater.inflate(R.menu.menu_result, menu)
        val displayMemory = evaluator.memoryIndex != 0L
        listOf(R.id.memory_add, R.id.memory_subtract).forEach { menu.findItem(it).isEnabled = displayMemory }
        highlightResult()
        return true
    }

    fun stopActionModeOrContextMenu(): Boolean = actionMode?.let { it.finish(); true } ?: false

    private fun highlightResult() =
        (text as Spannable).run { setSpan(highlightSpan, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    private fun unhighlightResult() = (text as Spannable).removeSpan(highlightSpan)

    private fun copyContent() {
        val text: CharSequence = getFullCopyText()
        // We include a tag URI, to allow us to recognize our own results and handle them
        // specially.
        val newItem = ClipData.Item(text, null, evaluator.capture(index))
        val cd = ClipData("calculator result", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), newItem)
        context.systemService<ClipboardManager>().setPrimaryClip(cd)
        Toast.makeText(context, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.memory_add -> {
            onMemoryAdd()
            true
        }
        R.id.memory_subtract -> {
            onMemorySubtract()
            true
        }
        R.id.memory_store -> {
            onMemoryStore()
            true
        }
        // Refuse to copy placeholder characters.
        R.id.menu_copy -> !evaluator.evaluationInProgress(index).also { inProgress ->
            if (!inProgress) {
                copyContent()
                unhighlightResult()
            }
        }
        else -> false
    }

    override fun onDetachedFromWindow() {
        stopActionModeOrContextMenu()
        super.onDetachedFromWindow()
    }

    companion object {
        const val MAX_RIGHT_SCROLL = 10000000
        // A larger value is unlikely to avoid running out of space
        const val INVALID = MAX_RIGHT_SCROLL + 10000

        // Maximum number of leading zeroes after decimal point before we switch to scientific
        // notation with negative exponent.
        const val MAX_LEADING_ZEROES = 6
        // Maximum number of trailing zeroes before the decimal point before we switch to
        // scientific notation with positive exponent.
        const val MAX_TRAILING_ZEROES = 6
        // Extra digits for standard scientific notation.  In this case we have a decimal point
        // and no ellipsis. We assume that we do not drop digits to make room for the decimal
        // point in ordinary scientific notation. Thus >= 1.
        private const val SCI_NOTATION_EXTRA = 1
        // The number of extra digits we are willing to compute to copy a result as an exact
        // number.
        private const val MAX_COPY_EXTRA = 100
        // The maximum number of digits we're willing to recompute in the UI thread.  We only do
        // this for known rational results, where we can bound the computation cost.
        private const val MAX_RECOMPUTE_DIGITS = 2000

        private const val MAX_COPY_SIZE = 1000000

        // Compute maximum digit width the hard way.
        // Compute the maximum advance width for each digit, thus accounting for
        // between-character spaces. If we ever support other kinds of digits, we may have to
        // avoid kerning effects that could reduce the advance width within this particular
        // string.
        private fun getMaxDigitWidth(paint: TextPaint): Float =
            FloatArray(10).also { paint.getTextWidths("0123456789", it) }.max()

        /**
         * Return the most significant digit position in the given string or
         * Evaluator.INVALID_MSD. Unlike Evaluator.getMsdIndexOf, we treat a final 1 as
         * significant. Pure function; callable from anywhere.
         */
        fun getNaiveMsdIndexOf(s: String): Int =
            s.indexOfFirst { it != '-' && it != '.' && it != '0' }.takeIf { it >= 0 } ?: Evaluator.INVALID_MSD
    }
}
