package io.mo.xatype.hooks

import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.keyboard.KeyboardChoice
import io.mo.xatype.provider.ShapeContentProvider
import io.mo.xatype.shape.ShapeDictionary
import io.mo.xatype.shape.ShapeDictionaryCodec
import io.mo.xatype.shape.ShapeEngine
import io.mo.xatype.shape.ShapeHostBridge
import io.mo.xatype.shape.ShapeHostKeyAdapter
import io.mo.xatype.shape.ShapeStore
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in replacement of the PINYIN engine, enabled only after the engine ABI resolves. */
object ShapeInputHook {
    private val reports = Executors.newSingleThreadExecutor()
    private var loaded: Loaded? = null
    private data class Loaded(val settings: Bundle, val dictionary: ShapeDictionary)

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
        // A missing/mismatched class leaves the original factory untouched.
        val bridge = ShapeHostBridge(loader, abi)
        val manager = abi.type("ab.x0")
        val service = abi.type("com.mi.ime.MiInputMethodService")
        val contextField = manager.declaredFields.single { it.type == service }.apply { isAccessible = true }
        val factory = manager.abiMethod(abi, "b", bridge.engineType)
        require(factory.returnType == bridge.baseInterface)
        val keys = ShapeHostKeyAdapter(loader, bridge, abi)
        val keyHandler = abi.type("ab.f1").abiMethod(abi, "c", keys.keyClass, keys.callbacksClass)
        val macros = abi.type("ab.s0").abiMethod(abi, "l", bridge.baseInterface)
        require(keyHandler.returnType == Void.TYPE && macros.returnType == Void.TYPE)

        // Bypass phonetic touch correction only for our own session and supported keys.
        // Other engines and keyboard controls continue through the original handler.
        module.hook(keyHandler).intercept { chain ->
            if (keys.handle(chain.getArg(0)!!, chain.getArg(1)!!)) null else chain.proceed()
        }
        module.deoptimize(keyHandler)

        // Pinyin time/date/calculator expansions must not add or displace shape candidates.
        module.hook(macros).intercept { chain ->
            if (bridge.session(chain.getArg(0)) != null) null else chain.proceed()
        }
        module.deoptimize(macros)

        module.hook(factory).intercept { chain ->
            if (chain.getArg(0) !== bridge.pinyin) chain.proceed() else {
                val context = contextField.get(chain.thisObject) as Context
                if (KeyboardSelectionHook.selected(context) != KeyboardChoice.SHAPE) return@intercept chain.proceed()
                val config = runCatching { load(context) }.onFailure {
                    XposedUtils.logError(module, "Shape dictionary unavailable; keeping original engine", it)
                }.getOrNull()
                if (config == null || !config.settings.getBoolean(ShapeStore.SHOW_IN_PICKER)) chain.proceed()
                else {
                    runCatching {
                        val engine = ShapeEngine(config.dictionary, ShapeStore.rules(config.settings, config.dictionary.maxCodeLength))
                        val firstInput = AtomicBoolean(false)
                        report(context, config.settings, "形码引擎已接入，等待输入验证（${config.dictionary.name}）")
                        bridge.create(engine) {
                            if (firstInput.compareAndSet(false, true)) report(context, config.settings, "已收到形码输入（${config.dictionary.name}）")
                        }
                    }.onFailure { XposedUtils.logError(module, "Shape engine creation failed; keeping original engine", it) }
                        .getOrElse { chain.proceed() }
                }
            }
        }
        module.deoptimize(factory)
        XposedUtils.log(module, "Shape input ABI ready (opt-in, resolved ABI)")
    }

    @Synchronized
    private fun load(context: Context): Loaded? {
        loaded?.let { return it }
        val settings = context.contentResolver.call(ShapeContentProvider.CONTENT_URI, "prepare", null, null) ?: return null
        if (!settings.getBoolean(ShapeStore.SHOW_IN_PICKER)) return null
        val dictionary = checkNotNull(context.contentResolver.openInputStream(ShapeContentProvider.DICTIONARY_URI))
            .use(ShapeDictionaryCodec::read)
        // An import must not pair a new table with settings captured before the import.
        val latest = context.contentResolver.call(ShapeContentProvider.CONTENT_URI, "snapshot", null, null)
        check(latest?.getLong(ShapeStore.REVISION) == settings.getLong(ShapeStore.REVISION)) { "码表已更新，请重启输入法" }
        return Loaded(settings, dictionary).also { loaded = it }
    }

    private fun report(context: Context, settings: Bundle, message: String) {
        val app = context.applicationContext ?: context
        reports.execute {
            runCatching { app.contentResolver.call(ShapeContentProvider.CONTENT_URI, "report", null, Bundle().apply {
                putLong(ShapeStore.REVISION, settings.getLong(ShapeStore.REVISION))
                putString(ShapeStore.STATUS, message)
            }) }
        }
    }

}
