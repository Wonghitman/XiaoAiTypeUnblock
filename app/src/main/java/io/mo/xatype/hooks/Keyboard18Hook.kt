package io.mo.xatype.hooks

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.keyboard18.Keyboard18Dictionary
import io.mo.xatype.keyboard18.Keyboard18Engine
import io.mo.xatype.keyboard18.Keyboard18HostBridge
import io.mo.xatype.keyboard18.Keyboard18HostLayout
import io.mo.xatype.keyboard18.Keyboard18Keys
import io.mo.xatype.keyboard18.Keyboard18Store
import io.mo.xatype.provider.Keyboard18ContentProvider
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in 18-key keyboard and independent offline decoder, restricted to host 21053. */
object Keyboard18Hook {
    private val reports = Executors.newSingleThreadExecutor()
    private data class Loaded(val settings: Bundle, val dictionary: Keyboard18Dictionary?)
    @Volatile private var loaded: Loaded? = null
    @Volatile private var service: InputMethodService? = null
    @Volatile private var layoutMode = "PINYIN"
    @Volatile private var chineseReady = false

    fun install(module: XposedInterface, loader: ClassLoader) {
        val bridge = Keyboard18HostBridge(loader)
        val layouts = Keyboard18HostLayout(loader)
        val manager = Class.forName("ab.x0", false, loader)
        val hostService = Class.forName("com.mi.ime.MiInputMethodService", false, loader)
        val contextField = manager.declaredFields.single { it.type == hostService }.apply { isAccessible = true }
        val factory = manager.getDeclaredMethod("b", bridge.engineType)
        val callbackClass = Class.forName("ab.b1", false, loader)
        val keyClass = Class.forName("ka.c", false, loader)
        val keyHandler = Class.forName("ab.f1", false, loader).getDeclaredMethod("c", keyClass, callbackClass)
        val current = callbackClass.getMethod("u")
        val uppercase = callbackClass.getMethod("o")
        val commit = callbackClass.getMethod("K", String::class.java)
        val refresh = callbackClass.getMethod("y", Class.forName("z8.r", false, loader))
        val codeField = keyClass.getDeclaredField("a").apply { isAccessible = true }
        val labelField = keyClass.getDeclaredField("b").apply { isAccessible = true }
        val kindField = keyClass.getDeclaredField("f").apply { isAccessible = true }
        val layoutFactory = Class.forName("ab.p1", false, loader).getDeclaredMethod("e",
            String::class.java, Integer::class.java, String::class.java, Boolean::class.javaPrimitiveType,
            Class.forName("la.a", false, loader))
        val layoutContext = layoutFactory.declaringClass.declaredFields.single { it.type == Context::class.java }
            .apply { isAccessible = true }
        val macros = Class.forName("ab.s0", false, loader).getDeclaredMethod("l", bridge.baseInterface)
        require(factory.returnType == bridge.baseInterface && layoutFactory.returnType == layouts.layout)

        // Both editor-action refresh and mode changes rebuild QWERTY, including SHUANGPIN.
        // Only replace the selected PINYIN/ENGLISH layout, never other QWERTY-based engines.
        val modeChange = layoutFactory.declaringClass.getDeclaredMethod("H", bridge.engineType,
            android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType)
        val editorChange = layoutFactory.declaringClass.getDeclaredMethod("G",
            android.view.inputmethod.EditorInfo::class.java, bridge.engineType)
        module.hook(modeChange).intercept { chain ->
            layoutMode = (chain.getArg(0) as Enum<*>).name
            chain.proceed()
        }
        module.deoptimize(modeChange)
        module.hook(editorChange).intercept { chain ->
            layoutMode = (chain.getArg(1) as Enum<*>).name
            chain.proceed()
        }
        module.deoptimize(editorChange)

        module.hook(keyHandler).intercept { chain ->
            val key = chain.getArg(0)!!
            val callbacks = chain.getArg(1)!!
            val session = bridge.session(current.invoke(callbacks))
            val label = labelField.get(key) as String
            val code = codeField.getInt(key)
            val kind = (kindField.get(key) as Enum<*>).name
            fun deliver(result: io.mo.xatype.keyboard18.Pinyin18Result) {
                result.commit?.takeIf { it.isNotEmpty() }?.let { commit.invoke(callbacks, it) }
                refresh.invoke(callbacks, bridge.result(result))
            }
            fun flush() { session?.engine?.let { engine -> deliver(engine.snapshot(commit = engine.commitAll())) } }
            val enabled = loaded?.dictionary != null
            if (enabled && label in Keyboard18Keys.shortcuts.values) {
                flush()
                shortcut(session?.service ?: service, label)
                null
            } else if (session == null) chain.proceed()
            else if (kind == "BACKSPACE" && session.engine.raw.isNotEmpty()) {
                deliver(session.engine.backspace()); null
            } else if (kind == "SPACE" && session.engine.raw.isNotEmpty()) {
                deliver(session.engine.input(' ')); null
            } else if (kind == "CHAR") {
                val group = Keyboard18Keys.group(code)
                val letter = code.toChar()
                session.onInput()
                if ((group != null && uppercase.invoke(callbacks) == true) || letter in 'A'..'Z') {
                    flush()
                    commit.invoke(callbacks, (group?.first()?.uppercaseChar() ?: letter).toString())
                    null
                } else if (group != null || letter in 'a'..'z' || session.engine.raw.isNotEmpty()) {
                    deliver(session.engine.input(letter)); null
                } else chain.proceed()
            } else {
                if (code < 0 && kind != "BACKSPACE") flush()
                chain.proceed()
            }
        }
        module.deoptimize(keyHandler)
        module.hook(macros).intercept { chain ->
            if (bridge.session(chain.getArg(0)) != null) null else chain.proceed()
        }
        module.deoptimize(macros)

        module.hook(factory).intercept { chain ->
            val context = contextField.get(chain.thisObject) as InputMethodService
            service = context
            if (chain.getArg(0) !== bridge.pinyin) chain.proceed() else {
                val config = runCatching { load(context) }.onFailure {
                    XposedUtils.logError(module, "18-key dictionary unavailable; retaining native input", it)
                }.getOrNull()
                val dictionary = config?.dictionary
                if (dictionary == null) chain.proceed() else runCatching {
                    val sp = context.createDeviceProtectedStorageContext().getSharedPreferences("keyboard18_frequency", Context.MODE_PRIVATE)
                    val engine = Keyboard18Engine(dictionary,
                        frequency = { text -> sp.getInt(text, 0) },
                        learn = { text ->
                            if (sp.contains(text) || sp.all.size < 2048) sp.edit()
                                .putInt(text, (sp.getInt(text, 0) + 1).coerceAtMost(10_000)).apply()
                        })
                    val first = AtomicBoolean(false)
                    report(context, config.settings, "18键离线引擎已接入，等待输入")
                    bridge.create(engine, context) {
                        if (first.compareAndSet(false, true)) report(context, config.settings, "已收到18键输入（未做运行验收）")
                    }
                }.onSuccess { chineseReady = true }
                    .onFailure { chineseReady = false; XposedUtils.logError(module, "18-key engine creation failed", it) }
                    .getOrElse { chain.proceed() }
            }
        }
        module.deoptimize(factory)
        module.hook(layoutFactory).intercept { chain ->
            val original = chain.proceed()!!
            val context = layoutContext.get(chain.thisObject) as Context
            val config = runCatching { load(context) }.getOrNull()
            val english = chain.getArg(3) == true
            if (config?.dictionary == null || (!english && (!chineseReady || layoutMode != "PINYIN"))) original else runCatching {
                layouts.rebuild(original, chain.getArg(3) == true)
            }.onFailure {
                XposedUtils.logError(module, "18-key layout unavailable; retaining native layout", it)
                report(context, config.settings, "18键布局接入失败，已保留原布局")
            }.getOrDefault(original)
        }
        module.deoptimize(layoutFactory)
        XposedUtils.log(module, "18-key prototype hooks installed (opt-in, host 21053)")
    }

    @Synchronized
    private fun load(context: Context): Loaded {
        loaded?.let { return it }
        val settings = context.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null)
            ?: error("无法读取18键设置")
        if (!settings.getBoolean(Keyboard18Store.ENABLED)) return Loaded(settings, null).also { loaded = it }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo("com.xiaomi.type", 0)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (version != 21053L) {
            report(context, settings, "未接入：18键原型仅适配小爱0.2.1053")
            return Loaded(settings, null).also { loaded = it }
        }
        val dictionary = checkNotNull(context.contentResolver.openInputStream(Keyboard18ContentProvider.DICTIONARY_URI))
            .use(Keyboard18Dictionary::read)
        val latest = context.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "snapshot", null, null)
        check(latest?.getLong(Keyboard18Store.REVISION) == settings.getLong(Keyboard18Store.REVISION)) { "设置已更新，请重启输入法" }
        return Loaded(settings, dictionary).also { loaded = it }
    }
    private fun report(context: Context, settings: Bundle, message: String) {
        val app = context.applicationContext ?: context
        reports.execute { runCatching { app.contentResolver.call(Keyboard18ContentProvider.CONTENT_URI, "report", null,
            Bundle().apply { putLong(Keyboard18Store.REVISION, settings.getLong(Keyboard18Store.REVISION))
                putString(Keyboard18Store.STATUS, message) }) } }
    }
    private fun shortcut(service: InputMethodService?, action: String) {
        val connection = service?.currentInputConnection ?: return
        when (action) {
            "全选" -> connection.performContextMenuAction(android.R.id.selectAll)
            "复制" -> connection.performContextMenuAction(android.R.id.copy)
            "粘贴" -> connection.performContextMenuAction(android.R.id.paste)
            "段首", "段尾" -> {
                val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
                if (extracted != null) {
                    val text = extracted.text.toString()
                    val caret = extracted.selectionStart.coerceIn(0, text.length)
                    val position = if (action == "段首") text.lastIndexOf('\n', (caret - 1).coerceAtLeast(-1)) + 1
                        else text.indexOf('\n', caret).let { if (it < 0) text.length else it }
                    connection.setSelection(extracted.startOffset + position, extracted.startOffset + position)
                } else {
                    val key = if (action == "段首") KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                }
            }
        }
    }
}
