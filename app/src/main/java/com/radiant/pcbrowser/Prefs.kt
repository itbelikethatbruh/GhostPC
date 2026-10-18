package com.radiant.pcbrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Persistent user settings. Everything the JS engine needs is exported by [settingsJson]. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("radiant_prefs", Context.MODE_PRIVATE)

    var mode: String
        get() {
            val v = sp.getString(K_MODE, MODE_KBM) ?: MODE_KBM
            return if (v in MODES) v else MODE_KBM
        }
        set(value) = sp.edit().putString(K_MODE, if (value in MODES) value else MODE_KBM).apply()

    var lookSensitivity: Float
        get() = sp.getFloat(K_SENS, 1.0f)
        set(value) = sp.edit().putFloat(K_SENS, value.coerceIn(0.1f, 4f)).apply()

    var deadzone: Float
        get() = sp.getFloat(K_DEADZONE, 0.15f)
        set(value) = sp.edit().putFloat(K_DEADZONE, value.coerceIn(0.02f, 0.5f)).apply()

    var cursorSpeed: Float
        get() = sp.getFloat(K_CURSOR_SPEED, 1.0f)
        set(value) = sp.edit().putFloat(K_CURSOR_SPEED, value.coerceIn(0.3f, 3f)).apply()

    var dpadArrows: Boolean
        get() = sp.getBoolean(K_DPAD_ARROWS, false)
        set(value) = sp.edit().putBoolean(K_DPAD_ARROWS, value).apply()

    var resolutionScale: Float
        get() = sp.getFloat(K_RES_SCALE, 1.0f)
        set(value) = sp.edit().putFloat(K_RES_SCALE, value.coerceIn(0.25f, 1f)).apply()

    /** CSS width forced on the top page's viewport, 0 = use the device width. */
    var desktopViewport: Int
        get() = sp.getInt(K_VIEWPORT, 1280)
        set(value) = sp.edit().putInt(K_VIEWPORT, value.coerceIn(0, 4096)).apply()

    var autoCursor: Boolean
        get() = sp.getBoolean(K_AUTO_CURSOR, true)
        set(value) = sp.edit().putBoolean(K_AUTO_CURSOR, value).apply()

    var touchGamepad: Boolean
        get() = sp.getBoolean(K_TOUCH_PAD, false)
        set(value) = sp.edit().putBoolean(K_TOUCH_PAD, value).apply()

    var lastUrl: String?
        get() = sp.getString(K_LAST_URL, null)
        set(value) = sp.edit().putString(K_LAST_URL, value).apply()

    /** Hosts the user chose to always allow (in addition to the built-in allowlist). */
    var userAllowedHosts: Set<String>
        get() = HashSet(sp.getStringSet(K_ALLOWED, emptySet()) ?: emptySet())
        set(value) = sp.edit().putStringSet(K_ALLOWED, HashSet(value)).apply()

    /** Which desktop browser pages are told they run in: chrome, edge, firefox or android (no spoofing). */
    var identity: String
        get() {
            val v = sp.getString(K_IDENTITY, IDENTITY_CHROME) ?: IDENTITY_CHROME
            return if (v in IDENTITIES) v else IDENTITY_CHROME
        }
        set(value) = sp.edit().putString(K_IDENTITY, if (value in IDENTITIES) value else IDENTITY_CHROME).apply()

    /** Turn on multithreading automatically for sites whose headers ask for cross-origin isolation. */
    var autoThreads: Boolean
        get() = sp.getBoolean(K_AUTO_THREADS, true)
        set(value) = sp.edit().putBoolean(K_AUTO_THREADS, value).apply()

    /** Origins (scheme://host[:port]) that get SharedArrayBuffer through WebView's isolation allowlist. */
    var threadOrigins: Set<String>
        get() = HashSet(sp.getStringSet(K_THREAD_ORIGINS, DEFAULT_THREAD_ORIGINS) ?: DEFAULT_THREAD_ORIGINS)
        set(value) = sp.edit().putStringSet(K_THREAD_ORIGINS, HashSet(value)).apply()

    /** Block ads and trackers with the bundled host list. */
    var adBlock: Boolean
        get() = sp.getBoolean(K_AD_BLOCK, true)
        set(value) = sp.edit().putBoolean(K_AD_BLOCK, value).apply()

    /** The swipe-through introduction has been seen. */
    var introDone: Boolean
        get() = sp.getBoolean(K_INTRO_DONE, false)
        set(value) = sp.edit().putBoolean(K_INTRO_DONE, value).apply()

    /** The on-screen tour of the main screen has been seen. */
    var tourDone: Boolean
        get() = sp.getBoolean(K_TOUR_DONE, false)
        set(value) = sp.edit().putBoolean(K_TOUR_DONE, value).apply()

    /** User-added bookmarks for a library section ("games" or "watch"). */
    fun bookmarks(section: String): List<CommandDrawer.Bookmark> {
        val raw = sp.getString(K_BOOKMARKS + section, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isEmpty()) null
                else CommandDrawer.Bookmark(o.optString("title").ifEmpty { url }, o.optString("monogram").ifEmpty { "+" }, url, custom = true)
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    fun setBookmarks(section: String, list: List<CommandDrawer.Bookmark>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("title", b.title).put("monogram", b.monogram).put("url", b.url))
        sp.edit().putString(K_BOOKMARKS + section, arr.toString()).apply()
    }

    /** Controller button index (standard mapping 0..16) -> action string understood by the JS engine. */
    val binds: Map<Int, String>
        get() {
            val out = HashMap(DEFAULT_BINDS)
            val raw = sp.getString(K_BINDS, null) ?: return out
            try {
                val o = JSONObject(raw)
                for (key in o.keys()) {
                    val index = key.toIntOrNull() ?: continue
                    if (index in 0..16) out[index] = o.getString(key)
                }
            } catch (_: JSONException) {
                // Corrupt value: fall back to defaults.
            }
            return out
        }

    fun setBind(index: Int, action: String) {
        val current = HashMap(binds)
        current[index] = action
        val o = JSONObject()
        for ((k, v) in current) o.put(k.toString(), v)
        sp.edit().putString(K_BINDS, o.toString()).apply()
    }

    fun resetBinds() {
        sp.edit().remove(K_BINDS).apply()
    }

    fun settingsJson(): JSONObject {
        val b = JSONObject()
        for ((k, v) in binds) b.put(k.toString(), v)
        return JSONObject()
            .put("mode", mode)
            .put("sens", lookSensitivity.toDouble())
            .put("deadzone", deadzone.toDouble())
            .put("dpadArrows", dpadArrows)
            .put("resScale", resolutionScale.toDouble())
            .put("desktopViewport", desktopViewport)
            .put("autoCursor", autoCursor)
            .put("binds", b)
            .put("identity", identity)
    }

    companion object {
        const val MODE_KBM = "kbm"
        const val MODE_PAD = "pad"
        const val MODE_HYBRID = "hybrid"
        val MODES = setOf(MODE_KBM, MODE_PAD, MODE_HYBRID)

        const val IDENTITY_CHROME = "chrome"
        const val IDENTITY_EDGE = "edge"
        const val IDENTITY_FIREFOX = "firefox"
        const val IDENTITY_ANDROID = "android"
        val IDENTITIES = setOf(IDENTITY_CHROME, IDENTITY_EDGE, IDENTITY_FIREFOX, IDENTITY_ANDROID)

        /** Sites known to need threads; anything else is added when it asks (autoThreads) or by hand. */
        val DEFAULT_THREAD_ORIGINS: Set<String> = setOf("https://vel.gg")

        private const val K_MODE = "mode"
        private const val K_SENS = "sens"
        private const val K_DEADZONE = "deadzone"
        private const val K_CURSOR_SPEED = "cursor_speed"
        private const val K_DPAD_ARROWS = "dpad_arrows"
        private const val K_RES_SCALE = "res_scale"
        private const val K_VIEWPORT = "desktop_viewport"
        private const val K_AUTO_CURSOR = "auto_cursor"
        private const val K_TOUCH_PAD = "touch_pad"
        private const val K_LAST_URL = "last_url"
        private const val K_ALLOWED = "allowed_hosts"
        private const val K_BINDS = "binds"
        private const val K_IDENTITY = "identity"
        private const val K_AUTO_THREADS = "auto_threads"
        private const val K_THREAD_ORIGINS = "thread_origins"
        private const val K_AD_BLOCK = "ad_block"
        private const val K_INTRO_DONE = "intro_done"
        private const val K_TOUR_DONE = "tour_done"
        private const val K_BOOKMARKS = "bookmarks_"

        val DEFAULT_BINDS: Map<Int, String> = mapOf(
            0 to "Space",
            1 to "KeyC",
            2 to "KeyR",
            3 to "WheelDown",
            4 to "KeyF+KeyE",
            5 to "KeyG",
            6 to "Mouse2",
            7 to "Mouse0",
            8 to "Tab",
            9 to "Menu",
            10 to "ShiftLeft",
            11 to "KeyV",
            12 to "Digit1",
            13 to "Digit2",
            14 to "Digit3",
            15 to "Digit4",
            16 to "None"
        )

        val BUTTON_NAMES = arrayOf(
            "A / Cross", "B / Circle", "X / Square", "Y / Triangle",
            "LB / L1", "RB / R1", "LT / L2", "RT / R2",
            "Select / Share", "Start / Options", "L3 (stick click)", "R3 (stick click)",
            "D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right", "Guide / Home"
        )
    }
}
