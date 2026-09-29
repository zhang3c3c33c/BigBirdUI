package io.bbui.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import androidx.annotation.WorkerThread
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Only bundled chat assets can talk to the native controller. No JavaScript interface or API key. */
@SuppressLint("SetJavaScriptEnabled")
class ChatWebView(context: Context, private val command: (JSONObject) -> Unit, private val failure: (String) -> Unit) : WebView(context) {
    private var ready = false
    private var disposed = false
    private var snapshot: JSONObject? = null
    private var presentationActive = true
    private val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()

    init {
        contentDescription = "BBUI 聊天"
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        isSaveEnabled = false // Service snapshots, not WebView history, restore the chat.
        webViewClient = object : WebViewClient() {
            @WorkerThread
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = intercept(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.url.toString() == ENTRY_URL) return false
                if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("https", "http") && !trustedOrigin(request.url)) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }.onFailure { failure("无法打开此链接") }
                }
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { ready = false }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) failure("聊天页面加载失败，请重新加载；仍可使用上方停止按钮")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) failure("聊天资源未能加载，请重新加载")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready = false
                (parent as? android.view.ViewGroup)?.removeView(this@ChatWebView)
                dispose()
                failure("聊天页面已退出，请重新加载；仍可使用上方停止按钮")
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "BBUI", setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
                if (message.type == WebMessageCompat.TYPE_STRING) receive(message.data, origin, mainFrame)
            }
            loadUrl(ENTRY_URL)
        } else failure("请更新 Android System WebView 后使用聊天；停止和设置仍可使用")
    }

    @WorkerThread
    internal fun intercept(uri: Uri): WebResourceResponse {
        if (!trustedOrigin(uri) || !uri.path.orEmpty().startsWith("/assets/chat/") ||
            uri.pathSegments.any { it == "." || it == ".." || it.contains('\\') }) return blocked()
        val response = loader.shouldInterceptRequest(uri) ?: return blocked()
        response.responseHeaders = (response.responseHeaders ?: emptyMap()) + mapOf(
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
            "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer"
        )
        return response
    }

    internal fun receive(raw: String?, origin: Uri, mainFrame: Boolean) {
        if (disposed || !mainFrame || !trustedOrigin(origin) || raw == null || raw.length > 65536) return
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (parsed.optString("type")) {
            "ready" -> {
                // Refresh service-owned drafts before the new document sees any
                // snapshot. Replaying the cache first can restore an old scroll
                // position even if the current snapshot follows immediately.
                ready = false
                command(JSONObject().put("type", "ready"))
                ready = true
                dispatch()
            }
            "answerQuestion", "questionDraft" -> {
                val value = JSONObject().put("type", parsed.getString("type"))
                for (key in listOf("sessionId", "runId", "requestId")) {
                    val id = parsed.opt(key) as? String ?: return
                    if (id.length !in 1..200) return
                    value.put(key, id)
                }
                value.put("answers", parsed.optJSONArray("answers") ?: return).put("cancelled", parsed.optBoolean("cancelled"))
                command(value)
            }
            "questionFocused" -> {
                val value = JSONObject().put("type", "questionFocused")
                for (key in listOf("id", "sessionId", "requestId")) {
                    val id = parsed.opt(key) as? String ?: return
                    if (id.length !in 1..200) return
                    value.put(key, id)
                }
                command(value)
            }
            "send", "steer" -> {
                val text = parsed.opt("text") as? String ?: return
                val sessionId = parsed.opt("sessionId") as? String ?: return
                val submissionId = parsed.opt("submissionId") as? String ?: return
                if (text.isNotBlank() && sessionId.isNotBlank() && submissionId.isNotBlank()) command(JSONObject()
                    .put("type", parsed.getString("type")).put("text", text).put("sessionId", sessionId).put("submissionId", submissionId))
            }
            "steerQueued" -> {
                val value = JSONObject().put("type", "steerQueued")
                for (key in listOf("sessionId", "submissionId", "controlId", "runId")) {
                    val id = parsed.opt(key) as? String ?: return
                    if (id.length !in 1..200) return
                    value.put(key, id)
                }
                command(value)
            }
            "takeOver", "resumeTask", "endManual" -> {
                val controlId = parsed.opt("controlId") as? String ?: return
                if (controlId.isNotBlank()) command(JSONObject().put("type", parsed.getString("type")).put("controlId", controlId))
            }
            "selectModel", "setThinkingLevel" -> {
                val keys = if (parsed.getString("type") == "selectModel") listOf("sessionId", "requestId", "connectionId", "modelId")
                    else listOf("sessionId", "requestId", "thinkingLevel")
                val value = JSONObject().put("type", parsed.getString("type"))
                for (key in keys) {
                    val text = parsed.opt(key) as? String ?: return
                    if (text.isBlank() || text.length > 512) return
                    value.put(key, text)
                }
                command(value)
            }
            "openSettings" -> {
                val page = parsed.opt("page")
                if (page != null && page !in setOf("models", "search", "memory")) return
                command(JSONObject().put("type", "openSettings").also { if (page != null) it.put("page", page) })
            }
            "stop", "openPreview", "newSession", "refreshSessions", "pauseQueue", "resumeQueue" -> command(JSONObject().put("type", parsed.getString("type")))
            "pinSession" -> {
                val sessionId = parsed.opt("sessionId") as? String ?: return
                val pinned = parsed.opt("pinned") as? Boolean ?: return
                if (sessionId.isNotBlank()) command(JSONObject().put("type", "pinSession").put("sessionId", sessionId).put("pinned", pinned))
            }
            "loadOlder", "selectSession", "renameSession", "deleteSession", "viewState", "cancelQueued" -> {
                val value = JSONObject().put("type", parsed.getString("type"))
                for (key in listOf("sessionId", "submissionId", "title", "text")) {
                    val text = parsed.opt(key) as? String
                    if (text != null) value.put(key, text)
                }
                if (parsed.opt("scrollTop") is Number) value.put("scrollTop", parsed.optDouble("scrollTop"))
                command(value)
            }
            "rendered" -> {
                val revision = parsed.opt("revision") as? Number ?: return
                if (revision.toLong() >= 0) command(JSONObject().put("type", "rendered").put("revision", revision.toLong()))
            }
        }
    }

    fun showSnapshot(value: JSONObject) {
        if (disposed || value.optString("type") != "snapshot") return
        snapshot = value
        dispatch()
    }
    fun setPresentationActive(active: Boolean) {
        if (presentationActive == active) return
        presentationActive = active
        if (active) { onResume(); dispatch() } else onPause()
    }
    private fun dispatch() {
        if (!ready || disposed || !presentationActive) return
        val data = snapshot?.toString() ?: return
        // Quote as a JSON string so model output can never become executable JavaScript.
        val quoted = JSONObject.quote(data).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        evaluateJavascript("window.dispatchEvent(new CustomEvent('bbui-message',{detail:JSON.parse($quoted)}));", null)
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        ready = false
        snapshot = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(this, "BBUI")
        stopLoading()
        destroy()
    }
    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val ENTRY_URL = "$ORIGIN/assets/chat/index.html"
        internal fun trustedOrigin(uri: Uri) = uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1 && uri.userInfo == null
        private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
