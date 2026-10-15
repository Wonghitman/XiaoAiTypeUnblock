package io.mo.xatype.hooks

import android.content.Context
import android.os.Build
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.keyboard.KeyboardPickerBridge
import io.mo.xatype.keyboard18.Keyboard18Store
import io.mo.xatype.provider.Keyboard18ContentProvider
import io.mo.xatype.provider.ShapeContentProvider
import io.mo.xatype.shape.ShapeStore
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Add choices to the host's own picker. The host still owns rendering and switching. */
object KeyboardSelectionHook {
    private const val PREFS = "xatype_keyboard_choice"
    private const val SELECTED = "selected"
    private data class Visibility(val shape: Boolean, val keyboard18: Boolean,
                                  val legacyShape: Boolean, val legacy18: Boolean)
    @Volatile private var visibility: Visibility? = null
    @Volatile private var context: Context? = null
    @Volatile private var ready = false

    @Synchronized
    private fun visibility(ctx: Context): Visibility {
        visibility?.let { return it }
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageInfo("com.xiaomi.type", 0)
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        check(version == 21053L) { "独立键盘选择仅适配小爱0.2.1053" }
        val shape = checkNotNull(ctx.contentResolver.call(ShapeContentProvider.CONTENT_URI, "visibility", null, null))
        val keyboard18 = checkNotNull(ctx.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null))
        return Visibility(shape.getBoolean(ShapeStore.SHOW_IN_PICKER), keyboard18.getBoolean(Keyboard18Store.SHOW_IN_PICKER),
            shape.getBoolean(ShapeStore.ENABLED), keyboard18.getBoolean(Keyboard18Store.ENABLED)).also { visibility = it }
    }

    fun selected(ctx: Context): KeyboardChoice {
        if (!ready) return KeyboardChoice.PINYIN
        context = ctx.applicationContext ?: ctx
        return runCatching {
            val visible = visibility(ctx)
            val sp = ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = sp.getString(SELECTED, null)
            KeyboardChoice.resolve(saved, visible.shape, visible.keyboard18, visible.legacyShape, visible.legacy18).also {
                // Store migration/fallback so a hidden choice cannot unexpectedly reactivate later.
                if (saved != it.id) check(sp.edit().putString(SELECTED, it.id).commit())
            }
        }.getOrDefault(KeyboardChoice.PINYIN)
    }

    fun install(module: XposedInterface, loader: ClassLoader) {
        val picker = KeyboardPickerBridge(loader)
        val callback = Class.forName("bd.b", false, loader)
        val invoke = callback.getMethod("g", Any::class.java)
        val compose = Class.forName("s0.p", false, loader)
        val screen = Class.forName("z9.u7", false, loader).getDeclaredMethod("a0", String::class.java,
            callback, Class.forName("kotlin.jvm.functions.Function0", false, loader), Set::class.java,
            compose, Int::class.javaPrimitiveType)
        val rows = Class.forName("oc.m", false, loader).getDeclaredMethod("T0", Iterable::class.java, Int::class.javaPrimitiveType)
        val hostCallback = Class.forName("gb.u0", false, loader)
        val hostService = Class.forName("com.mi.ime.MiInputMethodService", false, loader)
        val serviceField = hostCallback.declaredFields.single { it.type == hostService }.apply { isAccessible = true }

        // Add mode items at the selector's row grouping boundary. Avoid hooking listOf,
        // which is called throughout ordinary keyboard composition and input handling.
        module.hook(rows).intercept { chain ->
            val ctx = context
            val visible = visibility
            val items = chain.getArg(0) as? List<*>
            if (!ready || ctx == null || visible == null || items == null || items.size !in 1..4) chain.proceed()
            else {
                val extended = picker.extend(items.toTypedArray(), visible.shape, visible.keyboard18)
                if (extended == null) chain.proceed() else chain.proceed(arrayOf(extended, chain.getArg(1)))
            }
        }
        module.deoptimize(rows)
        // ART may have inlined row grouping into this composable before hook installation.
        module.deoptimize(Class.forName("z9.c3", false, loader).getDeclaredMethod("m", Any::class.java, Any::class.java))
        module.hook(screen).intercept { chain ->
            val original = chain.getArg(1)!!
            val previous = if (Proxy.isProxyClass(original.javaClass)) Proxy.getInvocationHandler(original) as? SelectionCallback else null
            val ctx = previous?.context ?: if (hostCallback.isInstance(original)) serviceField.get(original) as Context else null
            if (ctx == null) chain.proceed() else {
                val choice = selected(ctx)
                val visible = visibility
                if (visible == null) chain.proceed() else {
                    val handler = previous ?: SelectionCallback(ctx, original) { target, id -> invoke.invoke(target, id) }
                    val wrapped = if (previous != null) original else Proxy.newProxyInstance(loader, arrayOf(callback), handler)
                    val args = chain.args.toTypedArray()
                    if (args[0] == KeyboardChoice.PINYIN.id) args[0] = choice.id
                    args[1] = wrapped
                    args[3] = (args[3] as? Set<*>)?.toMutableSet()?.apply {
                        if (visible.shape) add(KeyboardChoice.SHAPE.id)
                        if (visible.keyboard18) add(KeyboardChoice.KEYBOARD18.id)
                    }
                    chain.proceed(args)
                }
            }
        }
        module.deoptimize(screen)
        ready = true
        runCatching { installSettings(module, loader, callback) }.onFailure {
            XposedUtils.logError(module, "Native keyboard settings entries unavailable; keyboard panel remains available", it)
        }
        XposedUtils.log(module, "Independent keyboard picker ready (host 21053)")
    }

    /** The native Settings > Keyboard type page uses preferences rather than Compose. */
    private fun installSettings(module: XposedInterface, loader: ClassLoader, callback: Class<*>) {
        val fragment = Class.forName("db.l", false, loader)
        val base = Class.forName("dg.u", false, loader)
        val preference = Class.forName("androidx.preference.Preference", false, loader)
        val single = Class.forName("miuix.preference.SingleChoicePreference", false, loader)
        val group = Class.forName("androidx.preference.PreferenceGroup", false, loader)
        val constructor = single.getConstructor(Context::class.java)
        val find = fragment.getMethod("f", String::class.java)
        val requireContext = fragment.getMethod("requireContext")
        val setKey = preference.getMethod("D", String::class.java)
        val setTitle = preference.getMethod("G", String::class.java)
        val value = single.getDeclaredField("n0").apply { isAccessible = true }
        val add = group.getMethod("L", preference)
        val icon = preference.getDeclaredField("j").apply { isAccessible = true }
        val bind = Class.forName("qh.c", false, loader).getDeclaredMethod("y", base, String::class.java,
            Map::class.java, String::class.java, callback)
        val invoke = callback.getMethod("g", Any::class.java)
        val service = Class.forName("com.mi.ime.MiInputMethodService", false, loader)
        val instance = service.getDeclaredField("instance").apply { isAccessible = true }
        val engineReady = service.getDeclaredMethod("isEngineManagerReady\$app_iflytekFullRelease")
        val engineType = Class.forName("z8.g", false, loader)
        val switch = service.getDeclaredMethod("switchEngine\$app_iflytekFullRelease", engineType)
        module.hook(bind).intercept { chain ->
            val page = chain.getArg(0)!!
            if (!fragment.isInstance(page) || chain.getArg(1) != "keyboard_type_group") chain.proceed() else {
                val ctx = requireContext.invoke(page) as Context
                val choice = selected(ctx)
                val visible = visibility
                val category = find.invoke(page, "keyboard_type_group")
                if (visible == null || category == null) chain.proceed() else {
                    @Suppress("UNCHECKED_CAST")
                    val mapping = LinkedHashMap(chain.getArg(2) as Map<String, String>)
                    val native = find.invoke(page, "kbd_type_pinyin")
                    fun entry(choice: KeyboardChoice, label: String) {
                        if (find.invoke(page, choice.id) == null) {
                            val row = constructor.newInstance(ctx)
                            setKey.invoke(row, choice.id)
                            setTitle.invoke(row, label)
                            value.set(row, choice.id)
                            if (native != null) icon.setInt(row, icon.getInt(native))
                            check(add.invoke(category, row) == true)
                        }
                        mapping[choice.id] = choice.id
                    }
                    if (visible.shape) entry(KeyboardChoice.SHAPE, "虎码/五笔")
                    if (visible.keyboard18) entry(KeyboardChoice.KEYBOARD18, "18键")
                    val original = chain.getArg(4)!!
                    val handler = SelectionCallback(ctx, original, "PINYIN") { target, id ->
                        val result = invoke.invoke(target, id)
                        // Native preference sync compares engine enums, so PINYIN -> PINYIN
                        // would otherwise leave a previous custom proxy alive.
                        runCatching {
                            val running = instance.get(null)
                            if (running != null && engineReady.invoke(running) == true) {
                                val type = engineType.enumConstants.single { (it as Enum<*>).name == id }
                                switch.invoke(running, type)
                            }
                        }.onFailure { XposedUtils.logError(module, "Unable to refresh keyboard from native settings", it) }
                        result
                    }
                    val args = chain.args.toTypedArray()
                    args[2] = mapping
                    if (args[3] == "PINYIN" && choice != KeyboardChoice.PINYIN) args[3] = choice.id
                    args[4] = Proxy.newProxyInstance(loader, arrayOf(callback), handler)
                    chain.proceed(args)
                }
            }
        }
        module.deoptimize(bind)
        module.deoptimize(fragment.getDeclaredMethod("h", String::class.java))
    }

    private class SelectionCallback(val context: Context, val original: Any,
                                    val pinyinId: String = KeyboardChoice.PINYIN.id,
                                    val invoke: (Any, String) -> Any?) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "XatypeKeyboardSelection"
            "g" -> {
                val id = args!![0] as String
                val visible = visibility(context)
                val choice = KeyboardChoice.resolve(id, visible.shape, visible.keyboard18)
                val sp = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val before = sp.getString(SELECTED, null)
                check(sp.edit().putString(SELECTED, choice.id).commit())
                try {
                    // Even PINYIN -> PINYIN recreates the engine through the normal host lifecycle.
                    invoke(original, if (id == KeyboardChoice.SHAPE.id || id == KeyboardChoice.KEYBOARD18.id)
                        pinyinId else id)
                } catch (failure: Throwable) {
                    sp.edit().putString(SELECTED, before).commit()
                    throw failure
                }
            }
            else -> method.invoke(original, *(args ?: emptyArray()))
        }
    }
}
