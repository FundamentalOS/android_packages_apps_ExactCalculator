/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.view.View

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure key-id mapping functions (the locale dependent ones need an Activity).
 */
class KeyMapsTest {

    private val operators = listOf(
        R.id.const_pi, R.id.const_e, R.id.op_sqrt, R.id.op_fact, R.id.op_pct,
        R.id.fun_sin, R.id.fun_cos, R.id.fun_tan, R.id.fun_arcsin, R.id.fun_arccos,
        R.id.fun_arctan, R.id.fun_ln, R.id.fun_log, R.id.fun_exp, R.id.lparen, R.id.rparen,
        R.id.op_pow, R.id.op_mul, R.id.op_div, R.id.op_add, R.id.op_sub, R.id.op_sqr
    )

    @Test
    fun byteEncodingRoundTripsAndIsPrintable() {
        val seen = HashSet<Byte>()
        for (id in operators) {
            val b = KeyMaps.toByte(id)
            assertTrue("byte for $id must be >= 0x20", b >= 0x20)
            assertTrue("byte for $id must be < 0x7f", b < 0x7f)
            assertTrue("duplicate encoding $b", seen.add(b))
            assertEquals(id, KeyMaps.fromByte(b))
        }
    }

    @Test
    fun byteEncodingIsStable() {
        // These values are stored in the history database and must never change.
        assertEquals('p'.code.toByte(), KeyMaps.toByte(R.id.const_pi))
        assertEquals('e'.code.toByte(), KeyMaps.toByte(R.id.const_e))
        assertEquals('r'.code.toByte(), KeyMaps.toByte(R.id.op_sqrt))
        assertEquals('!'.code.toByte(), KeyMaps.toByte(R.id.op_fact))
        assertEquals('%'.code.toByte(), KeyMaps.toByte(R.id.op_pct))
        assertEquals('s'.code.toByte(), KeyMaps.toByte(R.id.fun_sin))
        assertEquals('S'.code.toByte(), KeyMaps.toByte(R.id.fun_arcsin))
        assertEquals('l'.code.toByte(), KeyMaps.toByte(R.id.fun_ln))
        assertEquals('L'.code.toByte(), KeyMaps.toByte(R.id.fun_log))
        assertEquals('E'.code.toByte(), KeyMaps.toByte(R.id.fun_exp))
        assertEquals('^'.code.toByte(), KeyMaps.toByte(R.id.op_pow))
        assertEquals('2'.code.toByte(), KeyMaps.toByte(R.id.op_sqr))
        assertEquals(R.id.op_mul, KeyMaps.fromByte('*'.code.toByte()))
    }

    @Test
    fun unknownEncodingsAreRejected() {
        assertThrows(AssertionError::class.java) { KeyMaps.toByte(R.id.digit_1) }
        assertThrows(AssertionError::class.java) { KeyMaps.toByte(R.id.dec_point) }
        assertThrows(AssertionError::class.java) { KeyMaps.fromByte('x'.code.toByte()) }
        assertThrows(AssertionError::class.java) { KeyMaps.fromByte(0) }
    }

    @Test
    fun digitMapping() {
        for (d in 0..9) {
            val id = KeyMaps.keyForDigVal(d)
            assertEquals(d, KeyMaps.digVal(id))
        }
        assertEquals(View.NO_ID, KeyMaps.keyForDigVal(10))
        assertEquals(View.NO_ID, KeyMaps.keyForDigVal(-1))
        assertEquals(KeyMaps.NOT_DIGIT, KeyMaps.digVal(R.id.op_add))
        assertEquals(KeyMaps.NOT_DIGIT, KeyMaps.digVal(R.id.dec_point))
        assertEquals(KeyMaps.NOT_DIGIT, KeyMaps.digVal(View.NO_ID))
    }

    @Test
    fun operatorClassification() {
        val binary = setOf(R.id.op_pow, R.id.op_mul, R.id.op_div, R.id.op_add, R.id.op_sub)
        val trig = setOf(
            R.id.fun_sin, R.id.fun_cos, R.id.fun_tan,
            R.id.fun_arcsin, R.id.fun_arccos, R.id.fun_arctan
        )
        val funcs = trig + setOf(R.id.fun_ln, R.id.fun_log, R.id.fun_exp)
        val prefix = setOf(R.id.op_sqrt, R.id.op_sub)
        val suffix = setOf(R.id.op_fact, R.id.op_pct, R.id.op_sqr)
        for (id in operators + R.id.digit_0 + R.id.dec_point) {
            assertEquals("isBinary($id)", id in binary, KeyMaps.isBinary(id))
            assertEquals("isTrigFunc($id)", id in trig, KeyMaps.isTrigFunc(id))
            assertEquals("isFunc($id)", id in funcs, KeyMaps.isFunc(id))
            assertEquals("isPrefix($id)", id in prefix, KeyMaps.isPrefix(id))
            assertEquals("isSuffix($id)", id in suffix, KeyMaps.isSuffix(id))
        }
        assertFalse(KeyMaps.isBinary(View.NO_ID))
    }

    @Test
    fun specialCharacters() {
        assertEquals("…", KeyMaps.ELLIPSIS)
        assertEquals('−', KeyMaps.MINUS_SIGN)
        assertEquals(10, KeyMaps.NOT_DIGIT)
    }
}
