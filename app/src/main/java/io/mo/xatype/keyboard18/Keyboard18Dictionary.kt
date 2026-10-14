package io.mo.xatype.keyboard18

import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.ln

data class Pinyin18Entry(val text: String, val spelling: String, val weight: Int) {
    val code = spelling.replace(" ", "")
    val boundaries = buildSet {
        var position = 0
        spelling.forEach { if (it == ' ') add(position) else position++ }
    }
    val score = ln(weight.toDouble() + 1.0)
}

/** Project once when loading; each tap walks one trie path, never 2^n letter permutations. */
class Keyboard18Dictionary private constructor(val entries: List<Pinyin18Entry>) {
    private class Node {
        val children = HashMap<Char, Node>()
        val words = ArrayList<Pinyin18Entry>()
        val completions = ArrayList<Pinyin18Entry>()
    }
    private val root = Node()
    val maxLength = entries.maxOf { it.code.length }
    val maxScore = entries.maxOf { it.score }

    init {
        // Input rows are ordered by descending corpus frequency.
        entries.forEach { entry ->
            var node = root
            entry.code.forEach { letter ->
                node = node.children.getOrPut(Keyboard18Keys.representative(letter)) { Node() }
                if (node.completions.size < COMPLETIONS) node.completions.add(entry)
            }
            node.words.add(entry)
        }
    }

    private fun compatible(entry: Pinyin18Entry, raw: String, start: Int, end: Int): Boolean {
        var letter = 0
        for (position in start until end) {
            val token = raw[position]
            if (token == '\'') {
                if (letter !in entry.boundaries) return false
            } else {
                if (letter >= entry.code.length || !Keyboard18Keys.matches(token, entry.code[letter])) return false
                letter++
            }
        }
        return true
    }

    fun words(raw: String, start: Int): List<Pair<Int, Pinyin18Entry>> {
        val result = ArrayList<Pair<Int, Pinyin18Entry>>()
        var node = root
        var count = 0
        for (position in start until raw.length) {
            val token = raw[position]
            if (token == '\'') continue
            if (++count > maxLength) break
            node = node.children[Keyboard18Keys.projected(token)] ?: break
            var retained = 0
            for (entry in node.words) {
                if (compatible(entry, raw, start, position + 1)) {
                    result.add(position + 1 to entry)
                    if (++retained == WORDS_PER_CODE) break
                }
            }
        }
        return result
    }

    fun completions(raw: String, start: Int): List<Pinyin18Entry> {
        var node = root
        var count = 0
        for (position in start until raw.length) {
            if (raw[position] == '\'') continue
            count++
            node = node.children[Keyboard18Keys.projected(raw[position])] ?: return emptyList()
        }
        if (count == 0) return emptyList()
        return node.completions.filter { it.code.length > count && it.code.length - count <= 6 &&
            compatible(it, raw, start, raw.length) }.take(COMPLETIONS)
    }

    companion object {
        private const val COMPLETIONS = 24
        private const val WORDS_PER_CODE = 48
        fun read(input: InputStream): Keyboard18Dictionary {
            val rows = ArrayList<Pinyin18Entry>()
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('\t')
                    require(parts.size == 3 && parts[0].isNotEmpty()) { "拼音词库格式错误" }
                    val code = parts[1].replace(" ", "")
                    require(code.isNotEmpty() && code.length <= 48 && code.all { it in 'a'..'z' })
                    rows.add(Pinyin18Entry(parts[0], parts[1], parts[2].toInt().coerceAtLeast(0)))
                }
            }
            require(rows.isNotEmpty() && rows.size <= 300_000) { "拼音词库为空或过大" }
            return Keyboard18Dictionary(rows.sortedByDescending { it.weight })
        }
    }
}
