package io.mo.xatype.keyboard18

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Regression cases use the shipped corpus and user input, rather than mirroring trie logic. */
class Keyboard18EngineTest {
    private fun grouped(vararg groups: String) = groups.joinToString("") { Keyboard18Keys.code(it).toChar().toString() }
    private fun engine() = Keyboard18Engine(dictionary)

    @Test fun `why is offered for the correct grouped initials and can be committed`() {
        val engine = engine()
        engine.inputText(grouped("we", "sd", "m"))
        assertEquals("为什么", engine.candidates.first().text)
        assertEquals("为什么", engine.input(' ').commit)
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `full pinyin still offers why before abbreviated character combinations`() {
        val engine = engine()
        engine.inputText(grouped("we", "we", "io", "sd", "h", "we", "bn", "m", "we"))
        assertEquals("为什么", engine.candidates.first().text)
        assertEquals("为什么", engine.select(0))
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `wrong second group does not become the s initial and rare repeated words are suppressed`() {
        val engine = engine()
        engine.inputText(grouped("we", "we", "m"))
        assertFalse(engine.candidates.any { it.text == "为什么" })
        assertFalse(engine.candidates.take(5).any { it.text in setOf("哦哦呒", "额额呒", "哦哦无", "哦哦嘸") })
    }

    @Test fun `exact initials constrain ambiguity and deleting a grouped tap restores candidates`() {
        val engine = engine()
        engine.inputText("wsm")
        assertEquals("为什么", engine.candidates.first().text)
        engine.clear()
        val input = grouped("we", "sd", "m")
        engine.inputText(input)
        assertEquals(input.dropLast(1), engine.backspace().raw)
        assertEquals("为什么", engine.input(input.last()).candidates.first().text)
        assertNull(engine.restore(input).commit)
    }

    @Test fun `common single syllable initial outranks the rare standalone m entry`() {
        val engine = engine()
        engine.inputText(grouped("m"))
        assertNotEquals("呒", engine.candidates.first().text)
        assertTrue(engine.candidates.any { it.text == "么" })
    }

    companion object {
        private val dictionary by lazy {
            val asset = listOf(File("src/main/assets/keyboard18/pinyin.table"),
                File("app/src/main/assets/keyboard18/pinyin.table")).first { it.isFile }
            asset.inputStream().use(Keyboard18Dictionary::read)
        }
    }
}
