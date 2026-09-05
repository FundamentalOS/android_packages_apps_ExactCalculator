/*
 * SPDX-FileCopyrightText: 2015 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
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

        val isEmpty: Boolean get() = !sawDecimal && whole.isEmpty()

        /**
         * The constant as typed, without digit grouping: the characters that edit positions
         * count. Not internationalized.
         */
        fun modelString(): String = buildString {
            append(whole)
            if (sawDecimal) append('.').append(fraction)
            if (exponent != 0) append('E').append(exponent)
        }

        /** The number of characters in [modelString]. */
        val length: Int
            get() = whole.length +
                (if (sawDecimal) 1 + fraction.length else 0) +
                (if (exponent != 0) 1 + exponent.toString().length else 0)

        /**
         * The constant as shown, before internationalization: [modelString] with digit
         * grouping separators, as commas, in the whole number. Only the separators differ, so
         * the two map onto each other character by character.
         */
        fun displayString(): String = buildString {
            append(if (exponent != 0) whole else whole.addCommas(0, whole.length))
            if (sawDecimal) append('.').append(fraction)
            if (exponent != 0) append('E').append(exponent)
        }

        /**
         * This constant with the key's character inserted at [offset] of [modelString], or
         * null if that would not be a constant: a second decimal point, or one in an exponent.
         */
        fun inserting(offset: Int, id: Int): Constant? {
            if (id == R.id.dec_point && (sawDecimal || exponent != 0)) return null
            val c = if (id == R.id.dec_point) '.' else '0' + KeyMaps.digVal(id)
            return parse(StringBuilder(modelString()).insert(offset, c).toString())
        }

        /**
         * This constant with the character at [offset] of [modelString] removed: null if
         * nothing is left, this constant itself if what is left is not a constant (as when
         * the marker of an exponent goes but its sign stays), in which case nothing is deleted.
         */
        fun deleting(offset: Int): Constant? {
            val model = modelString().removeRange(offset, offset + 1)
            return if (model.isEmpty()) null else parse(model) ?: this
        }

        /**
         * This constant split at [offset] of [modelString], either part possibly being nothing;
         * or null if it cannot be split there, as inside an exponent.
         */
        fun split(offset: Int): Pair<Constant?, Constant?>? {
            val model = modelString()
            val head = model.take(offset)
            val tail = model.drop(offset)
            val headConstant = parse(head)
            val tailConstant = parse(tail)
            if (head.isNotEmpty() && headConstant == null || tail.isNotEmpty() && tailConstant == null) return null
            return headConstant to tailConstant
        }

        /**
         * Produce human-readable string representation of constant, as typed.
         * We do add digit grouping separators to the whole number, even if not typed.
         * Result is internationalized.
         */
        override fun toString(): String = KeyMaps.translateResult(displayString())

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

            /** Digits, at most one decimal point, and an optional exponent. */
            private val MODEL = Regex("(\\d*)(\\.(\\d*))?(?:E(-?\\d*))?")

            /**
             * The constant a model string describes, or null if it is not one, or is empty.
             * An exponent that is too large is refused; an exponent marker left without digits
             * is dropped, as deleting the digits of an exponent always has.
             */
            fun parse(model: String): Constant? {
                val match = MODEL.matchEntire(model) ?: return null
                val whole = match.groupValues[1]
                val sawDecimal = match.groupValues[2].isNotEmpty()
                val fraction = match.groupValues[3]
                val exponentDigits = match.groupValues[4]
                val exponent = when {
                    exponentDigits.isEmpty() || exponentDigits == "-" -> 0
                    else -> exponentDigits.toIntOrNull()?.takeIf { abs(it) <= MAX_EXPONENT } ?: return null
                }
                if (!sawDecimal && whole.isEmpty()) return null
                return Constant(whole, fraction, sawDecimal, exponent)
            }

            private const val MAX_EXPONENT = 10000
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

    /**
     * A place where the expression can be edited: before token [token], or, if [offset] is
     * not zero, [offset] characters into the constant token [token]. Positions are kept
     * normalised, so that the end of a constant is expressed as the start of the token after
     * it, and [end] is `Position(size, 0)`. Characters are those of the tokens' model
     * strings, i.e. constants as typed, without digit grouping; operators and pre-evaluated
     * results are indivisible.
     */
    data class Position(val token: Int, val offset: Int) : Comparable<Position> {
        override fun compareTo(other: Position) = compareValuesBy(this, other, { it.token }, { it.offset })
    }

    /** The position after the last token. */
    val end: Position get() = Position(expr.size, 0)

    /** Is [at] the end of the expression? */
    fun isAtEnd(at: Position) = normalised(at) == end

    private fun normalised(token: Int, offset: Int): Position {
        val t = expr.getOrNull(token)
        return when {
            t is Constant && offset >= t.length -> Position(token + 1, 0)
            t is Constant && offset > 0 -> Position(token, offset)
            else -> Position(token.coerceIn(0, expr.size), 0)
        }
    }

    private fun normalised(position: Position) = normalised(position.token, position.offset)

    /** The token just before the position: the constant it is in, or the previous token. */
    private fun tokenBefore(at: Position): Token? =
        if (at.offset > 0) expr.getOrNull(at.token) else expr.getOrNull(at.token - 1)

    private fun operatorBefore(at: Position) = tokenBefore(at) as? Operator

    /** Is what precedes the position a numeric constant, as opposed to an operator or a pre-evaluated result? */
    fun hasConstantBefore(at: Position): Boolean = when (val t = tokenBefore(at)) {
        is Constant -> true
        is Operator -> t.id == R.id.const_pi || t.id == R.id.const_e
        else -> false
    }

    fun hasBinaryBefore(at: Position) = operatorBefore(at)?.let { KeyMaps.isBinary(it.id) } == true

    fun hasSuffixBefore(at: Position) = operatorBefore(at)?.let { KeyMaps.isSuffix(it.id) } == true

    fun hasLeftParenBefore(at: Position) =
        operatorBefore(at)?.let { it.id == R.id.lparen || KeyMaps.isFunc(it.id) } == true

    fun hasRightParenBefore(at: Position) = operatorBefore(at)?.id == R.id.rparen

    /** Is there an unmatched lparen before the position? */
    fun hasOpenParenthesesBefore(at: Position): Boolean {
        val operators = expr.take(at.token).filterIsInstance<Operator>()
        val opens = operators.count { it.id == R.id.lparen || KeyMaps.isFunc(it.id) }
        val closes = operators.count { it.id == R.id.rparen }
        return opens > closes
    }

    /**
     * Does this expression end with a numeric constant?
     * As opposed to an operator or preevaluated expression.
     */
    fun hasTrailingConstant() = hasConstantBefore(end)

    /** Does this expression end with a binary operator? */
    fun hasTrailingBinary() = hasBinaryBefore(end)

    /** Does this expression end with a suffix operator? */
    fun hasTrailingSuffix() = hasSuffixBefore(end)

    /** Does this expression contain an unmatched lparen? */
    fun hasOpenParentheses() = hasOpenParenthesesBefore(end)

    /** Does this expression end with a left parenthesis? */
    fun hasTrailingLeftParen() = hasLeftParenBefore(end)

    /** Does this expression end with a right parenthesis? */
    fun hasTrailingRightParen() = hasRightParenBefore(end)

    /**
     * Insert the press of the key with the given id at [at]. Returns the position after what
     * was inserted, or null if the insertion was refused because it would clearly make a
     * syntax error, in which case the expression is unchanged. As one adjustment, a binary
     * operator inserted right after another one replaces it, unless it is a minus, which is
     * allowed as a unary minus.
     */
    fun insert(id: Int, at: Position): Position? {
        val pos = normalised(at)
        return if (KeyMaps.digVal(id) != KeyMaps.NOT_DIGIT || id == R.id.dec_point) {
            insertDigit(id, pos)
        } else {
            insertOperator(id, pos)
        }
    }

    private fun insertDigit(id: Int, pos: Position): Position? {
        if (pos.offset > 0) {
            // Inside a constant.
            val edited = (expr[pos.token] as Constant).inserting(pos.offset, id) ?: return null
            expr[pos.token] = edited
            return normalised(pos.token, pos.offset + 1)
        }
        val previous = expr.getOrNull(pos.token - 1)
        if (previous is Constant) {
            val edited = previous.inserting(previous.length, id) ?: return null
            expr[pos.token - 1] = edited
            return Position(pos.token, 0)
        }
        val next = expr.getOrNull(pos.token)
        if (next is Constant) {
            val edited = next.inserting(0, id) ?: return null
            expr[pos.token] = edited
            return normalised(pos.token, 1)
        }
        // A new constant. Juxtaposition means multiplication; next to a pre-evaluated result
        // that is made explicit, since the result would otherwise read as part of the constant.
        val constant = Constant.parse(if (id == R.id.dec_point) "." else KeyMaps.digVal(id).toString()) ?: return null
        var index = pos.token
        if (previous is PreEval) expr.add(index++, Operator(R.id.op_mul))
        expr.add(index++, constant)
        if (next is PreEval) expr.add(index, Operator(R.id.op_mul))
        return Position(index, 0)
    }

    private fun insertOperator(id: Int, pos: Position): Position? {
        var index = pos.token
        if (pos.offset == 0 && KeyMaps.isBinary(id) && !KeyMaps.isPrefix(id)) {
            val previousOp = (expr.getOrNull(index - 1) as? Operator)?.id ?: 0
            if (index == 0 || previousOp == R.id.lparen || KeyMaps.isFunc(previousOp) ||
                KeyMaps.isPrefix(previousOp) && previousOp != R.id.op_sub
            ) {
                return null
            }
            // Quietly replace the binary operators before it.
            while (index > 0 && (expr[index - 1] as? Operator)?.let { KeyMaps.isBinary(it.id) } == true) {
                expr.removeAt(--index)
            }
        }
        if (pos.offset > 0) {
            // Inside a constant, which is split around the operator.
            val (head, tail) = (expr[index] as Constant).split(pos.offset) ?: return null
            expr.removeAt(index)
            tail?.let { expr.add(index, it) }
            head?.let { expr.add(index++, it) }
        }
        expr.add(index, Operator(id))
        return Position(index + 1, 0)
    }

    /**
     * Delete the character before [at]: a digit, or a whole operator or pre-evaluated result.
     * Returns the position of what was deleted. Two constants left adjacent become one.
     */
    fun deleteBefore(at: Position): Position {
        val pos = normalised(at)
        if (pos.offset > 0) return deleteInConstant(pos.token, pos.offset - 1)
        if (pos.token == 0) return pos
        val index = pos.token - 1
        val previous = expr[index]
        if (previous is Constant) return deleteInConstant(index, previous.length - 1)
        expr.removeAt(index)
        val before = expr.getOrNull(index - 1) as? Constant
        val after = expr.getOrNull(index) as? Constant
        if (before != null && after != null) {
            Constant.parse(before.modelString() + after.modelString())?.let { merged ->
                expr[index - 1] = merged
                expr.removeAt(index)
                return normalised(index - 1, before.length)
            }
        }
        return Position(index, 0)
    }

    private fun deleteInConstant(index: Int, offset: Int): Position {
        val constant = expr[index] as Constant
        val edited = constant.deleting(offset)
        if (edited == null) {
            expr.removeAt(index)
            return Position(index, 0)
        }
        if (edited === constant) return normalised(index, offset + 1) // Refused: nothing changes.
        expr[index] = edited
        return normalised(index, offset)
    }

    /** Delete everything between the two positions; returns where that was. */
    fun deleteRange(from: Position, to: Position): Position {
        val start = normalised(from)
        var pos = normalised(to)
        while (pos > start) {
            val next = deleteBefore(pos)
            if (next == pos) break
            pos = next
        }
        return pos
    }

    /** Remove the op_add and op_sub operators just before [at]; returns the position again. */
    fun removeAdditiveOperatorsBefore(at: Position): Position {
        var pos = normalised(at)
        while (operatorBefore(pos)?.id.let { it == R.id.op_add || it == R.id.op_sub }) {
            expr.removeAt(pos.token - 1)
            pos = Position(pos.token - 1, 0)
        }
        return pos
    }

    /**
     * Insert the contents of the argument expression at [at]; returns the position after it.
     * It is assumed that the argument expression will not change, and thus its pieces can be
     * reused directly.
     */
    fun insertExpr(expr2: CalculatorExpr, at: Position): Position {
        val tokens = expr2.expr
        if (tokens.isEmpty()) return normalised(at)
        var index = boundaryAt(normalised(at))
        // Check that we're not concatenating Constant or PreEval tokens, since the result would
        // look like a single constant, with very mysterious results for the user.
        // Fudge it by adding an explicit multiplication.  We would have interpreted it as
        // such anyway, and this makes it recognizable to the user.
        if (expr.getOrNull(index - 1).let { it != null && it !is Operator } && tokens.first() !is Operator) {
            expr.add(index++, Operator(R.id.op_mul))
        }
        expr.addAll(index, tokens)
        index += tokens.size
        if (tokens.last() !is Operator && expr.getOrNull(index).let { it != null && it !is Operator }) {
            expr.add(index, Operator(R.id.op_mul))
        }
        return Position(index, 0)
    }

    /**
     * The token index of the position, splitting the constant it is inside if it is; where
     * that constant cannot be split, the boundary after it.
     */
    private fun boundaryAt(pos: Position): Int {
        if (pos.offset == 0) return pos.token
        var index = pos.token
        val (head, tail) = (expr[index] as Constant).split(pos.offset) ?: return index + 1
        expr.removeAt(index)
        tail?.let { expr.add(index, it) }
        head?.let { expr.add(index++, it) }
        return index
    }

    /**
     * Add an exponent to the constant that precedes [at], or that the position is inside.
     * Returns the position after that constant. Assumes there is such a constant.
     */
    fun addExponentBefore(at: Position, exp: Int): Position {
        val pos = normalised(at)
        val index = if (pos.offset > 0) pos.token else pos.token - 1
        (expr[index] as Constant).addExponent(exp)
        return Position(index + 1, 0)
    }

    /**
     * Append press of button with given id to expression.
     * If the insertion would clearly result in a syntax error, either just return false
     * and do nothing, or make an adjustment to avoid the problem.  We do the latter only
     * for unambiguous consecutive binary operators, in which case we delete the first
     * operator.
     */
    fun add(id: Int): Boolean = insert(id, end) != null

    /**
     * Add exponent to the constant at the end of the expression.
     * Assumes there is a constant at the end of the expression.
     */
    fun addExponent(exp: Int) {
        addExponentBefore(end, exp)
    }

    /**
     * Remove trailing op_add and op_sub operators.
     */
    fun removeTrailingAdditiveOperators() {
        removeAdditiveOperatorsBefore(end)
    }

    /** Append the contents of the argument expression; see [insertExpr]. */
    fun append(expr2: CalculatorExpr) {
        insertExpr(expr2, end)
    }

    /**
     * Undo the last key addition, if any.
     * Or possibly remove a trailing exponent digit.
     */
    fun delete() {
        deleteBefore(end)
    }

    /**
     * The position that [displayOffset] characters into the displayed formula (as produced
     * by [toSpannableStringBuilder]) corresponds to. Offsets inside an operator or a
     * pre-evaluated result snap to whichever of its ends is nearer.
     */
    fun positionOf(context: Context, displayOffset: Int): Position {
        var display = 0
        for ((index, token) in expr.withIndex()) {
            if (token is Constant) {
                val shown = token.displayString()
                if (displayOffset <= display + shown.length) {
                    // Grouping separators are shown but not counted.
                    val model = shown.take(displayOffset - display).count { it != ',' }
                    return normalised(index, model)
                }
                display += shown.length
            } else {
                val length = token.toCharSequence(context).length
                if (displayOffset < display + length) {
                    return Position(if ((displayOffset - display) * 2 <= length) index else index + 1, 0)
                }
                display += length
            }
        }
        return end
    }

    /** The offset into the displayed formula that [position] corresponds to. */
    fun displayOffsetOf(context: Context, position: Position): Int {
        var display = 0
        for ((index, token) in expr.withIndex()) {
            if (index == position.token) {
                if (position.offset == 0 || token !is Constant) return display
                val shown = token.displayString()
                var model = 0
                var i = 0
                while (i < shown.length && model < position.offset) {
                    if (shown[i] != ',') model++
                    i++
                }
                return display + i
            }
            display += if (token is Constant) token.displayString().length else token.toCharSequence(context).length
        }
        return display
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
