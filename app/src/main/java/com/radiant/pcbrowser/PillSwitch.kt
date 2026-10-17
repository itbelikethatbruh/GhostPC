package com.radiant.pcbrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Compact on/off switch. The surrounding row is the focusable, clickable target. */
class PillSwitch(context: Context) : View(context) {

    var isOn = false
        private set

    private var position = 0f
    private var animator: ValueAnimator? = null
    private val w = Ui.dp(context, 44f)
    private val h = Ui.dp(context, 26f)
    private val inset = Ui.dp(context, 3f).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setOn(on: Boolean, animate: Boolean) {
        isOn = on
        animator?.cancel()
        val target = if (on) 1f else 0f
        if (!animate || position == target) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = if (isEnabled) 255 else 90
        track.color = blend(0x40FFFFFF, Ui.ACCENT, position)
        track.alpha = (Color.alpha(track.color) * alpha) / 255
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = height / 2f
        canvas.drawRoundRect(rect, r, r, track)
        val tr = r - inset
        val cx = r + (width - 2 * r) * position
        thumb.alpha = alpha
        canvas.drawCircle(cx, r, tr, thumb)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val ia = 1f - t
        return Color.argb(
            (Color.alpha(a) * ia + Color.alpha(b) * t).toInt(),
            (Color.red(a) * ia + Color.red(b) * t).toInt(),
            (Color.green(a) * ia + Color.green(b) * t).toInt(),
            (Color.blue(a) * ia + Color.blue(b) * t).toInt()
        )
    }
}
