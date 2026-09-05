/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import com.hp.creals.CR
import com.hp.creals.UnaryCRFunction

import java.math.BigInteger

import kotlin.math.abs
import kotlin.math.ln

/**
 * Computable real numbers, represented so that we can get exact decidable comparisons
 * for a number of interesting special cases, including rational computations.
 *
 * A real number is represented as the product of two numbers with different representations:
 * A) A BoundedRational that can only represent a subset of the rationals, but supports
 *    exact computable comparisons.
 * B) A lazily evaluated "constructive real number" that provides operations to evaluate
 *    itself to any requested number of digits.
 * Whenever possible, we choose (B) to be one of a small set of known constants about which we
 * know more.  For example, whenever we can, we represent rationals such that (B) is 1.
 * This scheme allows us to do some very limited symbolic computation on numbers when both
 * have the same (B) value, as well as in some other situations.  We try to maximize that
 * possibility.
 *
 * Arithmetic operations and operations that produce finite approximations may throw unchecked
 * exceptions produced by the underlying CR and BoundedRational packages, including
 * CR.PrecisionOverflowException and CR.AbortedException.
 *
 * Well-known constructive reals are identified by reference, so all comparisons of CR values
 * below are intentionally identity comparisons.
 */
class UnifiedReal private constructor(
    private val ratFactor: BoundedRational,
    private val crFactor: CR
) {
    // TODO: It would be helpful to add flags to indicate whether the result is known
    // irrational, etc.  This sometimes happens even if crFactor is not one of the known ones.
    // And exact comparisons between rationals and known irrationals are decidable.

    constructor(cr: CR) : this(BoundedRational.ONE, cr)

    constructor(rat: BoundedRational) : this(rat, CR_ONE)

    constructor(n: BigInteger) : this(BoundedRational(n))

    constructor(n: Long) : this(BoundedRational(n))

    /** Is this number known to be zero? */
    val definitelyZero: Boolean get() = ratFactor.signum() == 0

    /** Is this number known to be rational? */
    val definitelyRational: Boolean get() = crFactor === CR_ONE || definitelyZero

    /**
     * Is this number known to be irrational?
     * TODO: We could track the fact that something is irrational with an explicit flag, which
     * could cover many more cases.  Whether that matters in practice is TBD.
     */
    val definitelyIrrational: Boolean get() = !definitelyRational && isNamed(crFactor)

    /** Is this number known to be algebraic? */
    val definitelyAlgebraic: Boolean get() = definitelyAlgebraic(crFactor) || definitelyZero

    /** Is this number known to be transcendental? */
    val definitelyTranscendental: Boolean get() = !definitelyAlgebraic && isNamed(crFactor)

    /**
     * Convert to String reflecting raw representation.
     * Debug or log messages only, not pretty.
     */
    override fun toString() = "$ratFactor*$crFactor"

    /**
     * Convert to readable String.
     * Intended for user output.  Produces exact expression when possible.
     */
    fun toNiceString(): String {
        if (definitelyRational) return ratFactor.toNiceString()
        val name = crName(crFactor)
        return when {
            name == null ->
                if (ratFactor == BoundedRational.ONE) crFactor.toString() else crValue().toString()
            BoundedRational.asBigInteger(ratFactor) == null -> "(${ratFactor.toNiceString()})$name"
            ratFactor == BoundedRational.ONE -> name
            else -> ratFactor.toNiceString() + name
        }
    }

    /**
     * Convert to a fraction in lowest terms, if this is known to be rational and is not a whole
     * number; null otherwise. Not internationalized.
     */
    fun toFractionString(): String? = if (definitelyRational) ratFactor.toFractionString() else null

    /** Will toNiceString() produce an exact representation? */
    val exactlyDisplayable: Boolean get() = crName(crFactor) != null

    /**
     * Returns a truncated representation of the result.
     * If exactlyTruncatable, we round correctly towards zero. Otherwise the resulting digit
     * string may occasionally be rounded up instead.
     * Always includes a decimal point in the result.
     * The result includes n digits to the right of the decimal point.
     * @param n result precision, >= 0
     */
    fun toStringTruncated(n: Int): String {
        if (crFactor === CR_ONE || ratFactor === BoundedRational.ZERO) {
            return ratFactor.toStringTruncated(n)
        }
        val scaled = CR.valueOf(BigInteger.TEN.pow(n)).multiply(crValue())
        val exact = exactlyTruncatable
        var intScaled = scaled.get_appr(if (exact) 0 else -EXTRA_PREC)
        val negative = intScaled.signum() < 0
        if (negative) intScaled = -intScaled
        if (exact) {
            if (CR.valueOf(intScaled).compareTo(scaled.abs()) > 0) intScaled -= BigInteger.ONE
            checkInvariant(CR.valueOf(intScaled).compareTo(scaled.abs()) < 0)
        } else {
            // Approximate case.  Exact comparisons are impossible.
            intScaled = intScaled shr EXTRA_PREC
        }
        return (if (negative) "-" else "") + intScaled.toScaledDecimalString(n)
    }

    /**
     * Can we compute correctly truncated approximations of this number?
     * If the value is known rational, we can do exact comparisons.
     * If the value is known irrational, then we can safely compare to rational
     * approximations; equality is impossible; hence the comparison must converge.
     * The only problem cases are the ones in which we don't know.
     */
    val exactlyTruncatable: Boolean
        get() = crFactor === CR_ONE || ratFactor === BoundedRational.ZERO || definitelyIrrational

    fun crValue(): CR = ratFactor.crValue().multiply(crFactor)

    /**
     * Are this and u exactly comparable?
     * We check for ONE only to speed up the common case.
     * The use of a tolerance here means we can spuriously return false, not true.
     */
    fun isComparable(u: UnifiedReal): Boolean =
        crFactor === u.crFactor &&
            (isNamed(crFactor) || crFactor.signum(DEFAULT_COMPARE_TOLERANCE) != 0) ||
            definitelyZero && u.definitelyZero ||
            definitelyIndependent(crFactor, u.crFactor) ||
            crValue().compareTo(u.crValue(), DEFAULT_COMPARE_TOLERANCE) != 0

    /**
     * Return +1 if this is greater than u, -1 if this is less than u, or 0 of the two are
     * known to be equal.
     * May diverge if the two are equal and !isComparable(u).
     */
    operator fun compareTo(u: UnifiedReal): Int = when {
        definitelyZero && u.definitelyZero -> 0
        // Can diverge if crFactor == 0.
        crFactor === u.crFactor -> crFactor.signum() * ratFactor.compareTo(u.ratFactor)
        else -> crValue().compareTo(u.crValue()) // Can also diverge.
    }

    /**
     * Return +1 if this is greater than u, -1 if this is less than u, or possibly 0 of the two
     * are within 2^a of each other.
     */
    fun compareTo(u: UnifiedReal, a: Int): Int =
        if (isComparable(u)) compareTo(u) else crValue().compareTo(u.crValue(), a)

    /** Return compareTo(ZERO, a). */
    fun signum(a: Int) = compareTo(ZERO, a)

    /**
     * Return compareTo(ZERO).
     * May diverge for ZERO argument if !isComparable(ZERO).
     */
    fun signum() = compareTo(ZERO)

    /**
     * Equality comparison.  May erroneously return true if values differ by less than 2^a,
     * and !isComparable(u).
     */
    fun approxEquals(u: UnifiedReal, a: Int): Boolean = when {
        !isComparable(u) -> crValue().compareTo(u.crValue(), a) == 0
        // No need to actually evaluate, though we don't know which is larger.
        definitelyIndependent(crFactor, u.crFactor) && !(definitelyZero && u.definitelyZero) -> false
        else -> compareTo(u) == 0
    }

    /**
     * Returns true if values are definitely known to be equal, false in all other cases.
     * This does not satisfy the contract for Object.equals().
     */
    fun definitelyEquals(u: UnifiedReal) = isComparable(u) && compareTo(u) == 0

    // Better useless than wrong. Probably.
    override fun hashCode() = 0

    override fun equals(other: Any?): Boolean {
        if (other !is UnifiedReal) return false
        // This is almost certainly a programming error. Don't even try.
        throw AssertionError("Can't compare UnifiedReals for exact equality")
    }

    /** Return equivalent BoundedRational, if known to exist, null otherwise */
    fun boundedRationalValue(): BoundedRational? = ratFactor.takeIf { definitelyRational }

    /** Returns equivalent BigInteger result if it exists, null if not. */
    fun bigIntegerValue(): BigInteger? = BoundedRational.asBigInteger(boundedRationalValue())

    operator fun plus(u: UnifiedReal): UnifiedReal {
        if (crFactor === u.crFactor) {
            (ratFactor + u.ratFactor)?.let { return UnifiedReal(it, crFactor) }
        }
        return when {
            // Avoid creating new crFactor, even if they don't currently match.
            definitelyZero -> u
            u.definitelyZero -> this
            else -> UnifiedReal(crValue().add(u.crValue()))
        }
    }

    operator fun unaryMinus() = UnifiedReal(checkNotNull(-ratFactor), crFactor)

    operator fun minus(u: UnifiedReal) = this + -u

    operator fun times(u: UnifiedReal): UnifiedReal {
        // Preserve a preexisting crFactor when we can.
        if (crFactor === CR_ONE) {
            (ratFactor * u.ratFactor)?.let { return UnifiedReal(it, u.crFactor) }
        }
        if (u.crFactor === CR_ONE) {
            (ratFactor * u.ratFactor)?.let { return UnifiedReal(it, crFactor) }
        }
        if (definitelyZero || u.definitelyZero) return ZERO
        if (crFactor === u.crFactor) {
            getSquare(crFactor)?.let { square ->
                (square * ratFactor * u.ratFactor)?.let { return UnifiedReal(it) }
            }
        }
        // Probably a bit cheaper to multiply component-wise.
        return (ratFactor * u.ratFactor)?.let { UnifiedReal(it, crFactor.multiply(u.crFactor)) }
            ?: UnifiedReal(crValue().multiply(u.crValue()))
    }

    class ZeroDivisionException : ArithmeticException("Division by zero")

    /** Return the reciprocal. */
    fun inverse(): UnifiedReal {
        if (definitelyZero) throw ZeroDivisionException()
        getSquare(crFactor)?.let { square ->
            // 1/sqrt(n) = sqrt(n)/n
            BoundedRational.inverse(ratFactor * square)?.let { return UnifiedReal(it, crFactor) }
        }
        return UnifiedReal(checkNotNull(BoundedRational.inverse(ratFactor)), crFactor.inverse())
    }

    operator fun div(u: UnifiedReal): UnifiedReal {
        if (crFactor === u.crFactor) {
            if (u.definitelyZero) throw ZeroDivisionException()
            (ratFactor / u.ratFactor)?.let { return UnifiedReal(it, CR_ONE) }
        }
        return this * u.inverse()
    }

    /**
     * Return the square root.
     * This may fail to return a known rational value, even when the result is rational.
     */
    fun sqrt(): UnifiedReal {
        if (definitelyZero) return ZERO
        if (crFactor === CR_ONE) {
            // Check for all arguments of the form <perfect rational square> * small_int,
            // where small_int has a known sqrt.  This includes the small_int = 1 case.
            SQRTS.forEachIndexed { divisor, sqrtDivisor ->
                if (sqrtDivisor != null) {
                    BoundedRational.sqrt(ratFactor / BoundedRational(divisor.toLong()))
                        ?.let { return UnifiedReal(it, sqrtDivisor) }
                }
            }
        }
        return UnifiedReal(crValue().sqrt())
    }

    /**
     * Return (this mod 2pi)/(pi/6) as a BigInteger, or null if that isn't easily possible.
     */
    private fun getPiTwelfths(): BigInteger? = when {
        definitelyZero -> BigInteger.ZERO
        crFactor === CR_PI ->
            BoundedRational.asBigInteger(ratFactor * BoundedRational.TWELVE)?.mod(BIG_24)
        else -> null
    }

    fun sin(): UnifiedReal =
        getPiTwelfths()?.let { sinPiTwelfths(it.toInt()) } ?: UnifiedReal(crValue().sin())

    fun cos(): UnifiedReal =
        getPiTwelfths()?.let { cosPiTwelfths(it.toInt()) } ?: UnifiedReal(crValue().cos())

    // Throw an exception if the argument is definitely out of bounds for asin or acos.
    private fun checkAsinDomain() {
        if (isComparable(ONE) && (this > ONE || this < MINUS_ONE)) {
            throw ArithmeticException("inverse trig argument out of range")
        }
    }

    /**
     * Return asin of this, assuming this is not an integral multiple of a half.
     */
    fun asinNonHalves(): UnifiedReal = when {
        compareTo(ZERO, -10) < 0 -> -(-this).asinNonHalves()
        definitelyEquals(HALF_SQRT2) -> UnifiedReal(BoundedRational.QUARTER, CR_PI)
        definitelyEquals(HALF_SQRT3) -> UnifiedReal(BoundedRational.THIRD, CR_PI)
        else -> UnifiedReal(crValue().asin())
    }

    fun asin(): UnifiedReal {
        checkAsinDomain()
        return (this * TWO).bigIntegerValue()?.let { asinHalves(it.toInt()) } ?: asinNonHalves()
    }

    fun acos() = PI_OVER_2 - asin()

    fun atan(): UnifiedReal {
        if (compareTo(ZERO, -10) < 0) return -(-this).atan()
        val asBI = bigIntegerValue()
        if (asBI != null && asBI <= BigInteger.ONE) {
            // These seem to be all rational cases:
            return when (asBI.toInt()) {
                0 -> ZERO
                1 -> PI_OVER_4
                else -> throw AssertionError("Impossible r_int")
            }
        }
        return when {
            definitelyEquals(THIRD_SQRT3) -> PI_OVER_6
            definitelyEquals(SQRT3) -> PI_OVER_3
            else -> UnifiedReal(UnaryCRFunction.atanFunction.execute(crValue()))
        }
    }

    /**
     * Compute an integral power of a constructive real, using the exp function when
     * we safely can. Use recursivePow when we can't. exp is known to be nonzero.
     */
    private fun expLnPow(exp: BigInteger): UnifiedReal {
        val sign = signum(DEFAULT_COMPARE_TOLERANCE)
        return when {
            // Safe to take the log. This avoids deep recursion for huge exponents, which
            // may actually make sense here.
            sign > 0 -> UnifiedReal(crValue().ln().multiply(CR.valueOf(exp)).exp())
            sign < 0 -> UnifiedReal(
                crValue().negate().ln().multiply(CR.valueOf(exp)).exp()
                    .let { if (exp.isOdd) it.negate() else it }
            )
            // Base of unknown sign with integer exponent. Use a recursive computation.
            // (Another possible option would be to use the absolute value of the base, and then
            // adjust the sign at the end.  But that would have to be done in the CR
            // implementation.)
            // This may be very expensive if -exp is large.
            exp.signum() < 0 -> UnifiedReal(recursivePow(crValue(), -exp).inverse())
            else -> UnifiedReal(recursivePow(crValue(), exp))
        }
    }

    /**
     * Compute an integral power of this.
     * This recurses roughly as deeply as the number of bits in the exponent, and can, in
     * ridiculous cases, result in a stack overflow.
     */
    private fun pow(exp: BigInteger): UnifiedReal {
        if (exp.isOne) return this
        // Questionable if base has undefined value or is 0.
        // Java.lang.Math.pow() returns 1 anyway, so we do the same.
        if (exp.signum() == 0) return ONE
        val absExp = exp.abs()
        if (crFactor === CR_ONE && absExp <= HARD_RECURSIVE_POW_LIMIT) {
            // We count on this to fail, e.g. for very large exponents, when it would
            // otherwise be too expensive.
            ratFactor.pow(exp)?.let { return UnifiedReal(it) }
        }
        if (absExp > RECURSIVE_POW_LIMIT) return expLnPow(exp)
        getSquare(crFactor)?.let { square ->
            (ratFactor.pow(exp) * square.pow(exp shr 1))?.let {
                // Odd power: Multiply by remaining square root.
                return if (exp.isOdd) UnifiedReal(it, crFactor) else UnifiedReal(it)
            }
        }
        return expLnPow(exp)
    }

    /**
     * Return this ^ expon.
     * This is really only well-defined for a positive base, particularly since
     * 0^x is not continuous at zero. (0^0 = 1 (as is epsilon^0), but 0^epsilon is 0.
     * We nonetheless try to do reasonable things at zero, when we recognize that case.
     */
    fun pow(expon: UnifiedReal): UnifiedReal {
        if (crFactor === CR_E) {
            return if (ratFactor == BoundedRational.ONE) {
                expon.exp()
            } else {
                expon.exp() * UnifiedReal(ratFactor).pow(expon)
            }
        }
        expon.boundedRationalValue()?.let { expAsBR ->
            BoundedRational.asBigInteger(expAsBR)?.let { return pow(it) }
            // Check for exponent that is a multiple of a half.
            BoundedRational.asBigInteger(BoundedRational.TWO * expAsBR)?.let { return pow(it).sqrt() }
        }
        // If the exponent were known zero, we would have handled it above.
        if (definitelyZero) return ZERO
        if (signum(DEFAULT_COMPARE_TOLERANCE) < 0) {
            throw ArithmeticException("Negative base for pow() with non-integer exponent")
        }
        return UnifiedReal(crValue().ln().multiply(expon.crValue()).exp())
    }

    fun ln(): UnifiedReal {
        if (crFactor === CR_E) return UnifiedReal(ratFactor, CR_ONE).ln() + ONE
        if (isComparable(ZERO)) {
            if (signum() <= 0) throw ArithmeticException("log(non-positive)")
            val compare1 = compareTo(ONE, DEFAULT_COMPARE_TOLERANCE)
            if (compare1 == 0) {
                if (definitelyEquals(ONE)) return ZERO
            } else if (compare1 < 0) {
                return -inverse().ln()
            }
            BoundedRational.asBigInteger(ratFactor)?.let { bi ->
                if (crFactor === CR_ONE) {
                    // Check for a power of a small integer.  We can use LOGS[] to return
                    // a more useful answer for those.
                    LOGS.forEachIndexed { i, log ->
                        if (log != null) {
                            val intLog = getIntLog(bi, i)
                            if (intLog != 0L) return UnifiedReal(BoundedRational(intLog), log)
                        }
                    }
                } else {
                    // Check for n^k * sqrt(n), for which we can also return a more useful answer.
                    val intSquare = getSquare(crFactor)?.intValue() ?: return@let
                    val log = LOGS[intSquare] ?: return@let
                    val intLog = getIntLog(bi, intSquare)
                    if (intLog != 0L) {
                        (BoundedRational(intLog) + BoundedRational.HALF)
                            ?.let { return UnifiedReal(it, log) }
                    }
                }
            }
        }
        return UnifiedReal(crValue().ln())
    }

    fun exp(): UnifiedReal {
        if (definitelyEquals(ZERO)) return ONE
        // Avoid redundant computations, and ensure we recognize all instances as equal.
        if (definitelyEquals(ONE)) return E
        getExp(crFactor)?.let { crExp ->
            // An exponent that is not an integer may still be a multiple of one half.
            val needSqrt = BoundedRational.asBigInteger(ratFactor) == null
            val ratExponent = if (needSqrt) ratFactor * BoundedRational.TWO else ratFactor
            BoundedRational.pow(crExp, ratExponent)?.let {
                return UnifiedReal(it).let { result -> if (needSqrt) result.sqrt() else result }
            }
        }
        return UnifiedReal(crValue().exp())
    }

    /**
     * Factorial function.
     * Fails if argument is clearly not an integer.
     * May round to nearest integer if value is close.
     */
    fun fact(): UnifiedReal {
        val asBI: BigInteger = bigIntegerValue() ?: run {
            val approx = crValue().get_appr(0) // Correct if it was an integer.
            if (!approxEquals(UnifiedReal(approx), DEFAULT_COMPARE_TOLERANCE)) {
                throw ArithmeticException("Non-integral factorial argument")
            }
            approx
        }
        if (asBI.signum() < 0) throw ArithmeticException("Negative factorial argument")
        // Will fail.  LongValue() may not work. Punt now.
        if (asBI.bitLength() > 20) throw ArithmeticException("Factorial argument too big")
        return UnifiedReal(BoundedRational(genFactorial(asBI.toLong(), 1)))
    }

    /**
     * Return the number of decimal digits to the right of the decimal point required to represent
     * the argument exactly.
     * Return Integer.MAX_VALUE if that's not possible.  Never returns a value less than zero, even
     * if r is a power of ten.
     */
    fun digitsRequired(): Int =
        if (definitelyRational) BoundedRational.digitsRequired(ratFactor) else Int.MAX_VALUE

    /**
     * Return an upper bound on the number of leading zero bits.
     * These are the number of 0 bits
     * to the right of the binary point and to the left of the most significant digit.
     * Return Integer.MAX_VALUE if we cannot bound it.
     */
    fun leadingBinaryZeroes(): Int {
        if (!isNamed(crFactor)) return Int.MAX_VALUE
        // Only ln(2) is smaller than one, and could possibly add one zero bit.
        // Adding 3 gives us a somewhat sloppy upper bound.
        val wholeBits = ratFactor.wholeNumberBits()
        return when {
            wholeBits == Int.MIN_VALUE -> Int.MAX_VALUE
            wholeBits >= 3 -> 0
            else -> -wholeBits + 3
        }
    }

    /**
     * Is the number of bits to the left of the decimal point greater than bound?
     * The result is inexact: We roughly approximate the whole number bits.
     */
    fun approxWholeNumberBitsGreaterThan(bound: Int): Boolean =
        if (isNamed(crFactor)) {
            ratFactor.wholeNumberBits() > bound
        } else {
            crValue().get_appr(bound - 2).bitLength() > 2
        }

    companion object {
        /**
         * Perform some nontrivial consistency checks.
         * @hide
         */
        @JvmField var enableChecks = true

        private fun checkInvariant(b: Boolean) {
            if (enableChecks && !b) throw AssertionError()
        }

        // Various helpful constants
        private val BIG_24: BigInteger = BigInteger.valueOf(24)
        private const val DEFAULT_COMPARE_TOLERANCE = -1000

        // Well-known CR constants we try to use in the crFactor position:
        private val CR_ONE: CR = CR.ONE
        private val CR_PI: CR = CR.PI
        private val CR_E: CR = CR.ONE.exp()
        private val CR_SQRT2: CR = CR.valueOf(2).sqrt()
        private val CR_SQRT3: CR = CR.valueOf(3).sqrt()
        private val CR_LN2: CR = CR.valueOf(2).ln()
        private val CR_LN3: CR = CR.valueOf(3).ln()
        private val CR_LN5: CR = CR.valueOf(5).ln()
        private val CR_LN6: CR = CR.valueOf(6).ln()
        private val CR_LN7: CR = CR.valueOf(7).ln()
        private val CR_LN10: CR = CR.valueOf(10).ln()

        // Square roots that we try to recognize.
        // We currently recognize only a small fixed collection, since the sqrt() function needs
        // to identify numbers of the form <SQRTS[i]>*n^2, and we don't otherwise know of a good
        // algorithm for that.
        private val SQRTS: Array<CR?> = arrayOf(
            null, CR.ONE, CR_SQRT2, CR_SQRT3, null, CR.valueOf(5).sqrt(), CR.valueOf(6).sqrt(),
            CR.valueOf(7).sqrt(), null, null, CR.valueOf(10).sqrt()
        )

        // Natural logs of small integers that we try to recognize.
        private val LOGS: Array<CR?> = arrayOf(
            null, null, CR_LN2, CR_LN3, null, CR_LN5, CR_LN6, CR_LN7, null, null, CR_LN10
        )

        // Some convenient UnifiedReal constants.
        @JvmField val PI = UnifiedReal(CR_PI)
        @JvmField val E = UnifiedReal(CR_E)
        @JvmField val ZERO = UnifiedReal(BoundedRational.ZERO)
        @JvmField val ONE = UnifiedReal(BoundedRational.ONE)
        @JvmField val MINUS_ONE = UnifiedReal(BoundedRational.MINUS_ONE)
        @JvmField val TWO = UnifiedReal(BoundedRational.TWO)
        @JvmField val MINUS_TWO = UnifiedReal(BoundedRational.MINUS_TWO)
        @JvmField val HALF = UnifiedReal(BoundedRational.HALF)
        @JvmField val MINUS_HALF = UnifiedReal(BoundedRational.MINUS_HALF)
        @JvmField val TEN = UnifiedReal(BoundedRational.TEN)
        @JvmField val RADIANS_PER_DEGREE = UnifiedReal(BoundedRational(1, 180), CR_PI)
        private val HALF_SQRT2 = UnifiedReal(BoundedRational.HALF, CR_SQRT2)
        private val SQRT3 = UnifiedReal(CR_SQRT3)
        private val HALF_SQRT3 = UnifiedReal(BoundedRational.HALF, CR_SQRT3)
        private val THIRD_SQRT3 = UnifiedReal(BoundedRational.THIRD, CR_SQRT3)
        private val PI_OVER_2 = UnifiedReal(BoundedRational.HALF, CR_PI)
        private val PI_OVER_3 = UnifiedReal(BoundedRational.THIRD, CR_PI)
        private val PI_OVER_4 = UnifiedReal(BoundedRational.QUARTER, CR_PI)
        private val PI_OVER_6 = UnifiedReal(BoundedRational.SIXTH, CR_PI)

        // Number of extra bits used in evaluation below to prefer truncation to rounding.
        // Must be <= 30.
        private const val EXTRA_PREC = 10

        // The (in abs value) integral exponent for which we attempt to use a recursive
        // algorithm for evaluating pow(). The recursive algorithm works independent of the sign
        // of the base, and can produce rational results. But it can become slow for very large
        // exponents.
        private val RECURSIVE_POW_LIMIT: BigInteger = BigInteger.valueOf(1000)

        // The corresponding limit when we're using rational arithmetic. This should fail fast
        // anyway, but we avoid ridiculously deep recursion.
        private val HARD_RECURSIVE_POW_LIMIT: BigInteger = BigInteger.ONE shl 1000

        fun valueOf(x: Double): UnifiedReal =
            if (x == 0.0 || x == 1.0) valueOf(x.toLong()) else UnifiedReal(BoundedRational.valueOf(x))

        fun valueOf(x: Long): UnifiedReal = when (x) {
            0L -> ZERO
            1L -> ONE
            else -> UnifiedReal(BoundedRational.valueOf(x))
        }

        /** Index of [cr] in [table], or null. Lookups are by identity, so misses are likely. */
        private fun indexOf(table: Array<CR?>, cr: CR): Int? =
            table.indexOfFirst { it === cr }.takeIf { it >= 0 }

        /**
         * Given a constructive real cr, try to determine whether cr is the square root of
         * a small integer.  If so, return its square as a BoundedRational.  Otherwise return
         * null. We make this determination by simple table lookup, so spurious null returns are
         * entirely possible, or even likely.
         */
        private fun getSquare(cr: CR): BoundedRational? =
            indexOf(SQRTS, cr)?.let { BoundedRational(it.toLong()) }

        /**
         * Given a constructive real cr, try to determine whether cr is the logarithm of a small
         * integer.  If so, return exp(cr) as a BoundedRational.  Otherwise return null.
         * We make this determination by simple table lookup, so spurious null returns are
         * entirely possible, or even likely.
         */
        private fun getExp(cr: CR): BoundedRational? =
            indexOf(LOGS, cr)?.let { BoundedRational(it.toLong()) }

        /**
         * If the argument is a well-known constructive real, return its name.
         * The name of "CR_ONE" is the empty string.
         * No named constructive reals are rational multiples of each other.
         * Thus two UnifiedReals with different named crFactors can be equal only if both
         * ratFactors are zero or possibly if one is CR_PI and the other is CR_E.
         * (The latter is apparently an open problem.)
         */
        private fun crName(cr: CR): String? = when {
            cr === CR_ONE -> ""
            cr === CR_PI -> "π" // GREEK SMALL LETTER PI
            cr === CR_E -> "e"
            else -> indexOf(SQRTS, cr)?.let { "√$it" /* SQUARE ROOT */ }
                ?: indexOf(LOGS, cr)?.let { "ln($it)" }
        }

        /** Would crName() return non-Null? */
        private fun isNamed(cr: CR): Boolean =
            cr === CR_ONE || cr === CR_PI || cr === CR_E ||
                SQRTS.any { it === cr } || LOGS.any { it === cr }

        /**
         * Is cr known to be algebraic (as opposed to transcendental)?
         * Currently only produces meaningful results for the above known special
         * constructive reals.
         */
        private fun definitelyAlgebraic(cr: CR) = cr === CR_ONE || getSquare(cr) != null

        /**
         * Is it known that the two constructive reals differ by something other than a
         * a rational factor, i.e. is it known that two UnifiedReals
         * with those crFactors will compare unequal unless both ratFactors are zero?
         * If this returns true, then a comparison of two UnifiedReals using those two
         * crFactors cannot diverge, though we don't know of a good runtime bound.
         */
        private fun definitelyIndependent(r1: CR, r2: CR): Boolean = when {
            // The question here is whether r1 = x*r2, where x is rational, where r1 and r2
            // are in our set of special known CRs, can have a solution.
            r1 === r2 -> false
            // This cannot happen for e or pi on one side, and a square root on the other.
            // (One is transcendental, the other is algebraic.)
            // Unfortunately, we do not know whether e/pi is rational.
            r1 === CR_E || r1 === CR_PI -> definitelyAlgebraic(r2)
            r2 === CR_E || r2 === CR_PI -> definitelyAlgebraic(r1)
            // This cannot happen if one is CR_ONE and the other is not.
            // (Since all others are irrational.)
            // This cannot happen for two named square roots, which have no repeated factors.
            // (To see this, square both sides of the equation and factor.  Each prime
            // factor in the numerator and denominator occurs twice.)
            // This cannot happen for two of our special natural logs.
            // (Otherwise ln(m) = (a/b)ln(n) ==> m = n^(a/b) ==> m^b = n^a, which is impossible
            // because either m or n includes a prime factor not shared by the other.)
            // This cannot happen for a log and a square root.
            // (The Lindemann-Weierstrass theorem tells us, among other things, that if
            // a is algebraic, then exp(a) is transcendental.  Thus if l in our finite
            // set of logs where algebraic, exp(l), must be transcendental.
            // But exp(l) is an integer.  Thus the logs are transcendental.  But of course the
            // square roots are algebraic.  Thus they can't be rational multiples.)
            else -> isNamed(r1) && isNamed(r2)
        }

        /**
         * Compute the sin() for an integer multiple n of pi/12, if easily representable.
         * @param n value between 0 and 23 inclusive.
         */
        private fun sinPiTwelfths(n: Int): UnifiedReal? {
            if (n >= 12) return sinPiTwelfths(n - 12)?.let { -it }
            return when (n) {
                0 -> ZERO
                2 -> HALF // 30 degrees
                3 -> HALF_SQRT2 // 45 degrees
                4 -> HALF_SQRT3 // 60 degrees
                6 -> ONE
                8 -> HALF_SQRT3
                9 -> HALF_SQRT2
                10 -> HALF
                else -> null
            }
        }

        private fun cosPiTwelfths(n: Int) = sinPiTwelfths((n + 6) % 24)

        /**
         * Return asin(n/2).  n is between -2 and 2.
         */
        fun asinHalves(n: Int): UnifiedReal {
            if (n < 0) return -asinHalves(-n)
            return when (n) {
                0 -> ZERO
                1 -> UnifiedReal(BoundedRational.SIXTH, CR.PI)
                2 -> UnifiedReal(BoundedRational.HALF, CR.PI)
                else -> throw AssertionError("asinHalves: Bad argument")
            }
        }

        /**
         * Compute an integral power of a constructive real, using the standard recursive
         * algorithm. exp is known to be positive.
         */
        private fun recursivePow(base: CR, exp: BigInteger): CR {
            if (exp.isOne) return base
            if (exp.isOdd) return base.multiply(recursivePow(base, exp - BigInteger.ONE))
            val tmp = recursivePow(base, exp shr 1)
            if (Thread.interrupted()) throw CR.AbortedException()
            return tmp.multiply(tmp)
        }

        /**
         * Raise the argument to the 16th power.
         */
        private fun pow16(n: Int): Long {
            if (n > 10) throw AssertionError("Unexpected pow16 argument")
            var result = (n * n).toLong()
            repeat(3) { result *= result }
            return result
        }

        /**
         * Return the integral log with respect to the given base if it exists, 0 otherwise.
         * n is presumed positive.
         */
        private fun getIntLog(value: BigInteger, base: Int): Long {
            var n = value
            val nAsDouble = n.toDouble()
            val approx = ln(nAsDouble) / ln(base.toDouble())
            // A relatively quick test first.
            // Unfortunately, this doesn't help for values to big to fit in a Double.
            if (!nAsDouble.isInfinite() && abs(approx - Math.rint(approx)) > 1.0e-6) return 0
            var result = 0L
            val bigBase = BigInteger.valueOf(base.toLong())
            val base16th by lazy { BigInteger.valueOf(pow16(base)) } // base^16
            while (n.mod(bigBase).signum() == 0) {
                if (Thread.interrupted()) throw CR.AbortedException()
                n /= bigBase
                ++result
                // And try a slightly faster computation for large n:
                while (n.mod(base16th).signum() == 0) {
                    n /= base16th
                    result += 16
                }
            }
            return if (n.isOne) result else 0
        }

        /**
         * Generalized factorial.
         * Compute n * (n - step) * (n - 2 * step) * etc.  This can be used to compute factorial a
         * bit faster, especially if BigInteger uses sub-quadratic multiplication.
         */
        private fun genFactorial(n: Long, step: Long): BigInteger {
            if (n > 4 * step) {
                val prod1 = genFactorial(n, 2 * step)
                if (Thread.interrupted()) throw CR.AbortedException()
                val prod2 = genFactorial(n - step, 2 * step)
                if (Thread.interrupted()) throw CR.AbortedException()
                return prod1 * prod2
            }
            if (n == 0L) return BigInteger.ONE
            return generateSequence(n - step) { it - step }.takeWhile { it > 1 }
                .fold(BigInteger.valueOf(n)) { acc, i -> acc * BigInteger.valueOf(i) }
        }
    }
}
