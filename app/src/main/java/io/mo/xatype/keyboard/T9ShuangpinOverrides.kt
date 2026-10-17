package io.mo.xatype.keyboard

/** Retain the nine-key source name while sharing scheme IDs with the offline decoder. */
typealias T9ShuangpinScheme = ShuangpinScheme

/** Limit virtual preferences to one T9 initialization, including merged Pinyin/T9 classes. */
class T9ShuangpinOverrides {
    private val current = ThreadLocal<T9ShuangpinScheme>()

    fun booleanValue(key: String?): Boolean? = if (current.get() != null && key == "shuangpin_enabled") true else null
    fun stringValue(key: String?): String? = if (key == "shuangpin_scheme") current.get()?.name else null

    fun <T> duringInitialization(mode: String?, scheme: T9ShuangpinScheme?, block: () -> T): T {
        val previous = current.get()
        if (mode == "T9" && scheme != null) current.set(scheme) else current.remove()
        return try { block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
