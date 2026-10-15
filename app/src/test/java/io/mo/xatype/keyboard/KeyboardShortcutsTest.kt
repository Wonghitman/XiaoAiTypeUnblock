package io.mo.xatype.keyboard

import io.mo.xatype.keyboard18.Keyboard18HostLayout
import org.junit.Assert.*
import org.junit.Test

class KeyboardShortcutsTest {
    private val loader = javaClass.classLoader!!
    private val adapter = KeyboardShortcutHostLayout(loader)
    private fun key(char: Char, popup: List<String>? = null, hint: String? = "?") = ka.c(char.code,
        char.toString(), 1f, 36f, 48f, ka.f.CHAR, hint, hint, popup, 3, 5, true, true, true, true, 7, true, 9)
    private fun layout(uppercase: Boolean = false, split: Boolean = false): ka.g {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").mapIndexed { index, letters ->
            val keys = letters.map { key(if (uppercase) it.uppercaseChar() else it,
                if (it == 'c') listOf("ç", "ć") else null) }.toMutableList()
            if (index == 2) { keys.add(0, ka.c(-1, ka.f.SHIFT)); keys.add(ka.c(-2, ka.f.BACKSPACE)) }
            if (split) ka.e(1f, keys.take(keys.size / 2), keys.drop(keys.size / 2)) else ka.e(1f, keys, null)
        } + ka.e(1f, listOf(ka.c(32, ka.f.SPACE)), null)
        return ka.g("qwerty", "拼音26键", rows, listOf("PINYIN", "ENGLISH"), 8f, 4f, true, 12f, true)
    }
    private fun keys(layout: ka.g) = layout.c.flatMap { it.a + it.c.orEmpty() }

    @Test fun `native popups retain all key properties and existing options`() {
        val original = layout()
        val updated = adapter.addTo(original) as ka.g
        assertNotSame(original, updated)
        assertSame(original.c.last(), updated.c.last())
        assertSame(original.d, updated.d)
        assertEquals(original.f, updated.f)
        assertEquals(original.h, updated.h)
        for ((before, after) in keys(original).zip(keys(updated))) {
            val action = KeyboardShortcuts.letters[before.a.toChar()]
            if (action == null) assertSame(before, after) else {
                assertEquals(KeyboardShortcuts.popup(before.a.toChar(), before.i, before.g), after.i)
                for (name in 'a'..'r') if (name != 'i') {
                    val field = ka.c::class.java.getDeclaredField(name.toString())
                    assertEquals("field $name", field.get(before), field.get(after))
                }
                assertFalse(before.i.orEmpty().contains(action))
            }
        }
        assertEquals(listOf("ç", "ć", "复制"), keys(updated).single { it.a == 'c'.code }.i)
        assertEquals(listOf("?", "粘贴"), keys(updated).single { it.a == 'v'.code }.i)
        assertSame(updated, adapter.addTo(updated))
    }

    @Test fun `uppercase and split native keyboards receive the same five actions`() {
        for (split in listOf(false, true)) {
            val updated = adapter.addTo(layout(uppercase = true, split = split)) as ka.g
            assertEquals(5, keys(updated).count { it.i.orEmpty().any(KeyboardShortcuts.actions::contains) })
            assertEquals(listOf("ç", "ć", "复制"), keys(updated).single { it.a == 'C'.code }.i)
            assertEquals(split, updated.c.first().c != null)
        }
    }

    @Test fun `18 key menus follow the independent switch and retain letters and hints`() {
        val builder = Keyboard18HostLayout(loader)
        for (english in listOf(false, true)) {
            val off = builder.rebuild(layout(), english, shortcuts = false) as ka.g
            val on = builder.rebuild(layout(), english, shortcuts = true) as ka.g
            assertFalse(keys(off).any { it.i.orEmpty().any(KeyboardShortcuts.actions::contains) })
            assertEquals(5, keys(on).count { it.i.orEmpty().any(KeyboardShortcuts.actions::contains) })
            for ((before, after) in keys(off).zip(keys(on))) {
                assertEquals(before.a, after.a)
                assertEquals(before.b, after.b)
                assertEquals(before.i.orEmpty(), after.i.orEmpty().filterNot(KeyboardShortcuts.actions::contains))
            }
            assertSame(on, adapter.addTo(on))
        }
    }

    @Test fun `non alphabet layouts remain untouched`() {
        val original = layout()
        val numbers = ka.g("number", original.b, original.c, original.d, original.e, original.f, true, null, false)
        assertSame(numbers, adapter.addTo(numbers))
        val partial = ka.g("qwerty", original.b, original.c.drop(1), original.d, original.e, original.f, true, null, false)
        assertSame(partial, adapter.addTo(partial))
    }

    @Test fun `popup fallback preserves symbols and does not duplicate actions`() {
        assertEquals(listOf("@", "#", "全选"), KeyboardShortcuts.popup('Z', emptyList(), "@#"))
        assertEquals(listOf("全选"), KeyboardShortcuts.popup('z', listOf("全选"), "@"))
        assertEquals(listOf("段尾"), KeyboardShortcuts.popup('m', null, null))
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
