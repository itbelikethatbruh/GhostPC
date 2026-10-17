package com.radiant.pcbrowser

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches a resource the way WebView would have (same headers, cookies and user agent), so a
 * shouldInterceptRequest handler can return it with a changed header or body.
 * Runs on WebView's network thread.
 */
object Refetch {

    class Result(
        val code: Int,
        val reason: String,
        val mime: String,
        val charset: String,
        val headers: LinkedHashMap<String, String>,
        val body: InputStream
    )

    /** Null for redirects (WebView then follows them itself) and on any failure. */
    fun get(url: String, request: WebResourceRequest, userAgent: String): Result? {
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
            if (code in 300..399 || code < 100 || code > 599) {
                conn.disconnect()
                return null
            }

            val headers = LinkedHashMap<String, String>()
            for ((key, values) in conn.headerFields) {
                if (key == null) continue
                when (key.lowercase(Locale.ROOT)) {
                    "set-cookie" -> values.forEach { CookieManager.getInstance().setCookie(url, it) }
                    // The body is already decoded and re-streamed.
                    "content-encoding", "content-length", "transfer-encoding", "connection", "keep-alive" -> Unit
                    else -> headers[key] = values.joinToString(", ")
                }
            }
            val contentType = conn.contentType ?: ""
            val mime = contentType.substringBefore(';').trim()
            val charset = Regex("charset=\"?([^;\"]+)", RegexOption.IGNORE_CASE).find(contentType)
                ?.groupValues?.get(1)?.trim() ?: "utf-8"
            val body = if (code >= 400) conn.errorStream ?: ByteArrayInputStream(ByteArray(0)) else conn.inputStream
            val reason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: if (code < 400) "OK" else "Error"
            return Result(code, reason, mime, charset, headers, body)
        } catch (t: Throwable) {
            Log.w(TAG, "Refetch of $url failed, loading normally", t)
            conn?.disconnect()
            return null
        }
    }

    private const val TAG = "RadiantRefetch"
}
