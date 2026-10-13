package io.mo.xatype.shape

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

class ShapeDictionaryTest {
    private fun parse(text: String) = ShapeDictionaryParser.parse(text.byteInputStream(), "测试")

    @Test fun `plain tables preserve order unicode and phrase spaces`() {
        val parsed = parse("\uFEFF# comment\n你好\tjx\t20\nabc\t词语\t10\n你好\tjx\t30\nhello world\thello\n坏行\n坏码\tA1\n")
        assertEquals(listOf("你好", "词语", "hello world"), parsed.dictionary.entries.map { it.text })
        assertEquals(2, parsed.skipped)
        assertEquals(1, parsed.duplicates)
        assertEquals(20.0, parsed.dictionary.entries.first().weight, 0.0)
    }

    @Test fun `rime columns and metadata are data only`() {
        val parsed = parse("""
            ---
            name: test
            version: "1"
            columns: [code, weight, text]
            import_tables:
              - never_load_this
            ...
            jx	99	你
            bh	80%	好
        """.trimIndent())
        assertEquals(listOf(ShapeEntry("你", "jx", 99.0), ShapeEntry("好", "bh", 80.0)), parsed.dictionary.entries)
        val block = parse("---\ncolumns:\n - text\n - code\n...\n你\tjx\n")
        assertEquals("你", block.dictionary.entries.single().text)
    }

    @Test fun `fcitx header and rules never become entries`() {
        val parsed = parse("KeyCode=abcdefghijklmnopqrstuvwxyz\r\nLength=4\n[Rule]\ne2=p11+p12\n[Data]\nu\t的\nf\t一\n")
        assertEquals(listOf(ShapeEntry("的", "u"), ShapeEntry("一", "f")), parsed.dictionary.entries)
        assertEquals(0, parsed.skipped)
    }

    @Test fun `a four letter wubi table uses the same engine without pinyin rules`() {
        val table = ShapeDictionaryParser.parse("你\twqiy\n好\tvbg\n".byteInputStream(), "五笔86").dictionary
        val engine = ShapeEngine(table, ShapeRules())
        assertEquals("你好", engine.inputText("wqiyvbg ").commit)
        assertTrue(engine.code.isEmpty())
    }

    @Test fun `invalid empty schema and non UTF8 tables fail`() {
        assertThrows(IllegalArgumentException::class.java) { parse("---\nname: empty\n...\n") }
        assertThrows(IllegalArgumentException::class.java) { parse("坏码\t123\n") }
        assertThrows(java.nio.charset.MalformedInputException::class.java) {
            ShapeDictionaryParser.parse(byteArrayOf(0xff.toByte(), 0xfe.toByte()).inputStream(), "测试")
        }
    }

    @Test fun `binary codec roundtrip rejects truncation unknown version and trailing bytes`() {
        val dictionary = parse("𠀀\tabcd\t2\n你好\tjx\n").dictionary
        val bytes = ByteArrayOutputStream().also { ShapeDictionaryCodec.write(dictionary, it) }.toByteArray()
        assertEquals(dictionary.entries, ShapeDictionaryCodec.read(bytes.inputStream()).entries)
        assertEquals(dictionary.entries.size, ShapeDictionaryCodec.header(bytes.inputStream()).entries)
        assertThrows(java.io.EOFException::class.java) { ShapeDictionaryCodec.read(bytes.copyOf(bytes.size - 1).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { ShapeDictionaryCodec.read((bytes + byteArrayOf(1)).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { ShapeDictionaryCodec.read(bytes.clone().also { it[0] = 0 }.inputStream()) }
    }

    @Test fun `bundled tiger table is complete and produces real words`() {
        val asset = File("src/main/assets/shape/tiger.table")
        val parsed = GZIPInputStream(asset.inputStream()).use { ShapeDictionaryParser.parse(it, "虎码") }
        assertEquals(171074, parsed.dictionary.entries.size)
        assertEquals(0, parsed.skipped)
        assertEquals(0, parsed.duplicates)
        assertEquals(4, parsed.dictionary.maxCodeLength)
        val e = ShapeEngine(parsed.dictionary, ShapeRules())
        assertEquals("你好", e.inputText("jx bh ").commit)
        val bytes = ByteArrayOutputStream().also { ShapeDictionaryCodec.write(parsed.dictionary, it) }.toByteArray()
        assertTrue(bytes.size < ShapeDictionary.MAX_FILE_BYTES)
        assertEquals(parsed.dictionary.entries, ShapeDictionaryCodec.read(bytes.inputStream()).entries)
    }
}
