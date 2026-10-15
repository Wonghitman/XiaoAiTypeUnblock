package io.mo.xatype.ui

import android.content.Context
import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatButton
import io.mo.xatype.keyboard.ShortcutGlyph
import io.mo.xatype.keyboard.ShortcutSwipeGesture

/** Preview counterpart of the host gesture, sharing distances, cancellation and vector glyphs. */
internal class ShortcutSwipeButton(context: Context) : AppCompatButton(context) {
    var shortcut: String? = null
    var shortcutsEnabled: () -> Boolean = { false }
    var executeShortcut: (String) -> Unit = {}
    private var gesture: ShortcutSwipeGesture? = null
    private var startX = 0f
    private var startY = 0f
    private var haptic = false
    private var heldAction: (() -> Unit)? = null

    fun chooseOnLongPress(action: () -> Unit) {
        setOnLongClickListener {
            if (gesture != null) heldAction = action else action()
            true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gesture = if (shortcut != null && shortcutsEnabled()) ShortcutSwipeGesture() else null
            startX = event.x; startY = event.y; haptic = false
            heldAction = null
        }
        val current = gesture ?: return super.onTouchEvent(event)
        if (event.pointerCount != 1 || !shortcutsEnabled() || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            current.cancel(); invalidate()
            heldAction = null
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) gesture = null
            return super.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            val wasDragging = current.dragging
            val density = resources.displayMetrics.density
            current.move((event.x - startX) / density, (event.y - startY) / density)
            if (current.dragging && !wasDragging) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            if (current.armed && !haptic) {
                haptic = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            invalidate()
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val result = current.release()
                val hold = heldAction
                heldAction = null
                gesture = null
                if (result == ShortcutSwipeGesture.Release.ACTION) shortcut?.let(executeShortcut)
                if (current.dragging) return true
                val handled = super.onTouchEvent(event)
                if (result == ShortcutSwipeGesture.Release.NATIVE) hold?.invoke()
                return handled
            }
        }
        return if (current.dragging) true else super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val progress = gesture?.progress ?: 0f
        if (progress <= 0f) { super.onDraw(canvas); return }
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), ((1f - progress) * 255f).toInt())
        try { super.onDraw(canvas) } finally { canvas.restoreToCount(save) }
        val textLayout = layout
        val line = (textLayout?.lineCount ?: 1) - 1
        val centerY = if (textLayout != null) totalPaddingTop + textLayout.getLineBaseline(line) - textSize * 0.35f else height / 2f
        shortcut?.let { ShortcutGlyph.draw(canvas, it, width / 2f, centerY,
            ShortcutSwipeGesture.ICON_SIZE * resources.displayMetrics.density, currentTextColor, progress) }
    }
}
