package com.radiant.pcbrowser

import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-based ad and tracker blocking for every request a page makes.
 *
 * The list is StevenBlack/hosts (unified ads + malware, MIT) bundled as res/raw/adhosts.txt and
 * loaded on a background thread; until it is ready the small built-in list in NavigationShield
 * still applies. A request is blocked when its host or any parent domain is listed, so
 * "cdn.ads.example.com" is caught by "ads.example.com".
 */
class AdBlocker(context: Context, private val prefs: Prefs) {

    @Volatile
    private var hosts: Set<String> = emptySet()

    @Volatile
    var ready: Boolean = false
        private set

    /** Blocked requests since the last page start, shown in the Shield tab. */
    val blockedOnPage = AtomicInteger(0)
    private val recent = ArrayDeque<String>()

    @Volatile
    var enabled: Boolean = prefs.adBlock
        set(value) {
            field = value
            prefs.adBlock = value
        }

    val listSize: Int
        get() = hosts.size

    init {
        val app = context.applicationContext
        Thread({
            try {
                val set = HashSet<String>(90_000)
                app.resources.openRawResource(R.raw.adhosts).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line[0] == '#') continue
                        set.add(line.trim())
                    }
                }
                hosts = set
                ready = true
            } catch (t: Throwable) {
                Log.w(TAG, "Ad host list not loaded", t)
            }
        }, "radiant-adlist").apply { isDaemon = true }.start()
    }

    /** Any thread. */
    fun isBlocked(host: String?): Boolean {
        if (!enabled || host == null) return false
        val h = host.lowercase(Locale.ROOT)
        val set = hosts
        if (h in set) return true
        var dot = h.indexOf('.')
        while (dot >= 0) {
            if (h.substring(dot + 1) in set) return true
            dot = h.indexOf('.', dot + 1)
        }
        return false
    }

    fun recordBlocked(host: String) {
        blockedOnPage.incrementAndGet()
        synchronized(recent) {
            if (recent.lastOrNull() != host) {
                recent.addLast(host)
                while (recent.size > 12) recent.removeFirst()
            }
        }
    }

    fun recentBlocked(): List<String> = synchronized(recent) { recent.toList() }

    fun pageStarted() {
        blockedOnPage.set(0)
        synchronized(recent) { recent.clear() }
    }

    companion object {
        private const val TAG = "RadiantAdBlock"
    }
}
