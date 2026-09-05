/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger

/**
 * Tests for expression editing, evaluation and (de)serialization.
 *
 * Expressions are described with a tiny test-only notation, see [tokenize].
 */
class CalculatorExprTest {

    /** In-memory [CalculatorExpr.ExprResolver] standing in for the Evaluator's database. */
    private class FakeResolver : CalculatorExpr.ExprResolver {
        val exprs = HashMap<Long, CalculatorExpr>()
        val degreeModes = HashMap<Long, Boolean>()
        val results = HashMap<Long, UnifiedReal>()

        override fun getExpr(index: Long): CalculatorExpr = exprs.getValue(index)

        override fun getDegreeMode(index: Long): Boolean = degreeModes[index] ?: false

        override fun getResult(index: Long): UnifiedReal? = results[index]

        override fun putResultIfAbsent(index: Long, result: UnifiedReal): UnifiedReal {
            return results.getOrPut(index) { result }
        }
    }

    /**
     * Map a compact test notation to button ids: digits and '.' as themselves, the usual
     * binary operators, parentheses, '!' factorial, '%' percent, 'q' squared, 'r' sqrt,
     * 'p' pi, 'e' e, and 's' 'c' 't' 'l' 'L' 'E' 'S' 'C' 'T' for the functions
     * sin cos tan ln log exp arcsin arccos arctan.
     *
     * Note that, exactly like the calculator buttons, a function token already contains its
     * opening parenthesis: "s90)" is sin(90), while "s(90)" is sin((90)).
     */
    private fun tokenize(s: String): List<Int> = s.map { c ->
        when (c) {
            in '0'..'9' -> KeyMaps.keyForDigVal(c - '0')
            '.' -> R.id.dec_point
            '+' -> R.id.op_add
            '-' -> R.id.op_sub
            '*' -> R.id.op_mul
            '/' -> R.id.op_div
            '^' -> R.id.op_pow
            '(' -> R.id.lparen
            ')' -> R.id.rparen
            '!' -> R.id.op_fact
            '%' -> R.id.op_pct
            'q' -> R.id.op_sqr
            'r' -> R.id.op_sqrt
            'p' -> R.id.const_pi
            'e' -> R.id.const_e
            's' -> R.id.fun_sin
            'c' -> R.id.fun_cos
            't' -> R.id.fun_tan
            'l' -> R.id.fun_ln
            'L' -> R.id.fun_log
            'E' -> R.id.fun_exp
            'S' -> R.id.fun_arcsin
            'C' -> R.id.fun_arccos
            'T' -> R.id.fun_arctan
            else -> throw IllegalArgumentException("Unknown test token '$c'")
        }
    }

    private fun expr(s: String): CalculatorExpr {
        val e = CalculatorExpr()
        for (id in tokenize(s)) {
            assertTrue("add() rejected token in \"$s\"", e.add(id))
        }
        return e
    }

    private fun eval(s: String, degrees: Boolean = false): UnifiedReal {
        return expr(s).eval(degrees, FakeResolver())
    }

    private fun assertEvaluatesTo(expected: UnifiedReal, s: String, degrees: Boolean = false) {
        val actual = eval(s, degrees)
        assertTrue("\"$s\" evaluated to $actual, expected $expected", actual.definitelyEquals(expected))
    }

    private fun assertEvaluatesTo(expected: Long, s: String, degrees: Boolean = false) {
        assertEvaluatesTo(UnifiedReal(expected), s, degrees)
    }

    private fun roundTrip(e: CalculatorExpr): CalculatorExpr {
        return CalculatorExpr(DataInputStream(ByteArrayInputStream(e.toBytes())))
    }

    @Test
    fun basicArithmeticAndPrecedence() {
        assertEvaluatesTo(36, "12*3")
        assertEvaluatesTo(7, "1+2*3")
        assertEvaluatesTo(9, "(1+2)*3")
        assertEvaluatesTo(-6, "-3*2")
        assertEvaluatesTo(-1, "2-3")
        assertEvaluatesTo(UnifiedReal(BoundedRational(7, 3)), "7/3")
        assertEquals("2.3333333333333333333", eval("7/3").toStringTruncated(19))
        assertEquals(BigInteger.ONE.shiftLeft(100), eval("2^100").bigIntegerValue())
        assertEvaluatesTo(512, "2^3^2") // right associative
        assertEvaluatesTo(UnifiedReal(BoundedRational(3, 2)), "1.5")
        assertEvaluatesTo(UnifiedReal(BoundedRational(1, 2)), ".5")
        assertEvaluatesTo(1000, "1000")
    }

    @Test
    fun juxtapositionMeansMultiplication() {
        assertEvaluatesTo(6, "2(3)")
        assertEvaluatesTo(6, "(2)(3)")
        assertEvaluatesTo(UnifiedReal.PI * UnifiedReal(2), "2p")
        assertEvaluatesTo(2, "(1)(1)2")
    }

    @Test
    fun suffixOperators() {
        assertEvaluatesTo(120, "5!")
        assertEvaluatesTo(9, "3q")
        assertEvaluatesTo(81, "3qq")
        assertEvaluatesTo(UnifiedReal(BoundedRational(1, 2)), "50%")
        assertEvaluatesTo(UnifiedReal(BoundedRational(1, 200)), "50%%")
        assertEvaluatesTo(-720, "-6!")
    }

    @Test
    fun percentAfterAdditiveOperatorIsRelative() {
        assertEvaluatesTo(220, "200+10%")
        assertEvaluatesTo(180, "200-10%")
        assertEvaluatesTo(UnifiedReal(BoundedRational(1001, 10)), "100+(10)%")
        assertEvaluatesTo(UnifiedReal(BoundedRational(1001, 10)), "100+10%*1")
        assertEvaluatesTo(242, "200+10%+10%")
        assertEvaluatesTo(220, "(200+10%)")
    }

    @Test
    fun squareRootsAndConstants() {
        assertEvaluatesTo(4, "r2*r8")
        assertEvaluatesTo(3, "r9")
        assertEvaluatesTo(UnifiedReal(2).sqrt(), "r-(-2)")
        assertEvaluatesTo(UnifiedReal.PI, "p")
        assertEvaluatesTo(UnifiedReal.E, "e")
        assertEvaluatesTo(1, "le)")
        assertEvaluatesTo(2, "L100)")
        assertEvaluatesTo(UnifiedReal.E, "E1)")
        assertEvaluatesTo(3, "l8)/l2)")
        // The function's own parenthesis is still open here, so this is ln(8 / ln(2)).
        assertEquals("2.4459", eval("l(8)/l(2)").toStringTruncated(4))
    }

    @Test
    fun trigonometryHonoursDegreeMode() {
        assertEvaluatesTo(1, "s(90)", degrees = true)
        assertEvaluatesTo(-1, "c(180)", degrees = true)
        assertEvaluatesTo(1, "t(45)", degrees = true)
        assertEvaluatesTo(90, "S(1)", degrees = true)
        assertEvaluatesTo(60, "C(0.5)", degrees = true)
        assertEvaluatesTo(45, "T(1)", degrees = true)
        assertEvaluatesTo(1, "s(p/2)")
        assertEvaluatesTo(-1, "c(p)")
        assertEvaluatesTo(UnifiedReal.PI / UnifiedReal(2), "S(1)")
        // Missing closing parentheses are tolerated at the end of an expression.
        assertEvaluatesTo(1, "s(90", degrees = true)
        assertEvaluatesTo(1, "s(90))", degrees = true)
    }

    @Test
    fun trailingBinaryOperatorsAreIgnoredWhenEvaluating() {
        val e = expr("2+")
        assertTrue(e.hasTrailingBinary())
        assertFalse(e.hasInterestingOps())
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(2)))
        assertEvaluatesTo(5, "2+3*")
        assertEvaluatesTo(5, "2+3*/^")
    }

    @Test
    fun interestingOps() {
        assertFalse(expr("").hasInterestingOps())
        assertFalse(expr("42").hasInterestingOps())
        assertFalse(expr("-2").hasInterestingOps())
        assertTrue(expr("1+2").hasInterestingOps())
        assertTrue(expr("(2)").hasInterestingOps())
        assertTrue(expr("2!").hasInterestingOps())
        assertTrue(expr("p").hasInterestingOps())
        assertTrue(expr("3+4-").hasInterestingOps())
        assertTrue(expr("s(1)").hasTrigFuncs())
        assertFalse(expr("l(1)").hasTrigFuncs())
    }

    @Test
    fun addRejectsOrRepairsObviousSyntaxErrors() {
        val e = CalculatorExpr()
        assertFalse(e.add(R.id.op_mul)) // Nothing to multiply.
        assertFalse(e.add(R.id.op_add))
        assertTrue(e.add(R.id.op_sub)) // Unary minus is fine.
        assertTrue(e.add(R.id.digit_2))
        assertTrue(e.add(R.id.dec_point))
        assertFalse(e.add(R.id.dec_point)) // Second decimal point in the same constant.
        assertTrue(e.add(R.id.digit_5))
        assertTrue(e.add(R.id.op_mul))
        assertTrue(e.add(R.id.op_div)) // Quietly replaces the trailing operator.
        assertTrue(e.hasTrailingBinary())
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(BoundedRational(-5, 2))))
        assertTrue(e.add(R.id.digit_2))
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(BoundedRational(-5, 4))))

        val f = expr("2*")
        assertTrue(f.add(R.id.op_add)) // "2*" becomes "2+".
        assertTrue(f.add(R.id.digit_3))
        assertTrue(f.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(5)))

        val g = expr("2*")
        assertTrue(g.add(R.id.op_sub)) // "2*-" is allowed: unary minus.
        assertTrue(g.add(R.id.digit_3))
        assertTrue(g.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(-6)))

        val h = expr("(")
        assertFalse(h.add(R.id.op_mul))
        val i = expr("s")
        assertFalse(i.add(R.id.op_pow))
        val j = expr("r")
        assertFalse(j.add(R.id.op_add))
    }

    @Test
    fun deleteRemovesOneKeyPressAtATime() {
        val e = expr("12+3")
        e.delete()
        assertTrue(e.hasTrailingBinary())
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(12)))
        e.delete()
        assertTrue(e.hasTrailingConstant())
        e.delete()
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(1)))
        e.delete()
        assertTrue(e.isEmpty())
        e.delete() // No-op on an empty expression.
        assertTrue(e.isEmpty())

        val f = expr("1.")
        f.delete()
        assertTrue(f.isConstant())
        assertTrue(f.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(1)))

        val g = expr("1+-")
        g.removeTrailingAdditiveOperators()
        assertTrue(g.isConstant())
        val h = expr("1*")
        h.removeTrailingAdditiveOperators()
        assertTrue(h.hasTrailingBinary())
    }

    @Test
    fun parenthesisQueries() {
        val open = expr("(1+2")
        assertTrue(open.hasOpenParentheses())
        assertFalse(open.hasTrailingLeftParen())
        assertTrue(open.hasTrailingConstant())
        assertFalse(open.hasTrailingRightParen())

        val closed = expr("(1+2)")
        assertFalse(closed.hasOpenParentheses())
        assertTrue(closed.hasTrailingRightParen())
        assertFalse(closed.hasTrailingConstant())

        assertTrue(expr("s").hasTrailingLeftParen())
        assertTrue(expr("s").hasOpenParentheses())
        assertTrue(expr("2!").hasTrailingSuffix())
        assertTrue(expr("p").hasTrailingConstant())
        assertFalse(expr("").hasTrailingConstant())
        assertTrue(expr("(").hasTrailingLeftParen())
    }

    @Test
    fun clearAndCopy() {
        val e = expr("12")
        val copy = e.copy()
        assertTrue(copy.add(R.id.digit_3))
        // The copy is deep: mutating the copied constant leaves the original alone.
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(12)))
        assertTrue(copy.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(123)))
        e.clear()
        assertTrue(e.isEmpty())
        assertFalse(e.isConstant())
    }

    @Test
    fun appendInsertsExplicitMultiplicationBetweenConstants() {
        val e = expr("12")
        e.append(expr("3"))
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(36)))

        // No multiplication is inserted next to an operator.
        val f = expr("12+")
        f.append(expr("3"))
        assertTrue(f.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(15)))
        val h = expr("12")
        h.append(expr("(3)"))
        assertTrue(h.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(36)))

        val g = CalculatorExpr()
        g.append(expr("7"))
        assertTrue(g.isConstant())
    }

    @Test
    fun exponentsOnConstants() {
        val e = expr("1.5")
        e.addExponent(3)
        assertTrue(e.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(1500)))
        val f = expr("25")
        f.addExponent(-2)
        assertTrue(f.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(BoundedRational(1, 4))))
        // Digits typed after an exponent extend the exponent.
        assertTrue(f.add(R.id.digit_1)) // 25E-2 becomes 25E-21.
        val expected = BoundedRational(BigInteger.ONE, BigInteger.valueOf(4).multiply(BigInteger.TEN.pow(19)))
        assertTrue(f.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(expected)))
        assertFalse(f.add(R.id.dec_point))
        // Deleting removes exponent digits first.
        f.delete()
        assertTrue(f.eval(false, FakeResolver()).definitelyEquals(UnifiedReal(BoundedRational(1, 4))))
        // Serialization keeps the exponent.
        assertTrue(roundTrip(e).eval(false, FakeResolver()).definitelyEquals(UnifiedReal(1500)))
    }

    @Test
    fun syntaxErrors() {
        assertThrows(CalculatorExpr.SyntaxException::class.java) {
            CalculatorExpr().eval(false, FakeResolver())
        }
        assertThrows(CalculatorExpr.SyntaxException::class.java) { eval("(") }
        assertThrows(CalculatorExpr.SyntaxException::class.java) { eval(".") }
        assertThrows(CalculatorExpr.SyntaxException::class.java) { eval("2)") }
        assertThrows(CalculatorExpr.SyntaxException::class.java) { eval("s") }
        assertThrows(CalculatorExpr.SyntaxException::class.java) { eval("2*(") }
        assertThrows(UnifiedReal.ZeroDivisionException::class.java) { eval("1/0") }
        assertThrows(ArithmeticException::class.java) { eval("r-1") }
        assertThrows(ArithmeticException::class.java) { eval("l(0)") }
        assertThrows(ArithmeticException::class.java) { eval("2.5!") }
    }

    @Test
    fun serializationRoundTrip() {
        val original = expr("1.5+2^3*r9-p/e+s(30)!%")
        val restored = roundTrip(original)
        assertEquals(
            original.eval(true, FakeResolver()).toStringTruncated(30),
            restored.eval(true, FakeResolver()).toStringTruncated(30)
        )
        assertArrayEquals(original.toBytes(), restored.toBytes())
        assertTrue(roundTrip(CalculatorExpr()).isEmpty())
    }

    @Test
    fun serializedFormatIsStable() {
        // The database stores these bytes, so the layout must never change:
        // token count, then per token either a kind byte (< 0x20) followed by the token
        // data, or a single printable byte naming an operator.
        val expected = byteArrayOf(
            0, 0, 0, 3, // three tokens
            0, 0, 1, '1'.code.toByte(), 1, 0, 1, '5'.code.toByte(), // constant "1", decimal, "5"
            '+'.code.toByte(), // operator
            0, 0, 1, '2'.code.toByte(), 0 // constant "2", no decimal point
        )
        assertArrayEquals(expected, expr("1.5+2").toBytes())

        val withExponent = expr("3")
        withExponent.addExponent(-7)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0, 0, 1, '3'.code.toByte(), 2, -1, -1, -1, -7),
            withExponent.toBytes()
        )
    }

    @Test
    fun preEvaluatedSubexpressionsAreResolvedThroughTheResolver() {
        val resolver = FakeResolver()
        resolver.exprs[5] = expr("2^10")
        val main = CalculatorExpr().abbreviate(5, "1024")
        assertTrue(main.add(R.id.op_add))
        assertTrue(main.add(R.id.digit_1))
        assertTrue(main.hasInterestingOps())
        assertTrue(main.eval(false, resolver).definitelyEquals(UnifiedReal(1025)))
        // The nested evaluation was cached through putResultIfAbsent.
        assertTrue(resolver.results.getValue(5).definitelyEquals(UnifiedReal(1024)))

        // Appending a constant to a pre-evaluated token inserts an explicit multiplication.
        val product = CalculatorExpr().abbreviate(5, "1024")
        product.append(expr("2"))
        assertTrue(product.eval(false, resolver).definitelyEquals(UnifiedReal(2048)))
        assertTrue(product.add(R.id.digit_3)) // "1024 × 2" then "3" extends the constant.
        assertTrue(product.eval(false, resolver).definitelyEquals(UnifiedReal(1024 * 23)))

        // Degree mode of the referenced expression is taken from the resolver, not the caller.
        resolver.exprs[6] = expr("s(90)")
        resolver.degreeModes[6] = true
        val nested = CalculatorExpr().abbreviate(6, "1")
        assertTrue(nested.eval(false, resolver).definitelyEquals(UnifiedReal.ONE))

        // Pre-evaluated tokens survive serialization.
        val restored = roundTrip(main)
        assertTrue(restored.eval(false, resolver).definitelyEquals(UnifiedReal(1025)))
    }

    @Test
    fun preEvaluatedTokensWithEllipsisAreInteresting() {
        assertFalse(CalculatorExpr().abbreviate(5, "1024").hasInterestingOps())
        assertTrue(CalculatorExpr().abbreviate(5, "3.14159" + KeyMaps.ELLIPSIS).hasInterestingOps())
        assertFalse(CalculatorExpr().abbreviate(5, "1024").isConstant())
        assertFalse(CalculatorExpr().abbreviate(5, "1024").hasTrailingConstant())
    }

    @Test
    fun transitivelyReferencedExpressionsAreOrderedForEvaluation() {
        val resolver = FakeResolver()
        resolver.exprs[5] = expr("2+3")
        val seven = CalculatorExpr().abbreviate(5, "5")
        assertTrue(seven.add(R.id.op_mul))
        assertTrue(seven.add(R.id.digit_2))
        resolver.exprs[7] = seven
        val main = CalculatorExpr().abbreviate(7, "10")
        assertTrue(main.add(R.id.op_add))
        assertTrue(main.add(R.id.digit_1))
        // Dependencies come first so that evaluation never needs to recurse.
        assertEquals(listOf(5L, 7L), main.getTransitivelyReferencedExprs(resolver))
        assertTrue(main.eval(false, resolver).definitelyEquals(UnifiedReal(11)))
        assertTrue(resolver.results.getValue(7).definitelyEquals(UnifiedReal(10)))
        // Already evaluated expressions are not listed again.
        assertEquals(emptyList<Long>(), main.getTransitivelyReferencedExprs(resolver))
    }

    @Test
    fun corruptedPreEvalIndexIsReplacedByPlaceholder() {
        val bytes = CalculatorExpr().abbreviate(-1, "bad").toBytes()
        val restored = CalculatorExpr(DataInputStream(ByteArrayInputStream(bytes)))
        assertTrue(restored.isConstant())
        assertThrows(CalculatorExpr.SyntaxException::class.java) {
            restored.eval(false, FakeResolver())
        }
    }

    // ---- Editing at a position ----

    private fun pos(token: Int, offset: Int = 0) = CalculatorExpr.Position(token, offset)

    private fun assertValue(expected: Long, e: CalculatorExpr) {
        val actual = e.eval(false, FakeResolver())
        assertTrue("expected $expected, got $actual", UnifiedReal(expected).definitelyEquals(actual))
    }

    @Test
    fun insertsDigitsAtPositions() {
        val e = expr("12+34")
        assertEquals(pos(0, 2), e.insert(KeyMaps.keyForDigVal(5), pos(0, 1))) // 152+34
        assertValue(186, e)
        assertEquals(pos(2, 1), e.insert(KeyMaps.keyForDigVal(5), pos(2))) // 152+534: before a constant
        assertValue(686, e)
        assertEquals(pos(3), e.insert(KeyMaps.keyForDigVal(5), pos(3))) // 152+5345: after a constant
        assertValue(5497, e)
        assertEquals(pos(0, 1), e.insert(KeyMaps.keyForDigVal(9), pos(0))) // 9152+5345
        assertValue(14497, e)
    }

    @Test
    fun anOperatorSplitsTheConstantItIsInsertedInto() {
        val e = expr("1234")
        assertEquals(pos(2), e.insert(R.id.op_add, pos(0, 2))) // 12+34
        assertValue(46, e)
        assertTrue(e.hasConstantBefore(pos(1)))
        assertTrue(e.hasBinaryBefore(pos(2)))
    }

    @Test
    fun insertingFollowsTheAppendingRules() {
        val e = expr("12")
        assertNull(e.insert(R.id.op_mul, pos(0))) // Nothing to multiply.
        assertValue(12, e)
        val f = expr("2*")
        assertEquals(pos(2), f.insert(R.id.op_add, pos(2))) // "2*" becomes "2+".
        assertEquals(pos(3), f.insert(KeyMaps.keyForDigVal(3), pos(2)))
        assertValue(5, f)
        assertNull(expr("(").insert(R.id.op_add, pos(1)))
    }

    @Test
    fun deletesBeforeAPosition() {
        val e = expr("123")
        assertEquals(pos(0, 1), e.deleteBefore(pos(0, 2))) // 13
        assertValue(13, e)
        assertEquals(pos(0), e.deleteBefore(pos(0))) // Nothing before the start.
        assertValue(13, e)
        val f = expr("12+34")
        assertEquals(pos(0, 2), f.deleteBefore(pos(2))) // The constants join up: 1234.
        assertValue(1234, f)
        val g = expr("12+s")
        assertEquals(pos(2), g.deleteBefore(pos(3))) // A function goes as a whole.
        assertTrue(g.hasBinaryBefore(pos(2)))
    }

    @Test
    fun deletesARange() {
        val e = expr("12+34")
        assertEquals(pos(0, 1), e.deleteRange(pos(0, 1), pos(2, 1))) // 14
        assertValue(14, e)
        val f = expr("1+2")
        assertEquals(pos(0), f.deleteRange(pos(0), pos(3)))
        assertTrue(f.isEmpty())
    }

    @Test
    fun removesAdditiveOperatorsBeforeAPosition() {
        val e = expr("1+-")
        assertEquals(pos(1), e.removeAdditiveOperatorsBefore(pos(3)))
        assertFalse(e.hasBinaryBefore(pos(1)))
        assertValue(1, e)
    }

    @Test
    fun insertsExpressionsWithExplicitMultiplication() {
        val e = expr("2")
        assertEquals(pos(1), e.insertExpr(expr("3"), pos(0))) // 3×2, the cursor before the ×
        assertValue(6, e)
        val f = expr("2+")
        assertEquals(pos(3), f.insertExpr(expr("3"), pos(2))) // 2+3
        assertValue(5, f)
    }

    @Test
    fun answersAboutWhatPrecedesAPosition() {
        val e = expr("1+(2")
        assertTrue(e.hasBinaryBefore(pos(2)))
        assertFalse(e.hasOpenParenthesesBefore(pos(2)))
        assertTrue(e.hasLeftParenBefore(pos(3)))
        assertTrue(e.hasOpenParenthesesBefore(pos(4)))
        assertTrue(e.hasConstantBefore(pos(4)))
        assertFalse(e.hasRightParenBefore(pos(4)))
    }
}
