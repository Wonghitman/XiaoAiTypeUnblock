package io.mo.xatype.keyboard18

import io.github.libxposed.api.XposedInterface

/** Host 21053 Compose hooks scoped to one generated letter key at a time. */
class Keyboard18HostAppearance(private val layouts: Keyboard18HostLayout) {
    private val drawing = ThreadLocal<Boolean>()

    fun install(module: XposedInterface, loader: ClassLoader) {
        val key = Class.forName("ka.c", false, loader)
        val composer = Class.forName("s0.p", false, loader)
        val modifier = Class.forName("e1.o", false, loader)
        val alignment = Class.forName("e1.g", false, loader)
        val renderer = Class.forName("z9.w5", false, loader).declaredMethods.single {
            it.name == "a" && it.parameterCount == 30 && it.parameterTypes.first() == key
        }
        val sizing = Class.forName("ma.v", false, loader)
        val typography = sizing.getDeclaredMethod("Q", Int::class.javaPrimitiveType, composer)
        val withHint = sizing.getDeclaredMethod("h0", composer)
        val box = Class.forName("androidx.compose.foundation.layout.a", false, loader)
        val align = box.getDeclaredMethod("a", modifier, alignment)
        val positions = Class.forName("e1.b", false, loader)
        val topCenter = positions.getDeclaredField("b").get(null)
        val topEnd = positions.getDeclaredField("c").get(null)

        module.hook(renderer).intercept { chain ->
            val previous = drawing.get()
            drawing.set(layouts.groupOf(chain.getArg(0)!!) != null)
            try { chain.proceed() } finally {
                if (previous == null) drawing.remove() else drawing.set(previous)
            }
        }
        module.deoptimize(renderer)
        module.hook(typography).intercept { chain ->
            val native = chain.proceed() as Long
            if (drawing.get() == true) Keyboard18Style.scaleTextUnit(native) else native
        }
        module.deoptimize(typography)
        // h0 delegates to Q for keys with hints; keep that call from being inlined.
        module.deoptimize(withHint)
        module.hook(align).intercept { chain ->
            if (drawing.get() == true && chain.getArg(1) === topEnd) {
                chain.proceed(chain.args.toTypedArray().apply { this[1] = topCenter })
            } else chain.proceed()
        }
        module.deoptimize(align)
    }
}
