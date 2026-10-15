package io.mo.xatype.compat

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Hash every ZIP entry, including DEX entries at the start of large central directories. */
internal object ApkFingerprint {
    fun of(apk: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().sortedBy { it.name }.forEach { entry ->
                check(entry.crc >= 0 && entry.size >= 0) { "Incomplete ZIP entry: ${entry.name}" }
                digest.update("${entry.name}\u0000${entry.crc}/${entry.size}/${entry.compressedSize}/${entry.method}\n"
                    .toByteArray(Charsets.UTF_8))
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
