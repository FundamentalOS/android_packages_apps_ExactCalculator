/*
 * SPDX-FileCopyrightText: 2015 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TtsSpan

import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger

import kotlin.math.abs

/**
 * A mathematical expression represented as a sequence of "tokens".
 * Many tokens are represented by button ids for the corresponding operator.
 * A token may also represent the result of a previously evaluated expression.
 * The add() method adds a token to the end of the expression.  The delete method() removes one.
 * Clear() deletes the entire expression contents. Eval() evaluates the expression,
 * producing a UnifiedReal result.
 * Expressions are parsed only during evaluation; no explicit parse tree is maintained.
 *
 * The write() method is used to save the current expression.  Note that neither UnifiedReal
 * nor the underlying CR provide a serialization facility.  Thus we save all previously
 * computed values by writing out the expression that was used to compute them, and reevaluate
 * when reading it back in.
 */
class CalculatorExpr() {
    /**
     * An interface for resolving expression indices in embedded subexpressions to
     * the associated CalculatorExpr, and associating a UnifiedReal result with it.
     * All methods are thread-safe in the strong sense; they may be called asynchronously
     * at any time from any thread.
     */
    interface ExprResolver {
        /** Retrieve the expression corresponding to index. */
        fun getExpr(index: Long): CalculatorExpr

        /** Retrieve the degree mode associated with the expression at index i. */
        fun getDegreeMode(index: Long): Boolean

        /** Retrieve the stored result for the expression at index, or return null. */
        fun getResult(index: Long): UnifiedReal?

        /**
         * Atomically test for an existing result, and set it if there was none.
         * Return the prior result if there was one, or the new one if there was not.
         * May only be called after getExpr.
         */
        fun putResultIfAbsent(index: Long, result: UnifiedReal): UnifiedReal
    }

    // The actual representation as a list of tokens.  Constant tokens are always nonempty.
    private val expr = ArrayList<Token>()

    /**
     * The ordinal of each kind is written to the database, so the order must never change.
     */
    private enum class TokenKind { CONSTANT, OPERATOR, PRE_EVAL }

    private sealed class Token {
        /**
         * Write token as either a very small Byte containing the TokenKind,
         * followed by data needed by subclass constructor,
         * or as a byte >= 0x20 directly describing the OPERATOR token.
         */
        @Throws(IOException::class)
        abstract fun write(out: DataOutput)

        /**
         * Return a textual representation of the token.
         * The result is suitable for either display as part of the formula or TalkBack use.
         * It may be a SpannableString that includes added TalkBack information.
         * @param context context used for converting button ids to strings
         */
        abstract fun toCharSequence(context: Context): CharSequence
    }

    /**
     * Representation of an operator token, identified by its button resource id.
     */
    private class Operator(val id: Int) : Token() {
        constructor(op: Byte) : this(KeyMaps.fromByte(op))

        override fun write(out: DataOutput) = out.writeByte(KeyMaps.toByte(id).toInt())

        override fun toCharSequence(context: Context): CharSequence {
            val desc = KeyMaps.toDescriptiveString(context, id)
                ?: return KeyMaps.toString(context, id)
            return SpannableString(KeyMaps.toString(context, id)).apply {
                setSpan(TtsSpan.TextBuilder(desc).build(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /**
     * Representation of a (possibly incomplete) numerical constant.
     * Supports addition and removal of trailing characters; hence mutable.
     */
    private class Constant(
        private var whole: String = "", // String preceding decimal point.
        private var fraction: String = "", // String after decimal point.
        private var sawDecimal: Boolean = false,
        private var exponent: Int = 0 // Explicit exponent, only generated through addExponent.
    ) : Token() {

        @Throws(IOException::class)
        constructor(input: DataInput) : this(whole = input.readUTF()) {
            val flags = input.readByte().toInt()
            if (flags and SAW_DECIMAL != 0) {
                sawDecimal = true
                fraction = input.readUTF()
            }
            if (flags and HAS_EXPONENT != 0) {
                exponent = input.readInt()
            }
        }

        override fun write(out: DataOutput) {
            val flags = (if (sawDecimal) SAW_DECIMAL else 0) or (if (exponent != 0) HAS_EXPONENT else 0)
            out.writeByte(TokenKind.CONSTANT.ordinal)
            out.writeUTF(whole)
            out.writeByte(flags)
            if (sawDecimal) out.writeUTF(fraction)
            if (exponent != 0) out.writeInt(exponent)
        }

        /**
         * Given a button press, append corresponding digit.
         * We assume id is a digit or decimal point.
         * Just return false if this was the second (or later) decimal point in this constant,
         * or if the exponent would get too large.
         */
        fun add(id: Int): Boolean {
            if (id == R.id.dec_point) {
                if (sawDecimal || exponent != 0) return false
                sawDecimal = true
                return true
            }
            val value = KeyMaps.digVal(id)
            when {
                exponent == 0 -> if (sawDecimal) fraction += value else whole += value
                abs(exponent) > 10000 -> return false // Too large; refuse
                exponent > 0 -> exponent = 10 * exponent + value
                else -> exponent = 10 * exponent - value
            }
            return true
        }

        fun addExponent(exp: Int) {
            // Note that adding a 0 exponent is a no-op.  That's OK.
            exponent = exp
        }

        /**
         * Undo the last add or remove last exponent digit.
         * Assumes the constant is nonempty.
         */
        fun delete() = when {
            // Once zero, it can only be added back with addExponent.
            exponent != 0 -> exponent /= 10
            fraction.isNotEmpty() -> fraction = fraction.dropLast(1)
            sawDecimal -> sawDecimal = false
            else -> whole = whole.dropLast(1)
        }

        val isEmpty: Boolean get() = !sawDecimal && whole.isEmpty()

        /**
         * Produce human-readable string representation of constant, as typed.
         * We do add digit grouping separators to the whole number, even if not typed.
         * Result is internationalized.
         */
        override fun toString(): String = KeyMaps.translateResult(
            buildString {
                append(if (exponent != 0) whole else whole.addCommas(0, whole.length))
                if (sawDecimal) append('.').append(fraction)
                if (exponent != 0) append('E').append(exponent)
            }
        )

        /**
         * Return BoundedRational representation of constant, if well-formed.
         */
        @Throws(SyntaxException::class)
        fun toRational(): BoundedRational {
            val whole = whole.ifEmpty {
                // Decimal point without digits.
                if (fraction.isEmpty()) throw SyntaxException()
                "0"
            }
            var num = BigInteger(whole + fraction)
            var den = BigInteger.TEN.pow(fraction.length)
            if (exponent > 0) num *= BigInteger.TEN.pow(exponent)
            if (exponent < 0) den *= BigInteger.TEN.pow(-exponent)
            return BoundedRational(num, den)
        }

        override fun toCharSequence(context: Context): CharSequence = toString()

        fun copy() = Constant(whole, fraction, sawDecimal, exponent)

        companion object {
            private const val SAW_DECIMAL = 0x1
            private const val HAS_EXPONENT = 0x2
        }
    }

    /**
     * The "token" class for previously evaluated subexpressions.
     * We treat previously evaluated subexpressions as tokens.  These are inserted when we either
     * continue an expression after evaluating some of it, or copy an expression and paste it back
     * in.
     * This only contains enough information to allow us to display the expression in a
     * formula, or reevaluate the expression with the aid of an ExprResolver; we no longer
     * cache the result. The expression corresponding to the index can be obtained through
     * the ExprResolver, which looks it up in a subexpression database.
     * The short string representation is stored in order to avoid potentially expensive
     * recomputation in the UI thread.
     */
    private class PreEval(
        val index: Long,
        private val shortRep: String // Not internationalized.
    ) : Token() {

        @Throws(IOException::class)
        constructor(input: DataInput) : this(input.readInt().toLong(), input.readUTF())

        // This writes out only a shallow representation of the result, without
        // information about subexpressions. To write out a deep representation, we
        // find referenced subexpressions, and iteratively write those as well.
        override fun write(out: DataOutput) {
            out.writeByte(TokenKind.PRE_EVAL.ordinal)
            if (index > Int.MAX_VALUE || index < Int.MIN_VALUE) {
                // This would be millions of expressions per day for the life of the device.
                throw AssertionError("Expression index too big")
            }
            out.writeInt(index.toInt())
            out.writeUTF(shortRep)
        }

        override fun toCharSequence(context: Context): CharSequence =
            KeyMaps.translateResult(shortRep)

        val hasEllipsis: Boolean get() = KeyMaps.ELLIPSIS in shortRep
    }

    /**
     * Construct CalculatorExpr, by reading it from input.
     */
    @Throws(IOException::class)
    constructor(input: DataInput) : this() {
        repeat(input.readInt()) { expr.add(newToken(input)) }
    }

    /**
     * Write this expression to out.
     */
    @Throws(IOException::class)
    fun write(out: DataOutput) {
        out.writeInt(expr.size)
        expr.forEach { it.write(out) }
    }

    /**
     * Use write() above to generate a byte array containing a serialized representation of
     * this expression.
     */
    fun toBytes(): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).use(::write) }.toByteArray()

    private val lastToken: Token? get() = expr.lastOrNull()

    private val lastOperator: Operator? get() = lastToken as? Operator

    /**
     * Does this expression end with a numeric constant?
     * As opposed to an operator or preevaluated expression.
     */
    fun hasTrailingConstant(): Boolean = when (val t = lastToken) {
        is Constant -> true
        is Operator -> t.id == R.id.const_pi || t.id == R.id.const_e
        else -> false
    }

    /** Does this expression end with a binary operator? */
    fun hasTrailingBinary() = lastOperator?.let { KeyMaps.isBinary(it.id) } == true

    /** Does this expression end with a suffix operator? */
    fun hasTrailingSuffix() = lastOperator?.let { KeyMaps.isSuffix(it.id) } == true

    /** Does this expression contain an unmatched lparen? */
    fun hasOpenParentheses(): Boolean {
        val operators = expr.filterIsInstance<Operator>()
        val opens = operators.count { it.id == R.id.lparen || KeyMaps.isFunc(it.id) }
        val closes = operators.count { it.id == R.id.rparen }
        return opens > closes
    }

    /** Does this expression end with a left parenthesis? */
    fun hasTrailingLeftParen() =
        lastOperator?.let { it.id == R.id.lparen || KeyMaps.isFunc(it.id) } == true

    /** Does this expression end with a right parenthesis? */
    fun hasTrailingRightParen() = lastOperator?.id == R.id.rparen

    /**
     * Append press of button with given id to expression.
     * If the insertion would clearly result in a syntax error, either just return false
     * and do nothing, or make an adjustment to avoid the problem.  We do the latter only
     * for unambiguous consecutive binary operators, in which case we delete the first
     * operator.
     */
    fun add(id: Int): Boolean {
        // Quietly replace a trailing binary operator with another one, unless the second
        // operator is minus, in which case we just allow it as a unary minus.
        if (KeyMaps.isBinary(id) && !KeyMaps.isPrefix(id)) {
            val lastOp = lastOperator?.id ?: 0
            if (expr.isEmpty() || lastOp == R.id.lparen || KeyMaps.isFunc(lastOp) ||
                KeyMaps.isPrefix(lastOp) && lastOp != R.id.op_sub
            ) {
                return false
            }
            while (hasTrailingBinary()) delete()
        }
        if (KeyMaps.digVal(id) == KeyMaps.NOT_DIGIT && id != R.id.dec_point) {
            expr.add(Operator(id))
            return true
        }
        // Since we treat juxtaposition as multiplication, a constant can appear anywhere.
        val last = lastToken
        if (last !is Constant) {
            // Add explicit multiplication to avoid confusing display.
            if (last is PreEval) expr.add(Operator(R.id.op_mul))
            expr.add(Constant())
        }
        return (expr.last() as Constant).add(id)
    }

    /**
     * Add exponent to the constant at the end of the expression.
     * Assumes there is a constant at the end of the expression.
     */
    fun addExponent(exp: Int) = (expr.last() as Constant).addExponent(exp)

    /**
     * Remove trailing op_add and op_sub operators.
     */
    fun removeTrailingAdditiveOperators() {
        while (lastOperator?.id.let { it == R.id.op_add || it == R.id.op_sub }) delete()
    }

    /**
     * Append the contents of the argument expression.
     * It is assumed that the argument expression will not change, and thus its pieces can be
     * reused directly.
     */
    fun append(expr2: CalculatorExpr) {
        // Check that we're not concatenating Constant or PreEval tokens, since the result would
        // look like a single constant, with very mysterious results for the user.
        // Fudge it by adding an explicit multiplication.  We would have interpreted it as
        // such anyway, and this makes it recognizable to the user.
        if (lastToken.let { it != null && it !is Operator } &&
            expr2.expr.firstOrNull().let { it != null && it !is Operator }
        ) {
            expr.add(Operator(R.id.op_mul))
        }
        expr.addAll(expr2.expr)
    }

    /**
     * Undo the last key addition, if any.
     * Or possibly remove a trailing exponent digit.
     */
    fun delete() {
        val last = lastToken ?: return
        if (last is Constant) {
            last.delete()
            if (!last.isEmpty) return
        }
        expr.removeAt(expr.size - 1)
    }

    /** Remove all tokens from the expression. */
    fun clear() = expr.clear()

    fun isEmpty() = expr.isEmpty()

    /**
     * Returns a logical deep copy of the CalculatorExpr.
     * Operator and PreEval tokens are immutable, and thus aren't really copied.
     */
    fun copy() = CalculatorExpr().also { result ->
        expr.mapTo(result.expr) { if (it is Constant) it.copy() else it }
    }

    /** Am I just a constant? */
    fun isConstant() = expr.singleOrNull() is Constant

    /**
     * Return a new expression consisting of a single token representing the current pre-evaluated
     * expression.
     * The caller supplies the expression index and short string representation.
     * The expression must have been previously evaluated.
     */
    fun abbreviate(index: Long, sr: String) = CalculatorExpr().also { it.expr.add(PreEval(index, sr)) }

    /**
     * Internal evaluation functions return an EvalRet pair.
     * We compute rational (BoundedRational) results when possible, both as a performance
     * optimization, and to detect errors exactly when we can.
     */
    private class EvalRet(
        var pos: Int, // Next position (expression index) to be parsed.
        val value: UnifiedReal // Constructive Real result of evaluating subexpression.
    )

    /**
     * Internal evaluation functions take an EvalContext argument.
     * If we add any other kinds of evaluation modes, they go here.
     */
    private class EvalContext(
        val degreeMode: Boolean,
        val prefixLength: Int, // Length of prefix to evaluate. Not explicitly saved.
        val exprResolver: ExprResolver // Reconstructed, not saved.
    )

    private fun toRadians(x: UnifiedReal, ec: EvalContext) =
        if (ec.degreeMode) x * UnifiedReal.RADIANS_PER_DEGREE else x

    private fun fromRadians(x: UnifiedReal, ec: EvalContext) =
        if (ec.degreeMode) x / UnifiedReal.RADIANS_PER_DEGREE else x

    // The following methods can all throw IndexOutOfBoundsException in the event of a syntax
    // error.  We expect that to be caught in eval below.

    private fun isOperatorUnchecked(i: Int, op: Int) = (expr[i] as? Operator)?.id == op

    private fun isOperator(i: Int, op: Int, ec: EvalContext) =
        i < ec.prefixLength && isOperatorUnchecked(i, op)

    class SyntaxException : Exception {
        constructor() : super()
        constructor(s: String) : super(s)
    }

    // The following functions all evaluate some kind of expression starting at position i in
    // expr in a specified evaluation context.  They return both the expression value (as
    // constructive real and, if applicable, as BoundedRational) and the position of the next token
    // that was not used as part of the evaluation.
    // This is essentially a simple recursive descent parser combined with expression evaluation.

    /**
     * Evaluate the parenthesized argument of a function or lparen at position i, consuming
     * the matching rparen if present.
     */
    @Throws(SyntaxException::class)
    private fun evalArgument(i: Int, ec: EvalContext): EvalRet = evalExpr(i + 1, ec).apply {
        if (isOperator(pos, R.id.rparen, ec)) pos++
    }

    /** Evaluate a function whose argument starts at position i, applying [f] to it. */
    @Throws(SyntaxException::class)
    private inline fun evalFunction(i: Int, ec: EvalContext, f: (UnifiedReal) -> UnifiedReal) =
        evalArgument(i, ec).let { EvalRet(it.pos, f(it.value)) }

    @Throws(SyntaxException::class)
    private fun evalUnary(i: Int, ec: EvalContext): EvalRet {
        val t = expr[i]
        if (t is Constant) return EvalRet(i + 1, UnifiedReal(t.toRational()))
        if (t is PreEval) {
            // We try to minimize this recursive evaluation case, but currently don't
            // completely avoid it.
            val res = ec.exprResolver.getResult(t.index) ?: nestedEval(t.index, ec.exprResolver)
            return EvalRet(i + 1, res)
        }
        return when ((t as Operator).id) {
            R.id.const_pi -> EvalRet(i + 1, UnifiedReal.PI)
            R.id.const_e -> EvalRet(i + 1, UnifiedReal.E)
            R.id.op_sqrt ->
                // Seems to have highest precedence.
                // Does not add implicit paren.
                // Does seem to accept a leading minus.
                if (isOperator(i + 1, R.id.op_sub, ec)) {
                    evalUnary(i + 2, ec).let { EvalRet(it.pos, (-it.value).sqrt()) }
                } else {
                    evalUnary(i + 1, ec).let { EvalRet(it.pos, it.value.sqrt()) }
                }
            R.id.lparen -> evalFunction(i, ec) { it }
            R.id.fun_sin -> evalFunction(i, ec) { toRadians(it, ec).sin() }
            R.id.fun_cos -> evalFunction(i, ec) { toRadians(it, ec).cos() }
            R.id.fun_tan -> evalFunction(i, ec) { toRadians(it, ec).let { arg -> arg.sin() / arg.cos() } }
            R.id.fun_ln -> evalFunction(i, ec) { it.ln() }
            R.id.fun_exp -> evalFunction(i, ec) { it.exp() }
            R.id.fun_log -> evalFunction(i, ec) { it.ln() / UnifiedReal.TEN.ln() }
            R.id.fun_arcsin -> evalFunction(i, ec) { fromRadians(it.asin(), ec) }
            R.id.fun_arccos -> evalFunction(i, ec) { fromRadians(it.acos(), ec) }
            R.id.fun_arctan -> evalFunction(i, ec) { fromRadians(it.atan(), ec) }
            else -> throw SyntaxException("Unrecognized token in expression")
        }
    }

    @Throws(SyntaxException::class)
    private fun evalSuffix(i: Int, ec: EvalContext): EvalRet {
        val tmp = evalUnary(i, ec)
        var cpos = tmp.pos
        var value = tmp.value
        while (true) {
            value = when {
                isOperator(cpos, R.id.op_fact, ec) -> value.fact()
                isOperator(cpos, R.id.op_sqr, ec) -> value * value
                isOperator(cpos, R.id.op_pct, ec) -> value * ONE_HUNDREDTH
                else -> break
            }
            ++cpos
        }
        return EvalRet(cpos, value)
    }

    @Throws(SyntaxException::class)
    private fun evalFactor(i: Int, ec: EvalContext): EvalRet {
        val result1 = evalSuffix(i, ec)
        if (!isOperator(result1.pos, R.id.op_pow, ec)) return result1
        val exp = evalSignedFactor(result1.pos + 1, ec)
        return EvalRet(exp.pos, result1.value.pow(exp.value))
    }

    @Throws(SyntaxException::class)
    private fun evalSignedFactor(i: Int, ec: EvalContext): EvalRet {
        val negative = isOperator(i, R.id.op_sub, ec)
        val tmp = evalFactor(if (negative) i + 1 else i, ec)
        return EvalRet(tmp.pos, if (negative) -tmp.value else tmp.value)
    }

    private fun canStartFactor(i: Int): Boolean {
        val t = expr.getOrNull(i) ?: return false
        return t !is Operator || !KeyMaps.isBinary(t.id) && t.id != R.id.op_fact && t.id != R.id.rparen
    }

    @Throws(SyntaxException::class)
    private fun evalTerm(i: Int, ec: EvalContext): EvalRet {
        var tmp = evalSignedFactor(i, ec)
        var cpos = tmp.pos // Current position in expression.
        var value = tmp.value // Current value.
        while (true) {
            val isMul = isOperator(cpos, R.id.op_mul, ec)
            val isDiv = !isMul && isOperator(cpos, R.id.op_div, ec)
            if (!isMul && !isDiv && !canStartFactor(cpos)) break
            if (isMul || isDiv) ++cpos
            tmp = evalSignedFactor(cpos, ec)
            value = if (isDiv) value / tmp.value else value * tmp.value
            cpos = tmp.pos
        }
        return EvalRet(cpos, value)
    }

    /**
     * Is the subexpression starting at pos a simple percent constant?
     * This is used to recognize expressions like 200+10%, which we handle specially.
     * This is defined as a Constant or PreEval token, followed by a percent sign, and followed
     * by either nothing or an additive operator.
     * Note that we are intentionally far more restrictive in recognizing such expressions than
     * e.g. http://blogs.msdn.com/b/oldnewthing/archive/2008/01/10/7047497.aspx .
     * When in doubt, we fall back to the the naive interpretation of % as 1/100.
     * Note that 100+(10)% yields 100.1 while 100+10% yields 110.  This may be controversial,
     * but is consistent with Google web search.
     */
    private fun isPercent(pos: Int): Boolean {
        if (expr.size < pos + 2 || !isOperatorUnchecked(pos + 1, R.id.op_pct)) return false
        if (expr[pos] is Operator) return false
        return when (val next = expr.getOrNull(pos + 2)) {
            null -> true
            !is Operator -> false
            else -> next.id == R.id.op_add || next.id == R.id.op_sub || next.id == R.id.rparen
        }
    }

    /**
     * Compute the multiplicative factor corresponding to an N% addition or subtraction.
     * @param pos position of Constant or PreEval expression token corresponding to N.
     * @param isSubtraction this is a subtraction, as opposed to addition.
     * @param ec usable evaluation context; only length matters.
     * @return UnifiedReal value and position, which is pos + 2, i.e. after percent sign
     */
    @Throws(SyntaxException::class)
    private fun getPercentFactor(pos: Int, isSubtraction: Boolean, ec: EvalContext): EvalRet {
        val n = evalUnary(pos, ec).value.let { if (isSubtraction) -it else it }
        return EvalRet(pos + 2 /* after percent sign */, UnifiedReal.ONE + n * ONE_HUNDREDTH)
    }

    @Throws(SyntaxException::class)
    private fun evalExpr(i: Int, ec: EvalContext): EvalRet {
        var tmp = evalTerm(i, ec)
        var cpos = tmp.pos
        var value = tmp.value
        while (true) {
            val isPlus = isOperator(cpos, R.id.op_add, ec)
            if (!isPlus && !isOperator(cpos, R.id.op_sub, ec)) break
            if (isPercent(cpos + 1)) {
                tmp = getPercentFactor(cpos + 1, !isPlus, ec)
                value *= tmp.value
            } else {
                tmp = evalTerm(cpos + 1, ec)
                value = if (isPlus) value + tmp.value else value - tmp.value
            }
            cpos = tmp.pos
        }
        return EvalRet(cpos, value)
    }

    /**
     * Return the starting position of the sequence of trailing binary operators.
     */
    private fun trailingBinaryOpsStart() =
        expr.indexOfLast { !(it is Operator && KeyMaps.isBinary(it.id)) } + 1

    /**
     * Is the current expression worth evaluating?
     */
    fun hasInterestingOps(): Boolean {
        val last = trailingBinaryOpsStart()
        // Leading minus is not by itself interesting.
        val first = if (last > 0 && isOperatorUnchecked(0, R.id.op_sub)) 1 else 0
        return expr.subList(first, last).any { it is Operator || it is PreEval && it.hasEllipsis }
    }

    /**
     * Does the expression contain trig operations?
     */
    fun hasTrigFuncs() = expr.any { it is Operator && KeyMaps.isTrigFunc(it.id) }

    /**
     * Add the indices of unevaluated PreEval expressions embedded in the current expression to
     * argument.  This includes only directly referenced expressions e, not those indirectly
     * referenced by e. If the index was already present, it is not added. If the argument
     * contained no duplicates, the result will not either. New indices are added to the end of
     * the list.
     */
    private fun addReferencedExprs(list: MutableList<Long>, er: ExprResolver) {
        expr.filterIsInstance<PreEval>().map { it.index }.distinct()
            .filterTo(list) { it !in list && er.getResult(it) == null }
    }

    /**
     * Return a list of unevaluated expressions transitively referenced by the current one.
     * All expressions in the resulting list will have had er.getExpr() called on them.
     * The resulting list is ordered such that evaluating expressions in list order
     * should trigger few recursive evaluations.
     */
    fun getTransitivelyReferencedExprs(er: ExprResolver): List<Long> {
        // We could avoid triggering any recursive evaluations by actually building the
        // dependency graph and topologically sorting it. Note that sorting by index works
        // for positive and negative indices separately, but not their union. Currently we
        // just settle for reverse breadth-first-search order, which handles the common case
        // of simple dependency chains well.
        val list = ArrayList<Long>()
        var scanned = 0 // We've added expressions referenced by [0, scanned) to the list
        addReferencedExprs(list, er)
        while (scanned != list.size) {
            er.getExpr(list[scanned++]).addReferencedExprs(list, er)
        }
        return list.asReversed()
    }

    /**
     * Evaluate the expression at the given index to a UnifiedReal.
     * Both saves and returns the result.
     */
    @Throws(SyntaxException::class)
    fun nestedEval(index: Long, er: ExprResolver): UnifiedReal {
        val nestedExpr = er.getExpr(index)
        val newEc = EvalContext(er.getDegreeMode(index), nestedExpr.trailingBinaryOpsStart(), er)
        return er.putResultIfAbsent(index, nestedExpr.evalExpr(0, newEc).value)
    }

    /**
     * Evaluate the expression excluding trailing binary operators.
     * Errors result in exceptions, most of which are unchecked.  Should not be called
     * concurrently with modification of the expression.  May take a very long time; avoid calling
     * from UI thread.
     *
     * @param degreeMode use degrees rather than radians
     */
    @Throws(SyntaxException::class)
    fun eval(degreeMode: Boolean, er: ExprResolver): UnifiedReal {
        // And unchecked exceptions thrown by UnifiedReal, CR, and BoundedRational.

        // First evaluate all indirectly referenced expressions in increasing index order.
        // This ensures that subsequent evaluation never encounters an embedded PreEval
        // expression that has not been previously evaluated.
        // We could do the embedded evaluations recursively, but that risks running out of
        // stack space.
        getTransitivelyReferencedExprs(er).forEach { nestedEval(it, er) }
        try {
            // We currently never include trailing binary operators, but include other trailing
            // operators.  Thus we usually, but not always, display results for prefixes of valid
            // expressions, and don't generate an error where we previously displayed an instant
            // result.  This reflects the Android L design.
            val prefixLen = trailingBinaryOpsStart()
            val res = evalExpr(0, EvalContext(degreeMode, prefixLen, er))
            if (res.pos != prefixLen) throw SyntaxException("Failed to parse full expression")
            return res.value
        } catch (e: IndexOutOfBoundsException) {
            throw SyntaxException("Unexpected expression end")
        }
    }

    /** Produce a string representation of the expression itself. */
    fun toSpannableStringBuilder(context: Context): SpannableStringBuilder =
        expr.fold(SpannableStringBuilder()) { ssb, t -> ssb.append(t.toCharSequence(context)) }

    companion object {
        private val tokenKindValues = TokenKind.values()

        private val ONE_HUNDREDTH = UnifiedReal(100).inverse()

        /**
         * Read token from input.
         */
        @Throws(IOException::class)
        private fun newToken(input: DataInput): Token {
            val kindByte = input.readByte()
            if (kindByte >= 0x20) return Operator(kindByte)
            return when (tokenKindValues[kindByte.toInt()]) {
                TokenKind.CONSTANT -> Constant(input)
                TokenKind.PRE_EVAL -> PreEval(input).takeUnless { it.index == -1L }
                    // Database corrupted by earlier bug.
                    // Return a conspicuously wrong placeholder that won't lead to a crash.
                    ?: Constant().apply { add(R.id.dec_point) }
                else -> throw IOException("Bad save file format")
            }
        }
    }
}
