package io.mo.xatype.keyboard

import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.shape.ShapeHostBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Decorate the resolved native engine; its decoding, learning and lifecycle stay native. */
class EmojiHostBridge(
    private val loader: ClassLoader,
    private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
) {
    private val checked = ShapeHostBridge(loader, abi)
    val engineType = checked.engineType
    val baseInterface = checked.baseInterface
    private val extended = abi.type("z8.p")
    private val composing = abi.type("z8.s")
    private val source = abi.type("z8.b").enumConstants.single { (it as Enum<*>).name == "SYSTEM" }
    private val candidateConstructor = abi.type("z8.a").getConstructor(String::class.java,
        String::class.java, Int::class.javaPrimitiveType, abi.type("z8.b"), String::class.java)
    private val pairConstructor = abi.type("nc.f").getConstructor(Any::class.java, Any::class.java)
    private val getState = baseInterface.abiMethod(abi, "f")
    private val getMode = baseInterface.abiMethod(abi, "a")
    private val clear = baseInterface.abiMethod(abi, "clear")
    private val page = extended.abiMethod(abi, "m", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val textField = fieldWithValue(candidateConstructor.newInstance(
        "emoji-probe-text", "emoji-probe-note", 1, source, "emoji-probe-display"), "emoji-probe-text")
    private val rawField = fieldWithValue(composing.getConstructor(Int::class.javaPrimitiveType,
        String::class.java, String::class.java, List::class.java).newInstance(
        0, "emoji-probe-raw", "emoji-probe-display", emptyList<Any>()), "emoji-probe-raw")

    private fun fieldWithValue(instance: Any, value: String) = instance.javaClass.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        .single { it.get(instance) == value }

    fun supports(engine: Any): Boolean = extended.isInstance(engine) && !Proxy.isProxyClass(engine.javaClass)

    companion object {
        /** The native engine behind an emoji decoration, so diagnostics report the real class. */
        fun unwrap(engine: Any): Any =
            (if (Proxy.isProxyClass(engine.javaClass)) Proxy.getInvocationHandler(engine) as? EmojiHostBridge.Session else null)
                ?.engine ?: engine
    }

    fun create(engine: Any, dictionary: () -> EmojiCandidates?, enabled: () -> Boolean,
               scheme: () -> ShuangpinScheme? = { null },
               observe: (String) -> Unit = {},
               failed: (Throwable) -> Unit): Any = Proxy.newProxyInstance(loader, arrayOf(extended),
        Session(engine, dictionary, enabled, scheme, observe, failed))

    private inner class Session(
        val engine: Any, val dictionary: () -> EmojiCandidates?, val enabled: () -> Boolean,
        val scheme: () -> ShuangpinScheme?, val observe: (String) -> Unit, val failed: (Throwable) -> Unit
    ) : InvocationHandler {
        private var nativeSize = 0
        private var head = emptyList<Any>()
        private var indices = emptyList<Int?>()
        private var composition = ""
        private var unavailable = false
        private var observations = 0

        private fun trace(message: String) {
            // Bounded metadata only; never record composing text or candidate words.
            if (observations++ < 40) runCatching { observe(message) }
        }

        private fun reset() {
            nativeSize = 0; head = emptyList(); indices = emptyList(); composition = ""
        }

        private fun raw(): String {
            val state = getState.invoke(engine)
            return if (composing.isInstance(state)) rawField.get(state) as String else ""
        }

        // ponytail: serialize one engine's input/page callbacks; use versioned snapshots
        // if asynchronous native pagination later needs to run concurrently.
        @Synchronized
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            try {
                return when (abi.canonical(method)) {
                    "equals" -> proxy === arguments[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "EmojiEngine(resolved ABI)"
                    "s" -> {
                        @Suppress("UNCHECKED_CAST")
                        val original = method.invoke(engine) as List<Any>
                        reset()
                        // Only optional decoration is recoverable. Never retry a native input/commit.
                        try {
                            if (unavailable || !enabled()) return original
                            val table = dictionary() ?: return original
                            val input = raw()
                            val currentScheme = scheme()
                            val mode = (getMode.invoke(engine) as Enum<*>).name
                            val additions = table.additions(input, original.map { textField.get(it) as String },
                                currentScheme, mode == "T9")
                            trace("mode=$mode scheme=${currentScheme?.name ?: "FULL"} inputLength=${input.length} " +
                                "native=${original.size} added=${additions.size}")
                            if (additions.isEmpty()) return original
                            val merged = ArrayList<Any>()
                            val mapping = ArrayList<Int?>()
                            original.forEachIndexed { index, candidate ->
                                merged.add(candidate); mapping.add(index)
                                additions.filter { it.after == index }.forEach { addition ->
                                    merged.add(candidateConstructor.newInstance(addition.text, null, 0, source, addition.text))
                                    mapping.add(null)
                                }
                            }
                            nativeSize = original.size; head = merged; indices = mapping; composition = input
                            merged
                        } catch (failure: Exception) {
                            reset(); unavailable = true; failed(failure); original
                        }
                    }
                    "m" -> {
                        if (head.isEmpty()) return method.invoke(engine, *arguments)
                        val start = arguments[0] as Int
                        val count = arguments[1] as Int
                        if (start < 0 || count <= 0 || start > Int.MAX_VALUE - count) return emptyList<Any>()
                        val prefix = head.drop(start).take(count)
                        if (prefix.size == count) return prefix
                        val nativeStart = maxOf(start, head.size) - (head.size - nativeSize)
                        @Suppress("UNCHECKED_CAST")
                        val rest = page.invoke(engine, nativeStart, count - prefix.size) as List<Any>
                        prefix + rest.take(count - prefix.size)
                    }
                    "b", "n" -> {
                        val index = arguments[0] as Int
                        if (head.isEmpty() || index < 0) return method.invoke(engine, *arguments)
                        val nativeIndex = if (index < head.size) indices[index] else index - (head.size - nativeSize)
                        if (nativeIndex == null) {
                            // A delayed tap must not clear a newer or partially selected composition;
                            // an unreadable state counts as changed rather than escaping into the host.
                            if (runCatching { raw() }.getOrNull() != composition) {
                                trace("selection ignored: composition changed")
                                return if (arguments.size == 1) "" else pairConstructor.newInstance("", false)
                            }
                            val emoji = textField.get(head[index]) as String
                            clear.invoke(engine)
                            reset()
                            trace("emoji selected: native composition cleared, returning commit")
                            if (arguments.size == 1) emoji else pairConstructor.newInstance(emoji, true)
                        } else {
                            val forwarded = arguments.copyOf().mapIndexed { i, arg -> if (i == 0) nativeIndex else arg }.toTypedArray()
                            reset()
                            method.invoke(engine, *forwarded)
                        }
                    }
                    "a", "e", "f", "j", "v", "singleCharFilterSupported" -> method.invoke(engine, *arguments)
                    else -> {
                        reset()
                        method.invoke(engine, *arguments)
                    }
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }
}
