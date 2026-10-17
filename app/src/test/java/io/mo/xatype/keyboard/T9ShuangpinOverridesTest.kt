package io.mo.xatype.keyboard

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class T9ShuangpinOverridesTest {
    private val overrides = T9ShuangpinOverrides()

    @Test fun `all seven schemes use their host IDs and old invalid settings fall back to Xiaohe`() {
        assertEquals(setOf("XIAOHE", "ZIRANMA", "WEIRUAN", "SOUGOU", "ZHINENG_ABC", "PINYIN_JIAJIA", "ZIGUANG"),
            T9ShuangpinScheme.entries.map { it.name }.toSet())
        assertEquals(T9ShuangpinScheme.XIAOHE, T9ShuangpinScheme.resolve(null))
        assertEquals(T9ShuangpinScheme.XIAOHE, T9ShuangpinScheme.resolve("unknown"))
        for (scheme in T9ShuangpinScheme.entries) {
            assertSame(scheme, T9ShuangpinScheme.resolve(scheme.name))
            overrides.duringInitialization("T9", scheme) {
                assertEquals(true, overrides.booleanValue("shuangpin_enabled"))
                assertEquals(scheme.name, overrides.stringValue("shuangpin_scheme"))
                assertNull(overrides.booleanValue("fuzzy_pinyin_master_enabled"))
                assertNull(overrides.stringValue("engine_type"))
            }
            assertInactive()
        }
    }

    @Test fun `disabled T9 and every other native engine preserve native preferences`() {
        for (mode in listOf("PINYIN", "SHUANGPIN", "ENGLISH", "STROKE", "HANDWRITING", "VOICE", null)) {
            overrides.duringInitialization(mode, T9ShuangpinScheme.XIAOHE) { assertInactive() }
        }
        overrides.duringInitialization("T9", null) { assertInactive() }
    }

    @Test fun `failed native initialization cannot leak overrides into later input or settings screens`() {
        assertThrows(IllegalStateException::class.java) {
            overrides.duringInitialization("T9", T9ShuangpinScheme.ZIRANMA) {
                assertEquals("ZIRANMA", overrides.stringValue("shuangpin_scheme"))
                throw IllegalStateException("native initialization failed")
            }
        }
        assertInactive()
    }

    @Test fun `nested Pinyin initialization and other threads never inherit T9 settings`() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            overrides.duringInitialization("T9", T9ShuangpinScheme.XIAOHE) {
                overrides.duringInitialization("PINYIN", T9ShuangpinScheme.WEIRUAN) { assertInactive() }
                assertEquals("XIAOHE", overrides.stringValue("shuangpin_scheme"))
                overrides.duringInitialization("T9", T9ShuangpinScheme.SOUGOU) {
                    assertEquals("SOUGOU", overrides.stringValue("shuangpin_scheme"))
                }
                assertEquals("XIAOHE", overrides.stringValue("shuangpin_scheme"))
                worker.submit { assertInactive() }.get()
            }
            assertInactive()
        } finally { worker.shutdownNow() }
    }

    private fun assertInactive() {
        assertNull(overrides.booleanValue("shuangpin_enabled"))
        assertNull(overrides.stringValue("shuangpin_scheme"))
    }
}
