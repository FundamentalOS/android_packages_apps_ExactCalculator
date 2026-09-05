/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

import java.math.BigInteger

/**
 * Tests for the exact rational arithmetic that backs "known exact" calculator results.
 */
class BoundedRationalTest {

    @Test
    fun arithmeticIsExact() {
        val third = BoundedRational(1, 3)
        val twoThirds = BoundedRational.add(third, third)
        assertEquals(BoundedRational(2, 3), twoThirds)
        assertEquals(BoundedRational.ONE, BoundedRational.add(twoThirds, third))
        assertEquals(BoundedRational(1, 9), BoundedRational.multiply(third, third))
        assertEquals(BoundedRational(3), BoundedRational.inverse(third))
        assertEquals(BoundedRational(1, 6), BoundedRational.divide(third, BoundedRational.TWO))
        assertEquals(BoundedRational(-1, 3), BoundedRational.negate(third))
        assertEquals(BoundedRational.ZERO, BoundedRational.add(third, BoundedRational.negate(third)))
    }

    @Test
    fun nullRepresentsUnknownAndPropagates() {
        assertNull(BoundedRational.add(null, BoundedRational.ONE))
        assertNull(BoundedRational.multiply(BoundedRational.ONE, null))
        assertNull(BoundedRational.divide(null, BoundedRational.TWO))
        assertNull(BoundedRational.negate(null))
        assertNull(BoundedRational.inverse(null))
        assertNull(BoundedRational.sqrt(null))
        assertNull(BoundedRational.pow(null, BoundedRational.TWO))
        assertNull(BoundedRational.asBigInteger(null))
        assertEquals(Int.MAX_VALUE, BoundedRational.digitsRequired(null))
    }

    @Test
    fun hugeNonIntegersBecomeNullButIntegersDoNot() {
        val bigFraction = BoundedRational(BigInteger.ONE.shiftLeft(6000), BigInteger.valueOf(3))
        // Numerator plus denominator would exceed the 10000 bit limit.
        assertNull(BoundedRational.multiply(bigFraction, bigFraction))

        val bigInteger = BoundedRational(BigInteger.ONE.shiftLeft(6000))
        val square = BoundedRational.multiply(bigInteger, bigInteger)
        assertEquals(BigInteger.ONE.shiftLeft(12000), BoundedRational.asBigInteger(square))
    }

    @Test
    fun equalityIgnoresRepresentation() {
        assertEquals(BoundedRational.HALF, BoundedRational(2, 4))
        assertEquals(BoundedRational.HALF.hashCode(), BoundedRational(2, 4).hashCode())
        assertEquals(BoundedRational(-1, 2), BoundedRational(1, -2))
        assertNotEquals(BoundedRational.HALF, BoundedRational.THIRD)
        assertTrue(BoundedRational.THIRD < BoundedRational.HALF)
        assertTrue(BoundedRational(1, -2) < BoundedRational.ZERO)
        assertEquals(-1, BoundedRational(1, -2).signum())
        assertEquals(0, BoundedRational.ZERO.signum())
    }

    @Test
    fun valueOfLongReusesSharedConstants() {
        assertSame(BoundedRational.ZERO, BoundedRational.valueOf(0L))
        assertSame(BoundedRational.ONE, BoundedRational.valueOf(1L))
        assertSame(BoundedRational.MINUS_TWO, BoundedRational.valueOf(-2L))
        assertSame(BoundedRational.TEN, BoundedRational.valueOf(10L))
        assertEquals(BoundedRational(7), BoundedRational.valueOf(7L))
    }

    @Test
    fun valueOfDoubleIsExact() {
        assertEquals(BoundedRational.HALF, BoundedRational.valueOf(0.5))
        assertEquals(BoundedRational(3), BoundedRational.valueOf(3.0))
        assertEquals(BoundedRational(-5, 2), BoundedRational.valueOf(-2.5))
        // 0.1 is not exactly representable as a double; we must convert the actual double.
        val tenth = BoundedRational.valueOf(0.1)
        assertNotEquals(BoundedRational(1, 10), tenth)
        assertEquals(0.1, tenth.doubleValue(), 0.0)
        assertEquals(55, BoundedRational.digitsRequired(tenth))
        assertThrows(ArithmeticException::class.java) { BoundedRational.valueOf(Double.NaN) }
        assertThrows(ArithmeticException::class.java) {
            BoundedRational.valueOf(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun doubleValueIsCorrectlyRounded() {
        assertEquals(1.0 / 3.0, BoundedRational(1, 3).doubleValue(), 0.0)
        assertEquals(-2.0 / 3.0, BoundedRational(-2, 3).doubleValue(), 0.0)
        assertEquals(0.0, BoundedRational.ZERO.doubleValue(), 0.0)
        assertEquals(
            Double.POSITIVE_INFINITY,
            BoundedRational(BigInteger.ONE.shiftLeft(1100)).doubleValue(), 0.0
        )
        assertEquals(
            Double.MIN_VALUE,
            BoundedRational(BigInteger.ONE, BigInteger.ONE.shiftLeft(1074)).doubleValue(), 0.0
        )
        assertEquals(
            0.0,
            BoundedRational(BigInteger.ONE, BigInteger.ONE.shiftLeft(1100)).doubleValue(), 0.0
        )
    }

    @Test
    fun stringConversions() {
        assertEquals("2/4", BoundedRational(2, 4).toString())
        assertEquals("1/2", BoundedRational(2, 4).toNiceString())
        assertEquals("-3", BoundedRational(6, -2).toNiceString())
        assertEquals("3.142", BoundedRational(22, 7).toStringTruncated(3))
        assertEquals("-0.33333", BoundedRational(-1, 3).toStringTruncated(5))
        assertEquals("5.", BoundedRational(5).toStringTruncated(0))
        assertEquals("0.00", BoundedRational(1, 1000).toStringTruncated(2))
    }

    @Test
    fun integerConversions() {
        assertEquals(2, BoundedRational(6, 3).intValue())
        assertThrows(ArithmeticException::class.java) { BoundedRational.HALF.intValue() }
        assertEquals(BigInteger.valueOf(-4), BoundedRational.asBigInteger(BoundedRational(8, -2)))
        assertNull(BoundedRational.asBigInteger(BoundedRational.THIRD))
        assertEquals(Int.MIN_VALUE, BoundedRational.ZERO.wholeNumberBits())
        assertEquals(3, BoundedRational(8).wholeNumberBits())
        // Only approximate: bitLength(1) - bitLength(8).
        assertEquals(-3, BoundedRational(1, 8).wholeNumberBits())
    }

    @Test
    fun digitsRequired() {
        assertEquals(0, BoundedRational.digitsRequired(BoundedRational(7)))
        assertEquals(3, BoundedRational.digitsRequired(BoundedRational(1, 8)))
        assertEquals(2, BoundedRational.digitsRequired(BoundedRational(3, 25)))
        assertEquals(1, BoundedRational.digitsRequired(BoundedRational(5, 10)))
        assertEquals(Int.MAX_VALUE, BoundedRational.digitsRequired(BoundedRational.THIRD))
    }

    @Test
    fun integralPowers() {
        assertEquals(
            BoundedRational(BigInteger.ONE.shiftLeft(100)),
            BoundedRational.TWO.pow(BigInteger.valueOf(100))
        )
        assertEquals(BoundedRational.QUARTER, BoundedRational.TWO.pow(BigInteger.valueOf(-2)))
        assertEquals(BoundedRational(8, 27), BoundedRational(2, 3).pow(BigInteger.valueOf(3)))
        assertEquals(BoundedRational.ONE, BoundedRational.THIRD.pow(BigInteger.ZERO))
        assertEquals(BoundedRational.ZERO, BoundedRational.ZERO.pow(BigInteger.TEN))
        // Huge exponents of +-1 are still cheap and exact.
        val hugeOdd = BigInteger.ONE.shiftLeft(5000).add(BigInteger.ONE)
        assertEquals(BoundedRational.MINUS_ONE, BoundedRational.MINUS_ONE.pow(hugeOdd))
        assertEquals(BoundedRational.ONE, BoundedRational.MINUS_ONE.pow(hugeOdd.add(BigInteger.ONE)))
        // Anything else with an absurd exponent gives up rather than overflowing the stack.
        assertNull(BoundedRational(3).pow(BigInteger.ONE.shiftLeft(1001)))
        // Rational exponents are only handled when they are integral.
        assertEquals(
            BoundedRational(9),
            BoundedRational.pow(BoundedRational(3), BoundedRational(4, 2))
        )
        assertNull(BoundedRational.pow(BoundedRational(4), BoundedRational.HALF))
    }

    @Test
    fun squareRootsOfPerfectSquaresOnly() {
        assertEquals(BoundedRational(2, 3), BoundedRational.sqrt(BoundedRational(4, 9)))
        assertEquals(BoundedRational(5), BoundedRational.sqrt(BoundedRational(50, 2)))
        assertNull(BoundedRational.sqrt(BoundedRational.TWO))
        assertNull(BoundedRational.sqrt(BoundedRational.HALF))
        assertThrows(ArithmeticException::class.java) {
            BoundedRational.sqrt(BoundedRational.MINUS_ONE)
        }
    }

    @Test
    fun divisionByZeroIsReported() {
        assertThrows(BoundedRational.ZeroDivisionException::class.java) {
            BoundedRational.inverse(BoundedRational.ZERO)
        }
        assertThrows(BoundedRational.ZeroDivisionException::class.java) {
            BoundedRational.divide(BoundedRational.ONE, BoundedRational(0, 5))
        }
        assertFalse(BoundedRational.ZERO.crValue().signum(-10) != 0)
    }

    @Test
    fun crValueMatches() {
        val cr = BoundedRational(1, 4).crValue()
        assertEquals("0.2500", cr.toString(4))
    }
}
