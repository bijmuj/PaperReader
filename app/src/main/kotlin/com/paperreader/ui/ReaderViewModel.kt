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
import com.paperreader.data.Highlight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** [pdfPage] and [pdfClip]: where the figure is in the original PDF (-1 and "" if unknown). */
    data class Figure(val target: String, val src: String, val caption: String, val pdfPage: Int = -1, val pdfClip: String = "") : Popup
    data class Image(val src: String, val pdfPage: Int = -1, val pdfClip: String = "") : Popup
    /** A page of the original PDF, outlining [clip] ("x0,y0,x1,y1" in points) if given. */
    data class PdfPage(val page: Int, val clip: String = "") : Popup
    /** A highlight's sheet; [editNote] focuses the note field (the "Note" selection action). */
    data class Highlight(val id: Long, val editNote: Boolean = false) : Popup
}

val HIGHLIGHT_COLORS = listOf("yellow", "green", "blue", "pink")

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

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    /** This book's highlights, oldest first. Read by the request handler thread too. */
    val highlights: StateFlow<List<Highlight>> = _highlights

    /** Highlights whose text could not be found in the book any more. */
    var lostHighlights by mutableStateOf<Set<Long>>(emptySet())

    /** Colour for new highlights: the last one picked. */
    private var lastColor = HIGHLIGHT_COLORS[0]

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
        _highlights.value = dao.highlights(bookId)
        _highlights.value.lastOrNull()?.let { lastColor = it.color }
        book = b
        epub = e
        viewModelScope.launch { dao.observeHighlights(bookId).collect { _highlights.value = it } }
    }

    fun highlight(id: Long): Highlight? = _highlights.value.firstOrNull { it.id == id }

    /** The highlight as reader.js expects it. */
    fun highlightJson(h: Highlight): String = JSONObject()
        .put("id", h.id)
        .put("chapter", epub?.spine?.indexOf(h.chapter) ?: -1)
        .put("start", h.start)
        .put("end", h.end)
        .put("quote", h.quote)
        .put("prefix", h.prefix)
        .put("suffix", h.suffix)
        .put("color", h.color)
        .put("note", h.note.isNotBlank())
        .toString()

    /** Saves a highlight for a selection captured by reader.js (prTakeSelection). */
    suspend fun addHighlight(selection: JSONObject): Highlight? {
        val href = epub?.spine?.getOrNull(selection.optInt("chapter", -1)) ?: return null
        val now = System.currentTimeMillis()
        val h = Highlight(
            bookId = bookId,
            chapter = href,
            start = selection.getInt("start"),
            end = selection.getInt("end"),
            quote = selection.getString("quote"),
            prefix = selection.optString("prefix"),
            suffix = selection.optString("suffix"),
            color = lastColor,
            createdAt = now,
            updatedAt = now,
        )
        val id = app.db.books().insertHighlight(h)
        return h.copy(id = id).also { _highlights.value = _highlights.value + it }
    }

    fun setHighlightColor(id: Long, color: String) {
        lastColor = color
        _highlights.value = _highlights.value.map { if (it.id == id) it.copy(color = color) else it }
        viewModelScope.launch { app.db.books().setHighlightColor(id, color, System.currentTimeMillis()) }
    }

    fun setHighlightNote(id: Long, note: String) {
        if (highlight(id)?.note == note) return
        _highlights.value = _highlights.value.map { if (it.id == id) it.copy(note = note) else it }
        viewModelScope.launch { app.db.books().setHighlightNote(id, note, System.currentTimeMillis()) }
    }

    fun deleteHighlight(id: Long) {
        _highlights.value = _highlights.value.filter { it.id != id }
        viewModelScope.launch { app.db.books().deleteHighlight(id) }
    }

    val shellUrl: String? get() = book?.let { "https://$ASSET_HOST/books/${it.folder}/$SHELL_PAGE" }

    fun urlFor(href: String): String? = book?.let { urlFor(it, href) }

    private fun urlFor(b: Book, href: String): String {
        val (path, frag) = href.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
        val encoded = path.split('/').joinToString("/") { Uri.encode(it) }
        return "https://$ASSET_HOST/books/${b.folder}/$encoded" + (frag?.let { "#$it" } ?: "")
    }

    /** Title of the chapter at [index]: its first table-of-contents entry. */
    /**
     * The highlights and notes as Markdown, in reading order under their section headings.
     * [exported] is prHighlightExport()'s result: each highlight's text (formulas as TeX) and
     * heading; highlights missing from it (not found in the text) use the stored quote.
     */
    fun notesMarkdown(exported: org.json.JSONArray): String {
        val fromPage = HashMap<Long, Pair<String, String>>()
        for (i in 0 until exported.length()) {
            val o = exported.getJSONObject(i)
            fromPage[o.getLong("id")] = o.optString("section") to o.optString("text")
        }
        val spine = epub?.spine.orEmpty()
        val sorted = _highlights.value.sortedWith(compareBy({ spine.indexOf(it.chapter) }, { it.start }))
        val out = StringBuilder()
        val b = book
        out.append("# ").append(b?.title ?: "Notes").append("\n\n")
        if (!b?.authors.isNullOrBlank()) out.append(b?.authors).append("\n\n")
        var lastSection: String? = null
        for (h in sorted) {
            val (pageSection, pageText) = fromPage[h.id] ?: ("" to "")
            // A paper without detected headings is one chapter named after the paper: no heading then.
            val section = pageSection.ifBlank { chapterTitle(spine.indexOf(h.chapter)) }
                .takeUnless { it.trim().equals(b?.title?.trim(), ignoreCase = true) }.orEmpty()
            if (section != lastSection) {
                if (section.isNotBlank()) out.append("## ").append(section).append("\n\n")
                lastSection = section
            }
            val text = pageText.ifBlank { h.quote.replace(Regex("\\s+"), " ").trim() }
            out.append(text.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }).append("\n\n")
            if (h.note.isNotBlank()) out.append(h.note.trim()).append("\n\n")
        }
        return out.toString().trimEnd() + "\n"
    }

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
        val hl = JSONArray()
        _highlights.value.forEach { hl.put(JSONObject(highlightJson(it))) }
        return JSONObject()
            .put("chapters", chapters)
            .put("fractions", fr)
            .put("current", chapter)
            .put("highlights", hl)
            .toString()
    }

    /** Local file behind a reader URL (images for the zoom viewer). */
    /** The PDF this book was converted from, if it was. */
    val originalPdf: File?
        get() = book?.let { File(app.library.folder(it), com.paperreader.epub.ORIGINAL_PDF).takeIf(File::isFile) }

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
