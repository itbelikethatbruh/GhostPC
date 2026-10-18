package com.radiant.pcbrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.min

/**
 * First-launch introduction: five swipeable pages that say what the app is for and how to drive
 * it from a controller. Fully operable with the D-pad / LB / RB / A / B, replayable from Status.
 */
class IntroView(
    context: Context,
    private val onFinished: (startTour: Boolean) -> Unit
) : FrameLayout(context) {

    private class Page(val title: String, val body: String, val art: (Context) -> View)

    private val density = resources.displayMetrics.density
    private val pager = ViewPager2(context)
    private val dots = LinearLayout(context)
    private val next: TextView
    private val skip: TextView
    private val glow = Paint()
    private val pages: List<Page> = listOf(
        Page(
            "A browser built for PC games.",
            "Desktop WebAssembly and WebGL games, played on this phone with a controller. " +
                "Nothing else on the screen, nothing in the way.",
            ::heroArt
        ),
        Page(
            "Your controller is a keyboard and mouse.",
            "Sticks become WASD and mouse look, triggers click, every button is rebindable. " +
                "A cursor appears in menus the moment a game lets go of the mouse.",
            ::mappingArt
        ),
        Page(
            "Games see a Windows PC.",
            "Sites are told they run in Chrome, Edge or Firefox on Windows 11. Missing GPU texture " +
                "formats are emulated and games that need shared-memory threads get them: Android's stock " +
                "WebView refuses both, and we know of no other Android browser that does all of this.",
            ::pcArt
        ),
        Page(
            "A shield in front of game portals.",
            "Ads and trackers are stopped against a list of 73,000 domains. Redirects and pop-ups you " +
                "did not tap never open; sites you trust are one tap away from being allowed.",
            ::shieldArt
        ),
        Page(
            "Ready.",
            "Select + Start opens the menu from anywhere. LB and RB switch its tabs, B backs out. " +
                "Take the short tour to see where everything lives, or start playing now.",
            ::readyArt
        )
    )

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(Ui.NIGHT)
        setWillNotDraw(false)

        // Small mark + name at the top
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f), dp(18f), dp(24f), 0)
        }
        head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(context, Ui.SURFACE, 12f)
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        head.addView(TextView(context).apply {
            text = "Radiant"
            typeface = Ui.DISPLAY
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Ui.TEXT)
            letterSpacing = 0.02f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(10f)
        })
        addView(head, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = pages.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = buildPage(pages[viewType])
                v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun getItemViewType(position: Int): Int = position
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }
        pager.offscreenPageLimit = 1
        // The pager only swipes; keys go through handleKey, so it must never take focus
        // (a focused RecyclerView draws Android's default focus highlight over the whole page).
        pager.isFocusable = false
        (pager.getChildAt(0) as? ViewGroup)?.let { rv ->
            rv.isFocusable = false
            rv.isFocusableInTouchMode = false
            rv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rv.defaultFocusHighlightEnabled = false
        }
        pager.setPageTransformer { page, position ->
            // Content drifts slightly slower than the page so pages feel layered, and fades at the edges.
            val p = position.coerceIn(-1f, 1f)
            page.alpha = 1f - 0.6f * abs(p)
            page.findViewWithTag<View>("content")?.translationX = -p * page.width * 0.18f
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateChrome(position)
            }
        })
        addView(pager, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).also {
            it.topMargin = dp(64f)
            it.bottomMargin = dp(84f)
        })

        // Bottom bar: skip, dots, next
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f), dp(12f), dp(24f), dp(20f))
        }
        skip = Ui.pillButton(context, "Skip", primary = false) { finish(false) }
        bar.addView(skip)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        bar.addView(dots, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        next = Ui.pillButton(context, "Next", primary = true) { advance() }
        // Holds focus even in touch mode, so the first controller press is not spent on focusing.
        next.isFocusableInTouchMode = true
        skip.isFocusableInTouchMode = true
        bar.addView(next)
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        for (i in pages.indices) {
            dots.addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } },
                LinearLayout.LayoutParams(dp(8f), dp(8f)).also { it.marginStart = dp(4f); it.marginEnd = dp(4f) })
        }
        updateChrome(0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glow.shader = RadialGradient(w * 0.5f, h * 0.35f, maxOf(w, h) * 0.55f,
            intArrayOf(0xFF241B3E.toInt(), Ui.NIGHT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
        super.onDraw(canvas)
    }

    fun focusFirst() {
        next.requestFocus()
    }

    /** Controller / keyboard: LB-RB and D-pad move between pages, A advances, B skips. */
    fun handleKey(event: KeyEvent): Boolean {
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
            else -> false
        }
        if (!handled) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_LEFT -> go(pager.currentItem - 1)
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_RIGHT -> go(pager.currentItem + 1)
            KeyEvent.KEYCODE_BUTTON_B -> finish(false)
            else -> if (findFocus() === skip) finish(false) else advance()
        }
        return true
    }

    private fun go(index: Int) {
        pager.setCurrentItem(index.coerceIn(0, pages.size - 1), true)
    }

    private fun advance() {
        if (pager.currentItem >= pages.size - 1) finish(true) else go(pager.currentItem + 1)
    }

    private fun finish(startTour: Boolean) {
        animate().alpha(0f).setDuration(if (ValueAnimator.areAnimatorsEnabled()) 260 else 0)
            .withEndAction { onFinished(startTour) }.start()
    }

    private fun updateChrome(position: Int) {
        val last = position == pages.size - 1
        next.text = if (last) "Show me around" else "Next"
        skip.text = if (last) "Start playing" else "Skip"
        for (i in 0 until dots.childCount) {
            val d = dots.getChildAt(i)
            (d.background as GradientDrawable).apply {
                if (i == position) {
                    colors = Ui.SPECTRUM
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                } else {
                    colors = null
                    setColor(0x40FFFFFF)
                }
            }
            val lp = d.layoutParams as LinearLayout.LayoutParams
            lp.width = dp(if (i == position) 22f else 8f)
            d.layoutParams = lp
        }
    }

    // ------------------------------------------------------------------ pages

    private fun buildPage(page: Page): View {
        val outer = FrameLayout(context)
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val content = LinearLayout(context).apply {
            // Landscape phones are short: art on the left, words on the right.
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "content"
            setPadding(dp(28f), 0, dp(28f), 0)
        }
        val maxW = min(dm.widthPixels, dp(if (landscape) 900f else 620f))
        outer.addView(content, LayoutParams(maxW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))

        val words = if (landscape) LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } else content
        if (landscape) {
            content.addView(page.art(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).also { it.marginStart = dp(28f) })
        } else {
            content.addView(page.art(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        words.addView(TextView(context).apply {
            text = page.title
            typeface = Ui.DISPLAY
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (landscape) 27f else 30f)
            setTextColor(Ui.TEXT)
            setLineSpacing(0f, 1.02f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = if (landscape) 0 else dp(22f)
        })
        words.addView(TextView(context).apply {
            text = page.body
            typeface = Ui.BODY
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (landscape) 14f else 15f)
            setTextColor(Ui.TEXT_DIM)
            setLineSpacing(0f, 1.28f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(10f)
        })
        return outer
    }

    private fun chip(text: String): View = TextView(context).apply {
        this.text = text
        typeface = Ui.LABEL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(Ui.TEXT)
        setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
        background = Ui.rounded(context, Ui.SURFACE, 12f, Ui.HAIRLINE, 1f)
    }

    private fun artRow(vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, v) in views.withIndex()) {
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                if (i > 0) it.marginStart = dp(8f)
            })
        }
    }

    private fun arrow(): View = ImageView(context).apply {
        setImageDrawable(Icon(context, Icon.Kind.FORWARD, Ui.TEXT_FAINT, 16f))
    }

    private fun heroArt(context: Context): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val word = TextView(context).apply {
            text = "Radiant"
            typeface = Ui.DISPLAY
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            setTextColor(Ui.TEXT)
            letterSpacing = 0.03f
        }
        col.addView(word)
        col.addView(View(context).apply { background = Ui.spectrum(GradientDrawable.Orientation.LEFT_RIGHT, dp(2f).toFloat()) },
            LinearLayout.LayoutParams(dp(140f), dp(3f)).also { it.topMargin = dp(4f) })
        col.addView(artRow(chip("WebAssembly"), chip("WebGL 2"), chip("Any controller")),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(18f) })
        return col
    }

    private fun mappingArt(context: Context): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            Triple("A", Ui.FACE_A, "Space"),
            Triple("RT", 0, "Left click"),
            Triple("Right stick", 0, "Mouse look"),
            Triple("Y", Ui.FACE_Y, "Next weapon")
        )
        for ((label, face, key) in rows) {
            col.addView(artRow(Ui.glyph(context, label, face), arrow(), Ui.keyCap(context, key)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8f) })
        }
        return col
    }

    private fun pcArt(context: Context): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (t in listOf("Windows 11, Chrome 141 (or Edge, or Firefox)", "S3TC textures emulated on phone GPUs",
            "Shared-memory threads for multithreaded games", "Pointer lock and fullscreen like a desktop")) {
            col.addView(Ui.featureRow(context, t), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(10f)
            })
        }
        return col
    }

    private fun shieldArt(context: Context): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(context).apply {
            setImageDrawable(Icon(context, Icon.Kind.SHIELD, Ui.CYAN, 64f))
            background = Ui.rounded(context, 0x1A5EE7FF, 24f)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }, LinearLayout.LayoutParams(dp(96f), dp(96f)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (t in listOf("73,000 ad and tracker domains", "Redirects you did not tap", "Pop-ups and casino portals")) {
            col.addView(Ui.featureRow(context, t), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(8f)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(20f) })
        return row
    }

    private fun readyArt(context: Context): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val plus = { TextView(context).apply { text = "+"; setTextColor(Ui.TEXT_FAINT); typeface = Ui.DISPLAY } }
        col.addView(artRow(Ui.glyph(context, "Select"), plus(), Ui.glyph(context, "Start"), arrow(), Ui.keyCap(context, "Menu")))
        col.addView(artRow(Ui.glyph(context, "L3"), plus(), Ui.glyph(context, "R3"), arrow(), Ui.keyCap(context, "Mouse cursor")),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10f) })
        col.addView(artRow(Ui.glyph(context, "LB"), Ui.glyph(context, "RB"), arrow(), Ui.keyCap(context, "Menu tabs")),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10f) })
        return col
    }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()
}
