package io.mo.xatype.compat

import dalvik.system.DexFile
import java.io.File

/** Locate the defining DEX container before a dynamically loaded package has a Context. */
internal object LoadedApkSource {
    @Suppress("DEPRECATION")
    fun find(loader: ClassLoader, className: String): String? = runCatching {
        val pathList = field(loader, "pathList") ?: return null
        val elements = field(pathList, "dexElements") as? Array<*> ?: return null
        elements.asSequence().filterNotNull().mapNotNull { element ->
            field(element, "dexFile") as? DexFile
        }.firstOrNull { dex ->
            File(dex.name).isFile && dex.entries().asSequence().any { it == className }
        }?.name
    }.getOrNull()

    private fun field(instance: Any, name: String): Any? {
        var owner: Class<*>? = instance.javaClass
        while (owner != null) {
            try {
                return owner.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                owner = owner.superclass
            }
        }
        return null
    }
}
