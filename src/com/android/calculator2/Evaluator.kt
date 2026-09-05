/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context
import android.net.Uri
import android.text.Spannable
import android.util.Log

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting

import com.hp.creals.CR

import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * This implements the calculator evaluation logic.
 * Logically this maintains a signed integer indexed set of expressions, one of which
 * is distinguished as the main expression.
 * The main expression is constructed and edited with append(), delete(), etc.
 * An evaluation an then be started with a call to evaluateAndNotify() or requireResult().
 * This starts an asynchronous computation, which requests display of the initial result, when
 * available.  When initial evaluation is complete, it calls the associated listener's
 * onEvaluate() method.  This occurs in a separate event, possibly quite a bit later.  Once a
 * result has been computed, and before the underlying expression is modified, the
 * getString(index) method may be used to produce Strings that represent approximations to various
 * precisions.
 *
 * Actual expressions being evaluated are represented as [CalculatorExpr]s.
 *
 * The Evaluator holds the expressions and all associated state needed for evaluating
 * them.  It provides functionality for saving and restoring this state.  However the underlying
 * CalculatorExprs are exposed to the client, and may be directly accessed after cancelling any
 * in-progress computations by invoking the cancelAll() method.
 *
 * When evaluation is requested, we invoke the eval() method on the CalculatorExpr from a
 * background coroutine.  A subsequent getString() call for the same expression index returns
 * immediately, though it may return a result containing placeholder ' ' characters.  If we had to
 * return placeholder characters, we start a background coroutine, which invokes the
 * onReevaluate() callback when it completes.  In either case, the background coroutine computes
 * the appropriate result digits by evaluating the UnifiedReal returned by CalculatorExpr.eval()
 * to the required precision.
 *
 * We cache the best decimal approximation we have already computed.  We compute generously to
 * allow for some scrolling without recomputation and to minimize the chance of digits flipping
 * from "0000" to "9999".  The best known result approximation is maintained as a string by
 * resultString (and often in a different format by the CR representation of the result).  When
 * we are in danger of not having digits to display in response to further scrolling, we also
 * initiate a background computation to higher precision, as if we had generated placeholder
 * characters.
 *
 * The code is designed to ensure that the error in the displayed result (excluding any
 * placeholder characters) is always strictly less than 1 in the last displayed digit.  Typically
 * we actually display a prefix of a result that has this property and additionally is computed to
 * a significantly higher precision.  Thus we almost always round correctly towards zero.  (Fully
 * correct rounding towards zero is not computable, at least given our representation.)
 *
 * Initial expression evaluation may time out.  This may happen in the case of domain errors such
 * as division by zero, or for large computations.  We do not currently time out reevaluations to
 * higher precision, since the original evaluation precluded a domain error that could result in
 * non-termination.  (We may discover that a presumed zero result is actually slightly negative
 * when re-evaluated; but that results in an exception, which we can handle.)  The user can abort
 * either kind of computation.
 *
 * We ensure that only one evaluation of either kind (initial evaluation or reevaluation) is
 * running at a time for any given expression. Cancelling an evaluation interrupts its thread;
 * the constructive real library polls for interrupts and aborts promptly.
 *
 * All public methods must be called from the UI thread unless documented otherwise. Changes to
 * the memory slot, the degree mode and requests to show dialogs are published as flows.
 */
class Evaluator private constructor(
    // Context for database helper.
    private val context: Context
) : CalculatorExpr.ExprResolver {

    interface EvaluationListener {
        /** Called if evaluation was explicitly cancelled or evaluation timed out. */
        fun onCancelled(index: Long)

        /** Called if evaluation resulted in an error. */
        fun onError(index: Long, errorId: Int)

        /**
         * Called if evaluation completed normally.
         * @param index index of expression whose evaluation completed
         * @param initPrecOffset the offset used for initial evaluation
         * @param msdIndex index of first non-zero digit in the computed result string
         * @param lsdOffset offset of last digit in result if result has finite decimal
         *        expansion
         * @param truncatedWholePart the integer part of the result
         */
        fun onEvaluate(
            index: Long,
            initPrecOffset: Int,
            msdIndex: Int,
            lsdOffset: Int,
            truncatedWholePart: String
        )

        /**
         * Called in response to a reevaluation request, once more precision is available.
         * Typically the listener will respond by calling getString() to retrieve the new
         * better approximation.
         */
        fun onReevaluate(index: Long) // More precision is now available; please redraw.
    }

    /**
     * A query interface for derived information based on character widths.
     * This provides information we need to calculate the "preferred precision offset" used
     * to display the initial result. It's used to compute the number of digits we can actually
     * display. All methods are callable from any thread.
     */
    interface CharMetricsInfo {
        /**
         * Return the maximum number of (adjusted, digit-width) characters that will fit in the
         * result display.  May be called asynchronously from non-UI thread.
         */
        fun getMaxChars(): Int

        /**
         * Return the number of additional digit widths required to add digit separators to
         * the supplied string prefix.
         * The prefix consists of the first len characters of string s, which is presumed to
         * represent a whole number. Callable from non-UI thread.
         * Returns zero if metrics information is not yet available.
         */
        fun separatorChars(s: String, len: Int): Float

        /**
         * Return extra width credit for presence of a decimal point, as fraction of a digit
         * width. May be called by non-UI thread.
         */
        fun getDecimalCredit(): Float

        /**
         * Return extra width credit for absence of ellipsis, as fraction of a digit width.
         * May be called by non-UI thread.
         */
        fun getNoEllipsisCredit(): Float
    }

    /**
     * A request to show a message dialog to the user; see [dialogRequests].
     * A [title] or [positiveButtonLabel] of 0 means none.
     */
    data class DialogRequest(
        @StringRes val title: Int,
        @StringRes val message: Int,
        @StringRes val positiveButtonLabel: Int,
        val tag: String?
    )

    /**
     * Result of [getString].
     * @param text the requested digits, possibly padded with blanks for unknown digits.
     * @param precOffset the actual precision offset of the last character in text.
     * @param truncated leading nonzero digits were dropped.
     * @param negative the result is negative.
     */
    data class StringResult(
        val text: String,
        val precOffset: Int,
        val truncated: Boolean,
        val negative: Boolean
    )

    /**
     * A CharMetricsInfo that can be used when we are really only interested in computing
     * short representations to be embedded on formulas.
     */
    private object DummyCharMetricsInfo : CharMetricsInfo {
        override fun getMaxChars() = SHORT_TARGET_LENGTH + 10
        override fun separatorChars(s: String, len: Int) = 0f
        override fun getDecimalCredit() = 0f
        override fun getNoEllipsisCredit() = 0f
    }

    /**
     * A background evaluation of one expression. [job] runs on the UI thread and hands the
     * actual computation to a background thread; cancelling it interrupts that thread.
     * @param initial this is an initial evaluation rather than a reevaluation to more digits.
     * @param required the result was explicitly requested by the user.
     * @param quiet suppress the cancellation message.
     */
    private class Evaluation(val initial: Boolean, val required: Boolean, var quiet: Boolean) {
        lateinit var job: Job
    }

    /**
     * An individual CalculatorExpr, together with its evaluation state.
     * Only the main expression may be changed in-place. The HISTORY_MAIN_INDEX expression is
     * periodically reset to be a fresh immutable copy of the main expression.
     * All other expressions are only added and never removed. The expressions themselves are
     * never modified.
     * All fields other than expr and value are touched only by the UI thread.
     * For MAIN_INDEX, expr and value may change, but are also only ever touched by the UI thread.
     * For all other expressions, expr does not change once the ExprInfo has been (atomically)
     * added to exprs. value may be asynchronously set by any thread, but we take care that it
     * does not change after that. degreeMode is handled exactly like expr.
     */
    private class ExprInfo(
        var expr: CalculatorExpr, // The expression itself.
        var degreeMode: Boolean // Evaluating in degree, not radian, mode.
    ) {
        // Currently running expression evaluation, if any.  This is either an initial
        // evaluation (if resultString == null or it's obsolete), or a reevaluation.
        // We arrange that only one evaluation is active at a time, in part by maintaining
        // two separate ExprInfo structure for the main and history view, so that they can
        // arrange for independent evaluations.
        var evaluator: Evaluation? = null

        // The remaining fields are valid only if an evaluation completed successfully.
        // value always points to an AtomicReference, but that may be null.
        var value: AtomicReference<UnifiedReal?> = AtomicReference()

        // We cache the best known decimal result in resultString.  Whenever that is
        // non-null, it is computed to exactly resultStringOffset, which is always > 0.
        // Valid only if resultString is non-null and (for the main expression) !changedValue.
        // ERRONEOUS_RESULT indicates evaluation resulted in an error.
        var resultString: String? = null
        var resultStringOffset = 0

        // Number of digits to which (possibly incomplete) evaluation has been requested.
        // Only accessed by UI thread.
        var resultStringOffsetReq = 0

        // Position of most significant digit in current cached result, if determined.  This is
        // just the index in resultString holding the msd.
        var msdIndex = INVALID_MSD

        // Long timeout needed for evaluation?
        var longTimeout = false
        var timeStamp = 0L

        /** The cached decimal result, if evaluation completed without error. */
        val validResultString: String?
            get() = resultString?.takeUnless { it == ERRONEOUS_RESULT }
    }

    /**
     * Result of initial asynchronous result computation.
     * Represents either an error or a result computed to an initial evaluation precision.
     */
    private class InitialResult private constructor(
        val errorResourceId: Int, // Error string or INVALID_RES_ID.
        val value: UnifiedReal, // Constructive real value.
        val newResultString: String, // "BAD" iff it can't be computed.
        val newResultStringOffset: Int,
        val initDisplayOffset: Int
    ) {
        constructor(v: UnifiedReal, s: String, p: Int, idp: Int) :
            this(Calculator.INVALID_RES_ID, v, s, p, idp)

        constructor(errorId: Int) : this(errorId, UnifiedReal.ZERO, "BAD", 0, 0)

        val isError: Boolean get() = errorResourceId != Calculator.INVALID_RES_ID
    }

    /**
     * Result of asynchronous reevaluation.
     */
    private data class ReevalResult(val newResultString: String, val newResultStringOffset: Int)

    /** Runs UI-side evaluation logic; background work is dispatched from here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val preferences = CalculatorPreferences(context, scope)

    /** Index of "saved" expression mirroring clipboard. 0 if unused. */
    var savedIndex = 0L
        private set

    private val _memoryIndex = MutableStateFlow(0L)

    /** Index of the "memory" expression, or 0 if unused. Emits whenever memory changes. */
    val memoryIndexFlow: StateFlow<Long> = _memoryIndex.asStateFlow()

    /** Index of "memory" expression, or 0 if unused. */
    val memoryIndex: Long get() = _memoryIndex.value

    private val _degreeMode: MutableStateFlow<Boolean>

    /** Whether the main expression is evaluated in degrees rather than radians. */
    val degreeModeFlow: StateFlow<Boolean> get() = _degreeMode

    private val _dialogRequests = MutableSharedFlow<DialogRequest>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Dialogs the evaluator would like the UI to show (timeouts and cancellations). */
    val dialogRequests: SharedFlow<DialogRequest> = _dialogRequests.asSharedFlow()

    //  A hopefully unique name associated with the saved expression.
    private var savedName = CalculatorPreferences.DEFAULT_SAVED_NAME

    // The main expression may have changed since the last evaluation in ways that would affect its
    // value.
    private var changedValue = false

    private val exprs = ConcurrentHashMap<Long, ExprInfo>()

    // The database holding persistent expressions.
    private val exprDB = ExpressionDB(context)

    private lateinit var mainExpr: ExprInfo //  == exprs[MAIN_INDEX]

    private fun setMainExpr(expr: ExprInfo) {
        mainExpr = expr
        exprs[MAIN_INDEX] = expr
    }

    init {
        val settings = preferences.readBlocking()
        _degreeMode = MutableStateFlow(settings.degreeMode)
        setMainExpr(ExprInfo(CalculatorExpr(), settings.degreeMode))
        savedName = settings.savedName
        // -1 could have been stored by an old bug; recover from that corruption.
        if (settings.savedIndex != 0L && settings.savedIndex != -1L) {
            setSavedIndexWhenEvaluated(settings.savedIndex)
        }
        if (settings.memoryIndex != 0L && settings.memoryIndex != -1L) {
            setMemoryIndexWhenEvaluated(settings.memoryIndex, false /* no need to persist again */)
        }
    }

    /**
     * Retrieve minimum expression index.
     * This is the minimum over all expressions, including uncached ones residing only
     * in the data base. If no expressions with negative indices were preserved, this will
     * return a small negative predefined constant.
     * May be called from any thread, but will block until the database is opened.
     */
    fun getMinIndex() = exprDB.getMinIndex()

    /**
     * Retrieve maximum expression index.
     * This is the maximum over all expressions, including uncached ones residing only
     * in the data base. If no expressions with positive indices were preserved, this will
     * return 0.
     * May be called from any thread, but will block until the database is opened.
     */
    fun getMaxIndex() = exprDB.getMaxIndex()

    /** Does the expression index refer to a transient and mutable expression? */
    private fun isMutableIndex(index: Long) = index == MAIN_INDEX || index == HISTORY_MAIN_INDEX

    /** Return the cached ExprInfo for an index that is known to be cached. */
    private fun exprInfo(index: Long): ExprInfo =
        exprs[index] ?: throw AssertionError("No cached expression at index $index")

    private fun displayCancelledMessage() {
        _dialogRequests.tryEmit(DialogRequest(0, R.string.cancelled, 0, null))
    }

    // Timeout handling.
    // Expressions are evaluated with a short timeout or a long timeout.
    // Each implies different maxima on both computation time and bit length.
    // We recheck bit length separately to avoid wasting time on decimal conversions that are
    // destined to fail.

    /**
     * Return the timeout in milliseconds.
     * Exceeding a few tens of seconds increases the risk of running out of memory
     * and impacting the rest of the system.
     * @param longTimeout a long timeout is in effect
     */
    private fun getTimeout(longTimeout: Boolean) = if (longTimeout) 15000L else 2000L

    /**
     * Return the maximum number of bits in the result.  Longer results are assumed to time out.
     * @param longTimeout a long timeout is in effect
     */
    private fun getMaxResultBits(longTimeout: Boolean) = if (longTimeout) 700000 else 240000

    private fun displayTimeoutMessage(longTimeout: Boolean) {
        _dialogRequests.tryEmit(
            DialogRequest(
                R.string.dialog_timeout, R.string.timeout,
                if (longTimeout) 0 else R.string.ok_remove_timeout, TIMEOUT_DIALOG_TAG
            )
        )
    }

    fun setLongTimeout() {
        mainExpr.longTimeout = true
    }

    /**
     * Run [block] on a background thread and return its result.
     * If the calling coroutine is cancelled, the background thread is interrupted, which the
     * constructive real library turns into a prompt [CR.AbortedException]; we do not wait for
     * that to happen before returning to the caller.
     */
    private suspend fun <T> compute(block: () -> T): T {
        val computation = scope.async(Dispatchers.Default) { runInterruptible(block = block) }
        try {
            return computation.await()
        } catch (e: CancellationException) {
            computation.cancel()
            throw e
        }
    }

    /**
     * Compute initial cache contents and result. Runs on a background thread.
     * Can result in an error result if something goes wrong.
     */
    private fun computeInitialResult(
        index: Long,
        exprInfo: ExprInfo,
        degreeMode: Boolean,
        required: Boolean,
        charMetricsInfo: CharMetricsInfo
    ): InitialResult {
        try {
            // expr does not change while we are evaluating; thus it's OK to read here.
            val res = exprInfo.value.get() ?: run {
                try {
                    val evaluated = exprInfo.expr.eval(degreeMode, this)
                    // TODO: This remains very slightly racey. Fix this.
                    if (Thread.currentThread().isInterrupted) throw CR.AbortedException()
                    putResultIfAbsent(index, evaluated)
                } catch (e: StackOverflowError) {
                    // Absurdly large integer exponents can cause this. There might be other
                    // examples as well. Treat it as a timeout.
                    return InitialResult(R.string.timeout)
                }
            }
            // Avoid starting a long uninterruptible decimal conversion.
            val maxBits = if (required) getMaxResultBits(exprInfo.longTimeout) else QUICK_MAX_RESULT_BITS
            if (res.approxWholeNumberBitsGreaterThan(maxBits)) return InitialResult(R.string.timeout)
            var precOffset = INIT_PREC
            var initResult = res.toStringTruncated(precOffset)
            var msd = getMsdIndexOf(initResult)
            if (msd == INVALID_MSD) {
                val leadingZeroBits = res.leadingBinaryZeroes()
                if (leadingZeroBits < QUICK_MAX_RESULT_BITS) {
                    // Enough initial nonzero digits for most displays.
                    precOffset = 30 + ceil(ln(2.0) / ln(10.0) * leadingZeroBits).toInt()
                    initResult = res.toStringTruncated(precOffset)
                    msd = getMsdIndexOf(initResult)
                    if (msd == INVALID_MSD) throw AssertionError("Impossible zero result")
                } else {
                    // Just try once more at higher fixed precision.
                    precOffset = MAX_MSD_PREC_OFFSET
                    initResult = res.toStringTruncated(precOffset)
                    msd = getMsdIndexOf(initResult)
                }
            }
            val lsdOffset = getLsdOffset(res, initResult, initResult.indexOf('.'))
            val initDisplayOffset = getPreferredPrec(initResult, msd, lsdOffset, charMetricsInfo)
            val newPrecOffset = initDisplayOffset + EXTRA_DIGITS
            if (newPrecOffset > precOffset) {
                precOffset = newPrecOffset
                initResult = res.toStringTruncated(precOffset)
            }
            return InitialResult(res, initResult, precOffset, initDisplayOffset)
        } catch (e: CalculatorExpr.SyntaxException) {
            return InitialResult(R.string.error_syntax)
        } catch (e: UnifiedReal.ZeroDivisionException) {
            return InitialResult(R.string.error_zero_divide)
        } catch (e: ArithmeticException) {
            return InitialResult(R.string.error_nan)
        } catch (e: CR.PrecisionOverflowException) {
            // Extremely unlikely unless we're actually dividing by zero or the like.
            return InitialResult(R.string.error_overflow)
        } catch (e: CR.AbortedException) {
            return InitialResult(R.string.error_aborted)
        }
    }

    /**
     * Perform an initial evaluation and notify the listener. Runs on the UI thread, with the
     * actual computation on a background thread.
     * We leave the expression display up, with scrolling disabled, until this computation
     * completes.  Can result in an error display if something goes wrong.  By default we set a
     * timeout to catch runaway computations.
     */
    private suspend fun evaluateInitially(
        index: Long,
        exprInfo: ExprInfo,
        evaluation: Evaluation,
        listener: EvaluationListener,
        charMetricsInfo: CharMetricsInfo
    ) {
        val timeout = when {
            // We evaluated the expression before with the current timeout, so this shouldn't
            // ever time out. We evaluate it with a ridiculously long timeout to avoid running
            // down the battery if something does go wrong. But we only log such timeouts, and
            // invoke the listener with onCancelled.
            index != MAIN_INDEX -> NON_MAIN_TIMEOUT
            evaluation.required -> getTimeout(exprInfo.longTimeout)
            else -> QUICK_TIMEOUT
        }
        val degreeMode = exprInfo.degreeMode
        val result = try {
            withTimeoutOrNull(timeout) {
                compute { computeInitialResult(index, exprInfo, degreeMode, evaluation.required, charMetricsInfo) }
            }
        } catch (e: CancellationException) {
            // Invoker reset the evaluator. If it did not ask for silence, tell the user.
            if (!evaluation.quiet) displayCancelledMessage()
            // Just drop the evaluation; Leave expression displayed.
            listener.onCancelled(index)
            return
        }
        if (result == null) {
            // Timed out. The background thread has been told to stop; don't wait for it.
            exprs[index]?.takeIf { it.evaluator === evaluation }?.evaluator = null
            if (evaluation.required && index == MAIN_INDEX) {
                // Replace expr with a copy to avoid races if the computation still runs for
                // a while.
                mainExpr.expr = mainExpr.expr.copy()
                displayTimeoutMessage(exprInfo.longTimeout)
            }
            listener.onCancelled(index)
            return
        }
        exprInfo.evaluator = null
        if (result.isError) {
            if (result.errorResourceId == R.string.timeout) {
                // Emulating timeout due to large result.
                if (evaluation.required && index == MAIN_INDEX) {
                    displayTimeoutMessage(exprs[index]?.longTimeout ?: exprInfo.longTimeout)
                }
                listener.onCancelled(index)
            } else {
                if (evaluation.required) exprInfo.resultString = ERRONEOUS_RESULT
                listener.onError(index, result.errorResourceId)
            }
            return
        }
        // exprInfo.value was already set by the background thread.
        val resultString = result.newResultString
        exprInfo.resultString = resultString
        exprInfo.resultStringOffset = result.newResultStringOffset
        val dotIndex = resultString.indexOf('.')
        val truncatedWholePart = resultString.substring(0, dotIndex)
        // Recheck display precision; it may change, since display dimensions may have been
        // unknown the first time.  In that case the initial evaluation precision should have
        // been conservative.
        // TODO: Could optimize by remembering display size and checking for change.
        exprInfo.msdIndex = getMsdIndexOf(resultString)
        val leastDigOffset = getLsdOffset(result.value, resultString, dotIndex)
        val newInitPrecOffset =
            getPreferredPrec(resultString, exprInfo.msdIndex, leastDigOffset, charMetricsInfo)
        // They should be equal.  But nothing horrible should happen if they're not. e.g.
        // because CalculatorResult.MAX_WIDTH was too small.
        val initPrecOffset = min(result.initDisplayOffset, newInitPrecOffset)
        listener.onEvaluate(index, initPrecOffset, exprInfo.msdIndex, leastDigOffset, truncatedWholePart)
    }

    /**
     * Compute new resultString contents to precOffset digits to the right of the decimal point.
     * Ensure that onReevaluate() is called after doing so.  If the evaluation fails for reasons
     * other than a timeout, ensure that onError() is called.
     * This assumes that initial evaluation of the expression has been successfully
     * completed. Runs on the UI thread, with the actual computation on a background thread.
     */
    private suspend fun reevaluate(index: Long, exprInfo: ExprInfo, precOffset: Int, listener: EvaluationListener) {
        val result = try {
            compute {
                try {
                    ReevalResult(checkNotNull(exprInfo.value.get()).toStringTruncated(precOffset), precOffset)
                } catch (e: ArithmeticException) {
                    null
                } catch (e: CR.PrecisionOverflowException) {
                    null
                } catch (e: CR.AbortedException) {
                    // Should only happen if we were cancelled, in which case we don't look at
                    // the result.
                    null
                }
            }
        } catch (e: CancellationException) {
            // Invoker should have left no trace of us.
            return
        }
        if (result == null) {
            // This should only be possible in the extremely rare case of encountering a
            // domain error while reevaluating or in case of a precision overflow.  We don't
            // know of a way to get the latter with a plausible amount of user input.
            exprInfo.resultString = ERRONEOUS_RESULT
            listener.onError(index, R.string.error_nan)
        } else {
            if (result.newResultStringOffset < exprInfo.resultStringOffset) {
                throw AssertionError("Unexpected reevaluation timing")
            }
            exprInfo.resultString = unflipZeroes(
                checkNotNull(exprInfo.resultString), exprInfo.resultStringOffset,
                result.newResultString, result.newResultStringOffset
            )
            exprInfo.resultStringOffset = result.newResultStringOffset
            listener.onReevaluate(index)
        }
        exprInfo.evaluator = null
    }

    /**
     * If necessary, start an evaluation of the expression at the given index to precOffset.
     * If we start an evaluation the listener is notified on completion.
     * Only called if prior evaluation succeeded.
     */
    private fun ensureCachePrec(index: Long, precOffset: Int, listener: EvaluationListener) {
        val ei = exprInfo(index)
        if (ei.resultString != null && ei.resultStringOffset >= precOffset ||
            ei.resultStringOffsetReq >= precOffset
        ) return
        // Ensure we only have one evaluation running at a time.
        ei.evaluator?.job?.cancel()
        ei.resultStringOffsetReq = precOffset + PRECOMPUTE_DIGITS
        if (ei.resultString != null) {
            ei.resultStringOffsetReq += ei.resultStringOffsetReq / PRECOMPUTE_DIVISOR
        }
        val evaluation = Evaluation(initial = false, required = false, quiet = true)
        ei.evaluator = evaluation
        evaluation.job = scope.launch { reevaluate(index, ei, ei.resultStringOffsetReq, listener) }
    }

    /**
     * Return most significant digit index for the result of the expression at the given index.
     * Returns an index in the result character array.  Return INVALID_MSD if the current result
     * is too close to zero to determine the result.
     * Result is almost consistent through reevaluations: It may increase by one, once.
     */
    private fun getMsdIndex(index: Long): Int {
        val ei = exprInfo(index)
        if (ei.msdIndex != INVALID_MSD) {
            // 0.100000... can change to 0.0999999...  We may have to correct once by one digit.
            if (checkNotNull(ei.resultString)[ei.msdIndex] == '0') ei.msdIndex++
            return ei.msdIndex
        }
        if (ei.value.get()?.definitelyZero == true) return INVALID_MSD // None exists
        return ei.resultString?.let { getMsdIndexOf(it) }?.also { ei.msdIndex = it } ?: INVALID_MSD
    }

    /**
     * Return result to precOffset digits to the right of the decimal point.
     * The returned precOffset is adjusted if the original value is out of range.  No exponent or
     * other indication of precision is added.  The result is returned immediately, based on the
     * current cache contents, but it may contain blanks for unknown digits.  It may also use
     * uncertain digits within EXTRA_DIGITS.  If either of those occurred, schedule a reevaluation
     * and redisplay operation.  Uncertain digits never appear to the left of the decimal point.
     * precOffset may be negative to only retrieve digits to the left of the decimal point.
     * (precOffset = 0 means we include the decimal point, but nothing to the right.
     * precOffset = -1 means we drop the decimal point and start at the ones position.  Should
     * not be invoked before the onEvaluate() callback is received.  This essentially just returns
     * a substring of the full result; a leading minus sign or leading digits can be dropped.
     * Result uses US conventions; is NOT internationalized.  Use getResult() and UnifiedReal
     * operations to determine whether the result is exact, or whether we dropped trailing digits.
     *
     * @param index Index of expression to approximate
     * @param precOffset Desired precision
     * @param maxPrecOffset Maximum adjusted precOffset
     * @param maxDigs Maximum length of result
     * @param listener EvaluationListener to notify when reevaluation is complete.
     * @return the digits, together with the actual precision offset and whether leading nonzero
     *         digits were dropped or the result is negative.
     */
    fun getString(
        index: Long,
        precOffset: Int,
        maxPrecOffset: Int,
        maxDigs: Int,
        listener: EvaluationListener
    ): StringResult {
        val ei = exprInfo(index)
        // Make sure we eventually get a complete answer
        val resultString = ei.resultString ?: run {
            ensureCachePrec(index, precOffset + EXTRA_DIGITS, listener)
            // Nothing else to do now; seems to happen on rare occasion with weird user input
            // timing; Will repair itself in a jiffy.
            return StringResult(" ", precOffset, false, false)
        }
        ensureCachePrec(index, precOffset + EXTRA_DIGITS + resultString.length / EXTRA_DIVISOR, listener)
        // Compute an appropriate substring of resultString.  Pad if necessary.
        val len = resultString.length
        val myNegative = resultString[0] == '-'
        // Don't scroll left past leftmost digits in resultString unless that still leaves an
        // integer.
        val integralDigits = len - ei.resultStringOffset - (if (myNegative) 1 else 0) // includes 1 for dec. pt
        val minPrecOffset = min(MIN_DISPLAYED_DIGS - integralDigits, -1)
        val currentPrecOffset = min(max(precOffset, minPrecOffset), maxPrecOffset)
        val extraDigs = max(ei.resultStringOffset - currentPrecOffset, 0) // trailing digits to drop
        // The number of digits we're short
        val deficit = if (extraDigs > 0) 0 else min(currentPrecOffset - ei.resultStringOffset, maxDigs)
        val endIndex = len - extraDigs
        if (endIndex < 1) return StringResult(" ", currentPrecOffset, false, myNegative)
        val startIndex = max(endIndex + deficit - maxDigs, 0)
        val truncated = startIndex > getMsdIndex(index)
        // Blank characters are replaced during translation.
        // Since we always compute past the decimal point, this never fills in the spot
        // where the decimal point should go, and we can otherwise treat placeholders
        // as though they were digits.
        val result = resultString.substring(startIndex, endIndex) + " ".repeat(deficit)
        return StringResult(result, currentPrecOffset, truncated, myNegative)
    }

    /** Clear the cache for the main expression. */
    private fun clearMainCache() = mainExpr.run {
        value.set(null)
        resultString = null
        resultStringOffset = 0
        resultStringOffsetReq = 0
        msdIndex = INVALID_MSD
    }

    fun clearMain() {
        mainExpr.expr.clear()
        clearMainCache()
        mainExpr.longTimeout = false
    }

    fun clearEverything() {
        val dm = mainExpr.degreeMode
        cancelAll(true)
        setSavedIndex(0)
        setMemoryIndex(0)
        exprDB.eraseAll()
        exprs.clear()
        setMainExpr(ExprInfo(CalculatorExpr(), dm))
    }

    /**
     * Start asynchronous evaluation.
     * Invoke listener on successful completion. If the result is required, invoke
     * onCancelled() if cancelled.
     * @param index index of expression to be evaluated.
     * @param required result was explicitly requested by user.
     */
    private fun evaluateResult(index: Long, listener: EvaluationListener, cmi: CharMetricsInfo, required: Boolean) {
        val ei = exprInfo(index)
        if (ei.evaluator != null) throw AssertionError("Evaluation already in progress!")
        // Otherwise the expression is immutable.
        if (index == MAIN_INDEX) clearMainCache()
        val evaluation = Evaluation(initial = true, required = required, quiet = !required || index != MAIN_INDEX)
        ei.evaluator = evaluation
        evaluation.job = scope.launch { evaluateInitially(index, ei, evaluation, listener, cmi) }
        if (index == MAIN_INDEX) changedValue = false
    }

    /** Notify listener of a previously completed evaluation. */
    private fun notifyImmediately(index: Long, ei: ExprInfo, listener: EvaluationListener, cmi: CharMetricsInfo) {
        val resultString = checkNotNull(ei.resultString)
        val dotIndex = resultString.indexOf('.')
        val leastDigOffset = getLsdOffset(checkNotNull(ei.value.get()), resultString, dotIndex)
        val msdIndex = getMsdIndex(index)
        val preferredPrecOffset = getPreferredPrec(resultString, msdIndex, leastDigOffset, cmi)
        listener.onEvaluate(index, preferredPrecOffset, msdIndex, leastDigOffset, resultString.substring(0, dotIndex))
    }

    /**
     * Start optional evaluation of expression and display when ready.
     * @param index of expression to be evaluated.
     * Can quietly time out without a listener callback.
     * No-op if cmi.getMaxChars() == 0.
     */
    fun evaluateAndNotify(index: Long, listener: EvaluationListener, cmi: CharMetricsInfo) {
        // Probably shouldn't happen. If it does, we didn't promise to do anything anyway.
        if (cmi.getMaxChars() == 0) return
        val ei = ensureExprIsCached(index)
        when {
            // Already done. Just notify.
            ei.validResultString != null && !(index == MAIN_INDEX && changedValue) ->
                notifyImmediately(index, ei, listener, cmi)
            // We only allow a single listener per expression, so this request must be redundant.
            ei.evaluator != null -> return
            else -> evaluateResult(index, listener, cmi, false)
        }
    }

    /**
     * Start required evaluation of expression at given index and call back listener when ready.
     * If index is MAIN_INDEX, we may also directly display a timeout message.
     * Uses longer timeouts than optional evaluation.
     * Requires cmi.getMaxChars() != 0.
     */
    fun requireResult(index: Long, listener: EvaluationListener, cmi: CharMetricsInfo) {
        if (cmi.getMaxChars() == 0) throw AssertionError("requireResult called too early")
        val ei = ensureExprIsCached(index)
        when {
            ei.resultString == null || (index == MAIN_INDEX && changedValue) -> when {
                // We don't want to compute a result for HISTORY_MAIN_INDEX that was
                // not already computed for the main expression. Pretend we timed out.
                // The error case doesn't get here.
                index == HISTORY_MAIN_INDEX -> listener.onCancelled(index)
                // Duplicate request; ignore.
                ei.evaluator?.let { it.initial && it.required } == true -> return
                else -> {
                    // (Re)start evaluation in requested mode, i.e. with longer timeout.
                    cancel(ei, true)
                    evaluateResult(index, listener, cmi, true)
                }
            }
            ei.resultString == ERRONEOUS_RESULT -> {
                // Just re-evaluate to generate a new notification.
                cancel(ei, true)
                evaluateResult(index, listener, cmi, true)
            }
            else -> notifyImmediately(index, ei, listener, cmi)
        }
    }

    /** Whether this expression has explicitly been evaluated (User pressed "=") */
    fun hasResult(index: Long) = ensureExprIsCached(index).resultString != null

    /** Is a reevaluation still in progress? */
    fun evaluationInProgress(index: Long) = exprs[index]?.evaluator != null

    /**
     * Cancel any current background evaluation associated with the given ExprInfo.
     * @param quiet suppress cancellation message
     * @return true if we cancelled an initial evaluation
     */
    private fun cancel(expr: ExprInfo, quiet: Boolean): Boolean {
        val evaluation = expr.evaluator ?: return false
        if (quiet) evaluation.quiet = true
        evaluation.job.cancel()
        expr.evaluator = null
        if (expr.value.get() != null) {
            // Reevaluation in progress. Background computation touches only constructive
            // reals. OK not to wait.
            expr.resultStringOffsetReq = expr.resultStringOffset
            return false
        }
        if (expr === mainExpr) {
            // The expression is modifiable, and the background thread is reading it.
            // There seems to be no good way to wait for cancellation.
            // Give ourselves a new copy to work on instead.
            mainExpr.expr = mainExpr.expr.copy()
            // Approximation of constructive reals should be thread-safe,
            // so we can let that continue until it notices the cancellation.
            changedValue = true // Didn't do the expected evaluation.
        }
        return true
    }

    /**
     * Cancel any current background evaluation associated with the given expression.
     * @param quiet suppress cancellation message
     * @return true if we cancelled an initial evaluation
     */
    fun cancel(index: Long, quiet: Boolean) = exprs[index]?.let { cancel(it, quiet) } ?: false

    // TODO: May want to keep active evaluations in a HashSet to avoid traversing
    // all expressions we've looked at.
    fun cancelAll(quiet: Boolean) = exprs.values.forEach { cancel(it, quiet) }

    /**
     * Quietly cancel all evaluations associated with expressions other than the main one.
     * These are currently the evaluations associated with the history fragment.
     */
    fun cancelNonMain() = exprs.values.filter { it !== mainExpr }.forEach { cancel(it, true) }

    /**
     * Restore the evaluator state, including the current expression.
     */
    fun restoreInstanceState(input: DataInput) {
        changedValue = true
        try {
            mainExpr.degreeMode = input.readBoolean()
            mainExpr.longTimeout = input.readBoolean()
            mainExpr.expr = CalculatorExpr(input)
        } catch (e: IOException) {
            Log.v("Calculator", "Exception while restoring:\n$e")
        }
        _degreeMode.value = mainExpr.degreeMode
    }

    /**
     * Save the evaluator state, including the expression and any saved value.
     */
    fun saveInstanceState(out: DataOutput) {
        try {
            out.writeBoolean(mainExpr.degreeMode)
            out.writeBoolean(mainExpr.longTimeout)
            mainExpr.expr.write(out)
        } catch (e: IOException) {
            Log.v("Calculator", "Exception while saving state:\n$e")
        }
    }

    /**
     * Insert a button press into the main expression at [at]; see CalculatorExpr.insert().
     * @param id Button identifier for the character or operator to be added.
     * @return the position after what was inserted, or null if we rejected the insertion due to
     * obvious syntax issues, and the expression is unchanged
     */
    fun insert(id: Int, at: CalculatorExpr.Position): CalculatorExpr.Position? {
        if (id == R.id.fun_10pow) return insert10pow(at) // Handled as macro expansion.
        // A binary operator at the very end does not count towards the value; anywhere else
        // it, or the operator it replaces, does.
        changedValue = changedValue || !KeyMaps.isBinary(id) || !mainExpr.expr.isAtEnd(at)
        return mainExpr.expr.insert(id, at)
    }

    /**
     * Remove the op_add and op_sub operators just before [at] in the main expression; see
     * CalculatorExpr.removeAdditiveOperatorsBefore(). Trailing ones do not count towards the
     * value, any others do.
     */
    fun removeAdditiveOperatorsBefore(at: CalculatorExpr.Position): CalculatorExpr.Position {
        val position = mainExpr.expr.removeAdditiveOperatorsBefore(at)
        if (!mainExpr.expr.isAtEnd(position)) changedValue = true
        return position
    }

    /** Delete the character or token before [at] in the main expression; see CalculatorExpr.deleteBefore(). */
    fun deleteBefore(at: CalculatorExpr.Position): CalculatorExpr.Position {
        changedValue = true
        val position = mainExpr.expr.deleteBefore(at)
        if (mainExpr.expr.isEmpty()) mainExpr.longTimeout = false
        return position
    }

    /** Delete everything between the two positions of the main expression. */
    fun deleteRange(from: CalculatorExpr.Position, to: CalculatorExpr.Position): CalculatorExpr.Position {
        changedValue = true
        val position = mainExpr.expr.deleteRange(from, to)
        if (mainExpr.expr.isEmpty()) mainExpr.longTimeout = false
        return position
    }

    /**
     * Set degree mode for main expression.
     */
    fun setDegreeMode(degreeMode: Boolean) {
        changedValue = true
        mainExpr.degreeMode = degreeMode
        _degreeMode.value = degreeMode
        preferences.setDegreeMode(degreeMode)
    }

    /**
     * Return an ExprInfo for a copy of the expression with the given index.
     * We remove trailing binary operators in the copy.
     * timeStamp is not copied.
     */
    private fun copy(index: Long, copyValue: Boolean): ExprInfo {
        val fromEi = exprInfo(index)
        return ExprInfo(fromEi.expr.copy(), fromEi.degreeMode).apply {
            while (expr.hasTrailingBinary()) expr.delete()
            if (copyValue) {
                value = AtomicReference(fromEi.value.get())
                resultString = fromEi.resultString
                resultStringOffset = fromEi.resultStringOffset
                resultStringOffsetReq = fromEi.resultStringOffset
                msdIndex = fromEi.msdIndex
            }
            longTimeout = fromEi.longTimeout
        }
    }

    /**
     * Return an ExprInfo corresponding to the sum of the expressions at the
     * two indices.
     * index1 should correspond to an immutable expression, and should thus NOT
     * be MAIN_INDEX. Index2 may be MAIN_INDEX. Both expressions are presumed
     * to have been evaluated.  The result is unevaluated.
     * Can return null if evaluation resulted in an error (a very unlikely case).
     */
    private fun sum(index1: Long, index2: Long) = generalizedSum(index1, index2, R.id.op_add)

    /**
     * Return an ExprInfo corresponding to the subtraction of the value at the subtrahend index
     * from value at the minuend index (minuend - subtrahend = result). Both are presumed to have
     * been previously evaluated. The result is unevaluated. Can return null.
     */
    private fun difference(minuendIndex: Long, subtrahendIndex: Long) =
        generalizedSum(minuendIndex, subtrahendIndex, R.id.op_sub)

    private fun generalizedSum(index1: Long, index2: Long, op: Int): ExprInfo? {
        // TODO: Consider not collapsing expr2, to save database space.
        // Note that this is a bit tricky, since our expressions can contain unbalanced lparens.
        val collapsed1 = getCollapsedExpr(index1) ?: return null
        val collapsed2 = getCollapsedExpr(index2) ?: return null
        val result = CalculatorExpr().apply {
            append(collapsed1)
            add(op)
            append(collapsed2)
        }
        return ExprInfo(result, false /* don't care about degrees/radians */).apply {
            longTimeout = exprInfo(index1).longTimeout || exprInfo(index2).longTimeout
        }
    }

    /**
     * Add the expression described by the argument to the database.
     * Returns the new row id in the database.
     * Fills in timestamp in ei, if it was not previously set.
     * If inHistory is true, add it with a positive index, so it will appear in the history.
     */
    private fun addToDB(inHistory: Boolean, ei: ExprInfo): Long {
        val rd = ExpressionDB.RowData(ei.expr.toBytes(), ei.degreeMode, ei.longTimeout, 0)
        val resultIndex = exprDB.addRow(!inHistory, rd)
        if (exprs[resultIndex] != null) {
            throw AssertionError("result slot already occupied! + Slot = $resultIndex")
        }
        if (resultIndex == MAIN_INDEX) throw AssertionError("Should not store main expression")
        // Add newly assigned date to the cache.
        ei.timeStamp = rd.timeStamp
        exprs[resultIndex] = ei
        return resultIndex
    }

    /**
     * Preserve a copy of the expression at oldIndex at a new index.
     * This is useful only of oldIndex is MAIN_INDEX or HISTORY_MAIN_INDEX.
     * This assumes that initial evaluation completed successfully.
     * @param inHistory use a positive index so the result appears in the history.
     * @return the new index
     */
    fun preserve(oldIndex: Long, inHistory: Boolean): Long {
        val ei = copy(oldIndex, true)
        if (ei.validResultString == null) throw AssertionError("Preserving unevaluated expression")
        return addToDB(inHistory, ei)
    }

    /**
     * Preserve a copy of the current main expression as the most recent history entry,
     * assuming it is already in the database, but may have been lost from the cache.
     * This requires database access only if the local state was preserved, but we
     * recreated the Evaluator.  That excludes the common cases of device rotation, etc.
     * TODO: Revisit once we deal with database failures. We could just copy from
     * MAIN_INDEX instead, but that loses the timestamp.
     */
    fun represerve() {
        ensureExprIsCached(getMaxIndex())
    }

    /**
     * Discard previous expression in HISTORY_MAIN_INDEX and replace it by a fresh copy
     * of the main expression. Note that the HISTORY_MAIN_INDEX expression is not preserved
     * in the database or anywhere else; it is always reconstructed when needed.
     */
    fun copyMainToHistory() {
        cancel(HISTORY_MAIN_INDEX, true /* quiet */)
        exprs[HISTORY_MAIN_INDEX] = copy(MAIN_INDEX, true)
    }

    /**
     * @return the [CalculatorExpr] representation of the result of the given
     * expression.
     * The resulting expression contains a single "token" with the pre-evaluated result.
     * The client should ensure that this is never invoked unless initial evaluation of the
     * expression has been completed.
     */
    private fun getCollapsedExpr(index: Long): CalculatorExpr? {
        val realIndex = if (isMutableIndex(index)) preserve(index, false) else index
        val ei = exprInfo(realIndex)
        // An error can occur here only under extremely unlikely conditions.
        // Check anyway, and just refuse.
        // rs *should* never be null, but it happens. Check as a workaround to protect against
        // crashes until we find the root cause (b/34801142)
        val rs = ei.validResultString ?: return null
        val leastDigOffset = getLsdOffset(checkNotNull(ei.value.get()), rs, rs.indexOf('.'))
        return ei.expr.abbreviate(realIndex, getShortString(rs, getMsdIndexOf(rs), leastDigOffset))
    }

    /**
     * Abbreviate the indicated expression to a pre-evaluated expression node,
     * and use that as the new main expression.
     * This should not be called unless the expression was previously evaluated and produced a
     * non-error result.  Pre-evaluated expressions can never represent an expression for which
     * evaluation to a constructive real diverges.  Subsequent re-evaluation will also not
     * diverge, though it may generate errors of various kinds.  E.g.  sqrt(-10^-1000) .
     */
    fun collapse(index: Long) {
        val longTimeout = exprInfo(index).longTimeout
        val abbrvExpr = getCollapsedExpr(index)
        clearMain()
        abbrvExpr?.let(mainExpr.expr::append)
        mainExpr.longTimeout = longTimeout
        changedValue = true
    }

    /**
     * Mark the expression as changed, preventing next evaluation request from being ignored.
     */
    fun touch() {
        changedValue = true
    }

    /**
     * A listener that runs the given action once evaluation of an expression completes.
     */
    private class SetWhenDoneListener(private val setNow: () -> Unit) : EvaluationListener {
        override fun onCancelled(index: Long) {} // Extremely unlikely; leave unset.

        override fun onError(index: Long, errorId: Int) {} // Extremely unlikely; leave unset.

        override fun onEvaluate(index: Long, initPrecOffset: Int, msdIndex: Int, lsdOffset: Int, truncatedWholePart: String) =
            setNow()

        override fun onReevaluate(index: Long) = throw AssertionError("unexpected callback")
    }

    /**
     * Set the local and persistent memory index.
     */
    private fun setMemoryIndex(index: Long) {
        _memoryIndex.value = index
        preferences.setMemoryIndex(index)
    }

    /**
     * Set the local and persistent saved index.
     */
    private fun setSavedIndex(index: Long) {
        savedIndex = index
        preferences.setSavedIndex(index)
    }

    /**
     * Set memoryIndex (possibly including the persistent version) to index when we finish
     * evaluating the corresponding expression.
     */
    private fun setMemoryIndexWhenEvaluated(index: Long, persist: Boolean) {
        val listener = SetWhenDoneListener {
            if (memoryIndex != 0L) throw AssertionError("Overwriting nonzero memory index")
            if (persist) setMemoryIndex(index) else _memoryIndex.value = index
        }
        requireResult(index, listener, DummyCharMetricsInfo)
    }

    /**
     * Set savedIndex (not the persistent version) to index when we finish evaluating
     * the corresponding expression.
     */
    private fun setSavedIndexWhenEvaluated(index: Long) =
        requireResult(index, SetWhenDoneListener { savedIndex = index }, DummyCharMetricsInfo)

    /**
     * Save an immutable version of the expression at the given index as the saved value.
     * expr is left alone.  Return false if result is unavailable.
     */
    private fun copyToSaved(index: Long): Boolean {
        if (exprInfo(index).validResultString == null) return false
        setSavedIndex(if (isMutableIndex(index)) preserve(index, false) else index)
        return true
    }

    /**
     * Save an immutable version of the expression at the given index as the "memory" value.
     * The expression at index is presumed to have been evaluated.
     */
    fun copyToMemory(index: Long) =
        setMemoryIndex(if (isMutableIndex(index)) preserve(index, false) else index)

    /**
     * Save an an expression representing the sum of "memory" and the expression with the
     * given index. Make memoryIndex point to it when we complete evaluating.
     */
    fun addToMemory(index: Long) = sum(memoryIndex, index)?.let(::storeAsMemory)

    /**
     * Save an an expression representing the subtraction of the expression with the given index
     * from "memory." Make memoryIndex point to it when we complete evaluating.
     */
    fun subtractFromMemory(index: Long) = difference(memoryIndex, index)?.let(::storeAsMemory)

    /** Add the (unevaluated) expression to the database and make it the memory once evaluated. */
    private fun storeAsMemory(ei: ExprInfo) {
        val newIndex = addToDB(false, ei)
        _memoryIndex.value = 0 // Invalidate while we're evaluating.
        setMemoryIndexWhenEvaluated(newIndex, true /* persist */)
    }

    private fun uriForSaved(): Uri = Uri.Builder().scheme("tag").encodedOpaquePart(savedName).build()

    /**
     * Save the index expression as the saved location and return a URI describing it.
     * The URI is used to distinguish this particular result from others we may generate.
     */
    fun capture(index: Long): Uri? {
        if (!copyToSaved(index)) return null
        // Generate a new (entirely private) URI for this result.
        // Attempt to conform to RFC4151, though it's unclear it matters.
        val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date())
        savedName = "calculator2.android.com,$isoDate:${Random().nextInt() and 0x3fffffff}"
        preferences.setSavedName(savedName)
        return uriForSaved()
    }

    fun isLastSaved(uri: Uri) = savedIndex != 0L && uri == uriForSaved()

    /**
     * Append the expression at index as a pre-evaluated expression to the main expression.
     */
    fun insertExpr(index: Long, at: CalculatorExpr.Position): CalculatorExpr.Position {
        changedValue = true
        mainExpr.longTimeout = mainExpr.longTimeout || exprInfo(index).longTimeout
        return getCollapsedExpr(index)?.let { mainExpr.expr.insertExpr(it, at) } ?: at
    }

    /**
     * Insert the power of 10 operator into the main expression.
     * This is treated essentially as a macro expansion.
     */
    private fun insert10pow(at: CalculatorExpr.Position): CalculatorExpr.Position {
        changedValue = true // For consistency.  Reevaluation is probably not useful.
        val ten = CalculatorExpr().apply { add(R.id.digit_1); add(R.id.digit_0) }
        val afterTen = mainExpr.expr.insertExpr(ten, at)
        return mainExpr.expr.insert(R.id.op_pow, afterTen) ?: afterTen
    }

    /**
     * Ensure that the expression with the given index is in exprs.
     * We assume that if it's either already in exprs or exprDB.
     * When we're done, the expression in exprs may still contain references to other
     * subexpressions that are not yet cached.
     */
    private fun ensureExprIsCached(index: Long): ExprInfo = exprs[index] ?: exprs.getOrPut(index) {
        if (index == MAIN_INDEX) throw AssertionError("Main expression should be cached")
        val row = exprDB.getRow(index)
        val expr = try {
            CalculatorExpr(DataInputStream(ByteArrayInputStream(row.expression)))
        } catch (e: IOException) {
            throw AssertionError("IO Exception without real IO:$e")
        }
        ExprInfo(expr, row.degreeMode).apply {
            timeStamp = row.timeStamp
            longTimeout = row.longTimeout
        }
    }

    override fun getExpr(index: Long) = ensureExprIsCached(index).expr

    /**
     * Return timestamp associated with the expression in milliseconds since epoch.
     * Yields zero if the expression has not been written to or read from the database.
     */
    fun getTimeStamp(index: Long) = ensureExprIsCached(index).timeStamp

    override fun getDegreeMode(index: Long) = ensureExprIsCached(index).degreeMode

    override fun getResult(index: Long): UnifiedReal? = ensureExprIsCached(index).value.get()

    override fun putResultIfAbsent(index: Long, result: UnifiedReal): UnifiedReal {
        val value = exprInfo(index).value
        // Cannot change once non-null.
        return if (value.compareAndSet(null, result)) result else checkNotNull(value.get())
    }

    /**
     * Add the exponent represented by s[begin..end) to the constant at the end of current
     * expression.
     * The end of the current expression must be a constant.  Exponents have the same syntax as
     * for exponentEnd().
     */
    fun addExponent(s: String, begin: Int, end: Int, at: CalculatorExpr.Position): CalculatorExpr.Position {
        // We do the decimal conversion ourselves to exactly match exponentEnd() conventions
        // and handle various kinds of digits on input.  Also avoids allocation.
        val negative = KeyMaps.keyForChar(s[begin + 1]) == R.id.op_sub
        val digitsStart = begin + if (negative) 2 else 1
        val exp = (digitsStart until end).fold(0) { acc, i -> 10 * acc + Character.digit(s[i], 10) }
        changedValue = true
        return mainExpr.expr.addExponentBefore(at, if (negative) -exp else exp)
    }

    /**
     * Generate a String representation of the expression at the given index.
     * This has the side effect of adding the expression to exprs.
     * The expression must exist in the database.
     */
    fun getExprAsString(index: Long) = getExprAsSpannable(index).toString()

    fun getExprAsSpannable(index: Long): Spannable = getExpr(index).toSpannableStringBuilder(context)

    /**
     * Generate a String representation of all expressions in the database.
     * Debugging only.
     */
    fun historyAsString(): String = buildString {
        for (i in getMinIndex() until ExpressionDB.MAXIMUM_MIN_INDEX) {
            append(i).append(": ").append(getExprAsString(i)).append("\n")
        }
        for (i in 1L until getMaxIndex()) {
            append(i).append(": ").append(getExprAsString(i)).append("\n")
        }
        append("Memory index = ").append(memoryIndex)
        append(" Saved index = ").append(savedIndex).append("\n")
    }

    /**
     * Wait for pending writes to the database to complete.
     */
    fun waitForWrites() = exprDB.waitForWrites()

    /**
     * Destroy the current evaluator, forcing getInstance to allocate a new one.
     * This is needed for testing, since Robolectric apparently doesn't let us preserve
     * an open database across tests. Cf. https://github.com/robolectric/robolectric/issues/1890 .
     */
    fun destroyEvaluator() {
        scope.cancel()
        exprDB.close()
        evaluator = null
    }

    companion object {
        private var evaluator: Evaluator? = null

        const val TIMEOUT_DIALOG_TAG = "timeout"

        fun getInstance(context: Context): Evaluator =
            evaluator ?: Evaluator(context.applicationContext).also { evaluator = it }

        const val MAIN_INDEX = 0L // Index of main expression.

        // Once final evaluation of an expression is complete, or when we need to save
        // a partial result, we copy the main expression to a non-zero index.
        // At that point, the expression no longer changes, and is preserved
        // until the entire history is cleared. Only expressions at nonzero indices
        // may be embedded in other expressions.
        // Each expression index can only have one outstanding evaluation request at a time.
        // To avoid conflicts between the history and main View, we copy the main expression
        // to allow independent evaluation by both.
        const val HISTORY_MAIN_INDEX = -1L // Read-only copy of main expression.

        // When naming variables and fields, "Offset" denotes a character offset in a string
        // representing a decimal number, where the offset is relative to the decimal point.  1 =
        // tenths position, -1 = units position.  Integer.MAX_VALUE is sometimes used for the
        // offset of the last digit in an a nonterminating decimal expansion.  We use the suffix
        // "Index" to denote a zero-based absolute index into such a string. (In other contexts,
        // like above, we also use "index" to refer to the key in exprs, the list of all known
        // expressions.)

        // The minimum number of extra digits we always try to compute to improve the chance of
        // producing a correctly-rounded-towards-zero result.  The extra digits can be displayed to
        // avoid generating placeholder digits, but should only be displayed briefly while
        // computing.
        private const val EXTRA_DIGITS = 20

        // We adjust EXTRA_DIGITS by adding the length of the previous result divided by
        // EXTRA_DIVISOR.  This helps hide recompute latency when long results are requested;
        // We start the recomputation substantially before the need is likely to be visible.
        private const val EXTRA_DIVISOR = 5

        // In addition to insisting on extra digits (see above), we minimize reevaluation
        // frequency by precomputing an extra PRECOMPUTE_DIGITS
        // + <current_precision_offset>/PRECOMPUTE_DIVISOR digits, whenever we are forced to
        // reevaluate.  The last term is dropped if prec < 0.
        private const val PRECOMPUTE_DIGITS = 30
        private const val PRECOMPUTE_DIVISOR = 5

        // Initial evaluation precision.  Enough to guarantee that we can compute the short
        // representation, and that we rarely have to evaluate nonzero results to
        // MAX_MSD_PREC_OFFSET. It also helps if this is at least EXTRA_DIGITS + display width,
        // so that we don't immediately need a second evaluation.
        private const val INIT_PREC = 50

        // The largest number of digits to the right of the decimal point to which we will
        // evaluate to compute proper scientific notation for values close to zero.  Chosen to
        // ensure that we always to better than IEEE double precision at identifying nonzeros.
        // And then some. This is used only when we cannot a priori determine the most
        // significant digit position, as we always can if we have a rational representation.
        private const val MAX_MSD_PREC_OFFSET = 1100

        // If we can replace an exponent by this many leading zeroes, we do so.  Also used in
        // estimating exponent size for truncating short representation.
        private const val EXP_COST = 3

        const val INVALID_MSD = Int.MAX_VALUE

        // Used to represent an erroneous result or a required evaluation. Not displayed.
        private const val ERRONEOUS_RESULT = "ERR"

        /**
         * Timeout for unrequested, speculative evaluations, in milliseconds.
         */
        private const val QUICK_TIMEOUT = 1000L

        /**
         * Timeout for non-MAIN expressions. Note that there may be many such evaluations in
         * progress at once. Thus the evaluation latency may include that needed to complete
         * other evaluations. Thus the longTimeout flag is not very meaningful, and currently
         * ignored.
         * Since this is only used for expressions that we have previously successfully
         * evaluated, these timeouts should never trigger.
         */
        private const val NON_MAIN_TIMEOUT = 100000L

        /**
         * Maximum result bit length for unrequested, speculative evaluations.
         * Also used to bound evaluation precision for small non-zero fractions.
         */
        private const val QUICK_MAX_RESULT_BITS = 150000

        private const val SHORT_TARGET_LENGTH = 8
        private const val SHORT_UNCERTAIN_ZERO = "0.00000" + KeyMaps.ELLIPSIS

        // Refuse to scroll past the point at which this many digits from the whole number
        // part of the result are still displayed.  Avoids silly displays like 1E1.
        private const val MIN_DISPLAYED_DIGS = 5

        /**
         * Maximum number of characters in a scientific notation exponent.
         */
        private const val MAX_EXP_CHARS = 8

        /**
         * Check whether a new higher precision result flips previously computed trailing 9s
         * to zeroes.  If so, flip them back.  Return the adjusted result.
         * Assumes newPrecOffset >= oldPrecOffset > 0.
         * Since our results are accurate to < 1 ulp, this can only happen if the true result
         * is less than the new result with trailing zeroes, and thus appending 9s to the
         * old result must also be correct.  Such flips are impossible if the newly computed
         * digits consist of anything other than zeroes.
         * It is unclear that there are real cases in which this is necessary,
         * but we have failed to prove there aren't such cases.
         */
        @VisibleForTesting
        fun unflipZeroes(oldDigs: String, oldPrecOffset: Int, newDigs: String, newPrecOffset: Int): String {
            if (oldDigs.last() != '9') return newDigs
            val precDiff = newPrecOffset - oldPrecOffset
            val oldLastInNew = newDigs.length - 1 - precDiff
            if (newDigs[oldLastInNew] != '0') return newDigs
            // Earlier digits could not have changed without a 0 to 9 or 9 to 0 flip at end.
            // The former is OK.
            if (newDigs.takeLast(precDiff) != "0".repeat(precDiff)) {
                throw AssertionError("New approximation invalidates old one!")
            }
            return oldDigs + "9".repeat(precDiff)
        }

        /**
         * Return the rightmost nonzero digit position, if any.
         * @param value UnifiedReal value of result.
         * @param cache Current cached decimal string representation of result.
         * @param decIndex Index of decimal point in cache.
         * @return Position of rightmost nonzero digit relative to decimal point.
         *         Integer.MIN_VALUE if we cannot determine.  Integer.MAX_VALUE if there is no lsd,
         *         or we cannot determine it.
         */
        internal fun getLsdOffset(value: UnifiedReal, cache: String, decIndex: Int): Int {
            if (value.definitelyZero) return Int.MIN_VALUE
            val result = value.digitsRequired()
            if (result != 0) return result
            // An integer: skip back over trailing zeroes of the whole part.
            var i = -1
            while (decIndex + i > 0 && cache[decIndex + i] == '0') --i
            return i
        }

        // TODO: We may want to consistently specify the position of the current result
        // window using the left-most visible digit index instead of the offset for the rightmost
        // one. It seems likely that would simplify the logic.

        /**
         * Retrieve the preferred precision "offset" for the currently displayed result.
         * May be called from non-UI thread.
         * @param cache Current approximation as string.
         * @param msdIn Position of most significant digit in result.  Index in cache.
         *            Can be INVALID_MSD if we haven't found it yet.
         * @param lastDigitOffsetIn Position of least significant digit (1 = tenths digit)
         *                  or Integer.MAX_VALUE.
         */
        @VisibleForTesting
        internal fun getPreferredPrec(cache: String, msdIn: Int, lastDigitOffsetIn: Int, cm: CharMetricsInfo): Int {
            val lineLength = cm.getMaxChars()
            val wholeSize = cache.indexOf('.')
            val rawSepChars = cm.separatorChars(cache, wholeSize)
            val rawSepCharsNoDecimal = rawSepChars - cm.getNoEllipsisCredit()
            val rawSepCharsWithDecimal = rawSepCharsNoDecimal - cm.getDecimalCredit()
            val sepCharsNoDecimal = ceil(max(rawSepCharsNoDecimal, 0.0f)).toInt()
            val sepCharsWithDecimal = ceil(max(rawSepCharsWithDecimal, 0.0f)).toInt()
            val negative = if (cache[0] == '-') 1 else 0
            // Don't display decimal point if result is an integer.
            val lastDigitOffset = if (lastDigitOffsetIn == 0) -1 else lastDigitOffsetIn
            if (lastDigitOffset != Int.MAX_VALUE) {
                // Exact integer.  Prefer to display as integer, without decimal point.
                if (wholeSize <= lineLength - sepCharsNoDecimal && lastDigitOffset <= 0) return -1
                // Display full exact number without scientific notation.
                if (lastDigitOffset >= 0 &&
                    wholeSize + lastDigitOffset + 1 /* decimal pt. */ <= lineLength - sepCharsWithDecimal
                ) return lastDigitOffset
            }
            // Display number without scientific notation.  Treat leading zero as msd.
            val msd = if (msdIn > wholeSize && msdIn <= wholeSize + EXP_COST + 1) wholeSize - 1 else msdIn
            if (msd > QUICK_MAX_RESULT_BITS) {
                // Display a probable but uncertain 0 as "0.000000000", without exponent.  That's
                // a judgment call, but less likely to confuse naive users.  A more informative
                // and confusing option would be to use a large negative exponent.
                // Treat extremely large msd values as unknown to avoid slow computations.
                return lineLength - 2
            }
            // Return position corresponding to having msd at left, effectively presuming
            // scientific notation that preserves the left part of the result.
            // After adjustment for the space required by an exponent, evaluating to the
            // resulting precision should not overflow the display.
            var result = msd - wholeSize + lineLength - negative - 1
            if (wholeSize <= lineLength - sepCharsNoDecimal) {
                // Fits without scientific notation; will need space for separators.
                result -= if (wholeSize < lineLength - sepCharsWithDecimal) sepCharsWithDecimal else sepCharsNoDecimal
            }
            return result
        }

        /**
         * Get a short representation of the value represented by the string cache.
         * We try to match the CalculatorResult code when the result is finite
         * and small enough to suit our needs.
         * The result is not internationalized.
         * @param cache String approximation of value.  Assumed to be long enough
         *              that if it doesn't contain enough significant digits, we can
         *              reasonably abbreviate as SHORT_UNCERTAIN_ZERO.
         * @param msdIndexIn Index of most significant digit in cache, or INVALID_MSD.
         * @param lsdOffsetIn Position of least significant digit in finite representation,
         *            relative to decimal point, or MAX_VALUE.
         */
        @VisibleForTesting
        internal fun getShortString(cache: String, msdIndexIn: Int, lsdOffsetIn: Int): String {
            // This somewhat mirrors the display formatting code, but
            // - The constants are different, since we don't want to use the whole display.
            // - This is an easier problem, since we don't support scrolling and the length
            //   is a bit flexible.
            // TODO: Think about refactoring this to remove partial redundancy with
            // CalculatorResult.
            var msdIndex = msdIndexIn
            var lsdOffset = lsdOffsetIn
            val dotIndex = cache.indexOf('.')
            val negative = if (cache[0] == '-') 1 else 0
            val negativeSign = if (negative == 1) "-" else ""

            // Ensure we don't have to worry about running off the end of cache.
            if (msdIndex >= cache.length - SHORT_TARGET_LENGTH) msdIndex = INVALID_MSD
            if (msdIndex == INVALID_MSD) return if (lsdOffset < INIT_PREC) "0" else SHORT_UNCERTAIN_ZERO
            // Avoid scientific notation for small numbers of zeros.
            // Instead stretch significant digits to include decimal point.
            if (lsdOffset < -1 && dotIndex - msdIndex + negative <= SHORT_TARGET_LENGTH &&
                lsdOffset >= -CalculatorResult.MAX_TRAILING_ZEROES - 1
            ) {
                // Whole number that fits in allotted space.
                // CalculatorResult would not use scientific notation either.
                lsdOffset = -1
            }
            if (msdIndex > dotIndex) {
                if (msdIndex <= dotIndex + EXP_COST + 1) {
                    // Preferred display format in this case is with leading zeroes, even if
                    // it doesn't fit entirely.  Replicate that here.
                    msdIndex = dotIndex - 1
                } else if (lsdOffset <= SHORT_TARGET_LENGTH - negative - 2 &&
                    lsdOffset <= CalculatorResult.MAX_LEADING_ZEROES + 1
                ) {
                    // Fraction that fits entirely in allotted space.
                    // CalculatorResult would not use scientific notation either.
                    msdIndex = dotIndex - 1
                }
            }
            // Adjust for the fact that the decimal point itself takes space.
            val exponent = (dotIndex - msdIndex).let { if (it > 0) it - 1 else it }
            if (lsdOffset != Int.MAX_VALUE) {
                val lsdIndex = dotIndex + lsdOffset
                val totalDigits = lsdIndex - msdIndex + negative + 1
                if (totalDigits <= SHORT_TARGET_LENGTH && dotIndex > msdIndex && lsdOffset >= -1) {
                    // Fits, no exponent needed.
                    return negativeSign + cache.addCommas(msdIndex, dotIndex) + cache.substring(dotIndex, lsdIndex + 1)
                }
                if (totalDigits <= SHORT_TARGET_LENGTH - 3) {
                    return negativeSign + cache[msdIndex] + "." + cache.substring(msdIndex + 1, lsdIndex + 1) + "E" + exponent
                }
            }
            // We need to abbreviate.
            if (dotIndex > msdIndex && dotIndex < msdIndex + SHORT_TARGET_LENGTH - negative - 1) {
                return negativeSign + cache.addCommas(msdIndex, dotIndex) +
                    cache.substring(dotIndex, msdIndex + SHORT_TARGET_LENGTH - negative - 1) + KeyMaps.ELLIPSIS
            }
            // Need abbreviation + exponent
            return negativeSign + cache[msdIndex] + "." +
                cache.substring(msdIndex + 1, msdIndex + SHORT_TARGET_LENGTH - negative - 4) +
                KeyMaps.ELLIPSIS + "E" + exponent
        }

        /**
         * Return the most significant digit index in the given numeric string.
         * Return INVALID_MSD if there are not enough digits to prove the numeric value is
         * different from zero.  As usual, we assume an error of strictly less than 1 ulp.
         */
        fun getMsdIndexOf(s: String): Int =
            s.indexOfFirst { it != '-' && it != '.' && it != '0' }
                .takeIf { it >= 0 && (it < s.length - 1 || s[it] != '1') } ?: INVALID_MSD

        /**
         * Return the index of the character after the exponent starting at s[offset].
         * Return offset if there is no exponent at that position.
         * Exponents have syntax E[-]digit* .  "E2" and "E-2" are valid.  "E+2" and "e2" are not.
         * We allow any Unicode digits, and either of the commonly used minus characters.
         */
        fun exponentEnd(s: String, offset: Int): Int {
            var i = offset
            if (i >= s.length - 1 || s[i] != 'E') return offset
            ++i
            if (KeyMaps.keyForChar(s[i]) == R.id.op_sub) ++i
            if (i == s.length || !s[i].isDigit()) return offset
            ++i
            while (i < s.length && s[i].isDigit()) {
                ++i
                if (i > offset + MAX_EXP_CHARS) return offset
            }
            return i
        }
    }
}
