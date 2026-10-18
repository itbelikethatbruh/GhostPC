package com.radiant.pcbrowser

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * "Ghost glass" design tokens: a translucent violet-night panel floating over the blurred game,
 * one spectral (cyan → violet → pink) edge as the signature, and Xbox face colours where a
 * controller button is shown.
 */
object Ui {
    // Palette
    const val NIGHT: Int = 0xFF120F1C.toInt()
    const val GLASS: Int = 0xEB16121F.toInt()
    const val RAIL: Int = 0x40000000
    const val SURFACE: Int = 0x14FFFFFF
    const val SURFACE_HI: Int = 0x26FFFFFF
    const val PRESSED: Int = 0x33FFFFFF
    const val HAIRLINE: Int = 0x1FFFFFFF
    const val TEXT: Int = 0xFFF2EEFF.toInt()
    const val TEXT_DIM: Int = 0xFFA9A3C4.toInt()
    const val TEXT_FAINT: Int = 0xFF726C8C.toInt()
    const val ACCENT: Int = 0xFF8B7CFF.toInt()
    const val ACCENT_DIM: Int = 0x598B7CFF
    const val CYAN: Int = 0xFF5EE7FF.toInt()
    const val PINK: Int = 0xFFFF6FD8.toInt()
    const val OK: Int = 0xFF5BE49B.toInt()
    const val WARN: Int = 0xFFFFC857.toInt()
    const val BAD: Int = 0xFFFF6B7A.toInt()
    const val FACE_X: Int = 0xFF5EA8FF.toInt()

    val SPECTRUM: IntArray = intArrayOf(CYAN, ACCENT, PINK)

    // Type: condensed for titles and controller glyphs, medium for labels, regular for body.
    val DISPLAY: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val TITLE: Typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 600, false)
    val LABEL: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BODY: Typeface = Typeface.DEFAULT

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).roundToInt()

    fun rounded(context: Context, fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0f) setStroke(dp(context, strokeDp), strokeColor)
        }

    fun spectrum(orientation: GradientDrawable.Orientation, radiusPx: Float = 0f): GradientDrawable =
        GradientDrawable(orientation, SPECTRUM).apply { cornerRadius = radiusPx }

    /**
     * Interactive surface with a clearly visible focus ring for D-pad navigation.
     * selected = filled accent (segmented controls, chosen options).
     */
    fun focusable(context: Context, selected: Boolean = false, radiusDp: Float = 12f, base: Int = SURFACE): StateListDrawable {
        val idle = if (selected) ACCENT else base
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rounded(context, if (selected) ACCENT else SURFACE_HI, radiusDp, TEXT, 2f)
            )
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, if (selected) ACCENT else PRESSED, radiusDp))
            addState(intArrayOf(), rounded(context, idle, radiusDp))
        }
    }

    /** Option card: outlined in accent when chosen. */
    fun optionCard(context: Context, selected: Boolean): StateListDrawable {
        val fill = if (selected) 0x268B7CFF else SURFACE
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(context, fill, 16f, TEXT, 2f))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, PRESSED, 16f))
            addState(intArrayOf(), rounded(context, fill, 16f, if (selected) ACCENT else HAIRLINE, if (selected) 1.5f else 1f))
        }
    }

    /** Tab rail item: the active tab carries the spectral bar. */
    fun railItem(context: Context, selected: Boolean): StateListDrawable {
        val bg: Drawable = if (selected) {
            val fill = rounded(context, 0x2E8B7CFF, 16f)
            val bar = spectrum(GradientDrawable.Orientation.TOP_BOTTOM, dp(context, 2f).toFloat())
            LayerDrawable(arrayOf(fill, bar)).apply {
                setLayerGravity(1, Gravity.START or Gravity.CENTER_VERTICAL)
                setLayerWidth(1, dp(context, 3f))
                setLayerHeight(1, dp(context, 26f))
            }
        } else {
            rounded(context, 0x00000000, 16f)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(context, SURFACE_HI, 16f, TEXT, 2f))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, PRESSED, 16f))
            addState(intArrayOf(), bg)
        }
    }

    /** Glass panel with the spectral spine on its outer edge. */
    fun glassPanel(context: Context): Drawable {
        val fill = GradientDrawable().apply { setColor(GLASS) }
        val spine = spectrum(GradientDrawable.Orientation.TOP_BOTTOM)
        return LayerDrawable(arrayOf(fill, spine)).apply {
            setLayerGravity(1, Gravity.END or Gravity.FILL_VERTICAL)
            setLayerWidth(1, dp(context, 2f))
        }
    }

    const val FACE_A: Int = 0xFF5BE49B.toInt()
    const val FACE_B: Int = 0xFFFF6B7A.toInt()
    const val FACE_Y: Int = 0xFFFFC857.toInt()

    /** Controller button badge: ABXY in their Xbox colours, everything else as an outlined pill. */
    fun glyph(context: Context, label: String, face: Int = 0, small: Boolean = false): TextView = TextView(context).apply {
        text = label
        typeface = DISPLAY
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 10.5f else 13f)
        gravity = Gravity.CENTER
        val h = dp(context, if (small) 20f else 28f)
        minHeight = h
        minWidth = h
        if (face != 0) {
            setTextColor(face)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((face and 0x00FFFFFF) or 0x2E000000)
                setStroke(dp(context, 1.5f), face)
            }
            setPadding(0, 0, 0, 0)
        } else {
            setTextColor(if (small) TEXT_DIM else TEXT)
            background = rounded(context, 0x00000000, if (small) 6f else 9f, TEXT_FAINT, 1f)
            setPadding(dp(context, if (small) 5f else 8f), 0, dp(context, if (small) 5f else 8f), 0)
        }
    }

    /** Keyboard key cap, used where a controller button is shown mapped to a key. */
    fun keyCap(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        typeface = LABEL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        minHeight = dp(context, 30f)
        minWidth = dp(context, 30f)
        setPadding(dp(context, 10f), 0, dp(context, 10f), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 7f).toFloat()
            setColor(SURFACE_HI)
            setStroke(dp(context, 1f), 0x33FFFFFF)
        }
    }

    /** Primary (accent) or quiet pill button; focusable for the D-pad. */
    fun pillButton(context: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        typeface = LABEL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (primary) android.graphics.Color.WHITE else TEXT)
        gravity = Gravity.CENTER
        minHeight = dp(context, 48f)
        setPadding(dp(context, 26f), 0, dp(context, 26f), 0)
        background = focusable(context, primary, 24f, if (primary) ACCENT else SURFACE)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Small check-marked feature line. */
    fun featureRow(context: Context, text: String): View {
        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(android.widget.ImageView(context).apply { setImageDrawable(Icon(context, Icon.Kind.CHECK, OK, 18f)) },
            android.widget.LinearLayout.LayoutParams(dp(context, 18f), dp(context, 18f)))
        row.addView(TextView(context).apply {
            this.text = text
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = BODY
        }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(context, 12f)
        })
        return row
    }

    /** Deterministic two-tone tile for a site monogram. */
    fun monogram(context: Context, seed: String): GradientDrawable {
        var h = 0
        for (c in seed) h = h * 31 + c.code
        val hue = ((h % 360) + 360) % 360
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.80f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf(((hue + 48) % 360).toFloat(), 0.65f, 0.55f))
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply {
            cornerRadius = dp(context, 12f).toFloat()
        }
    }
}
