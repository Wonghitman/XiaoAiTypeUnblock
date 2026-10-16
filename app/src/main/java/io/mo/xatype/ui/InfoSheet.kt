package io.mo.xatype.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import io.mo.xatype.R

/** A bottom sheet that explains one setting: what it does, caveats and technical detail. */
internal object InfoSheet {

    fun show(context: Context, title: CharSequence, detail: SettingsKit.Detail) {
        val kit = SettingsKit(context)
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            val pad = kit.dimen(R.dimen.space_xl)
            setPadding(pad, kit.dimen(R.dimen.space_md), pad, kit.dimen(R.dimen.space_lg))
            // Taps on the sheet must not reach the outside-touch dismissal.
            isClickable = true
        }
        sheet.addView(View(context).apply { setBackgroundResource(R.drawable.bg_sheet_handle) },
            LinearLayout.LayoutParams(kit.dp(36), kit.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = kit.dimen(R.dimen.space_lg)
            })
        sheet.addView(kit.label(title, 18f, R.color.text_primary, bold = true))

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(kit.label(detail.about, 15f, R.color.text_primary).apply {
            setLineSpacing(kit.dp(4).toFloat(), 1f)
        }, spaced(kit, R.dimen.space_md))
        detail.extra?.let { body.addView(it(kit), spaced(kit, R.dimen.space_lg)) }
        if (detail.notes.isNotEmpty()) {
            body.addView(heading(kit, "注意"), spaced(kit, R.dimen.space_xl))
            detail.notes.forEach { note ->
                body.addView(bullet(context, kit, note), spaced(kit, R.dimen.space_sm))
            }
        }
        detail.technical?.let {
            body.addView(heading(kit, "技术细节"), spaced(kit, R.dimen.space_xl))
            body.addView(kit.label(it, 13f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_sheet_note)
                val pad = kit.dimen(R.dimen.space_md)
                setPadding(pad, pad, pad, pad)
                setLineSpacing(kit.dp(2).toFloat(), 1f)
                setTextIsSelectable(true)
            }, spaced(kit, R.dimen.space_sm))
        }
        val maxBodyHeight = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        sheet.addView(BoundedScrollView(context, maxBodyHeight).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dialog = Sheet(context, sheet)
        sheet.addView(Button(context).apply {
            text = "知道了"
            textSize = 15f
            isAllCaps = false
            setTextColor(kit.color(R.color.primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_button_outlined)
            stateListAnimator = null
            setOnClickListener { dialog.cancel() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, kit.dp(48)).apply {
            topMargin = kit.dimen(R.dimen.space_xl)
        })
        dialog.show()
    }

    private fun spaced(kit: SettingsKit, topRes: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = kit.dimen(topRes) }

    private fun heading(kit: SettingsKit, text: String) = kit.label(text, 13f, R.color.text_secondary, bold = true)

    private fun bullet(context: Context, kit: SettingsKit, text: CharSequence) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(kit.label("•", 14f, R.color.text_secondary), LinearLayout.LayoutParams(kit.dp(16), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(kit.label(text, 14f, R.color.text_primary).apply {
            setLineSpacing(kit.dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Scrolls only once its content is taller than [maxHeight]. */
    private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    /** Slides [panel] up on open and back down before every dismissal by back, outside tap or button. */
    private class Sheet(context: Context, private val panel: View) : Dialog(context) {
        private val spring = SpringAnimator(SpringAnimator.STANDARD_STIFFNESS, SpringAnimator.STANDARD_DAMPING) {
            panel.translationY = it
        }
        private var leaving = false

        init {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(panel)
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(R.style.Animation_XiaoAiTypeUnblock_Sheet)
            }
            // Start below the screen on the first frame so the panel never flashes in place.
            panel.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    panel.viewTreeObserver.removeOnPreDrawListener(this)
                    panel.translationY = panel.height.toFloat()
                    spring.start(panel.height.toFloat(), 0f)
                    return true
                }
            })
        }

        override fun onStop() {
            spring.stop()
            super.onStop()
        }

        override fun cancel() {
            if (leaving) return
            leaving = true
            spring.onEnd = ::close
            spring.start(panel.translationY, panel.height.toFloat())
        }

        private fun close() {
            spring.onEnd = null
            if (isShowing) dismiss()
        }
    }
}
