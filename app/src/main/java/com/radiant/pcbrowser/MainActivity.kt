package com.radiant.pcbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

class MainActivity : ComponentActivity(),
    WebBridge.Host,
    ControllerManager.Listener,
    CommandDrawer.Host,
    TouchGamepadView.Listener,
    VirtualCursor.Host {

    private lateinit var prefs: Prefs
    private lateinit var shield: NavigationShield
    private lateinit var root: FrameLayout
    private lateinit var touchPad: TouchGamepadView
    private lateinit var cursorView: CursorOverlayView
    private lateinit var cursor: VirtualCursor
    private lateinit var drawer: CommandDrawer
    private lateinit var menuButton: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerAllowOnce: TextView
    private lateinit var bannerAllowAlways: TextView
    private lateinit var controller: ControllerManager

    private var webView: WebView? = null
    private val bridge = WebBridge(this)
    private val ui = Handler(Looper.getMainLooper())

    private var shimSource = ""
    private var scriptHandler: ScriptHandler? = null
    private var docStartSupported = false
    private var messageListenerSupported = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private var chromeMajor = "131"
    private var chromeFull = "131.0.0.0"
    private var desktopUa = ""
    private var webViewVersion = "unknown"

    private var pageScale = 0f
    private var lockOwnerId = 0
    private var cursorOverride: Boolean? = null
    private var glStatus = ""
    private var threadStatus = ""
    private var blockedUrl: String? = null
    private var windowFocused = false
    private val crashTimes = ArrayDeque<Long>()

    private var cursorTouchTarget: View? = null
    private var cursorTouchDownTime = 0L

    private var mouseDx = 0f
    private var mouseDy = 0f
    private var mouseWheel = 0f
    private var mouseButtons = 0
    private var mouseFlushPosted = false
    private var touchpadLastX = Float.NaN
    private var touchpadLastY = Float.NaN
    private val popupProbes = HashSet<WebView>()

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private val mouseFlush = Runnable {
        mouseFlushPosted = false
        if (lockOwnerId != 0) {
            val msg = JSONObject()
                .put("t", "mouse")
                .put("dx", mouseDx.toDouble())
                .put("dy", mouseDy.toDouble())
                .put("b", mouseButtons)
                .put("w", mouseWheel.toDouble())
            bridge.send(lockOwnerId, msg.toString())
        }
        mouseDx = 0f
        mouseDy = 0f
        mouseWheel = 0f
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback
            fileCallback = null
            if (callback != null) {
                val uris = if (result.resultCode == Activity.RESULT_OK) parseUris(result.data) else null
                callback.onReceiveValue(uris)
            }
        }

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        prefs = Prefs(this)
        shield = NavigationShield(prefs)
        shimSource = resources.openRawResource(R.raw.pc_engine_shim).bufferedReader().use { it.readText() }
        resolveEngineVersions()

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        createWebView()

        touchPad = TouchGamepadView(this, this)
        touchPad.visibility = if (prefs.touchGamepad) View.VISIBLE else View.GONE
        touchPad.lookAsStick = prefs.mode == Prefs.MODE_PAD
        root.addView(touchPad, matchParent())

        banner = buildBanner()
        root.addView(banner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).also { it.topMargin = dp(10f) })

        menuButton = buildMenuButton()
        root.addView(menuButton, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.START).also {
            it.leftMargin = dp(8f)
            it.topMargin = dp(8f)
        })

        drawer = CommandDrawer(this, prefs, this)
        root.addView(drawer.view, matchParent())

        cursorView = CursorOverlayView(this)
        root.addView(cursorView, FrameLayout.LayoutParams(cursorView.sizePx, cursorView.sizePx))
        cursor = VirtualCursor(this, cursorView)

        controller = ControllerManager(this, this)
        controller.setVirtualPadEnabled(prefs.touchGamepad)

        root.setOnApplyWindowInsetsListener { _, insets ->
            applyCutout(insets)
            insets
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isOpen) closeDrawer() else openDrawer(fromController = false)
            }
        })

        hideSystemBars()
        loadTrusted(intent?.dataString ?: prefs.lastUrl ?: DEFAULT_URL)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        controller.start()
        hideSystemBars()
    }

    override fun onPause() {
        cursor.cancel()
        controller.stop()
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        for (p in popupProbes.toList()) destroyProbe(p)
        scriptHandler = null
        webView?.let {
            root.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let {
            loadTrusted(it)
            closeDrawer()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) {
            hideSystemBars()
            if (!drawer.isOpen) webView?.requestFocus()
        }
        updatePointerCapture()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::controller.isInitialized && controller.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (::controller.isInitialized && controller.handleMotionEvent(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    // =====================================================================================
    // WebView setup
    // =====================================================================================

    private fun resolveEngineVersions() {
        val defaultUa = try {
            WebSettings.getDefaultUserAgent(this)
        } catch (_: Throwable) {
            ""
        }
        Regex("Chrome/(\\d+)\\.([\\d.]+)").find(defaultUa)?.let {
            chromeMajor = it.groupValues[1]
            chromeFull = it.groupValues[1] + "." + it.groupValues[2]
        }
        WebViewCompat.getCurrentWebViewPackage(this)?.let { webViewVersion = it.packageName + " " + it.versionName }
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.0.0 Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.BLACK)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)          // required for the desktop-width overview scale...
        s.builtInZoomControls = false   // ...while pinch / double-tap zoom stay off (no 300 ms tap delay)
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.offscreenPreRaster = true
        s.textZoom = 100
        s.userAgentString = desktopUa
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            try {
                WebSettingsCompat.setUserAgentMetadata(s, buildUaMetadata())
            } catch (t: Throwable) {
                Log.w(TAG, "UA metadata not applied", t)
            }
        }

        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        wv.setOnCapturedPointerListener { _, event -> onCapturedPointer(event) }
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        messageListenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (messageListenerSupported) {
            WebViewCompat.addWebMessageListener(wv, WebBridge.JS_OBJECT_NAME, setOf("*"), bridge)
        }
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartSupported) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, buildScript(), setOf("*"))
        }

        root.addView(wv, 0, matchParent())
        webView = wv
    }

    private fun buildUaMetadata(): UserAgentMetadata {
        fun brand(name: String, major: String, full: String): UserAgentMetadata.BrandVersion =
            UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(major).setFullVersion(full).build()
        return UserAgentMetadata.Builder()
            .setBrandVersionList(
                listOf(
                    brand("Google Chrome", chromeMajor, chromeFull),
                    brand("Chromium", chromeMajor, chromeFull),
                    brand("Not_A Brand", "24", "24.0.0.0")
                )
            )
            .setFullVersion(chromeFull)
            .setPlatform("Windows")
            .setPlatformVersion("15.0.0")
            .setArchitecture("x86")
            .setModel("")
            .setMobile(false)
            .setBitness(64)
            .setWow64(false)
            .build()
    }

    private fun buildScript(): String {
        val boot = JSONObject()
            .put("ua", desktopUa)
            .put("chromeMajor", chromeMajor)
            .put("chromeFull", chromeFull)
            .put("bridge", WebBridge.JS_OBJECT_NAME)
            .put("settings", prefs.settingsJson())
            .put("scale", pageScale.toDouble())
        return "window.__RADIANT_BOOT__=" + boot.toString() + ";\n" + shimSource
    }

    /** New frames get the new settings at document start; existing frames got a "cfg" message. */
    private fun reinstallScript() {
        val wv = webView ?: return
        if (!docStartSupported) return
        scriptHandler?.remove()
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, buildScript(), setOf("*"))
    }

    private fun loadTrusted(url: String) {
        shield.trustNextNavigation()
        Uri.parse(url).host?.let { shield.allowForSession(it) }
        webView?.loadUrl(url)
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase(Locale.ROOT) ?: return true
            if (scheme == "http" || scheme == "https") {
                if (!request.isForMainFrame) return false
                val verdict = shield.decide(url, request.hasGesture(), request.isRedirect, view.url)
                if (verdict == NavigationShield.Verdict.ALLOW) return false
                showBlocked(url, popup = false)
                return true
            }
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
            // intent:, market:, tel: ... are almost always ad redirects inside game portals.
            showBlocked(url, popup = false)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host ?: return null
            if (shield.isAdHost(host)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            lockOwnerId = 0
            cursorOverride = null
            glStatus = ""
            if (!docStartSupported) view.evaluateJavascript(buildScript(), null)
            updateCursorState()
            updatePointerCapture()
            drawer.onUrlChanged(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) prefs.lastUrl = url
            drawer.onUrlChanged(url)
            if (!drawer.isOpen && !view.hasFocus()) view.requestFocus()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            drawer.onUrlChanged(url)
        }

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            pageScale = newScale
            bridge.broadcast(JSONObject().put("t", "scale").put("v", newScale.toDouble()).toString())
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recoverRenderer(view, detail.didCrash())
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val accepts = fileChooserParams.acceptTypes
                .flatMap { it.split(',') }
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
            // Game files (.iso .zip .wad .mpq .pak .bsp ...) have no reliable MIME type, so extension
            // filters must not narrow the picker: show everything and let the game validate.
            val mimes = accepts.filter { it.contains('/') && it != "*/*" }
            val hasExtensionFilter = accepts.any { it.startsWith('.') }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                if (mimes.isNotEmpty() && !hasExtensionFilter) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            }
            try {
                fileChooserLauncher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                filePathCallback.onReceiveValue(null)
                toast("No file picker available on this device")
            }
            return true
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) {
                showBlockedText("Blocked a pop-up")
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val probe = WebView(this@MainActivity)
            popupProbes.add(probe)
            var decided = false
            fun decide(target: String) {
                if (decided) return
                decided = true
                ui.post { destroyProbe(probe) }
                onPopupRequested(target)
            }
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    decide(request.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") decide(url)
                }

                // All WebViews share one renderer; the default (false) would kill the whole app.
                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    decided = true
                    ui.post { destroyProbe(probe) }
                    return true
                }
            }
            ui.postDelayed({
                decided = true
                destroyProbe(probe)
            }, 8_000L)
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) Log.d(TAG, "console: " + consoleMessage.message())
            return super.onConsoleMessage(consoleMessage)
        }
    }

    private fun destroyProbe(probe: WebView) {
        if (!popupProbes.remove(probe)) return
        probe.stopLoading()
        probe.destroy()
    }

    private fun onPopupRequested(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            showBlocked(uri, popup = true)
            return
        }
        if (shield.decidePopup(uri, webView?.url) == NavigationShield.Verdict.ALLOW) {
            loadTrusted(url)
        } else {
            showBlocked(uri, popup = true)
        }
    }

    private fun recoverRenderer(dead: WebView, crashed: Boolean) {
        val url = dead.url ?: prefs.lastUrl
        if (webView === dead) webView = null
        root.removeView(dead)
        dead.destroy()
        scriptHandler = null
        bridge.clear()
        lockOwnerId = 0
        glStatus = ""
        cursor.cancel()
        val now = SystemClock.uptimeMillis()
        crashTimes.addLast(now)
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > 60_000L) crashTimes.removeFirst()
        createWebView()
        updateCursorState()
        updatePointerCapture()
        if (crashTimes.size >= 3) {
            toast("This game keeps crashing the renderer (usually out of memory). Try 50% resolution.")
            openDrawer(fromController = false)
            return
        }
        toast(if (crashed) "Game renderer crashed - reloading" else "Android reclaimed the game's memory - reloading")
        url?.let { loadTrusted(it) }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("This kind of download can't be saved from here")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
            if (!mimeType.isNullOrEmpty()) request.setMimeType(mimeType)
            if (!userAgent.isNullOrEmpty()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            request.setTitle(name)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast("Downloading $name to Downloads")
        } catch (e: Exception) {
            toast("Download failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun parseUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        return data.data?.let { arrayOf(it) }
    }

    // =====================================================================================
    // Frame bridge
    // =====================================================================================

    override fun helloPayload(frame: WebBridge.Frame): JSONObject = JSONObject()
        .put("settings", prefs.settingsJson())
        .put("lock", lockOwnerId)
        .put("cursor", ::cursor.isInitialized && cursor.active)
        .put("scale", pageScale.toDouble())

    override fun onFrameMessage(frame: WebBridge.Frame, msg: JSONObject) {
        when (msg.optString("t")) {
            "lock" -> {
                val on = msg.optBoolean("on")
                if (on) {
                    lockOwnerId = frame.id
                    cursorOverride = null // the game captured the mouse: back to look mode
                } else if (lockOwnerId == frame.id) {
                    lockOwnerId = 0
                } else {
                    return
                }
                bridge.broadcast(JSONObject().put("t", "lockOwner").put("id", lockOwnerId).toString())
                updateCursorState()
                updatePointerCapture()
            }
            "gl" -> {
                glStatus = msg.optString("api").uppercase(Locale.ROOT) + " · " + msg.optString("renderer") +
                    " · S3TC " + msg.optString("s3tc")
                val sab = msg.optString("sab")
                if (sab.isNotEmpty()) threadStatus = sab
                drawer.refreshStatus()
            }
            "status" -> {
                if (frame.isMainFrame || threadStatus.isEmpty()) threadStatus = msg.optString("sab")
                drawer.refreshStatus()
            }
            "rumble" -> {
                val ms = msg.optDouble("ms", 0.0)
                controller.rumble(
                    msg.optInt("i"),
                    if (ms.isFinite()) ms.toLong().coerceIn(0L, 5_000L) else 0L,
                    msg.optDouble("s", 0.0).toFloat(),
                    msg.optDouble("w", 0.0).toFloat()
                )
            }
        }
    }

    override fun onFrameGone(frame: WebBridge.Frame) {
        if (frame.id == lockOwnerId) {
            lockOwnerId = 0
            bridge.broadcast(JSONObject().put("t", "lockOwner").put("id", 0).toString())
            updateCursorState()
            updatePointerCapture()
        }
    }

    // =====================================================================================
    // Controller routing
    // =====================================================================================

    override val isDrawerOpen: Boolean
        get() = ::drawer.isInitialized && drawer.isOpen

    override val isCursorActive: Boolean
        get() = ::cursor.isInitialized && cursor.active

    override fun onToggleDrawer() {
        if (drawer.isOpen) closeDrawer() else openDrawer(fromController = true)
    }

    override fun onToggleCursor() {
        setCursorEnabled(!cursor.active)
    }

    override fun onControllersChanged() {
        if (::cursor.isInitialized) updateCursorState()
        if (::drawer.isInitialized) drawer.refreshStatus()
    }

    override fun onPadState(json: String) {
        bridge.broadcast(json)
    }

    override fun onCursorFrame(dtSeconds: Float, state: ControllerManager.State) {
        cursor.frame(dtSeconds, state, prefs.cursorSpeed, prefs.deadzone)
    }

    override fun onCursorButton(index: Int, down: Boolean) {
        cursor.button(index, down)
    }

    override fun onDrawerButton(index: Int, down: Boolean) {
        if (down) return
        when (index) {
            0 -> {
                val f = currentFocus
                when {
                    f == null || !drawer.contains(f) -> drawer.focusFirst()
                    f is EditText -> getSystemService(InputMethodManager::class.java)?.showSoftInput(f, 0)
                    else -> f.performClick()
                }
            }
            1 -> closeDrawer()
        }
    }

    private fun autoCursorWanted(): Boolean {
        if (!prefs.autoCursor || prefs.mode != Prefs.MODE_KBM) return false
        if (lockOwnerId != 0) return false
        return controller.hasPhysicalController()
    }

    private fun updateCursorState() {
        val want = !drawer.isOpen && (cursorOverride ?: autoCursorWanted())
        if (want == cursor.active) return
        cursor.setActive(want)
        controller.markDirty()
        updatePointerCapture()
    }

    // =====================================================================================
    // Virtual cursor host: injects real input into the view hierarchy
    // =====================================================================================

    override val areaWidth: Int
        get() = root.width

    override val areaHeight: Int
        get() = root.height

    override fun cursorTouch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) {
            val target = pickTouchTarget(x, y)
            cursorTouchTarget = target
            cursorTouchDownTime = SystemClock.uptimeMillis()
        }
        val target = cursorTouchTarget ?: return
        val downTime = cursorTouchDownTime
        val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (end) cursorTouchTarget = null
        if (target === webView) {
            // Tell every frame a synthetic tap is coming so it can hide the touch nature of the
            // event, then deliver it slightly later (all steps share the delay, so order is kept).
            bridge.broadcast(if (end) TAP_ENDING_JSON else TAP_ACTIVE_JSON)
            ui.postDelayed({ dispatchTouch(target, action, x, y, downTime) }, TAP_DELAY_MS)
        } else {
            dispatchTouch(target, action, x, y, downTime)
        }
    }

    override fun cursorHover(x: Float, y: Float) {
        val wv = webView ?: return
        val ev = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x - wv.left, y - wv.top, 0f)
        wv.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun cursorScroll(x: Float, y: Float, amount: Float) {
        val wv = webView ?: return
        val ev = mouseEvent(MotionEvent.ACTION_SCROLL, x - wv.left, y - wv.top, amount)
        wv.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun cursorKey(code: String, down: Boolean) {
        bridge.broadcast(JSONObject().put("t", "key").put("code", code).put("down", down).toString())
    }

    private fun pickTouchTarget(x: Float, y: Float): View {
        if (banner.visibility == View.VISIBLE && hit(banner, x, y)) return banner
        if (menuButton.visibility == View.VISIBLE && hit(menuButton, x, y)) return menuButton
        return webView ?: root
    }

    private fun hit(v: View, x: Float, y: Float): Boolean =
        x >= v.left && x < v.right && y >= v.top && y < v.bottom

    private fun dispatchTouch(target: View, action: Int, x: Float, y: Float, downTime: Long) {
        if (target is WebView && target !== webView) return // renderer was recreated meanwhile
        val localX = x - target.left
        val localY = y - target.top
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = localX
            this.y = localY
            pressure = 1f
            size = 0.04f
        })
        val ev = MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun mouseEvent(action: Int, localX: Float, localY: Float, vscroll: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = localX
            this.y = localY
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0)
    }

    // =====================================================================================
    // On-screen gamepad
    // =====================================================================================

    override fun onTouchPadState(buttons: FloatArray, axes: FloatArray) {
        controller.updateVirtualPad(buttons, axes)
    }

    override fun onTouchLook(dxDp: Float, dyDp: Float) {
        if (cursor.active) {
            val d = resources.displayMetrics.density * 1.5f
            cursor.nudge(dxDp * d, dyDp * d)
        } else {
            bridge.broadcast(
                JSONObject().put("t", "look")
                    .put("dx", (dxDp * TOUCH_LOOK_SCALE).toDouble())
                    .put("dy", (dyDp * TOUCH_LOOK_SCALE).toDouble())
                    .toString()
            )
        }
    }

    override fun onTouchTap(x: Float, y: Float) {
        cursorTouch(MotionEvent.ACTION_DOWN, x, y)
        cursorTouch(MotionEvent.ACTION_UP, x, y)
    }

    // =====================================================================================
    // Physical mouse: real pointer capture while the game holds the (emulated) pointer lock
    // =====================================================================================

    private fun hasExternalMouse(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id)
        d != null && !d.isVirtual && d.isExternal &&
            (d.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE &&
            (d.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
    }

    private fun updatePointerCapture() {
        val wv = webView ?: return
        if (!::drawer.isInitialized || !::cursor.isInitialized) return
        val want = lockOwnerId != 0 && !drawer.isOpen && !cursor.active && windowFocused && hasExternalMouse()
        if (want && !wv.hasPointerCapture()) {
            wv.requestFocus()
            wv.requestPointerCapture()
        } else if (!want && wv.hasPointerCapture()) {
            wv.releasePointerCapture()
        }
    }

    private fun onCapturedPointer(event: MotionEvent): Boolean {
        val relative = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!relative) {
                touchpadLastX = event.x
                touchpadLastY = event.y
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (relative) {
                    for (h in 0 until event.historySize) {
                        mouseDx += event.getHistoricalX(h)
                        mouseDy += event.getHistoricalY(h)
                    }
                    mouseDx += event.x
                    mouseDy += event.y
                } else {
                    // Captured touchpads report absolute finger positions: convert to deltas.
                    if (!touchpadLastX.isNaN()) {
                        mouseDx += event.x - touchpadLastX
                        mouseDy += event.y - touchpadLastY
                    }
                    touchpadLastX = event.x
                    touchpadLastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!relative) {
                touchpadLastX = Float.NaN
                touchpadLastY = Float.NaN
            }
            MotionEvent.ACTION_SCROLL -> mouseWheel += -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 100f
        }
        mouseButtons = event.buttonState and 0x7
        if (!mouseFlushPosted) {
            mouseFlushPosted = true
            root.postOnAnimation(mouseFlush)
        }
        return true
    }

    // =====================================================================================
    // Drawer host
    // =====================================================================================

    private fun openDrawer(fromController: Boolean) {
        if (drawer.isOpen) return
        cursor.cancel()
        touchPad.releaseAll()
        touchPad.visibility = View.GONE
        controller.resetStates()
        webView?.let {
            it.isFocusable = false
            it.isFocusableInTouchMode = false
            it.clearFocus()
        }
        drawer.open(fromController)
        menuButton.visibility = View.GONE
        updateCursorState()
        updatePointerCapture()
    }

    private fun closeDrawer() {
        if (!drawer.isOpen) return
        drawer.close()
        menuButton.visibility = View.VISIBLE
        touchPad.visibility = if (prefs.touchGamepad) View.VISIBLE else View.GONE
        controller.resetStates()
        webView?.let {
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.requestFocus()
        }
        hideSystemBars()
        updateCursorState()
        updatePointerCapture()
    }

    override fun navigate(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> t
            t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        loadTrusted(url)
        closeDrawer()
    }

    override fun goBack() {
        webView?.let { if (it.canGoBack()) it.goBack() }
    }

    override fun goForward() {
        webView?.let { if (it.canGoForward()) it.goForward() }
    }

    override fun reload() {
        webView?.reload()
    }

    override fun goHome() {
        loadTrusted(DEFAULT_URL)
        closeDrawer()
    }

    override fun exitApp() {
        finishAndRemoveTask()
    }

    override fun canGoBack(): Boolean = webView?.canGoBack() == true

    override fun canGoForward(): Boolean = webView?.canGoForward() == true

    override fun currentUrl(): String? = webView?.url

    override fun isCurrentSiteAllowed(): Boolean {
        val host = webView?.url?.let { Uri.parse(it).host } ?: return false
        return shield.isAllowedHost(host)
    }

    override fun allowCurrentSiteAlways() {
        val host = webView?.url?.let { Uri.parse(it).host } ?: return
        shield.allowAlways(host)
        toast("Always allowing $host")
    }

    override fun onSettingsChanged(reloadPage: Boolean) {
        bridge.broadcast(JSONObject().put("t", "cfg").put("settings", prefs.settingsJson()).toString())
        reinstallScript()
        controller.setVirtualPadEnabled(prefs.touchGamepad)
        touchPad.lookAsStick = prefs.mode == Prefs.MODE_PAD
        if (!drawer.isOpen) touchPad.visibility = if (prefs.touchGamepad) View.VISIBLE else View.GONE
        updateCursorState()
        drawer.refreshStatus()
        if (reloadPage) webView?.reload()
    }

    override fun setCursorEnabled(on: Boolean) {
        cursorOverride = on
        // Like pressing Esc on a PC: release the game's mouse capture before showing the cursor.
        if (on && lockOwnerId != 0) bridge.send(lockOwnerId, "{\"t\":\"unlock\"}")
        updateCursorState()
    }

    override fun isCursorEnabled(): Boolean = cursorOverride ?: autoCursorWanted()

    override fun statusText(): String {
        val pads = controller.controllerNames
        val sb = StringBuilder()
        sb.append("Controllers: ")
            .append(if (pads.isEmpty()) "none (pair over Bluetooth or plug in USB-C)" else pads.joinToString(", "))
            .append('\n')
        sb.append("Engine: Chrome ").append(chromeFull).append(" (").append(webViewVersion).append(")\n")
        sb.append("Presented to sites as: Windows 11, Chrome ").append(chromeMajor).append('\n')
        sb.append("Game frame: ").append(if (glStatus.isEmpty()) "no WebGL context yet" else glStatus).append('\n')
        sb.append("Threads (SharedArrayBuffer): ").append(if (threadStatus.isEmpty()) "unknown" else threadStatus).append('\n')
        sb.append("Pointer lock: ").append(if (lockOwnerId != 0) "held by the game" else "free")
        if (hasExternalMouse()) sb.append(" · physical mouse detected")
        sb.append('\n')
        sb.append("Script injection: ")
            .append(if (docStartSupported) "document-start OK" else "LIMITED - update Android System WebView")
            .append(" · controller bridge ")
            .append(if (messageListenerSupported) "OK" else "unavailable")
        return sb.toString()
    }

    override fun requestCloseDrawer() {
        closeDrawer()
    }

    // =====================================================================================
    // Chrome: immersive mode, banner, floating button
    // =====================================================================================

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyCutout(insets: WindowInsets) {
        val cut = insets.displayCutout
        val l = cut?.safeInsetLeft ?: 0
        val t = cut?.safeInsetTop ?: 0
        val r = cut?.safeInsetRight ?: 0
        val b = cut?.safeInsetBottom ?: 0
        val lp = menuButton.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = l + dp(8f)
        lp.topMargin = t + dp(8f)
        menuButton.layoutParams = lp
        drawer.setInsets(l, t, b)
        touchPad.setSafeInsets(l, r)
    }

    private fun buildMenuButton(): TextView = TextView(this).apply {
        text = "☰"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        background = Ui.rounded(this@MainActivity, 0x80000000.toInt(), 22f, 0x66FFFFFF, 1f)
        alpha = 0.6f
        isFocusable = false
        contentDescription = "Open command drawer"
        setOnClickListener { openDrawer(fromController = false) }
    }

    private fun buildBanner(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.background = Ui.rounded(this, 0xEE1B1F27.toInt(), 12f, Ui.ACCENT_DIM, 1f)
        b.setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
        b.elevation = dp(6f).toFloat()
        b.visibility = View.GONE
        bannerText = TextView(this).apply {
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
        }
        b.addView(bannerText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginEnd = dp(8f) })
        bannerAllowOnce = bannerButton("Allow once") { allowBlocked(false) }
        bannerAllowAlways = bannerButton("Always allow") { allowBlocked(true) }
        b.addView(bannerAllowOnce)
        b.addView(bannerAllowAlways)
        b.addView(bannerButton("✕") { hideBannerNow() })
        return b
    }

    private fun bannerButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Ui.ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        background = Ui.focusable(this@MainActivity)
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = dp(4f) }
    }

    private fun showBlocked(url: Uri, popup: Boolean) {
        val scheme = url.scheme?.lowercase(Locale.ROOT)
        val web = scheme == "http" || scheme == "https"
        val what = url.host ?: scheme ?: "link"
        blockedUrl = if (web) url.toString() else null
        bannerText.text = (if (popup) "Blocked pop-up: " else "Blocked redirect: ") + what
        bannerAllowOnce.visibility = if (web) View.VISIBLE else View.GONE
        bannerAllowAlways.visibility = if (web) View.VISIBLE else View.GONE
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 9_000L)
    }

    private fun showBlockedText(text: String) {
        blockedUrl = null
        bannerText.text = text
        bannerAllowOnce.visibility = View.GONE
        bannerAllowAlways.visibility = View.GONE
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 4_000L)
    }

    private fun hideBannerNow() {
        ui.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
    }

    private fun allowBlocked(always: Boolean) {
        val url = blockedUrl ?: return
        Uri.parse(url).host?.let { if (always) shield.allowAlways(it) else shield.allowForSession(it) }
        hideBannerNow()
        loadTrusted(url)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float): Int = Ui.dp(this, v)

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    companion object {
        private const val TAG = "RadiantPC"
        private const val DEFAULT_URL = "https://radiantoptimizer.games"
        private const val TAP_DELAY_MS = 24L
        private const val TOUCH_LOOK_SCALE = 2.2f
        private const val TAP_ACTIVE_JSON = "{\"t\":\"tap\",\"ms\":60000}"
        private const val TAP_ENDING_JSON = "{\"t\":\"tap\",\"ms\":450}"
    }
}
