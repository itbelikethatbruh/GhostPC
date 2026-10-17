package com.radiant.pcbrowser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Slide-out command drawer: URL bar, game hub bookmarks, input mode, sensitivity/performance controls,
 * per-button key rebinding and a diagnostics panel. Fully navigable with a controller
 * (D-pad/stick move focus, A activates, B closes).
 */
class CommandDrawer(private val context: Context, private val prefs: Prefs, private val host: Host) {

    interface Host {
        fun navigate(input: String)
        fun goBack()
        fun goForward()
        fun reload()
        fun goHome()
        fun exitApp()
        fun canGoBack(): Boolean
        fun canGoForward(): Boolean
        fun currentUrl(): String?
        fun isCurrentSiteAllowed(): Boolean
        fun allowCurrentSiteAlways()
        fun onSettingsChanged(reloadPage: Boolean)
        fun setCursorEnabled(on: Boolean)
        fun isCursorEnabled(): Boolean
        fun statusText(): String
        fun requestCloseDrawer()
    }

    data class Bookmark(val title: String, val url: String)

    private val handler = Handler(Looper.getMainLooper())
    private val refreshers = ArrayList<() -> Unit>()
    private val applyRunnable = Runnable { host.onSettingsChanged(false) }

    /** Full-screen container (scrim + panel). Add to the activity root above the WebView. */
    val view: FrameLayout = FrameLayout(context)
    private val panel = LinearLayout(context)
    private val scroll = ScrollView(context)
    private lateinit var urlField: EditText
    private lateinit var backButton: TextView
    private lateinit var closeButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var siteLabel: TextView
    private lateinit var allowSiteButton: TextView
    private lateinit var statusLabel: TextView

    var isOpen = false
        private set

    init {
        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            isClickable = true
            isFocusable = false
            setOnClickListener { host.requestCloseDrawer() }
        }
        view.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        val pad = dp(14f)
        panel.setPadding(pad, pad, pad, dp(40f))
        scroll.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.isFillViewport = true
        scroll.setBackgroundColor(Ui.PANEL)
        scroll.isClickable = true
        view.addView(scroll, FrameLayout.LayoutParams(dp(400f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))

        view.visibility = View.GONE
        buildContent()
    }

    // ------------------------------------------------------------------------ public API

    /** @param fromController leave touch mode and focus the first item so the D-pad works immediately. */
    fun open(fromController: Boolean) {
        if (isOpen) return
        isOpen = true
        val screenW = context.resources.displayMetrics.widthPixels
        val w = min(dp(420f), (screenW * 0.62f).roundToInt())
        scroll.layoutParams = (scroll.layoutParams as FrameLayout.LayoutParams).also { it.width = w }
        refresh()
        view.visibility = View.VISIBLE
        scroll.translationX = -w.toFloat()
        scroll.animate().translationX(0f).setDuration(180).start()
        // The close button is never disabled, so it can always take focus (Back may be disabled).
        view.post { if (fromController) focusFirst() else closeButton.requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        handler.removeCallbacks(applyRunnable)
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(urlField.windowToken, 0)
        urlField.clearFocus()
        scroll.animate().translationX(-scroll.width.toFloat()).setDuration(150).withEndAction {
            if (!isOpen) view.visibility = View.GONE
        }.start()
    }

    /** Leaves touch mode and puts focus on the first item, so the D-pad and A work right away. */
    fun focusFirst() {
        closeButton.requestFocusFromTouch()
    }

    /** True when [v] is part of the drawer's view tree. */
    fun contains(v: View?): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === view) return true
            cur = cur.parent as? View
        }
        return false
    }

    fun setInsets(left: Int, top: Int, bottom: Int) {
        val pad = dp(14f)
        panel.setPadding(pad + left, pad + top, pad, dp(40f) + bottom)
    }

    fun onUrlChanged(url: String?) {
        if (!urlField.hasFocus()) urlField.setText(url ?: "")
        refreshSite()
    }

    fun refresh() {
        urlField.setText(host.currentUrl() ?: "")
        for (r in refreshers) r()
        refreshSite()
        refreshStatus()
    }

    fun refreshStatus() {
        if (isOpen) statusLabel.text = host.statusText()
    }

    // ------------------------------------------------------------------------ content

    private fun buildContent() {
        // Header
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "Radiant PC Game Browser"
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        closeButton = chip("✕") { host.requestCloseDrawer() }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(44f), dp(40f)))
        panel.addView(header)

        // Navigation row
        val nav = row()
        backButton = chip("◀ Back") { host.goBack(); refreshNav() }
        forwardButton = chip("▶") { host.goForward(); refreshNav() }
        nav.addView(backButton, weighted(1.4f))
        nav.addView(forwardButton, weighted(0.8f))
        nav.addView(chip("⟳ Reload") { host.reload(); host.requestCloseDrawer() }, weighted(1.4f))
        nav.addView(chip("⌂ Home") { host.goHome() }, weighted(1.2f))
        nav.addView(chip("Exit") { host.exitApp() }, weighted(1f))
        panel.addView(nav, topMargin(10f))
        refreshers.add { refreshNav() }

        // URL bar
        val urlRow = row()
        urlField = EditText(context).apply {
            setSingleLine(true)
            hint = "Enter a game URL or search"
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = Ui.focusable(context)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnEditorActionListener { v, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    host.navigate(v.text.toString())
                    true
                } else {
                    false
                }
            }
        }
        urlRow.addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(chip("Go") { host.navigate(urlField.text.toString()) }, LinearLayout.LayoutParams(dp(56f), dp(44f)).also { it.marginStart = dp(6f) })
        panel.addView(urlRow, topMargin(8f))

        // Bookmarks
        section("Game hubs")
        for (b in BOOKMARKS) {
            val t = chip(b.title) { host.navigate(b.url) }
            t.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            t.text = b.title + "\n" + (Uri.parse(b.url).host ?: b.url)
            t.setLineSpacing(0f, 1.05f)
            panel.addView(t, topMargin(5f))
        }

        // This site
        section("This site")
        siteLabel = TextView(context).apply { setTextColor(Ui.TEXT_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        panel.addView(siteLabel)
        allowSiteButton = chip("Always allow redirects/pop-ups on this site") {
            host.allowCurrentSiteAlways()
            refreshSite()
        }
        panel.addView(allowSiteButton, topMargin(6f))

        // Input
        section("Controller output")
        panel.addView(segmented(
            listOf(Prefs.MODE_KBM to "PC Keyboard + Mouse", Prefs.MODE_PAD to "XInput Gamepad", Prefs.MODE_HYBRID to "Hybrid (both)"),
            { prefs.mode },
            { prefs.mode = it; host.onSettingsChanged(false) }
        ))
        hint("Keyboard + Mouse: FPS / Quake / CS / Half-Life / Zombies. XInput: games with native controller support (GTA III, Skate 3, PES 6, Halo CE).")
        panel.addView(toggle("Virtual mouse cursor (L3 + R3)", { host.isCursorEnabled() }, { host.setCursorEnabled(it) }), topMargin(8f))
        panel.addView(toggle("Show cursor automatically in menus", { prefs.autoCursor }, { prefs.autoCursor = it; host.onSettingsChanged(false) }))
        panel.addView(toggle("On-screen touch gamepad", { prefs.touchGamepad }, { prefs.touchGamepad = it; host.onSettingsChanged(false) }))
        panel.addView(toggle("D-pad sends arrow keys (instead of 1-4)", { prefs.dpadArrows }, { prefs.dpadArrows = it; host.onSettingsChanged(false) }))

        section("Sensitivity")
        panel.addView(slider("Look sensitivity", 0.1f, 4f, 0.05f, { prefs.lookSensitivity }, { prefs.lookSensitivity = it },
            { String.format(Locale.US, "%.2f×", it) }))
        panel.addView(slider("Stick deadzone", 0.02f, 0.5f, 0.01f, { prefs.deadzone }, { prefs.deadzone = it },
            { String.format(Locale.US, "%d%%", (it * 100).roundToInt()) }))
        panel.addView(slider("Cursor speed", 0.3f, 3f, 0.05f, { prefs.cursorSpeed }, { prefs.cursorSpeed = it },
            { String.format(Locale.US, "%.2f×", it) }))

        section("Performance")
        panel.addView(segmented(
            listOf("1.0" to "100% Native", "0.75" to "75% High FPS", "0.5" to "50% Ultra FPS"),
            { String.format(Locale.US, "%s", trimScale(prefs.resolutionScale)) },
            { prefs.resolutionScale = it.toFloat(); host.onSettingsChanged(false) }
        ))
        hint("Scales the canvas resolution games compute from devicePixelRatio. Games with a fixed canvas size ignore it; reload if a game does not react.")
        panel.addView(segmented(
            listOf("0" to "Phone width", "1280" to "Desktop 1280", "1600" to "Desktop 1600"),
            { prefs.desktopViewport.toString() },
            { prefs.desktopViewport = it.toInt(); host.onSettingsChanged(true) }
        ), topMargin(8f))
        hint("Desktop width lays pages out like a 1280/1600 px wide PC window (reloads the page).")

        section("Button bindings (PC Keyboard + Mouse mode)")
        for (i in 0 until ControllerManager.BUTTONS) {
            val r = row()
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(TextView(context).apply {
                text = Prefs.BUTTON_NAMES[i]
                setTextColor(Ui.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            val b = chip("") { pickBinding(i) }
            r.addView(b, LinearLayout.LayoutParams(0, dp(40f), 1.1f))
            refreshers.add {
                val id = prefs.binds[i] ?: "None"
                b.text = when {
                    i in 12..15 && prefs.dpadArrows -> "Arrow keys (D-pad mode)"
                    i == 16 -> "Opens this drawer"
                    else -> BindTargets.label(id)
                }
            }
            panel.addView(r, topMargin(4f))
        }
        panel.addView(chip("Reset bindings to defaults") {
            prefs.resetBinds()
            refresh()
            host.onSettingsChanged(false)
        }, topMargin(8f))

        section("Status")
        statusLabel = TextView(context).apply {
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(false)
        }
        panel.addView(statusLabel)

        section("Controller shortcuts")
        hint(
            "Select + Start (or Guide): open / close this drawer\n" +
                "L3 + R3: toggle the virtual mouse cursor\n" +
                "Cursor mode: sticks move, A / RT click (hold to drag), LB / RB scroll, B / Start = Esc, Y = Enter, X = Backspace, D-pad = arrows\n" +
                "Game mode: Start tap = Esc (releases the mouse first), Start hold = Enter\n" +
                "Android Back button: open / close this drawer"
        )
    }

    private fun refreshNav() {
        backButton.isEnabled = host.canGoBack()
        backButton.alpha = if (backButton.isEnabled) 1f else 0.45f
        forwardButton.isEnabled = host.canGoForward()
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.45f
    }

    private fun refreshSite() {
        val url = host.currentUrl()
        val siteHost = url?.let { Uri.parse(it).host } ?: ""
        val allowed = host.isCurrentSiteAllowed()
        siteLabel.text = if (siteHost.isEmpty()) "No page loaded" else if (allowed) "$siteHost (allowed)" else "$siteHost (redirect shield active)"
        allowSiteButton.visibility = if (siteHost.isEmpty() || allowed) View.GONE else View.VISIBLE
    }

    private fun pickBinding(index: Int) {
        if (index == 16 || (index in 12..15 && prefs.dpadArrows)) return
        val targets = BindTargets.ALL
        val current = prefs.binds[index] ?: "None"
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Bind " + Prefs.BUTTON_NAMES[index])
            .setItems(targets.map { it.label }.toTypedArray()) { _, which ->
                prefs.setBind(index, targets[which].id)
                refresh()
                host.onSettingsChanged(false)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        val list = dialog.listView
                        val selected = list?.selectedView
                        if (list != null && selected != null) list.performItemClick(selected, list.selectedItemPosition, list.selectedItemId)
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP) d.dismiss()
                    true
                }
                else -> false
            }
        }
        dialog.show()
        dialog.window?.let { w ->
            val c = WindowCompat.getInsetsController(w, w.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
        val pos = targets.indexOfFirst { it.id == current }
        if (pos >= 0) dialog.listView?.setSelection(pos)
    }

    // ------------------------------------------------------------------------ widgets

    private fun dp(v: Float): Int = Ui.dp(context, v)

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun weighted(w: Float) = LinearLayout.LayoutParams(0, dp(42f), w).also { it.marginEnd = dp(5f) }

    private fun topMargin(v: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dp(v) }

    private fun section(title: String) {
        panel.addView(TextView(context).apply {
            text = title.uppercase(Locale.ROOT)
            setTextColor(Ui.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
        }, topMargin(18f))
    }

    private fun hint(text: String) {
        panel.addView(TextView(context).apply {
            this.text = text
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.1f)
        }, topMargin(4f))
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Ui.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        gravity = Gravity.CENTER
        minHeight = dp(40f)
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        background = Ui.focusable(context)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun segmented(options: List<Pair<String, String>>, selected: () -> String, onPick: (String) -> Unit): LinearLayout {
        val r = row()
        val chips = ArrayList<Pair<String, TextView>>()
        for ((value, label) in options) {
            val c = chip(label) {
                onPick(value)
                refresh()
            }
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            chips.add(value to c)
            r.addView(c, LinearLayout.LayoutParams(0, dp(46f), 1f).also { it.marginEnd = dp(5f) })
        }
        refreshers.add {
            val sel = selected()
            for ((value, c) in chips) {
                val on = value == sel
                c.background = Ui.focusable(context, on)
                c.setTextColor(if (on) Color.WHITE else Ui.TEXT)
            }
        }
        return r
    }

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit): Switch {
        val s = Switch(context)
        s.text = label
        s.setTextColor(Ui.TEXT)
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        s.background = Ui.focusable(context)
        s.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        s.isFocusable = true
        val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked -> set(checked) }
        refreshers.add {
            s.setOnCheckedChangeListener(null)
            s.isChecked = get()
            s.setOnCheckedChangeListener(listener)
        }
        s.layoutParams = topMargin(5f)
        return s
    }

    private fun slider(
        label: String, min: Float, max: Float, step: Float,
        get: () -> Float, set: (Float) -> Unit, format: (Float) -> String
    ): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.CHIP, 10f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(6f))
        }
        val title = TextView(context).apply { setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f) }
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(context).apply {
            this.max = steps
            isFocusable = true
            background = Ui.focusable(context)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                title.text = label + ": " + format(v)
                if (fromUser) {
                    set(v)
                    handler.removeCallbacks(applyRunnable)
                    handler.postDelayed(applyRunnable, 300)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        refreshers.add {
            val v = get()
            bar.progress = ((v - min) / step).roundToInt().coerceIn(0, steps)
            title.text = label + ": " + format(v)
        }
        box.addView(title)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))
        box.layoutParams = topMargin(6f)
        return box
    }

    private fun trimScale(v: Float): String = when {
        v >= 0.99f -> "1.0"
        v >= 0.74f -> "0.75"
        else -> "0.5"
    }

    companion object {
        val BOOKMARKS = listOf(
            Bookmark("Radiant Catalog (32 Games)", "https://radiantoptimizer.games"),
            Bookmark("Radiant Full Index (185 Games)", "https://radiantoptimizer.lol"),
            Bookmark("BO1 Zombies Hub", "https://vel.gg/bo1z"),
            Bookmark("Quake 1 Arena", "https://q1.pieter.com"),
            Bookmark("Quake 2 Arena", "https://q2.pieter.com"),
            Bookmark("Return to Castle Wolfenstein", "https://rtcw.pieter.com"),
            Bookmark("Urban Terror", "https://ut.pieter.com"),
            Bookmark("CS Surf", "https://surfd.net"),
            Bookmark("WASM Arcade (GTA III / Minecraft)", "https://wasmarcade.com"),
            Bookmark("WebXash (Half-Life / CS 1.6)", "https://x8bitrain.github.io/webXash/"),
            Bookmark("Diablo Web", "https://johnimril.github.io/diablo_web/")
        )
    }
}
