package com.radiant.pcbrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * Guided tour over the live main screen: a dimmed layer with a spotlight on the control being
 * explained and a card that says what it does. A or a tap moves on, B ends it.
 */
class TourOverlay(
    context: Context,
    private val steps: List<Step>,
    private val onFinished: () -> Unit
) : FrameLayout(context) {

    class Step(val title: String, val body: String, val target: View? = null, val glyphs: List<String> = emptyList())

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0xD4120F1C.toInt() }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Ui.CYAN
    }
    private val hole = RectF()
    private var holeAlpha = 0f
    private var index = -1
    private val card = LinearLayout(context)
    private val title = TextView(context)
    private val body = TextView(context)
    private val glyphRow = LinearLayout(context)
    private val counter = TextView(context)
    private val next: TextView

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null) // needed for the CLEAR hole
        isClickable = true
        setOnClickListener { advance() }

        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.glassPanel(context)
        card.setPadding(dp(22f), dp(18f), dp(24f), dp(18f))
        card.isClickable = true
        card.setOnClickListener { advance() }
        title.typeface = Ui.DISPLAY
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.setTextColor(Ui.TEXT)
        card.addView(title)
        glyphRow.orientation = LinearLayout.HORIZONTAL
        glyphRow.gravity = Gravity.CENTER_VERTICAL
        card.addView(glyphRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(10f)
        })
        body.typeface = Ui.BODY
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        body.setTextColor(Ui.TEXT_DIM)
        body.setLineSpacing(0f, 1.25f)
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(8f)
        })
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        counter.typeface = Ui.LABEL
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        counter.setTextColor(Ui.TEXT_FAINT)
        bar.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.pillButton(context, "End tour", primary = false) { finish() })
        next = Ui.pillButton(context, "Next", primary = true) { advance() }
        bar.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(8f)
        })
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(16f)
        })
        addView(card, LayoutParams(dp(400f), ViewGroup.LayoutParams.WRAP_CONTENT))
        alpha = 0f
    }

    fun start() {
        animate().alpha(1f).setDuration(if (ValueAnimator.areAnimatorsEnabled()) 220 else 0).start()
        show(0)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return false
    }

    private fun show(i: Int) {
        if (i >= steps.size) { finish(); return }
        index = i
        val step = steps[i]
        title.text = step.title
        body.text = step.body
        counter.text = (i + 1).toString() + " of " + steps.size
        next.text = if (i == steps.size - 1) "Done" else "Next"
        glyphRow.removeAllViews()
        glyphRow.visibility = if (step.glyphs.isEmpty()) GONE else VISIBLE
        step.glyphs.forEachIndexed { k, g ->
            if (g == "+") {
                glyphRow.addView(TextView(context).apply { text = "+"; setTextColor(Ui.TEXT_FAINT); typeface = Ui.DISPLAY; setPadding(dp(6f), 0, dp(6f), 0) })
            } else {
                val face = when (g) { "A" -> Ui.FACE_A; "B" -> Ui.FACE_B; "X" -> Ui.FACE_X; "Y" -> Ui.FACE_Y; else -> 0 }
                glyphRow.addView(Ui.glyph(context, g, face), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    if (k > 0) it.marginStart = dp(4f)
                })
            }
        }
        post { place(step) }
        next.requestFocus()
    }

    private fun place(step: Step) {
        val target = step.target
        val lp = card.layoutParams as LayoutParams
        val margin = dp(20f)
        if (target != null && target.isShown) {
            val r = Rect()
            target.getGlobalVisibleRect(r)
            val mine = IntArray(2).also { getLocationOnScreen(it) }
            r.offset(-mine[0], -mine[1])
            val pad = dp(10f)
            hole.set(r.left - pad.toFloat(), r.top - pad.toFloat(), r.right + pad.toFloat(), r.bottom + pad.toFloat())
            holeAlpha = 1f
            // Card beside the target: to its right when there is room, else below.
            card.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            if (width - hole.right - margin >= lp.width) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = (hole.right + margin).toInt()
                lp.topMargin = max(margin, hole.top.toInt())
            } else {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = margin
                lp.topMargin = (hole.bottom + margin).toInt()
            }
        } else {
            holeAlpha = 0f
            lp.gravity = Gravity.CENTER
            lp.leftMargin = 0
            lp.topMargin = 0
        }
        card.layoutParams = lp
        invalidate()
    }

    private fun advance() {
        show(index + 1)
    }

    private fun finish() {
        animate().alpha(0f).setDuration(if (ValueAnimator.areAnimatorsEnabled()) 200 else 0)
            .withEndAction { onFinished() }.start()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (holeAlpha > 0f) {
            val r = hole.height() / 2f
            canvas.drawRoundRect(hole, r, r, clear)
            canvas.drawRoundRect(hole, r, r, ring)
        }
        super.onDraw(canvas)
    }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()
}
