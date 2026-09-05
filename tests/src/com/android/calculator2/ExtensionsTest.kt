/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.math.BigInteger

import kotlin.concurrent.thread

class ExtensionsTest {

    @Test
    fun addCommas() {
        assertEquals("1,234,567", "1234567".addCommas(0, 7))
        assertEquals("-1,234", "-1234".addCommas(0, 5))
        assertEquals("123", "123".addCommas(0, 3))
        assertEquals("1,000", "1000".addCommas(0, 4))
        assertEquals("", "".addCommas(0, 0))
        // Only the requested range is processed; the caller appends the rest.
        assertEquals("12,345", "12345.67".addCommas(0, 5))
        assertEquals("345", "12345.67".addCommas(2, 5))
        // Leading blanks (placeholders) are preserved.
        assertEquals("  1,234", "  1234".addCommas(0, 6))
    }

    @Test
    fun extensionIgnoringSeparators() {
        assertEquals("5", "1,234,5".extensionIgnoring("1,234", ',').toString())
        assertEquals("3", "1,2,3".extensionIgnoring("12", ',').toString())
        assertEquals("", "12".extensionIgnoring("12", ',').toString())
        assertEquals("", "1,2,".extensionIgnoring("12", ',').toString())
        assertEquals("12", "12".extensionIgnoring("", ',').toString())
        assertNull("12".extensionIgnoring("13", ','))
        assertNull("1".extensionIgnoring("12", ','))
        assertNull("".extensionIgnoring("1", ','))
    }

    @Test
    fun scaledDecimalStrings() {
        assertEquals("3.142", BigInteger.valueOf(3142).toScaledDecimalString(3))
        assertEquals("0.005", BigInteger.valueOf(5).toScaledDecimalString(3))
        assertEquals("0.0", BigInteger.ZERO.toScaledDecimalString(1))
        assertEquals("42.", BigInteger.valueOf(42).toScaledDecimalString(0))
        assertEquals("0.", BigInteger.ZERO.toScaledDecimalString(0))
    }

    @Test
    fun bigIntegerPredicates() {
        assertTrue(BigInteger.ONE.isOne)
        assertFalse(BigInteger.TEN.isOne)
        assertTrue(BigInteger.valueOf(7).isOdd)
        assertFalse(BigInteger.valueOf(8).isOdd)
        assertTrue(BigInteger.valueOf(-3).isOdd)
    }

    @Test
    fun nullableRationalOperators() {
        val third: BoundedRational? = BoundedRational.THIRD
        val none: BoundedRational? = null
        assertEquals(BoundedRational(2, 3), third + third)
        assertEquals(BoundedRational(1, 9), third * third)
        assertEquals(BoundedRational.ONE, third / third)
        assertEquals(BoundedRational(-1, 3), -third)
        assertNull(third + none)
        assertNull(none * third)
        assertNull(none / third)
        assertNull(-none)
    }

    @Test
    fun waitUntilDefersInterrupts() {
        val lock = Object()
        var ready = false
        val waiter = thread {
            synchronized(lock) { lock.waitUntil { ready } }
        }
        // Interrupting the waiter must not wake it up early...
        waiter.interrupt()
        Thread.sleep(50)
        assertTrue(waiter.isAlive)
        // ...but the interrupt is re-asserted once the condition holds.
        synchronized(lock) {
            ready = true
            lock.notifyAll()
        }
        waiter.join(5000)
        assertFalse(waiter.isAlive)
    }

    @Test
    fun waitUntilReturnsImmediatelyWhenConditionHolds() {
        val lock = Object()
        synchronized(lock) { lock.waitUntil { true } }
    }
}
