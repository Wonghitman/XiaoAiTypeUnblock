package io.mo.xatype.keyboard

/** Copy only popup lists, retaining native geometry, symbols, key codes and control rows. */
class KeyboardShortcutHostLayout(loader: ClassLoader) {
    val key = Class.forName("ka.c", false, loader)
    val layout = Class.forName("ka.g", false, loader)
    private val kind = Class.forName("ka.f", false, loader)
    private val row = Class.forName("ka.e", false, loader)
    private val fields = ('a'..'r').map { key.getDeclaredField(it.toString()).apply { isAccessible = true } }
    private val constructor = key.getConstructor(Int::class.javaPrimitiveType, String::class.java,
        Float::class.javaPrimitiveType, java.lang.Float::class.java, java.lang.Float::class.java,
        kind, String::class.java, String::class.java, List::class.java, Integer::class.java,
        Integer::class.java, Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Integer::class.java,
        Boolean::class.javaPrimitiveType, Integer::class.java)
    private val rowConstructor = row.getConstructor(Float::class.javaPrimitiveType, List::class.java, List::class.java)
    private val layoutConstructor = layout.getConstructor(String::class.java, String::class.java,
        List::class.java, List::class.java, java.lang.Float::class.java, java.lang.Float::class.java,
        Boolean::class.javaPrimitiveType, java.lang.Float::class.java, Boolean::class.javaPrimitiveType)
    private fun value(instance: Any, field: String) = instance.javaClass.getDeclaredField(field)
        .apply { isAccessible = true }.get(instance)
    private fun keys(instance: Any, field: String) = (value(instance, field) as? List<*>)?.filterNotNull().orEmpty()
    private fun letter(instance: Any): Char? {
        if ((fields[5].get(instance) as Enum<*>).name != "CHAR") return null
        val code = fields[0].getInt(instance)
        if (code !in 'a'.code..'z'.code && code !in 'A'.code..'Z'.code) return null
        val label = fields[1].get(instance) as? String ?: return null
        // Native key labels can stay uppercase while input codes remain lowercase.
        // Require the same single letter without conflating display case and input case.
        return code.toChar().takeIf { label.length == 1 && label[0].equals(it, ignoreCase = true) }
    }

    fun addTo(original: Any): Any {
        if (value(original, "a") != "qwerty") return original
        val rows = keys(original, "c")
        val letters = rows.flatMap { keys(it, "a") + keys(it, "c") }.mapNotNull(::letter)
        if (letters.size != 26 || letters.map(Char::lowercaseChar).toSet() != ('a'..'z').toSet()) return original
        fun update(instance: Any): Any {
            val letter = letter(instance) ?: return instance
            if (letter.lowercaseChar() !in KeyboardShortcuts.letters) return instance
            val args = fields.map { it.get(instance) }.toTypedArray()
            @Suppress("UNCHECKED_CAST")
            val popup = KeyboardShortcuts.popup(letter, args[8] as? List<String>, args[6] as? String)
            if (popup == args[8]) return instance
            args[8] = popup
            return constructor.newInstance(*args)
        }
        val updated = rows.map { item ->
            val left = keys(item, "a")
            val right = keys(item, "c")
            val newLeft = left.map(::update)
            val newRight = right.map(::update)
            if (left.indices.all { left[it] === newLeft[it] } && right.indices.all { right[it] === newRight[it] }) item
            else rowConstructor.newInstance(value(item, "b"), newLeft, newRight.takeIf { value(item, "c") != null })
        }
        if (rows.indices.all { rows[it] === updated[it] }) return original
        return layoutConstructor.newInstance(value(original, "a"), value(original, "b"), updated,
            value(original, "d"), value(original, "e"), value(original, "f"), value(original, "g"),
            value(original, "h"), value(original, "i"))
    }
}
