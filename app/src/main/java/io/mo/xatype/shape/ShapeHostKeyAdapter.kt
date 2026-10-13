package io.mo.xatype.shape

/** Keep raw shape codes out of the host's phonetic touch correction and learning path. */
class ShapeHostKeyAdapter(loader: ClassLoader, private val bridge: ShapeHostBridge) {
    val callbacksClass: Class<*> = Class.forName("ab.b1", false, loader)
    val keyClass: Class<*> = Class.forName("ka.c", false, loader)
    // Use DEX names, not JADX's f9430a display alias.
    private val code = keyClass.getDeclaredField("a").apply { isAccessible = true }
    private val kind = keyClass.getDeclaredField("f").apply { isAccessible = true }
    private val currentEngine = callbacksClass.getMethod("u")
    private val uppercase = callbacksClass.getMethod("o")
    private val commit = callbacksClass.getMethod("K", String::class.java)
    private val refresh = callbacksClass.getMethod("y", Class.forName("z8.r", false, loader))

    init {
        require(code.type == Int::class.javaPrimitiveType && kind.type.isEnum &&
            currentEngine.returnType == bridge.baseInterface &&
            uppercase.returnType == Boolean::class.javaPrimitiveType &&
            commit.returnType == Void.TYPE && refresh.returnType == Void.TYPE)
    }

    /** True only if we handled this key. Native controls and other engines return false. */
    fun handle(key: Any, callbacks: Any): Boolean {
        val proxy = currentEngine.invoke(callbacks) ?: return false
        val engine = bridge.session(proxy) ?: return false
        val ascii = code.getInt(key)
        val keyKind = (kind.get(key) as Enum<*>).name
        val character = when {
            keyKind == "CHAR" && ascii in 97..122 && uppercase.invoke(callbacks) != true -> ascii.toChar()
            keyKind == "CHAR" && engine.code.isNotEmpty() && (ascii in 48..57 || ascii == 59 || ascii == 39) -> ascii.toChar()
            keyKind == "SPACE" && engine.code.isNotEmpty() -> ' '
            keyKind == "BACKSPACE" && engine.code.isNotEmpty() -> '\b'
            else -> return false
        }
        val result = bridge.input(proxy, character)
        if (!result.consumed) return false
        result.commit?.takeIf { it.isNotEmpty() }?.let { commit.invoke(callbacks, it) }
        refresh.invoke(callbacks, bridge.result(result))
        return true
    }
}
