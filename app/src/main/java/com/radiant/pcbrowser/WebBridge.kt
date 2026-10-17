package com.radiant.pcbrowser

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONException
import org.json.JSONObject

/**
 * One message channel per frame, built on WebViewCompat.addWebMessageListener.
 *
 * Every frame's shim posts {"t":"hello"} on start; the reply proxy we get back is the only way to talk
 * to that particular frame (including cross-origin iframes), so it is kept until the frame says "bye".
 * The injected object is visible to page scripts, so every incoming message is treated as untrusted.
 */
class WebBridge(private val host: Host) : WebViewCompat.WebMessageListener {

    interface Host {
        fun helloPayload(frame: Frame): JSONObject
        fun onFrameMessage(frame: Frame, msg: JSONObject)
        fun onFrameGone(frame: Frame)
    }

    class Frame(val id: Int, val proxy: JavaScriptReplyProxy, val isMainFrame: Boolean, val origin: String)

    private val frames = LinkedHashMap<JavaScriptReplyProxy, Frame>()
    private var nextId = 1

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        // Page scripts can see the injected object; an ArrayBuffer message would make getData() throw.
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val data = message.data ?: return
        if (data.length > MAX_MESSAGE) return
        val msg = try {
            JSONObject(data)
        } catch (_: JSONException) {
            return
        }
        when (msg.optString("t")) {
            "hello" -> {
                val frame = frames[replyProxy] ?: Frame(nextId++, replyProxy, isMainFrame, sourceOrigin.toString()).also {
                    frames[replyProxy] = it
                    trim()
                }
                val reply = host.helloPayload(frame)
                reply.put("t", "hello")
                reply.put("id", frame.id)
                post(frame, reply.toString())
            }
            "bye" -> frames.remove(replyProxy)?.let { host.onFrameGone(it) }
            else -> frames[replyProxy]?.let { host.onFrameMessage(it, msg) }
        }
    }

    fun broadcast(text: String) {
        for (f in frames.values) post(f, text)
    }

    fun send(frameId: Int, text: String) {
        for (f in frames.values) {
            if (f.id == frameId) {
                post(f, text)
                return
            }
        }
    }

    fun frameCount(): Int = frames.size

    fun clear() {
        frames.clear()
    }

    private fun trim() {
        while (frames.size > MAX_FRAMES) {
            val oldest = frames.keys.first()
            frames.remove(oldest)?.let { host.onFrameGone(it) }
        }
    }

    private fun post(frame: Frame, text: String) {
        try {
            frame.proxy.postMessage(text)
        } catch (_: Throwable) {
            // Frame already gone; it will be dropped on "bye" or trimmed.
        }
    }

    companion object {
        const val JS_OBJECT_NAME = "RadiantNative"
        private const val MAX_FRAMES = 64
        private const val MAX_MESSAGE = 16_384
    }
}
