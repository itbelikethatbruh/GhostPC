package com.radiant.pcbrowser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import kotlin.math.roundToInt

/** Small styling helpers for the programmatic overlay UI (no XML layouts needed). */
object Ui {
    const val ACCENT: Int = 0xFF00E5FF.toInt()
    const val ACCENT_DIM: Int = 0x6600E5FF
    const val PANEL: Int = 0xF0101318.toInt()
    const val TEXT: Int = 0xFFECEFF4.toInt()
    const val TEXT_DIM: Int = 0xFF9AA3AF.toInt()
    const val CHIP: Int = 0x24FFFFFF
    const val CHIP_PRESSED: Int = 0x55FFFFFF

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).roundToInt()

    fun rounded(context: Context, fill: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0f) setStroke(dp(context, strokeDp), strokeColor)
        }

    /** Background with a clearly visible focus ring for controller (D-pad) navigation. */
    fun focusable(context: Context, selected: Boolean = false): StateListDrawable {
        val base = if (selected) ACCENT_DIM else CHIP
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(context, CHIP_PRESSED, 10f, ACCENT, 2.5f))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(context, CHIP_PRESSED, 10f))
            addState(intArrayOf(), rounded(context, base, 10f, if (selected) ACCENT else 0, if (selected) 1.5f else 0f))
        }
    }
}
