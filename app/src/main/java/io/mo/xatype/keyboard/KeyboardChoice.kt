package io.mo.xatype.keyboard

/** Persist a separate Chinese choice without extending the host's engine enum. */
enum class KeyboardChoice(val id: String) {
    PINYIN("qwerty_chinese"), SHAPE("xatype_shape"), KEYBOARD18("xatype_keyboard18");

    companion object {
        fun resolve(saved: String?, showShape: Boolean, show18: Boolean,
                    legacyShape: Boolean = false, legacy18: Boolean = false): KeyboardChoice {
            val choice = entries.firstOrNull { it.id == saved } ?: if (saved == null) {
                when { legacyShape -> SHAPE; legacy18 -> KEYBOARD18; else -> PINYIN }
            } else PINYIN
            return when {
                choice == SHAPE && !showShape -> PINYIN
                choice == KEYBOARD18 && !show18 -> PINYIN
                else -> choice
            }
        }
    }
}
