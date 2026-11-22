package dev.foldkeys

import android.content.Context
import java.util.concurrent.Executors

/**
 * Word completions and next-word guesses for the suggestion bar.
 *
 * Completions come from a list of the 30,000 most common English words, most common first.
 * Next-word guesses come from what you have typed before (word pairs learned on this phone),
 * falling back to common starters.
 */
class Predictor(context: Context) {

    private val prefs = context.getSharedPreferences("foldkeys_words", Context.MODE_PRIVATE)
    @Volatile private var words: List<String> = emptyList()
    @Volatile private var wordSet: Set<String> = emptySet()

    /** previous word -> (next word -> count), learned from typing. */
    private val pairs = HashMap<String, HashMap<String, Int>>()
    private var dirty = 0

    init {
        val app = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            val list = try {
                app.assets.open("words.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
            wordSet = list.toHashSet()
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

    fun isKnown(word: String): Boolean = word.lowercase() in wordSet

    /** Guesses for the word after [previous] (empty at the start of a sentence). */
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
        val prev = previous.lowercase()
        val m = pairs.getOrPut(prev) { HashMap() }
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

    private fun fixI(w: String) = if (w == "i" || w.startsWith("i'")) "I" + w.substring(1) else w

    private fun fixCase(typed: String, w: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        typed.first().isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> fixI(w)
    }
}
