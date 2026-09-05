/*
 * SPDX-FileCopyrightText: 2015 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context
import android.util.Log
import android.view.View

import androidx.appcompat.app.AppCompatActivity

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Collection of mapping functions between key ids, characters, internationalized
 * and non-internationalized characters, etc.
 *
 * Everything here is static.
 * All functions are either pure, or are assumed to be called only from a single UI thread.
 */
object KeyMaps {
    const val NOT_DIGIT = 10

    const val ELLIPSIS = "…"

    const val MINUS_SIGN = '−'

    /**
     * Character used as a placeholder for digits that are currently unknown in a result that
     * is being computed.  We initially generate blanks, and then use this as a replacement
     * during final translation.
     *
     * Note: the character must correspond closely to the width of a digit,
     * otherwise the UI will visibly shift once the computation is finished.
     */
    private const val CHAR_DIGIT_UNKNOWN = ' '

    /** The Arabic decimal separator; results are laid out LTR, so they show a comma instead. */
    private const val RTL_COMMA = '\u066B'

    /** Digit key ids, indexed by digit value. */
    private val DIGITS = listOf(
        R.id.digit_0, R.id.digit_1, R.id.digit_2, R.id.digit_3, R.id.digit_4,
        R.id.digit_5, R.id.digit_6, R.id.digit_7, R.id.digit_8, R.id.digit_9
    )

    private val BINARY_OPS = setOf(R.id.op_pow, R.id.op_mul, R.id.op_div, R.id.op_add, R.id.op_sub)

    private val TRIG_FUNCS = setOf(
        R.id.fun_sin, R.id.fun_cos, R.id.fun_tan, R.id.fun_arcsin, R.id.fun_arccos, R.id.fun_arctan
    )

    /** Functions that introduce an implicit lparen. */
    private val FUNCS = TRIG_FUNCS + setOf(R.id.fun_ln, R.id.fun_log, R.id.fun_exp)

    private val PREFIX_OPS = setOf(R.id.op_sqrt, R.id.op_sub)

    private val SUFFIX_OPS = setOf(R.id.op_fact, R.id.op_pct, R.id.op_sqr)

    /** Display string resources for keys other than functions. */
    private val LABELS = mapOf(
        R.id.const_pi to R.string.const_pi,
        R.id.const_e to R.string.const_e,
        R.id.op_sqrt to R.string.op_sqrt,
        R.id.op_fact to R.string.op_fact,
        R.id.op_pct to R.string.op_pct,
        R.id.lparen to R.string.lparen,
        R.id.rparen to R.string.rparen,
        R.id.op_pow to R.string.op_pow,
        R.id.op_mul to R.string.op_mul,
        R.id.op_div to R.string.op_div,
        R.id.op_add to R.string.op_add,
        R.id.op_sub to R.string.op_sub,
        R.id.op_sqr to R.string.squared, // Button label doesn't work.
        R.id.dec_point to R.string.dec_point,
        R.id.digit_0 to R.string.digit_0,
        R.id.digit_1 to R.string.digit_1,
        R.id.digit_2 to R.string.digit_2,
        R.id.digit_3 to R.string.digit_3,
        R.id.digit_4 to R.string.digit_4,
        R.id.digit_5 to R.string.digit_5,
        R.id.digit_6 to R.string.digit_6,
        R.id.digit_7 to R.string.digit_7,
        R.id.digit_8 to R.string.digit_8,
        R.id.digit_9 to R.string.digit_9
    )

    /** Display string resources for functions; the display string gets an lparen appended. */
    private val FUNCTION_LABELS = mapOf(
        R.id.fun_sin to R.string.fun_sin,
        R.id.fun_cos to R.string.fun_cos,
        R.id.fun_tan to R.string.fun_tan,
        R.id.fun_arcsin to R.string.fun_arcsin,
        R.id.fun_arccos to R.string.fun_arccos,
        R.id.fun_arctan to R.string.fun_arctan,
        R.id.fun_ln to R.string.fun_ln,
        R.id.fun_log to R.string.fun_log,
        R.id.fun_exp to R.string.exponential // Button label doesn't work.
    )

    /**
     * The labels the pads show for the keys whose display strings are taken from them; they
     * match the keys resources in xml/.
     */
    private val KEY_LABELS = mapOf(
        R.id.fun_sin to R.string.fun_sin,
        R.id.fun_cos to R.string.fun_cos,
        R.id.fun_tan to R.string.fun_tan,
        R.id.fun_arcsin to R.string.fun_arcsin,
        R.id.fun_arccos to R.string.fun_arccos,
        R.id.fun_arctan to R.string.fun_arctan,
        R.id.fun_ln to R.string.fun_ln,
        R.id.fun_log to R.string.fun_log,
        R.id.fun_exp to R.string.fun_exp,
        R.id.op_sub to R.string.op_sub,
        R.id.digit_0 to R.string.digit_0,
        R.id.digit_1 to R.string.digit_1,
        R.id.digit_2 to R.string.digit_2,
        R.id.digit_3 to R.string.digit_3,
        R.id.digit_4 to R.string.digit_4,
        R.id.digit_5 to R.string.digit_5,
        R.id.digit_6 to R.string.digit_6,
        R.id.digit_7 to R.string.digit_7,
        R.id.digit_8 to R.string.digit_8,
        R.id.digit_9 to R.string.digit_9
    )

    /** TalkBack descriptions for keys other than functions. */
    private val DESCRIPTIONS = mapOf(
        R.id.op_fact to R.string.desc_op_fact,
        R.id.lparen to R.string.desc_lparen,
        R.id.rparen to R.string.desc_rparen,
        R.id.op_pow to R.string.desc_op_pow,
        R.id.dec_point to R.string.desc_dec_point
    )

    /** TalkBack descriptions for functions; the description gets an lparen appended. */
    private val FUNCTION_DESCRIPTIONS = mapOf(
        R.id.fun_sin to R.string.desc_fun_sin,
        R.id.fun_cos to R.string.desc_fun_cos,
        R.id.fun_tan to R.string.desc_fun_tan,
        R.id.fun_arcsin to R.string.desc_fun_arcsin,
        R.id.fun_arccos to R.string.desc_fun_arccos,
        R.id.fun_arctan to R.string.desc_fun_arctan,
        R.id.fun_ln to R.string.desc_fun_ln,
        R.id.fun_log to R.string.desc_fun_log,
        R.id.fun_exp to R.string.desc_fun_exp
    )

    /**
     * Single byte, somewhat human readable, encoding of each operator key, used to serialize
     * expressions in the database. We only use characters with single-byte UTF8 encodings in
     * the range 0x20-0x7F. These values must never change.
     */
    private val OPERATOR_BYTES = mapOf(
        R.id.const_pi to 'p',
        R.id.const_e to 'e',
        R.id.op_sqrt to 'r',
        R.id.op_fact to '!',
        R.id.op_pct to '%',
        R.id.fun_sin to 's',
        R.id.fun_cos to 'c',
        R.id.fun_tan to 't',
        R.id.fun_arcsin to 'S',
        R.id.fun_arccos to 'C',
        R.id.fun_arctan to 'T',
        R.id.fun_ln to 'l',
        R.id.fun_log to 'L',
        R.id.fun_exp to 'E',
        R.id.lparen to '(',
        R.id.rparen to ')',
        R.id.op_pow to '^',
        R.id.op_mul to '*',
        R.id.op_div to '/',
        R.id.op_add to '+',
        R.id.op_sub to '-',
        R.id.op_sqr to '2'
    )

    private val KEYS_FOR_BYTES = OPERATOR_BYTES.entries.associate { (id, c) -> c to id }

    // The locale's decimal separator: recognized in input from a physical keyboard and shown
    // in results, whatever label the pad's button carries.
    private var decimalPt = ' '

    // Only used for recognizing additional input characters from a physical keyboard.
    private var piChar = ' '

    /**
     * Map typed function name strings to corresponding button ids.
     * We (now redundantly?) include both localized and English names.
     */
    private var keyValForFun = HashMap<String, Int>()

    /**
     * Result string corresponding to a character in the calculator result.
     * The string values in the map are expected to be one character long.
     */
    private var outputForResultChar = HashMap<Char, String>()

    /**
     * Locale corresponding to preceding map and character constants.
     * We recompute the map if this is not the current locale.
     */
    private var localeForMaps: Locale? = null

    /**
     * Activity to use for looking up buttons.
     */
    private var activity: AppCompatActivity? = null

    /**
     * Map key id to corresponding (internationalized) display string.
     * Pure function.
     */
    fun toString(context: Context, id: Int): String =
        FUNCTION_LABELS[id]?.let { context.getString(it) + context.getString(R.string.lparen) }
            ?: LABELS[id]?.let(context::getString)
            ?: ""

    /**
     * Map key id to a single byte, somewhat human readable, description.
     * Used to serialize expressions in the database.
     * The result is in the range 0x20-0x7f.
     */
    fun toByte(id: Int): Byte =
        (OPERATOR_BYTES[id] ?: throw AssertionError("Unexpected key id")).code.toByte()

    /**
     * Map single byte encoding generated by key id generated by toByte back to
     * key id.
     */
    fun fromByte(b: Byte): Int =
        KEYS_FOR_BYTES[b.toInt().toChar()]
            ?: throw AssertionError("Unexpected single byte operator encoding")

    /**
     * Map key id to corresponding (internationalized) descriptive string that can be used
     * to correctly read back a formula.
     * Only used for operators and individual characters; not used inside constants.
     * Returns null when we don't need a descriptive string.
     * Pure function.
     */
    fun toDescriptiveString(context: Context, id: Int): String? =
        FUNCTION_DESCRIPTIONS[id]?.let {
            context.getString(it) + " " + context.getString(R.string.desc_lparen)
        } ?: DESCRIPTIONS[id]?.let(context::getString)

    /** Does a button id correspond to a binary operator? Pure function. */
    fun isBinary(id: Int) = id in BINARY_OPS

    /** Does a button id correspond to a trig function? Pure function. */
    fun isTrigFunc(id: Int) = id in TRIG_FUNCS

    /** Does a button id correspond to a function that introduces an implicit lparen? */
    fun isFunc(id: Int) = id in FUNCS

    /** Does a button id correspond to a prefix operator? Pure function. */
    fun isPrefix(id: Int) = id in PREFIX_OPS

    /** Does a button id correspond to a suffix operator? */
    fun isSuffix(id: Int) = id in SUFFIX_OPS

    /** Map key id to digit or NOT_DIGIT. Pure function. */
    fun digVal(id: Int): Int = DIGITS.indexOf(id).takeIf { it >= 0 } ?: NOT_DIGIT

    /** Map digit to corresponding key.  Inverse of above. Pure function. */
    fun keyForDigVal(v: Int): Int = DIGITS.getOrElse(v) { View.NO_ID }

    /**
     * Set activity used for looking up button labels.
     * Call only from UI thread.
     */
    fun setActivity(a: AppCompatActivity?) {
        activity = a
    }

    /**
     * Return the button id corresponding to the supplied character or return NO_ID.
     * Called only by UI thread.
     */
    fun keyForChar(c: Char): Int {
        validateMaps()
        if (c.isDigit()) return keyForDigVal(Character.digit(c, 10))
        return when (c) {
            '.', ',' -> R.id.dec_point
            '-', MINUS_SIGN -> R.id.op_sub
            '+' -> R.id.op_add
            '*', '×' /* MULTIPLICATION SIGN */ -> R.id.op_mul
            '/', '÷' /* DIVISION SIGN */ -> R.id.op_div
            // We no longer localize function names, so they can't start with an 'e' or 'p'.
            'e', 'E' -> R.id.const_e
            'p', 'P' -> R.id.const_pi
            '^' -> R.id.op_pow
            '!' -> R.id.op_fact
            '%' -> R.id.op_pct
            '(' -> R.id.lparen
            ')' -> R.id.rparen
            decimalPt -> R.id.dec_point
            // pi is not translated, but it might be typable on a Greek keyboard,
            // or pasted in, so we check ...
            piChar -> R.id.const_pi
            else -> View.NO_ID
        }
    }

    private fun requireActivity(): AppCompatActivity =
        checkNotNull(activity) { "KeyMaps.setActivity() must be called first" }

    private fun buttonLabel(buttonId: Int): String = requireActivity().getString(KEY_LABELS.getValue(buttonId))

    /**
     * Ensure that the preceding map and character constants correspond to the current locale.
     * Called only by UI thread.
     */
    private fun validateMaps() {
        val locale = Locale.getDefault()
        if (locale == localeForMaps) return
        Log.v("Calculator", "Setting locale to: " + locale.toLanguageTag())
        keyValForFun = hashMapOf(
            "sin" to R.id.fun_sin,
            "cos" to R.id.fun_cos,
            "tan" to R.id.fun_tan,
            "arcsin" to R.id.fun_arcsin,
            "arccos" to R.id.fun_arccos,
            "arctan" to R.id.fun_arctan,
            "asin" to R.id.fun_arcsin,
            "acos" to R.id.fun_arccos,
            "atan" to R.id.fun_arctan,
            "ln" to R.id.fun_ln,
            "log" to R.id.fun_log,
            "sqrt" to R.id.op_sqrt // special treatment
        )
        // Also map the (possibly localized) button labels to their ids.
        FUNCTION_LABELS.keys.forEach { keyValForFun[buttonLabel(it)] = it }

        // Set locale-dependent character "constants"
        // We recognize this in keyboard input, even if we use a different character.
        decimalPt = DecimalFormatSymbols.getInstance().decimalSeparator
        piChar = requireActivity().getString(R.string.const_pi).singleOrNull() ?: ' '

        outputForResultChar = hashMapOf(
            'e' to "E",
            'E' to "E",
            ' ' to CHAR_DIGIT_UNKNOWN.toString(),
            ELLIPSIS[0] to ELLIPSIS,
            // Translate numbers for fraction display, but not the separating slash, which
            // appears to be universal.  We also do not translate the ln, sqrt, pi
            '/' to "/",
            '(' to "(",
            ')' to ")",
            'l' to "l",
            'n' to "n",
            ',' to DecimalFormatSymbols.getInstance().groupingSeparator.toString(),
            '√' to "√", // SQUARE ROOT
            'π' to "π" // GREEK SMALL LETTER PI
        )
        outputForResultChar['-'] = buttonLabel(R.id.op_sub)
        outputForResultChar['.'] = if (decimalPt == RTL_COMMA) "," else decimalPt.toString()
        DIGITS.forEachIndexed { i, id -> outputForResultChar['0' + i] = buttonLabel(id) }

        localeForMaps = locale
    }

    /**
     * Return function button id for the substring of s starting at pos and ending with
     * the next "(".  Return NO_ID if there is none.
     * We currently check for both (possibly localized) button labels, and standard
     * English names.  (They should currently be the same, and hence this is currently redundant.)
     * Callable only from UI thread.
     */
    fun funForString(s: String, pos: Int): Int {
        validateMaps()
        val parenPos = s.indexOf('(', pos).takeIf { it != -1 } ?: return View.NO_ID
        return keyValForFun[s.substring(pos, parenPos)] ?: View.NO_ID
    }

    /**
     * Return the localization of the string s representing a numeric answer.
     * Callable only from UI thread.
     * A trailing e is treated as the mathematical constant, not an exponent.
     */
    fun translateResult(s: String): String {
        validateMaps()
        return buildString {
            s.forEachIndexed { i, c ->
                if (i < s.length - 1 || c != 'e') {
                    append(
                        outputForResultChar[c] ?: run {
                            // Should not get here.  Report if we do.
                            Log.v("Calculator", "Bad character:$c")
                            c.toString()
                        }
                    )
                }
            }
        }
    }
}
