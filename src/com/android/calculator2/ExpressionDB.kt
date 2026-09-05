/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

// We make some strong assumptions about the databases we manipulate.
// We maintain a single table containing expressions, their indices in the sequence of
// expressions, and some data associated with each expression.
// All indices are used, except for a small gap around zero.  New rows are added
// either just below the current minimum (negative) index, or just above the current
// maximum index. Currently no rows are deleted unless we clear the whole table.

// TODO: Especially if we notice serious performance issues on rotation in the history
// view, we may need to use a CursorLoader or some other scheme to preserve the database
// across rotations.
// TODO: We may want to switch to a scheme in which all expressions saved in the database have
// a positive index, and a flag indicates whether the expression is displayed as part of
// the history or not. That would avoid potential thrashing between CursorWindows when accessing
// with a negative index. It would also make it easy to sort expressions in dependency order,
// which helps with avoiding deep recursion during evaluation. But it makes the history UI
// implementation more complicated. It should be possible to make this change without a
// database version bump.

// This ensures strong thread-safety, i.e. each call looks atomic to other threads. We need some
// such property, since expressions may be read by one thread while the main thread is updating
// another expression.

package com.android.calculator2

import android.content.ContentValues
import android.content.Context
import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import android.util.Log

import java.util.concurrent.Executors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

import kotlin.math.max
import kotlin.math.min

class ExpressionDB(context: Context) {

    /* Table contents */
    object ExpressionEntry : BaseColumns {
        const val TABLE_NAME = "expressions"
        const val COLUMN_NAME_EXPRESSION = "expression"
        const val COLUMN_NAME_FLAGS = "flags"
        // Time stamp as returned by currentTimeMillis().
        const val COLUMN_NAME_TIMESTAMP = "timeStamp"
    }

    /* Data to be written to or read from a row in the table */
    class RowData private constructor(
        val expression: ByteArray,
        val flags: Int,
        var timeStamp: Long // 0 ==> to be filled in when written.
    ) {
        /**
         * More client-friendly constructor that hides implementation ugliness.
         * A zero timestamp will cause it to be automatically filled in.
         */
        constructor(expr: ByteArray, degreeMode: Boolean, longTimeout: Boolean, timeStamp: Long) :
            this(expr, flagsFromDegreeAndTimeout(degreeMode, longTimeout), timeStamp)

        val degreeMode: Boolean get() = flags and DEGREE_MODE != 0

        val longTimeout: Boolean get() = flags and LONG_TIMEOUT != 0

        /**
         * Return a ContentValues object representing the current data.
         */
        fun toContentValues() = ContentValues().apply {
            put(ExpressionEntry.COLUMN_NAME_EXPRESSION, expression)
            put(ExpressionEntry.COLUMN_NAME_FLAGS, flags)
            if (timeStamp == 0L) timeStamp = System.currentTimeMillis()
            put(ExpressionEntry.COLUMN_NAME_TIMESTAMP, timeStamp)
        }

        companion object {
            private const val DEGREE_MODE = 2
            private const val LONG_TIMEOUT = 1

            /**
             * Reads a row from the given cursor, which is assumed to be positioned on it.
             */
            internal fun fromCursor(cursor: Cursor) =
                RowData(cursor.getBlob(1), cursor.getInt(2) /* flags */, cursor.getLong(3) /* timestamp */)

            private fun flagsFromDegreeAndTimeout(degreeMode: Boolean, longTimeout: Boolean) =
                (if (degreeMode) DEGREE_MODE else 0) or (if (longTimeout) LONG_TIMEOUT else 0)
        }
    }

    private class ExpressionDBHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SQL_CREATE_ENTRIES)
            db.execSQL(SQL_CREATE_TIMESTAMP_INDEX)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // For now just throw away history on database version upgrade/downgrade.
            db.execSQL(SQL_DROP_TIMESTAMP_INDEX)
            db.execSQL(SQL_DROP_TABLE)
            onCreate(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            onUpgrade(db, oldVersion, newVersion)

        companion object {
            // If you change the database schema, you must increment the database version.
            const val DATABASE_VERSION = 1
            const val DATABASE_NAME = "Expressions.db"
        }
    }

    private val expressionDBHelper = ExpressionDBHelper(context)

    private lateinit var expressionDB: SQLiteDatabase // Constant after initialization.

    // Expression indices between minAccessible and maxAccessible inclusive can be accessed.
    // We set these to more interesting values if a database access fails.
    // We punt on writes outside this range. We should never read outside this range.
    // If higher layers refer to an index outside this range, it will already be cached.
    // This also somewhat limits the size of the database, but only to an unreasonably
    // huge value.
    private var minAccessible = -10000000L
    private var maxAccessible = 10000000L

    // Minimum index value in DB.
    private var minIndex = 0L

    // Maximum index value in DB.
    private var maxIndex = 0L

    // A cursor that refers to the whole table, in reverse order.
    private var allCursor: AbstractWindowedCursor? = null

    // Expression index corresponding to a zero absolute offset for allCursor.
    // This is the argument we passed to the query.
    // We explicitly query only for entries that existed when we started, to avoid
    // interference from updates as we're running. It's unclear whether or not this matters.
    private var allCursorBase = 0

    // Database has been opened, minIndex and maxIndex are correct, allCursorBase and
    // allCursor have been set.
    private var dbInitialized = false

    // lock protects expressionDB, minAccessible, and maxAccessible, allCursor,
    // allCursorBase, minIndex, maxIndex, and dbInitialized. We access expressionDB without
    // synchronization after it's known to be initialized.  Used to wait for database
    // initialization.
    private val lock = Object()

    private var databaseWarningIssued = false

    // We track the number of outstanding writes to prevent onSaveInstanceState from
    // completing with in-flight database writes.
    private var incompleteWrites = 0
    private val writeCountsLock = Object() // Protects the preceding field.

    // All background database work runs on this single thread, in the order it was submitted
    // from the UI thread. That keeps writes ordered relative to each other and to erasure.
    private val dbDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "ExpressionDB") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dbDispatcher)

    init {
        scope.launch { initialize() }
    }

    // Is database completely unusable?
    private fun isDBBad() = CONTINUE_WITH_BAD_DB && synchronized(lock) { minAccessible > maxAccessible }

    // Is the index in the accessible range of the database?
    private fun inAccessibleRange(index: Long) =
        !CONTINUE_WITH_BAD_DB || synchronized(lock) { index in minAccessible..maxAccessible }

    private fun setBadDB() {
        if (!CONTINUE_WITH_BAD_DB) {
            Log.e("Calculator", "Database access failed")
            throw RuntimeException("Database access failed")
        }
        displayDatabaseWarning()
        synchronized(lock) {
            minAccessible = 1L
            maxAccessible = -1L
        }
    }

    /**
     * Open the database and determine its index range. Runs on the database thread.
     */
    private fun initialize() {
        try {
            val db = expressionDBHelper.writableDatabase
            synchronized(lock) {
                expressionDB = db
                // An empty database yields no rows for either query.
                minIndex = db.rawQuery(SQL_GET_MIN, null).use { c ->
                    if (c.moveToFirst()) min(c.getLong(0), MAXIMUM_MIN_INDEX) else MAXIMUM_MIN_INDEX
                }
                maxIndex = db.rawQuery(SQL_GET_MAX, null).use { c ->
                    if (c.moveToFirst()) max(c.getLong(0), 0L) else 0L
                }
                if (maxIndex > Int.MAX_VALUE) throw AssertionError("Expression index absurdly large")
                allCursorBase = maxIndex.toInt()
                if (maxIndex != 0L || minIndex != MAXIMUM_MIN_INDEX) {
                    // Set up a cursor for reading the entire database.
                    val args = arrayOf(allCursorBase.toString(), minIndex.toString())
                    val cursor = db.rawQuery(SQL_GET_ALL, args) as AbstractWindowedCursor
                    allCursor = cursor
                    if (!cursor.moveToFirst()) {
                        setBadDB()
                        displayDatabaseWarning()
                        return
                    }
                }
                dbInitialized = true
                // Wake up any UI thread call that is blocked waiting for us.
                lock.notifyAll()
            }
        } catch (e: SQLiteException) {
            Log.e("Calculator", "Database initialization failed.\n", e)
            synchronized(lock) {
                setBadDB()
                lock.notifyAll()
            }
            displayDatabaseWarning()
        }
    }

    /**
     * Display a warning message that a database access failed.
     * Do this only once. TODO: Replace with a real UI message.
     */
    private fun displayDatabaseWarning() {
        if (databaseWarningIssued) return
        Log.e("Calculator", "Calculator restarting due to database error")
        databaseWarningIssued = true
    }

    /**
     * Wait until the database and allCursor, etc. have been initialized.
     */
    private fun waitForDBInitialized() =
        synchronized(lock) { lock.waitUntil { dbInitialized || isDBBad() } }

    /**
     * Erase ALL database entries.
     * This is currently only safe if expressions that may refer to them are also erased.
     * Should only be called when concurrent references to the database are impossible.
     * The erasure runs on the database thread, after any previously submitted writes.
     * TODO: Look at ways to more selectively clear the database.
     */
    fun eraseAll() {
        waitForDBInitialized()
        synchronized(lock) { dbInitialized = false }
        scope.launch {
            expressionDB.execSQL(SQL_DROP_TIMESTAMP_INDEX)
            expressionDB.execSQL(SQL_DROP_TABLE)
            runCatching { expressionDB.execSQL("VACUUM") }.onFailure {
                // Should only happen with concurrent execution, which should be impossible.
                Log.v("Calculator", "Database VACUUM failed\n", it)
            }
            expressionDB.execSQL(SQL_CREATE_ENTRIES)
            expressionDB.execSQL(SQL_CREATE_TIMESTAMP_INDEX)
            synchronized(lock) {
                // Reinitialize everything to an empty and fully functional database.
                minAccessible = -10000000L
                maxAccessible = 10000000L
                minIndex = MAXIMUM_MIN_INDEX
                maxIndex = 0
                allCursorBase = 0
                dbInitialized = true
                lock.notifyAll()
            }
        }
    }

    private fun writeCompleted() = synchronized(writeCountsLock) {
        if (--incompleteWrites == 0) writeCountsLock.notifyAll()
    }

    private fun writeStarted() = synchronized(writeCountsLock) { ++incompleteWrites }

    /**
     * Wait for in-flight writes to complete.
     * This is not safe to call from the database thread, since the writes it would wait for
     * run on that very thread.
     */
    fun waitForWrites() =
        synchronized(writeCountsLock) { writeCountsLock.waitUntil { incompleteWrites == 0 } }

    /**
     * Insert the given row in the database. Runs on the database thread.
     */
    private fun insertRow(index: Long, cvs: ContentValues) {
        val result = expressionDB.insert(ExpressionEntry.TABLE_NAME, null, cvs)
        writeCompleted()
        when (result) {
            index -> return
            -1L -> {
                // The write failed; stop using indices beyond it.
                synchronized(lock) {
                    if (index > 0) maxAccessible = index - 1 else minAccessible = index + 1
                }
                displayDatabaseWarning()
            }
            else -> throw AssertionError("Expected row id $index, got $result")
        }
    }

    /**
     * Add a row with index outside existing range.
     * The returned index will be just larger than any existing index unless negativeIndex is
     * true. In that case it will be smaller than any existing index and smaller than
     * MAXIMUM_MIN_INDEX.
     * This ensures that prior additions have completed, but does not wait for this insertion
     * to complete.
     */
    fun addRow(negativeIndex: Boolean, data: RowData): Long {
        waitForDBInitialized()
        synchronized(lock) {
            val newIndex = if (negativeIndex) --minIndex else ++maxIndex
            if (!inAccessibleRange(newIndex)) {
                // Just drop it, but go ahead and return a new index to use for the cache.
                // So long as reads of previously written expressions continue to work,
                // we should be fine. When the application is restarted, history will revert
                // to just include values between minAccessible and maxAccessible.
                return newIndex
            }
            writeStarted()
            val cvs = data.toContentValues().apply { put(BaseColumns._ID, newIndex) }
            // The single database thread executes writes in submission order.
            scope.launch { insertRow(newIndex, cvs) }
            return newIndex
        }
    }

    /**
     * Generate a fake database row that's good enough to hopefully prevent crashes,
     * but bad enough to avoid confusion with real data. In particular, the result
     * will fail to evaluate.
     */
    private fun makeBadRow(): RowData {
        val badExpr = CalculatorExpr().apply {
            add(R.id.lparen)
            add(R.id.rparen)
        }
        return RowData(badExpr.toBytes(), false, false, 0)
    }

    /**
     * Retrieve the row with the given index using a direct query.
     * Such a row must exist.
     * We assume that the database has been initialized, and the argument has been range checked.
     */
    private fun getRowDirect(index: Long): RowData =
        expressionDB.rawQuery(SQL_GET_ROW, arrayOf(index.toString())).use { c ->
            if (c.moveToFirst()) {
                RowData.fromCursor(c)
            } else {
                setBadDB()
                makeBadRow()
            }
        }

    /**
     * Retrieve the row at the given offset from allCursorBase.
     * Note the argument is NOT an expression index!
     * We assume that the database has been initialized, and the argument has been range checked.
     */
    private fun getRowFromCursor(offset: Int): RowData = synchronized(lock) {
        val cursor = allCursor?.takeIf { it.moveToPosition(offset) } ?: run {
            Log.e("Calculator", "Failed to move cursor to position $offset")
            setBadDB()
            return makeBadRow()
        }
        RowData.fromCursor(cursor)
    }

    /**
     * Retrieve the database row at the given index.
     * We currently assume that we never read data that we added since we initialized the database.
     * This makes sense, since we cache it anyway. And we should always cache recently added data.
     */
    fun getRow(index: Long): RowData {
        waitForDBInitialized()
        if (!inAccessibleRange(index)) {
            // Even if something went wrong opening or writing the database, we should
            // not see such read requests, unless they correspond to a persistently
            // saved index, and we can't retrieve that expression.
            displayDatabaseWarning()
            return makeBadRow()
        }
        // We currently assume that the only gap between expression indices is the one around 0.
        val position = allCursorBase - index.toInt() - (if (index < 0) GAP.toInt() else 0)
        if (position < 0) {
            throw AssertionError("Database access out of range, index = $index rel. pos. = $position")
        }
        if (index < 0) {
            // Avoid using allCursor to read data that's far away from the current position,
            // since we're likely to have to return to the current position.
            // This is a heuristic; we don't worry about doing the "wrong" thing in the race case.
            val endPosition = synchronized(lock) {
                val window = (allCursor ?: return getRowDirect(index)).window
                window.startPosition + window.numRows
            }
            if (position >= endPosition) return getRowDirect(index)
        }
        // In the positive index case, it's probably OK to cross a cursor boundary, since
        // we're much more likely to stay in the new window.
        return getRowFromCursor(position)
    }

    fun getMinIndex(): Long {
        waitForDBInitialized()
        return synchronized(lock) { minIndex }
    }

    fun getMaxIndex(): Long {
        waitForDBInitialized()
        return synchronized(lock) { maxIndex }
    }

    fun close() {
        expressionDBHelper.close()
        dbDispatcher.close()
    }

    companion object {
        private const val CONTINUE_WITH_BAD_DB = false

        // Never allocate new negative indices (row ids) >= MAXIMUM_MIN_INDEX.
        const val MAXIMUM_MIN_INDEX = -10L

        // Gap between negative and positive row ids in the database.
        // Expressions with index [MAXIMUM_MIN_INDEX .. 0] are not stored.
        private const val GAP = -MAXIMUM_MIN_INDEX + 1

        private const val SQL_CREATE_ENTRIES =
            "CREATE TABLE " + ExpressionEntry.TABLE_NAME + " (" +
                BaseColumns._ID + " INTEGER PRIMARY KEY," +
                ExpressionEntry.COLUMN_NAME_EXPRESSION + " BLOB," +
                ExpressionEntry.COLUMN_NAME_FLAGS + " INTEGER," +
                ExpressionEntry.COLUMN_NAME_TIMESTAMP + " INTEGER)"
        private const val SQL_DROP_TABLE = "DROP TABLE IF EXISTS " + ExpressionEntry.TABLE_NAME
        private const val SQL_GET_MIN = "SELECT MIN(" + BaseColumns._ID + ") FROM " +
            ExpressionEntry.TABLE_NAME
        private const val SQL_GET_MAX = "SELECT MAX(" + BaseColumns._ID + ") FROM " +
            ExpressionEntry.TABLE_NAME
        private const val SQL_GET_ROW = "SELECT * FROM " + ExpressionEntry.TABLE_NAME +
            " WHERE " + BaseColumns._ID + " = ?"
        private const val SQL_GET_ALL = "SELECT * FROM " + ExpressionEntry.TABLE_NAME +
            " WHERE " + BaseColumns._ID + " <= ? AND " + BaseColumns._ID + " >= ?" +
            " ORDER BY " + BaseColumns._ID + " DESC "

        // We may eventually need an index by timestamp. We don't use it yet.
        private const val SQL_CREATE_TIMESTAMP_INDEX =
            "CREATE INDEX timestamp_index ON " + ExpressionEntry.TABLE_NAME + "(" +
                ExpressionEntry.COLUMN_NAME_TIMESTAMP + ")"
        private const val SQL_DROP_TIMESTAMP_INDEX = "DROP INDEX IF EXISTS timestamp_index"
    }
}
