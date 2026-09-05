/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.calculator2

import android.content.Context

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// One DataStore per process. Settings written by older versions with SharedPreferences are
// migrated on first access, so the key names below must stay the same.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calculator",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "${context.packageName}_preferences"))
    }
)

/**
 * The calculator's persistent settings, backed by Preferences DataStore.
 * Writes are applied asynchronously on [scope], in the order they were requested.
 */
class CalculatorPreferences(context: Context, private val scope: CoroutineScope) {

    /** An immutable view of all settings. */
    data class Snapshot(
        /** Evaluate the main expression in degrees rather than radians. */
        val degreeMode: Boolean = false,
        /** Index of the expression mirroring the clipboard, or 0 if unused. */
        val savedIndex: Long = 0L,
        /** Index of the "memory" expression, or 0 if unused. */
        val memoryIndex: Long = 0L,
        /** A hopefully unique name associated with the saved expression. */
        val savedName: String = DEFAULT_SAVED_NAME
    ) {
        companion object {
            fun from(preferences: Preferences) = Snapshot(
                degreeMode = preferences[DEGREE_MODE] ?: false,
                savedIndex = preferences[SAVED_INDEX] ?: 0L,
                memoryIndex = preferences[MEMORY_INDEX] ?: 0L,
                savedName = preferences[SAVED_NAME] ?: DEFAULT_SAVED_NAME
            )
        }
    }

    private val dataStore = context.dataStore

    /** The settings, re-emitted whenever any of them changes. */
    val snapshots: Flow<Snapshot> = dataStore.data.map(Snapshot::from)

    /**
     * Read the current settings synchronously.
     * This blocks on a small file read, exactly like SharedPreferences used to; it is meant to
     * be called once, at start-up, before anything is displayed.
     */
    fun readBlocking(): Snapshot = runBlocking { snapshots.first() }

    fun setDegreeMode(degreeMode: Boolean) = edit { it[DEGREE_MODE] = degreeMode }

    fun setSavedIndex(index: Long) = edit { it[SAVED_INDEX] = index }

    fun setMemoryIndex(index: Long) = edit { it[MEMORY_INDEX] = index }

    fun setSavedName(name: String) = edit { it[SAVED_NAME] = name }

    private fun edit(transform: (MutablePreferences) -> Unit): Job =
        scope.launch { dataStore.edit { transform(it) } }

    companion object {
        internal val DEGREE_MODE = booleanPreferencesKey("degree_mode")
        internal val SAVED_INDEX = longPreferencesKey("saved_index")
        internal val MEMORY_INDEX = longPreferencesKey("memory_index")
        internal val SAVED_NAME = stringPreferencesKey("saved_name")
        internal const val DEFAULT_SAVED_NAME = "none"
    }
}
