package io.mo.xatype.diagnostics

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One bounded session; snapshots are flushed and copied under the same lock as writes.
 * The app writes the header, then [handOff] passes the file to a root logcat that appends the rest.
 */
object DiagnosticLog {
    const val MAX_BYTES = 8 * 1024 * 1024
    /** Kept free after hand-off for the end marker and the stop note. */
    private const val TRAILER_BYTES = 1024
    /** No session in this process yet; a log from an earlier one may still be on disk. */
    const val IDLE_STATUS = "尚未开始采集"
    @Volatile var status = IDLE_STATUS
        private set
    @Volatile var running = false
        private set
    /** The last session ended with an error rather than a stop or a limit. */
    @Volatile var failed = false
        private set
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var bytes = 0
    private var generation = 0L

    internal fun logFile(context: Context) = File(context.filesDir, "input-diagnostic.txt")

    fun begin(context: Context): Long = begin(logFile(context))

    @Synchronized internal fun begin(file: File): Long {
        writer?.close()
        generation++
        this.file = file
        writer = file.bufferedWriter()
        bytes = 0
        running = true
        failed = false
        status = "正在请求 Root 权限…"
        return generation
    }

    @Synchronized fun append(token: Long, text: String): Boolean {
        if (token != generation || writer == null) return false
        val line = text.take(16_384) + "\n"
        val size = line.toByteArray(Charsets.UTF_8).size
        if (bytes + size > MAX_BYTES) {
            finish(token, "日志已达到 8 MB，已自动停止")
            return false
        }
        writer!!.write(line)
        writer!!.flush()
        bytes += size
        return true
    }

    @Synchronized fun update(token: Long, message: String) {
        if (token == generation && running) status = message
    }

    @Synchronized fun isActive(token: Long): Boolean = token == generation && running

    /** Closes the app's writer and returns the byte budget left for the root capture, or -1 if stale. */
    @Synchronized fun handOff(token: Long): Long {
        if (token != generation || writer == null) return -1
        writer!!.close()
        writer = null
        return (MAX_BYTES - bytes - TRAILER_BYTES).toLong().coerceAtLeast(0)
    }

    /** Resumes tracking a root capture that outlived the app process that started it. */
    @Synchronized internal fun adopt(file: File, message: String): Long {
        writer?.close()
        writer = null
        generation++
        this.file = file
        running = true
        failed = false
        status = message
        return generation
    }

    /** [note] appends [message] to the log; skip it when the root capture already wrote its end marker. */
    @Synchronized fun finish(token: Long, message: String, note: Boolean = true, error: Boolean = false) {
        if (token != generation || !running) return
        val current = writer
        if (current != null) {
            if (note) current.write("\n[collector] $message\n")
            current.close()
        } else if (note) {
            file?.appendText("\n[collector] $message\n")
        }
        writer = null
        running = false
        failed = error
        status = message
    }

    fun snapshot(context: Context): File = snapshot(logFile(context), File(context.cacheDir, "diagnostics"))

    @Synchronized internal fun snapshot(source: File, directory: File): File {
        writer?.flush()
        check(source.exists()) { "请先开始采集并复现问题" }
        directory.mkdirs()
        // Only files owned by this exporter, kept for 24 hours for delayed share targets.
        directory.listFiles()?.filter {
            it.isFile && it.name.startsWith("xiaotype-") &&
                System.currentTimeMillis() - it.lastModified() > 86_400_000L
        }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return source.copyTo(File(directory, "xiaotype-$stamp.txt"))
    }
}
