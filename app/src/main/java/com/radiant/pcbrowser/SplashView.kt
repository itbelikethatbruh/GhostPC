package com.radiant.pcbrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Launch animation: a spectral line powers on like a CRT, traces the monitor-and-controller mark,
 * the wordmark settles in, and a hairline tracks the first page load. It dissolves into the game
 * once the page is mostly loaded (or right away on any tap / controller press).
 */
class SplashView(context: Context, private val onFinished: () -> Unit) : View(context) {

    private val density = resources.displayMetrics.density
    private var startTime = 0L
    private var progress = 0f
    private var skipRequested = false
    private var exiting = false
    private val motion = ValueAnimator.areAnimatorsEnabled()

    // Mark geometry, in the 108-unit launcher icon space.
    private val monitor = Path().apply {
        addRoundRect(RectF(40f, 27f, 68f, 40f), 2f, 2f, Path.Direction.CW)
    }
    private val stand = Path().apply {
        moveTo(54f, 40f); lineTo(54f, 44f)
        moveTo(49f, 44.5f); lineTo(59f, 44.5f)
    }
    private val body = Path().apply {
        moveTo(38f, 47f); lineTo(70f, 47f)
        cubicTo(77f, 47f, 81.5f, 52f, 83.5f, 61f)
        lineTo(85.5f, 71f)
        cubicTo(86.5f, 77f, 81f, 80.5f, 77f, 76.5f)
        lineTo(70.5f, 70f); lineTo(37.5f, 70f); lineTo(31f, 76.5f)
        cubicTo(27f, 80.5f, 21.5f, 77f, 22.5f, 71f)
        lineTo(24.5f, 61f)
        cubicTo(26.5f, 52f, 31f, 47f, 38f, 47f)
        close()
    }
    private val details = Path().apply {
        moveTo(39.5f, 54.5f); lineTo(39.5f, 62.5f)
        moveTo(35.5f, 58.5f); lineTo(43.5f, 58.5f)
        addCircle(68f, 54f, 2.3f, Path.Direction.CW)
        addCircle(73.5f, 59f, 2.3f, Path.Direction.CW)
        addCircle(62.5f, 59f, 2.3f, Path.Direction.CW)
        addCircle(68f, 64f, 2.3f, Path.Direction.CW)
    }
    private val traced = listOf(monitor, stand, body)
    private val measures = traced.map { PathMeasure(it, false) }
    private val segment = Path()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bg = Paint()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        typeface = Ui.DISPLAY
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    private val tagline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT_DIM
        typeface = Ui.LABEL
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(Ui.NIGHT)
    }

    fun start() {
        startTime = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** Page load progress, 0..1. */
    fun setProgress(value: Float) {
        progress = max(progress, value.coerceIn(0f, 1f))
    }

    fun requestSkip() {
        skipRequested = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The mark spans x 22..86 and y 27..80 in icon units; it takes about a third of the height.
        scale = min(h * 0.34f / 53f, w * 0.30f / 64f)
        originX = w / 2f - 54f * scale
        originY = h * 0.40f - 53.5f * scale
        bg.shader = RadialGradient(w / 2f, h * 0.42f, max(w, h) * 0.6f,
            intArrayOf(0xFF221B38.toInt(), Ui.NIGHT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        val left = originX + 22f * scale
        val right = originX + 86f * scale
        val gradient = LinearGradient(left, 0f, right, 0f, Ui.SPECTRUM, null, Shader.TileMode.CLAMP)
        stroke.shader = gradient
        glow.shader = LinearGradient(left, 0f, right, 0f, Ui.SPECTRUM, null, Shader.TileMode.CLAMP)
        fill.shader = LinearGradient(left, originY + 27f * scale, right, originY + 80f * scale, Ui.SPECTRUM, null, Shader.TileMode.CLAMP)
        bar.shader = LinearGradient(w * 0.36f, 0f, w * 0.64f, 0f, Ui.SPECTRUM, null, Shader.TileMode.CLAMP)
        title.textSize = h * 0.085f
        tagline.textSize = max(12f * density, h * 0.032f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (startTime == 0L) start()
        val elapsed = (SystemClock.uptimeMillis() - startTime).toFloat()
        // With system animations off, draw the finished frame and only wait for the page.
        val t = if (motion) elapsed else 5_000f
        canvas.drawRect(0f, 0f, w, h, bg)

        val cy = originY + 53.5f * scale
        // 1. Power-on line
        val lineIn = ease(range(t, 0f, 380f))
        val lineOut = range(t, 420f, 900f)
        if (lineIn > 0f && lineOut < 1f) {
            val half = w * 0.26f * lineIn * (1f - 0.7f * ease(lineOut))
            val a = ((1f - lineOut) * 255).toInt()
            glow.alpha = a / 2
            glow.strokeWidth = 4f * density
            stroke.alpha = a
            stroke.strokeWidth = 2f * density
            canvas.drawLine(w / 2f - half, cy, w / 2f + half, cy, glow)
            canvas.drawLine(w / 2f - half, cy, w / 2f + half, cy, stroke)
        }

        // 2. Trace the mark
        val trace = ease(range(t, 300f, 1150f))
        if (trace > 0f) {
            canvas.save()
            canvas.translate(originX, originY)
            canvas.scale(scale, scale)
            val fillIn = ease(range(t, 950f, 1450f))
            if (fillIn > 0f) {
                fill.alpha = (fillIn * 60).toInt()
                canvas.drawPath(body, fill)
                canvas.drawPath(monitor, fill)
            }
            stroke.alpha = 255
            stroke.strokeWidth = 2.2f * density / scale
            glow.alpha = 140
            glow.strokeWidth = 5f * density / scale
            for (i in traced.indices) {
                segment.reset()
                val m = measures[i]
                m.getSegment(0f, m.length * trace, segment, true)
                canvas.drawPath(segment, glow)
                canvas.drawPath(segment, stroke)
            }
            if (fillIn > 0f) {
                stroke.alpha = (fillIn * 255).toInt()
                canvas.drawPath(details, stroke)
            }
            canvas.restore()
        }

        // 3. Wordmark
        val word = ease(range(t, 1050f, 1500f))
        if (word > 0f) {
            title.alpha = (word * 255).toInt()
            val ty = originY + 80f * scale + title.textSize * 1.25f + (1f - word) * 10f * density
            canvas.drawText("Radiant", w / 2f, ty, title)
            val tag = ease(range(t, 1250f, 1650f))
            tagline.alpha = (tag * 255).toInt()
            canvas.drawText("PC game browser", w / 2f, ty + tagline.textSize * 1.9f, tagline)
        }

        // 4. Load hairline
        val barIn = ease(range(t, 600f, 1000f))
        if (barIn > 0f) {
            val bw = w * 0.28f
            val bx = (w - bw) / 2f
            val by = h * 0.88f
            val bh = 2f * density
            track.alpha = (barIn * 34).toInt()
            canvas.drawRoundRect(bx, by, bx + bw, by + bh, bh, bh, track)
            bar.alpha = (barIn * 255).toInt()
            val shown = max(0.04f, progress)
            canvas.drawRoundRect(bx, by, bx + bw * shown, by + bh, bh, bh, bar)
        }

        if (!exiting) {
            val ready = (elapsed >= 1700f && (progress >= 0.7f || elapsed >= 6_000f)) || (skipRequested && elapsed >= 450f)
            if (ready) exit() else postInvalidateOnAnimation()
        }
    }

    private fun exit() {
        if (exiting) return
        exiting = true
        animate().alpha(0f).scaleX(1.04f).scaleY(1.04f)
            .setDuration(if (motion) 380 else 0)
            .withEndAction { onFinished() }
            .start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            requestSkip()
            postInvalidateOnAnimation()
        }
        return true
    }

    private fun range(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun ease(x: Float): Float {
        val inv = 1f - x
        return 1f - inv * inv * inv
    }
}
