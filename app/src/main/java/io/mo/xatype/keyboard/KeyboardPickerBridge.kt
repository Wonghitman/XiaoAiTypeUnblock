package io.mo.xatype.keyboard

/** Reflection only; also exercised against host-shaped fixtures on the desktop. */
class KeyboardPickerBridge(loader: ClassLoader) {
    private val item = Class.forName("z9.g6", false, loader)
    private val constructor = item.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
    private val id = item.getDeclaredField("a").apply { isAccessible = true }
    private val icon = item.getDeclaredField("c").apply { isAccessible = true }
    private val nativeIds = setOf("qwerty_chinese", "t9_chinese", "handwriting_chinese", "stroke_chinese")

    fun extend(values: Array<*>, showShape: Boolean, show18: Boolean): List<Any>? {
        if (values.isEmpty() || values.size > nativeIds.size || values.any { !item.isInstance(it) }) return null
        val ids = values.map { id.get(it) }
        if (ids.distinct().size != values.size || ids.any { it !in nativeIds } || KeyboardChoice.PINYIN.id !in ids) return null
        val qwerty = values.single { id.get(it) == KeyboardChoice.PINYIN.id }
        return values.filterNotNull().toMutableList().apply {
            if (showShape) add(constructor.newInstance(icon.getInt(qwerty), KeyboardChoice.SHAPE.id, "虎码/五笔"))
            if (show18) add(constructor.newInstance(icon.getInt(qwerty), KeyboardChoice.KEYBOARD18.id, "18键"))
        }
    }
}
