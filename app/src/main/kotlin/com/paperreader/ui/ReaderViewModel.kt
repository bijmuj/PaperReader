package com.paperreader.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperreader.PaperReaderApp
import com.paperreader.data.Book
import com.paperreader.data.Progress
import com.paperreader.epub.EpubBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

const val ASSET_HOST = "appassets.androidplatform.net"

/** Virtual page that assembles the whole book; served by [BookPathHandler]. */
const val SHELL_PAGE = "__reader__.html"

sealed interface Popup {
    /** [target] is a local element id in the book page, for "Go to". */
    data class Note(val target: String, val text: String) : Popup
    data class Figure(val target: String, val src: String, val caption: String) : Popup
    data class Image(val src: String) : Popup
}

class ReaderViewModel(private val app: PaperReaderApp, private val bookId: Long) : ViewModel() {
    var book by mutableStateOf<Book?>(null)
        private set
    var epub by mutableStateOf<EpubBook?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Index into the spine of the chapter on screen. */
    var chapter by mutableIntStateOf(0)
        private set
    var popup by mutableStateOf<Popup?>(null)

    /** Scroll fraction per chapter href. Read by the request handler thread. */
    private val fractions = ConcurrentHashMap<String, Float>()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val b = app.library.get(bookId) ?: run { error = "This book is no longer in the library."; return }
        val e = withContext(Dispatchers.IO) { runCatching { EpubBook.parse(app.library.folder(b)) } }
            .getOrElse { error = "Could not open this book: ${it.message}"; return }
        val dao = app.db.books()
        dao.progress(bookId)?.let { saved ->
            runCatching {
                val obj = JSONObject(saved.chapters)
                obj.keys().forEach { k -> fractions[k] = obj.getDouble(k).toFloat() }
            }
            fractions.putIfAbsent(saved.href, saved.fraction)
            chapter = e.spine.indexOf(saved.href).coerceAtLeast(0)
        }
        dao.touch(bookId, System.currentTimeMillis())
        book = b
        epub = e
    }

    val shellUrl: String? get() = book?.let { "https://$ASSET_HOST/books/${it.folder}/$SHELL_PAGE" }

    fun urlFor(href: String): String? = book?.let { urlFor(it, href) }

    private fun urlFor(b: Book, href: String): String {
        val (path, frag) = href.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
        val encoded = path.split('/').joinToString("/") { Uri.encode(it) }
        return "https://$ASSET_HOST/books/${b.folder}/$encoded" + (frag?.let { "#$it" } ?: "")
    }

    /** Title of the chapter at [index]: its first table-of-contents entry. */
    fun chapterTitle(index: Int): String {
        val e = epub ?: return ""
        val href = e.spine.getOrNull(index) ?: return ""
        return e.toc.firstOrNull { it.href.substringBefore('#') == href }?.title ?: ""
    }

    /** Config for reader.js: chapter URLs/titles and where to resume. */
    fun shellConfig(): String {
        val b = book ?: return "{}"
        val e = epub ?: return "{}"
        val chapters = JSONArray()
        val fr = JSONObject()
        e.spine.forEachIndexed { i, href ->
            chapters.put(JSONObject().put("url", urlFor(b, href)).put("title", chapterTitle(i)))
            fractions[href]?.let { fr.put(i.toString(), it.toDouble()) }
        }
        return JSONObject()
            .put("chapters", chapters)
            .put("fractions", fr)
            .put("current", chapter)
            .toString()
    }

    /** Local file behind a reader URL (images for the zoom viewer). */
    fun fileFor(url: String): File? {
        val b = book ?: return null
        val uri = Uri.parse(url)
        if (uri.host != ASSET_HOST) return null
        val prefix = "/books/${b.folder}/"
        val path = uri.path?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
        val root = app.library.folder(b).canonicalFile
        val f = File(root, path).canonicalFile
        return f.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    fun onChapter(index: Int) {
        val e = epub ?: return
        if (index !in e.spine.indices) return
        chapter = index
        scheduleSave()
    }

    fun onProgress(index: Int, fraction: Float) {
        val href = epub?.spine?.getOrNull(index) ?: return
        fractions[href] = fraction.coerceIn(0f, 1f)
        scheduleSave()
    }

    private fun scheduleSave() {
        val e = epub ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            val href = e.spine[chapter]
            val all = JSONObject()
            fractions.forEach { (k, v) -> all.put(k, v.toDouble()) }
            app.db.books().saveProgress(
                Progress(bookId, href, fractions[href] ?: 0f, System.currentTimeMillis(), all.toString()),
            )
        }
    }
}
