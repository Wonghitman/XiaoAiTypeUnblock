package io.mo.xatype.keyboard

import kotlin.math.abs

/** Distances are in dp. Progress is driven by position, including when the finger moves back. */
class ShortcutSwipeGesture(private val slop: Float = SLOP) {
    enum class Release { NATIVE, ACTION, CANCEL }
    var dragging = false
        private set
    var abandoned = false
        private set
    var progress = 0f
        private set
    var armed = false
        private set
    private var cancelled = false
    private var finished = false

    fun move(dx: Float, dy: Float): Boolean {
        if (finished || cancelled || abandoned) return false
        if (!dx.isFinite() || !dy.isFinite()) { cancel(); return false }
        if (!dragging) {
            if (abs(dx) > slop && abs(dx) > abs(dy) || dy < -slop) {
                abandoned = true
                return false
            }
            if (dy <= slop || dy < abs(dx)) return false
            dragging = true
        }
        progress = ((dy - slop) / (FULL_DISTANCE - slop)).coerceIn(0f, 1f)
        armed = dy >= ACTIVATE_DISTANCE && abs(dx) <= HORIZONTAL_LIMIT
        if (abs(dx) > HORIZONTAL_LIMIT) { progress = 0f; armed = false }
        return true
    }

    fun release(): Release {
        if (finished) return Release.CANCEL
        finished = true
        return if (cancelled) Release.CANCEL else if (!dragging) Release.NATIVE
            else if (armed) Release.ACTION else Release.CANCEL
    }

    fun cancel() { cancelled = true; progress = 0f; armed = false }

    companion object {
        const val SLOP = 6f
        const val ACTIVATE_DISTANCE = 36f
        const val FULL_DISTANCE = 48f
        const val HORIZONTAL_LIMIT = 32f
        const val ICON_SIZE = 24f
        const val ICON_STROKE = 1.7f
    }
}
