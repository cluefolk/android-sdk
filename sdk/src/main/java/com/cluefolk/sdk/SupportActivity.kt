package com.cluefolk.sdk

import android.annotation.SuppressLint
import android.app.Application
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.MutableContextWrapper
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

class SupportActivity : ComponentActivity() {
    private var requestId = ""
    private var content: SupportView? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { files ->
        content?.completeFileSelection(files.filter { it.scheme == "content" }.take(3).toTypedArray())
    }

    internal fun chooseFiles(): Boolean {
        if (!Cluefolk.isCurrentSupport(requestId)) return false
        return try {
            filePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra("cluefolkRequestId").orEmpty()
        if (!Cluefolk.isCurrentSupport(requestId)) {
            finish()
            return
        }
        // The game's own orientation setting, or full rotation when free rotation
        // is on (see SupportSettings.orientation). Android 8.0 refuses some requests;
        // the screen then follows the system.
        try {
            requestedOrientation = intent.getIntExtra("cluefolkOrientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        } catch (_: IllegalStateException) {
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Cluefolk.notifyError("UNSUPPORTED_WEBVIEW")
            Cluefolk.close()
            finish()
            return
        }
        val content = Cluefolk.attachSupport(this, requestId, null)
        if (content == null) {
            finish()
            return
        }
        this.content = content
        setContentView(content.web)
        content.load()
        Cluefolk.supportShown(requestId)
    }

    override fun onDestroy() {
        Cluefolk.detachSupport(this, requestId, isChangingConfigurations)
        super.onDestroy()
    }
}

// Cluefolk retains the view across configuration changes while detaching its Activity context.
// The widget built into the SDK, served for the API origin of the options.
internal class SupportView(
    private val app: Application,
    apiUrl: String,
    private val requestId: String,
) {
    private val url = WidgetAssets.ENTRY
    private val loader = WidgetAssets.loader(app, apiUrl)
    private val context = MutableContextWrapper(app)
    val web = WebView(context)
    private var loaded = false
    private var destroyed = false
    private var fileSelection: ValueCallback<Array<Uri>>? = null

    fun completeFileSelection(files: Array<Uri>) {
        val callback = fileSelection ?: return
        fileSelection = null
        if (destroyed || !Cluefolk.isCurrentSupport(requestId)) callback.onReceiveValue(null)
        else callback.onReceiveValue(files.takeIf { it.isNotEmpty() })
    }

    // JavaScript is limited to the configured HTTPS origin and an origin-checked bridge.
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = true // Only content URIs chosen through the system photo picker (document picker fallback on older devices).
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (destroyed || view !== web || Cluefolk.origin(view.url.orEmpty()) != Cluefolk.origin(url)) return false
                fileSelection?.onReceiveValue(null)
                fileSelection = callback
                val activity = context.baseContext as? SupportActivity
                if (activity?.chooseFiles() == true) return true
                fileSelection = null
                callback.onReceiveValue(null)
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme != "https" || uri.host != WidgetAssets.HOST) return null
                return loader.shouldInterceptRequest(uri) ?: WidgetAssets.notFound()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.isForMainFrame || Cluefolk.origin(request.url.toString()) != Cluefolk.origin(url)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (Cluefolk.isCurrentSupport(requestId)) {
                    Cluefolk.notifyError("WEB_CONTENT_TERMINATED")
                    if (Cluefolk.isCurrentSupport(requestId)) Cluefolk.close()
                }
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "cluefolkNative", setOf(Cluefolk.origin(url))) {
                source, message, origin, mainFrame, reply ->
                if (!Cluefolk.isCurrentSupport(requestId) || !mainFrame || source !== web ||
                    Cluefolk.origin(origin.toString()) != Cluefolk.origin(url)) return@addWebMessageListener
                val raw = message.data
                fun post(id: String, type: String, data: JSONObject = JSONObject()) {
                    if (Cluefolk.isCurrentSupport(requestId) && !destroyed &&
                        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        reply.postMessage(BridgeMessages.reply(id, type, data))
                    }
                }
                if (raw == null || raw.toByteArray(Charsets.UTF_8).size > 131_072) {
                    post(UUID.randomUUID().toString(), BridgeProtocol.SDK_ERROR, JSONObject().put("code", "INVALID_BRIDGE_MESSAGE"))
                    Cluefolk.notifyError("INVALID_BRIDGE_MESSAGE")
                    return@addWebMessageListener
                }
                val fallbackId = BridgeMessages.requestId(raw) ?: UUID.randomUUID().toString()
                try {
                    val request = BridgeMessages.parse(raw)
                    val payload = request.payload
                    fun respond(type: String, data: JSONObject = JSONObject()) {
                        post(request.id, type, data)
                    }
                    when (request.type) {
                        !in BridgeProtocol.NATIVE_REQUESTS ->
                            respond(BridgeProtocol.SDK_ERROR, JSONObject().put("code", "UNSUPPORTED_COMMAND"))
                        BridgeProtocol.NATIVE_READY, BridgeProtocol.NATIVE_AUTH_REQUIRED -> Cluefolk.bootstrap { result, error ->
                            if (error != null || result == null)
                                respond(BridgeProtocol.SDK_ERROR, JSONObject().put("code", error ?: "SUPPORT_UNAVAILABLE"))
                            else respond(BridgeProtocol.SDK_BOOTSTRAP, result)
                        }
                        BridgeProtocol.NATIVE_DRAFT_GET, BridgeProtocol.NATIVE_DRAFT_PUT -> {
                            // Draft content is opaque to the SDK apart from its size limit.
                            val write = request.type == BridgeProtocol.NATIVE_DRAFT_PUT
                            Cluefolk.draft(payload.getString("key"), payload.optJSONObject("content"), write) { result, error ->
                                if (error != null || result == null)
                                    respond(BridgeProtocol.SDK_ERROR, JSONObject().put("code", error ?: "DRAFT_STORAGE_UNAVAILABLE"))
                                else respond(BridgeProtocol.SDK_ACK, result)
                            }
                        }
                        BridgeProtocol.NATIVE_IMAGE_PUT, BridgeProtocol.NATIVE_IMAGE_GET -> {
                            // The player's own preview of an image they sent; the server never returns images.
                            val image = BridgeMessages.image(request.type, payload)
                            Cluefolk.image(image.id, image.data) { result, error ->
                                if (error != null || result == null)
                                    respond(BridgeProtocol.SDK_ERROR, JSONObject().put("code", error ?: "IMAGE_STORAGE_UNAVAILABLE"))
                                else respond(BridgeProtocol.SDK_ACK, result)
                            }
                        }
                        BridgeProtocol.NATIVE_HISTORY_PAGE -> {
                            respond(BridgeProtocol.SDK_ACK, Cluefolk.historyPage(payload.getString("syncId"), payload.getInt("index")))
                        }
                        BridgeProtocol.NATIVE_CLOSE -> {
                            respond(BridgeProtocol.SDK_ACK)
                            Cluefolk.close()
                        }
                        BridgeProtocol.NATIVE_UNREAD_COUNT_CHANGED -> {
                            Cluefolk.notifyUnreadChanged(payload.getInt("count"))
                            respond(BridgeProtocol.SDK_ACK)
                        }
                        BridgeProtocol.NATIVE_CONVERSATION_CREATED -> {
                            Cluefolk.notifyConversationCreated(identifier(payload, "conversationId"))
                            respond(BridgeProtocol.SDK_ACK)
                        }
                        BridgeProtocol.NATIVE_CONVERSATION_STATUS_CHANGED -> {
                            val status = payload.getString("status")
                            require(status in setOf("open", "pending_agent", "pending_player", "resolved", "closed"))
                            Cluefolk.notifyConversationStatusChanged(identifier(payload, "conversationId"), status)
                            respond(BridgeProtocol.SDK_ACK)
                        }
                        BridgeProtocol.NATIVE_MESSAGE_SENT -> {
                            Cluefolk.notifyMessageSent(identifier(payload, "conversationId"), identifier(payload, "messageId"))
                            respond(BridgeProtocol.SDK_ACK)
                        }
                    }
                } catch (_: Exception) {
                    post(fallbackId, BridgeProtocol.SDK_ERROR, JSONObject().put("code", "INVALID_BRIDGE_MESSAGE"))
                    if (Cluefolk.isCurrentSupport(requestId)) Cluefolk.notifyError("INVALID_BRIDGE_MESSAGE")
                }
            }
        }
    }

    init { configure() }

    private fun identifier(payload: JSONObject, key: String): String =
        payload.getString(key).also { require(it.length in 1..128) }

    fun attach(activity: Activity) {
        (web.parent as? ViewGroup)?.removeView(web)
        context.baseContext = activity
    }

    fun detach() {
        (web.parent as? ViewGroup)?.removeView(web)
        context.baseContext = app
    }

    fun load() {
        if (loaded) return
        loaded = true
        web.loadUrl(url)
    }

    fun navigate(target: JSONObject) {
        if (destroyed || !loaded) return
        web.evaluateJavascript("window.dispatchEvent(new CustomEvent('cluefolk:navigate',{detail:$target}))", null)
    }

    fun setLanguage(language: String) {
        if (destroyed || !loaded) return
        val json = JSONObject.quote(language)
        web.evaluateJavascript("window.dispatchEvent(new CustomEvent('cluefolk:language',{detail:$json}))", null)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        fileSelection?.onReceiveValue(null)
        fileSelection = null
        web.webChromeClient = null
        detach()
        web.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(web, "cluefolkNative")
        web.clearHistory()
        web.removeAllViews()
        web.destroy()
    }
}
