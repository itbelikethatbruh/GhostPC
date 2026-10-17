package com.radiant.pcbrowser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Slide-out command panel styled like a console quick menu: a tab rail on the left (LB / RB switch
 * tabs), pages on the right, everything reachable with the D-pad and A / B.
 */
class CommandDrawer(private val context: Context, private val prefs: Prefs, private val host: Host) {

    interface Host {
        fun navigate(input: String)
        fun goBack()
        fun goForward()
        fun reload()
        fun exitApp()
        fun canGoBack(): Boolean
        fun canGoForward(): Boolean
        fun currentUrl(): String?
        fun siteRedirectState(): Int
        fun setCurrentSiteAllowed(allowed: Boolean)
        fun threadsSupported(): Boolean
        fun isThreadsEnabledForCurrentSite(): Boolean
        fun setThreadsForCurrentSite(enabled: Boolean)
        fun threadOrigins(): List<String>
        fun removeThreadOrigin(origin: String)
        fun onSettingsChanged(reloadPage: Boolean)
        fun onIdentityChanged()
        fun setCursorEnabled(on: Boolean)
        fun isCursorEnabled(): Boolean
        fun statusItems(): List<StatusItem>
        fun crashReport(): CrashLog.Report?
        fun graphicsBackendText(): String
        fun openWebViewDevTools()
        fun copyCrashReport()
        fun clearCrashReport()
        fun requestCloseDrawer()
    }

    data class Bookmark(val title: String, val monogram: String, val url: String)

    data class StatusItem(val label: String, val value: String, val level: Int)

    private enum class Tab(val label: String, val icon: Icon.Kind) {
        GAMES("Games", Icon.Kind.GAMES),
        CONTROLS("Controls", Icon.Kind.GAMEPAD),
        DISPLAY("Display", Icon.Kind.DISPLAY),
        BUTTONS("Buttons", Icon.Kind.BUTTONS),
        STATUS("Status", Icon.Kind.STATUS)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val applyRunnable = Runnable { host.onSettingsChanged(false) }
    private val refreshers = ArrayList<() -> Unit>()

    /** Full-screen container (scrim + panel). Add it to the activity root above the WebView. */
    val view: FrameLayout = FrameLayout(context)
    private val scrim = View(context)
    private val panel = FrameLayout(context)
    private val column = LinearLayout(context)
    private val pagesHost = FrameLayout(context)
    private val pages = LinkedHashMap<Tab, ScrollView>()
    private val railItems = LinkedHashMap<Tab, LinearLayout>()
    private val bindChips = HashMap<Int, TextView>()
    private var current = Tab.GAMES
    private var picker: View? = null
    private var pickerIndex = -1

    private lateinit var urlField: EditText
    private lateinit var urlPill: LinearLayout
    private lateinit var urlIcon: ImageView
    private lateinit var backButton: ImageView
    private lateinit var forwardButton: ImageView
    private lateinit var closeButton: ImageView
    private lateinit var siteBox: LinearLayout
    private lateinit var threadListBox: LinearLayout
    private lateinit var statusBox: LinearLayout

    var isOpen = false
        private set

    init {
        scrim.setBackgroundColor(Color.argb(110, 6, 3, 14))
        scrim.isClickable = true
        scrim.isFocusable = false
        scrim.setOnClickListener { host.requestCloseDrawer() }
        view.addView(scrim, matchParent())

        panel.background = Ui.glassPanel(context)
        panel.isClickable = true
        view.addView(panel, FrameLayout.LayoutParams(dp(560f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))

        column.orientation = LinearLayout.VERTICAL
        panel.addView(column, matchParent())
        column.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(View(context).apply { setBackgroundColor(Ui.HAIRLINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(buildRail(), LinearLayout.LayoutParams(dp(92f), ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(pagesHost, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        buildPages()
        view.visibility = View.GONE
        selectTab(Tab.GAMES, animate = false, focus = false)
    }

    // ============================================================================ public API

    fun open(fromController: Boolean) {
        if (isOpen) return
        isOpen = true
        val screenW = context.resources.displayMetrics.widthPixels
        val w = min(dp(620f), (screenW * 0.7f).roundToInt())
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).also { it.width = w }
        refresh()
        view.visibility = View.VISIBLE
        panel.translationX = -w.toFloat()
        panel.alpha = 0.6f
        panel.animate().translationX(0f).alpha(1f).setDuration(200).start()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        view.post { if (fromController) focusFirst() else closeButton.requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        handler.removeCallbacks(applyRunnable)
        closePicker(false)
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlField.windowToken, 0)
        urlField.clearFocus()
        panel.animate().translationX(-panel.width.toFloat()).alpha(0.6f).setDuration(160).withEndAction {
            if (!isOpen) view.visibility = View.GONE
        }.start()
        scrim.animate().alpha(0f).setDuration(160).start()
    }

    /** B / Back inside the panel: closes the key picker first, then the panel. */
    fun handleBack(): Boolean {
        if (picker != null) {
            closePicker(true)
            return true
        }
        return false
    }

    fun cycleTab(direction: Int) {
        val values = Tab.values()
        val next = values[(current.ordinal + direction + values.size) % values.size]
        selectTab(next, animate = true, focus = true)
    }

    /** Leaves touch mode and focuses the active tab, so the D-pad and A work right away. */
    fun focusFirst() {
        railItems[current]?.requestFocusFromTouch()
    }

    fun contains(v: View?): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === view) return true
            cur = cur.parent as? View
        }
        return false
    }

    fun setInsets(left: Int, top: Int, bottom: Int) {
        column.setPadding(left, top, 0, bottom)
    }

    fun onUrlChanged(url: String?) {
        if (!urlField.hasFocus()) urlField.setText(url ?: "")
        val secure = url?.startsWith("https://") == true
        urlIcon.setImageDrawable(Icon(context, if (secure) Icon.Kind.LOCK else Icon.Kind.GLOBE, Ui.TEXT_DIM, 16f))
        if (isOpen) {
            refreshNav()
            refreshSite()
        }
    }

    fun refresh() {
        urlField.setText(host.currentUrl() ?: "")
        for (r in refreshers) r()
        refreshNav()
        refreshSite()
        refreshThreadList()
        refreshStatus()
    }

    fun refreshStatus() {
        if (!isOpen) return
        statusBox.removeAllViews()
        for (item in host.statusItems()) statusBox.addView(statusRow(item))
        val crash = host.crashReport() ?: return
        statusBox.addView(statusRow(StatusItem("Last crash", CrashLog.stamp(crash.time) + "\n" + crash.summary, LEVEL_BAD)))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(chip("Copy crash details") { host.copyCrashReport() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(8f) })
        actions.addView(chip("Clear") {
            host.clearCrashReport()
            refreshStatus()
            railItems[Tab.STATUS]?.requestFocus()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusBox.addView(actions, lp(top = 8f))
    }

    // ============================================================================ top bar + rail

    private fun buildTopBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(10f), dp(12f), dp(10f))
        }
        backButton = iconButton(Icon.Kind.BACK, "Back") { host.goBack(); refreshNav() }
        forwardButton = iconButton(Icon.Kind.FORWARD, "Forward") { host.goForward(); refreshNav() }
        bar.addView(backButton)
        bar.addView(forwardButton)
        bar.addView(iconButton(Icon.Kind.RELOAD, "Reload") { host.reload(); host.requestCloseDrawer() })

        urlPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(context, Ui.SURFACE, 22f)
            setPadding(dp(14f), 0, dp(4f), 0)
        }
        urlIcon = ImageView(context).apply { setImageDrawable(Icon(context, Icon.Kind.GLOBE, Ui.TEXT_DIM, 16f)) }
        urlPill.addView(urlIcon, LinearLayout.LayoutParams(dp(16f), dp(16f)))
        urlField = EditText(context).apply {
            background = null
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            hint = "Search or type a game address"
            setHintTextColor(Ui.TEXT_FAINT)
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Ui.BODY
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setSelectAllOnFocus(true)
            setPadding(dp(10f), 0, dp(4f), 0)
            setOnEditorActionListener { v, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    host.navigate(v.text.toString())
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, has ->
                urlPill.background = if (has) Ui.rounded(context, Ui.SURFACE_HI, 22f, Ui.ACCENT, 1.5f)
                else Ui.rounded(context, Ui.SURFACE, 22f)
            }
        }
        urlPill.addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        urlPill.addView(iconButton(Icon.Kind.CHEVRON, "Go", 36f) { host.navigate(urlField.text.toString()) })
        bar.addView(urlPill, LinearLayout.LayoutParams(0, dp(44f), 1f).also {
            it.marginStart = dp(8f)
            it.marginEnd = dp(8f)
        })
        closeButton = iconButton(Icon.Kind.CLOSE, "Close menu") { host.requestCloseDrawer() }
        bar.addView(closeButton)
        return bar
    }

    private fun buildRail(): View {
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        for (tab in Tab.values()) {
            val item = railItem(tab.icon, tab.label) { selectTab(tab, animate = true, focus = false) }
            railItems[tab] = item
            rail.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54f)).also {
                it.bottomMargin = dp(3f)
            })
        }
        // Scrolls on very short screens instead of cutting tabs off.
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Ui.RAIL)
            addView(rail)
        }
    }

    private fun railItem(kind: Icon.Kind, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.railItem(context, false)
            isFocusable = true
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(ImageView(context).apply { setImageDrawable(Icon(context, kind, Ui.TEXT_DIM, 21f)) },
                LinearLayout.LayoutParams(dp(21f), dp(21f)))
            addView(TextView(context).apply {
                text = label
                setTextColor(Ui.TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Ui.LABEL
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(3f)
            })
        }

    private fun selectTab(tab: Tab, animate: Boolean, focus: Boolean) {
        closePicker(false)
        val previous = current
        current = tab
        for ((t, item) in railItems) {
            val sel = t == tab
            item.background = Ui.railItem(context, sel)
            ((item.getChildAt(0) as ImageView).drawable as? Icon)?.color = if (sel) Ui.TEXT else Ui.TEXT_DIM
            (item.getChildAt(1) as TextView).setTextColor(if (sel) Ui.TEXT else Ui.TEXT_DIM)
        }
        for ((t, page) in pages) {
            if (t != tab) {
                page.visibility = View.GONE
                continue
            }
            page.visibility = View.VISIBLE
            if (animate && t != previous) {
                page.alpha = 0f
                page.translationY = dp(10f).toFloat()
                page.animate().alpha(1f).translationY(0f).setDuration(170).start()
            }
        }
        if (tab == Tab.STATUS) refreshStatus()
        if (focus) railItems[tab]?.requestFocus()
    }

    // ============================================================================ pages

    private fun buildPages() {
        for (tab in Tab.values()) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18f), dp(14f), dp(22f), dp(28f))
            }
            when (tab) {
                Tab.GAMES -> buildGames(col)
                Tab.CONTROLS -> buildControls(col)
                Tab.DISPLAY -> buildDisplay(col)
                Tab.BUTTONS -> buildButtons(col)
                Tab.STATUS -> buildStatus(col)
            }
            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(col)
                visibility = View.GONE
            }
            pages[tab] = scroll
            pagesHost.addView(scroll, matchParent())
        }
    }

    private fun buildGames(col: LinearLayout) {
        pageTitle(col, "Games", null)
        siteBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SURFACE, 18f, Ui.HAIRLINE, 1f)
            setPadding(dp(14f), dp(14f), dp(14f), dp(10f))
        }
        col.addView(siteBox, lp(top = 12f))
        section(col, "Game hubs")
        var row: LinearLayout? = null
        BOOKMARKS.forEachIndexed { i, b ->
            if (i % 2 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, lp(top = 8f))
            }
            row?.addView(tile(b), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                if (i % 2 == 0) it.marginEnd = dp(8f)
            })
        }
        if (BOOKMARKS.size % 2 == 1) row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun refreshSite() {
        siteBox.removeAllViews()
        val uri = host.currentUrl()?.let { Uri.parse(it) }
        val siteHost = uri?.host
        if (uri == null || siteHost.isNullOrEmpty()) {
            siteBox.addView(body("Nothing open yet. Pick a game hub below or type an address above."))
            return
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(context).apply {
            setImageDrawable(Icon(context, if (uri.scheme == "https") Icon.Kind.LOCK else Icon.Kind.GLOBE, Ui.TEXT_DIM, 18f))
        }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        head.addView(TextView(context).apply {
            text = siteHost
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Ui.TITLE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(10f) })
        siteBox.addView(head)
        val path = (uri.encodedPath ?: "") + (uri.encodedQuery?.let { "?$it" } ?: "")
        if (path.length > 1) {
            siteBox.addView(TextView(context).apply {
                text = path
                setTextColor(Ui.TEXT_FAINT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, lp(top = 2f))
        }

        val redirect = host.siteRedirectState()
        val builtIn = redirect == SITE_BUILT_IN
        siteBox.addView(toggleRow(
            "Allow redirects and pop-ups",
            if (builtIn) "Built-in game site, always allowed" else "When off, only links you tap can leave this site",
            get = { redirect != SITE_SHIELDED },
            set = { host.setCurrentSiteAllowed(it); refreshSite() },
            enabled = !builtIn,
            register = false,
            flat = true
        ), lp(top = 10f))

        val threadsOk = host.threadsSupported()
        siteBox.addView(toggleRow(
            "Multithreading",
            if (threadsOk) "Gives the site shared memory for multithreaded games. Reloads the page."
            else "Needs a newer Android System WebView. Update it from the Play Store.",
            get = { threadsOk && host.isThreadsEnabledForCurrentSite() },
            set = { host.setThreadsForCurrentSite(it); refreshSite(); refreshThreadList() },
            enabled = threadsOk,
            register = false,
            flat = true
        ))
    }

    private fun buildControls(col: LinearLayout) {
        pageTitle(col, "Controls", null)
        section(col, "Controller output")
        val modes = listOf(
            Triple(Prefs.MODE_KBM, "Keyboard and mouse", "Sticks become WASD and mouse look. Best for shooters and anything with menus."),
            Triple(Prefs.MODE_PAD, "Xbox controller", "The game sees an XInput gamepad. Use it for games with built-in controller support."),
            Triple(Prefs.MODE_HYBRID, "Both", "Sends keyboard, mouse and gamepad input at the same time.")
        )
        for ((value, title, desc) in modes) {
            col.addView(optionCard(title, desc, { prefs.mode == value }) {
                prefs.mode = value
                host.onSettingsChanged(false)
                for (r in refreshers) r()
            }, lp(top = 8f))
        }

        section(col, "Cursor and touch")
        col.addView(toggleRow("Mouse cursor", "Also toggles with L3 + R3",
            get = { host.isCursorEnabled() }, set = { host.setCursorEnabled(it) }), lp(top = 8f))
        col.addView(toggleRow("Show the cursor in menus", "Appears whenever the game lets go of the mouse",
            get = { prefs.autoCursor }, set = { prefs.autoCursor = it; host.onSettingsChanged(false) }), lp(top = 6f))
        col.addView(toggleRow("On-screen gamepad", "Touch controls drawn over the game",
            get = { prefs.touchGamepad }, set = { prefs.touchGamepad = it; host.onSettingsChanged(false) }), lp(top = 6f))
        col.addView(toggleRow("D-pad sends arrow keys", "When off, the D-pad sends 1, 2, 3 and 4",
            get = { prefs.dpadArrows }, set = { prefs.dpadArrows = it; host.onSettingsChanged(false) }), lp(top = 6f))

        section(col, "Feel")
        col.addView(slider("Look sensitivity", 0.1f, 4f, 0.05f, { prefs.lookSensitivity }, { prefs.lookSensitivity = it },
            { String.format(Locale.US, "%.2f×", it) }), lp(top = 8f))
        col.addView(slider("Stick deadzone", 0.02f, 0.5f, 0.01f, { prefs.deadzone }, { prefs.deadzone = it },
            { String.format(Locale.US, "%d%%", (it * 100).roundToInt()) }), lp(top = 6f))
        col.addView(slider("Cursor speed", 0.3f, 3f, 0.05f, { prefs.cursorSpeed }, { prefs.cursorSpeed = it },
            { String.format(Locale.US, "%.2f×", it) }), lp(top = 6f))
    }

    private fun buildDisplay(col: LinearLayout) {
        pageTitle(col, "Display", null)
        section(col, "Resolution")
        col.addView(segmented(
            listOf("1.0" to "100%", "0.75" to "75%", "0.5" to "50%", "0.35" to "35%"),
            { trimScale(prefs.resolutionScale) },
            { prefs.resolutionScale = it.toFloat(); host.onSettingsChanged(true) }
        ), lp(top = 8f))
        note(col, "The game draws fewer pixels and the screen scales them up. 50% is four times fewer pixels than 100%: the biggest speed-up there is on a phone. Reloads the page.")

        section(col, "Page width")
        col.addView(segmented(
            listOf("0" to "Phone", "1280" to "1280 px", "1600" to "1600 px"),
            { prefs.desktopViewport.toString() },
            { prefs.desktopViewport = it.toInt(); host.onSettingsChanged(true) }
        ), lp(top = 8f))
        note(col, "Lays pages out like a desktop window of that width. Reloads the page.")

        section(col, "Browser identity")
        col.addView(segmented(
            listOf(Prefs.IDENTITY_CHROME to "Chrome", Prefs.IDENTITY_EDGE to "Edge",
                Prefs.IDENTITY_FIREFOX to "Firefox", Prefs.IDENTITY_ANDROID to "Android"),
            { prefs.identity },
            { prefs.identity = it; host.onIdentityChanged() }
        ), lp(top = 8f))
        note(col, "What sites are told this browser is, all on Windows 11 except Android. Chrome matches the real engine and works best. Reloads the page.")

        section(col, "Graphics engine")
        val backendText = body("")
        refreshers.add { backendText.text = "Now: " + host.graphicsBackendText() }
        col.addView(backendText, lp(top = 6f))
        note(col, "WebGL runs on the GPU driver that Android System WebView picks; this app cannot switch it. " +
            "WebView DevTools (part of Android System WebView) has a Flags page where \"WebViewVulkan\" turns on Vulkan compositing " +
            "for every WebView app. It can crash on unsupported devices, and it does not make the game itself draw with Vulkan.")
        col.addView(chip("Open WebView DevTools") { host.openWebViewDevTools() }, lp(top = 8f))

        section(col, "Multithreading")
        col.addView(toggleRow("Turn on when a site asks", "Sites that request shared memory get it and reload once",
            get = { prefs.autoThreads }, set = { prefs.autoThreads = it }), lp(top = 8f))
        threadListBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(threadListBox, lp(top = 6f))
    }

    private fun refreshThreadList() {
        threadListBox.removeAllViews()
        val origins = host.threadOrigins().sorted()
        if (origins.isEmpty()) {
            threadListBox.addView(body("No sites have multithreading yet."))
            return
        }
        for (origin in origins) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(2f), dp(4f), dp(2f))
                background = Ui.rounded(context, Ui.SURFACE, 14f)
            }
            row.addView(ImageView(context).apply { setImageDrawable(Icon(context, Icon.Kind.THREADS, Ui.ACCENT, 18f)) },
                LinearLayout.LayoutParams(dp(18f), dp(18f)))
            row.addView(TextView(context).apply {
                text = Uri.parse(origin).host ?: origin
                setTextColor(Ui.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(10f) })
            row.addView(iconButton(Icon.Kind.CLOSE, "Remove " + origin, 40f) {
                host.removeThreadOrigin(origin)
                refreshThreadList()
                refreshSite()
            })
            threadListBox.addView(row, lp(top = 6f))
        }
    }

    private fun buildButtons(col: LinearLayout) {
        pageTitle(col, "Buttons", "Keyboard and mouse mode. Choose a binding to change it.")
        for (i in 0 until ControllerManager.BUTTONS) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val glyphBox = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            glyphBox.addView(buttonGlyph(i))
            glyphBox.addView(TextView(context).apply {
                text = PLAYSTATION_NAMES[i]
                setTextColor(Ui.TEXT_FAINT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(8f)
            })
            row.addView(glyphBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f))
            val chip = chip("") { openPicker(i) }
            chip.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            bindChips[i] = chip
            row.addView(chip, LinearLayout.LayoutParams(0, dp(42f), 1.15f))
            refreshers.add {
                val id = prefs.binds[i] ?: "None"
                val fixed = i == 16 || (i in 12..15 && prefs.dpadArrows)
                chip.text = when {
                    i in 12..15 && prefs.dpadArrows -> "Arrow keys"
                    i == 16 -> "Opens this menu"
                    else -> BindTargets.label(id)
                }
                chip.isEnabled = !fixed
                chip.alpha = if (fixed) 0.55f else 1f
            }
            col.addView(row, lp(top = 6f))
        }
        col.addView(chip("Reset all bindings") {
            prefs.resetBinds()
            for (r in refreshers) r()
            host.onSettingsChanged(false)
        }, lp(top = 14f))
    }

    private fun buildStatus(col: LinearLayout) {
        pageTitle(col, "Status", null)
        statusBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(statusBox, lp(top = 6f))
        section(col, "Shortcuts")
        val shortcuts = listOf(
            listOf("Select", "Start") to "Open or close this menu",
            listOf("L3", "R3") to "Show or hide the mouse cursor",
            listOf("Start") to "Tap for Esc, hold for Enter",
            listOf("LB", "RB") to "Switch tabs in this menu",
            listOf("B") to "Back out of this menu"
        )
        for ((keys, what) in shortcuts) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, dp(6f))
            }
            val glyphs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            keys.forEachIndexed { k, key ->
                if (k > 0) glyphs.addView(TextView(context).apply {
                    text = "+"
                    setTextColor(Ui.TEXT_FAINT)
                    setPadding(dp(5f), 0, dp(5f), 0)
                })
                glyphs.addView(glyph(key, small = false))
            }
            row.addView(glyphs, LinearLayout.LayoutParams(dp(150f), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(body(what), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(row)
        }
        col.addView(chip("Close the app") { host.exitApp() }, lp(top = 18f))
    }

    // ============================================================================ key picker

    private fun openPicker(index: Int) {
        if (index == 16 || (index in 12..15 && prefs.dpadArrows)) return
        closePicker(false)
        pickerIndex = index
        val current = prefs.binds[index] ?: "None"
        val overlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.NIGHT)
            isClickable = true
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(10f), dp(18f), dp(6f))
        }
        head.addView(iconButton(Icon.Kind.BACK, "Back to buttons") { closePicker(true) })
        head.addView(TextView(context).apply {
            text = "Bind"
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Ui.DISPLAY
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(6f)
            it.marginEnd = dp(10f)
        })
        head.addView(buttonGlyph(index))
        overlay.addView(head)

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(4f), dp(22f), dp(28f))
        }
        var focusTarget: View? = null
        val groups = listOf(
            "Mouse and actions" to BindTargets.ALL.take(9),
            "Keyboard" to BindTargets.ALL.drop(9)
        )
        for ((title, targets) in groups) {
            section(grid, title)
            var row: LinearLayout? = null
            targets.forEachIndexed { i, t ->
                if (i % 3 == 0) {
                    row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row, lp(top = 6f))
                }
                val selected = t.id == current
                val c = chip(t.label, selected) {
                    prefs.setBind(index, t.id)
                    for (r in refreshers) r()
                    host.onSettingsChanged(false)
                    closePicker(true)
                }
                c.maxLines = 2
                if (selected) focusTarget = c
                row?.addView(c, LinearLayout.LayoutParams(0, dp(46f), 1f).also { it.marginEnd = dp(6f) })
            }
            val leftover = targets.size % 3
            if (leftover != 0) repeat(3 - leftover) {
                row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).also { it.marginEnd = dp(6f) })
            }
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(grid)
        }
        overlay.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pagesHost.addView(overlay, matchParent())
        picker = overlay
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(140).start()
        val target = focusTarget
        overlay.post { (target ?: head.getChildAt(0)).requestFocus() }
    }

    private fun closePicker(restoreFocus: Boolean) {
        val p = picker ?: return
        picker = null
        pagesHost.removeView(p)
        if (restoreFocus) bindChips[pickerIndex]?.requestFocus()
        pickerIndex = -1
    }

    // ============================================================================ widgets

    private fun dp(v: Float): Int = Ui.dp(context, v)

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun lp(top: Float = 0f) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dp(top) }

    private fun pageTitle(col: LinearLayout, title: String, subtitle: String?) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = title
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            typeface = Ui.DISPLAY
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Controller hint: the shoulder buttons flip between these tabs.
        row.addView(glyph("LB", small = true))
        row.addView(glyph("RB", small = true).also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4f)
        })
        col.addView(row)
        if (subtitle != null) col.addView(body(subtitle), lp(top = 2f))
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(TextView(context).apply {
            text = title
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Ui.TITLE
        }, lp(top = 22f))
    }

    private fun note(col: LinearLayout, text: String) {
        col.addView(TextView(context).apply {
            this.text = text
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.15f)
        }, lp(top = 6f))
    }

    private fun body(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Ui.TEXT_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(0f, 1.15f)
    }

    private fun iconButton(kind: Icon.Kind, description: String, sizeDp: Float = 44f, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageDrawable(Icon(context, kind, Ui.TEXT, 20f))
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.focusable(context, radiusDp = sizeDp / 2f, base = 0x00000000)
            contentDescription = description
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    private fun chip(label: String, selected: Boolean = false, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(if (selected) Color.WHITE else Ui.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Ui.LABEL
        gravity = Gravity.CENTER
        minHeight = dp(40f)
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        background = Ui.focusable(context, selected, 12f)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tile(b: Bookmark): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.focusable(context, radiusDp = 16f)
            setPadding(dp(10f), dp(10f), dp(12f), dp(10f))
            isFocusable = true
            isClickable = true
            contentDescription = b.title
            setOnClickListener { host.navigate(b.url) }
        }
        row.addView(TextView(context).apply {
            text = b.monogram
            typeface = Ui.DISPLAY
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.monogram(context, b.url)
        }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = b.title
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Ui.LABEL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(context).apply {
            text = Uri.parse(b.url).host ?: b.url
            setTextColor(Ui.TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(12f) })
        return row
    }

    private fun toggleRow(
        title: String,
        subtitle: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        enabled: Boolean = true,
        register: Boolean = true,
        flat: Boolean = false
    ): View {
        val sw = PillSwitch(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.focusable(context, radiusDp = 14f, base = if (flat) 0x00000000 else Ui.SURFACE)
            setPadding(dp(if (flat) 4f else 14f), dp(10f), dp(if (flat) 2f else 12f), dp(10f))
            isFocusable = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.55f
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = title
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Ui.LABEL
        })
        texts.addView(TextView(context).apply {
            text = subtitle
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(1f)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(12f) })
        row.addView(sw)
        sw.isEnabled = enabled
        // Registered rows get their state from refresh() when the panel opens. Reading it while the
        // panel is being built would run host code before the activity has finished starting.
        if (register) refreshers.add { sw.setOn(get(), false) } else sw.setOn(get(), false)
        row.setOnClickListener {
            val next = !sw.isOn
            sw.setOn(next, true)
            set(next)
        }
        return row
    }

    private fun optionCard(title: String, description: String, selected: () -> Boolean, onPick: () -> Unit): View {
        val check = ImageView(context).apply { setImageDrawable(Icon(context, Icon.Kind.CHECK, Ui.ACCENT, 20f)) }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            isFocusable = true
            isClickable = true
            setOnClickListener { onPick() }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = title
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.LABEL
        })
        texts.addView(TextView(context).apply {
            text = description
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(2f)
        })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(check, LinearLayout.LayoutParams(dp(20f), dp(20f)).also { it.marginStart = dp(10f) })
        refreshers.add {
            val sel = selected()
            card.background = Ui.optionCard(context, sel)
            check.visibility = if (sel) View.VISIBLE else View.INVISIBLE
        }
        return card
    }

    private fun segmented(options: List<Pair<String, String>>, selected: () -> String, onPick: (String) -> Unit): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(context, Ui.SURFACE, 14f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        val items = ArrayList<Pair<String, TextView>>()
        options.forEachIndexed { i, (value, label) ->
            val t = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Ui.LABEL
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    onPick(value)
                    for (r in refreshers) r()
                }
            }
            items.add(value to t)
            bar.addView(t, LinearLayout.LayoutParams(0, dp(40f), 1f).also { if (i > 0) it.marginStart = dp(4f) })
        }
        refreshers.add {
            val sel = selected()
            for ((value, t) in items) {
                val on = value == sel
                t.background = Ui.focusable(context, on, 10f, 0x00000000)
                t.setTextColor(if (on) Color.WHITE else Ui.TEXT_DIM)
            }
        }
        return bar
    }

    private fun slider(
        label: String, min: Float, max: Float, step: Float,
        get: () -> Float, set: (Float) -> Unit, format: (Float) -> String
    ): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SURFACE, 14f)
            setPadding(dp(14f), dp(10f), dp(8f), dp(4f))
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(context).apply {
            text = label
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Ui.LABEL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(context).apply {
            setTextColor(Ui.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Ui.TITLE
            setPadding(0, 0, dp(8f), 0)
        }
        head.addView(value)
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(context).apply {
            this.max = steps
            isFocusable = true
            background = Ui.focusable(context, radiusDp = 12f, base = 0x00000000)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                value.text = format(v)
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
            value.text = format(v)
        }
        box.addView(head)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))
        return box
    }

    private fun statusRow(item: StatusItem): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = Ui.rounded(context, Ui.SURFACE, 14f)
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when (item.level) {
                        LEVEL_OK -> Ui.OK
                        LEVEL_WARN -> Ui.WARN
                        LEVEL_BAD -> Ui.BAD
                        else -> Ui.TEXT_FAINT
                    }
                )
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(8f), dp(8f)).also { it.topMargin = dp(6f) })
        row.addView(TextView(context).apply {
            text = item.label
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, LinearLayout.LayoutParams(dp(118f), ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(12f) })
        row.addView(TextView(context).apply {
            text = item.value
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.layoutParams = lp(top = 6f)
        return row
    }

    /** Controller button badge: ABXY in their Xbox colours, everything else as an outlined pill. */
    private fun buttonGlyph(index: Int): TextView = glyph(GLYPH_LABELS[index], small = false, face = FACE_COLORS[index])

    private fun glyph(label: String, small: Boolean, face: Int = 0): TextView = TextView(context).apply {
        text = label
        typeface = Ui.DISPLAY
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 10.5f else 13f)
        gravity = Gravity.CENTER
        val h = dp(if (small) 20f else 28f)
        minHeight = h
        minWidth = h
        if (face != 0) {
            setTextColor(face)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((face and 0x00FFFFFF) or 0x2E000000)
                setStroke(dp(1.5f), face)
            }
            setPadding(0, 0, 0, 0)
        } else {
            setTextColor(if (small) Ui.TEXT_DIM else Ui.TEXT)
            background = Ui.rounded(context, 0x00000000, if (small) 6f else 9f, Ui.TEXT_FAINT, 1f)
            setPadding(dp(if (small) 5f else 8f), 0, dp(if (small) 5f else 8f), 0)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h)
    }

    private fun refreshNav() {
        backButton.isEnabled = host.canGoBack()
        backButton.alpha = if (backButton.isEnabled) 1f else 0.35f
        forwardButton.isEnabled = host.canGoForward()
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
    }

    private fun trimScale(v: Float): String = when {
        v >= 0.99f -> "1.0"
        v >= 0.74f -> "0.75"
        v >= 0.49f -> "0.5"
        else -> "0.35"
    }

    companion object {
        const val SITE_NONE = 0
        const val SITE_BUILT_IN = 1
        const val SITE_ALLOWED = 2
        const val SITE_SHIELDED = 3

        const val LEVEL_OK = 0
        const val LEVEL_WARN = 1
        const val LEVEL_BAD = 2
        const val LEVEL_INFO = 3

        val BOOKMARKS = listOf(
            Bookmark("Radiant Catalog", "RC", "https://radiantoptimizer.games"),
            Bookmark("Radiant Full Index", "RI", "https://radiantoptimizer.lol"),
            Bookmark("BO1 Zombies Hub", "BO", "https://vel.gg/bo1z"),
            Bookmark("The Simpsons: Hit & Run", "SH", "https://shar-wasm.cjoseph.workers.dev/?skipmovie"),
            Bookmark("Quake 1 Arena", "Q1", "https://q1.pieter.com"),
            Bookmark("Quake 2 Arena", "Q2", "https://q2.pieter.com"),
            Bookmark("Return to Castle Wolfenstein", "RW", "https://rtcw.pieter.com"),
            Bookmark("Urban Terror", "UT", "https://ut.pieter.com"),
            Bookmark("CS Surf", "CS", "https://surfd.net"),
            Bookmark("WASM Arcade", "WA", "https://wasmarcade.com"),
            Bookmark("WebXash (Half-Life, CS 1.6)", "HL", "https://x8bitrain.github.io/webXash/"),
            Bookmark("Diablo Web", "DI", "https://johnimril.github.io/diablo_web/")
        )

        private val GLYPH_LABELS = arrayOf(
            "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "Select", "Start", "L3", "R3",
            "D↑", "D↓", "D←", "D→", "Guide"
        )

        private val PLAYSTATION_NAMES = arrayOf(
            "Cross", "Circle", "Square", "Triangle", "L1", "R1", "L2", "R2", "Share", "Options",
            "Left stick", "Right stick", "Up", "Down", "Left", "Right", "PS"
        )

        private val FACE_COLORS = intArrayOf(Ui.OK, Ui.BAD, Ui.FACE_X, Ui.WARN, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
