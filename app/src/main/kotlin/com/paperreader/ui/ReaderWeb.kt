package com.paperreader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File

private val MIME = mapOf(
    "xhtml" to "application/xhtml+xml",
    "html" to "text/html",
    "htm" to "text/html",
    "css" to "text/css",
    "js" to "text/javascript",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "svg" to "image/svg+xml",
    "webp" to "image/webp",
    "ttf" to "font/ttf",
    "otf" to "font/otf",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
)

/**
 * Serves unpacked books from filesDir/books under https://appassets.androidplatform.net/books/.
 * "<folder>/__reader__.html" is the generated shell page that reader.js fills with the chapters.
 */
class BookPathHandler(
    booksDir: File,
    private val shell: () -> String,
) : WebViewAssetLoader.PathHandler {
    private val root = booksDir.canonicalFile

    override fun handle(path: String): WebResourceResponse {
        if (path.substringAfter('/') == SHELL_PAGE) {
            return WebResourceResponse("text/html", "utf-8", shell().byteInputStream())
        }
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
        }
        val mime = MIME[file.extension.lowercase()] ?: "application/octet-stream"
        return WebResourceResponse(mime, null, file.inputStream())
    }
}

fun shellPage(config: String, theme: String): String {
    val base = "https://$ASSET_HOST/assets/reader"
    return """<!DOCTYPE html>
<html><head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta name="pr-config" content="${htmlAttr(config)}"/>
<script>
window.addEventListener('error', function (e) {
  console.error('reader error: ' + e.message + ' @' + e.filename + ':' + e.lineno);
  var s = document.getElementById('pr-strip');
  if (s) s.insertAdjacentHTML('beforeend', '<pre style="white-space:pre-wrap;padding:1em;color:#b00">' + String(e.message).replace(/</g, '&lt;') + '</pre>');
});
</script>
<link id="pr-base-css" rel="stylesheet" href="$base/reader.css"/>
<link id="pr-theme" rel="stylesheet" href="$base/theme-$theme.css"/>
<script src="$base/reader.js" defer></script>
</head><body><div id="pr-strip"><div class="pr-loading">Opening…</div></div></body></html>"""
}

private fun htmlAttr(s: String) = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

/** Receives events from reader.js. Called on a WebView binder thread. */
class ReaderBridge(private val vm: ReaderViewModel) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onChapter(index: Int) {
        main.post { vm.onChapter(index) }
    }

    @JavascriptInterface
    fun onProgress(index: Int, fraction: Double) {
        main.post { vm.onProgress(index, fraction.toFloat()) }
    }

    @JavascriptInterface
    fun onNoteref(target: String, text: String) {
        main.post { vm.popup = Popup.Note(target, text) }
    }

    @JavascriptInterface
    fun onFigureRef(target: String, src: String, caption: String) {
        main.post { vm.popup = Popup.Figure(target, src, caption) }
    }

    @JavascriptInterface
    fun onImage(src: String) {
        main.post { vm.popup = Popup.Image(src) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createReaderWebView(
    context: Context,
    booksDir: File,
    vm: ReaderViewModel,
    shell: () -> String,
    openExternal: (Uri) -> Unit,
): WebView {
    val loader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/books/", BookPathHandler(booksDir, shell))
        .build()
    return WebView(context).apply {
        // Without explicit MATCH_PARENT the WebView sizes its viewport to the content,
        // and the reader's full-height chapter columns (height: 100%) collapse to 0.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ASSET_HOST) return false
                openExternal(request.url)
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i("PaperReaderWeb", "${m.messageLevel()} ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                return true
            }
        }
        addJavascriptInterface(ReaderBridge(vm), "PaperReader")
    }
}

/** Quotes [s] as a JavaScript string literal. */
fun jsString(s: String): String = org.json.JSONObject.quote(s)
