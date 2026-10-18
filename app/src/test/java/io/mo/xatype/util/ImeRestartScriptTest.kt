package io.mo.xatype.util

import org.junit.Assert.*
import org.junit.Test

class ImeRestartScriptTest {
    @Test fun savesDefaultImeBeforeForceStopAndRestoresOnlyXiaomiIme() {
        val script = ImeRestartScript.build(extraPackages = listOf("com.miui.phrase"))
        val save = script.indexOf("saved=\$(settings get secure default_input_method)")
        val phrase = script.indexOf("am force-stop com.miui.phrase")
        val stop = script.indexOf("am force-stop com.xiaomi.type")
        val set = script.indexOf("ime set \"\$saved\"")
        assertTrue(save in 0 until phrase)
        assertTrue(phrase < stop && stop < set)
        assertTrue(script.contains("com.xiaomi.type/*)"))
        assertTrue(script.indexOf("ime enable \"\$saved\"") in stop until set)
        assertFalse(script.contains("--user"))
    }

    @Test fun targetsGivenUserWithoutImeUserFlagForOwner() {
        val owner = ImeRestartScript.build(0)
        assertTrue(owner.contains("settings --user 0 get secure default_input_method"))
        assertTrue(owner.contains("am force-stop --user 0 com.xiaomi.type"))
        assertTrue(owner.contains("ime set \"\$saved\""))
        assertFalse(owner.contains("com.miui.phrase"))

        val secondary = ImeRestartScript.build(10)
        assertTrue(secondary.contains("ime set --user 10 \"\$saved\""))
        assertTrue(secondary.contains("ime enable --user 10 \"\$saved\""))
    }
}
