/*
 * SPDX-FileCopyrightText: 2015 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import com.hp.creals.CR

import java.math.BigInteger
import java.util.Objects
import java.util.Random

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rational numbers that may turn to null if they get too big.
 * For many operations, if the length of the numerator plus the length of the denominator exceeds
 * a maximum size, we simply return null, and rely on our caller do something else.
 * We currently never return null for a pure integer or for a BoundedRational that has just been
 * constructed.
 *
 * We also implement a number of irrational functions.  These return a non-null result only when
 * the result is known to be rational.
 *
 * Arithmetic is exposed as static functions taking nullable arguments so that null propagates;
 * see the operator extensions in Extensions.kt for the infix spelling.
 */
class BoundedRational(private val num: BigInteger, private val den: BigInteger) :
    Comparable<BoundedRational> {
    // TODO: Consider returning null for integers.  With some care, large factorials might become
    // much faster.
    // TODO: Maybe eventually make this extend Number?

    constructor(n: BigInteger) : this(n, BigInteger.ONE)

    constructor(n: Long, d: Long) : this(BigInteger.valueOf(n), BigInteger.valueOf(d))

    constructor(n: Long) : this(BigInteger.valueOf(n), BigInteger.ONE)

    /**
     * Convert to String reflecting raw representation.
     * Debug or log messages only, not pretty.
     */
    override fun toString() = "$num/$den"

    /**
     * Convert to readable String.
     * Intended for output to user.  More expensive, less useful for debugging than
     * toString().  Not internationalized.
     */
    fun toNiceString(): String = reduce().positiveDen().let {
        if (it.den.isOne) "${it.num}" else "${it.num}/${it.den}"
    }

    /**
     * Convert to a fraction in lowest terms, "numerator/denominator", or null if this is a whole
     * number. Not internationalized.
     */
    fun toFractionString(): String? =
        reduce().positiveDen().takeUnless { it.den.isOne }?.let { "${it.num}/${it.den}" }

    /**
     * Returns a truncated (rounded towards 0) representation of the result.
     * Includes n digits to the right of the decimal point.
     * @param n result precision, >= 0
     */
    fun toStringTruncated(n: Int): String =
        (if (signum() < 0) "-" else "") +
            (num.abs() * BigInteger.TEN.pow(n) / den.abs()).toScaledDecimalString(n)

    /**
     * Return a double approximation.
     * The result is correctly rounded to nearest, with ties rounded away from zero.
     * TODO: Should round ties to even.
     */
    fun doubleValue(): Double {
        val sign = signum()
        if (sign < 0) {
            return -checkNotNull(negate(this)).doubleValue()
        }
        // We get the mantissa by dividing the numerator by denominator, after
        // suitably prescaling them so that the integral part of the result contains
        // enough bits. We do the prescaling to avoid any precision loss, so the division result
        // is correctly truncated towards zero.
        val apprExp = num.bitLength() - den.bitLength()
        if (apprExp < -1100 || sign == 0) {
            // Bail fast for clearly zero result.
            return 0.0
        }
        val neededPrec = apprExp - 80
        val dividend = if (neededPrec < 0) num shl -neededPrec else num
        val divisor = if (neededPrec > 0) den shl neededPrec else den
        val quotient = dividend / divisor
        val qLength = quotient.bitLength()
        var extraBits = qLength - 53
        var exponent = neededPrec + qLength // Exponent assuming leading binary point.
        if (exponent >= -1021) {
            // Binary point is actually to right of leading bit.
            --exponent
        } else {
            // We're in the gradual underflow range. Drop more bits.
            extraBits += (-1022 - exponent) + 1
            exponent = -1023
        }
        val bigMantissa = (quotient + (BigInteger.ONE shl (extraBits - 1))) shr extraBits
        if (exponent > 1024) {
            return Double.POSITIVE_INFINITY
        }
        if (exponent > -1023 && bigMantissa.bitLength() != 53 ||
            exponent <= -1023 && bigMantissa.bitLength() >= 53
        ) {
            throw AssertionError("doubleValue internal error")
        }
        val mantissa = bigMantissa.toLong()
        val bits = (mantissa and ((1L shl 52) - 1)) or ((exponent.toLong() + 1023) shl 52)
        return Double.fromBits(bits)
    }

    fun crValue(): CR = CR.valueOf(num).divide(CR.valueOf(den))

    fun intValue(): Int = reduce().let {
        if (it.den.isOne) it.num.toInt() else throw ArithmeticException("intValue of non-int")
    }

    // Approximate number of bits to left of binary point.
    // Negative indicates leading zeroes to the right of binary point.
    fun wholeNumberBits(): Int =
        if (num.signum() == 0) Int.MIN_VALUE else num.bitLength() - den.bitLength()

    /**
     * Is this number too big for us to continue with rational arithmetic?
     * We return false for integers on the assumption that we have no better fallback.
     */
    private val tooBig: Boolean
        get() = !den.isOne && num.bitLength() + den.bitLength() > MAX_SIZE

    /**
     * Return an equivalent fraction with a positive denominator.
     */
    private fun positiveDen() = if (den.signum() > 0) this else BoundedRational(-num, -den)

    /**
     * Return an equivalent fraction in lowest terms.
     * Denominator sign may remain negative.
     */
    private fun reduce(): BoundedRational {
        if (den.isOne) return this // Optimization only
        val divisor = num.gcd(den)
        return BoundedRational(num / divisor, den / divisor)
    }

    // Compare by multiplying both sides by denominators, invert result if denominator product
    // was negative.
    override fun compareTo(other: BoundedRational): Int =
        (num * other.den).compareTo(other.num * den) * den.signum() * other.den.signum()

    fun signum() = num.signum() * den.signum()

    // Note that this may be too expensive to be useful.
    override fun hashCode() = reduce().positiveDen().let { Objects.hash(it.num, it.den) }

    override fun equals(other: Any?) = other is BoundedRational && compareTo(other) == 0

    /**
     * Compute integral power of this, assuming this has been reduced and exp is >= 0.
     */
    private fun rawPow(exp: BigInteger): BoundedRational? = when {
        exp.isOne -> this
        exp.isOdd -> rawMultiply(rawPow(exp - BigInteger.ONE), this)
        exp.signum() == 0 -> ONE
        else -> {
            val tmp = rawPow(exp shr 1)
            if (Thread.interrupted()) throw CR.AbortedException()
            rawMultiply(tmp, tmp)?.takeUnless { it.tooBig }
        }
    }

    /**
     * Compute an integral power of this.
     */
    fun pow(exp: BigInteger): BoundedRational? {
        val expSign = exp.signum()
        if (expSign == 0) {
            // Questionable if base has undefined or zero value.
            // java.lang.Math.pow() returns 1 anyway, so we do the same.
            return ONE
        }
        if (exp.isOne) return this
        // Reducing once at the beginning means there's no point in reducing later.
        val reduced = reduce().positiveDen()
        // First handle cases in which huge exponents could give compact results.
        if (reduced.den.isOne) {
            when (reduced.num) {
                BigInteger.ZERO -> return ZERO
                BigInteger.ONE -> return ONE
                BIG_MINUS_ONE -> return if (exp.isOdd) MINUS_ONE else ONE
            }
        }
        if (exp.bitLength() > 1000) {
            // Stack overflow is likely; a useful rational result is not.
            return null
        }
        return if (expSign < 0) checkNotNull(inverse(reduced)).rawPow(-exp) else reduced.rawPow(exp)
    }

    class ZeroDivisionException : ArithmeticException("Division by zero")

    companion object {
        private const val MAX_SIZE = 10000 // total, in bits

        @JvmField val ZERO = BoundedRational(0)
        @JvmField val HALF = BoundedRational(1, 2)
        @JvmField val MINUS_HALF = BoundedRational(-1, 2)
        @JvmField val THIRD = BoundedRational(1, 3)
        @JvmField val QUARTER = BoundedRational(1, 4)
        @JvmField val SIXTH = BoundedRational(1, 6)
        @JvmField val ONE = BoundedRational(1)
        @JvmField val MINUS_ONE = BoundedRational(-1)
        @JvmField val TWO = BoundedRational(2)
        @JvmField val MINUS_TWO = BoundedRational(-2)
        @JvmField val TEN = BoundedRational(10)
        @JvmField val TWELVE = BoundedRational(12)
        private val BIG_MINUS_ONE: BigInteger = BigInteger.valueOf(-1)
        private val BIG_FIVE: BigInteger = BigInteger.valueOf(5)

        private val reduceRng = Random()

        /**
         * Produce BoundedRational equal to the given double.
         */
        fun valueOf(x: Double): BoundedRational {
            // Math.round rounds half up, unlike kotlin.math.round, which rounds half to even.
            val l = Math.round(x)
            if (l.toDouble() == x && abs(l) <= 1000) {
                return valueOf(l)
            }
            val allBits = abs(x).toRawBits()
            var mantissa = allBits and ((1L shl 52) - 1)
            val biasedExp = (allBits ushr 52).toInt()
            if ((biasedExp and 0x7ff) == 0x7ff) {
                throw ArithmeticException("Infinity or NaN not convertible to BoundedRational")
            }
            val sign = if (x < 0.0) -1L else 1L
            var exp = biasedExp - 1075 // 1023 + 52; we treat mantissa as integer.
            if (biasedExp == 0) {
                exp += 1 // Denormal exponent is 1 greater.
            } else {
                mantissa += (1L shl 52) // Implied leading one.
            }
            val num = BigInteger.valueOf(sign * mantissa)
            return if (exp >= 0) {
                BoundedRational(num shl exp, BigInteger.ONE)
            } else {
                BoundedRational(num, BigInteger.ONE shl -exp)
            }
        }

        /**
         * Produce BoundedRational equal to the given long.
         */
        fun valueOf(x: Long): BoundedRational = when (x) {
            -2L -> MINUS_TWO
            -1L -> MINUS_ONE
            0L -> ZERO
            1L -> ONE
            2L -> TWO
            10L -> TEN
            else -> BoundedRational(x)
        }

        /**
         * Return a possibly reduced version of r that's not tooBig().
         * Return null if none exists.
         */
        private fun maybeReduce(r: BoundedRational?): BoundedRational? {
            if (r == null) return null
            // Reduce randomly, with 1/16 probability, or if the result is too big.
            if (!r.tooBig && (reduceRng.nextInt() and 0xf) != 0) return r
            return r.positiveDen().reduce().takeUnless { it.tooBig }
        }

        // We use static methods for arithmetic, so that we can easily handle the null case.  We
        // try to catch domain errors whenever possible, sometimes even when one of the arguments
        // is null, but not relevant.

        /**
         * Returns equivalent BigInteger result if it exists, null if not.
         */
        fun asBigInteger(r: BoundedRational?): BigInteger? {
            if (r == null) return null
            val (quotient, remainder) = r.num.divideAndRemainder(r.den)
            return quotient.takeIf { remainder.signum() == 0 }
        }

        fun add(r1: BoundedRational?, r2: BoundedRational?): BoundedRational? {
            if (r1 == null || r2 == null) return null
            return maybeReduce(
                BoundedRational(r1.num * r2.den + r2.num * r1.den, r1.den * r2.den)
            )
        }

        /**
         * Return the argument, but with the opposite sign.
         * Returns null only for a null argument.
         */
        fun negate(r: BoundedRational?): BoundedRational? = r?.let { BoundedRational(-it.num, it.den) }

        /**
         * Return product of r1 and r2 without reducing the result.
         */
        private fun rawMultiply(r1: BoundedRational?, r2: BoundedRational?): BoundedRational? = when {
            // It's tempting but marginally unsound to reduce 0 * null to 0.  The null could
            // represent an infinite value, for which we failed to throw an exception because it
            // was too big.
            r1 == null || r2 == null -> null
            // Optimize the case of our special ONE constant, since that's cheap and somewhat
            // frequent.
            r1 === ONE -> r2
            r2 === ONE -> r1
            else -> BoundedRational(r1.num * r2.num, r1.den * r2.den)
        }

        fun multiply(r1: BoundedRational?, r2: BoundedRational?) = maybeReduce(rawMultiply(r1, r2))

        /**
         * Return the reciprocal of r (or null if the argument was null).
         */
        fun inverse(r: BoundedRational?): BoundedRational? {
            if (r == null) return null
            if (r.num.signum() == 0) throw ZeroDivisionException()
            return BoundedRational(r.den, r.num)
        }

        fun divide(r1: BoundedRational?, r2: BoundedRational?) = multiply(r1, inverse(r2))

        private fun BigInteger.exactSqrtOrNull(): BigInteger? =
            BigInteger.valueOf(Math.round(sqrt(toDouble()))).takeIf { it * it == this }

        fun sqrt(r: BoundedRational?): BoundedRational? {
            // Return non-null if numerator and denominator are small perfect squares.
            val reduced = r?.positiveDen()?.reduce() ?: return null
            if (reduced.num.signum() < 0) throw ArithmeticException("sqrt(negative)")
            val numSqrt = reduced.num.exactSqrtOrNull() ?: return null
            val denSqrt = reduced.den.exactSqrtOrNull() ?: return null
            return BoundedRational(numSqrt, denSqrt)
        }

        fun pow(base: BoundedRational?, exp: BoundedRational?): BoundedRational? {
            if (exp == null || base == null) return null
            val reducedExp = exp.reduce().positiveDen()
            return if (reducedExp.den.isOne) base.pow(reducedExp.num) else null
        }

        /**
         * Return the number of decimal digits to the right of the decimal point required to
         * represent the argument exactly.
         * Return Integer.MAX_VALUE if that's not possible.  Never returns a value less than zero,
         * even if r is a power of ten.
         */
        fun digitsRequired(r: BoundedRational?): Int {
            if (r == null) return Int.MAX_VALUE
            // Try the easy case first to speed things up.
            if (r.den.isOne) return 0
            var den = r.reduce().den
            if (den.bitLength() > MAX_SIZE) return Int.MAX_VALUE
            var powersOfTwo = 0 // Max power of 2 that divides denominator
            var powersOfFive = 0 // Max power of 5 that divides denominator
            while (!den.isOdd) {
                ++powersOfTwo
                den = den shr 1
            }
            while (den.mod(BIG_FIVE).signum() == 0) {
                ++powersOfFive
                den /= BIG_FIVE
            }
            // If the denominator has a factor of other than 2 or 5 (the divisors of 10), the
            // decimal expansion does not terminate.  Multiplying the fraction by any number of
            // powers of 10 will not cancel the denominator.  (Recall the fraction was in lowest
            // terms to start with.) Otherwise the powers of 10 we need to cancel the denominator
            // is the larger of powersOfTwo and powersOfFive.
            if (!den.isOne && den != BIG_MINUS_ONE) return Int.MAX_VALUE
            return max(powersOfTwo, powersOfFive)
        }
    }
}
