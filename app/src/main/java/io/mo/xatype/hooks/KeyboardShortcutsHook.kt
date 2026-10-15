package io.mo.xatype.hooks

import android.inputmethodservice.InputMethodService
import android.os.Build
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.keyboard.KeyboardShortcutActions
import io.mo.xatype.keyboard.KeyboardShortcutHostLayout
import io.mo.xatype.keyboard.KeyboardShortcutHostActions
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.util.XposedUtils

/** Native 26-key integration; does not load or require either experimental decoder. */
object KeyboardShortcutsHook {
    @Volatile private var service: InputMethodService? = null
    @Volatile private var supported: Boolean? = null
    @Volatile private var layoutMode = "PINYIN"

    fun install(module: XposedInterface, loader: ClassLoader) {
        val layouts = KeyboardShortcutHostLayout(loader)
        val actions = KeyboardShortcutHostActions(loader)
        val hostService = Class.forName("com.mi.ime.MiInputMethodService", false, loader)
        val manager = Class.forName("ab.p1", false, loader)
        val context = manager.declaredFields.single { hostService.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val engineType = Class.forName("z8.g", false, loader)
        val factory = manager.getDeclaredMethod("e", String::class.java, Integer::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Class.forName("la.a", false, loader))
        val callbacks = Class.forName("ab.b1", false, loader)
        val handler = Class.forName("ab.f1", false, loader).getDeclaredMethod("c", layouts.key, callbacks)
        val label = layouts.key.getDeclaredField("b").apply { isAccessible = true }
        val kind = layouts.key.getDeclaredField("f").apply { isAccessible = true }
        val popup = layouts.key.getDeclaredField("i").apply { isAccessible = true }
        val mode = callbacks.getMethod("m")
        val layoutId = callbacks.getMethod("A")
        require(factory.returnType == layouts.layout && handler.returnType == Void.TYPE)

        val modeChange = manager.getDeclaredMethod("H", engineType,
            android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType)
        val editorChange = manager.getDeclaredMethod("G", android.view.inputmethod.EditorInfo::class.java, engineType)
        for ((method, index) in listOf(modeChange to 0, editorChange to 1)) {
            module.hook(method).intercept { chain ->
                layoutMode = (chain.getArg(index) as Enum<*>).name
                chain.proceed()
            }
            module.deoptimize(method)
        }
        module.hook(factory).intercept { chain ->
            val ime = context.get(chain.thisObject) as InputMethodService
            service = ime
            if (supported == null) {
                supported = runCatching {
                    @Suppress("DEPRECATION")
                    val info = ime.packageManager.getPackageInfo("com.xiaomi.type", 0)
                    @Suppress("DEPRECATION")
                    (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()) == 21053L
                }.getOrDefault(false)
                if (supported == true) ConfigManager.syncFromProvider(ime)
            }
            val original = chain.proceed() ?: return@intercept null
            if (supported != true || !ConfigManager.isKeyboardShortcutsEnabled() ||
                KeyboardSelectionHook.selected(ime) != KeyboardChoice.PINYIN ||
                (chain.getArg(3) != true && layoutMode != "PINYIN" && layoutMode != "ENGLISH")) original
            else runCatching { layouts.addTo(original) }.onFailure {
                XposedUtils.logError(module, "Letter shortcut popup unavailable; retaining native layout", it)
            }.getOrDefault(original)
        }
        module.deoptimize(factory)
        module.hook(handler).intercept { chain ->
            val key = chain.getArg(0)!!
            val action = label.get(key) as String
            if (action !in KeyboardShortcuts.actions || (kind.get(key) as Enum<*>).name != "CHAR" ||
                (popup.get(key) as? List<*>)?.contains(action) != true)
                return@intercept chain.proceed()
            val ime = service ?: return@intercept chain.proceed()
            // The 18-key adapter commits its own pending decoding before dispatching.
            if (KeyboardSelectionHook.selected(ime) == KeyboardChoice.KEYBOARD18) return@intercept chain.proceed()
            val target = chain.getArg(1)!!
            if (supported != true || KeyboardSelectionHook.selected(ime) != KeyboardChoice.PINYIN ||
                (mode.invoke(target) as? Enum<*>)?.name !in setOf("PINYIN", "ENGLISH") ||
                layoutId.invoke(target) != "qwerty") return@intercept chain.proceed()
            // Consume stale popup actions after disabling as well, never insert their labels.
            if (ConfigManager.isKeyboardShortcutsEnabled()) runCatching {
                val connection = ime.currentInputConnection ?: return@runCatching
                actions.finishInput(target)
                KeyboardShortcutActions.perform(connection, action)
            }.onFailure { XposedUtils.logError(module, "Letter shortcut failed: $action", it) }
            null
        }
        module.deoptimize(handler)
        XposedUtils.log(module, "Independent letter shortcut hooks installed (host 21053)")
    }
}
