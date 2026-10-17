package com.radiant.pcbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * On-screen Xbox-style gamepad. Feeds the same pipeline as a physical controller.
 *
 * Left 42% of the screen (lower part): floating analog stick (double-tap and hold = L3 / sprint).
 * Right side outside the buttons: drag = mouse look, quick tap = click at that spot in the page.
 */
class TouchGamepadView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onTouchPadState(buttons: FloatArray, axes: FloatArray)
        fun onTouchLook(dxDp: Float, dyDp: Float)
        fun onTouchTap(x: Float, y: Float)
    }

    private class Btn(val index: Int, val label: String) {
        var cx = 0f
        var cy = 0f
        var r = 0f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val buttons = listOf(
        Btn(0, "A"), Btn(1, "B"), Btn(2, "X"), Btn(3, "Y"),
        Btn(4, "LB"), Btn(5, "RB"), Btn(6, "LT"), Btn(7, "RT"),
        Btn(8, "⧉"), Btn(9, "☰"), Btn(11, "R3"),
        Btn(12, "▲"), Btn(13, "▼"), Btn(14, "◀"), Btn(15, "▶")
    )

    private val state = FloatArray(ControllerManager.BUTTONS)
    private val axes = FloatArray(ControllerManager.AXES)
    private val pointerButton = SparseIntArray()

    private var stickPointer = -1
    private var stickBaseX = 0f
    private var stickBaseY = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var lastStickUp = 0L
    private var l3Held = false

    private var lookPointer = -1
    private var lookLastX = 0f
    private var lookLastY = 0f
    private var lookDownX = 0f
    private var lookDownY = 0f
    private var lookDownTime = 0L
    private var lookMoved = false

    private var safeLeft = 0f
    private var safeRight = 0f

    /** XInput mode: the right-side drag area acts as a floating right stick instead of mouse look. */
    var lookAsStick = false
        set(value) {
            field = value
            axes[2] = 0f
            axes[3] = 0f
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(150, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
        isFakeBoldText = true
    }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setSafeInsets(left: Int, right: Int) {
        safeLeft = left.toFloat()
        safeRight = right.toFloat()
        layoutButtons(width, height)
        invalidate()
    }

    /** Releases everything (used when the view is hidden). */
    fun releaseAll() {
        pointerButton.clear()
        stickPointer = -1
        lookPointer = -1
        l3Held = false
        state.fill(0f)
        axes.fill(0f)
        listener.onTouchPadState(state, axes)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutButtons(w, h)
    }

    private fun layoutButtons(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val right = w - safeRight
        val faceX = right - dp(110f)
        val faceY = h - dp(110f)
        val off = dp(54f)
        val left = safeLeft
        for (b in buttons) {
            when (b.index) {
                0 -> set(b, faceX, faceY + off, 30f)
                1 -> set(b, faceX + off, faceY, 30f)
                2 -> set(b, faceX - off, faceY, 30f)
                3 -> set(b, faceX, faceY - off, 30f)
                7 -> set(b, right - dp(255f), h - dp(70f), 36f)
                6 -> set(b, right - dp(255f), h - dp(165f), 30f)
                5 -> set(b, right - dp(60f), h - dp(240f), 28f)
                4 -> set(b, right - dp(150f), h - dp(262f), 26f)
                11 -> set(b, right - dp(255f), h - dp(252f), 22f)
                8 -> set(b, w / 2f - dp(64f), dp(34f), 20f)
                9 -> set(b, w / 2f + dp(64f), dp(34f), 20f)
                12 -> set(b, left + dp(100f), dp(112f), 20f)
                13 -> set(b, left + dp(100f), dp(188f), 20f)
                14 -> set(b, left + dp(62f), dp(150f), 20f)
                15 -> set(b, left + dp(138f), dp(150f), 20f)
            }
        }
    }

    private fun set(b: Btn, x: Float, y: Float, rDp: Float) {
        b.cx = x
        b.cy = y
        b.r = dp(rDp)
    }

    private fun hit(x: Float, y: Float): Btn? {
        var best: Btn? = null
        var bestD = Float.MAX_VALUE
        for (b in buttons) {
            val d = hypot(x - b.cx, y - b.cy)
            if (d <= b.r * 1.3f && d < bestD) {
                best = b
                bestD = d
            }
        }
        return best
    }

    private fun inStickZone(x: Float, y: Float) = x < width * 0.42f && y > height * 0.40f
    private fun inLookZone(x: Float) = x >= width * 0.42f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val claimed = claim(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !claimed) return false
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) move(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = event.actionIndex
                release(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                return true
            }
        }
        listener.onTouchPadState(state, axes)
        invalidate()
        return true
    }

    private fun claim(id: Int, x: Float, y: Float, time: Long): Boolean {
        val b = hit(x, y)
        if (b != null) {
            pointerButton.put(id, b.index)
            state[b.index] = 1f
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return true
        }
        if (inStickZone(x, y) && stickPointer == -1) {
            stickPointer = id
            stickBaseX = x
            stickBaseY = y
            stickX = x
            stickY = y
            if (time - lastStickUp < 300L) {
                l3Held = true
                state[10] = 1f
            }
            return true
        }
        if (inLookZone(x) && lookPointer == -1) {
            lookPointer = id
            lookLastX = x
            lookLastY = y
            lookDownX = x
            lookDownY = y
            lookDownTime = time
            lookMoved = false
            return true
        }
        return false
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == stickPointer) {
            val radius = dp(56f)
            var dx = x - stickBaseX
            var dy = y - stickBaseY
            val d = hypot(dx, dy)
            if (d > radius) {
                // drag the base along so the stick never "sticks" at the edge
                stickBaseX += dx / d * (d - radius)
                stickBaseY += dy / d * (d - radius)
                dx = x - stickBaseX
                dy = y - stickBaseY
            }
            stickX = x
            stickY = y
            axes[0] = (dx / radius).coerceIn(-1f, 1f)
            axes[1] = (dy / radius).coerceIn(-1f, 1f)
        } else if (id == lookPointer && lookAsStick) {
            val radius = dp(56f)
            if (!lookMoved && hypot(x - lookDownX, y - lookDownY) > dp(8f)) lookMoved = true
            axes[2] = ((x - lookDownX) / radius).coerceIn(-1f, 1f)
            axes[3] = ((y - lookDownY) / radius).coerceIn(-1f, 1f)
        } else if (id == lookPointer) {
            val dx = x - lookLastX
            val dy = y - lookLastY
            lookLastX = x
            lookLastY = y
            if (!lookMoved && hypot(x - lookDownX, y - lookDownY) > dp(8f)) lookMoved = true
            if (lookMoved && (dx != 0f || dy != 0f)) listener.onTouchLook(dx / density, dy / density)
        }
    }

    private fun release(id: Int, x: Float, y: Float, time: Long) {
        val idx = pointerButton.get(id, -1)
        if (idx >= 0) {
            pointerButton.delete(id)
            var stillHeld = false
            for (k in 0 until pointerButton.size()) if (pointerButton.valueAt(k) == idx) stillHeld = true
            if (!stillHeld) state[idx] = 0f
            return
        }
        if (id == stickPointer) {
            stickPointer = -1
            axes[0] = 0f
            axes[1] = 0f
            lastStickUp = time
            if (l3Held) {
                l3Held = false
                state[10] = 0f
            }
            return
        }
        if (id == lookPointer) {
            lookPointer = -1
            axes[2] = 0f
            axes[3] = 0f
            if (!lookMoved && time - lookDownTime < 250L) listener.onTouchTap(x, y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (b in buttons) {
            val pressed = state[b.index] > 0.5f
            fillPaint.color = if (pressed) Color.argb(150, 0, 229, 255) else Color.argb(70, 20, 20, 24)
            canvas.drawCircle(b.cx, b.cy, b.r, fillPaint)
            canvas.drawCircle(b.cx, b.cy, b.r, strokePaint)
            textPaint.textSize = if (b.label.length > 1) dp(13f) else dp(16f)
            canvas.drawText(b.label, b.cx, b.cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }
        val radius = dp(56f)
        if (lookAsStick && lookPointer != -1) {
            fillPaint.color = Color.argb(45, 255, 255, 255)
            canvas.drawCircle(lookDownX, lookDownY, radius, fillPaint)
            canvas.drawCircle(lookDownX, lookDownY, radius, strokePaint)
            fillPaint.color = Color.argb(150, 255, 255, 255)
            canvas.drawCircle(lookDownX + axes[2] * radius, lookDownY + axes[3] * radius, dp(22f), fillPaint)
        }
        if (stickPointer != -1) {
            fillPaint.color = Color.argb(60, 255, 255, 255)
            canvas.drawCircle(stickBaseX, stickBaseY, radius, fillPaint)
            canvas.drawCircle(stickBaseX, stickBaseY, radius, strokePaint)
            fillPaint.color = if (l3Held) Color.argb(200, 0, 229, 255) else Color.argb(170, 255, 255, 255)
            val kx = stickBaseX + axes[0] * radius
            val ky = stickBaseY + axes[1] * radius
            canvas.drawCircle(kx, ky, dp(26f), fillPaint)
        } else {
            fillPaint.color = Color.argb(35, 255, 255, 255)
            val hx = safeLeft + dp(150f)
            val hy = height - dp(130f)
            canvas.drawCircle(hx, hy, radius, fillPaint)
            canvas.drawCircle(hx, hy, radius, strokePaint)
        }
    }
}
