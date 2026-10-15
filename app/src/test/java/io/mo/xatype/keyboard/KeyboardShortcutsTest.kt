package io.mo.xatype.keyboard

import io.mo.xatype.keyboard18.Keyboard18HostLayout
import org.junit.Assert.*
import org.junit.Test

class KeyboardShortcutsTest {
    private val loader = javaClass.classLoader!!
    private val abi = io.mo.xatype.compat.KeyboardAbi.reference(loader)
    private val adapter = KeyboardShortcutHostLayout(loader, abi)
    private fun key(code: Int, label: String, popup: List<String>? = null) = ka.c(code,
        label, 1f, 36f, 48f, ka.f.CHAR, "-", "-", popup, 1, 5, false, true, false, false, 7, false, 9)
    private fun layout(english: Boolean, shift: Boolean, split: Boolean): ka.g {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").mapIndexed { index, letters ->
            val keys = letters.map { letter -> key((if (shift) letter.uppercaseChar() else letter).code,
                (if (!english || shift) letter.uppercaseChar() else letter).toString(),
                listOf(letter.uppercaseChar().toString(), "-", letter.toString())) }.toMutableList()
            if (index == 2) { keys.add(0, ka.c(-1, ka.f.SHIFT)); keys.add(ka.c(-2, ka.f.BACKSPACE)) }
            if (split) ka.e(1f, keys.take(keys.size / 2), keys.drop(keys.size / 2)) else ka.e(1f, keys, null)
        } + ka.e(1f, listOf(ka.c(32, ka.f.SPACE)), null)
        return ka.g("qwerty", "拼音26键", rows, listOf("PINYIN", "ENGLISH"), 8f, 4f, true, 12f, true)
    }
    private fun keys(layout: ka.g) = layout.c.flatMap { it.a + it.c.orEmpty() }

    @Test fun `native Chinese English shift and split keys all retain independent display casing`() {
        for (english in listOf(false, true)) for (shift in listOf(false, true)) for (split in listOf(false, true)) {
            val original = layout(english, shift, split)
            assertEquals(5, keys(original).mapNotNull { adapter.action(it, false) }.size)
            val copy = keys(original).single { it.a == (if (shift) 'C' else 'c').code }
            assertEquals("复制", adapter.action(copy, false))
            assertEquals(if (!english || shift) "C" else "c", copy.b)
            assertEquals(listOf("C", "-", "c"), copy.i)
        }
    }

    @Test fun `release action copies native properties without changing the visible key or menu`() {
        val original = key('c'.code, "C", listOf("C", "-", "c"))
        val action = adapter.actionKey(original, "复制") as ka.c
        assertEquals('c'.code, original.a)
        assertEquals("C", original.b)
        assertEquals(listOf("C", "-", "c"), original.i)
        assertEquals('复'.code, action.a)
        assertEquals("复制", action.b)
        assertEquals(listOf("复制"), action.i)
        for (name in 'a'..'r') if (name !in listOf('a', 'b', 'i')) {
            val field = ka.c::class.java.getDeclaredField(name.toString())
            assertEquals("field $name", field.get(original), field.get(action))
        }
    }

    @Test fun `18 key actions work in Chinese and English while popup keeps letters and hints only`() {
        val builder = Keyboard18HostLayout(loader, abi)
        for (english in listOf(false, true)) {
            val keyboard = builder.rebuild(layout(english, false, false), english) as ka.g
            assertEquals(5, keys(keyboard).mapNotNull { adapter.action(it, true) }.size)
            assertFalse(keys(keyboard).any { it.i.orEmpty().any(KeyboardShortcuts.actions::contains) })
            val copy = keys(keyboard).single { adapter.action(it, true) == "复制" }
            assertTrue(copy.i.orEmpty().containsAll(listOf("x", "c", "7")))
            assertNull(adapter.action(copy, false))
        }
    }

    @Test fun `unrelated labels symbols controls and non shortcut letters are not gestures`() {
        assertNull(adapter.action(key('a'.code, "A"), false))
        assertNull(adapter.action(key('c'.code, "XC"), false))
        assertNull(adapter.action(key('c'.code, "复制"), false))
        assertNull(adapter.action(key('1'.code, "1"), false))
        assertNull(adapter.action(ka.c(-2, ka.f.BACKSPACE), false))
    }

    @Test(expected = IllegalArgumentException::class) fun `release cannot synthesize an arbitrary action`() {
        adapter.actionKey(key('c'.code, "C"), "任意文字")
    }

    @Test fun `paragraph navigation handles boundaries offsets and empty text`() {
        val text = "第一段\n第二段\n"
        assertEquals(0, KeyboardShortcuts.paragraphPosition(text, 0, 0, false))
        assertEquals(0, KeyboardShortcuts.paragraphPosition(text, 3, 0, false))
        assertEquals(4, KeyboardShortcuts.paragraphPosition(text, 4, 0, false))
        assertEquals(7, KeyboardShortcuts.paragraphPosition(text, 4, 0, true))
        assertEquals(8, KeyboardShortcuts.paragraphPosition(text, text.length, 0, true))
        assertEquals(108, KeyboardShortcuts.paragraphPosition(text, 99, 100, false))
        assertEquals(12, KeyboardShortcuts.paragraphPosition("", 0, 12, true))
        assertEquals(0, KeyboardShortcuts.paragraphPosition(text, -2, 0, false))
    }
}
