package io.mo.xatype.keyboard18

data class Pinyin18Candidate(val text: String, val spelling: String, val consumed: Int, val score: Double)
data class Pinyin18Result(val consumed: Boolean, val raw: String, val display: String,
    val candidates: List<Pinyin18Candidate>, val commit: String? = null)

/** Bounded word lattice with sentence composition, prefix selection and exact-letter constraints. */
class Keyboard18Engine(private val dictionary: Keyboard18Dictionary,
    private val frequency: (String) -> Int = { 0 }, private val learn: (String) -> Unit = {}) {
    var raw: String = ""
        private set
    var candidates: List<Pinyin18Candidate> = emptyList()
        private set
    val display: String get() = Keyboard18Keys.readable(raw)
    fun snapshot(consumed: Boolean = true, commit: String? = null) =
        Pinyin18Result(consumed, raw, display, candidates, commit)
    fun clear() { raw = ""; candidates = emptyList() }
    fun restore(value: String): Pinyin18Result {
        if (value.length > MAX_INPUT || value.any { !Keyboard18Keys.valid(it) }) return snapshot(false)
        raw = value; refresh(); return snapshot()
    }
    fun backspace(): Pinyin18Result {
        if (raw.isEmpty()) return snapshot(false)
        raw = raw.dropLast(1); refresh(); return snapshot()
    }
    fun select(index: Int): String? {
        val candidate = candidates.getOrNull(index) ?: return null
        raw = raw.drop(candidate.consumed).trimStart('\'')
        learn(candidate.text)
        refresh()
        return candidate.text
    }
    fun commitFirst(): String? {
        if (raw.isEmpty()) return null
        // Space commits the highest ranked candidate; a prefix selection keeps the suffix composing.
        if (candidates.isNotEmpty()) return select(0)
        val literal = display
        clear()
        return literal
    }
    fun commitAll(): String? {
        if (raw.isEmpty()) return null
        val result = StringBuilder()
        while (raw.isNotEmpty()) result.append(commitFirst().orEmpty())
        return result.toString()
    }
    fun input(token: Char): Pinyin18Result {
        if (token == '\b') return backspace()
        if (token == ' ') return snapshot(raw.isNotEmpty(), commitFirst())
        if (token == '\n') return snapshot(raw.isNotEmpty(), commitAll())
        if (Keyboard18Keys.valid(token)) {
            if (token == '\'' && (raw.isEmpty() || raw.last() == '\'')) return snapshot()
            var commit: String? = null
            if (raw.length == MAX_INPUT) commit = commitAll()
            raw += token; refresh(); return snapshot(commit = commit)
        }
        if (raw.isEmpty()) return snapshot(false)
        val text = commitAll().orEmpty() + token
        return snapshot(commit = text)
    }
    fun inputText(text: String): Pinyin18Result {
        val commits = StringBuilder()
        text.forEach { token ->
            val result = input(token)
            result.commit?.let(commits::append)
            if (!result.consumed) commits.append(token)
        }
        return snapshot(text.isNotEmpty(), commits.toString().ifEmpty { null })
    }

    private data class Path(val text: String, val spelling: String, val score: Double)
    private fun refresh() {
        if (raw.isEmpty()) { candidates = emptyList(); return }
        val paths = Array(raw.length + 1) { mutableListOf<Path>() }
        paths[0].add(Path("", "", 0.0))
        val direct = dictionary.words(raw, 0)
        for (start in raw.indices) {
            if (paths[start].isEmpty()) continue
            if (raw[start] == '\'') {
                paths[start + 1].addAll(paths[start]); prune(paths[start + 1]); continue
            }
            val words = if (start == 0) direct else dictionary.words(raw, start)
            for ((end, entry) in words) {
                val score = entry.score - dictionary.maxScore - WORD_PENALTY + learning(entry.text)
                paths[start].forEach { path -> paths[end].add(Path(path.text + entry.text,
                    (path.spelling + " " + entry.spelling).trim(), path.score + score)) }
                prune(paths[end])
            }
        }
        val whole = paths[raw.length].map { Pinyin18Candidate(it.text, it.spelling, raw.length, it.score) }
        val prefix = direct.map { (end, entry) -> Pinyin18Candidate(entry.text, entry.spelling, end,
            entry.score - dictionary.maxScore + learning(entry.text)) }
        val completion = dictionary.completions(raw, 0).map { entry ->
            Pinyin18Candidate(entry.text, entry.spelling + " · 补全", raw.length,
                entry.score - dictionary.maxScore - 3.0 + learning(entry.text)) }
        // Full input matches precede shorter word selections; incomplete spelling offers completions.
        candidates = (whole.sortedByDescending { it.score } +
            prefix.sortedWith(compareByDescending<Pinyin18Candidate> { it.consumed }.thenByDescending { it.score }) +
            completion.sortedByDescending { it.score }).distinctBy { it.text to it.consumed }.take(MAX_CANDIDATES)
    }
    private fun learning(text: String) = kotlin.math.ln(1.0 + frequency(text).coerceIn(0, 10_000)) * 0.6
    private fun prune(paths: MutableList<Path>) {
        val top = paths.sortedByDescending { it.score }.distinctBy { it.text }.take(BEAM)
        paths.clear(); paths.addAll(top)
    }
    companion object {
        private const val MAX_INPUT = 64
        private const val BEAM = 8
        private const val WORD_PENALTY = 0.7
        private const val MAX_CANDIDATES = 160
    }
}
