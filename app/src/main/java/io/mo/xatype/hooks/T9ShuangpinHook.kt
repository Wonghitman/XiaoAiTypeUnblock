package io.mo.xatype.hooks

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.T9ShuangpinOverrides
import io.mo.xatype.keyboard.T9ShuangpinScheme
import io.mo.xatype.util.XposedUtils

/** Reuse native nine-key decoding and candidates with an independent opt-in scheme. */
object T9ShuangpinHook {
    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
        val initialize = checkNotNull(HostSymbols.method(HostSymbols.T9_SHUANGPIN_INIT))
        val boolean = checkNotNull(HostSymbols.method(HostSymbols.T9_SHUANGPIN_BOOLEAN))
        val string = checkNotNull(HostSymbols.method(HostSymbols.T9_SHUANGPIN_STRING))
        val engine = abi.type("z8.q")
        val mode = engine.abiMethod(abi, "a")
        require(engine.isAssignableFrom(initialize.declaringClass))
        val overrides = T9ShuangpinOverrides()

        module.hook(boolean).intercept { chain ->
            overrides.booleanValue(chain.getArg(0) as? String) ?: chain.proceed()
        }
        module.deoptimize(boolean)
        module.hook(string).intercept { chain ->
            overrides.stringValue(chain.getArg(0) as? String) ?: chain.proceed()
        }
        module.deoptimize(string)
        module.hook(initialize).intercept { chain ->
            val scheme = runCatching {
                if ((mode.invoke(chain.thisObject) as? Enum<*>)?.name != "T9") null else {
                    ConfigManager.syncFromProvider(chain.getArg(0) as Context)
                    if (ConfigManager.isT9ShuangpinEnabled())
                        T9ShuangpinScheme.resolve(ConfigManager.getT9ShuangpinScheme()) else null
                }
            }.onFailure { XposedUtils.logError(module, "Unable to read nine-key ShuangPin settings", it) }.getOrNull()
            overrides.duringInitialization(if (scheme != null) "T9" else null, scheme) { chain.proceed() }
        }
        module.deoptimize(initialize)
        XposedUtils.log(module, "Independent nine-key ShuangPin hooks installed (native decoder, opt-in)")
    }
}
