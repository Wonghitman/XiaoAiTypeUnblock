package io.mo.xatype.shape

import org.junit.Assert.*
import org.junit.Test

class ShapeEngineTest {
    private val dictionary = ShapeDictionary("测试", listOf(
        ShapeEntry("你", "jx"), ShapeEntry("好", "bh"),
        ShapeEntry("甲", "a", 1.0), ShapeEntry("乙", "a", 10.0), ShapeEntry("丙", "a", 10.0),
        ShapeEntry("测试", "abcd")))
    private fun engine(rules: ShapeRules = ShapeRules()) = ShapeEngine(dictionary, rules)

    @Test fun `space chooses exact candidate and clears composition`() {
        val e = engine()
        assertNull(e.inputText("jx").commit)
        assertEquals(listOf("你"), e.candidates.map { it.text })
        assertEquals("你", e.input(' ').commit)
        assertEquals("", e.code)
        assertTrue(e.candidates.isEmpty())
        assertFalse(e.input(' ').consumed)
    }

    @Test fun `max code unique auto commit and overflow preserve every commit`() {
        assertEquals("测试你好", engine().inputText("abcdjx bh ").commit)
        val e = engine(ShapeRules(autoCommitUnique = false))
        assertNull(e.inputText("abcd").commit)
        assertEquals("测试", e.input('j').commit)
        assertEquals("j", e.code)
        assertEquals("你好", e.inputText("x bh ").commit)
    }

    @Test fun `disabled overflow retains full code until selected or deleted`() {
        val e = engine(ShapeRules(autoCommitUnique = false, commitOnOverflow = false))
        e.inputText("abcd")
        assertNull(e.input('j').commit)
        assertEquals("abcd", e.code)
        assertEquals("abc", e.backspace().code)
        assertEquals("测试", e.inputText("d ").commit)
    }

    @Test fun `digit and punctuation selections respect rules`() {
        val e = engine()
        assertEquals("乙", e.inputText("a2").commit)
        assertEquals("乙", e.inputText("a;").commit)
        assertEquals("丙", e.inputText("a'").commit)
        assertNull(e.inputText("a9").commit)
        assertEquals("a", e.code)
        assertEquals("甲。", e.input('。').commit)
        val literal = engine(ShapeRules(selectByDigits = false, semicolonSecond = false, quoteThird = false))
        assertEquals("甲2甲;甲'", literal.inputText("a2a;a'").commit)
    }

    @Test fun `unknown code is preserved and reconstruction never commits`() {
        val e = engine()
        assertEquals("zz", e.inputText("zz ").commit)
        assertNull(e.replaceCode("abcd").commit)
        assertEquals("abcd", e.code)
        assertFalse(e.replaceCode("too-long").consumed)
        assertEquals("abcd", e.code)
        e.clear()
        assertFalse(e.backspace().consumed)
        assertEquals("ABC123", e.inputText("ABC123").commit)
    }

    @Test fun `weight ordering is stable and optional`() {
        assertEquals(listOf("甲", "乙", "丙"), dictionary.candidates("a").map { it.text })
        assertEquals(listOf("乙", "丙", "甲"), dictionary.candidates("a", true).map { it.text })
    }
}
