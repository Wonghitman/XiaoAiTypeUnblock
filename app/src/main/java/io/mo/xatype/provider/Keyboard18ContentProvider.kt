package io.mo.xatype.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import io.mo.xatype.keyboard18.Keyboard18Store
import java.io.FileNotFoundException

class Keyboard18ContentProvider : ContentProvider() {
    override fun onCreate() = true
    private fun caller() {
        val ctx = checkNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && ctx.packageManager.getPackagesForUid(uid)?.contains("com.xiaomi.type") != true)
            throw SecurityException("18-key data is restricted to the module and input method")
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        caller()
        val ctx = checkNotNull(context)
        return when (method) {
            "snapshot" -> Keyboard18Store.snapshot(ctx)
            "report" -> {
                val sp = Keyboard18Store.prefs(ctx)
                if (extras != null && extras.getLong(Keyboard18Store.REVISION, -1) == sp.getLong(Keyboard18Store.REVISION, 0)) {
                    sp.edit().putString(Keyboard18Store.STATUS, extras.getString(Keyboard18Store.STATUS).orEmpty().take(400))
                        .putLong(Keyboard18Store.STATUS_TIME, System.currentTimeMillis()).apply()
                }
                Bundle()
            }
            else -> super.call(method, arg, extras)
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        caller()
        if (uri != DICTIONARY_URI || mode != "r") throw FileNotFoundException("Read-only dictionary URI required")
        return Keyboard18Store.openDictionary(checkNotNull(context))
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object {
        val CONTENT_URI: Uri = Uri.parse("content://io.mo.xatype.keyboard18provider")
        val DICTIONARY_URI: Uri = Uri.parse("content://io.mo.xatype.keyboard18provider/dictionary")
    }
}
