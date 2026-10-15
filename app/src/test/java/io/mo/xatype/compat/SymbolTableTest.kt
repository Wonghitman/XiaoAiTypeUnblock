package io.mo.xatype.compat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SymbolTableTest {
    @get:Rule val folder = TemporaryFolder()
    private val descriptor = "Ljava/lang/String;->length()I"
    private val loader = javaClass.classLoader!!

    private inner class Table(schema: Int = 1) : SymbolTable(schema, { _, _ -> error("Test uses injected scanner") }) {
        override val keys = listOf("method")
        var scans = 0
        var next = FingerprintResult(mapOf("method" to listOf(descriptor)), emptyMap())
        override fun performScan(loader: ClassLoader, apkPath: String?, loadNative: () -> Unit): FingerprintResult {
            scans++
            return next
        }
    }

    private fun apk(name: String = "host.apk", content: String = "AAAA", large: Boolean = false): File =
        File(folder.root, name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                fun entry(name: String, value: String) {
                    val bytes = value.toByteArray()
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        time = 1_700_000_000_000
                        size = bytes.size.toLong()
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    })
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("classes.dex", content)
                if (large) repeat(1800) { entry("res/raw/fixture_%04d.bin".format(it), "x") }
            }
            assertTrue(setLastModified(1_700_000_000_000))
        }

    @Test fun `all DEX metadata participates even beyond the final 64 KiB`() {
        val a = apk("a.apk", "AAAA", true)
        val b = apk("b.apk", "BBBB", true)
        assertEquals(a.length(), b.length())
        assertEquals(a.lastModified(), b.lastModified())
        assertArrayEquals(a.readBytes().takeLast(65536).toByteArray(), b.readBytes().takeLast(65536).toByteArray())
        assertNotEquals(ApkFingerprint.of(a), ApkFingerprint.of(b))
        val cache = File(folder.root, "cache.json")
        val table = Table()
        table.init(loader, a.path, cache, "module")
        table.init(loader, b.path, cache, "module")
        assertEquals(2, table.scans)
    }

    @Test fun `a valid cache reloads without scanning but build or schema changes rescan`() {
        val host = apk(); val cache = File(folder.root, "cache.json"); val table = Table()
        table.init(loader, host.path, cache, "module")
        assertTrue(cache.isFile)
        table.init(loader, host.path, cache, "module")
        assertEquals(1, table.scans)
        assertEquals(SymbolTable.Source.CACHE, table.source)
        assertEquals("length", table.method("method")!!.name)
        table.init(loader, host.path, cache, "new-module")
        assertEquals(2, table.scans)
        val revised = Table(2)
        revised.init(loader, host.path, cache, "new-module")
        assertEquals(1, revised.scans)
    }

    @Test fun `missing keys unloadable descriptors and overlapping states reject caches`() {
        for (kind in listOf("incomplete", "unloadable", "overlap", "malformed")) {
            val host = apk(); val cache = File(folder.root, "cache.json"); val table = Table()
            cache.delete()
            table.init(loader, host.path, cache, "module")
            val json = JSONObject(cache.readText())
            when (kind) {
                "incomplete" -> json.put("symbols", JSONObject())
                "unloadable" -> json.getJSONObject("symbols").put("method", listOf("Lmissing/Host;->gone()V"))
                "overlap" -> json.getJSONObject("missing").put("method", "miss")
            }
            cache.writeText(if (kind == "malformed") "{broken" else json.toString())
            table.init(loader, host.path, cache, "module")
            assertEquals(kind, 2, table.scans)
            assertEquals(kind, SymbolTable.Source.SCAN, table.source)
            assertTrue(table.missing.isEmpty())
        }
    }

    @Test fun `transient per-key failure retries after restart while stable misses are cached`() {
        val host = apk(); val cache = File(folder.root, "cache.json")
        val collector = FingerprintCollector()
        collector.put("method") { throw IllegalStateException("temporary class loader failure") }
        val failed = Table().apply { next = collector.result() }
        failed.init(loader, host.path, cache, "module")
        assertFalse(cache.exists())
        val restarted = Table()
        restarted.init(loader, host.path, cache, "module")
        assertEquals(1, restarted.scans)
        val stable = FingerprintCollector()
        stable.put("method") { throw FingerprintMiss("No unique target") }
        val unavailable = Table().apply { next = stable.result() }
        cache.delete()
        unavailable.init(loader, host.path, cache, "module")
        assertTrue(cache.exists())
        unavailable.init(loader, host.path, cache, "module")
        assertEquals(1, unavailable.scans)
        assertEquals(SymbolTable.Source.CACHE, unavailable.source)
    }

    @Test fun `partial collector output never becomes a partial success`() {
        val collector = FingerprintCollector()
        collector.put("one", "two") { listOf(listOf(descriptor)) }
        assertTrue(collector.result().resolved.isEmpty())
        assertEquals(setOf("one", "two"), collector.result().missing.keys)
    }

    @Test fun `asking for the wrong descriptor kind preserves a valid cache`() {
        val host = apk(); val cache = File(folder.root, "cache.json"); val table = Table()
        table.next = FingerprintResult(mapOf("method" to listOf("Ljava/lang/String;")), emptyMap())
        table.init(loader, host.path, cache, "module")
        assertTrue(table.methods("method").isEmpty())
        assertNull(table.method("method"))
        assertNull(table.field("method"))
        assertEquals(String::class.java, table.clazz("method"))
        assertTrue(table.missing.isEmpty())
        assertTrue(cache.isFile)
    }
}
