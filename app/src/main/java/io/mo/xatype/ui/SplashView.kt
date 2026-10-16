package io.mo.xatype.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import io.mo.xatype.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The launch animation, telling what the module does in under two seconds:
 * a keyboard appears with a few keys masked as blocked content, a scan line
 * sweeps across and restores them, then the keys gather into the shield icon
 * and its check mark draws. A tap skips to the exit. The host shows it only
 * while animations are enabled.
 */
internal class SplashView(context: Context) : View(context), Choreographer.FrameCallback {

    /** Fires once when the fade-out begins, so the page underneath can settle in. */
    var onExitStart: (() -> Unit)? = null
    /** Fires after the fade-out; the host removes the view. */
    var onFinished: (() -> Unit)? = null

    private class Key(val label: String, val blocked: Boolean, val row: Int, val col: Int) {
        val rect = RectF()
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val background = context.getColor(R.color.surface_light)
    private val keyFill = context.getColor(R.color.surface_raised)
    private val keyStroke = context.getColor(R.color.card_stroke)
    private val keyText = context.getColor(R.color.text_primary)
    private val blockedFill = context.getColor(R.color.status_inactive_container)
    private val blockedMark = context.getColor(R.color.status_inactive)
    private val cleanFill = context.getColor(R.color.primary_container)
    private val primary = context.getColor(R.color.primary)
    private val onPrimary = context.getColor(R.color.on_primary)
    private val textPrimary = context.getColor(R.color.text_primary)
    private val textSecondary = context.getColor(R.color.text_secondary)

    private val keys = buildList {
        ROWS.forEachIndexed { row, letters ->
            letters.forEachIndexed { col, c -> add(Key(c.toString(), c in BLOCKED, row, col)) }
        }
        add(Key("", false, ROWS.size, 0))
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = textPrimary
    }
    private val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = textSecondary
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanMatrix = Matrix()
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val title = context.getString(R.string.app_name)
    private val tagline = context.getString(R.string.splash_tagline)

    // Layout, computed in onSizeChanged.
    private val keyboard = RectF()
    private var keyRadius = 0f
    private var iconCx = 0f
    private var iconCy = 0f
    private var iconSize = 0f
    private val tilePath = Path()
    private val shieldPath = Path()
    private val checkPath = Path()
    private val checkSegment = Path()
    private val checkMeasure = PathMeasure()
    private var titleY = 0f
    private var taglineY = 0f
    private var scanHalfWidth = 0f

    private var startNanos = 0L
    private var frameMs = 0f
    private var exitAtMs = EXIT_AT_MS
    private var exitNotified = false
    private var running = false

    init {
        contentDescription = title
        isClickable = true
    }

    /** Jumps to the fade-out from wherever the animation is. */
    fun skip() {
        if (frameMs < exitAtMs) exitAtMs = frameMs
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        startNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (startNanos == 0L) startNanos = frameTimeNanos
        frameMs = (frameTimeNanos - startNanos) / 1_000_000f
        val exit = progress(frameMs, exitAtMs, EXIT_MS)
        if (exit > 0f && !exitNotified) {
            exitNotified = true
            onExitStart?.invoke()
        }
        alpha = 1f - easeOut(exit)
        val scale = 1f + EXIT_SCALE * easeOut(exit)
        scaleX = scale
        scaleY = scale
        invalidate()
        if (exit >= 1f) {
            running = false
            onFinished?.invoke()
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        // Consumes every touch so nothing underneath reacts while the splash is up.
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        skip()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = dp(KEY_GAP_DP)
        val width = minOf(w - dp(KEYBOARD_SIDE_MARGIN_DP) * 2, dp(KEYBOARD_MAX_WIDTH_DP))
        val keyW = (width - gap * (COLUMNS - 1)) / COLUMNS
        val keyH = minOf(keyW * KEY_ASPECT, dp(KEY_MAX_HEIGHT_DP))
        val rowGap = dp(ROW_GAP_DP)
        val rowCount = ROWS.size + 1
        val height = keyH * rowCount + rowGap * (rowCount - 1)
        val centerY = h * CENTER_FRACTION
        keyboard.set((w - width) / 2f, centerY - height / 2f, (w + width) / 2f, centerY + height / 2f)
        keyRadius = keyH * KEY_RADIUS_FRACTION
        letterPaint.textSize = keyH * LETTER_FRACTION

        keys.forEach { key ->
            val count = if (key.row < ROWS.size) ROWS[key.row].length else SPACE_COLUMNS
            val offset = (COLUMNS - count) / 2f * (keyW + gap)
            val left = keyboard.left + offset + key.col * (keyW + gap)
            val top = keyboard.top + key.row * (keyH + rowGap)
            val right = if (key.row < ROWS.size) left + keyW else left + count * keyW + (count - 1) * gap
            key.rect.set(left, top, right, top + keyH)
        }

        iconSize = dp(ICON_SIZE_DP)
        iconCx = w / 2f
        iconCy = centerY - dp(ICON_LIFT_DP)
        val iconLeft = iconCx - iconSize / 2f
        val iconTop = iconCy - iconSize / 2f
        superellipse(tilePath, iconCx, iconCy, iconSize / 2f)
        val toIcon = Matrix().apply {
            setScale(iconSize / VIEWPORT, iconSize / VIEWPORT)
            postTranslate(iconLeft, iconTop)
        }
        shieldPath.set(PathParser.createPathFromPathData(SHIELD_PATH))
        shieldPath.transform(toIcon)
        checkPath.set(PathParser.createPathFromPathData(CHECK_PATH))
        checkPath.transform(toIcon)
        checkMeasure.setPath(checkPath, false)
        checkPaint.strokeWidth = CHECK_STROKE / VIEWPORT * iconSize

        val available = w - dp(TEXT_SIDE_MARGIN_DP) * 2
        fitText(titlePaint, title, sp(TITLE_SIZE_SP), available)
        fitText(taglinePaint, tagline, sp(TAGLINE_SIZE_SP), available)
        titleY = iconTop + iconSize + dp(TITLE_GAP_DP) - titlePaint.ascent()
        taglineY = titleY + titlePaint.descent() + dp(TAGLINE_GAP_DP) - taglinePaint.ascent()

        scanHalfWidth = dp(SCAN_HALF_WIDTH_DP)
        val glow = ColorUtils.setAlphaComponent(primary, SCAN_GLOW_ALPHA)
        val clear = ColorUtils.setAlphaComponent(primary, 0)
        scanPaint.shader = LinearGradient(-scanHalfWidth, 0f, scanHalfWidth, 0f,
            intArrayOf(clear, glow, clear), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        val t = frameMs
        val scan = easeInOut(progress(t, SCAN_START_MS, SCAN_MS))
        val scanX = lerp(keyboard.left - scanHalfWidth, keyboard.right + scanHalfWidth, scan)
        val gather = progress(t, GATHER_START_MS, GATHER_MS)

        if (gather < 1f) {
            keys.forEach { drawKey(canvas, it, t, scanX, scan, gather) }
            if (scan > 0f && scan < 1f) drawScan(canvas, scanX, scan)
        }
        drawIcon(canvas, t)
        drawText(canvas, t)
    }

    private fun drawKey(canvas: Canvas, key: Key, t: Float, scanX: Float, scan: Float, gather: Float) {
        val enter = easeOut(progress(t, key.row * ROW_STAGGER_MS + key.col * COLUMN_STAGGER_MS, ENTER_MS))
        if (enter <= 0f) return
        val pull = easeIn(gather)
        val fade = 1f - progress(gather, GATHER_FADE_FROM, 1f - GATHER_FADE_FROM)
        val alpha = enter * fade
        val rect = key.rect
        val cx = lerp(rect.centerX(), iconCx, pull)
        val cy = lerp(rect.centerY(), iconCy, pull) + (1f - enter) * dp(ENTER_RISE_DP)
        val scale = lerp(1f, GATHER_SCALE, pull)

        // How far the scan line has crossed this key, and its glow while passing.
        val cleaned = if (scan >= 1f) 1f else ((scanX - rect.left) / rect.width()).coerceIn(0f, 1f)
        val glow = (1f - abs(scanX - rect.centerX()) / scanHalfWidth).coerceIn(0f, 1f) * (if (scan < 1f) 1f else 0f)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        val halfW = rect.width() / 2f
        val halfH = rect.height() / 2f

        fillPaint.color = if (key.blocked) ColorUtils.blendARGB(blockedFill, cleanFill, cleaned) else keyFill
        fillPaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(-halfW, -halfH, halfW, halfH, keyRadius, keyRadius, fillPaint)
        strokePaint.strokeWidth = dp(1f) + glow * dp(0.5f)
        val stroke = if (key.blocked) ColorUtils.blendARGB(blockedMark, primary, cleaned) else keyStroke
        strokePaint.color = ColorUtils.blendARGB(stroke, primary, glow)
        strokePaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(-halfW, -halfH, halfW, halfH, keyRadius, keyRadius, strokePaint)

        if (key.blocked) {
            // A redaction bar that gives way to the restored letter.
            val barAlpha = alpha * (1f - cleaned)
            if (barAlpha > 0f) {
                fillPaint.color = blockedMark
                fillPaint.alpha = (barAlpha * 255).toInt()
                val barW = halfW * REDACTION_WIDTH
                val barH = halfH * REDACTION_HEIGHT
                canvas.drawRoundRect(-barW, -barH, barW, barH, barH, barH, fillPaint)
            }
            drawLetter(canvas, key.label, primary, alpha * cleaned)
        } else {
            drawLetter(canvas, key.label, keyText, alpha)
        }
        canvas.restore()
    }

    private fun drawLetter(canvas: Canvas, label: String, color: Int, alpha: Float) {
        if (label.isEmpty() || alpha <= 0f) return
        letterPaint.color = color
        letterPaint.alpha = (alpha * 255).toInt()
        canvas.drawText(label, 0f, -(letterPaint.ascent() + letterPaint.descent()) / 2f, letterPaint)
    }

    private fun drawScan(canvas: Canvas, scanX: Float, scan: Float) {
        val presence = sin(scan * PI).toFloat()
        val top = keyboard.top - dp(SCAN_OVERHANG_DP)
        val bottom = keyboard.bottom + dp(SCAN_OVERHANG_DP)
        scanMatrix.setTranslate(scanX, 0f)
        scanPaint.shader.setLocalMatrix(scanMatrix)
        scanPaint.alpha = (presence * 255).toInt()
        canvas.drawRect(scanX - scanHalfWidth, top, scanX + scanHalfWidth, bottom, scanPaint)
        fillPaint.color = primary
        fillPaint.alpha = (presence * 255).toInt()
        val half = dp(SCAN_LINE_DP) / 2f
        canvas.drawRoundRect(scanX - half, top, scanX + half, bottom, half, half, fillPaint)
    }

    private fun drawIcon(canvas: Canvas, t: Float) {
        val since = t - ICON_START_MS
        if (since <= 0f) return
        val alpha = progress(t, ICON_START_MS, ICON_FADE_MS)
        val scale = lerp(ICON_FROM_SCALE, 1f, springStep(since / 1000f))

        // A ring that expands once the check completes: the purified pulse.
        val pulse = progress(t, PULSE_START_MS, PULSE_MS)
        if (pulse > 0f && pulse < 1f) {
            strokePaint.color = primary
            strokePaint.alpha = ((1f - pulse) * PULSE_ALPHA).toInt()
            strokePaint.strokeWidth = dp(PULSE_STROKE_DP) * (1f - pulse)
            canvas.drawCircle(iconCx, iconCy, iconSize / 2f * lerp(PULSE_FROM_SCALE, PULSE_TO_SCALE, easeOut(pulse)), strokePaint)
        }

        canvas.save()
        canvas.scale(scale, scale, iconCx, iconCy)
        fillPaint.color = primary
        fillPaint.alpha = (alpha * 255).toInt()
        canvas.drawPath(tilePath, fillPaint)
        fillPaint.color = onPrimary
        fillPaint.alpha = (alpha * 255).toInt()
        canvas.drawPath(shieldPath, fillPaint)

        val check = easeOut(progress(t, CHECK_START_MS, CHECK_MS))
        if (check > 0f) {
            checkSegment.reset()
            checkMeasure.getSegment(0f, checkMeasure.length * check, checkSegment, true)
            checkPaint.color = primary
            canvas.drawPath(checkSegment, checkPaint)
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, t: Float) {
        val titleIn = easeOut(progress(t, TEXT_START_MS, TEXT_MS))
        if (titleIn > 0f) {
            titlePaint.alpha = (titleIn * 255).toInt()
            canvas.drawText(title, iconCx, titleY + (1f - titleIn) * dp(TEXT_RISE_DP), titlePaint)
        }
        val taglineIn = easeOut(progress(t, TEXT_START_MS + TAGLINE_DELAY_MS, TEXT_MS))
        if (taglineIn > 0f) {
            taglinePaint.alpha = (taglineIn * 255).toInt()
            canvas.drawText(tagline, iconCx, taglineY + (1f - taglineIn) * dp(TEXT_RISE_DP), taglinePaint)
        }
    }

    private companion object {
        val ROWS = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
        /** Keys shown masked, as the input method does with blocked content. */
        val BLOCKED = setOf('E', 'I', 'F', 'K', 'X', 'N')
        const val COLUMNS = 10
        const val SPACE_COLUMNS = 5

        // Geometry, in dp unless noted.
        const val KEYBOARD_MAX_WIDTH_DP = 320f
        const val KEYBOARD_SIDE_MARGIN_DP = 32f
        const val KEY_GAP_DP = 6f
        const val ROW_GAP_DP = 8f
        const val KEY_MAX_HEIGHT_DP = 44f
        const val KEY_ASPECT = 1.3f
        const val KEY_RADIUS_FRACTION = 0.22f
        const val LETTER_FRACTION = 0.4f
        const val REDACTION_WIDTH = 0.56f
        const val REDACTION_HEIGHT = 0.16f
        const val CENTER_FRACTION = 0.44f
        const val ICON_SIZE_DP = 96f
        const val ICON_LIFT_DP = 24f
        const val TITLE_SIZE_SP = 20f
        const val TAGLINE_SIZE_SP = 13f
        const val TITLE_GAP_DP = 28f
        const val TAGLINE_GAP_DP = 8f
        const val TEXT_SIDE_MARGIN_DP = 24f
        const val TEXT_RISE_DP = 8f
        const val ENTER_RISE_DP = 12f
        const val SCAN_HALF_WIDTH_DP = 32f
        const val SCAN_OVERHANG_DP = 10f
        const val SCAN_LINE_DP = 2f
        const val SCAN_GLOW_ALPHA = 0x48
        const val PULSE_STROKE_DP = 3f
        const val PULSE_ALPHA = 0x66
        const val PULSE_FROM_SCALE = 1.1f
        const val PULSE_TO_SCALE = 1.9f

        // The launcher icon (ic_launcher.xml) on its 108-unit viewport; the
        // check runs along the middle of the icon's filled check shape.
        const val VIEWPORT = 108f
        const val SHIELD_PATH = "M54,20 L84,35 L84,55 C84,73.5 71.2,90.3 54,95 C36.8,90.3 24,73.5 24,55 L24,35 Z"
        const val CHECK_PATH = "M38.1,50.9 L48,60.8 L69.9,38.9"
        const val CHECK_STROKE = 6f
        const val SUPERELLIPSE_EXPONENT = 5.0
        const val SUPERELLIPSE_POINTS = 96

        // Timeline, in milliseconds from the first frame.
        const val ROW_STAGGER_MS = 55f
        const val COLUMN_STAGGER_MS = 16f
        const val ENTER_MS = 300f
        const val SCAN_START_MS = 320f
        const val SCAN_MS = 520f
        const val GATHER_START_MS = 880f
        const val GATHER_MS = 340f
        const val GATHER_FADE_FROM = 0.55f
        const val GATHER_SCALE = 0.3f
        const val ICON_START_MS = 1000f
        const val ICON_FADE_MS = 140f
        const val ICON_FROM_SCALE = 0.4f
        const val CHECK_START_MS = 1150f
        const val CHECK_MS = 260f
        const val PULSE_START_MS = 1380f
        const val PULSE_MS = 480f
        const val TEXT_START_MS = 1220f
        const val TAGLINE_DELAY_MS = 80f
        const val TEXT_MS = 360f
        const val EXIT_AT_MS = 1780f
        const val EXIT_MS = 280f
        const val EXIT_SCALE = 0.04f

        fun progress(t: Float, start: Float, duration: Float) = ((t - start) / duration).coerceIn(0f, 1f)
        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
        fun easeOut(f: Float) = 1f - (1f - f).pow(3)
        fun easeIn(f: Float) = f * f * f
        fun easeInOut(f: Float) = if (f < 0.5f) 4f * f * f * f else 1f - (-2f * f + 2f).pow(3) / 2f

        /** Step response of the fling spring token, so the icon settles like the rest of the app. */
        fun springStep(seconds: Float): Float {
            val omega = sqrt(SpringAnimator.FLING_STIFFNESS)
            val zeta = SpringAnimator.FLING_DAMPING
            val damped = omega * sqrt(1f - zeta * zeta)
            val decay = exp(-zeta * omega * seconds)
            return 1f - decay * (cos(damped * seconds) + zeta * omega / damped * sin(damped * seconds))
        }

        /** A smooth-cornered tile, curvature continuous unlike a rounded rect. */
        fun superellipse(path: Path, cx: Float, cy: Float, radius: Float) {
            path.reset()
            val power = 2.0 / SUPERELLIPSE_EXPONENT
            for (i in 0 until SUPERELLIPSE_POINTS) {
                val angle = 2.0 * PI * i / SUPERELLIPSE_POINTS
                val c = cos(angle)
                val s = sin(angle)
                val x = cx + radius * (sign(c) * abs(c).pow(power)).toFloat()
                val y = cy + radius * (sign(s) * abs(s).pow(power)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }

        fun fitText(paint: Paint, text: String, size: Float, available: Float) {
            paint.textSize = size
            val width = paint.measureText(text)
            if (width > available) paint.textSize = size * available / width
        }
    }
}
