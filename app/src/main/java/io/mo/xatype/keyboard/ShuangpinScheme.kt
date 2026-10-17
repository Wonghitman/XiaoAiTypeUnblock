package io.mo.xatype.keyboard

/** Standard key assignments checked against Rime's scheme definitions; see SOURCE-shuangpin.txt. */
enum class ShuangpinScheme(val title: String) {
    XIAOHE("小鹤双拼"), ZIRANMA("自然码"), WEIRUAN("微软双拼"),
    SOUGOU("搜狗双拼"), ZHINENG_ABC("智能 ABC"), PINYIN_JIAJIA("拼音加加"), ZIGUANG("紫光双拼");

    val usesSemicolon: Boolean get() = this in setOf(WEIRUAN, SOUGOU, ZIGUANG)
    private val finals: Map<String, Char> by lazy {
        val common = assignments("a:a e:e i:i o:o u:u v:v ai:l ei:z ao:k ou:b an:j en:f ang:h eng:g er:r " +
            "ia:w ie:x iao:c iu:q ian:m in:n iang:d ing:y iong:s ua:w uo:o uai:y ui:v uan:r un:p uang:d ong:s ue:t ve:t van:r vn:p")
        common + assignments(when (this) {
            XIAOHE -> "ei:w ai:d ao:c ou:z ia:x ua:x iao:n ie:p in:b ing:k uai:k iang:l uang:l un:y vn:y"
            ZIRANMA -> ""
            WEIRUAN, SOUGOU -> "ing:; v:y"
            ZHINENG_ABC -> "ei:q ian:w iu:r iang:t uang:t uan:p van:p ia:d ua:d iao:z in:c uai:c un:n vn:n ue:m ve:m ui:m"
            PINYIN_JIAJIA -> "iu:n ia:b ua:b er:q ing:q uan:c van:c ue:x ve:x uai:x un:z vn:z ong:y iong:y " +
                "iang:h uang:h en:r eng:t ang:g ian:j an:f iao:k ao:d ai:s ei:w ie:m ui:v ou:p in:l"
            ZIGUANG -> "en:w eng:t in:y uai:y ai:p iang:g uang:g ang:s ie:d ian:f ong:h iong:h er:j iu:j ei:k " +
                "uan:l van:l ing:; ou:z ia:x ua:x iao:b ue:n ve:n ui:n un:m vn:m ao:q an:r"
        })
    }

    /** Standard two-key spelling; standalone syllabic interjections retain their literal spelling. */
    fun encodeSyllable(pinyin: String): String? {
        if (pinyin.isEmpty() || pinyin.any { it !in 'a'..'z' }) return null
        if (pinyin in setOf("m", "n", "hm", "ng")) return pinyin
        if (pinyin.first() in "aoe") {
            if (this == XIAOHE || this == ZIRANMA) {
                if (pinyin.length == 1) return pinyin.repeat(2)
                if (pinyin.length == 2) return pinyin
                return finals[pinyin]?.let { "${pinyin.first()}$it" }
            }
            return finals[pinyin]?.let { "o$it" }
        }
        val initial = listOf("zh", "ch", "sh").firstOrNull { pinyin.startsWith(it) } ?: pinyin.take(1)
        val first = when (initial) {
            "zh" -> when (this) { ZHINENG_ABC -> 'a'; ZIGUANG -> 'u'; else -> 'v' }
            "ch" -> when (this) { ZHINENG_ABC -> 'e'; ZIGUANG -> 'a'; PINYIN_JIAJIA -> 'u'; else -> 'i' }
            "sh" -> when (this) { ZHINENG_ABC -> 'v'; ZIGUANG, PINYIN_JIAJIA -> 'i'; else -> 'u' }
            else -> initial.single()
        }
        return finals[pinyin.drop(initial.length)]?.let { "$first$it" }
    }

    companion object {
        fun resolve(value: String?): ShuangpinScheme = entries.firstOrNull { it.name == value } ?: XIAOHE
        private fun assignments(value: String): Map<String, Char> = value.split(' ').filter { it.isNotEmpty() }
            .associate { it.substringBefore(':') to it.substringAfter(':').single() }
    }
}
