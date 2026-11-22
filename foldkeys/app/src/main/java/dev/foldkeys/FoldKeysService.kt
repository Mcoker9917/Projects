package dev.foldkeys

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private val predictor by lazy { Predictor(this) }

    // Emoji search: non-null while the search box is open; typing goes into it, not the app.
    private var emojiQuery: String? = null
    private val clicks by lazy { KeyClick(this) }

    // Voice typing
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var partialSpeech = ""

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
        predictor.save()
        recognizer?.destroy()
        clicks.release()
        tracker?.removeWindowLayoutInfoListener(layoutListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        view = v
        clicks // load the click sounds now so the first tap isn't silent
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
        if (emojiQuery != null) {
            emojiQuery = null
            view?.searchQuery = null
            view?.searchResults = emptyList()
        }
        lastFix = null
        auto.reset()
        updateBar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        view?.reset()
        stopListening(cancel = true)
        endSearch()
        predictor.save()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        autoShift()
        if (correctThisField) auto.check(currentWord())
        updateBar()
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

    /**
     * The word before the current one, "^" at the start of a sentence, or "" when there
     * isn't a usable one (after a comma, say).
     */
    private fun previousWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val rest = before.dropLastWhile { it.isLetter() || it == '\'' || it == '’' }.trimEnd()
        if (rest.isEmpty()) return "^"
        val last = rest.last()
        return when {
            last.isLetter() -> rest.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }
            last in ".!?\n" -> "^"
            else -> ""
        }
    }

    /**
     * QuickType bar. While typing a word: "typed" | correction (highlighted) or completion | another.
     * Between words: three next-word guesses. While dictating: what's been heard so far.
     */
    private fun updateBar() {
        if (emojiQuery != null) {
            barWords = emptyList()
            view?.setSuggestions(emptyList(), -1)
            return
        }
        if (listening) {
            barWords = emptyList()
            view?.setSuggestions(listOf("", partialSpeech.ifEmpty { "Listening…" }, ""), -1)
            return
        }
        if (!correctThisField || mode == Mode.EMOJI) {
            barWords = emptyList()
            view?.setSuggestions(emptyList(), -1)
            return
        }
        val word = currentWord()
        var highlight = -1
        barWords = if (word.isNotEmpty()) {
            val fix = auto.correction?.takeIf { auto.checkedWord == word }
            if (fix != null) {
                highlight = 1
                listOf(word, fix, auto.alternate ?: predictor.complete(word, 1).firstOrNull().orEmpty())
            } else {
                val more = predictor.complete(word, 2)
                listOf(word, more.getOrNull(0).orEmpty(), more.getOrNull(1).orEmpty())
            }
        } else {
            val prev = previousWord()
            predictor.next(prev, sentenceStart = prev == "^", n = 3)
        }
        val shown = barWords.mapIndexed { i, w -> if (i == 0 && word.isNotEmpty()) "\u201C$w\u201D" else w }
        view?.setSuggestions(shown, highlight)
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
        val prev = previousWord()
        if (word.isNotEmpty() && index == 0) auto.learn(word) // keep your own word, stop correcting it
        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$pick ", 1)
        ic.endBatchEdit()
        predictor.learn(prev, pick)
        lastFix = null
        if (shift == Shift.ONCE) setShift(Shift.OFF)
    }

    // ---- emoji search -------------------------------------------------------------------

    override fun onEmojiSearchStart() {
        EmojiSearch.load(this)
        emojiQuery = ""
        setShift(Shift.OFF)
        setMode(Mode.LETTERS)
        refreshSearch()
    }

    override fun onEmojiResult(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        view?.rememberEmoji(emoji)
    }

    private fun refreshSearch() {
        val q = emojiQuery
        view?.searchQuery = q
        view?.searchResults = if (q == null) emptyList() else EmojiSearch.search(q, 24)
        updateBar()
    }

    /** Close the search box; optionally go back to the emoji panel (like tapping Done on iOS). */
    private fun endSearch(backToEmoji: Boolean = false) {
        if (emojiQuery == null) return
        emojiQuery = null
        view?.searchQuery = null
        view?.searchResults = emptyList()
        setMode(if (backToEmoji) Mode.EMOJI else Mode.LETTERS)
    }

    override fun onKeySound(type: KeyType) = clicks.play(type)

    // ---- voice typing -------------------------------------------------------------------

    override fun onMic() {
        if (listening) {
            stopListening(cancel = false)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Permissions can only be asked for from a screen, so open the setup screen.
            startActivity(Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ask_mic", true))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(speechListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        partialSpeech = ""
        listening = true
        view?.micActive = true
        updateBar()
        r.startListening(intent)
    }

    private fun stopListening(cancel: Boolean) {
        if (!listening) return
        if (cancel) {
            recognizer?.cancel()
            endListening()
        } else {
            recognizer?.stopListening() // final results still arrive
        }
    }

    private fun endListening() {
        listening = false
        partialSpeech = ""
        view?.micActive = false
        updateBar()
    }

    private fun insertSpeech(heard: String) {
        val ic = currentInputConnection ?: return
        if (heard.isBlank()) return
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val info = currentInputEditorInfo
        val caps = info != null && ic.getCursorCapsMode(info.inputType or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        var text = if (caps) heard.replaceFirstChar { it.uppercase() } else heard
        if (before.isNotEmpty() && !before.last().isWhitespace()) text = " $text"
        ic.commitText(text, 1)
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialSpeech = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            updateBar()
        }

        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            endListening()
            insertSpeech(heard)
        }

        override fun onError(error: Int) = endListening()
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
        updateBar()
    }

    /** Capitalize at the start of a sentence, when the text field asks for it. */
    private fun autoShift() {
        if (shift == Shift.LOCKED || mode != Mode.LETTERS || emojiQuery != null) return
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
        emojiQuery?.let { q ->
            emojiQuery = q + text.lowercase()
            refreshSearch()
            return
        }
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
        emojiQuery?.let { q ->
            if (q.isNotEmpty() && !q.endsWith(" ")) emojiQuery = "$q "
            refreshSearch()
            return
        }
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        // Double-tap space for ". "
        if (now - lastSpaceTap < 450 && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTap = 0
        } else {
            lastFix = null
            val word = currentWord()
            val prev = previousWord()
            if (!applyCorrection(" ")) ic.commitText(" ", 1)
            if (word.isNotEmpty()) predictor.learn(prev, lastFix?.second ?: word)
            lastSpaceTap = now
        }
        if (mode != Mode.LETTERS && typedInAltMode) setMode(Mode.LETTERS)
    }

    override fun onDelete(repeatCount: Int) {
        val ic = currentInputConnection ?: return
        emojiQuery?.let { q ->
            emojiQuery = if (repeatCount > 20) "" else q.dropLast(1)
            refreshSearch()
            return
        }
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
        if (emojiQuery != null) {
            endSearch(backToEmoji = true)
            return
        }
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
            KeyType.TO_EMOJI -> when {
                emojiQuery != null -> endSearch(backToEmoji = true)
                mode == Mode.EMOJI -> setMode(Mode.LETTERS)
                else -> setMode(Mode.EMOJI)
            }
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
