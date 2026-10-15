package io.mo.xatype.ui

import android.animation.ValueAnimator
import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A damped spring driven by the frame clock. It starts from the current
 * position and velocity, so a new gesture can stop it anywhere and a release
 * can hand over its speed. With animations turned off it jumps to the target.
 */
internal class SpringAnimator(
    private val stiffness: Float,
    private val dampingRatio: Float,
    /** Distance that counts as arrived, in the animated unit. */
    private val precision: Float = 0.5f,
    private val onUpdate: (Float) -> Unit
) : Choreographer.FrameCallback {

    private var position = 0f
    private var velocity = 0f
    private var target = 0f
    private var lastFrameNanos = 0L
    var isRunning = false
        private set
    var onEnd: (() -> Unit)? = null

    fun start(from: Float, to: Float, initialVelocity: Float = 0f) {
        stop()
        position = from
        target = to
        velocity = initialVelocity
        if (!ValueAnimator.areAnimatorsEnabled() || (abs(from - to) < precision && abs(initialVelocity) < precision * 16f)) {
            onUpdate(to)
            onEnd?.invoke()
            return
        }
        isRunning = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos - FRAME_NANOS
        var remaining = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, MAX_FRAME_SECONDS)
        lastFrameNanos = frameTimeNanos

        val omega = sqrt(stiffness)
        val damping = 2f * dampingRatio * omega
        // Small semi-implicit Euler steps stay stable for stiff springs.
        while (remaining > 0f) {
            val h = minOf(STEP_SECONDS, remaining)
            val acceleration = -stiffness * (position - target) - damping * velocity
            velocity += acceleration * h
            position += velocity * h
            remaining -= h
        }

        if (abs(position - target) < precision && abs(velocity) < precision * 16f) {
            isRunning = false
            onUpdate(target)
            onEnd?.invoke()
            return
        }
        onUpdate(position)
        Choreographer.getInstance().postFrameCallback(this)
    }

    companion object {
        // Tokens: stiffness ≈ (2π / response)², mass 1.
        const val STANDARD_STIFFNESS = 380f
        const val STANDARD_DAMPING = 1f
        const val FLING_STIFFNESS = 320f
        const val FLING_DAMPING = 0.8f
        const val MICRO_STIFFNESS = 1500f
        const val MICRO_DAMPING = 0.6f

        private const val STEP_SECONDS = 0.004f
        private const val MAX_FRAME_SECONDS = 0.064f
        private const val FRAME_NANOS = 16_000_000L
    }
}
