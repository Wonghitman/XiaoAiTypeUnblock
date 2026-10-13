package io.mo.xatype.shape

data class ShapeRules(
    val maxCodeLength: Int = 4,
    val autoCommitUnique: Boolean = true,
    val commitOnOverflow: Boolean = true,
    val selectByDigits: Boolean = true,
    val semicolonSecond: Boolean = true,
    val quoteThird: Boolean = true,
    val sortByWeight: Boolean = false
) {
    init { require(maxCodeLength in 1..8) }
}

data class ShapeResult(
    val consumed: Boolean,
    val code: String,
    val candidates: List<ShapeEntry>,
    val commit: String? = null
)

/** One state machine per host engine instance; no network or host SDK dependency. */
class ShapeEngine(val dictionary: ShapeDictionary, val rules: ShapeRules) {
    var code: String = ""
        private set
    var candidates: List<ShapeEntry> = emptyList()
        private set

    init { require(rules.maxCodeLength >= dictionary.maxCodeLength) { "码长小于码表中的最长编码" } }

    fun snapshot(consumed: Boolean = true, commit: String? = null) =
        ShapeResult(consumed, code, candidates, commit)

    fun clear() { code = ""; candidates = emptyList() }

    private fun refresh() { candidates = dictionary.candidates(code, rules.sortByWeight) }

    fun select(index: Int): String? {
        val selected = candidates.getOrNull(index)?.text ?: return null
        clear()
        return selected
    }

    fun backspace(): ShapeResult {
        if (code.isEmpty()) return snapshot(false)
        code = code.dropLast(1)
        refresh()
        return snapshot()
    }

    /** Used for cursor reconstruction. Restoring a code never commits it. */
    fun replaceCode(raw: String): ShapeResult {
        if (raw.length > rules.maxCodeLength || raw.any { it !in 'a'..'z' }) return snapshot(false)
        code = raw
        refresh()
        return snapshot()
    }

    fun input(key: Char): ShapeResult {
        if (key == '\b') return backspace()
        if (key in 'a'..'z') {
            var commit: String? = null
            if (code.length == rules.maxCodeLength) {
                if (!rules.commitOnOverflow) return snapshot()
                // An unmatched code remains visible if explicitly committed; it is never silently lost.
                commit = candidates.firstOrNull()?.text ?: code
                clear()
            }
            code += key
            refresh()
            if (rules.autoCommitUnique && code.length == rules.maxCodeLength && candidates.size == 1) {
                commit = commit.orEmpty() + select(0).orEmpty()
            }
            return snapshot(commit = commit)
        }
        if (code.isEmpty()) return snapshot(false)
        if (rules.selectByDigits && key in '1'..'9') return snapshot(commit = select(key - '1'))
        if (rules.semicolonSecond && key == ';' && candidates.size >= 2) return snapshot(commit = select(1))
        if (rules.quoteThird && key == '\'' && candidates.size >= 3) return snapshot(commit = select(2))
        val text = candidates.firstOrNull()?.text ?: code
        clear()
        return snapshot(commit = text + if (key == ' ') "" else key.toString())
    }

    /** A whole paste/replay can cross multiple commits; preserve every emitted character. */
    fun inputText(text: String): ShapeResult {
        val commits = StringBuilder()
        for (key in text) {
            val result = input(key)
            if (result.commit != null) commits.append(result.commit)
            else if (!result.consumed) commits.append(key)
        }
        return snapshot(text.isNotEmpty(), commits.toString().ifEmpty { null })
    }
}
