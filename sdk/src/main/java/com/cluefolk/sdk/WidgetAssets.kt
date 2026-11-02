package com.cluefolk.sdk

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.IOException

// The widget ships inside the SDK (assets/cluefolk-widget, vendored from the
// widget build) and is served from Android's reserved WebViewAssetLoader origin,
// so opening support downloads no page.
internal object WidgetAssets {
    const val HOST = "appassets.androidplatform.net"
    const val ENTRY = "https://$HOST/cluefolk/index.html"

    fun loader(context: Context, apiUrl: String): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/cluefolk/", Handler(context.applicationContext, policy(apiUrl)))
            .build()

    // Only the API origin of these options and the files host next to it
    // (files.cluefolk.com, uploads only); images are inline or blobs.
    internal fun policy(apiUrl: String): String {
        val api = Cluefolk.origin(apiUrl)
        val host = api.substringAfter("://")
        val files = "https://files." + host.substringBefore(':')
        return "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; " +
            "img-src 'self' data: blob:; connect-src $api wss://$host $files; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }

    fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private class Handler(private val context: Context, private val policy: String) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse {
            if (path != "index.html" && !Regex("assets/[A-Za-z0-9_.-]+").matches(path)) return notFound()
            val type = when (path.substringAfterLast('.')) {
                "html" -> "text/html"
                "js" -> "text/javascript"
                "css" -> "text/css"
                else -> return notFound()
            }
            val stream = try {
                context.assets.open("cluefolk-widget/$path")
            } catch (_: IOException) {
                return notFound()
            }
            return WebResourceResponse(type, "utf-8", 200, "OK", mapOf(
                "Content-Security-Policy" to policy,
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "no-referrer",
                "Cache-Control" to "no-store",
            ), stream)
        }
    }
}
