package io.mo.xatype.shape

import org.junit.Assert.*
import org.junit.Test

class ShapeHostKeyAdapterTest {
    private val bridge = ShapeHostBridge(javaClass.classLoader!!)
    private val adapter = ShapeHostKeyAdapter(javaClass.classLoader!!, bridge)
    private val engine = ShapeEngine(ShapeDictionary("测试", listOf(
        ShapeEntry("你", "jx"), ShapeEntry("甲", "a"), ShapeEntry("乙", "a"))), ShapeRules())
    private var notifications = 0
    private val proxy = bridge.create(engine) { notifications++ } as z8.q
    private val callbacks = Callbacks(proxy)
    private class Callbacks(var current: z8.q?, var shift: Boolean = false) : ab.b1 {
        val committed = StringBuilder()
        val results = ArrayList<z8.r>()
        override fun u() = current
        override fun o() = shift
        override fun K(text: String) { committed.append(text) }
        override fun y(result: z8.r) { results.add(result) }
    }
    private fun key(char: Char) = adapter.handle(ka.c(char.code, ka.f.CHAR), callbacks)

    @Test fun `raw letters backspace and space use shape callbacks directly`() {
        assertTrue(key('j')); assertTrue(key('x'))
        assertEquals("jx", callbacks.results.last().code)
        assertTrue(adapter.handle(ka.c(-5, ka.f.BACKSPACE), callbacks))
        assertEquals("j", engine.code)
        assertTrue(key('x'))
        assertTrue(adapter.handle(ka.c(32, ka.f.SPACE), callbacks))
        assertEquals("你", callbacks.committed.toString())
        assertEquals("", callbacks.results.last().code)
        assertEquals(5, notifications)
        assertFalse(adapter.handle(ka.c(32, ka.f.SPACE), callbacks))
        assertFalse(adapter.handle(ka.c(-5, ka.f.BACKSPACE), callbacks))
    }

    @Test fun `shortcuts are scoped to active codes and other controls pass through`() {
        assertFalse(key('2'))
        assertTrue(key('a')); assertTrue(key(';'))
        assertEquals("乙", callbacks.committed.toString())
        assertFalse(adapter.handle(ka.c(-1, ka.f.SHIFT), callbacks))
        callbacks.shift = true
        assertFalse(key('j'))
        callbacks.current = null
        assertFalse(key('a'))
        assertTrue(engine.code.isEmpty())
    }
}
