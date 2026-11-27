package com.paperreader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
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

    @JavascriptInterface
    fun onHighlight(id: Long) {
        main.post { vm.popup = Popup.Highlight(id) }
    }

    @JavascriptInterface
    fun onLostHighlights(idsJson: String) {
        val ids = runCatching {
            val a = org.json.JSONArray(idsJson)
            (0 until a.length()).map { a.getLong(it) }.toSet()
        }.getOrDefault(emptySet())
        main.post { vm.lostHighlights = ids }
    }
}

/** Actions added to the text-selection menu. */
enum class SelectionAction(val label: String) { HIGHLIGHT("Highlight"), NOTE("Note") }

/** WebView whose text-selection menu also offers [SelectionAction]s. */
@SuppressLint("ViewConstructor")
class ReaderWebView(context: Context) : WebView(context) {
    var onSelectionAction: (SelectionAction) -> Unit = {}

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    private fun wrap(inner: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = inner.onCreateActionMode(mode, menu)
            SelectionAction.entries.forEachIndexed { i, a ->
                menu.add(Menu.NONE, MENU_BASE + i, i, a.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = inner.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = SelectionAction.entries.getOrNull(item.itemId - MENU_BASE)
                ?: return inner.onActionItemClicked(mode, item)
            onSelectionAction(action)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }

    private companion object {
        const val MENU_BASE = 0x5052 // "PR"
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createReaderWebView(
    context: Context,
    booksDir: File,
    vm: ReaderViewModel,
    shell: () -> String,
    openExternal: (Uri) -> Unit,
): ReaderWebView {
    val loader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/books/", BookPathHandler(booksDir, shell))
        .build()
    return ReaderWebView(context).apply {
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
