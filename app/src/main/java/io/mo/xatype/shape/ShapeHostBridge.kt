package io.mo.xatype.shape

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** The 21053 ABI only. Resolve and validate everything before replacing a host engine. */
class ShapeHostBridge(private val loader: ClassLoader) {
    private fun type(name: String) = Class.forName(name, false, loader)
    val engineType = type("z8.g")
    val baseInterface = type("z8.q")
    private val extendedInterface = type("z8.p")
    private val resultType = type("z8.r")
    private val stateType = type("z8.u")
    private val sourceType = type("z8.b")
    private val pairType = type("nc.f")
    private val intType = Int::class.javaPrimitiveType!!
    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val voidType = Void.TYPE
    private val stringType = String::class.java
    private val listType = List::class.java
    val pinyin: Any = engineType.enumConstants.single { (it as Enum<*>).name == "PINYIN" }
    private val systemSource: Any = sourceType.enumConstants.single { (it as Enum<*>).name == "SYSTEM" }
    private val idle = type("z8.t").let { idleClass ->
        idleClass.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == idleClass }
            .apply { isAccessible = true }.get(null)
    }
    private val resultConstructor = resultType.getConstructor(booleanType, stringType, listType,
        stringType, intType, listType, stringType)
    private val candidateConstructor = type("z8.a").getConstructor(stringType, stringType, intType, sourceType, stringType)
    private val composingConstructor = type("z8.s").getConstructor(intType, stringType, stringType, listType)
    private val pairConstructor = pairType.getConstructor(Any::class.java, Any::class.java)

    init {
        require(baseInterface.isInterface && extendedInterface.isInterface &&
            baseInterface.isAssignableFrom(extendedInterface) && stateType.isInstance(idle))
        val expected = linkedMapOf(
            "a" to (engineType to emptyArray()), "b" to (stringType to arrayOf(intType)),
            "clear" to (voidType to emptyArray()), "d" to (resultType to arrayOf(stringType)),
            "destroy" to (voidType to emptyArray()), "e" to (booleanType to emptyArray()),
            "f" to (stateType to emptyArray()), "g" to (resultType to arrayOf(listType)),
            "i" to (resultType to arrayOf(intType)), "j" to (stringType to emptyArray()),
            "k" to (resultType to emptyArray()), "o" to (resultType to arrayOf(stringType)),
            "q" to (voidType to arrayOf(type("com.mi.ime.MiInputMethodService"), type("z8.e"))),
            "s" to (listType to emptyArray()), "t" to (voidType to emptyArray()),
            "u" to (voidType to arrayOf(type("y8.b"))), "c" to (resultType to emptyArray()),
            "h" to (resultType to arrayOf(Char::class.javaPrimitiveType!!)),
            "l" to (voidType to arrayOf(intType)), "m" to (listType to arrayOf(intType, intType)),
            "n" to (pairType to arrayOf(intType, stringType)), "p" to (voidType to arrayOf(intType)),
            "r" to (resultType to arrayOf(intType)), "v" to (listType to emptyArray())
        )
        require(extendedInterface.methods.size == expected.size) { "形码接口数量不匹配" }
        expected.forEach { (name, signature) ->
            require(extendedInterface.getMethod(name, *signature.second).returnType == signature.first) {
                "形码接口签名不匹配：$name"
            }
        }
        // Exercise constructors before the native factory is bypassed.
        candidate(ShapeEntry("测试", "a"))
        result(ShapeResult(true, "", emptyList()))
        composingConstructor.newInstance(8, "a", "a", emptyList<Any>())
        pairConstructor.newInstance("", false)
    }

    private fun candidate(entry: ShapeEntry): Any = candidateConstructor.newInstance(
        entry.text, entry.code, entry.weight.coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt(), systemSource, entry.text)

    fun result(value: ShapeResult): Any = resultConstructor.newInstance(value.consumed, value.code,
        value.candidates.take(PAGE_SIZE).map(::candidate), value.commit, 0, emptyList<Any>(), value.code)

    fun create(engine: ShapeEngine, onInput: () -> Unit = {}): Any = Proxy.newProxyInstance(
        loader, arrayOf(extendedInterface), Session(engine, onInput))

    fun session(proxy: Any?): ShapeEngine? = if (proxy != null && Proxy.isProxyClass(proxy.javaClass))
        (Proxy.getInvocationHandler(proxy) as? Session)?.engine else null

    fun input(proxy: Any, key: Char): ShapeResult {
        val handler = Proxy.getInvocationHandler(proxy) as Session
        handler.onInput()
        return handler.engine.input(key)
    }

    private inner class Session(val engine: ShapeEngine, val onInput: () -> Unit) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val arg = args.orEmpty()
            return when (method.name) {
                "equals" -> proxy === arg[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ShapeEngine(21053)"
                "a" -> pinyin
                "b" -> engine.select(arg[0] as Int).orEmpty()
                "clear", "destroy", "t" -> { engine.clear(); null }
                "d" -> result(engine.replaceCode(arg[0] as String))
                "e" -> false // No prediction or resurrection of previously committed codes.
                "f" -> if (engine.code.isEmpty()) idle else
                    composingConstructor.newInstance(8, engine.code, engine.code, emptyList<Any>())
                "g" -> result(engine.snapshot(false))
                "i" -> { onInput(); result(engine.input((arg[0] as Int).toChar())) }
                "j" -> "" // Host's T9-only buffer.
                "k" -> result(engine.backspace())
                "o" -> { onInput(); result(engine.inputText(arg[0] as String)) }
                "q", "u", "l", "p" -> null
                "s" -> engine.candidates.take(PAGE_SIZE).map(::candidate)
                "c" -> result(engine.input('\''))
                "h" -> { onInput(); result(engine.input(arg[0] as Char)) }
                "m" -> {
                    val start = arg[0] as Int
                    val count = (arg[1] as Int).coerceIn(0, 60)
                    if (start < 0 || start >= engine.candidates.size) emptyList<Any>()
                    else engine.candidates.subList(start, start + minOf(count, engine.candidates.size - start)).map(::candidate)
                }
                "n" -> {
                    val selected = engine.select(arg[0] as Int)
                    pairConstructor.newInstance(selected.orEmpty(), selected != null)
                }
                "r" -> result(engine.snapshot(false))
                "v" -> emptyList<Any>()
                else -> error("Unexpected host method: ${method.name}")
            }
        }
    }

    companion object { const val PAGE_SIZE = 20 }
}
