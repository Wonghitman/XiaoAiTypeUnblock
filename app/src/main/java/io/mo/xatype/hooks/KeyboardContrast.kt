package io.mo.xatype.hooks

/**
 * Automatic text colors for the keyboard. Keycap labels follow the keycap,
 * while candidates and the clipboard strip sit on the keyboard background.
 * Shared by the palette hook and the settings preview so both agree.
 */
internal object KeyboardContrast {
    const val LIGHT_TEXT = 0xf2ffffff.toInt()
    const val DARK_TEXT = 0xe6000000.toInt()

    /** Candidate chips moved onto a dark background. */
    const val DARK_SIDE_CHIP = 0x33ffffff

    /** Candidate chips moved onto a light background. */
    const val LIGHT_SIDE_CHIP = 0x1f000000

    fun luma(color: Int): Int =
        (299 * ((color shr 16) and 0xff) + 587 * ((color shr 8) and 0xff) + 114 * (color and 0xff)) / 1000

    fun isDark(color: Int): Boolean = luma(color) < 150

    /** [color] alpha-composited over [base], ignoring the base's own alpha. */
    fun opaqueOver(color: Int, base: Int): Int {
        val a = (color ushr 24) / 255f
        fun mix(shift: Int): Int {
            val top = (color shr shift) and 0xff
            val bottom = (base shr shift) and 0xff
            return (top * a + bottom * (1 - a) + 0.5f).toInt()
        }
        return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** [custom] text, otherwise the automatic label for a dark or light surface. */
    fun textOn(dark: Boolean, custom: Int? = null): Int = custom ?: if (dark) LIGHT_TEXT else DARK_TEXT

    /**
     * A candidate chip that lands on the other side of light/dark than the
     * background it is drawn on, moved to the background's side so it carries
     * the same text as the unselected candidates; null when it already fits.
     */
    fun chipOn(chip: Int, backdrop: Int): Int? {
        val dark = isDark(backdrop)
        if (isDark(opaqueOver(chip, backdrop)) == dark) return null
        return if (dark) DARK_SIDE_CHIP else LIGHT_SIDE_CHIP
    }
}
