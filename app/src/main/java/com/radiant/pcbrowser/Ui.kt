package com.radiant.pcbrowser

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
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
