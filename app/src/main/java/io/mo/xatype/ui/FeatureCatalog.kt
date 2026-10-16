package io.mo.xatype.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.ui.SettingsKit.Detail

/** A plain on/off hook setting: a one-line [summary] under the title, caveats in an optional [detail]. */
internal class Feature(val key: String, val title: String, val summary: String, val detail: Detail? = null)

/** Every feature's wording, shared by the settings tabs and the guide page. */
internal object FeatureCatalog {

    val purifyGroups = listOf(
        "内容风控" to listOf(
            Feature(ConfigManager.KEY_AI_SAFETY, "AI 表达安全拦截解除", "AI 润色与回复不再被屏蔽"),
            Feature(ConfigManager.KEY_VOICE_MODERATION, "语音转写合规审查解除", "语音输入不再被风控打断"),
            Feature(ConfigManager.KEY_CLOUD_BLACKLIST, "云端黑名单词库下发拦截", "保留完整的云端联想与热词")
        ),
        "系统与机型限制" to listOf(
            Feature(ConfigManager.KEY_OS_VERSION_UNBLOCK, "澎湃 OS4+ 版本限制解除", "旧系统与第三方 ROM 也能使用"),
            Feature(ConfigManager.KEY_AI_ABILITY_UNLOCK, "解除 AI 功能机型限制", "显示被机型限制隐藏的 AI 入口", Detail(
                "放开按机型、区域或配置白名单屏蔽的端侧 AI 能力入口。",
                notes = listOf("部分能力仍需 AI 服务实际支持，入口出现不代表一定可用。")
            ))
        ),
        "剪贴板" to listOf(
            Feature(ConfigManager.KEY_CLIPBOARD_SENSITIVE, "剪贴板敏感标记忽略绕过", "敏感内容也能记录与联想", Detail(
                "被应用标记为敏感的复制内容，也会进入剪贴板快捷记录并参与联想。",
                notes = listOf("这类内容可能包含密码或验证码，请按需开启。")
            )),
            Feature(ConfigManager.KEY_CLIPBOARD_PERMANENT, "剪贴板永久保存", "不限条数、时长与文字长度")
        ),
        "隐私" to listOf(
            Feature(ConfigManager.KEY_TELEMETRY_BLOCK, "遥测与埋点拦截", "关闭小米统计与广告监控上报", Detail(
                "拦截小米 OneTrack 统计与广告监控上报。",
                notes = listOf("不包含讯飞输入内核自身的遥测。")
            ))
        )
    )

    val keyboardShortcuts = Feature(ConfigManager.KEY_KEYBOARD_SHORTCUTS, "按键下滑快捷操作",
        "按住按键下滑，快速全选、复制、粘贴", Detail(
            "按住对应按键向下滑，字母会随距离渐变为功能图标；轻震后松手执行，往回滑可取消。",
            notes = listOf("适用于拼音九键、18 键与拼音/英文 26 键。"),
            extra = ::shortcutTable
        ))

    val keyboardHeight = Feature(ConfigManager.KEY_KEYBOARD_HEIGHT_UNBLOCK, "解除键盘高度上限",
        "可继续向上拉伸，仅支持 0.2.1053")

    val bottomSpacing = Feature(ConfigManager.KEY_BOTTOM_SPACING_ENABLED, "自定义键盘底部间距",
        "调节按键下方的留白")

    val keyboardFeatures get() = listOf(keyboardShortcuts, keyboardHeight, bottomSpacing)

    /** Rows of action, nine-key, 26-key and 18-key triggers. */
    private val shortcutKeys = listOf(
        listOf("全选", "7", "Z", "Z"),
        listOf("复制", "8", "C", "XC"),
        listOf("粘贴", "9", "V", "V"),
        listOf("段首", "4", "B", "BN"),
        listOf("段尾", "6", "M", "M")
    )

    private fun shortcutTable(kit: SettingsKit): View {
        val context = kit.context
        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun line(cells: List<String>, header: Boolean) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            cells.forEachIndexed { index, text ->
                val cell = when {
                    header -> kit.label(text, 12f, R.color.text_secondary).apply { if (index > 0) gravity = Gravity.CENTER }
                    index == 0 -> kit.label(text, 14f, R.color.text_primary)
                    else -> kit.label(text, 14f, R.color.primary, bold = true).apply {
                        gravity = Gravity.CENTER
                        setBackgroundResource(R.drawable.bg_key_chip)
                    }
                }
                addView(cell, LinearLayout.LayoutParams(0, if (header || index == 0) LinearLayout.LayoutParams.WRAP_CONTENT else kit.dp(32), 1f).apply {
                    if (index > 0) marginStart = kit.dimen(R.dimen.space_sm)
                })
            }
        }
        table.addView(line(listOf("操作", "九键", "26 键", "18 键"), header = true))
        shortcutKeys.forEach { cells ->
            table.addView(line(cells, header = false), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = kit.dimen(R.dimen.space_sm) })
        }
        return table
    }
}
