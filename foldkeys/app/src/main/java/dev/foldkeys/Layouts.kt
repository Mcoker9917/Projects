package dev.foldkeys

import android.graphics.RectF

enum class KeyType { CHAR, SPACER, SHIFT, DELETE, TO_NUMBERS, TO_SYMBOLS, TO_LETTERS, TO_EMOJI, GLOBE, SPACE, RETURN }

enum class Mode { LETTERS, NUMBERS, SYMBOLS, EMOJI }

enum class Shift { OFF, ONCE, LOCKED }

class Key(val type: KeyType, val label: String = "", val weight: Float = 1f) {
    /** Where the key is drawn, in view coordinates. */
    val rect = RectF()
}

/** One keyboard row. When the keyboard is split, [right] holds the right-hand half. */
class Row(val left: List<Key>, val right: List<Key>) {
    val all: List<Key> get() = left + right
}

object Layouts {

    private fun chars(s: String, w: Float = 1f) = s.map { Key(KeyType.CHAR, it.toString(), w) }
    private fun spacer(w: Float) = Key(KeyType.SPACER, weight = w)
    private fun shift() = Key(KeyType.SHIFT, weight = 1.35f)
    private fun delete() = Key(KeyType.DELETE, weight = 1.35f)

    /** Hold a key to get these, iOS style. The plain key comes first so letting go without sliding types it. */
    val accents: Map<String, List<String>> = mapOf(
        "a" to "àáâäæãåā", "c" to "çćč", "e" to "èéêëēėę", "i" to "îïíīįì", "l" to "ł",
        "n" to "ñń", "o" to "ôöòóœøōõ", "s" to "ßśš", "u" to "ûüùúū", "y" to "ÿ", "z" to "žźż",
        "0" to "°", "-" to "–—•", "/" to "\\", "$" to "¢€£¥₩", "&" to "§", "\"" to "„“”«»",
        "." to "…", "?" to "¿", "!" to "¡", "'" to "‘’`", "%" to "‰",
    ).mapValues { (k, v) -> listOf(k) + v.map { it.toString() } }

    /**
     * Builds the rows for a mode. Each of the top three rows has a split point:
     * the index where the left half ends when the keyboard is split for thumbs.
     */
    fun rows(mode: Mode, split: Boolean, globe: Boolean): List<Row> {
        if (mode == Mode.EMOJI) {
            // The emoji grid is drawn over the top three rows; the spacer holds the category tabs.
            val empty = Row(emptyList(), emptyList())
            return listOf(empty, empty, empty, Row(
                listOf(Key(KeyType.TO_LETTERS, "ABC", 1.25f), spacer(7.5f), Key(KeyType.DELETE, weight = 1.25f)),
                emptyList(),
            ))
        }
        val top: List<Pair<List<Key>, Int>> = when (mode) {
            Mode.LETTERS -> listOf(
                chars("qwertyuiop") to 5,
                (listOf(spacer(0.5f)) + chars("asdfghjkl") + spacer(0.5f)) to 6,
                (listOf(shift(), spacer(0.15f)) + chars("zxcvbnm") + listOf(spacer(0.15f), delete())) to 6,
            )
            Mode.NUMBERS -> listOf(
                chars("1234567890") to 5,
                chars("-/:;()$&@\"") to 5,
                (listOf(Key(KeyType.TO_SYMBOLS, "#+=", 1.35f), spacer(0.15f)) + chars(".,?!'", 1.4f) +
                    listOf(spacer(0.15f), delete())) to 4,
            )
            else -> listOf(
                chars("[]{}#%^*+=") to 5,
                chars("_\\|~<>€£¥•") to 5,
                (listOf(Key(KeyType.TO_NUMBERS, "123", 1.35f), spacer(0.15f)) + chars(".,?!'", 1.4f) +
                    listOf(spacer(0.15f), delete())) to 4,
            )
        }
        val rows = top.map { (keys, at) ->
            if (split) Row(keys.take(at), keys.drop(at)) else Row(keys, emptyList())
        }
        return rows + bottomRow(mode, split, globe)
    }

    private fun bottomRow(mode: Mode, split: Boolean, globe: Boolean): Row {
        val modeKey = if (mode == Mode.LETTERS) Key(KeyType.TO_NUMBERS, "123", 1.25f)
        else Key(KeyType.TO_LETTERS, "ABC", 1.25f)
        val emoji = Key(KeyType.TO_EMOJI, weight = 1.25f)
        val ret = Key(KeyType.RETURN, weight = 2.5f)
        return if (split) {
            val left = if (globe) listOf(modeKey, emoji, Key(KeyType.GLOBE, weight = 1.25f), Key(KeyType.SPACE, weight = 1.75f))
            else listOf(modeKey, emoji, Key(KeyType.SPACE, weight = 3f))
            Row(left, listOf(Key(KeyType.SPACE, weight = 3f), ret))
        } else {
            val keys = if (globe) listOf(modeKey, emoji, Key(KeyType.GLOBE, weight = 1.25f), Key(KeyType.SPACE, weight = 3.75f), ret)
            else listOf(modeKey, emoji, Key(KeyType.SPACE, weight = 5f), ret)
            Row(keys, emptyList())
        }
    }
}
