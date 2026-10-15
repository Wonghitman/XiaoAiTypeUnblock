package io.mo.xatype.keyboard

/** Shared release actions for both alphabet layouts. */
object KeyboardShortcuts {
    val letters = mapOf('z' to "全选", 'c' to "复制", 'v' to "粘贴", 'b' to "段首", 'm' to "段尾")
    val groups18 = mapOf("z" to "全选", "xc" to "复制", "v" to "粘贴", "bn" to "段首", "m" to "段尾")
    val actions = letters.values.toSet()

    /** ExtractedText selections are relative to its text, while setSelection is absolute. */
    fun paragraphPosition(text: CharSequence, selection: Int, startOffset: Int, end: Boolean): Int {
        val caret = selection.coerceIn(0, text.length)
        val value = text.toString()
        val relative = if (end) value.indexOf('\n', caret).let { if (it < 0) value.length else it }
            else value.lastIndexOf('\n', caret - 1) + 1
        return startOffset + relative
    }
}
