package dev.foldkeys

import android.content.Context
import java.util.concurrent.Executors

/**
 * Word completions, next-word guesses and instant autocorrect for the suggestion bar.
 *
 * Uses a list of the 120,000 most common English words with how common each is
 * (wordfreq "zipf" scores: 7 = "the", 3 = rare). Next-word guesses come from word pairs
 * learned on this phone, falling back to common starters.
 */
class Predictor(context: Context) {

    private val prefs = context.getSharedPreferences("foldkeys_words", Context.MODE_PRIVATE)

    /** Words, most common first, for completions. */
    @Volatile private var words: List<String> = emptyList()
    /** word -> zipf * 100 */
    @Volatile private var freq: Map<String, Int> = emptyMap()

    /** previous word -> (next word -> count), learned from typing. */
    private val pairs = HashMap<String, HashMap<String, Int>>()
    private var dirty = 0

    init {
        val app = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            val list = ArrayList<String>(120_000)
            val map = HashMap<String, Int>(160_000)
            try {
                app.assets.open("lexicon.txt").bufferedReader().forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val w = line.substring(0, tab)
                        list += w
                        map[w] = line.substring(tab + 1).toIntOrNull() ?: 0
                    }
                }
            } catch (e: Exception) {
                // No list: completions and instant corrections are simply off.
            }
            freq = map
            words = list
        }
        prefs.all.forEach { (prev, value) ->
            val m = HashMap<String, Int>()
            (value as? String)?.split(",")?.forEach { entry ->
                val i = entry.lastIndexOf(':')
                if (i > 0) m[entry.substring(0, i)] = entry.substring(i + 1).toIntOrNull() ?: 1
            }
            if (m.isNotEmpty()) pairs[prev] = m
        }
    }

    // ---- completions & next word -----------------------------------------------------------

    /** Up to [n] common words starting with [prefix], not counting the prefix itself. */
    fun complete(prefix: String, n: Int): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        val out = ArrayList<String>(n)
        for (w in words) {
            if (w.length > p.length && w.startsWith(p)) {
                out += w
                if (out.size == n) break
            }
        }
        return out.map { fixCase(prefix, it) }
    }

    /** Guesses for the word after [previous] ("^" at the start of a sentence). */
    fun next(previous: String, sentenceStart: Boolean, n: Int): List<String> {
        val learned = pairs[previous.lowercase()].orEmpty().entries
            .sortedByDescending { it.value }.map { it.key }
        val fallback = if (sentenceStart) listOf("I", "The", "I'm") else listOf("the", "I", "to")
        return (learned + fallback)
            .map { if (sentenceStart) it.replaceFirstChar { c -> c.uppercase() } else fixI(it) }
            .distinctBy { it.lowercase() }
            .take(n)
    }

    /** Remember that [next] followed [previous]. */
    fun learn(previous: String, next: String) {
        if (previous.isEmpty() || next.isEmpty()) return
        val m = pairs.getOrPut(previous.lowercase()) { HashMap() }
        val key = next.lowercase()
        m[key] = (m[key] ?: 0) + 1
        // Keep each list short so it stays fast and small.
        if (m.size > 12) m.remove(m.minByOrNull { it.value }!!.key)
        if (++dirty >= 5) save()
    }

    fun save() {
        if (dirty == 0) return
        dirty = 0
        val e = prefs.edit().clear()
        pairs.forEach { (prev, m) -> e.putString(prev, m.entries.joinToString(",") { "${it.key}:${it.value}" }) }
        e.apply()
    }

    // ---- autocorrect ---------------------------------------------------------------------

    private var memoKey: String? = null
    private var memoFix: String? = null

    /**
     * The correction for [typed], or null to leave it alone. Runs instantly (no waiting on
     * the system spell checker), so it's ready the moment space is pressed.
     *
     * Rules, from how common words are:
     *  - "i" -> "I"
     *  - a missing apostrophe is fixed when the apostrophe word is clearly more common
     *    ("dont" -> "don't", "im" -> "I'm"; "its" and "well" are left alone)
     *  - otherwise a word one typo away replaces it when it is far more common
     *    ("teh" -> "the", "recieve" -> "receive"; "form" stays "form")
     *  - words not in the list at all get the most likely close word
     */
    fun correct(typed: String, deep: Boolean): String? {
        val key = "$deep|$typed"
        if (key == memoKey) return memoFix
        val fix = computeCorrection(typed, deep)
        memoKey = key
        memoFix = fix
        return fix
    }

    /** [deep] also tries two-typo fixes; that's slower, so it only runs when space is pressed. */
    private fun computeCorrection(typed: String, deep: Boolean): String? {
        val f = freq
        if (f.isEmpty() || typed.isEmpty()) return null
        val w = typed.lowercase().replace('’', '\'')
        if (w == "i") return if (typed == "i") "I" else null
        if (w.any { !it.isLetter() && it != '\'' }) return null
        val own = f[w] ?: 0

        // 1. Missing apostrophe: dont -> don't
        var best: String? = null
        var bestScore = 0
        if (!w.contains('\'')) {
            for (i in 1 until w.length) {
                val cand = w.substring(0, i) + "'" + w.substring(i)
                val cf = f[cand] ?: continue
                if (cf - own >= 100 && cf > bestScore) { best = cand; bestScore = cf }
            }
            if (best != null) return fixCase(typed, best)
        }
        if (w.length < 3) return null

        // 2. One typo away (swap, wrong key, missing or extra letter)
        val need1 = if (own == 0) 0 else 200   // known-but-rare words need a much more common fix
        for ((cand, bonus) in edits1(w)) {
            val cf = f[cand] ?: continue
            val score = cf + bonus
            if (cf - own >= need1 && score > bestScore) { best = cand; bestScore = score }
        }
        if (best != null) return fixCase(typed, best)

        // 3. Two typos away: longer words we don't know, or known misspellings far rarer
        //    than the fix ("tommorow" -> "tomorrow")
        if (deep && own <= 520 && ((own == 0 && w.length >= 5) || w.length >= 6)) {
            val need2 = if (own == 0) 300 else own + 250
            for ((c1, _) in edits1(w)) {
                for ((cand, _) in edits1(c1)) {
                    val cf = f[cand] ?: continue
                    if (cf >= need2 && cf > bestScore) { best = cand; bestScore = cf }
                }
            }
            if (best != null) return fixCase(typed, best)
        }
        return null
    }

    /** Every string one edit from [w], with a small bonus for likely finger slips. */
    private fun edits1(w: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(w.length * 56)
        for (i in w.indices) {
            out += (w.removeRange(i, i + 1) to 0)                                    // extra letter
            if (i + 1 < w.length) {                                                  // swapped letters
                out += (w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) to 50)
            }
            for (c in 'a'..'z') {                                                    // wrong key
                if (c != w[i]) {
                    val near = NEIGHBOURS[w[i]]?.contains(c) == true
                    out += (w.substring(0, i) + c + w.substring(i + 1) to if (near) 30 else 0)
                }
            }
        }
        for (i in 0..w.length) for (c in 'a'..'z') {                                 // missing letter
            // Typing one letter of a double ("helo" for "hello") is the most common slip of all.
            val doubled = (i > 0 && w[i - 1] == c) || (i < w.length && w[i] == c)
            out += (w.substring(0, i) + c + w.substring(i) to if (doubled) 150 else 0)
        }
        return out
    }

    private fun fixI(w: String) = if (w == "i" || w.startsWith("i'")) "I" + w.substring(1) else w

    private fun fixCase(typed: String, w: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        typed.first().isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> fixI(w)
    }

    private companion object {
        /** Keys next to each other on QWERTY, for "wrong key" typos. */
        val NEIGHBOURS: Map<Char, String> = run {
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val m = HashMap<Char, String>()
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { i, ch ->
                    val sb = StringBuilder()
                    if (i > 0) sb.append(row[i - 1])
                    if (i + 1 < row.length) sb.append(row[i + 1])
                    for (rr in listOf(r - 1, r + 1)) {
                        val other = rows.getOrNull(rr) ?: continue
                        for (j in i - 1..i + 1) other.getOrNull(j)?.let { sb.append(it) }
                    }
                    m[ch] = sb.toString()
                }
            }
            m
        }
    }
}
