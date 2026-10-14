package io.mo.xatype.keyboard18

/** Build host-native KeyData/KeyRow/KeyboardLayout objects, preserving the control row. */
class Keyboard18HostLayout(loader: ClassLoader) {
    private val key = Class.forName("ka.c", false, loader)
    private val kind = Class.forName("ka.f", false, loader)
    private val row = Class.forName("ka.e", false, loader)
    val layout = Class.forName("ka.g", false, loader)
    private val int = Int::class.javaPrimitiveType!!
    private val float = Float::class.javaPrimitiveType!!
    private val bool = Boolean::class.javaPrimitiveType!!
    private val string = String::class.java
    private val keyConstructor = key.getConstructor(int, string, float, java.lang.Float::class.java,
        java.lang.Float::class.java, kind, string, string, List::class.java, Integer::class.java,
        Integer::class.java, bool, java.lang.Boolean::class.java, bool, bool, Integer::class.java, bool, Integer::class.java)
    private val rowConstructor = row.getConstructor(float, List::class.java, List::class.java)
    private val layoutConstructor = layout.getConstructor(string, string, List::class.java, List::class.java,
        java.lang.Float::class.java, java.lang.Float::class.java, bool, java.lang.Float::class.java, bool)
    private val charKind = kind.enumConstants.single { (it as Enum<*>).name == "CHAR" }
    private val fields = key.declaredFields.associateBy { it.name }.onEach { it.value.isAccessible = true }
    private fun value(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    fun rebuild(original: Any, english: Boolean): Any {
        if (value(original, "a") != "qwerty") return original
        @Suppress("UNCHECKED_CAST")
        val rows = value(original, "c") as List<Any>
        if (rows.size != 4 || rows.any { value(it, "c") != null }) return original // Split-pad layout is retained.
        @Suppress("UNCHECKED_CAST")
        val originals = rows.flatMap { value(it, "a") as List<Any> }
        val letters = originals.filter { fields.getValue("a").getInt(it).toChar().lowercaseChar() in 'a'..'z' }
        if (letters.size != 26) return original
        val template = letters.first()
        val shift = originals.firstOrNull { fields.getValue("a").getInt(it) == -1 } ?: return original
        val delete = originals.firstOrNull { fields.getValue("a").getInt(it) == -2 } ?: return original
        val height = fields.getValue("e").get(template)
        val spacing = (value(original, "f") as? Float) ?: 5f
        @Suppress("UNCHECKED_CAST")
        val first = value(rows[0], "a") as List<Any>
        val fixed = first.map { fields.getValue("d").get(it) as? Float }
        val width = if (fixed.all { it != null }) fixed.sumOf { it!!.toDouble() }.toFloat() + spacing * (first.size - 1) else null
        val uppercase = letters.any { fields.getValue("a").getInt(it).toChar() in 'A'..'Z' }
        fun newKey(code: Int, label: String, weight: Float, hint: String?, popup: List<String>?): Any =
            keyConstructor.newInstance(code, label, weight, null, height, charKind, hint,
                hint?.takeIf { it.length == 1 }, popup, 0, null, false, english, true, false, null, false, null)
        fun resized(source: Any, fixedWidth: Float?, weight: Float): Any = keyConstructor.newInstance(
            fields.getValue("a").get(source), fields.getValue("b").get(source), weight, fixedWidth,
            fields.getValue("e").get(source), fields.getValue("f").get(source), fields.getValue("g").get(source),
            fields.getValue("h").get(source), fields.getValue("i").get(source), fields.getValue("j").get(source),
            fields.getValue("k").get(source), fields.getValue("l").get(source), fields.getValue("m").get(source),
            fields.getValue("n").get(source), fields.getValue("o").get(source), fields.getValue("p").get(source),
            fields.getValue("q").get(source), fields.getValue("r").get(source))
        val newRows = Keyboard18Keys.rows.mapIndexed { index, groups ->
            val keys = groups.map { group ->
                val hint = Keyboard18Keys.hints[Keyboard18Keys.groups.indexOf(group)]
                val popup = group.map { if (uppercase) it.uppercaseChar().toString() else it.toString() } +
                    listOf(hint).filter { it.length == 1 } + listOfNotNull(Keyboard18Keys.shortcuts[group])
                newKey(if (english) (if (uppercase) group.first().uppercaseChar() else group.first()).code else Keyboard18Keys.code(group),
                    if (uppercase) group.uppercase() else group, if (group.length == 2) 1.7f else 1f, hint, popup)
            }.toMutableList()
            if (index == 2) { keys.add(0, resized(shift, null, 1.3f)); keys.add(resized(delete, null, 1.3f)) }
            val total = keys.sumOf { fields.getValue("c").getFloat(it).toDouble() }.toFloat()
            val available = width?.let { (it - spacing * (keys.size - 1)).coerceAtLeast(1f) }
            val sized = keys.map { item -> val weight = fields.getValue("c").getFloat(item)
                resized(item, available?.let { it * weight / total }, weight) }
            rowConstructor.newInstance(value(rows[index], "b"), sized, null)
        } + rows.last()
        return layoutConstructor.newInstance("qwerty", "18键", newRows, value(original, "d"),
            value(original, "e"), value(original, "f"), value(original, "g"), value(original, "h"), value(original, "i"))
    }
}
