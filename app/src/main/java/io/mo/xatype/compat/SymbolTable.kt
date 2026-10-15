package io.mo.xatype.compat

import java.io.File
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** Descriptors per symbol key, plus the reason each unresolved key missed. */
class FingerprintResult(
    val resolved: Map<String, List<String>>,
    val missing: Map<String, String>,
    val retryable: Set<String> = emptySet()
)

/** A fingerprint that matched no member, or more than one. */
internal class FingerprintMiss(message: String) : Exception(message)

internal fun <T> single(candidates: Collection<T>, what: String): T =
    candidates.singleOrNull() ?: throw FingerprintMiss("$what: ${candidates.size} candidates")

/** Runs fingerprints independently so one miss never hides the others. */
internal class FingerprintCollector {
    private val resolved = LinkedHashMap<String, List<String>>()
    private val missing = LinkedHashMap<String, String>()
    private val retryable = LinkedHashSet<String>()

    /** [block] returns one descriptor list per key, in [keys] order. */
    fun put(vararg keys: String, block: () -> List<List<String>>) {
        try {
            val values = block()
            if (values.size != keys.size) throw FingerprintMiss("Expected ${keys.size} results, got ${values.size}")
            values.forEachIndexed { index, entries -> resolved[keys[index]] = entries }
        } catch (t: Throwable) {
            keys.forEach { missing[it] = (t as? FingerprintMiss)?.message ?: t.toString() }
            if (t !is FingerprintMiss) retryable.addAll(keys)
        }
    }

    fun result() = FingerprintResult(resolved, missing, retryable)
}

/**
 * Obfuscated members located by fingerprint instead of by name.
 *
 * The scan runs once per installed APK; results (including misses) are cached
 * so later launches only resolve descriptors by reflection. A missing symbol
 * disables only the hook that needs it, which then falls back to the names
 * verified for earlier builds.
 */
abstract class SymbolTable(
    /** Bump whenever a fingerprint changes so stale caches are rescanned. */
    private val schema: Int,
    private val scan: (DexKitBridge, ClassLoader) -> FingerprintResult
) {
    /** How the current symbols were obtained, for the status shown to users. */
    enum class Source { CACHE, SCAN, UNAVAILABLE }

    /** Every key the fingerprints produce. */
    abstract val keys: List<String>

    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var symbols: Map<String, List<String>> = emptyMap()
    @Volatile private var activeCache: File? = null

    @Volatile var source: Source = Source.UNAVAILABLE
        private set

    /** Unresolved keys with the reason each fingerprint missed. */
    @Volatile var missing: Map<String, String> = emptyMap()
        private set

    /**
     * @param apkPath the APK [loader] was created from; without it the dex is
     * read through the class loader and the result is not cached.
     * @param moduleStamp identifies the installed module build, so any
     * reinstall rescans even when the version code is unchanged.
     * @param loadNative loads libdexkit; only called on a cache miss.
     * @return a one-line summary for the module log.
     */
    @Synchronized
    fun init(
        loader: ClassLoader,
        apkPath: String?,
        cacheFile: File?,
        moduleStamp: String,
        loadNative: () -> Unit = { System.loadLibrary("dexkit") }
    ): String {
        classLoader = loader
        activeCache = cacheFile
        val cacheKey = apkPath?.let {
            val apk = File(it)
            ApkFingerprint.of(apk)?.let { fingerprint ->
                "$moduleStamp/$schema/zip-v2/${apk.length()}/${apk.lastModified()}/$fingerprint"
            }
        }
        if (cacheKey != null) {
            readCache(cacheFile, cacheKey)?.let {
                symbols = it.resolved
                missing = it.missing
                source = Source.CACHE
                return "fingerprints from cache: ${it.resolved.size} resolved" + describeMissing()
            }
        }

        val started = System.nanoTime()
        val result = try {
            val scanned = performScan(loader, apkPath, loadNative)
            val invalid = scanned.resolved.filter { (name, values) -> name !in keys || !validateValues(name, values) }.keys
            val absent = keys.toSet() - scanned.resolved.keys - scanned.missing.keys
            FingerprintResult(scanned.resolved - invalid,
                scanned.missing + (invalid + absent).associateWith { "Resolved symbols failed validation; retry next launch" },
                scanned.retryable + invalid + absent)
        } catch (t: Throwable) {
            // Not cached: a transient failure (e.g. native load) retries next launch.
            symbols = emptyMap()
            missing = keys.associateWith { t.toString() }
            source = Source.UNAVAILABLE
            cacheFile?.delete()
            return "fingerprint scan unavailable: $t"
        }
        symbols = result.resolved
        missing = result.missing
        source = Source.SCAN
        // A class-loader/JNI/resource failure is not a stable negative result.
        // Leave no cache behind so the next launch retries it.
        if (cacheKey != null && result.retryable.isEmpty()) writeCache(cacheFile, cacheKey, result)
        else cacheFile?.delete()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return "fingerprint scan ${elapsed}ms: ${result.resolved.size} resolved" + describeMissing()
    }

    protected open fun performScan(loader: ClassLoader, apkPath: String?, loadNative: () -> Unit): FingerprintResult {
        loadNative()
        val bridge = apkPath?.let { DexKitBridge.create(it) } ?: DexKitBridge.create(loader, false)
        return bridge.use { scan(it, loader) }
    }

    private fun describeMissing(): String =
        if (missing.isEmpty()) "" else "; missing: " + missing.entries.joinToString { "${it.key} (${it.value})" }

    fun has(key: String): Boolean = symbols.containsKey(key)

    fun describe(): Map<String, List<String>> = symbols

    /** Raw entries of a key that stores values other than descriptors. */
    fun strings(key: String): List<String>? = symbols[key]

    fun clazz(key: String): Class<*>? = symbols[key]?.singleOrNull()?.takeIf { "->" !in it && it.startsWith('L') }?.let {
        runCatching { DexClass(it).getInstance(loader()) }.getOrElse { failure ->
            invalidate(key, failure)
            null
        }
    }

    fun method(key: String): Method? = if (symbols[key]?.singleOrNull()?.let { it.startsWith('L') && "->" in it && '(' in it } == true)
        first(key) { DexMethod(it).getMethodInstance(loader()) } else null

    fun methods(key: String): List<Method> =
        symbols[key].orEmpty().filter { it.startsWith('L') && "->" in it && '(' in it }.mapNotNull { descriptor ->
            runCatching { DexMethod(descriptor).getMethodInstance(loader()).apply { isAccessible = true } }
                .getOrElse { failure -> invalidate(key, failure); null }
        }

    fun field(key: String): Field? = if (symbols[key]?.singleOrNull()?.let { it.startsWith('L') && "->" in it && '(' !in it } == true)
        first(key) { DexField(it).getFieldInstance(loader()) } else null

    private fun loader() = checkNotNull(classLoader)
    protected fun currentLoader(): ClassLoader? = classLoader

    private fun <T : AccessibleObject> first(key: String, block: (String) -> T): T? =
        symbols[key]?.singleOrNull()?.let { descriptor ->
            runCatching { block(descriptor).apply { isAccessible = true } }
                .getOrElse { failure -> invalidate(key, failure); null }
        }

    /** Runtime installation failures are reported alongside fingerprint misses. */
    @Synchronized
    fun markUnavailable(key: String, failure: Throwable) {
        missing = missing + (key to failure.toString())
    }

    @Synchronized
    private fun invalidate(key: String, failure: Throwable) {
        symbols = symbols - key
        markUnavailable(key, failure)
        activeCache?.delete()
    }

    /** Override for keys containing profile properties instead of descriptors. */
    protected open fun validateValues(key: String, values: List<String>): Boolean =
        values.isNotEmpty() && values.all { descriptor ->
            runCatching {
                when {
                    "->" !in descriptor -> DexClass(descriptor).getInstance(loader())
                    '(' in descriptor -> DexMethod(descriptor).getMethodInstance(loader()).apply { isAccessible = true }
                    else -> DexField(descriptor).getFieldInstance(loader()).apply { isAccessible = true }
                }
            }.isSuccess
        }

    private fun readCache(file: File?, key: String): FingerprintResult? = runCatching {
        if (file == null || !file.isFile) return null
        val json = JSONObject(file.readText())
        if (json.getString("key") != key) return null
        val entries = json.getJSONObject("symbols")
        val resolved = entries.keys().asSequence().associateWith { name ->
            val values = entries.getJSONArray(name)
            List(values.length()) { values.getString(it) }
        }
        val misses = json.optJSONObject("missing")
        val missing = misses?.keys()?.asSequence()?.associateWith { misses.getString(it) }.orEmpty()
        if (resolved.keys.intersect(missing.keys).isNotEmpty()) return null
        if (resolved.keys + missing.keys != keys.toSet()) return null
        if (resolved.any { (name, values) -> !validateValues(name, values) }) return null
        FingerprintResult(resolved, missing)
    }.getOrNull()

    private fun writeCache(file: File?, key: String, result: FingerprintResult) {
        if (file == null) return
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        runCatching {
            val entries = JSONObject()
            result.resolved.forEach { (name, values) -> entries.put(name, JSONArray(values)) }
            val misses = JSONObject()
            result.missing.forEach { (name, reason) -> misses.put(name, reason) }
            file.parentFile?.mkdirs()
            // Several processes may scan concurrently; rename publishes atomically.
            temp.writeText(
                JSONObject().put("key", key).put("symbols", entries).put("missing", misses).toString()
            )
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        temp.delete()
    }
}
