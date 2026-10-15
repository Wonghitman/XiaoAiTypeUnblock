package io.mo.xatype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import io.mo.xatype.R
import kotlin.math.roundToInt

/** Builds the grouped settings rows shared by the main tabs. Every size and color comes from resources. */
internal class SettingsKit(private val context: Context) {

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    fun dimen(res: Int): Int = context.resources.getDimensionPixelSize(res)
    fun color(res: Int): Int = context.getColor(res)

    fun label(text: CharSequence, sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color(colorRes))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun sectionHeader(title: String, first: Boolean = false) = label(title, 13f, R.color.text_secondary, bold = true).apply {
        setPadding(dimen(R.dimen.space_xs), if (first) dimen(R.dimen.space_sm) else dimen(R.dimen.space_xl),
            dimen(R.dimen.space_xs), dimen(R.dimen.space_sm))
    }

    fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        clipToOutline = true
    }

    fun divider() = View(context).apply {
        setBackgroundColor(color(R.color.card_stroke))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dimen(R.dimen.settings_divider_height)).apply {
            marginStart = dimen(R.dimen.settings_row_padding)
            marginEnd = dimen(R.dimen.settings_row_padding)
        }
    }

    /** A titled card of rows separated by hairlines. */
    fun group(parent: LinearLayout, title: String?, rows: List<View>, first: Boolean = false): LinearLayout {
        title?.let { parent.addView(sectionHeader(it, first)) }
        val card = card()
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(divider())
            card.addView(row)
        }
        parent.addView(card)
        return card
    }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        val padding = dimen(R.dimen.settings_row_padding)
        setPadding(padding, dimen(R.dimen.space_md), padding, dimen(R.dimen.space_md))
        isClickable = true
        isFocusable = true
        foreground = selectableBackground()
    }

    fun selectableBackground() = TypedValue().let {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        context.getDrawable(it.resourceId)
    }

    fun titleBlock(title: CharSequence, subtitle: CharSequence?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 15f, R.color.text_primary, bold = true))
        if (!subtitle.isNullOrEmpty()) {
            addView(label(subtitle, 13f, R.color.text_secondary).apply {
                setPadding(0, dp(2), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
    }

    fun iconTile(iconRes: Int) = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(color(R.color.primary))
        setBackgroundResource(R.drawable.bg_icon_tile)
        val pad = dp(9)
        setPadding(pad, pad, pad, pad)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun addLeading(row: LinearLayout, iconRes: Int?) {
        iconRes ?: return
        val size = dimen(R.dimen.icon_tile)
        row.addView(iconTile(iconRes), LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dimen(R.dimen.space_md)
        })
    }

    class SwitchRow(val view: LinearLayout, val switch: SwitchCompat)

    /** The whole row toggles; [onChange] fires on every change after creation. */
    fun switchRow(
        title: CharSequence,
        subtitle: CharSequence?,
        checked: Boolean,
        iconRes: Int? = null,
        onChange: (Boolean) -> Unit
    ): SwitchRow {
        val row = row()
        addLeading(row, iconRes)
        row.addView(titleBlock(title, subtitle), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val switch = SwitchCompat(context).apply {
            isChecked = checked
            setPadding(dimen(R.dimen.space_md), 0, 0, 0)
            contentDescription = title
            setOnCheckedChangeListener { view, value ->
                confirmHaptic(view)
                onChange(value)
            }
        }
        row.addView(switch)
        row.setOnClickListener { switch.toggle() }
        return SwitchRow(row, switch)
    }

    fun navRow(title: CharSequence, subtitle: CharSequence?, iconRes: Int?, onClick: () -> Unit): LinearLayout {
        val row = row()
        addLeading(row, iconRes)
        row.addView(titleBlock(title, subtitle), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_shape_chevron)
            imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dimen(R.dimen.space_sm) })
        row.setOnClickListener { onClick() }
        return row
    }

    fun seekBar(max: Int, value: Int, description: String) = SeekBar(context).apply {
        this.max = max
        progress = value
        contentDescription = description
        progressTintList = ColorStateList.valueOf(color(R.color.primary))
        thumbTintList = ColorStateList.valueOf(color(R.color.primary))
    }

    companion object {
        fun confirmHaptic(view: View) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
        }

        fun tickHaptic(view: View) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE
                else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }
}
