package com.radiant.pcbrowser

import android.net.Uri
import android.os.SystemClock
import java.util.Locale

/**
 * Popup and redirect shield.
 *
 * Main-frame navigations are allowed when they go to an allowlisted game host, stay on the current
 * site, come from a user gesture, or follow a load the user started (typed URL / bookmark).
 * Everything else - script redirects, popunders, casino/betting hosts, known popunder ad networks -
 * is blocked and surfaced in a banner with "Allow once" / "Always allow".
 */
class NavigationShield(private val prefs: Prefs) {

    enum class Verdict { ALLOW, BLOCK }

    private val sessionAllowed = HashSet<String>()

    @Volatile
    private var trustedUntil = 0L

    /** Call right before loadUrl() for a URL the user chose, so its redirect chain is trusted. */
    fun trustNextNavigation(durationMs: Long = 15_000L) {
        trustedUntil = SystemClock.uptimeMillis() + durationMs
    }

    fun decide(url: Uri, hasGesture: Boolean, isRedirect: Boolean, currentUrl: String?): Verdict {
        val host = url.host?.lowercase(Locale.ROOT) ?: return Verdict.BLOCK
        if (isAdHost(host) || looksLikeGambling(host)) return Verdict.BLOCK
        if (isAllowedHost(host)) return Verdict.ALLOW
        val current = currentUrl?.let { Uri.parse(it).host?.lowercase(Locale.ROOT) }
        if (current != null && sameSite(current, host)) return Verdict.ALLOW
        if (SystemClock.uptimeMillis() < trustedUntil) return Verdict.ALLOW
        if (hasGesture && !isRedirect) {
            // A real tap on a link: allow it and its immediate redirect chain.
            trustNextNavigation(5_000L)
            return Verdict.ALLOW
        }
        return Verdict.BLOCK
    }

    fun decidePopup(url: Uri, currentUrl: String?): Verdict {
        val host = url.host?.lowercase(Locale.ROOT) ?: return Verdict.BLOCK
        if (isAdHost(host) || looksLikeGambling(host)) return Verdict.BLOCK
        if (isAllowedHost(host)) return Verdict.ALLOW
        val current = currentUrl?.let { Uri.parse(it).host?.lowercase(Locale.ROOT) }
        if (current != null && sameSite(current, host)) return Verdict.ALLOW
        return Verdict.BLOCK
    }

    fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return matches(h, DEFAULT_ALLOWED) || matches(h, prefs.userAllowedHosts) || matches(h, sessionAllowed)
    }

    fun allowForSession(host: String) {
        sessionAllowed.add(host.lowercase(Locale.ROOT))
    }

    fun allowAlways(host: String) {
        val h = siteKey(host.lowercase(Locale.ROOT))
        prefs.userAllowedHosts = prefs.userAllowedHosts + h
        sessionAllowed.add(h)
    }

    fun removeAlways(host: String) {
        val h = host.lowercase(Locale.ROOT)
        val key = siteKey(h)
        prefs.userAllowedHosts = prefs.userAllowedHosts.filterNot { it == key || it == h }.toSet()
        sessionAllowed.remove(h)
        sessionAllowed.remove(key)
    }

    /** True only for hosts the user allowed, not for the built-in game list. */
    fun isUserAllowed(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return matches(h, prefs.userAllowedHosts) || matches(h, sessionAllowed)
    }

    fun isBuiltInAllowed(host: String): Boolean = matches(host.lowercase(Locale.ROOT), DEFAULT_ALLOWED)

    /** Thread-safe (immutable set), used from shouldInterceptRequest on a background thread. */
    fun isAdHost(host: String): Boolean = matches(host.lowercase(Locale.ROOT), AD_HOSTS)

    fun looksLikeGambling(host: String): Boolean {
        val tokens = host.lowercase(Locale.ROOT).split('.', '-')
        for (t in tokens) {
            if (t in GAMBLING_EXACT) return true
            for (needle in GAMBLING_CONTAINS) if (t.contains(needle)) return true
        }
        return false
    }

    /** Approximate registrable domain; multi-tenant hosting suffixes keep one more label. */
    fun siteKey(host: String): String {
        val labels = host.lowercase(Locale.ROOT).trimEnd('.').split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        val keep = if (lastTwo in MULTI_TENANT_SUFFIXES) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    fun sameSite(a: String, b: String): Boolean = siteKey(a) == siteKey(b)

    private fun matches(host: String, set: Set<String>): Boolean {
        if (host in set) return true
        var dot = host.indexOf('.')
        while (dot >= 0) {
            if (host.substring(dot + 1) in set) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }

    companion object {
        val DEFAULT_ALLOWED: Set<String> = setOf(
            "radiantoptimizer.games",
            "radiantoptimizergames.com",
            "radiantoptimizer.lol",
            "vel.gg",
            "pieter.com",
            "surfd.net",
            "wasmarcade.com",
            "github.io",
            "workers.dev",
            "hcemobile.com",
            "mitchellhynes.com",
            "optijuegos.net"
        )

        /** Popunder / redirect ad networks. Requests to them are answered with an empty response. */
        val AD_HOSTS: Set<String> = setOf(
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "propellerclick.com",
            "onclickads.net",
            "adsterra.com",
            "exoclick.com",
            "juicyads.com",
            "hilltopads.net",
            "clickadu.com",
            "adcash.com",
            "monetag.com"
        )

        private val GAMBLING_EXACT = setOf("bet", "bets", "slot", "slots", "poker", "stake", "bingo", "vulkan", "casino")
        private val GAMBLING_CONTAINS = listOf(
            "casino", "roulette", "jackpot", "gambl", "sportsbook", "1xbet", "bet365", "betway",
            "parimatch", "mostbet", "melbet", "betwinner", "pinup", "1win"
        )

        private val MULTI_TENANT_SUFFIXES = setOf(
            "github.io", "workers.dev", "pages.dev", "netlify.app", "vercel.app", "web.app",
            "firebaseapp.com", "itch.io", "glitch.me", "herokuapp.com", "onrender.com",
            "co.uk", "com.au", "com.br", "co.jp", "co.in", "com.mx", "co.za", "com.tr"
        )
    }
}
