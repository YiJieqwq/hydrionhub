package io.hydrion.hub.ui.messages

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

private const val origin = "https://rich.hydrion.invalid"
internal const val richPage = "$origin/index.html"
internal data class RichRequest(val items: List<RenderItem>, val width: Int, val ink: Int, val dark: Boolean)
internal class RichImage(val bitmap: ImageBitmap, val width: Float, val height: Float)
internal typealias RichRaster = suspend (Context, RichRequest, (WebView?) -> Unit) -> Map<Int, RichImage>

private val rendererSlot = Semaphore(1)
private val imageCache = object : LruCache<RichRequest, Map<Int, RichImage>>(12 * 1024 * 1024) {
    override fun sizeOf(key: RichRequest, value: Map<Int, RichImage>) = 1024 + key.items.sumOf { it.source.length * 2 } +
        value.values.sumOf { it.bitmap.width * it.bitmap.height * 4 }
}

internal fun richAsset(context: Context, url: String, method: String): WebResourceResponse {
    val name = when (url) {
        richPage -> "index.html"
        "$origin/render.js" -> "render.js"
        "$origin/mathjax.js" -> "mathjax.js"
        "$origin/mermaid.js" -> "mermaid.js"
        else -> null
    }
    if (method != "GET" || name == null) return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
        emptyMap(), ByteArrayInputStream(ByteArray(0)))
    // Android's asset merger expands .gz inputs and drops the suffix.
    val stream = context.assets.open("rich/$name")
    return WebResourceResponse(if (name == "index.html") "text/html" else "application/javascript", "utf-8", 200, "OK",
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        stream)
}

@Suppress("DEPRECATION", "SetJavaScriptEnabled")
internal fun newRichWebView(context: Context, ready: CompletableDeferred<Boolean>, onRendererGone: () -> Unit = {}) = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        domStorageEnabled = false
        databaseEnabled = false
        blockNetworkLoads = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
    }
    isFocusable = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
            richAsset(context, request.url.toString(), request.method)
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        override fun onPageFinished(view: WebView, url: String) { ready.complete(url == richPage) }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            ready.complete(false)
            onRendererGone()
            return true
        }
    }
    setDownloadListener { _, _, _, _, _ -> }
}

private suspend fun WebView.readScript(expression: String): String = suspendCancellableCoroutine { callback ->
    evaluateJavascript(expression) { if (callback.isActive) callback.resume(it ?: "null") }
}

private fun RichRequest.payload(): String {
    val array = JSONArray()
    for (item in items) array.put(JSONObject().put("kind", item.kind).put("source", item.source))
    return JSONObject().put("items", array).put("width", width).put("ink", "#%06x".format(ink and 0xffffff))
        .put("dark", dark).toString()
}

private suspend fun WebView.readImage(slot: Int, meta: JSONArray): RichImage? {
    val width = meta.getDouble(0).toFloat()
    val height = meta.getDouble(1).toFloat()
    val length = meta.getInt(2)
    if (width !in 0.01f..8192f || height !in 0.01f..8192f || length !in 1..1500000) return null
    val data = StringBuilder(length)
    while (data.length < length) {
        // The index and offset originate here. Source text is only ever sent as WebMessage data.
        val chunk = readScript("window.richChunk($slot," + data.length + ")")
        if (chunk.length !in 3..131074) return null
        val value = JSONTokener(chunk).nextValue() as? String ?: return null
        if (value.isEmpty() || value.length > minOf(length - data.length, 65536)) return null
        data.append(value)
    }
    if (data.length != length) return null
    return withContext(Dispatchers.Default) {
        val bytes = Base64.decode(data.toString(), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..1600 || bounds.outHeight !in 1..1400) return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { RichImage(it.asImageBitmap(), width, height) }
    }
}

private suspend fun renderLocal(context: Context, job: RichRequest, attach: (WebView?) -> Unit): Map<Int, RichImage> =
    rendererSlot.withPermit {
        imageCache.get(job)?.let { return@withPermit it }
        val ready = CompletableDeferred<Boolean>()
        var rendererGone = false
        val view = newRichWebView(context, ready) { rendererGone = true }
        val images = mutableMapOf<Int, RichImage>()
        try {
            attach(view)
            withTimeoutOrNull(25000L) {
                view.loadUrl(richPage)
                if (!ready.await()) return@withTimeoutOrNull
                view.postWebMessage(WebMessage(job.payload()), Uri.parse(origin))
                for (i in job.items.indices) {
                    var answer = "null"
                    while (answer == "null") {
                        if (rendererGone) return@withTimeoutOrNull
                        answer = view.readScript("window.richMeta ? window.richMeta($i) : null")
                        if (answer == "null") delay(60)
                    }
                    if (answer.startsWith("[")) view.readImage(i, JSONArray(answer))?.let { images[i] = it }
                }
                if (images.isNotEmpty()) imageCache.put(job, images)
            }
        } finally {
            attach(null)
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            if (!rendererGone) view.stopLoading()
            view.destroy()
        }
        images
    }

internal val LocalRichRaster = staticCompositionLocalOf<RichRaster> { ::renderLocal }
internal class RichImages { var entries by mutableStateOf<Map<Int, RichImage>>(emptyMap()) }

@Composable
internal fun RichRenderHost(plan: RichMessagePlan, width: Int, images: RichImages) {
    val render = LocalRichRaster.current
    val context = LocalContext.current
    val ink = LocalMessageStyle.current.renderPalette.assistant.text.messageColor().toArgb()
    val dark = LocalMessageStyle.current.tone == MessageTone.Dark
    val job = remember(plan, width, ink, dark) { RichRequest(plan.items, width.coerceIn(120, 800), ink, dark) }
    var view by remember(job) { mutableStateOf<WebView?>(null) }
    LaunchedEffect(job, render) {
        images.entries = emptyMap()
        if (job.items.isNotEmpty()) {
            try { images.entries = render(context, job) { view = it } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Source is already visible if the local WebView is unavailable. */ }
        }
    }
    view?.let { worker ->
        key(worker) {
            AndroidView(factory = { worker }, modifier = Modifier.size(1.dp).alpha(0f).clearAndSetSemantics { })
        }
    }
}
