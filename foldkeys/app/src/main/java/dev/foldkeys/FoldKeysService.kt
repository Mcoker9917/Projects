package dev.foldkeys

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

class FoldKeysService : InputMethodService(), KeyboardView.Listener {

    private var view: KeyboardView? = null
    private var mode = Mode.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var typedInAltMode = false

    // Autocorrect
    private val auto by lazy { AutoCorrect(this) { updateBar() } }
    private var correctThisField = false
    /** The last autocorrection (original, corrected, what was typed after it), so delete can undo it. */
    private var lastFix: Triple<String, String, String>? = null
    private var barWords: List<String> = emptyList()

    // Split for thumbs on the big inner screen (and whenever the phone is half folded).
    // Long-press 123/ABC to force it on or off; that choice resets on fold/unfold.
    private var foldHalfOpen = false
    private var splitOverride: Boolean? = null
    private var onInnerScreen = false

    private fun isInnerScreen() = resources.configuration.smallestScreenWidthDp >= 600

    private var tracker: WindowInfoTrackerCallbackAdapter? = null
    private val layoutListener = Consumer<WindowLayoutInfo> { info ->
        foldHalfOpen = info.displayFeatures.filterIsInstance<FoldingFeature>()
            .any { it.state == FoldingFeature.State.HALF_OPENED }
        applySplit()
    }

    override fun onCreate() {
        super.onCreate()
        onInnerScreen = isInnerScreen()
        try {
            tracker = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this)).also {
                it.addWindowLayoutInfoListener(this, mainExecutor, layoutListener)
            }
        } catch (e: Exception) {
            tracker = null // No fold info on this device; the manual toggle still works.
        }
    }

    override fun onDestroy() {
        auto.close()
        tracker?.removeWindowLayoutInfoListener(layoutListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        view = v
        syncView()
        return v
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val inner = isInnerScreen()
        if (inner != onInnerScreen) {
            onInnerScreen = inner
            splitOverride = null
        }
        applySplit()
        view?.invalidate()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        mode = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Mode.NUMBERS
            else -> Mode.LETTERS
        }
        typedInAltMode = false
        if (shift != Shift.LOCKED) shift = Shift.OFF
        view?.reset()
        updateReturnKey(info)
        syncView()
        autoShift()
        correctThisField = shouldCorrect(info)
        lastFix = null
        auto.reset()
        updateBar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        view?.reset()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        autoShift()
        if (correctThisField) auto.check(currentWord())
    }

    // ---- autocorrect --------------------------------------------------------------------

    /** Like iOS: no autocorrect in passwords, emails, web addresses, or fields that opt out. */
    private fun shouldCorrect(info: EditorInfo): Boolean {
        val t = info.inputType
        if (t and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /** The word right before the cursor. */
    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        return before.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }.trimStart('\'', '’')
    }

    /** QuickType-style bar: "typed word" | correction (highlighted) | alternate. */
    private fun updateBar() {
        val fix = auto.correction
        val word = auto.checkedWord
        barWords = if (correctThisField && fix != null && word != null && word == currentWord()) {
            listOf(word, fix, auto.alternate.orEmpty())
        } else emptyList()
        view?.setSuggestions(
            if (barWords.isEmpty()) emptyList() else listOf("\u201C${barWords[0]}\u201D", barWords[1], barWords[2]),
            if (barWords.isEmpty()) -1 else 1,
        )
    }

    /** Swap in the pending correction before [suffix] (a space or punctuation). */
    private fun applyCorrection(suffix: String): Boolean {
        if (!correctThisField) return false
        val ic = currentInputConnection ?: return false
        val fix = auto.correction ?: return false
        val word = currentWord()
        if (word.isEmpty() || word != auto.checkedWord) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fix + suffix, 1)
        ic.endBatchEdit()
        lastFix = Triple(word, fix, suffix)
        return true
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val pick = barWords.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        val word = currentWord()
        if (index == 0) auto.learn(word) // tapping your own word keeps it and stops future corrections
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$pick ", 1)
        ic.endBatchEdit()
        lastFix = null
    }

    /** Leave the strip above the keys see-through and untouchable, so bubbles can float over the app. */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val v = view ?: return
        if (!v.isShown || v.height == 0) return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val top = loc[1] + v.popupSpace
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, v.width, loc[1] + v.height)
    }

    // ---- state helpers ------------------------------------------------------------------

    private fun syncView() {
        val v = view ?: return
        v.mode = mode
        v.shift = shift
        v.showGlobe = shouldOfferSwitchingToNextInputMethod()
        applySplit()
    }

    private fun applySplit() {
        view?.split = splitOverride ?: (onInnerScreen || foldHalfOpen)
    }

    private fun setShift(s: Shift) {
        shift = s
        view?.shift = s
    }

    private fun setMode(m: Mode) {
        mode = m
        typedInAltMode = false
        view?.mode = m
        autoShift()
    }

    /** Capitalize at the start of a sentence, when the text field asks for it. */
    private fun autoShift() {
        if (shift == Shift.LOCKED || mode != Mode.LETTERS) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        val caps = ic.getCursorCapsMode(info.inputType) != 0
        setShift(if (caps) Shift.ONCE else Shift.OFF)
    }

    private fun updateReturnKey(info: EditorInfo) {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 || isMultiLine(info)
        val (label, blue) = if (noAction) "return" to false else when (action) {
            EditorInfo.IME_ACTION_GO -> "go" to true
            EditorInfo.IME_ACTION_SEARCH -> "search" to true
            EditorInfo.IME_ACTION_SEND -> "send" to true
            EditorInfo.IME_ACTION_DONE -> "done" to true
            EditorInfo.IME_ACTION_NEXT -> "next" to false
            else -> "return" to false
        }
        view?.returnLabel = label
        view?.returnHighlighted = blue
    }

    private fun isMultiLine(info: EditorInfo) =
        info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    // ---- key actions --------------------------------------------------------------------

    override fun onCharacter(text: String) {
        val ic = currentInputConnection ?: return
        lastFix = null
        val out = if (mode == Mode.LETTERS && shift != Shift.OFF && text != "ß") text.uppercase() else text
        if (text.length == 1 && text in ".,?!;:" && applyCorrection(out)) {
            if (mode != Mode.LETTERS && mode != Mode.EMOJI) typedInAltMode = true
            return
        }
        ic.commitText(out, 1)
        if (mode == Mode.LETTERS) {
            if (shift == Shift.ONCE) setShift(Shift.OFF)
        } else if (mode != Mode.EMOJI) {
            typedInAltMode = true
            if (text == "'") setMode(Mode.LETTERS) // iOS jumps back to letters after an apostrophe
        }
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        // Double-tap space for ". "
        if (now - lastSpaceTap < 450 && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTap = 0
        } else {
            lastFix = null
            if (!applyCorrection(" ")) ic.commitText(" ", 1)
            lastSpaceTap = now
        }
        if (mode != Mode.LETTERS && typedInAltMode) setMode(Mode.LETTERS)
    }

    override fun onDelete(repeatCount: Int) {
        val ic = currentInputConnection ?: return
        // Delete right after an autocorrection puts your original word back, and remembers it.
        val fix = lastFix
        lastFix = null
        if (fix != null && repeatCount == 0) {
            val (original, corrected, suffix) = fix
            val tail = corrected + suffix
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                ic.deleteSurroundingText(tail.length, 0)
                ic.commitText(original, 1)
                auto.learn(original)
                return
            }
        }
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString()
        when {
            before.isNullOrEmpty() -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) // terminals, games, etc.
            repeatCount > 20 -> {
                // After holding a while, delete whole words like iOS.
                var n = before.length
                while (n > 0 && before[n - 1].isWhitespace()) n--
                while (n > 0 && !before[n - 1].isWhitespace()) n--
                ic.deleteSurroundingText(maxOf(1, before.length - n), 0)
            }
            else -> {
                // Delete one visible character, so a multi-part emoji like 👩‍💻 or a flag goes in one tap.
                val bi = android.icu.text.BreakIterator.getCharacterInstance()
                bi.setText(before)
                val start = bi.preceding(before.length).coerceAtLeast(0)
                ic.deleteSurroundingText(maxOf(1, before.length - start), 0)
            }
        }
    }

    override fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        val next = when {
            now - lastShiftTap < 300 && shift != Shift.LOCKED -> Shift.LOCKED // double-tap = caps lock
            shift == Shift.OFF -> Shift.ONCE
            else -> Shift.OFF
        }
        lastShiftTap = now
        setShift(next)
    }

    override fun onReturn() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        when {
            info != null && isMultiLine(info) -> ic.commitText("\n", 1)
            !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                ic.performEditorAction(action)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onModeKey(type: KeyType) {
        when (type) {
            KeyType.TO_NUMBERS -> setMode(Mode.NUMBERS)
            KeyType.TO_SYMBOLS -> setMode(Mode.SYMBOLS)
            KeyType.TO_LETTERS -> setMode(Mode.LETTERS)
            KeyType.TO_EMOJI -> setMode(Mode.EMOJI)
            else -> Unit
        }
    }

    override fun onGlobe(longPress: Boolean) {
        if (longPress) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        } else {
            switchToNextInputMethod(false)
        }
    }

    override fun onToggleSplit() {
        val current = view?.split ?: false
        splitOverride = !current
        applySplit()
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        repeat(kotlin.math.abs(dx)) {
            sendDownUpKeyEvents(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        }
        repeat(kotlin.math.abs(dy)) {
            sendDownUpKeyEvents(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
    }
}
