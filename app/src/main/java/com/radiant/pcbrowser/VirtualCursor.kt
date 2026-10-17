package com.radiant.pcbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/** High-visibility desktop arrow cursor. Moved with translationX/Y so no redraw is needed per frame. */
class CursorOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val scale = density * 1.35f
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 20f)
        lineTo(5f, 15.5f)
        lineTo(8.6f, 23.2f)
        lineTo(11.7f, 21.8f)
        lineTo(8.1f, 14.3f)
        lineTo(14.6f, 14.3f)
        close()
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(170, 139, 124, 255)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /** Pixel size of the view; the arrow tip sits at (PAD, PAD). */
    val sizePx: Int get() = (28f * scale).toInt()
    private val pad = 4f * scale

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun moveTo(x: Float, y: Float) {
        translationX = x - pad
        translationY = y - pad
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(pad, pad)
        canvas.scale(scale, scale)
        canvas.drawPath(arrow, glow)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

/**
 * Virtual PC mouse for menus: sticks move the arrow, A/RT taps (or drags) at the arrow position,
 * LB/RB scroll, B/Start = Esc, Y = Enter, D-pad = arrow keys.
 */
class VirtualCursor(private val host: Host, private val view: CursorOverlayView) {

    interface Host {
        val areaWidth: Int
        val areaHeight: Int
        fun cursorTouch(action: Int, x: Float, y: Float)
        fun cursorHover(x: Float, y: Float)
        fun cursorScroll(x: Float, y: Float, amount: Float)
        fun cursorKey(code: String, down: Boolean)
    }

    var active = false
        private set
    var x = -1f
        private set
    var y = -1f
        private set

    private var touching = false
    private var scrollAccum = 0f
    private var hoverDirty = false

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            view.visibility = View.VISIBLE
            if (ensurePlaced()) host.cursorHover(x, y)
        } else {
            cancel()
            view.visibility = View.GONE
        }
    }

    /** Ends a held tap/drag without leaving a stuck touch in the page. */
    fun cancel() {
        if (touching) {
            touching = false
            host.cursorTouch(MotionEvent.ACTION_UP, x, y)
        }
        scrollAccum = 0f
    }

    /** Centres the cursor the first time the screen size is known; false while there is no layout yet. */
    private fun ensurePlaced(): Boolean {
        val w = host.areaWidth
        val h = host.areaHeight
        if (w <= 0 || h <= 0) {
            view.visibility = View.INVISIBLE
            return false
        }
        if (x < 0f || y < 0f) {
            x = w / 2f
            y = h / 2f
        }
        clamp()
        view.moveTo(x, y)
        view.visibility = View.VISIBLE
        return true
    }

    fun nudge(dx: Float, dy: Float) {
        if (!active || !ensurePlaced()) return
        x += dx
        y += dy
        clamp()
        view.moveTo(x, y)
        if (touching) host.cursorTouch(MotionEvent.ACTION_MOVE, x, y) else host.cursorHover(x, y)
    }

    fun frame(dt: Float, state: ControllerManager.State, speedSetting: Float, deadzone: Float) {
        if (!active || !ensurePlaced()) return
        val w = host.areaWidth
        val h = host.areaHeight
        val base = max(w, h) * 0.85f * speedSetting
        val (lx, ly) = curve(state.axes[0], state.axes[1], deadzone)
        val (rx, ry) = curve(state.axes[2], state.axes[3], deadzone)
        val dx = (lx + rx * 0.35f) * base * dt
        val dy = (ly + ry * 0.35f) * base * dt
        if (dx != 0f || dy != 0f) {
            x += dx
            y += dy
            clamp()
            view.moveTo(x, y)
            if (touching) host.cursorTouch(MotionEvent.ACTION_MOVE, x, y) else hoverDirty = true
        }
        if (hoverDirty && !touching) {
            hoverDirty = false
            host.cursorHover(x, y)
        }
        // LB scrolls up, RB scrolls down, ~10 notches per second while held
        val dir = (if (state.pressed(5)) 1f else 0f) - (if (state.pressed(4)) 1f else 0f)
        if (dir != 0f) {
            scrollAccum += dir * dt * 10f
            while (scrollAccum >= 1f) {
                scrollAccum -= 1f
                host.cursorScroll(x, y, -1f)
            }
            while (scrollAccum <= -1f) {
                scrollAccum += 1f
                host.cursorScroll(x, y, 1f)
            }
        } else {
            scrollAccum = 0f
        }
    }

    fun button(index: Int, down: Boolean) {
        if (!active || (down && !ensurePlaced())) return
        when (index) {
            0, 7 -> {
                if (down && !touching) {
                    touching = true
                    host.cursorTouch(MotionEvent.ACTION_DOWN, x, y)
                } else if (!down && touching) {
                    touching = false
                    host.cursorTouch(MotionEvent.ACTION_UP, x, y)
                }
            }
            4 -> if (down) host.cursorScroll(x, y, 1f)
            5 -> if (down) host.cursorScroll(x, y, -1f)
            1, 9 -> host.cursorKey("Escape", down)
            3 -> host.cursorKey("Enter", down)
            2 -> host.cursorKey("Backspace", down)
            12 -> host.cursorKey("ArrowUp", down)
            13 -> host.cursorKey("ArrowDown", down)
            14 -> host.cursorKey("ArrowLeft", down)
            15 -> host.cursorKey("ArrowRight", down)
        }
    }

    private fun clamp() {
        x = x.coerceIn(0f, (host.areaWidth - 1).coerceAtLeast(0).toFloat())
        y = y.coerceIn(0f, (host.areaHeight - 1).coerceAtLeast(0).toFloat())
    }

    private fun curve(ax: Float, ay: Float, deadzone: Float): Pair<Float, Float> {
        val mag = hypot(ax, ay)
        if (mag <= deadzone) return Pair(0f, 0f)
        val n = ((mag - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val s = n.toDouble().pow(2.0).toFloat()
        return Pair(ax / mag * s, ay / mag * s)
    }
}
