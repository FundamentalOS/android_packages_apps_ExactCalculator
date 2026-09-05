/*
 * SPDX-FileCopyrightText: 2016 The Android Open Source Project
 * SPDX-FileCopyrightText: The FundamentalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// TODO: Copy & more general paste in formula?  Note that this requires
//       great care: Currently the text version of a displayed formula
//       is not directly useful for re-evaluating the formula later, since
//       it contains ellipses representing subexpressions evaluated with
//       a different degree mode.  Rather than supporting copy from the
//       formula window, we may eventually want to support generation of a
//       more useful text version in a separate window.  It's not clear
//       this is worth the added (code and user) complexity.

package com.android.calculator2

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.ActionMode
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.calculator2.CalculatorFormula.OnFormulaContextMenuClickListener
import com.android.calculator2.CalculatorFormula.OnTextSizeChangeListener
import com.android.calculator2.CalculatorResult.EvaluationRequest
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

import kotlin.math.max
import kotlin.math.min

class Calculator : AppCompatActivity(), OnTextSizeChangeListener, AlertDialogFragment.OnClickListener,
    Evaluator.EvaluationListener /* for main result */ {

    private enum class CalculatorState {
        INPUT, // Result and formula both visible, no evaluation requested,
        // Though result may be visible on bottom line.
        EVALUATE, // Both visible, evaluation requested, evaluation/animation incomplete.
        // Not used for instant result evaluation.
        INIT, // Very temporary state used as alternative to EVALUATE
        // during reinitialization.  Do not animate on completion.
        INIT_FOR_RESULT, // Identical to INIT, but evaluation is known to terminate
        // with result, and current expression has been copied to history.
        RESULT, // Result displayed, formula invisible.
        // If we are in RESULT state, the formula was evaluated without
        // error to initial precision.
        // The current formula is now also the last history entry.
        ERROR // Error displayed: Formula visible, result shows error message.
        // Display similar to INPUT state.
    }
    // Normal transition sequence is
    // INPUT -> EVALUATE -> RESULT (or ERROR) -> INPUT
    // A RESULT -> ERROR transition is possible in rare corner cases, in which
    // a higher precision evaluation exposes an error.  This is possible, since we
    // initially evaluate assuming we were given a well-defined problem.  If we
    // were actually asked to compute sqrt(<extremely tiny negative number>) we produce 0
    // unless we are asked for enough precision that we can distinguish the argument from zero.
    // ERROR and RESULT are translated to INIT or INIT_FOR_RESULT state if the application
    // is restarted in that state.  This leads us to recompute and redisplay the result
    // ASAP.
    // In INIT_FOR_RESULT, and RESULT state, a copy of the current
    // expression has been saved in the history db; in the other states, it has not.
    // TODO: Possibly save a bit more information, e.g. its initial display string
    // or most significant digit position, to speed up restart.

    private val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            scrollFormulaToCursor()
            formulaContainer.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            return false
        }
    }

    private val onFormulaContextMenuClickListener = object : OnFormulaContextMenuClickListener {
        override fun onPaste(clip: ClipData): Boolean {
            // nothing to paste, bail early...
            val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return false

            // Check if the item is a previously copied result, otherwise paste as raw text.
            val uri = item.uri
            if (uri != null && evaluator.isLastSaved(uri)) {
                clearIfNotInputState()
                beginEdit(currentSelection())
                cursor = evaluator.insertExpr(evaluator.savedIndex, cursor)
                redisplayAfterFormulaChange()
            } else {
                addChars(item.coerceToText(this@Calculator).toString(), false)
            }
            return true
        }

        override fun onMemoryRecall() {
            clearIfNotInputState()
            val memoryIndex = evaluator.memoryIndex
            if (memoryIndex != 0L) {
                beginEdit(currentSelection())
                cursor = evaluator.insertExpr(memoryIndex, cursor)
                redisplayAfterFormulaChange()
            }
        }

        override fun onCut() = onClear()
    }

    private val formulaTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(charSequence: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(charSequence: CharSequence, start: Int, count: Int, after: Int) {}

        override fun afterTextChanged(editable: Editable) {
            formulaContainer.viewTreeObserver.takeIf { it.isAlive }?.apply {
                removeOnPreDrawListener(preDrawListener)
                addOnPreDrawListener(preDrawListener)
            }
        }
    }

    private lateinit var currentState: CalculatorState
    private lateinit var evaluator: Evaluator

    private lateinit var modeView: TextView
    private lateinit var advancedToggle: MaterialButton
    private lateinit var historyToggle: MaterialButton
    private lateinit var formulaText: CalculatorFormula
    private lateinit var resultText: CalculatorResult
    // Shows the exact fraction under a result; the one-line layout has no room for it.
    private var fractionText: TextView? = null
    private lateinit var formulaContainer: HorizontalScrollView
    private lateinit var mainCalculator: MotionLayout

    private lateinit var inputPad: Keypad
    private lateinit var advancedPad: Keypad

    /** Whether the advanced pad shows the inverse functions; see onInverseToggled(). */
    private var inverseMode = false

    /**
     * Where the expression is being edited: the formula's cursor, as a position in the main
     * expression. Taken from the formula when an edit begins (see beginEdit()), moved along
     * by the edits, and put back into the formula when it is redisplayed.
     */
    private var cursor = CalculatorExpr.Position(0, 0)

    /**
     * The formula's selection as a touch began, before stopping its text action mode collapsed
     * it, so that a key the touch ends on can still act on it; see dispatchTouchEvent().
     */
    private var touchSelection: IntRange? = null

    // Characters that were recently entered at the end of the display that have not yet
    // been added to the underlying expression.
    private var unprocessedChars: String? = null

    // Color to highlight unprocessed characters from physical keyboard.
    // TODO: should probably match this to the error color?
    private val unprocessedColorSpan = ForegroundColorSpan(Color.RED)

    /** Whether the display is one line. */
    var isOneLine = false
        private set

    private val mainExpr: CalculatorExpr get() = evaluator.getExpr(Evaluator.MAIN_INDEX)

    private val isHistoryShowing: Boolean
        get() = mainCalculator.currentState in HISTORY_ORIGINS

    /**
     * Map the old saved state to a new state reflecting requested result reevaluation.
     */
    private fun mapFromSaved(savedState: CalculatorState) = when (savedState) {
        // Evaluation is expected to terminate normally.
        CalculatorState.RESULT, CalculatorState.INIT_FOR_RESULT -> CalculatorState.INIT_FOR_RESULT
        CalculatorState.ERROR, CalculatorState.INIT -> CalculatorState.INIT
        CalculatorState.EVALUATE, CalculatorState.INPUT -> savedState
    }

    /**
     * Restore Evaluator state and currentState from savedInstanceState.
     */
    private fun restoreInstanceState(savedInstanceState: Bundle) {
        setState(
            CalculatorState.entries[
                savedInstanceState.getInt(KEY_DISPLAY_STATE, CalculatorState.INPUT.ordinal)
            ]
        )
        savedInstanceState.getCharSequence(KEY_UNPROCESSED_CHARS)?.let { unprocessedChars = it.toString() }
        savedInstanceState.getByteArray(KEY_EVAL_STATE)?.let { state ->
            runCatching {
                ObjectInputStream(ByteArrayInputStream(state)).use(evaluator::restoreInstanceState)
            }.onFailure {
                // When in doubt, revert to clean state
                currentState = CalculatorState.INPUT
                evaluator.clearMain()
            }
        }
        onInverseToggled(savedInstanceState.getBoolean(KEY_INVERSE_MODE))
        if (savedInstanceState.getBoolean(KEY_ADVANCED_PAD)) mainCalculator.jumpToState(R.id.state_advanced)
        // TODO: We're currently not saving and restoring scroll position.
        //       We probably should.  Details may require care to deal with:
        //         - new display size
        //         - slow recomputation if we've scrolled far.
    }

    private fun restoreDisplay() {
        if (!isResultLayout) redisplayFormula()
        if (currentState == CalculatorState.INPUT) {
            // This resultText will explicitly call evaluateAndNotify when ready.
            resultText.setShouldEvaluateResult(EvaluationRequest.SHOULD_EVALUATE, this)
        } else {
            // Just reevaluate.
            setState(mapFromSaved(currentState))
            // Request evaluation when we know display width.
            resultText.setShouldEvaluateResult(EvaluationRequest.SHOULD_REQUIRE, this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_calculator)

        mainCalculator = findViewById(R.id.main_calculator)
        findViewById<View>(R.id.overflow).setOnClickListener { overflowMenu.show() }
        setupInsets()
        modeView = findViewById(R.id.mode)
        // The toolbar's mode label switches the mode just like the key on the advanced pad.
        modeView.setOnClickListener { onKey(R.id.toggle_mode) }
        advancedToggle = findViewById(R.id.toggle_advanced)
        advancedToggle.addOnCheckedChangeListener { _, checked -> onAdvancedToggled(checked) }
        historyToggle = findViewById(R.id.toggle_history)
        historyToggle.addOnCheckedChangeListener { _, checked -> onHistoryToggled(checked) }
        formulaText = findViewById(R.id.formula)
        resultText = findViewById(R.id.result)
        fractionText = findViewById(R.id.fraction)
        formulaContainer = findViewById(R.id.formula_scroll_view)
        evaluator = Evaluator.getInstance(this)
        resultText.setEvaluator(evaluator, Evaluator.MAIN_INDEX)
        observeEvaluator()
        KeyMaps.setActivity(this)

        inputPad = findViewById(R.id.input_pad)
        advancedPad = findViewById(R.id.advanced_pad)
        for (pad in listOf(inputPad, advancedPad)) {
            pad.onKeyClick = ::onKey
            pad.onKeyLongClick = ::onKeyLongPress
        }

        isOneLine = resultText.isInvisible

        // The history panel lives in its frame for the whole life of the activity and is only
        // reloaded as the sheet starts to open, so nothing is inflated during the pull.
        if (historyFragment == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.history_frame, HistoryFragment(), HistoryFragment.TAG)
                .commitNow()
        }

        // The display sheet has three positions; see activity_calculator_scene.xml.
        mainCalculator.setTransitionListener(object : MotionLayout.TransitionListener {
            override fun onTransitionStarted(motionLayout: MotionLayout, startId: Int, endId: Int) {
                if (endId in HISTORY_ORIGINS) onHistoryOpening()
            }

            override fun onTransitionChange(motionLayout: MotionLayout, startId: Int, endId: Int, progress: Float) {}

            override fun onTransitionCompleted(motionLayout: MotionLayout, currentId: Int) =
                onSheetStateChanged(currentId)

            override fun onTransitionTrigger(motionLayout: MotionLayout, triggerId: Int, positive: Boolean, progress: Float) {}
        })

        formulaText.apply {
            onContextMenuClickListener = onFormulaContextMenuClickListener
            onDisplayMemoryOperationsListener = OnDisplayMemoryOperationsListener { evaluator.memoryIndex != 0L }
            onTextSizeChangeListener = this@Calculator
            addTextChangedListener(formulaTextWatcher)
        }

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState)
        } else {
            currentState = CalculatorState.INPUT
            evaluator.clearMain()
            onInverseToggled(false)
        }
        cursor = mainExpr.end
        restoreDisplay()
        // The formula holds focus, so that its cursor blinks; the pads take none.
        formulaText.requestFocus()
        onSheetStateChanged(mainCalculator.currentState)
        // A restored advanced pad shows up already unfolded, with the toggle's icon to match.
        advancedToggle.jumpDrawablesToCurrentState()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBackRequested()
        })
    }

    /**
     * Mirror the evaluator's published state into the UI while we are started: the memory
     * slot (which enables the memory menu items), the degree mode labels, and dialog requests.
     */
    private fun observeEvaluator() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { evaluator.memoryIndexFlow.collect { formulaText.onMemoryStateChanged() } }
                launch { evaluator.degreeModeFlow.collect(::onModeChanged) }
                launch {
                    evaluator.dialogRequests.collect {
                        AlertDialogFragment.showMessageDialog(
                            this@Calculator, it.title, it.message, it.positiveButtonLabel, it.tag
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If HistoryFragment is showing, hide the main Calculator elements from accessibility.
        // This is because Talkback does not use visibility as a cue for RelativeLayout elements,
        // and RelativeLayout is the base class of DragLayout.
        // If we did not do this, it would be possible to traverse to main Calculator elements from
        // HistoryFragment.
        mainCalculator.importantForAccessibility = if (mainCalculator.currentState in HISTORY_ORIGINS) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    /** Ask before wiping the history; the answer arrives in onClick() with the dialog's tag. */
    private fun confirmClearHistory() = AlertDialogFragment.showMessageDialog(
        this, "" /* title */, getString(R.string.dialog_clear), getString(R.string.menu_clear_history),
        HistoryFragment.CLEAR_DIALOG_TAG
    )

    /** Pull the display sheet down over the pads, whichever way they are laid out. */
    private fun showHistory() = mainCalculator.transitionToState(
        if (advancedToggle.isChecked) R.id.state_history_advanced else R.id.state_history
    )

    /** Open the history, or close it again to wherever the sheet was pulled down from. */
    private fun onHistoryToggled(showHistory: Boolean) {
        val state = mainCalculator.currentState
        when {
            showHistory && state !in HISTORY_ORIGINS -> showHistory()
            !showHistory && state in HISTORY_ORIGINS ->
                mainCalculator.transitionToState(HISTORY_ORIGINS.getValue(state))
        }
    }

    /**
     * Unfold the advanced pad over the top of the numeric pad, or fold it away again. The
     * display does not move; only the numeric pad's buttons get squashed.
     */
    private fun onAdvancedToggled(showAdvanced: Boolean) {
        advancedToggle.contentDescription =
            getString(if (showAdvanced) R.string.desc_advanced_on else R.string.desc_advanced_off)
        val target = if (showAdvanced) R.id.state_advanced else R.id.state_basic
        if (mainCalculator.currentState != target) mainCalculator.transitionToState(target)
    }

    /**
     * The display sheet settled in [stateId]. Whatever is folded to nothing or slid off screen
     * (the advanced pad, the pads under the history, the history behind the pads) is kept away
     * from accessibility services and focus meanwhile.
     */
    private fun onSheetStateChanged(stateId: Int) {
        val advancedShowing = stateId == R.id.state_advanced
        val historyShowing = stateId in HISTORY_ORIGINS
        advancedPad.importantForAccessibility = importantIf(advancedShowing)
        inputPad.importantForAccessibility = importantIf(!historyShowing)
        findViewById<View>(R.id.history_frame).importantForAccessibility = importantIf(historyShowing)
        historyToggle.isChecked = historyShowing
        if (!historyShowing) {
            historyFragment?.onClosed()
            advancedToggle.isChecked = advancedShowing
        }
        prepareSwipeTransition(stateId)
    }

    /**
     * While the sheet rests in [stateId], set up the transition it can be pulled along from
     * there. Switching to another transition makes MotionLayout solve both of its states and
     * rebuild every child's path; left to the start of a pull, that stalls its first frame.
     */
    private fun prepareSwipeTransition(stateId: Int) {
        val transitionId = when (stateId) {
            R.id.state_basic, R.id.state_history -> R.id.transition_basic_history
            R.id.state_advanced, R.id.state_history_advanced -> R.id.transition_advanced_history
            else -> return
        }
        // Not from within the transition listener, and not before the scene has a size.
        mainCalculator.post {
            mainCalculator.doOnceLaidOut {
                if (mainCalculator.currentState != stateId) return@doOnceLaidOut
                val transition = mainCalculator.getTransition(transitionId)
                if (mainCalculator.startState != transition.startConstraintSetId ||
                    mainCalculator.endState != transition.endConstraintSetId
                ) {
                    mainCalculator.setTransition(transitionId)
                }
            }
        }
    }

    private fun importantIf(shown: Boolean) =
        if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

    override fun onSaveInstanceState(outState: Bundle) {
        evaluator.cancelAll(true)

        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DISPLAY_STATE, currentState.ordinal)
        outState.putCharSequence(KEY_UNPROCESSED_CHARS, unprocessedChars)
        val evalState = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use(evaluator::saveInstanceState) }
            .toByteArray()
        outState.putByteArray(KEY_EVAL_STATE, evalState)
        outState.putBoolean(KEY_INVERSE_MODE, inverseMode)
        outState.putBoolean(KEY_ADVANCED_PAD, advancedToggle.isChecked)
        // We must wait for asynchronous writes to complete, since outState may contain
        // references to expressions being written.
        evaluator.waitForWrites()
    }

    // Set the state, updating delete label and display colors.
    // This restores display positions on moving to INPUT.
    // But movement/animation for moving to RESULT has already been done.
    private fun setState(state: CalculatorState) {
        if (::currentState.isInitialized && currentState == state) return
        if (state == CalculatorState.INPUT) {
            // We'll explicitly request evaluation from now on.
            resultText.setShouldEvaluateResult(EvaluationRequest.SHOULD_NOT_EVALUATE, null)
            restoreDisplayPositions()
        }
        currentState = state

        if (isOneLine) {
            // Only one of the two is shown, except while a result is being computed or shown.
            formulaText.isInvisible = state == CalculatorState.ERROR
            resultText.isInvisible =
                state !in listOf(CalculatorState.RESULT, CalculatorState.EVALUATE, CalculatorState.ERROR)
        }

        if (state == CalculatorState.ERROR) {
            val errorColor = ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_error)
            formulaText.setTextColor(errorColor)
            resultText.setTextColor(errorColor)
        } else if (state != CalculatorState.RESULT) {
            formulaText.setTextColor(ContextCompat.getColor(this, R.color.display_formula_text_color))
            resultText.setTextColor(ContextCompat.getColor(this, R.color.display_result_text_color))
        }

    }

    /** Note that ERROR has INPUT, not RESULT layout. */
    val isResultLayout: Boolean
        get() = currentState == CalculatorState.INIT_FOR_RESULT || currentState == CalculatorState.RESULT

    /**
     * Destroy the evaluator and close the underlying database.
     */
    fun destroyEvaluator() = evaluator.destroyEvaluator()

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        if (mode.tag == CalculatorFormula.TAG_ACTION_MODE) scrollFormulaToCursor()
    }

    /** Scroll the formula so that its cursor is in view; at the end, that is the right end. */
    private fun scrollFormulaToCursor() {
        val layout = formulaText.layout
        val cursorX = if (layout == null) {
            formulaText.right
        } else {
            val offset = formulaText.selectionEnd.coerceIn(0, formulaText.length())
            formulaText.left + formulaText.paddingLeft + layout.getPrimaryHorizontal(offset).toInt()
        }
        val margin = formulaText.paddingLeft
        val visibleWidth = formulaContainer.width - formulaContainer.paddingLeft - formulaContainer.paddingRight
        val scrollX = formulaContainer.scrollX
        when {
            cursorX + margin > scrollX + visibleWidth -> formulaContainer.scrollTo(cursorX + margin - visibleWidth, 0)
            cursorX - margin < scrollX -> formulaContainer.scrollTo(cursorX - margin, 0)
        }
    }

    /**
     * Stop any active ActionMode or ContextMenu for copy/paste actions.
     * Return true if there was one.
     */
    private fun stopActionModeOrContextMenu() =
        resultText.stopActionModeOrContextMenu() || formulaText.stopActionModeOrContextMenu()

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            touchSelection = currentSelection()
            stopActionModeOrContextMenu()
            if (isHistoryShowing) historyFragment?.stopActionModeOrContextMenu()
        }
        val handled = super.dispatchTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchSelection = null
        }
        return handled
    }

    /**
     * Handle the system back gesture/button: close any copy/paste menu first, then the history
     * panel, and otherwise send the task to the background.
     */
    private fun onBackRequested() {
        when {
            stopActionModeOrContextMenu() -> moveTaskToBack(true)
            // Back from the history returns the sheet to where it was pulled down from.
            isHistoryShowing ->
                mainCalculator.transitionToState(HISTORY_ORIGINS.getValue(mainCalculator.currentState))
            else -> moveTaskToBack(true)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Allow the system to handle special key codes (e.g. "BACK" or "DPAD").
        if (keyCode in SYSTEM_KEY_CODES) return super.onKeyUp(keyCode, event)

        // The selection, before stopping the action mode collapses it.
        val selection = currentSelection()

        // Stop the action mode or context menu if it's showing.
        stopActionModeOrContextMenu()

        // Always cancel unrequested in-progress evaluation of the main expression, so that
        // we don't have to worry about subsequent asynchronous completion.
        // Requested in-progress evaluations are handled below.
        cancelUnrequested()

        when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> onEquals()
            KeyEvent.KEYCODE_DEL -> onDelete(selection)
            KeyEvent.KEYCODE_CLEAR -> onClear()
            else -> {
                cancelIfEvaluating(false)
                val raw = event.keyCharacterMap.get(keyCode, event.metaState)
                if ((raw and KeyCharacterMap.COMBINING_ACCENT) != 0) return true // discard
                // Try to discard non-printing characters and the like.
                // The user will have to explicitly delete other junk that gets past us.
                if (Character.isIdentifierIgnorable(raw) || Character.isWhitespace(raw)) return true
                val c = raw.toChar()
                if (c == '=') {
                    onEquals()
                } else {
                    addChars(c.toString(), true, selection)
                    redisplayAfterFormulaChange()
                }
            }
        }
        return true
    }

    /**
     * Invoked whenever the inverse button is toggled to update the UI.
     *
     * @param showInverse `true` if inverse functions should be shown
     */
    private fun onInverseToggled(showInverse: Boolean) {
        inverseMode = showInverse
        advancedPad.setKeySelected(R.id.toggle_inv, showInverse)
        advancedPad.setKeyContentDescription(
            R.id.toggle_inv, getString(if (showInverse) R.string.desc_inv_on else R.string.desc_inv_off)
        )
        INVERTIBLE_KEYS.forEach { advancedPad.setKeyVisible(it, !showInverse) }
        INVERSE_KEYS.forEach { advancedPad.setKeyVisible(it, showInverse) }
    }

    /**
     * Invoked whenever the deg/rad mode may have changed to update the UI. Note that the mode has
     * not necessarily actually changed where this is invoked.
     *
     * @param degreeMode `true` if in degree mode
     */
    private fun onModeChanged(degreeMode: Boolean) {
        modeView.setText(if (degreeMode) R.string.mode_deg else R.string.mode_rad)
        modeView.contentDescription = getString(if (degreeMode) R.string.desc_switch_rad else R.string.desc_switch_deg)

        advancedPad.setKeyLabel(R.id.toggle_mode, getString(if (degreeMode) R.string.mode_rad else R.string.mode_deg))
        advancedPad.setKeyContentDescription(
            R.id.toggle_mode, getString(if (degreeMode) R.string.desc_switch_rad else R.string.desc_switch_deg)
        )
    }

    /**
     * Switch to INPUT from RESULT state in response to input of the specified button_id.
     * View.NO_ID is treated as an incomplete function id.
     */
    private fun switchToInput(buttonId: Int) {
        if (KeyMaps.isBinary(buttonId) || KeyMaps.isSuffix(buttonId)) {
            evaluator.collapse(evaluator.getMaxIndex() /* Most recent history entry */)
        } else {
            announceClearedForAccessibility()
            evaluator.clearMain()
        }
        cursor = mainExpr.end
        setState(CalculatorState.INPUT)
    }

    /**
     * The formula's selection as the offsets of its start and end into the formula's text;
     * both the same at a plain cursor.
     */
    private fun currentSelection(): IntRange {
        val start = formulaText.selectionStart
        val end = formulaText.selectionEnd
        if (start < 0 || end < 0) return formulaText.length().let { it..it }
        return minOf(start, end)..maxOf(start, end)
    }

    /**
     * Start an edit at [selection]: at its end, after deleting what it selected, as typing over
     * a selection does. Characters that could not be processed only ever sit at the end, so
     * while there are any the cursor is the end.
     * @return whether a selection was deleted
     */
    private fun beginEdit(selection: IntRange): Boolean {
        if (haveUnprocessed() || currentState == CalculatorState.RESULT) {
            // A result is continued or replaced whole; see switchToInput().
            cursor = mainExpr.end
            return false
        }
        cursor = mainExpr.positionOf(this, selection.last)
        if (selection.first == selection.last) return false
        cursor = evaluator.deleteRange(mainExpr.positionOf(this, selection.first), cursor)
        return true
    }

    /**
     * The parenthesis key, with something selected, puts the selection in parentheses rather
     * than replacing it; returns whether it did.
     */
    private fun wrapSelectionInParentheses(selection: IntRange): Boolean {
        if (selection.first == selection.last || haveUnprocessed() || currentState == CalculatorState.RESULT) {
            return false
        }
        val from = mainExpr.positionOf(this, selection.first)
        val to = mainExpr.positionOf(this, selection.last)
        if (from == to) return false
        if (currentState == CalculatorState.ERROR) setState(CalculatorState.INPUT)
        // The closing one first, so that the opening one does not move the place for it; the
        // opening one then moves the closing one along by the tokens it adds.
        val afterClose = evaluator.insert(R.id.rparen, to) ?: return false
        cursor = afterClose
        val afterOpen = evaluator.insert(R.id.lparen, from) ?: return true
        cursor = CalculatorExpr.Position(afterClose.token + (afterOpen.token - from.token), 0)
        return true
    }

    // Insert the given button id into the input expression at the cursor.
    // If appropriate, clear the expression before doing so.
    private fun addKeyToExpr(id: Int) {
        when (currentState) {
            CalculatorState.ERROR -> setState(CalculatorState.INPUT)
            CalculatorState.RESULT -> switchToInput(id)
            else -> {}
        }
        // TODO: Some user visible feedback when the key is rejected?
        cursor = evaluator.insert(id, cursor) ?: cursor
    }

    /**
     * Insert the given button id into the input expression, assuming it was explicitly
     * typed/touched.
     * We perform slightly more aggressive correction than in pasted expressions.
     */
    private fun addExplicitKeyToExpr(id: Int) {
        if (currentState == CalculatorState.INPUT && id == R.id.op_sub) {
            cursor = evaluator.removeAdditiveOperatorsBefore(cursor)
        }
        addKeyToExpr(id)
    }

    fun evaluateInstantIfNecessary() {
        if (currentState == CalculatorState.INPUT && mainExpr.hasInterestingOps()) {
            evaluator.evaluateAndNotify(Evaluator.MAIN_INDEX, this, resultText)
        }
    }

    private fun redisplayAfterFormulaChange() {
        // TODO: Could do this more incrementally.
        redisplayFormula()
        setState(CalculatorState.INPUT)
        resultText.clear()
        if (haveUnprocessed()) {
            // Force reevaluation when text is deleted, even if expression is unchanged.
            evaluator.touch()
        } else {
            evaluateInstantIfNecessary()
        }
    }

    /** A key of either pad was tapped. */
    private fun onKey(id: Int) {
        // The selection, before the formula's action mode is stopped, which collapses it; a
        // touch stopped it at touch-down already, and kept what it saw.
        val selection = touchSelection ?: currentSelection()

        // Any animation is ended before we get here.
        stopActionModeOrContextMenu()

        // See onKey above for the rationale behind some of the behavior below:
        cancelUnrequested()

        when (id) {
            R.id.eq -> onEquals()
            R.id.del -> onDelete(selection)
            R.id.clr -> onClear()
            R.id.toggle_inv -> {
                val selected = !inverseMode
                onInverseToggled(selected)
                // In case we cancelled reevaluation.
                if (currentState == CalculatorState.RESULT) resultText.redisplay()
            }
            R.id.toggle_mode -> {
                cancelIfEvaluating(false)
                val mode = !evaluator.getDegreeMode(Evaluator.MAIN_INDEX)
                if (currentState == CalculatorState.RESULT && mainExpr.hasTrigFuncs()) {
                    // Capture current result evaluated in old mode.
                    evaluator.collapse(evaluator.getMaxIndex())
                    redisplayFormula()
                }
                // In input mode, we reinterpret already entered trig functions.
                // The mode labels follow degreeModeFlow.
                evaluator.setDegreeMode(mode)
                cursor = mainExpr.end
                setState(CalculatorState.INPUT)
                resultText.clear()
                if (!haveUnprocessed()) evaluateInstantIfNecessary()
            }
            R.id.paren -> {
                // With something selected, put it in parentheses. Otherwise:
                // If the cursor follows a function or left paren, add another.
                // If there are no open parentheses before it, add a left one.
                // If it follows a digit, symbolic constant, right parenthesis, or suffix
                // operator, add a right one.
                // If it follows an operator, add a left one.
                cancelIfEvaluating(false)
                if (!wrapSelectionInParentheses(selection)) {
                    beginEdit(selection)
                    val closes = mainExpr.run {
                        !hasLeftParenBefore(cursor) && hasOpenParenthesesBefore(cursor) &&
                            (hasRightParenBefore(cursor) || hasConstantBefore(cursor) || hasSuffixBefore(cursor))
                    }
                    addExplicitKeyToExpr(if (closes) R.id.rparen else R.id.lparen)
                }
                redisplayAfterFormulaChange()
            }
            else -> {
                cancelIfEvaluating(false)
                if (haveUnprocessed()) {
                    // For consistency, append as uninterpreted characters.
                    // This may actually be useful for a left parenthesis.
                    addChars(KeyMaps.toString(this, id), true, selection)
                } else {
                    beginEdit(selection)
                    addExplicitKeyToExpr(id)
                    redisplayAfterFormulaChange()
                }
            }
        }
    }

    private fun redisplayFormula() {
        val formula = mainExpr.toSpannableStringBuilder(this)
        // The cursor goes where the last edit left it; characters that could not be processed
        // follow the expression, so with any of those it can only be at the end.
        val caret = if (haveUnprocessed()) -1 else mainExpr.displayOffsetOf(this, cursor)
        // Add and highlight characters we couldn't process.
        unprocessedChars?.let { formula.append(it, unprocessedColorSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        formulaText.changeTextTo(formula, if (caret < 0) formula.length else caret)
        formulaText.contentDescription = if (TextUtils.isEmpty(formula)) getString(R.string.desc_formula) else null
    }

    /** A key of either pad was held down: holding the delete key clears everything. */
    private fun onKeyLongPress(id: Int): Boolean {
        if (id != R.id.del) return false
        onClear()
        return true
    }

    // Initial evaluation completed successfully.  Initiate display.
    override fun onEvaluate(index: Long, initPrecOffset: Int, msdIndex: Int, lsdOffset: Int, truncatedWholePart: String) {
        if (index != Evaluator.MAIN_INDEX) throw AssertionError("Unexpected evaluation result index\n")

        resultText.onEvaluate(index, initPrecOffset, msdIndex, lsdOffset, truncatedWholePart)
        // In EVALUATE, INIT, RESULT, or INIT_FOR_RESULT state.
        if (currentState != CalculatorState.INPUT) onResult(isResultLayout /* previously preserved */)
    }

    // Reset state to reflect evaluator cancellation.  Invoked by evaluator.
    override fun onCancelled(index: Long) {
        // Index is Evaluator.MAIN_INDEX. We should be in EVALUATE state.
        setState(CalculatorState.INPUT)
        resultText.onCancelled(index)
    }

    // Reevaluation completed; ask result to redisplay current value.
    // Index is Evaluator.MAIN_INDEX.
    override fun onReevaluate(index: Long) = resultText.onReevaluate(index)

    override fun onTextSizeChanged(textView: TextView, oldSize: Float) {
        // Only animate text changes that occur from user input.
        if (currentState != CalculatorState.INPUT) return

        // Calculate the values needed to perform the scale and translation animations,
        // maintaining the same apparent baseline for the displayed text.
        val textScale = oldSize / textView.textSize
        val translationX = (1.0f - textScale) * (textView.width / 2.0f - textView.paddingEnd)
        val translationY = (1.0f - textScale) * (textView.height / 2.0f - textView.paddingBottom)

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(textView, View.SCALE_X, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.SCALE_Y, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_X, translationX, 0.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_Y, translationY, 0.0f)
            )
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            interpolator = AnimationUtils.loadInterpolator(this@Calculator, R.interpolator.standard)
        }.start()
    }

    /**
     * Cancel any in-progress explicitly requested evaluations.
     * @param quiet suppress pop-up message.  Explicit evaluation can change the expression
     *              value, and certainly changes the display, so it seems reasonable to warn.
     * @return      true if there was such an evaluation
     */
    private fun cancelIfEvaluating(quiet: Boolean): Boolean {
        if (currentState != CalculatorState.EVALUATE) return false
        evaluator.cancel(Evaluator.MAIN_INDEX, quiet)
        return true
    }

    private fun cancelUnrequested() {
        if (currentState == CalculatorState.INPUT) evaluator.cancel(Evaluator.MAIN_INDEX, true)
    }

    private fun haveUnprocessed() = !unprocessedChars.isNullOrEmpty()

    private fun onEquals() {
        // Ignore if in non-INPUT state, or if there are no operators.
        if (currentState != CalculatorState.INPUT) return
        if (haveUnprocessed()) {
            setState(CalculatorState.EVALUATE)
            onError(Evaluator.MAIN_INDEX, R.string.error_syntax)
        } else if (mainExpr.hasInterestingOps()) {
            setState(CalculatorState.EVALUATE)
            evaluator.requireResult(Evaluator.MAIN_INDEX, this, resultText)
        }
    }

    private fun onDelete(selection: IntRange) {
        // Delete works like backspace; remove the character or operator before the cursor, or
        // the selection. Note that we handle keyboard delete exactly like the delete button.
        // For example the delete button can be used to delete a character from an incomplete
        // function name typed on a physical keyboard.
        // This should be impossible in RESULT state.
        // If there is an in-progress explicit evaluation, just cancel it and return.
        if (cancelIfEvaluating(false)) return
        setState(CalculatorState.INPUT)
        val unprocessed = unprocessedChars
        if (!unprocessed.isNullOrEmpty()) {
            unprocessedChars = unprocessed.dropLast(1)
            cursor = mainExpr.end
        } else if (!beginEdit(selection)) {
            cursor = evaluator.deleteBefore(cursor)
        }
        // Resulting formula won't be announced, since it's empty.
        if (mainExpr.isEmpty() && !haveUnprocessed()) announceClearedForAccessibility()
        redisplayAfterFormulaChange()
    }

    private fun announceClearedForAccessibility() =
        resultText.announceForAccessibility(resources.getString(R.string.cleared))

    fun onClearEnd() {
        unprocessedChars = null
        resultText.clear()
        evaluator.clearMain()
        cursor = mainExpr.end
        setState(CalculatorState.INPUT)
        redisplayFormula()
    }

    private fun onClear() {
        if (mainExpr.isEmpty() && !haveUnprocessed()) return
        cancelIfEvaluating(true)
        announceClearedForAccessibility()
        onClearEnd()
    }

    // Evaluation encountered en error.  Display the error.
    override fun onError(index: Long, errorId: Int) {
        if (index != Evaluator.MAIN_INDEX) throw AssertionError("Unexpected error source")
        when (currentState) {
            CalculatorState.EVALUATE -> {
                resultText.announceForAccessibility(resources.getString(errorId))
                setState(CalculatorState.ERROR)
                resultText.onError(index, errorId)
            }
            CalculatorState.INIT, CalculatorState.INIT_FOR_RESULT /* very unlikely */ -> {
                setState(CalculatorState.ERROR)
                resultText.onError(index, errorId)
            }
            else -> {
                resultText.clear()
                hideFraction()
            }
        }
    }

    // Result window now remains translated in the top slot while the result is displayed.
    // (We convert it back to formula use only when the user provides new input.)
    // Historical note: In the Lollipop version, this invisibly and instantaneously moved
    // formula and result displays back at the end of the animation.  We no longer do that,
    // so that we can continue to properly support scrolling of the result.
    // We assume the result already contains the text to be expanded.
    private fun onResult(resultWasPreserved: Boolean) {
        // Calculate the textSize that would be used to display the result in the formula.
        // For scrollable results just use the minimum textSize to maximize the number of digits
        // that are visible on screen.
        val textSize = if (resultText.isScrollable) {
            formulaText.minimumTextSize
        } else {
            formulaText.getVariableTextSize(resultText.text.toString())
        }

        // Scale the result to match the calculated textSize, minimizing the jump-cut transition
        // when a result is reused in a subsequent expression.
        val resultScale = textSize / resultText.textSize

        // Set the result's pivot to match its gravity.
        resultText.pivotX = (resultText.width - resultText.paddingRight).toFloat()
        resultText.pivotY = (resultText.height - resultText.paddingBottom).toFloat()

        // Calculate the necessary translations so the result takes the place of the formula and
        // the formula moves off the top of the screen.
        val resultTranslationY = ((formulaContainer.bottom - resultText.bottom) -
            (formulaText.paddingBottom - resultText.paddingBottom)).toFloat()
        var formulaTranslationY = -formulaContainer.bottom.toFloat()
        if (isOneLine) {
            // Position the result text.
            resultText.y = resultText.bottom.toFloat()
            formulaTranslationY = -(findViewById<View>(R.id.toolbar).bottom + formulaContainer.bottom).toFloat()
        }

        // Change the result's textColor to match the formula.
        val formulaTextColor = formulaText.currentTextColor

        if (resultWasPreserved) {
            // Result was previously added to history.
            evaluator.represerve()
        } else {
            // Add current result to history.
            evaluator.preserve(Evaluator.MAIN_INDEX, true)
        }

        resultText.apply {
            scaleX = resultScale
            scaleY = resultScale
            translationY = resultTranslationY
            setTextColor(formulaTextColor)
        }
        formulaContainer.translationY = formulaTranslationY
        showFraction()
        setState(CalculatorState.RESULT)
    }

    // Restore positions of the formula and result displays back to their original,
    // pre-animation state.
    private fun restoreDisplayPositions() {
        resultText.apply {
            // Clear result.
            text = ""
            // Reset all of the values modified during the animation.
            scaleX = 1.0f
            scaleY = 1.0f
            translationX = 0.0f
            translationY = 0.0f
        }
        formulaContainer.translationY = 0.0f
        hideFraction()

        formulaText.requestFocus()
    }

    /**
     * Under a result, show the exact fraction it stands for, if it is known to be rational, is
     * not a whole number and fits on the line. The font lays the digits out as a fraction.
     */
    private fun showFraction() {
        val view = fractionText ?: return
        val fraction = evaluator.getResult(Evaluator.MAIN_INDEX)?.toFractionString()
            ?.let(KeyMaps::translateResult)
            ?.takeIf {
                Layout.getDesiredWidth(it, view.paint) <= view.width - view.paddingLeft - view.paddingRight
            }
        view.text = fraction.orEmpty()
        view.isInvisible = fraction == null
    }

    private fun hideFraction() {
        fractionText?.apply {
            text = ""
            isInvisible = true
        }
    }

    override fun onClick(fragment: AlertDialogFragment, which: Int) {
        if (which != DialogInterface.BUTTON_POSITIVE) return
        when (fragment.tag) {
            HistoryFragment.CLEAR_DIALOG_TAG -> {
                // TODO: Try to preserve the current, saved, and memory expressions. How
                // should we handle expressions to which they refer?
                evaluator.clearEverything()
                // TODO: It's not clear what we should really do here. This is an initial
                // hack. The memory menu items follow memoryIndexFlow.
                onClearEnd()
                // The rows refer to expressions that are gone; empty the list before it slides away.
                historyFragment?.refresh()
                onBackPressedDispatcher.onBackPressed()
            }
            // Timeout extension request.
            Evaluator.TIMEOUT_DIALOG_TAG -> evaluator.setLongTimeout()
            else -> Log.e(TAG, "Unknown AlertDialogFragment click:" + fragment.tag)
        }
    }

    /** The overflow menu, anchored to the button at the end of the display's toolbar. */
    private val overflowMenu by lazy {
        OverflowMenu(findViewById(R.id.overflow), R.menu.activity_calculator, ::onOverflowItemSelected)
    }

    private fun onOverflowItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_history -> confirmClearHistory()
            R.id.menu_licenses -> startActivity(Intent(this, Licenses::class.java))
            else -> return false
        }
        return true
    }

    /** Settle the evaluation before the history is revealed: a pending "=" is cancelled. */
    private fun prepareForHistory() {
        if (currentState == CalculatorState.EVALUATE) {
            cancelIfEvaluating(true /* quiet */)
            setState(CalculatorState.INPUT)
        }
    }

    private val historyFragment: HistoryFragment?
        get() = supportFragmentManager.takeUnless { it.isDestroyed }
            ?.findFragmentByTag(HistoryFragment.TAG)
            ?.takeUnless { it.isRemoving } as? HistoryFragment

    /** The sheet is starting to reveal the history: settle the evaluation and reload the list. */
    private fun onHistoryOpening() {
        prepareForHistory()
        stopActionModeOrContextMenu()
        historyFragment?.refresh()
    }

    /**
     * Add input characters to the end of the expression.
     * Map them to the appropriate button pushes when possible.  Leftover characters
     * are added to unprocessedChars, which is presumed to immediately precede the newly
     * added characters.
     * @param moreChars characters to be added
     * @param explicit these characters were explicitly typed by the user, not pasted
     */
    private fun addChars(moreChars: String, explicit: Boolean, selection: IntRange = currentSelection()) {
        val chars = unprocessedChars.orEmpty() + moreChars
        var current = 0
        val len = chars.length
        var lastWasDigit = false
        // Clear display immediately for incomplete function name.
        if (currentState == CalculatorState.RESULT && len != 0) {
            switchToInput(KeyMaps.keyForChar(chars[current]))
        } else {
            beginEdit(selection)
        }
        val groupingSeparator = KeyMaps.translateResult(",")[0]
        val addKey = if (explicit) ::addExplicitKeyToExpr else ::addKeyToExpr
        while (current < len) {
            val c = chars[current]
            if (Character.isSpaceChar(c) || c == groupingSeparator) {
                ++current
                continue
            }
            val k = KeyMaps.keyForChar(c)
            if (!explicit) {
                val expEnd = if (lastWasDigit) Evaluator.exponentEnd(chars, current) else current
                if (current != expEnd) {
                    // Process scientific notation with 'E' when pasting, in spite of ambiguity
                    // with base of natural log.
                    // Otherwise the 10^x key is the user's friend.
                    cursor = evaluator.addExponent(chars, current, expEnd, cursor)
                    current = expEnd
                    lastWasDigit = false
                    continue
                }
                val isDigit = KeyMaps.digVal(k) != KeyMaps.NOT_DIGIT
                if (current == 0 && (isDigit || k == R.id.dec_point) && mainExpr.hasConstantBefore(cursor)) {
                    // Refuse to concatenate pasted content to the constant before the cursor.
                    // This makes pasting of calculator results more consistent, whether or
                    // not the old calculator instance is still around.
                    addKeyToExpr(R.id.op_mul)
                }
                lastWasDigit = isDigit || lastWasDigit && k == R.id.dec_point
            }
            if (k != View.NO_ID) {
                addKey(k)
                current += if (c.isSurrogate()) 2 else 1
                continue
            }
            val f = KeyMaps.funForString(chars, current)
            if (f != View.NO_ID) {
                addKey(f)
                // Square root entered as function; don't lose the parenthesis.
                if (f == R.id.op_sqrt) addKeyToExpr(R.id.lparen)
                current = chars.indexOf('(', current) + 1
                continue
            }
            // There are characters left, but we can't convert them to button presses.
            unprocessedChars = chars.substring(current)
            redisplayAfterFormulaChange()
            return
        }
        unprocessedChars = null
        redisplayAfterFormulaChange()
    }

    private fun clearIfNotInputState() {
        if (currentState == CalculatorState.ERROR || currentState == CalculatorState.RESULT) {
            setState(CalculatorState.INPUT)
            evaluator.clearMain()
            cursor = mainExpr.end
        }
    }


    /**
     * Clean up animation for context menu.
     */
    override fun onContextMenuClosed(menu: Menu) {
        stopActionModeOrContextMenu()
    }

    fun interface OnDisplayMemoryOperationsListener {
        fun shouldDisplayMemory(): Boolean
    }

    /**
     * Lay the calculator out edge-to-edge. The side insets become padding of the root, whose
     * background is the app background. The top and bottom insets move two guidelines that the
     * sheet and the pads are constrained to in every state of the scene, so animating the sheet
     * never has to change any padding; two scrims in the display colour fill the bar areas.
     */
    private fun setupInsets() {
        mainCalculator.onSystemBarInsets { insets ->
            // The window re-dispatches unchanged insets whenever a view is added.
            if (insets == sceneInsets) return@onSystemBarInsets
            sceneInsets = insets
            mainCalculator.updatePadding(left = insets.left, right = insets.right)
            updateSceneGeometry()
        }
        // A new size (the first layout, a split screen resizing) refits the scene before the
        // frame is drawn, so that no frame shows the pads at the wrong size.
        mainCalculator.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                mainCalculator.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        mainCalculator.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                        return !updateSceneGeometry()
                    }
                })
            }
        }
    }

    /** The system bar insets the scene was last laid out for; see updateSceneGeometry(). */
    private var sceneInsets = Insets.NONE
    private var appliedInsets: Insets? = null
    private var appliedPadsTop = -1

    /**
     * Fit the scene to the window: the status and navigation bar guidelines go to the insets,
     * and the top of the pads goes 5/4 of their width above the navigation bar, unless that
     * would leave the display less than its minimum height, as in a split screen, in which
     * case the pads get what is left (and, sized by their height, narrower). In the history
     * states the pads are translated off the bottom of the screen from that same place.
     * Rebuilding the scene solves every state, so nothing is touched unless something changed;
     * returns whether it was. (The landscape scene sizes the pads by height and has no
     * guideline for their top.)
     */
    private fun updateSceneGeometry(): Boolean {
        val insets = sceneInsets
        val width = mainCalculator.width - mainCalculator.paddingLeft - mainCalculator.paddingRight
        val height = mainCalculator.height
        var padsTop = -1
        if (findViewById<View>(R.id.pads_top_guideline) != null && width > 0 && height > 0) {
            val padWidth = min(width, resources.getDimensionPixelSize(R.dimen.pad_max_width))
            val navigationBarTop = height - insets.bottom
            val displayBottom = insets.top + resources.getDimensionPixelSize(R.dimen.display_min_height)
            padsTop = max(displayBottom, navigationBarTop - (padWidth * 5 + 2) / 4).coerceAtMost(navigationBarTop)
        }
        if (insets == appliedInsets && padsTop == appliedPadsTop) return false
        appliedInsets = insets
        appliedPadsTop = padsTop
        SHEET_STATES.forEach { state ->
            mainCalculator.getConstraintSet(state)?.apply {
                setGuidelineBegin(R.id.status_bar_guideline, insets.top)
                setGuidelineEnd(R.id.navigation_bar_guideline, insets.bottom)
                if (padsTop >= 0) {
                    setGuidelineBegin(R.id.pads_top_guideline, padsTop)
                    val offScreen = if (state in HISTORY_ORIGINS) (height - padsTop).toFloat() else 0f
                    setTranslationY(R.id.input_pad, offScreen)
                    setTranslationY(R.id.advanced_pad, offScreen)
                }
            }
        }
        // The scene's own sets were edited in place: rebuild it once, then apply the current one.
        mainCalculator.updateState()
        mainCalculator.getConstraintSet(mainCalculator.currentState)?.applyTo(mainCalculator)
        return true
    }

    companion object {
        private const val TAG = "Calculator"

        /**
         * Constant for an invalid resource id.
         */
        const val INVALID_RES_ID = -1

        private const val NAME = "Calculator"
        private const val KEY_DISPLAY_STATE = NAME + "_display_state"
        private const val KEY_UNPROCESSED_CHARS = NAME + "_unprocessed_chars"

        /**
         * Associated value is a byte array holding the evaluator state.
         */
        private const val KEY_EVAL_STATE = NAME + "_eval_state"
        private const val KEY_INVERSE_MODE = NAME + "_inverse_mode"
        private const val KEY_ADVANCED_PAD = NAME + "_advanced_pad"

        /** The functions of the advanced pad that INV swaps for their inverses, and those. */
        private val INVERTIBLE_KEYS = listOf(
            R.id.fun_sin, R.id.fun_cos, R.id.fun_tan, R.id.fun_ln, R.id.fun_log, R.id.op_sqrt
        )
        private val INVERSE_KEYS = listOf(
            R.id.fun_arcsin, R.id.fun_arccos, R.id.fun_arctan, R.id.fun_exp, R.id.fun_10pow, R.id.op_sqr
        )

        private const val RTL_COMMA = '٫' // ARABIC DECIMAL SEPARATOR

        /** The two history positions of the display sheet, and the positions they are pulled down from. */
        private val HISTORY_ORIGINS = mapOf(
            R.id.state_history to R.id.state_basic,
            R.id.state_history_advanced to R.id.state_advanced
        )

        /** All positions of the display sheet, see activity_calculator_scene.xml. */
        private val SHEET_STATES = HISTORY_ORIGINS.keys + HISTORY_ORIGINS.values

        /** Key codes that the system, not the calculator, handles. */
        private val SYSTEM_KEY_CODES = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )
    }
}
