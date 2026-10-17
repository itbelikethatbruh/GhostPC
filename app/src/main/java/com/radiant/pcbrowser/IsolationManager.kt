package com.radiant.pcbrowser

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

/**
 * Multithreaded WebAssembly games need SharedArrayBuffer, which browsers only grant to
 * cross-origin isolated pages.
 *
 * Android WebView never isolates pages on its own (it has no per-site renderer processes), and
 * COOP+COEP headers do not change that. Since androidx.webkit 1.18 an app can opt specific origins
 * in with Profile.setCrossOriginIsolatedAllowlist; WebView then isolates those pages for real
 * ("concrete" isolation) when their document arrives with a Document-Isolation-Policy header.
 *
 * Game hosts send COOP/COEP, not Document-Isolation-Policy, so for allowlisted origins this class
 * fetches the HTML document itself and returns it to WebView with the header added. Only GET
 * document requests are touched; scripts, wasm, textures and everything else load normally.
 */
class IsolationManager(private val prefs: Prefs) {

    /** Whether the installed WebView implements the allowlist API. Checked once on the UI thread. */
    val supported: Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST)
    } catch (_: Throwable) {
        false
    }

    /** Read from the network thread in shouldInterceptRequest, written on the UI thread. */
    @Volatile
    var origins: Set<String> = prefs.threadOrigins
        private set

    /** User agent used for the document fetch (kept in sync with the WebView). */
    @Volatile
    var userAgent: String = ""

    fun isEnabled(origin: String?): Boolean = origin != null && origin in origins

    fun setEnabled(webView: WebView?, origin: String, enabled: Boolean) {
        origins = if (enabled) origins + origin else origins - origin
        prefs.threadOrigins = origins
        apply(webView)
    }

    /** Pushes the allowlist into the WebView profile. Affects already-loaded pages too. */
    fun apply(webView: WebView?) {
        if (!supported || webView == null) return
        try {
            WebViewCompat.getProfile(webView).setCrossOriginIsolatedAllowlist(origins)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not set the cross-origin isolation allowlist", t)
        }
    }

    /** Network thread. True for document loads of allowlisted origins that we should re-serve. */
    fun shouldIntercept(request: WebResourceRequest): Boolean {
        if (!supported || !request.method.equals("GET", ignoreCase = true)) return false
        val origin = originOf(request.url) ?: return false
        if (origin !in origins) return false
        if (request.isForMainFrame) return true
        val headers = request.requestHeaders ?: return false
        val dest = header(headers, "Sec-Fetch-Dest")
        if (dest != null) return dest == "iframe" || dest == "frame" || dest == "document"
        val accept = header(headers, "Accept") ?: return false
        return accept.startsWith("text/html")
    }

    /**
     * Network thread. Fetches the document and returns it with Document-Isolation-Policy added.
     * Returns null (WebView then loads it normally) for redirects and on any failure.
     */
    fun fetchIsolated(request: WebResourceRequest): WebResourceResponse? {
        val fetched = Refetch.get(request.url.toString(), request, userAgent) ?: return null
        val headers = fetched.headers
        val coep = headers.entries.firstOrNull { it.key.equals("Cross-Origin-Embedder-Policy", true) }
            ?.value?.lowercase(Locale.ROOT) ?: ""
        headers.keys.firstOrNull { it.equals("Document-Isolation-Policy", true) }?.let { headers.remove(it) }
        headers["Document-Isolation-Policy"] =
            if (coep.contains("require-corp")) "isolate-and-require-corp" else "isolate-and-credentialless"
        return WebResourceResponse(fetched.mime.ifEmpty { "text/html" }, fetched.charset, fetched.code, fetched.reason,
            headers, fetched.body)
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.lowercase(Locale.ROOT)

    companion object {
        private const val TAG = "RadiantIsolation"

        /** scheme://host[:port] with the default port omitted, as the allowlist expects. */
        fun originOf(uri: Uri?): String? {
            if (uri == null) return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val port = uri.port
            val defaultPort = if (scheme == "https") 443 else 80
            return if (port == -1 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
        }

        fun originOf(url: String?): String? = url?.let { originOf(Uri.parse(it)) }
    }
}
