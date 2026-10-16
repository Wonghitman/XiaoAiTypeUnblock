package io.mo.xatype.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import io.mo.xatype.BuildConfig
import io.mo.xatype.R

/** The usage guide: page map, every feature with its detail sheet, and how changes apply. */
class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kit = SettingsKit(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.color(R.color.background_light))
        }
        root.addView(Toolbar(this).apply {
            title = "使用说明"
            setTitleTextColor(kit.color(R.color.text_primary))
            setBackgroundColor(kit.color(R.color.surface_light))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(kit.color(R.color.text_primary))
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, actionBarHeight()))
        root.addView(View(this).apply { setBackgroundColor(kit.color(R.color.card_stroke)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, kit.dimen(R.dimen.settings_divider_height)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = kit.dimen(R.dimen.space_lg)
            setPadding(side, kit.dimen(R.dimen.space_sm), side, kit.dimen(R.dimen.space_xl))
        }
        root.addView(NestedScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        kit.group(content, "页面分区", listOf(
            kit.staticRow("概览", "运行状态、重启输入法与模块设置", R.drawable.ic_nav_home),
            kit.staticRow("净化", "内容风控、系统限制、剪贴板与隐私", R.drawable.ic_nav_shield),
            kit.staticRow("键盘", "快捷操作、尺寸布局与实验输入方案", R.drawable.ic_nav_keyboard),
            kit.staticRow("外观", "圆角、背景、文字、键帽与卡片颜色", R.drawable.ic_nav_palette)
        ), first = true)

        FeatureCatalog.purifyGroups.forEach { (title, features) ->
            kit.group(content, title, features.map { kit.detailRow(it.title, it.summary, it.detail) })
        }
        kit.group(content, "键盘", FeatureCatalog.keyboardFeatures.map { kit.detailRow(it.title, it.summary, it.detail) })
        kit.group(content, "外观", listOf(
            kit.detailRow("键盘外观个性化", "圆角、透明度、液态玻璃与配色", FeatureCatalog.appearance)
        ))

        content.addView(kit.sectionHeader("生效方式"))
        content.addView(kit.textCard("修改设置后，底部会出现提示条。点「立即重启」通过 Root 重启输入法即可生效。"))

        content.addView(kit.label(
            "版本 ${BuildConfig.VERSION_NAME}\n基于 libxposed API 102 (Modern Xposed 规范)\n支持 LSPosed v2.1.0+ 及模块自动热重载",
            12f, R.color.text_secondary
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(kit.dp(3).toFloat(), 1f)
            setPadding(0, kit.dimen(R.dimen.space_xl), 0, kit.dimen(R.dimen.space_sm))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun actionBarHeight(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, value, true)
        return android.util.TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
    }
}
