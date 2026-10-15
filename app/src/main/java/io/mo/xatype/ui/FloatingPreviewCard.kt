package io.mo.xatype.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import io.mo.xatype.R
import kotlin.math.hypot

/**
 * A card that floats over its parent FrameLayout and can be dragged anywhere
 * inside it. Drags follow the finger, resist past the edges and, on release,
 * keep their speed into a spring that settles inside the bounds. The position
 * is kept as fractions of the free space, so it survives size changes and
 * restarts. Taps still reach the buttons inside.
 */
class FloatingPreviewCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Called when a drag or fling has come to rest. */
    var onSettled: (() -> Unit)? = null
    /** Called when the card is tapped rather than dragged. */
    var onTap: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val margin = resources.getDimension(R.dimen.floating_margin)
    private var prefs: SharedPreferences? = null
    private var fractionX = DEFAULT_FRACTION_X
    private var fractionY = DEFAULT_FRACTION_Y

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    private val springX = SpringAnimator(SpringAnimator.FLING_STIFFNESS, SpringAnimator.FLING_DAMPING) { translationX = it }
    private val springY = SpringAnimator(SpringAnimator.FLING_STIFFNESS, SpringAnimator.FLING_DAMPING) { translationY = it }
    private val pressScale = SpringAnimator(SpringAnimator.MICRO_STIFFNESS, SpringAnimator.MICRO_DAMPING, 0.002f) {
        scaleX = it
        scaleY = it
    }

    private val parentLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) placeFromFractions()
    }

    init {
        springY.onEnd = { if (!springX.isRunning) settle() }
        springX.onEnd = { if (!springY.isRunning) settle() }
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) placeFromFractions()
        }
    }

    /** Restores and from now on remembers the position in [store]. */
    fun bindPosition(store: SharedPreferences) {
        prefs = store
        fractionX = store.getFloat(KEY_X, DEFAULT_FRACTION_X).coerceIn(0f, 1f)
        fractionY = store.getFloat(KEY_Y, DEFAULT_FRACTION_Y).coerceIn(0f, 1f)
        placeFromFractions()
    }

    /** True when the card's centre sits in the lower half of its parent. */
    val inLowerHalf: Boolean
        get() {
            val parentHeight = (parent as? View)?.height ?: return true
            return translationY + height / 2f > parentHeight / 2f
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentLayoutListener)
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        springX.stop()
        springY.stop()
        pressScale.stop()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------- bounds

    private class Range(val min: Float, val max: Float) {
        fun clamp(value: Float) = value.coerceIn(min, max)
        fun fraction(value: Float) = if (max <= min) 0.5f else ((value - min) / (max - min)).coerceIn(0f, 1f)
        fun at(fraction: Float) = min + (max - min) * fraction
    }

    private fun range(parentSize: Int, size: Int): Range {
        val min = margin
        val max = parentSize - size - margin
        return if (max < min) ((parentSize - size) / 2f).let { Range(it, it) } else Range(min, max)
    }

    private fun rangeX(): Range? {
        val p = parent as? View ?: return null
        return if (p.width == 0 || width == 0) null else range(p.width, width)
    }

    private fun rangeY(): Range? {
        val p = parent as? View ?: return null
        return if (p.height == 0 || height == 0) null else range(p.height, height)
    }

    private fun placeFromFractions() {
        if (dragging || springX.isRunning || springY.isRunning) return
        val x = rangeX() ?: return
        val y = rangeY() ?: return
        translationX = x.at(fractionX)
        translationY = y.at(fractionY)
    }

    private fun settle() {
        val x = rangeX() ?: return
        val y = rangeY() ?: return
        fractionX = x.fraction(translationX)
        fractionY = y.fraction(translationY)
        prefs?.edit()?.putFloat(KEY_X, fractionX)?.putFloat(KEY_Y, fractionY)?.apply()
        onSettled?.invoke()
    }

    /** Past an edge the card moves less and less, like a rubber band. */
    private fun resist(value: Float, range: Range, dimension: Int): Float {
        val overshoot = when {
            value < range.min -> value - range.min
            value > range.max -> value - range.max
            else -> return value
        }
        val d = dimension.coerceAtLeast(1).toFloat()
        val eased = (1f - 1f / (kotlin.math.abs(overshoot) * RUBBER_BAND / d + 1f)) * d
        return (if (overshoot < 0) range.min else range.max) + if (overshoot < 0) -eased else eased
    }

    // ------------------------------------------------------------- touch

    private fun track(event: MotionEvent) {
        // Raw coordinates, since moving the card shifts its local ones.
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        (tracker ?: VelocityTracker.obtain().also { tracker = it }).addMovement(copy)
        copy.recycle()
    }

    private fun down(event: MotionEvent) {
        springX.stop()
        springY.stop()
        tracker?.clear()
        track(event)
        downRawX = event.rawX
        downRawY = event.rawY
        startX = translationX
        startY = translationY
        dragging = false
    }

    private fun maybeStartDrag(event: MotionEvent): Boolean {
        if (dragging) return true
        if (hypot(event.rawX - downRawX, event.rawY - downRawY) <= touchSlop) return false
        dragging = true
        // Restart from here so the card does not jump by the slop distance.
        downRawX = event.rawX
        downRawY = event.rawY
        parent?.requestDisallowInterceptTouchEvent(true)
        pressScale.start(scaleX, DRAG_SCALE)
        return true
    }

    private fun move(event: MotionEvent) {
        val p = parent as? View ?: return
        val x = rangeX() ?: return
        val y = rangeY() ?: return
        translationX = resist(startX + event.rawX - downRawX, x, p.width)
        translationY = resist(startY + event.rawY - downRawY, y, p.height)
    }

    private fun release(cancelled: Boolean) {
        val wasDragging = dragging
        dragging = false
        val velocity = tracker?.let {
            it.computeCurrentVelocity(1000)
            it.xVelocity to it.yVelocity
        } ?: (0f to 0f)
        tracker?.recycle()
        tracker = null
        if (!wasDragging) return

        pressScale.start(scaleX, 1f)
        val x = rangeX() ?: return
        val y = rangeY() ?: return
        val (vx, vy) = if (cancelled) 0f to 0f else velocity
        // Aim for where the throw would coast to, then let the spring land there.
        val targetX = x.clamp(translationX + vx * PROJECTION_SECONDS)
        val targetY = y.clamp(translationY + vy * PROJECTION_SECONDS)
        springX.start(translationX, targetX, vx)
        springY.start(translationY, targetY, vy)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(event)
            MotionEvent.ACTION_MOVE -> {
                track(event)
                if (maybeStartDrag(event)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle()
                tracker = null
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(event)
            MotionEvent.ACTION_MOVE -> {
                track(event)
                if (maybeStartDrag(event)) move(event)
            }
            MotionEvent.ACTION_UP -> {
                track(event)
                if (dragging) release(cancelled = false) else {
                    tracker?.recycle()
                    tracker = null
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> release(cancelled = true)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    private companion object {
        const val KEY_X = "floating_preview_x"
        const val KEY_Y = "floating_preview_y"
        const val DEFAULT_FRACTION_X = 0.5f
        const val DEFAULT_FRACTION_Y = 1f
        const val RUBBER_BAND = 0.55f
        const val PROJECTION_SECONDS = 0.18f
        const val DRAG_SCALE = 1.03f
    }
}
