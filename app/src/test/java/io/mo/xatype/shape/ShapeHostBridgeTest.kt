package io.mo.xatype.shape

import org.junit.Assert.*
import org.junit.Test
import z8.p

class ShapeHostBridgeTest {
    private val bridge = ShapeHostBridge(javaClass.classLoader!!)
    private fun proxy(count: Int = 30): p = bridge.create(ShapeEngine(
        ShapeDictionary("测试", (1..count).map { ShapeEntry("候选$it", "a") }), ShapeRules())) as p

    @Test fun `host selection state and pagination follow the interface contract`() {
        val p = proxy()
        assertSame(z8.g.PINYIN, p.a())
        assertSame(z8.t, p.f())
        assertFalse(p.e())
        assertEquals("", p.j())
        val input = p.i('a'.code)
        assertTrue(input.consumed)
        assertEquals("a", input.acceptedRaw)
        assertEquals(20, input.candidates.size)
        assertEquals("a", (p.f() as z8.s).raw)
        assertEquals(20, p.s().size)
        assertEquals((21..30).map { "候选$it" }, p.m(20, 20).map { it.text })
        assertTrue(p.m(-1, 20).isEmpty())
        assertTrue(p.m(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(p.m(0, -1).isEmpty())
        val invalid = p.n(200, "must not commit fallback")
        assertFalse(invalid.second)
        assertEquals("a", bridge.session(p)!!.code)
        val selected = p.n(21, "ignored")
        assertEquals("候选22", selected.first)
        assertTrue(selected.second)
        assertSame(z8.t, p.f())
        assertEquals("", p.b(0))
    }

    @Test fun `host reconstruction lifecycle and mixed characters preserve state`() {
        val p = proxy()
        p.q(com.mi.ime.MiInputMethodService(), z8.e())
        p.u(y8.b())
        p.l(0); p.p(0)
        assertEquals("a", p.d("a").code)
        assertFalse(p.g(emptyList()).consumed)
        assertFalse(p.r(0).consumed)
        assertTrue(p.v().isEmpty())
        assertEquals("候选3", p.h('3').commit)
        assertEquals("候选1", p.o("a ").commit)
        p.d("a")
        assertEquals("候选3", p.c().commit)
        p.d("a")
        assertEquals("", p.k().code)
        p.d("a"); p.t(); assertSame(z8.t, p.f())
        p.d("a"); p.clear(); assertSame(z8.t, p.f())
        p.d("a"); p.destroy(); assertSame(z8.t, p.f())
        assertEquals(p, p)
        assertNotEquals(p, proxy())
        assertEquals(System.identityHashCode(p), p.hashCode())
    }
}
