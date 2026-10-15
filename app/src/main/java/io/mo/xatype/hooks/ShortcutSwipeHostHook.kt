package io.mo.xatype.hooks

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.keyboard.ShortcutSwipeGesture
import io.mo.xatype.keyboard.ShortcutSwipeHostBridge
import io.mo.xatype.util.XposedUtils
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.min

/** Bind to actual Compose key coordinates; cancel native input only once a downward drag wins. */
class ShortcutSwipeHostHook(private val enabled: () -> Boolean, private val keyboard18: () -> Boolean) {
    private class Key(val owner: WeakReference<View>, var source: Any, val action: String,
                      var callback: Any, val state: Any, val is18: Boolean) {
        var coordinates: WeakReference<Any>? = null
        var originalModifier: Any? = null
        var decoratedModifier: Any? = null
        var width = Float.MAX_VALUE
        var blockNative = false
    }
    private class Touch(val key: Key, val pointer: Int, val x: Float, val y: Float, val density: Float,
                        val gesture: ShortcutSwipeGesture = ShortcutSwipeGesture()) {
        var nativeCancelled = false
        var haptic = false
    }
    private val keys = WeakHashMap<View, MutableMap<String, Key>>()
    private val rendering = ThreadLocal<Key?>()
    private var touch: Touch? = null
    private var drawFailureReported = false

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_SWIPE)
        val bridge = ShortcutSwipeHostBridge(loader, abi)
        val renderer = abi.namedMethods(abi.type("z9.w5"), "a").single {
            it.parameterCount == 30 && it.parameterTypes.first() == bridge.key
        }
        val text = abi.namedMethods(abi.type("p0.n"), "a").single {
            it.parameterCount == 19 && it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == bridge.modifier
        }
        val dispatch = abi.type("d2.w").abiMethod(abi, "dispatchTouchEvent", MotionEvent::class.java)
        module.hook(renderer).intercept { chain ->
            if (drawFailureReported || !enabled()) return@intercept chain.proceed()
            val source = chain.getArg(0)!!
            val is18 = keyboard18()
            val action = bridge.action(source, is18) ?: return@intercept chain.proceed()
            val owner = bridge.view(chain.getArg(26)!!)
            val id = "${is18}:${source.javaClass.abiField(abi, "a").getInt(source)}:${bridge.label(source)}"
            val key = keys.getOrPut(owner) { LinkedHashMap() }.getOrPut(id) {
                Key(WeakReference(owner), source, action, bridge.unwrap(chain.getArg(10)!!), bridge.progressState(), is18)
            }
            key.source = source
            key.callback = bridge.unwrap(chain.getArg(10)!!)
            val args = chain.args.toTypedArray()
            if (args[2] !== key.decoratedModifier && args[2] !== key.originalModifier) {
                key.originalModifier = args[2]
                key.decoratedModifier = bridge.positioned(args[2]!!) {
                    key.coordinates = WeakReference(it)
                    key.width = bridge.bounds(it)?.width() ?: Float.MAX_VALUE
                }
            }
            args[2] = key.decoratedModifier ?: args[2]
            args[10] = bridge.guarded(key.callback) { key.blockNative }
            args[27] = (args[27] as Int) and 0x380.inv() // Re-evaluate the injected modifier.
            args[28] = (args[28] as Int) and 0xe.inv()
            val previous = rendering.get()
            rendering.set(key)
            try { chain.proceed(args) } finally {
                if (previous == null) rendering.remove() else rendering.set(previous)
            }
        }
        module.deoptimize(renderer)
        module.hook(text).intercept { chain ->
            val key = rendering.get() ?: return@intercept chain.proceed()
            val label = (chain.getArg(0) as String).filterNot(Char::isWhitespace)
            if (!label.equals(bridge.label(key.source).filterNot(Char::isWhitespace), ignoreCase = true))
                return@intercept chain.proceed()
            val args = chain.args.toTypedArray()
            val color = bridge.argb(args[2] as Long)
            args[1] = bridge.drawing(args[1]!!) { scope ->
                if (drawFailureReported) { bridge.drawContent(scope); return@drawing }
                try {
                    val density = key.owner.get()?.resources?.displayMetrics?.density ?: 1f
                    bridge.draw(scope, key.state, key.action, color, min(ShortcutSwipeGesture.ICON_SIZE * density, key.width * 0.65f))
                } catch (failure: Throwable) {
                    if (!drawFailureReported) {
                        drawFailureReported = true
                        XposedUtils.logError(module, "Swipe shortcut drawing unavailable", failure)
                    }
                    bridge.drawContent(scope)
                }
            }
            args[16] = (args[16] as Int) and 0x70.inv()
            chain.proceed(args)
        }
        module.deoptimize(text)
        module.hook(dispatch).intercept { chain ->
            val view = chain.thisObject as View
            val event = chain.getArg(0) as MotionEvent
            fun reset() {
                touch?.let { current ->
                    bridge.progress(current.key.state, 0f)
                    // Let pending native cancellation callbacks drain before accepting a fresh callback.
                    current.key.owner.get()?.post { current.key.blockNative = false }
                }
                touch = null
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                reset()
                if (!drawFailureReported && enabled() && event.pointerCount == 1) {
                    val key = keys[view]?.values?.lastOrNull { candidate ->
                        candidate.is18 == keyboard18() && candidate.coordinates?.get()?.let {
                            bridge.bounds(it)?.contains(event.rawX, event.rawY)
                        } == true
                    }
                    if (key != null) {
                        key.blockNative = false
                        touch = Touch(key, event.getPointerId(0), event.rawX, event.rawY,
                            view.resources.displayMetrics.density)
                    }
                }
                return@intercept chain.proceed()
            }
            val current = touch?.takeIf { it.key.owner.get() === view } ?: return@intercept chain.proceed()
            val nativeWasCancelled = current.nativeCancelled
            if (drawFailureReported || !enabled() || current.key.is18 != keyboard18() || event.pointerCount != 1 ||
                event.actionMasked == MotionEvent.ACTION_CANCEL || event.findPointerIndex(current.pointer) < 0) {
                current.gesture.cancel()
                bridge.progress(current.key.state, 0f)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) reset()
                return@intercept if (nativeWasCancelled) true else chain.proceed()
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                current.gesture.move((event.rawX - current.x) / current.density, (event.rawY - current.y) / current.density)
                if (current.gesture.dragging && !current.nativeCancelled) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    current.nativeCancelled = true
                    current.key.blockNative = true
                    try { chain.proceed(arrayOf(cancel)) } finally { cancel.recycle() }
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                bridge.progress(current.key.state, current.gesture.progress)
                if (current.gesture.armed && !current.haptic) {
                    current.haptic = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val result = current.gesture.release()
                    val cancelled = current.nativeCancelled
                    reset()
                    if (result == ShortcutSwipeGesture.Release.ACTION) runCatching {
                        bridge.dispatch(current.key.callback, bridge.actionKey(current.key.source, current.key.action))
                    }.onFailure { XposedUtils.logError(module, "Swipe shortcut failed: ${current.key.action}", it) }
                    return@intercept if (cancelled) true else chain.proceed()
                }
            }
            if (current.nativeCancelled) true else chain.proceed()
        }
        module.deoptimize(dispatch)
    }
}
