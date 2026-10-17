package com.radiant.pcbrowser

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
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
        val url = request.url.toString()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            for ((k, v) in request.requestHeaders ?: emptyMap()) {
                // Let HttpURLConnection negotiate and decode compression itself.
                if (k.equals("Accept-Encoding", true) || k.equals("Range", true)) continue
                conn.setRequestProperty(k, v)
            }
            if (userAgent.isNotEmpty()) conn.setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }

            val code = conn.responseCode
            if (code in 300..399) {
                // WebView repeats the request natively and follows the redirect itself.
                conn.disconnect()
                return null
            }

            val headers = LinkedHashMap<String, String>()
            var coep = ""
            for ((key, values) in conn.headerFields) {
                if (key == null) continue
                when (key.lowercase(Locale.ROOT)) {
                    "set-cookie" -> values.forEach { CookieManager.getInstance().setCookie(url, it) }
                    // The body is already decoded and re-streamed.
                    "content-encoding", "content-length", "transfer-encoding", "connection", "keep-alive" -> Unit
                    else -> {
                        val joined = values.joinToString(", ")
                        headers[key] = joined
                        if (key.equals("Cross-Origin-Embedder-Policy", true)) coep = joined.lowercase(Locale.ROOT)
                    }
                }
            }
            headers.keys.firstOrNull { it.equals("Document-Isolation-Policy", true) }?.let { headers.remove(it) }
            headers["Document-Isolation-Policy"] =
                if (coep.contains("require-corp")) "isolate-and-require-corp" else "isolate-and-credentialless"

            val contentType = conn.contentType ?: "text/html"
            val mime = contentType.substringBefore(';').trim().ifEmpty { "text/html" }
            val charset = Regex("charset=\"?([^;\"]+)", RegexOption.IGNORE_CASE).find(contentType)
                ?.groupValues?.get(1)?.trim() ?: "utf-8"
            val body = if (code >= 400) conn.errorStream ?: ByteArrayInputStream(ByteArray(0)) else conn.inputStream
            val reason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: if (code < 400) "OK" else "Error"
            return WebResourceResponse(mime, charset, code, reason, headers, body)
        } catch (t: Throwable) {
            Log.w(TAG, "Isolated fetch failed for $url, loading normally", t)
            conn?.disconnect()
            return null
        }
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
