/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

import java.math.BigInteger

/**
 * Tests for the symbolic/constructive real layer used for every calculator result.
 */
class UnifiedRealTest {

    private fun ur(n: Long) = UnifiedReal(n)

    private fun ur(num: Long, den: Long) = UnifiedReal(BoundedRational(num, den))

    private fun assertExactlyEqual(expected: UnifiedReal, actual: UnifiedReal) {
        assertTrue("expected $expected but was $actual", actual.definitelyEquals(expected))
    }

    @Test
    fun rationalArithmeticStaysRational() {
        val sum = ur(1, 3) + ur(1, 6)
        assertExactlyEqual(UnifiedReal.HALF, sum)
        assertTrue(sum.definitelyRational)
        assertEquals("1/2", sum.toNiceString())
        assertEquals(BoundedRational.HALF, sum.boundedRationalValue())
        assertExactlyEqual(ur(36), ur(12) * ur(3))
        assertEquals(BigInteger.valueOf(36), (ur(12) * ur(3)).bigIntegerValue())
        assertExactlyEqual(ur(-6), ur(12) - ur(18))
        assertExactlyEqual(ur(7, 3), ur(7) / ur(3))
        assertExactlyEqual(ur(-7, 3), -(ur(7) / ur(3)))
    }

    @Test
    fun sharedConstantsAndValueOf() {
        assertSame(UnifiedReal.ZERO, UnifiedReal.valueOf(0.0))
        assertSame(UnifiedReal.ONE, UnifiedReal.valueOf(1.0))
        assertSame(UnifiedReal.ONE, UnifiedReal.valueOf(1L))
        assertExactlyEqual(ur(5, 2), UnifiedReal.valueOf(2.5))
        assertTrue(UnifiedReal.ZERO.definitelyZero)
        assertFalse(UnifiedReal.PI.definitelyZero)
    }

    @Test
    fun squareRootsAreRecognizedSymbolically() {
        val sqrt2 = ur(2).sqrt()
        assertEquals("√2", sqrt2.toNiceString())
        assertTrue(sqrt2.definitelyIrrational)
        assertTrue(sqrt2.definitelyAlgebraic)
        assertFalse(sqrt2.definitelyTranscendental)
        assertEquals("2√2", ur(8).sqrt().toNiceString())

        // sqrt(2) * sqrt(8) == 4 exactly, not just approximately.
        val product = sqrt2 * ur(8).sqrt()
        assertTrue(product.definitelyRational)
        assertExactlyEqual(ur(4), product)
        assertEquals(BigInteger.valueOf(4), product.bigIntegerValue())

        assertExactlyEqual(UnifiedReal.HALF, UnifiedReal(BoundedRational.QUARTER).sqrt())
        assertSame(UnifiedReal.ZERO, UnifiedReal.ZERO.sqrt())
        assertExactlyEqual(UnifiedReal.ONE / sqrt2, sqrt2.inverse())
        assertThrows(ArithmeticException::class.java) { ur(-1).sqrt() }
    }

    @Test
    fun trigonometryAtSpecialAngles() {
        val piOver2 = UnifiedReal.PI / UnifiedReal.TWO
        assertExactlyEqual(UnifiedReal.ONE, piOver2.sin())
        assertExactlyEqual(UnifiedReal.ZERO, UnifiedReal.PI.sin())
        assertExactlyEqual(UnifiedReal.MINUS_ONE, UnifiedReal.PI.cos())
        assertExactlyEqual(UnifiedReal.HALF, (UnifiedReal.PI / ur(6)).sin())
        assertExactlyEqual(UnifiedReal.HALF, (UnifiedReal.PI / ur(3)).cos())

        // Degree mode is implemented by scaling with RADIANS_PER_DEGREE.
        assertExactlyEqual(UnifiedReal.ONE, (ur(90) * UnifiedReal.RADIANS_PER_DEGREE).sin())
        assertExactlyEqual(UnifiedReal.MINUS_ONE, (ur(180) * UnifiedReal.RADIANS_PER_DEGREE).cos())

        assertExactlyEqual(piOver2, UnifiedReal.ONE.asin())
        assertExactlyEqual(UnifiedReal.PI / ur(6), UnifiedReal.HALF.asin())
        assertExactlyEqual(UnifiedReal.ZERO, UnifiedReal.ONE.acos())
        assertExactlyEqual(UnifiedReal.PI / ur(4), UnifiedReal.ONE.atan())
        assertExactlyEqual(UnifiedReal.PI / ur(3), ur(3).sqrt().atan())
        assertExactlyEqual(UnifiedReal.ZERO, UnifiedReal.ZERO.atan())
        assertThrows(ArithmeticException::class.java) { ur(2).asin() }
    }

    @Test
    fun logarithmsAndExponentials() {
        assertExactlyEqual(UnifiedReal.ONE, UnifiedReal.E.ln())
        assertExactlyEqual(UnifiedReal.ZERO, UnifiedReal.ONE.ln())
        assertSame(UnifiedReal.ONE, UnifiedReal.ZERO.exp())
        assertSame(UnifiedReal.E, UnifiedReal.ONE.exp())
        // ln(8) is recognized as 3 ln(2).
        assertEquals("3ln(2)", ur(8).ln().toNiceString())
        assertExactlyEqual(ur(2).ln() * ur(3), ur(8).ln())
        assertExactlyEqual(-ur(2).ln(), UnifiedReal.HALF.ln())
        // exp(ln(2)) folds back to 2.
        assertExactlyEqual(ur(2), ur(2).ln().exp())
        assertExactlyEqual(ur(4), (ur(2).ln() * ur(2)).exp())
        assertThrows(ArithmeticException::class.java) { UnifiedReal.ZERO.ln() }
        assertThrows(ArithmeticException::class.java) { ur(-1).ln() }
        assertEquals("2.30258", ur(10).ln().toStringTruncated(5))
    }

    @Test
    fun powers() {
        assertEquals(BigInteger.ONE.shiftLeft(100), ur(2).pow(ur(100)).bigIntegerValue())
        assertExactlyEqual(ur(2), ur(4).pow(UnifiedReal.HALF))
        assertExactlyEqual(UnifiedReal.HALF, ur(2).pow(UnifiedReal.MINUS_ONE))
        assertExactlyEqual(ur(-512), ur(-8).pow(ur(3)))
        assertExactlyEqual(UnifiedReal.ONE, ur(7).pow(UnifiedReal.ZERO))
        assertExactlyEqual(UnifiedReal.E, UnifiedReal.E.pow(UnifiedReal.ONE))
        assertExactlyEqual(ur(2).sqrt(), ur(2).pow(UnifiedReal.HALF))
        // (sqrt(2))^4 == 4
        assertExactlyEqual(ur(4), ur(2).sqrt().pow(ur(4)))
        assertEquals("1.41421", ur(2).pow(UnifiedReal.HALF).toStringTruncated(5))
        assertEquals("8.82497", ur(2).pow(UnifiedReal.PI).toStringTruncated(5))
        assertThrows(ArithmeticException::class.java) { ur(-2).pow(UnifiedReal.HALF) }
    }

    @Test
    fun factorial() {
        assertEquals("3628800", ur(10).fact().toNiceString())
        assertExactlyEqual(UnifiedReal.ONE, UnifiedReal.ZERO.fact())
        assertExactlyEqual(ur(120), ur(5).fact())
        assertThrows(ArithmeticException::class.java) { UnifiedReal.HALF.fact() }
        assertThrows(ArithmeticException::class.java) { ur(-1).fact() }
        assertThrows(ArithmeticException::class.java) { ur(1L shl 30).fact() }
    }

    @Test
    fun decimalApproximations() {
        assertEquals("3.14159", UnifiedReal.PI.toStringTruncated(5))
        assertEquals("2.71828", UnifiedReal.E.toStringTruncated(5))
        assertEquals("0.3333333333", (ur(1) / ur(3)).toStringTruncated(10))
        assertEquals("-0.3333333333", (ur(-1) / ur(3)).toStringTruncated(10))
        assertEquals("-3.1415", (-UnifiedReal.PI).toStringTruncated(4))
        assertEquals("4.", (UnifiedReal.PI * ur(4) / UnifiedReal.PI).toStringTruncated(0))
        assertTrue(UnifiedReal.PI.exactlyTruncatable)
        assertTrue(UnifiedReal.PI.exactlyDisplayable)
        assertFalse((UnifiedReal.PI + UnifiedReal.E).exactlyDisplayable)
    }

    @Test
    fun comparisons() {
        assertTrue(UnifiedReal.PI.compareTo(ur(3), -100) > 0)
        assertTrue(UnifiedReal.PI.compareTo(ur(4), -100) < 0)
        assertTrue(UnifiedReal.PI > ur(3))
        assertTrue(ur(3) < UnifiedReal.PI)
        assertEquals(0, UnifiedReal.PI.compareTo(UnifiedReal.PI))
        assertEquals(1, UnifiedReal.PI.signum())
        assertEquals(-1, (-UnifiedReal.PI).signum(-10))
        assertTrue(UnifiedReal.PI.isComparable(ur(3)))
        assertTrue(ur(2).sqrt().isComparable(ur(3).sqrt()))
        assertFalse(ur(2).sqrt().definitelyEquals(ur(3).sqrt()))
        assertTrue(UnifiedReal.PI.approxEquals(UnifiedReal.PI + UnifiedReal.ZERO, -1000))
        assertFalse(UnifiedReal.PI.approxEquals(UnifiedReal.E, -1000))
        // Object.equals() is deliberately unusable on UnifiedReal.
        assertThrows(AssertionError::class.java) {
            @Suppress("ReplaceCallWithBinaryOperator")
            UnifiedReal.ONE.equals(UnifiedReal.TWO)
        }
        assertFalse(UnifiedReal.ONE.equals("1"))
    }

    @Test
    fun divisionByZero() {
        assertThrows(UnifiedReal.ZeroDivisionException::class.java) { UnifiedReal.ONE / UnifiedReal.ZERO }
        assertThrows(UnifiedReal.ZeroDivisionException::class.java) { UnifiedReal.ZERO.inverse() }
        assertThrows(UnifiedReal.ZeroDivisionException::class.java) {
            UnifiedReal.PI / (UnifiedReal.PI - UnifiedReal.PI)
        }
    }

    @Test
    fun digitBounds() {
        assertEquals(3, UnifiedReal(BoundedRational(1, 8)).digitsRequired())
        assertEquals(Int.MAX_VALUE, UnifiedReal.PI.digitsRequired())
        assertEquals(Int.MAX_VALUE, ur(1, 3).digitsRequired())
        assertEquals(0, ur(8).leadingBinaryZeroes())
        assertEquals(13, ur(1, 1024).leadingBinaryZeroes())
        assertEquals(Int.MAX_VALUE, UnifiedReal.ZERO.leadingBinaryZeroes())
        assertEquals(Int.MAX_VALUE, (UnifiedReal.PI + UnifiedReal.E).leadingBinaryZeroes())
        assertTrue(ur(1024).approxWholeNumberBitsGreaterThan(5))
        assertFalse(ur(1024).approxWholeNumberBitsGreaterThan(10))
        assertTrue((UnifiedReal.PI + UnifiedReal.E).approxWholeNumberBitsGreaterThan(1))
        assertFalse((UnifiedReal.PI + UnifiedReal.E).approxWholeNumberBitsGreaterThan(10))
        assertNull(UnifiedReal.PI.boundedRationalValue())
        assertNull(UnifiedReal.PI.bigIntegerValue())
    }

    @Test
    fun niceStringsForNamedConstants() {
        assertEquals("π", UnifiedReal.PI.toNiceString())
        assertEquals("e", UnifiedReal.E.toNiceString())
        assertEquals("2π", (UnifiedReal.PI * ur(2)).toNiceString())
        assertEquals("(1/2)π", (UnifiedReal.PI / ur(2)).toNiceString())
        assertEquals("-1√3", (-ur(3).sqrt()).toNiceString())
        assertEquals("0", (UnifiedReal.PI * UnifiedReal.ZERO).toNiceString())
    }
}
