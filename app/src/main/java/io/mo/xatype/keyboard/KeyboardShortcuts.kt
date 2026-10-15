package io.mo.xatype.keyboard

/** Shared labels for the native long-press popup in both alphabet layouts. */
object KeyboardShortcuts {
    val letters = mapOf('z' to "全选", 'c' to "复制", 'v' to "粘贴", 'b' to "段首", 'm' to "段尾")
    val groups18 = mapOf("z" to "全选", "xc" to "复制", "v" to "粘贴", "bn" to "段首", "m" to "段尾")
    val actions = letters.values.toSet()

    fun popup(letter: Char, existing: List<String>?, hint: String?): List<String> {
        val original = existing?.takeIf { it.isNotEmpty() } ?: hint.orEmpty().map(Char::toString)
        val action = letters[letter.lowercaseChar()] ?: return original
        return if (action in original) original else original + action
    }

    /** ExtractedText selections are relative to its text, while setSelection is absolute. */
    fun paragraphPosition(text: CharSequence, selection: Int, startOffset: Int, end: Boolean): Int {
        val caret = selection.coerceIn(0, text.length)
        val value = text.toString()
        val relative = if (end) value.indexOf('\n', caret).let { if (it < 0) value.length else it }
            else value.lastIndexOf('\n', caret - 1) + 1
        return startOffset + relative
    }
}
