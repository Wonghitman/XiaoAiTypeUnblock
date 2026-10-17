package io.mo.xatype.keyboard18

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.File
import io.mo.xatype.keyboard.ShuangpinScheme

object Keyboard18Store {
    const val ENABLED = "enabled" // Legacy migration only.
    const val SHOW_IN_PICKER = "show_in_picker"
    const val SHUANGPIN = "shuangpin"
    const val SCHEME = "shuangpin_scheme"
    const val REVISION = "revision"
    const val STATUS = "host_status"
    const val STATUS_TIME = "host_status_time"
    private fun device(context: Context) = context.createDeviceProtectedStorageContext()
    fun prefs(context: Context) = device(context).getSharedPreferences("keyboard18", Context.MODE_PRIVATE)
    fun setVisible(context: Context, visible: Boolean) {
        val sp = prefs(context)
        check(sp.edit().putBoolean(SHOW_IN_PICKER, visible).putLong(REVISION, sp.getLong(REVISION, 0) + 1)
            .remove(STATUS).remove(STATUS_TIME).commit()) { "无法保存18键设置" }
    }
    fun setShuangpin(context: Context, enabled: Boolean, scheme: ShuangpinScheme) {
        val sp = prefs(context)
        check(sp.edit().putBoolean(SHUANGPIN, enabled).putString(SCHEME, scheme.name)
            .putLong(REVISION, sp.getLong(REVISION, 0) + 1).remove(STATUS).remove(STATUS_TIME).commit()) {
            "无法保存18键双拼设置"
        }
    }
    fun scheme(settings: Bundle): ShuangpinScheme? = if (settings.getBoolean(SHUANGPIN))
        ShuangpinScheme.resolve(settings.getString(SCHEME)) else null
    fun snapshot(context: Context) = prefs(context).let { sp -> Bundle().apply {
        putBoolean(ENABLED, sp.getBoolean(ENABLED, false))
        putBoolean(SHUANGPIN, sp.getBoolean(SHUANGPIN, false))
        putString(SCHEME, ShuangpinScheme.resolve(sp.getString(SCHEME, null)).name)
        putBoolean(SHOW_IN_PICKER, sp.getBoolean(SHOW_IN_PICKER, sp.getBoolean(ENABLED, true)))
        putLong(REVISION, sp.getLong(REVISION, 0))
        putString(STATUS, sp.getString(STATUS, "尚未接入输入法"))
        putLong(STATUS_TIME, sp.getLong(STATUS_TIME, 0))
    } }
    @Synchronized
    fun dictionaryFile(context: Context): File {
        val atomic = AtomicFile(File(device(context).filesDir, "keyboard18-pinyin.table"))
        if (!atomic.baseFile.exists()) {
            val stream = atomic.startWrite()
            try {
                context.assets.open("keyboard18/pinyin.table").use { it.copyTo(stream) }
                atomic.finishWrite(stream)
            } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        }
        atomic.openRead().close()
        return atomic.baseFile
    }
    fun openDictionary(context: Context): ParcelFileDescriptor =
        ParcelFileDescriptor.open(dictionaryFile(context), ParcelFileDescriptor.MODE_READ_ONLY)
}
