package io.mo.xatype.shape

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Module-owned device-protected data, so the keyboard also works before first unlock. */
object ShapeStore {
    const val PREFS = "shape_input"
    const val ENABLED = "enabled"
    const val MAX_CODE = "max_code_length"
    const val AUTO_COMMIT = "auto_commit_unique"
    const val OVERFLOW = "commit_on_overflow"
    const val DIGITS = "select_by_digits"
    const val SEMICOLON = "semicolon_second"
    const val QUOTE = "quote_third"
    const val SORT_WEIGHT = "sort_by_weight"
    const val STATUS = "host_status"
    const val STATUS_TIME = "host_status_time"
    const val NAME = "dictionary_name"
    const val COUNT = "dictionary_count"
    const val DICT_MAX = "dictionary_max_code"
    const val REVISION = "revision"

    private fun deviceContext(context: Context): Context = context.createDeviceProtectedStorageContext()
    fun prefs(context: Context): SharedPreferences = deviceContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun atomic(context: Context) = AtomicFile(File(deviceContext(context).filesDir, "shape-dictionary.xts"))

    @Synchronized
    fun ensureBuiltin(context: Context): ShapeDictionaryCodec.Header {
        val file = atomic(context)
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            return file.openRead().use(ShapeDictionaryCodec::header)
        }
        restoreBuiltin(context)
        return file.openRead().use(ShapeDictionaryCodec::header)
    }

    @Synchronized
    fun restoreBuiltin(context: Context): ShapeImport =
        GZIPInputStream(context.assets.open("shape/tiger.table")).use {
            import(context, it, "虎码字词（内置）")
        }

    @Synchronized
    fun import(context: Context, input: InputStream, name: String): ShapeImport {
        val parsed = ShapeDictionaryParser.parse(input, name)
        val file = atomic(context)
        val stream = file.startWrite()
        try {
            ShapeDictionaryCodec.write(parsed.dictionary, stream)
            require(stream.channel.size() <= ShapeDictionary.MAX_FILE_BYTES) { "转换后的码表超过 32 MB" }
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            throw t
        }
        val sp = prefs(context)
        check(sp.edit()
            .putInt(MAX_CODE, maxOf(sp.getInt(MAX_CODE, 4), parsed.dictionary.maxCodeLength))
            .putLong(REVISION, sp.getLong(REVISION, 0) + 1)
            .remove(STATUS).remove(STATUS_TIME).commit()) { "无法保存方案设置" }
        return parsed
    }

    @Synchronized
    fun snapshot(context: Context): Bundle {
        val sp = prefs(context)
        val bundle = Bundle().apply {
            putBoolean(ENABLED, sp.getBoolean(ENABLED, false))
            putInt(MAX_CODE, sp.getInt(MAX_CODE, 4).coerceIn(1, 8))
            putBoolean(AUTO_COMMIT, sp.getBoolean(AUTO_COMMIT, true))
            putBoolean(OVERFLOW, sp.getBoolean(OVERFLOW, true))
            putBoolean(DIGITS, sp.getBoolean(DIGITS, true))
            putBoolean(SEMICOLON, sp.getBoolean(SEMICOLON, true))
            putBoolean(QUOTE, sp.getBoolean(QUOTE, true))
            putBoolean(SORT_WEIGHT, sp.getBoolean(SORT_WEIGHT, false))
            putString(STATUS, sp.getString(STATUS, "尚未收到输入法运行结果"))
            putLong(STATUS_TIME, sp.getLong(STATUS_TIME, 0))
            putLong(REVISION, sp.getLong(REVISION, 0))
        }
        val file = atomic(context)
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val header = file.openRead().use(ShapeDictionaryCodec::header)
            bundle.putString(NAME, header.name)
            bundle.putInt(COUNT, header.entries)
            bundle.putInt(DICT_MAX, header.maxCodeLength)
        }
        return bundle
    }

    @Synchronized
    fun openReadOnly(context: Context): android.os.ParcelFileDescriptor {
        val file = atomic(context)
        // Let AtomicFile finish recovery before handing the stable inode to the host.
        file.openRead().close()
        return android.os.ParcelFileDescriptor.open(file.baseFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun rules(bundle: Bundle, minimum: Int) = ShapeRules(
        maxOf(bundle.getInt(MAX_CODE, 4), minimum).coerceIn(1, 8),
        bundle.getBoolean(AUTO_COMMIT, true), bundle.getBoolean(OVERFLOW, true),
        bundle.getBoolean(DIGITS, true), bundle.getBoolean(SEMICOLON, true),
        bundle.getBoolean(QUOTE, true), bundle.getBoolean(SORT_WEIGHT, false)
    )

    fun saveBoolean(context: Context, key: String, value: Boolean) {
        if (key == ENABLED && value) io.mo.xatype.keyboard18.Keyboard18Store.setEnabled(context, false)
        val sp = prefs(context)
        check(sp.edit().putBoolean(key, value).putLong(REVISION, sp.getLong(REVISION, 0) + 1)
            .remove(STATUS).remove(STATUS_TIME).commit())
    }
}
