package dev.foldkeys

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

/**
 * iOS-style autocorrect on top of the phone's system spell checker.
 *
 * As you type, the word before the cursor is checked in the background. If it looks like a
 * typo, [correction] holds the fix; the service swaps it in when you hit space or punctuation.
 * Words you undo (delete right after a correction) are remembered and never corrected again.
 */
class AutoCorrect(context: Context, private val onChanged: () -> Unit) {

    private val prefs = context.getSharedPreferences("foldkeys", Context.MODE_PRIVATE)
    private val tsm = context.getSystemService(TextServicesManager::class.java)
    private var session: SpellCheckerSession? = null
    private var seq = 0
    private val queries = HashMap<Int, String>()

    /** The word the current results are for. */
    var checkedWord: String? = null
        private set
    var correction: String? = null
        private set
    var alternate: String? = null
        private set

    private val ignored: MutableSet<String>
        get() = prefs.getStringSet("ignored_words", emptySet()).orEmpty().toMutableSet()

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            results?.forEach { handle(it) }
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            results?.forEach { s ->
                if (s.suggestionsCount == 0) {
                    // Nothing flagged: the latest word is spelled fine.
                    clearResult()
                } else {
                    for (i in 0 until s.suggestionsCount) handle(s.getSuggestionsInfoAt(i))
                }
            }
        }
    }

    private fun ensureSession(): SpellCheckerSession? {
        if (session == null && tsm != null) {
            // Null when the phone's spell checker is turned off.
            @Suppress("DEPRECATION")
            session = tsm.newSpellCheckerSession(null, null, listener, true)
        }
        return session
    }

    /** Check [word] (the letters right before the cursor). Results arrive asynchronously. */
    fun check(word: String) {
        if (word == checkedWord) return
        checkedWord = word
        val had = correction != null
        correction = null
        alternate = null
        if (had) onChanged()
        if (word.length < 2 || word.any { it.isDigit() } || word.lowercase() in ignored) return
        val s = ensureSession() ?: return
        seq++
        queries[seq] = word
        s.getSentenceSuggestions(arrayOf(TextInfo(word, 0, seq)), 3)
    }

    private fun handle(info: SuggestionsInfo) {
        val word = queries.remove(info.sequence) ?: return
        if (word != checkedWord) return // stale: the user kept typing
        val attrs = info.suggestionsAttributes
        val typo = attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
        val known = attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0
        val options = (0 until maxOf(0, info.suggestionsCount))
            .map { info.getSuggestionAt(it) }
            .filter { it.isNotBlank() && !it.equals(word, ignoreCase = true) }
            .map { matchCase(word, it) }
        if (typo && !known && options.isNotEmpty()) {
            correction = options[0]
            alternate = options.getOrNull(1)
        } else {
            correction = null
            alternate = null
        }
        onChanged()
    }

    private fun clearResult() {
        if (correction != null || alternate != null) {
            correction = null
            alternate = null
            onChanged()
        }
    }

    /** Forget results (e.g. the cursor moved somewhere new). */
    fun reset() {
        checkedWord = null
        queries.clear()
        clearResult()
    }

    /** Never autocorrect this word again. */
    fun learn(word: String) {
        prefs.edit().putStringSet("ignored_words", ignored + word.lowercase()).apply()
    }

    fun close() {
        session?.close()
        session = null
    }

    private fun matchCase(original: String, s: String): String = when {
        original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> s.uppercase()
        original.first().isUpperCase() -> s.replaceFirstChar { it.uppercase() }
        else -> s
    }
}
