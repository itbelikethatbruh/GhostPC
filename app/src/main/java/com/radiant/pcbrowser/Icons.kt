package com.radiant.pcbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Stroke icons drawn on a 24-unit grid, so they stay crisp at any size and never clip
 * the way unicode glyphs did inside small buttons.
 */
class Icon(context: Context, private val kind: Kind, color: Int, sizeDp: Float = 20f) : Drawable() {

    enum class Kind {
        BACK, FORWARD, RELOAD, CLOSE, MENU, LOCK, GLOBE, GAMES, GAMEPAD, DISPLAY, BUTTONS,
        STATUS, POWER, CHECK, THREADS, CHEVRON
    }

    private val sizePx = Ui.dp(context, sizeDp)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private val strokePath = Path()
    private val fillPath = Path()

    init {
        build()
    }

    var color: Int
        get() = stroke.color
        set(value) {
            stroke.color = value
            fill.color = value
            invalidateSelf()
        }

    private fun build() {
        val p = strokePath
        val f = fillPath
        when (kind) {
            Kind.BACK -> { p.moveTo(15f, 5f); p.lineTo(8f, 12f); p.lineTo(15f, 19f) }
            Kind.FORWARD -> { p.moveTo(9f, 5f); p.lineTo(16f, 12f); p.lineTo(9f, 19f) }
            Kind.CHEVRON -> { p.moveTo(10f, 7f); p.lineTo(15f, 12f); p.lineTo(10f, 17f) }
            Kind.RELOAD -> {
                p.addArc(RectF(5f, 5f, 19f, 19f), -40f, -290f)
                p.moveTo(18.4f, 4.6f); p.lineTo(18.4f, 9.2f); p.lineTo(13.8f, 9.2f)
            }
            Kind.CLOSE -> { p.moveTo(6.5f, 6.5f); p.lineTo(17.5f, 17.5f); p.moveTo(17.5f, 6.5f); p.lineTo(6.5f, 17.5f) }
            Kind.MENU -> {
                p.moveTo(5f, 7f); p.lineTo(19f, 7f)
                p.moveTo(5f, 12f); p.lineTo(19f, 12f)
                p.moveTo(5f, 17f); p.lineTo(14f, 17f)
            }
            Kind.LOCK -> {
                p.addRoundRect(RectF(5.5f, 10.5f, 18.5f, 20f), 2.5f, 2.5f, Path.Direction.CW)
                p.moveTo(8.5f, 10.5f); p.lineTo(8.5f, 8f)
                p.arcTo(RectF(8.5f, 4.5f, 15.5f, 11.5f), 180f, 180f)
                p.lineTo(15.5f, 10.5f)
            }
            Kind.GLOBE -> {
                p.addCircle(12f, 12f, 8f, Path.Direction.CW)
                p.addOval(RectF(8.5f, 4f, 15.5f, 20f), Path.Direction.CW)
                p.moveTo(4f, 12f); p.lineTo(20f, 12f)
            }
            Kind.GAMES -> {
                p.addRoundRect(RectF(4f, 4f, 10.5f, 10.5f), 2f, 2f, Path.Direction.CW)
                p.addRoundRect(RectF(13.5f, 4f, 20f, 10.5f), 2f, 2f, Path.Direction.CW)
                p.addRoundRect(RectF(4f, 13.5f, 10.5f, 20f), 2f, 2f, Path.Direction.CW)
                p.addRoundRect(RectF(13.5f, 13.5f, 20f, 20f), 2f, 2f, Path.Direction.CW)
            }
            Kind.GAMEPAD -> {
                p.moveTo(7.5f, 7.5f); p.lineTo(16.5f, 7.5f)
                p.cubicTo(19.2f, 7.5f, 20.6f, 9.6f, 21.1f, 12.5f)
                p.lineTo(21.6f, 15.6f)
                p.cubicTo(21.9f, 17.7f, 19.9f, 18.8f, 18.6f, 17.5f)
                p.lineTo(16.3f, 15.2f); p.lineTo(7.7f, 15.2f); p.lineTo(5.4f, 17.5f)
                p.cubicTo(4.1f, 18.8f, 2.1f, 17.7f, 2.4f, 15.6f)
                p.lineTo(2.9f, 12.5f)
                p.cubicTo(3.4f, 9.6f, 4.8f, 7.5f, 7.5f, 7.5f)
                p.close()
                p.moveTo(7.5f, 10f); p.lineTo(7.5f, 13f); p.moveTo(6f, 11.5f); p.lineTo(9f, 11.5f)
                f.addCircle(16f, 10.6f, 1f, Path.Direction.CW)
                f.addCircle(17.8f, 12.4f, 1f, Path.Direction.CW)
            }
            Kind.DISPLAY -> {
                p.addRoundRect(RectF(3.5f, 4.5f, 20.5f, 15.5f), 2f, 2f, Path.Direction.CW)
                p.moveTo(12f, 15.5f); p.lineTo(12f, 19.5f)
                p.moveTo(8f, 19.5f); p.lineTo(16f, 19.5f)
            }
            Kind.BUTTONS -> {
                p.addRoundRect(RectF(3f, 6.5f, 21f, 17.5f), 2.5f, 2.5f, Path.Direction.CW)
                p.moveTo(8f, 14f); p.lineTo(16f, 14f)
                f.addCircle(7f, 10f, 0.95f, Path.Direction.CW)
                f.addCircle(10.3f, 10f, 0.95f, Path.Direction.CW)
                f.addCircle(13.7f, 10f, 0.95f, Path.Direction.CW)
                f.addCircle(17f, 10f, 0.95f, Path.Direction.CW)
            }
            Kind.STATUS -> {
                p.moveTo(3f, 12f); p.lineTo(7f, 12f); p.lineTo(9.5f, 6.5f)
                p.lineTo(14.5f, 17.5f); p.lineTo(17f, 12f); p.lineTo(21f, 12f)
            }
            Kind.POWER -> {
                p.addArc(RectF(5f, 5.5f, 19f, 19.5f), -60f, 300f)
                p.moveTo(12f, 3.5f); p.lineTo(12f, 11.5f)
            }
            Kind.CHECK -> { p.moveTo(5f, 12.5f); p.lineTo(10f, 17.5f); p.lineTo(19f, 7f) }
            Kind.THREADS -> {
                p.addRoundRect(RectF(7f, 7f, 17f, 17f), 2f, 2f, Path.Direction.CW)
                for (x in floatArrayOf(10f, 14f)) {
                    p.moveTo(x, 3.5f); p.lineTo(x, 7f)
                    p.moveTo(x, 17f); p.lineTo(x, 20.5f)
                }
                for (y in floatArrayOf(10f, 14f)) {
                    p.moveTo(3.5f, y); p.lineTo(7f, y)
                    p.moveTo(17f, y); p.lineTo(20.5f, y)
                }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        canvas.drawPath(strokePath, stroke)
        if (!fillPath.isEmpty) canvas.drawPath(fillPath, fill)
        canvas.restore()
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
