package io.hydrion.hub.ui.messages

import android.content.Context
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.hydrion.hub.R
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer

private val markdownParser = Parser.builder().build()
private val previewRenderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true)
    .attributeProviderFactory { AttributeProvider { _, _, attrs ->
        attrs.remove("href")
        attrs.remove("src")
    } }.build()

internal fun renderReplyPreview(markdown: String): String {
    val body = previewRenderer.render(markdownParser.parse(markdown))
    return """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
        <style>:root{color-scheme:light dark}body{font:1rem/1.6 system-ui;margin:1rem;overflow-wrap:anywhere}
        pre{overflow-x:auto;padding:.6rem}blockquote{margin-inline:0;padding-inline-start:1rem;border-inline-start:2px solid gray}</style>
        </head><body>$body</body></html>"""
}

@Suppress("DEPRECATION")
internal fun replyPreviewView(context: Context) = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = false
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        blockNetworkImage = true
        setSupportMultipleWindows(false)
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
    }
}

@Composable
internal fun ReplyWebPreview(source: String, close: () -> Unit) {
    val context = LocalContext.current
    val payload by produceState<String?>(null, source) {
        value = null
        value = withContext(Dispatchers.Default) {
            Base64.encodeToString(renderReplyPreview(source).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.turn_web_preview), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = close) { Text(stringResource(android.R.string.ok)) }
                }
                val data = payload
                if (data == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else key(data) {
                    val view = remember(context) { try { replyPreviewView(context) } catch (_: RuntimeException) { null } }
                    if (view == null) Text(stringResource(R.string.turn_web_unavailable), Modifier.padding(16.dp))
                    else AndroidView(factory = { view.apply { loadData(data, "text/html", "base64") } },
                        modifier = Modifier.fillMaxSize(), onRelease = { it.stopLoading(); it.destroy() })
                }
            }
        }
    }
}
