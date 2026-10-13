package io.mo.xatype.shape

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class ShapeEntry(val text: String, val code: String, val weight: Double = 0.0)

/** Immutable, shared between sessions. Candidate order is stable, including ties. */
class ShapeDictionary(val name: String, entries: List<ShapeEntry>) {
    val entries: List<ShapeEntry> = entries.toList()
    val maxCodeLength = entries.maxOfOrNull { it.code.length } ?: 0
    private val byCode = this.entries.groupBy { it.code }
    val codeCount: Int get() = byCode.size

    init {
        require(name.isNotBlank() && name.length <= 128) { "方案名称应为 1–128 个字符" }
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) { "码表为空或条目过多" }
        require(entries.all { validCode(it.code) && it.text.isNotEmpty() &&
            it.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES &&
            it.weight.isFinite() }) { "码表含不支持的编码或文字" }
    }

    fun candidates(code: String, sortByWeight: Boolean = false): List<ShapeEntry> {
        val found = byCode[code].orEmpty()
        return if (sortByWeight) found.sortedByDescending { it.weight } else found
    }

    companion object {
        const val MAX_ENTRIES = 500_000
        const val MAX_TEXT_BYTES = 4096
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
        fun validCode(code: String) = code.length in 1..8 && code.all { it in 'a'..'z' }
    }
}

data class ShapeImport(val dictionary: ShapeDictionary, val skipped: Int, val duplicates: Int)

/** Data-only importer: never executes YAML rules, Lua, commands or imported paths. */
object ShapeDictionaryParser {
    fun parse(input: InputStream, name: String): ShapeImport {
        val bytes = input.readBytesLimited(ShapeDictionary.MAX_FILE_BYTES)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = BufferedReader(InputStreamReader(bytes.inputStream(), decoder))
        val entries = ArrayList<ShapeEntry>()
        val seen = HashSet<Pair<String, String>>()
        var skipped = 0
        var duplicates = 0
        var yamlHeader = false
        var yamlData = false
        var fcitxHeader = false
        var fcitxData = false
        var columnBlock = false
        val columns = ArrayList<String>()
        var yaml = false
        reader.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.removePrefix("\uFEFF").trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEach
                require(line.length <= 16384) { "码表包含过长的行" }
                if (line == "---") { yaml = true; yamlHeader = true; return@forEach }
                if (line == "...") { yamlData = true; yamlHeader = false; return@forEach }
                if (line == "[Data]") { fcitxHeader = true; fcitxData = true; return@forEach }
                if (!yamlData && (line.startsWith("KeyCode=") || line.startsWith("[Rule]"))) {
                    fcitxHeader = true
                    return@forEach
                }
                if (yamlHeader || (yaml && !yamlData)) {
                    if (line.startsWith("columns:")) {
                        columns.clear()
                        columnBlock = true
                        val inline = line.substringAfter(':').trim()
                        if (inline.startsWith('[')) {
                            columns.addAll(inline.removeSurrounding("[", "]").split(',')
                                .map { it.trim().trim('"', '\'') })
                            columnBlock = false
                        }
                    } else if (columnBlock && line.startsWith("- ")) {
                        columns.add(line.removePrefix("- ").trim().trim('"', '\''))
                    } else {
                        columnBlock = false
                    }
                    return@forEach
                }
                if (fcitxHeader && !fcitxData) return@forEach
                val parts = if ('\t' in line) line.split('\t').map { it.trim() }
                    else line.split(Regex("\\s+"))
                if (parts.size < 2) { skipped++; return@forEach }
                val codeIndex: Int
                val textIndex: Int
                val weightIndex: Int
                if (yamlData) {
                    codeIndex = if (columns.isEmpty()) 1 else columns.indexOf("code")
                    textIndex = if (columns.isEmpty()) 0 else columns.indexOf("text")
                    weightIndex = if (columns.isEmpty()) 2 else columns.indexOf("weight")
                } else if (fcitxData || (ShapeDictionary.validCode(parts[0]) &&
                        !ShapeDictionary.validCode(parts[1]))) {
                    codeIndex = 0; textIndex = 1; weightIndex = 2
                } else {
                    textIndex = 0; codeIndex = 1; weightIndex = 2
                }
                val code = parts.getOrNull(codeIndex)
                val text = parts.getOrNull(textIndex)
                if (code == null || !ShapeDictionary.validCode(code) || text.isNullOrEmpty() ||
                    text.toByteArray(Charsets.UTF_8).size > ShapeDictionary.MAX_TEXT_BYTES) {
                    skipped++; return@forEach
                }
                if (!seen.add(code to text)) { duplicates++; return@forEach }
                val weight = parts.getOrNull(weightIndex)?.removeSuffix("%")?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() } ?: 0.0
                entries.add(ShapeEntry(text, code, weight))
                require(entries.size <= ShapeDictionary.MAX_ENTRIES) { "码表超过 50 万条" }
            }
        }
        require(entries.isNotEmpty()) { "没有有效码表。请导入 UTF-8 文本或含实际条目的 .dict.yaml" }
        return ShapeImport(ShapeDictionary(name.take(128), entries), skipped, duplicates)
    }
}

internal fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "码表文件超过 ${limit / 1024 / 1024} MB" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
