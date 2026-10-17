package com.radiant.pcbrowser

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.util.Locale

/**
 * Puts the page script into Web Workers.
 *
 * Document-start scripts only run in frames. Games that render from a worker thread
 * (OffscreenCanvas, Emscripten PROXY_TO_PTHREAD) create their WebGL context there and would
 * miss the S3TC emulation and the desktop navigator. The page-side shim therefore tags every
 * worker script URL with a marker query parameter; this class serves those requests itself with
 * the script prepended, so the worker's own URL and base URL stay exactly as the page intended.
 */
object WorkerScripts {

    /** Must match WORKER_MARKER in pc_engine_shim.js. */
    const val MARKER = "__radiant_worker"

    fun isMarked(request: WebResourceRequest): Boolean {
        if (!request.method.equals("GET", ignoreCase = true)) return false
        val url = request.url
        val scheme = url.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        return try {
            url.getQueryParameter(MARKER) != null
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    /** Network thread. Null (normal load, without the shim) on redirects or failure. */
    fun serve(request: WebResourceRequest, script: String, userAgent: String): WebResourceResponse? {
        val original = withoutMarker(request.url)
        val fetched = Refetch.get(original, request, userAgent) ?: return null
        val prelude = (script + "\n").toByteArray(Charsets.UTF_8)
        val body = SequenceInputStream(ByteArrayInputStream(prelude), fetched.body)
        val mime = fetched.mime.ifEmpty { "text/javascript" }
        // The prelude is ASCII, so the script's own charset still describes the whole body.
        return WebResourceResponse(mime, fetched.charset, fetched.code, fetched.reason, fetched.headers, body)
    }

    /** Drops only the marker; every other query parameter is kept byte for byte. */
    fun withoutMarker(url: Uri): String {
        val query = url.encodedQuery ?: return url.toString()
        val kept = query.split('&').filter { it != MARKER && !it.startsWith("$MARKER=") }
        return url.buildUpon().encodedQuery(if (kept.isEmpty()) null else kept.joinToString("&")).build().toString()
    }
}
