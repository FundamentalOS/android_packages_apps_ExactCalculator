/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for the display-formatting helpers of the Evaluator that do not need Android.
 */
class EvaluatorTest {

    /** Metrics for a display that fits exactly [maxChars] digit widths and nothing else. */
    private class FixedMetrics(
        private val maxChars: Int,
        private val separatorWidth: Float = 0f
    ) : Evaluator.CharMetricsInfo {
        override fun getMaxChars(): Int = maxChars

        override fun separatorChars(s: String, len: Int): Float {
            var start = 0
            while (start < len && !s[start].isDigit()) ++start
            return ((len - start - 1) / 3) * separatorWidth
        }

        override fun getDecimalCredit(): Float = 0f

        override fun getNoEllipsisCredit(): Float = 0f
    }

    private val pi = "3.14159265358979323846264338327950288419716939937510582097494459230781640628"

    @Test
    fun unflipZeroes() {
        // The old approximation ended in 9s and the new one flipped them to zeroes: keep the 9s.
        assertEquals("3.149999", Evaluator.unflipZeroes("3.1499", 4, "3.150000", 6))
        // No trailing 9s: the new digits are used as is.
        assertEquals("3.141592", Evaluator.unflipZeroes("3.1415", 4, "3.141592", 6))
        // Trailing 9 but the new digits are not a flip.
        assertEquals("3.149912", Evaluator.unflipZeroes("3.1499", 4, "3.149912", 6))
        assertEquals("0.999999", Evaluator.unflipZeroes("0.9999", 4, "1.000000", 6))
        // The digit that corresponds to the old last digit did not flip: nothing to repair.
        assertEquals("0.999900", Evaluator.unflipZeroes("0.9999", 4, "0.999900", 6))
        // A "flip" followed by nonzero digits contradicts the old approximation.
        assertThrows(AssertionError::class.java) {
            Evaluator.unflipZeroes("1.99", 2, "1.9012", 4)
        }
    }

    @Test
    fun mostSignificantDigit() {
        assertEquals(4, Evaluator.getMsdIndexOf("0.00123"))
        assertEquals(3, Evaluator.getMsdIndexOf("-0.5"))
        assertEquals(0, Evaluator.getMsdIndexOf("100."))
        assertEquals(1, Evaluator.getMsdIndexOf("-100.0"))
        // Not enough digits to prove the value is nonzero.
        assertEquals(Evaluator.INVALID_MSD, Evaluator.getMsdIndexOf("0.000"))
        // A trailing 1 could still be rounding noise.
        assertEquals(Evaluator.INVALID_MSD, Evaluator.getMsdIndexOf("0.001"))
        assertEquals(4, Evaluator.getMsdIndexOf("0.0012"))
        assertEquals(4, Evaluator.getMsdIndexOf("0.002"))
        // CalculatorResult's naive variant treats the trailing 1 as significant.
        assertEquals(4, CalculatorResult.getNaiveMsdIndexOf("0.001"))
        assertEquals(Evaluator.INVALID_MSD, CalculatorResult.getNaiveMsdIndexOf("-0.000"))
    }

    @Test
    fun leastSignificantDigit() {
        assertEquals(Int.MIN_VALUE, Evaluator.getLsdOffset(UnifiedReal.ZERO, "0.000", 1))
        assertEquals(3, Evaluator.getLsdOffset(UnifiedReal(BoundedRational(1, 8)), "0.125000", 1))
        assertEquals(Int.MAX_VALUE, Evaluator.getLsdOffset(UnifiedReal.PI, pi, 1))
        assertEquals(
            Int.MAX_VALUE,
            Evaluator.getLsdOffset(UnifiedReal(BoundedRational(1, 3)), "0.3333", 1)
        )
        // Integers: the offset points at the last nonzero digit left of the decimal point.
        assertEquals(-3, Evaluator.getLsdOffset(UnifiedReal(1200), "1200.00", 4))
        assertEquals(-1, Evaluator.getLsdOffset(UnifiedReal(1234), "1234.00", 4))
        assertEquals(-1, Evaluator.getLsdOffset(UnifiedReal(-7), "-7.00", 2))
    }

    @Test
    fun shortStringsForFormulas() {
        val big = "1267650600228229401496703205376.0000000000000000000000000000000000000000000000000"
        assertEquals("36", Evaluator.getShortString("36.000000000000000000", 0, -1))
        assertEquals("-36", Evaluator.getShortString("-36.000000000000000000", 1, -1))
        assertEquals("1,200", Evaluator.getShortString("1200.000000000000000000", 0, -3))
        assertEquals("0.125", Evaluator.getShortString("0.125000000000000000000", 2, 3))
        assertEquals("3.14159…", Evaluator.getShortString(pi, 0, Int.MAX_VALUE))
        // The minus sign counts towards the target length.
        assertEquals("-3.1415…", Evaluator.getShortString("-$pi", 1, Int.MAX_VALUE))
        assertEquals("1.267…E30", Evaluator.getShortString(big, 0, -1))
        assertEquals("0.00012…", Evaluator.getShortString("0.000123456789012345678901234567890", 5, Int.MAX_VALUE))
        assertEquals("1.23E-7", Evaluator.getShortString("0.000000123000000000000000000000000", 8, 9))
        assertEquals("1.234…E-7", Evaluator.getShortString("0.000000123456789012345678901234567", 8, Int.MAX_VALUE))
        // Values not yet known to be nonzero.
        assertEquals("0", Evaluator.getShortString("0.00000000000000000000", Evaluator.INVALID_MSD, -1))
        assertEquals(
            "0.00000…",
            Evaluator.getShortString("0.00000000000000000000", Evaluator.INVALID_MSD, Int.MAX_VALUE)
        )
        // Too few digits after the most significant one to be sure of anything.
        assertEquals("0.00000…", Evaluator.getShortString("0.000000000000000001", 18, Int.MAX_VALUE))
    }

    @Test
    fun preferredPrecision() {
        val ten = FixedMetrics(10)
        // Exact integer that fits: no decimal point.
        assertEquals(-1, Evaluator.getPreferredPrec("36.00000000000000000", 0, -1, ten))
        // Exact fraction that fits: show all of it.
        assertEquals(2, Evaluator.getPreferredPrec("1.25000000000000000", 0, 2, ten))
        // Nonterminating fraction: fill the display.
        assertEquals(8, Evaluator.getPreferredPrec("0.33333333333333333333", 2, Int.MAX_VALUE, ten))
        assertEquals(7, Evaluator.getPreferredPrec("-0.3333333333333333333", 3, Int.MAX_VALUE, ten))
        // Whole part too wide: negative precision means scientific notation territory.
        assertEquals(
            -22,
            Evaluator.getPreferredPrec("1267650600228229401496703205376.00000", 0, -1, ten)
        )
        // A few leading zeroes are shown rather than switching to an exponent.
        assertEquals(8, Evaluator.getPreferredPrec("0.00012345678901234567890", 5, Int.MAX_VALUE, ten))
        // Many leading zeroes: keep the leading digit on screen (scientific notation later).
        assertEquals(20, Evaluator.getPreferredPrec("0.00000000001234567890123456789", 12, Int.MAX_VALUE, ten))
        // Possibly zero: show zeroes rather than a huge exponent.
        assertEquals(8, Evaluator.getPreferredPrec("0.0000000000000000000", Evaluator.INVALID_MSD, Int.MAX_VALUE, ten))
        // Digit separators eat into the available space.
        val withSeparators = FixedMetrics(10, separatorWidth = 1f)
        assertEquals(-1, Evaluator.getPreferredPrec("1234567.0000000000", 0, -1, withSeparators))
        // "1,234,567." versus "1234567.33" on a ten character display.
        assertEquals(0, Evaluator.getPreferredPrec("1234567.3333333333", 0, Int.MAX_VALUE, withSeparators))
        assertEquals(2, Evaluator.getPreferredPrec("1234567.3333333333", 0, Int.MAX_VALUE, ten))
    }
}
