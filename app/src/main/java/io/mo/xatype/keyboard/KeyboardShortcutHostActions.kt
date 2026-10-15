package io.mo.xatype.keyboard

/** Finish pending input through native candidate callbacks before moving the editor selection. */
class KeyboardShortcutHostActions(loader: ClassLoader) {
    private val callbacks = Class.forName("ab.b1", false, loader)
    private val pending = callbacks.getMethod("n")
    private val candidates = callbacks.getMethod("d")
    private val text = callbacks.getMethod("f")
    private val selected = callbacks.getMethod("M")
    private val choose = callbacks.getMethod("b", Int::class.javaPrimitiveType)
    private val commit = callbacks.getMethod("K", String::class.java)
    private val current = callbacks.getMethod("u")
    private val clear = current.returnType.getMethod("clear")
    private val refresh = callbacks.getMethod("F")
    private val finish = callbacks.getMethod("J")

    fun finishInput(target: Any) {
        var previous: String? = null
        var selections = 0
        while (pending.invoke(target) == true) {
            val raw = text.invoke(target) as String
            // A decoder may have no candidate, or fail to progress. Preserve its remaining
            // input literally instead of discarding it or looping on an invalid candidate.
            if (candidates.invoke(target) != true || raw == previous || selections >= 64) {
                if (raw.isNotEmpty()) commit.invoke(target, raw)
                current.invoke(target)?.let { clear.invoke(it) }
                refresh.invoke(target)
                break
            }
            previous = raw
            choose.invoke(target, selected.invoke(target))
            selections++
        }
        finish.invoke(target)
    }
}
