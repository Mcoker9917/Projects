package dev.foldkeys

import android.content.Context
import java.util.concurrent.Executors

/**
 * Finds emoji by keyword ("party" → 🎉 🥳 …). Keywords come from emojibase (MIT licensed),
 * stored in assets/emoji_keywords.txt as: emoji <tab> name <tab> keywords.
 */
object EmojiSearch {

    private class Entry(val emoji: String, val name: String, val words: List<String>)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var loading = false

    fun load(context: Context) {
        if (entries.isNotEmpty() || loading) return
        loading = true
        val app = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            entries = try {
                app.assets.open("emoji_keywords.txt").bufferedReader().readLines().mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) null else Entry(parts[0], parts[1], parts[2].split(' '))
                }
            } catch (e: Exception) {
                emptyList()
            }
            loading = false
        }
    }

    /** Emoji whose name or keywords start with every word typed, best matches first. */
    fun search(query: String, limit: Int): List<String> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val q = tokens.joinToString(" ")
        return entries.asSequence()
            .filter { e -> tokens.all { t -> e.words.any { it.startsWith(t) } || e.name.contains(t) } }
            .sortedBy { e ->
                when {
                    e.name == q -> 0
                    e.name.startsWith(q) -> 1
                    e.words.contains(tokens[0]) -> 2
                    else -> 3
                }
            }
            .map { it.emoji }
            .take(limit)
            .toList()
    }
}
