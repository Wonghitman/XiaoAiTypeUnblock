package io.mo.xatype.keyboard

import java.io.Reader
import java.util.Locale

/** Complete Unicode sequences stay intact, including ZWJ and variation selectors. */
class EmojiCandidates private constructor(private val words: Map<String, Entry>) {
    private data class Entry(val readings: List<List<String>>, val emojis: List<String>) {
        val spellings = readings.map { it.joinToString("") }.toSet()

        fun matches(spelling: String, scheme: ShuangpinScheme?, nineKey: Boolean): Boolean {
            // Nine-key raw input may be digits for both full pinyin and ShuangPin codes.
            fun same(code: String) = spelling == code || (nineKey && spelling == code.map { letter ->
                when (letter) {
                    in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
                    in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
                    in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> letter
                }
            }.joinToString(""))
            if (spellings.any(::same)) return true
            if (scheme == null) return false
            return readings.any { syllables ->
                same(syllables.map { scheme.encodeSyllable(it) ?: return@any false }.joinToString(""))
            }
        }
    }
    data class Addition(val after: Int, val text: String)

    fun additions(raw: String, candidates: List<String>, scheme: ShuangpinScheme? = null,
                  nineKey: Boolean = false): List<Addition> {
        val spelling = raw.lowercase(Locale.ROOT).replace('ü', 'v').filterNot { it == '\'' || it == ' ' }
        if (spelling.isEmpty() || spelling.any { it !in 'a'..'z' &&
                !(nineKey && it in '2'..'9') && !(scheme != null && it == ';') }) return emptyList()
        val seen = candidates.mapTo(HashSet()) { it.replace("\uFE0F", "") }
        val result = ArrayList<Addition>()
        // ponytail: only full, unselected compositions. Supporting partial phrases
        // requires the decoder's consumed-span metadata, never clearing a suffix.
        candidates.take(5).forEachIndexed { index, word ->
            val entry = words[word] ?: return@forEachIndexed
            if (!entry.matches(spelling, scheme, nineKey)) return@forEachIndexed
            var count = 0
            for (emoji in entry.emojis) {
                if (count == 3 || result.size == 6) break
                if (seen.add(emoji.replace("\uFE0F", ""))) {
                    result.add(Addition(index, emoji))
                    count++
                }
            }
        }
        return result
    }

    companion object {
        fun read(reader: Reader): EmojiCandidates = reader.buffered().useLines { lines ->
            val words = LinkedHashMap<String, Entry>()
            lines.forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 3 && parts.all { it.isNotBlank() }) { "Invalid emoji table row" }
                val readings = parts[1].split(' ').map { it.split('\'') }
                require(readings.all { r -> r.all { s -> s.isNotEmpty() && s.all { it in 'a'..'z' } } })
                require(words.put(parts[0], Entry(readings, parts[2].split(' '))) == null) { "Duplicate emoji keyword" }
                require(words.size <= 20_000) { "Emoji table too large" }
            }
            require(words.isNotEmpty()) { "Empty emoji table" }
            EmojiCandidates(words)
        }
    }
}
