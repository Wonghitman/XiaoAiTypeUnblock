package io.mo.xatype.keyboard

import io.mo.xatype.keyboard18.Keyboard18Keys

/** Inspect native/grouped letters and create a release action without mutating visible keys. */
class KeyboardShortcutHostLayout(loader: ClassLoader) {
    val key = Class.forName("ka.c", false, loader)
    val layout = Class.forName("ka.g", false, loader)
    private val kind = Class.forName("ka.f", false, loader)
    private val fields = ('a'..'r').map { key.getDeclaredField(it.toString()).apply { isAccessible = true } }
    private val constructor = key.getConstructor(Int::class.javaPrimitiveType, String::class.java,
        Float::class.javaPrimitiveType, java.lang.Float::class.java, java.lang.Float::class.java,
        kind, String::class.java, String::class.java, List::class.java, Integer::class.java,
        Integer::class.java, Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Integer::class.java,
        Boolean::class.javaPrimitiveType, Integer::class.java)
    fun label(instance: Any) = fields[1].get(instance) as String
    fun action(instance: Any, keyboard18: Boolean): String? {
        if ((fields[5].get(instance) as Enum<*>).name != "CHAR") return null
        val code = fields[0].getInt(instance)
        if (keyboard18) {
            val letters = label(instance).filterNot(Char::isWhitespace).lowercase()
            val group = Keyboard18Keys.group(code) ?: Keyboard18Keys.groups.firstOrNull {
                it.first() == code.toChar().lowercaseChar() && it == letters
            }
            return KeyboardShortcuts.groups18[group].takeIf { group == letters }
        }
        if (code !in 'a'.code..'z'.code && code !in 'A'.code..'Z'.code) return null
        val label = fields[1].get(instance) as? String ?: return null
        // Native key labels can stay uppercase while input codes remain lowercase.
        // Require the same single letter without conflating display case and input case.
        if (label.length != 1 || !label[0].equals(code.toChar(), ignoreCase = true)) return null
        return KeyboardShortcuts.letters[code.toChar().lowercaseChar()]
    }

    fun actionKey(source: Any, action: String): Any {
        require(action in KeyboardShortcuts.actions)
        val args = fields.map { it.get(source) }.toTypedArray()
        args[0] = action.first().code
        args[1] = action
        args[8] = listOf(action)
        return constructor.newInstance(*args)
    }
}
