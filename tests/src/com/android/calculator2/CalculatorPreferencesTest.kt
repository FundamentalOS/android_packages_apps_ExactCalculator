/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the mapping between DataStore preferences and the settings snapshot.
 * The keys must keep their SharedPreferences names so that old settings migrate.
 */
class CalculatorPreferencesTest {

    @Test
    fun defaultsWhenNothingIsStored() {
        assertEquals(CalculatorPreferences.Snapshot(), CalculatorPreferences.Snapshot.from(emptyPreferences()))
        assertEquals(false, CalculatorPreferences.Snapshot().degreeMode)
        assertEquals(0L, CalculatorPreferences.Snapshot().savedIndex)
        assertEquals(0L, CalculatorPreferences.Snapshot().memoryIndex)
        assertEquals("none", CalculatorPreferences.Snapshot().savedName)
    }

    @Test
    fun storedValuesAreRead() {
        val preferences = preferencesOf(
            CalculatorPreferences.DEGREE_MODE to true,
            CalculatorPreferences.SAVED_INDEX to -12L,
            CalculatorPreferences.MEMORY_INDEX to -13L,
            CalculatorPreferences.SAVED_NAME to "calculator2.android.com,2026-09-05:42"
        )
        assertEquals(
            CalculatorPreferences.Snapshot(true, -12L, -13L, "calculator2.android.com,2026-09-05:42"),
            CalculatorPreferences.Snapshot.from(preferences)
        )
    }

    @Test
    fun keysMatchTheLegacySharedPreferencesNames() {
        assertEquals("degree_mode", CalculatorPreferences.DEGREE_MODE.name)
        assertEquals("saved_index", CalculatorPreferences.SAVED_INDEX.name)
        assertEquals("memory_index", CalculatorPreferences.MEMORY_INDEX.name)
        assertEquals("saved_name", CalculatorPreferences.SAVED_NAME.name)
    }
}
