package io.mo.xatype.shape

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Versioned on-disk snapshot; import parses text once, host loads only validated records. */
object ShapeDictionaryCodec {
    private const val MAGIC = 0x58545331 // XTS1
    data class Header(val name: String, val entries: Int, val maxCodeLength: Int)

    fun write(dictionary: ShapeDictionary, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeUTF(dictionary.name)
        out.writeInt(dictionary.entries.size)
        out.writeInt(dictionary.maxCodeLength)
        for (entry in dictionary.entries) {
            out.writeUTF(entry.code)
            val text = entry.text.toByteArray(Charsets.UTF_8)
            out.writeInt(text.size)
            out.write(text)
            out.writeDouble(entry.weight)
        }
        out.flush()
    }

    fun header(input: InputStream): Header = header(DataInputStream(input))

    private fun header(input: DataInputStream): Header {
        require(input.readInt() == MAGIC) { "不支持的码表版本" }
        val name = input.readUTF()
        val count = input.readInt()
        val max = input.readInt()
        require(name.isNotBlank() && name.length <= 128 &&
            count in 1..ShapeDictionary.MAX_ENTRIES && max in 1..8) { "码表头损坏" }
        return Header(name, count, max)
    }

    fun read(input: InputStream): ShapeDictionary {
        val source = DataInputStream(input.readBytesLimited(ShapeDictionary.MAX_FILE_BYTES).inputStream())
        val header = header(source)
        val entries = ArrayList<ShapeEntry>(header.entries)
        repeat(header.entries) {
            val code = source.readUTF()
            val length = source.readInt()
            require(length in 1..ShapeDictionary.MAX_TEXT_BYTES) { "码表文字长度异常" }
            val text = ByteArray(length).also { source.readFully(it) }.toString(Charsets.UTF_8)
            entries.add(ShapeEntry(text, code, source.readDouble()))
        }
        require(source.read() == -1) { "码表包含多余数据" }
        return ShapeDictionary(header.name, entries).also {
            require(it.maxCodeLength == header.maxCodeLength) { "码表码长不一致" }
        }
    }
}
