package com.radiant.pcbrowser

import android.content.Context
import android.content.SharedPreferences
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
    }

    companion object {
        const val MODE_KBM = "kbm"
        const val MODE_PAD = "pad"
        const val MODE_HYBRID = "hybrid"
        val MODES = setOf(MODE_KBM, MODE_PAD, MODE_HYBRID)

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
