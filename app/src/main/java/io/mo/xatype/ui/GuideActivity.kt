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

/** The usage guide: every feature, with a detail sheet where it has caveats. */
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

        fun row(feature: Feature) = feature.detail?.let { kit.detailRow(feature.title, feature.summary, it) }
            ?: kit.staticRow(feature.title, feature.summary)
        FeatureCatalog.purifyGroups.forEachIndexed { index, (title, features) ->
            kit.group(content, title, features.map(::row), first = index == 0)
        }
        kit.group(content, "键盘", FeatureCatalog.keyboardFeatures.map(::row))
        kit.group(content, "外观", listOf(kit.staticRow("键盘外观个性化", "圆角、透明度、液态玻璃与配色")))

        content.addView(kit.label(
            "版本 ${BuildConfig.VERSION_NAME} · 需要 LSPosed v2.1.0+",
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
