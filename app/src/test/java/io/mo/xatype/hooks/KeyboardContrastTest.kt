package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardContrastTest {
    private val black = 0xff000000.toInt()
    private val white = 0xffffffff.toInt()
    private val mint = 0xffd0ffd6.toInt()

    @Test fun lightKeycapsOnBlackSplitKeyAndBackgroundText() {
        // The reported setup: keycap labels go dark, candidates on the background stay light.
        assertEquals(KeyboardContrast.DARK_TEXT, KeyboardContrast.textOn(KeyboardContrast.isDark(mint)))
        assertEquals(KeyboardContrast.LIGHT_TEXT, KeyboardContrast.textOn(KeyboardContrast.isDark(black)))
    }

    @Test fun customTextWinsEverywhere() {
        val gold = 0xffe6c35c.toInt()
        assertEquals(gold, KeyboardContrast.textOn(true, gold))
        assertEquals(gold, KeyboardContrast.textOn(false, gold))
    }

    @Test fun translucentColorsAreJudgedAsComposited() {
        val nativeChip = 0x99ffffff.toInt() // White at 60%: light over black.
        assertEquals(0xff999999.toInt(), KeyboardContrast.opaqueOver(nativeChip, black))
        assertFalse(KeyboardContrast.isDark(KeyboardContrast.opaqueOver(nativeChip, black)))
        assertTrue(KeyboardContrast.isDark(KeyboardContrast.opaqueOver(0x1a000000, black)))
    }

    @Test fun lightChipOnDarkBackgroundMovesToTheDarkSide() {
        val chip = KeyboardContrast.chipOn(0x99ffffff.toInt(), black)
        assertEquals(KeyboardContrast.DARK_SIDE_CHIP, chip)
        assertTrue(KeyboardContrast.isDark(KeyboardContrast.opaqueOver(chip!!, black)))
    }

    @Test fun darkChipOnLightBackgroundMovesToTheLightSide() {
        val chip = KeyboardContrast.chipOn(0xcc000000.toInt(), white)
        assertEquals(KeyboardContrast.LIGHT_SIDE_CHIP, chip)
        assertFalse(KeyboardContrast.isDark(KeyboardContrast.opaqueOver(chip!!, white)))
    }

    @Test fun chipsAlreadyOnTheBackgroundSideStayNative() {
        assertNull(KeyboardContrast.chipOn(0x99ffffff.toInt(), white))
        assertNull(KeyboardContrast.chipOn(0x33ffffff, black))
    }
}
