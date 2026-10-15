package io.mo.xatype.keyboard

import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod

/** Finish pending input through native candidate callbacks before moving the editor selection. */
class KeyboardShortcutHostActions(loader: ClassLoader, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ACTIONS)) {
    private val callbacks = abi.type("ab.b1")
    private val pending = callbacks.abiMethod(abi, "n")
    private val candidates = callbacks.abiMethod(abi, "d")
    private val text = callbacks.abiMethod(abi, "f")
    private val selected = callbacks.abiMethod(abi, "M")
    private val choose = callbacks.abiMethod(abi, "b", Int::class.javaPrimitiveType)
    private val commit = callbacks.abiMethod(abi, "K", String::class.java)
    private val current = callbacks.abiMethod(abi, "u")
    private val clear = current.returnType.abiMethod(abi, "clear")
    private val refresh = callbacks.abiMethod(abi, "F")
    private val finish = callbacks.abiMethod(abi, "J")

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
