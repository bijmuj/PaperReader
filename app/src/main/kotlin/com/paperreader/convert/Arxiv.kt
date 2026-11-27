package com.paperreader.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document.OutputSettings
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * arXiv paper -> Document, ported from converter/src/paper2epub/arxiv.py.
 *
 * Text, MathML, tables, citations, footnotes and cross-references come from
 * arXiv's LaTeXML HTML. Figures come from the LaTeX source when they can be
 * matched (vector PDFs rendered at ~3000 px with the \includegraphics trims),
 * otherwise from arXiv's own images.
 */

private const val TAG = "Arxiv"
private const val USER_AGENT = "paper2epub/0.1 (Android)"
private const val MATHML_NS = "http://www.w3.org/1998/Math/MathML"
private const val MAX_IMAGE_PX = 4000
private const val TARGET_IMAGE_PX = 3000

data class ArxivId(val base: String, val version: Int?)

object Arxiv {
    private val NEW_ID = Regex("""(\d{4}\.\d{4,5})(v\d+)?""")
    private val OLD_ID = Regex("""([a-zA-Z][a-zA-Z.-]+/\d{7})(v\d+)?""")
    private val URL_ID = Regex("""arxiv\.org/(?:abs|pdf|html|src)/(.+?)(?:\.pdf)?/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val ANYWHERE = Regex("""arxiv\.org/(?:abs|pdf|html)/([^\s?#]+)|(?:arxiv:\s*)?\b(\d{4}\.\d{4,5}(?:v\d+)?)\b""", RegexOption.IGNORE_CASE)

    /** '2604.14191v2', 'arXiv:2604.14191', an abs/pdf/html URL... */
    fun parseId(text: String): ArxivId? {
        var s = text.trim().replace(Regex("^arxiv:", RegexOption.IGNORE_CASE), "")
        URL_ID.find(s)?.let { s = it.groupValues[1] }
        for (re in listOf(NEW_ID, OLD_ID)) {
            val m = re.matchEntire(s) ?: continue
            return ArxivId(m.groupValues[1], m.groupValues[2].takeIf { it.isNotEmpty() }?.drop(1)?.toInt())
        }
        return null
    }

    /** Finds an arXiv id or link inside shared text (e.g. "Check this out https://arxiv.org/abs/..."). */
    fun findId(text: String): ArxivId? {
        for (m in ANYWHERE.findAll(text)) {
            val candidate = m.groupValues[1].ifEmpty { m.groupValues[2] }
            parseId(candidate.removeSuffix(".pdf"))?.let { return it }
        }
        return null
    }
}

/** Polite, cached HTTP access to arxiv.org. */
class ArxivClient(private val cacheDir: File) {
    private var lastRequest = 0L
    private val absPages = HashMap<String, String>()

    @Synchronized
    fun get(url: String): ByteArray? {
        val wait = 1000 - (System.currentTimeMillis() - lastRequest)
        if (wait > 0) Thread.sleep(wait)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            try {
                val code = conn.responseCode
                if (code == 404) return null
                if (code >= 400) throw IOException("arXiv answered $code for $url")
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } finally {
            lastRequest = System.currentTimeMillis()
        }
    }

    fun cached(file: File, url: String): ByteArray? {
        if (file.exists()) return file.readBytes().takeIf { it.isNotEmpty() } // empty = known 404
        val data = get(url)
        file.parentFile?.mkdirs()
        file.writeBytes(data ?: ByteArray(0))
        return data
    }

    fun folder(base: String, v: Int) = File(cacheDir, "${base.replace('/', '_')}v$v")

    private fun absPage(base: String): String =
        absPages.getOrPut(base) { get("https://arxiv.org/abs/$base")?.toString(Charsets.UTF_8) ?: "" }

    fun latestVersion(base: String): Int =
        Regex("/abs/${Regex.escape(base)}v(\\d+)").findAll(absPage(base)).maxOfOrNull { it.groupValues[1].toInt() } ?: 1

    /** Title and authors ("First Last") from the abstract page's citation tags. */
    fun metadata(base: String): Pair<String, List<String>> {
        val page = absPage(base)
        val title = Regex("""<meta name="citation_title" content="([^"]*)"""").find(page)?.groupValues?.get(1)
        val authors = Regex("""<meta name="citation_author" content="([^"]*)"""").findAll(page).map {
            val a = Entities.unescape(it.groupValues[1])
            val last = a.substringBefore(", ")
            val first = a.substringAfter(", ", "")
            "$first $last".trim()
        }.toList()
        return (title?.let { Entities.unescape(it) } ?: "") to authors
    }

    /** HTML for [version] (default latest), else the nearest version that has one. */
    fun fetchHtml(base: String, version: Int?, warn: (String) -> Unit): Pair<Int, String> {
        val latest = latestVersion(base)
        val want = min(version ?: latest, latest)
        val order = listOf(want) + (want - 1 downTo 1) + (want + 1..latest)
        for (v in order) {
            val data = cached(File(folder(base, v), "page.html"), "https://arxiv.org/html/${base}v$v") ?: continue
            val text = data.toString(Charsets.UTF_8)
            if ("ltx_document" !in text) continue
            if (v != want) warn("arXiv has no HTML for v$want; using v$v")
            return v to text
        }
        throw IOException("arXiv has no HTML version of $base (its LaTeX conversion failed). PDF conversion isn't on the phone yet.")
    }

    /** Unpacks the LaTeX source of [base]v[v]; returns its folder, or null. */
    fun fetchSource(base: String, v: Int): File? {
        val folder = folder(base, v)
        val src = File(folder, "src")
        if (src.isDirectory) return src
        val data = cached(File(folder, "source.bin"), "https://arxiv.org/src/${base}v$v") ?: return null
        val raw: ByteArray = if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            try {
                GZIPInputStream(data.inputStream()).use { it.readBytes() }
            } catch (e: IOException) {
                return null
            }
        } else {
            data
        }
        val tmp = File(folder, "src.tmp").apply { deleteRecursively(); mkdirs() }
        val isTar = raw.size >= 512 && String(raw, 257, 5, Charsets.ISO_8859_1) == "ustar"
        when {
            isTar -> Tar.extract(raw.inputStream(), tmp)
            raw.size >= 5 && String(raw, 0, 5, Charsets.ISO_8859_1) == "%PDF-" -> {
                tmp.deleteRecursively()
                return null // PDF-only submission
            }
            else -> File(tmp, "main.tex").writeBytes(raw)
        }
        tmp.renameTo(src)
        return src
    }
}

/** Minimal streaming tar reader (ustar, GNU long names, pax paths) that refuses paths escaping [dest]. */
object Tar {
    fun extract(input: InputStream, dest: File) {
        val root = dest.canonicalPath + File.separator
        val inp = DataInputStream(input)
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            try {
                inp.readFully(header)
            } catch (e: EOFException) {
                return
            }
            if (header.all { it == 0.toByte() }) return
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val ustar = String(header, 257, 5, Charsets.ISO_8859_1) == "ustar"
            val name = cstr(header, 0, 100)
            val prefix = if (ustar) cstr(header, 345, 155) else ""
            val path = longName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L', 'x' -> {
                    val body = ByteArray(size.toInt()).also { inp.readFully(it) }
                    skip(inp, padded - size)
                    longName = if (type == 'L') {
                        cstr(body, 0, body.size)
                    } else {
                        Regex("""\d+ path=([^\n]*)\n""").find(String(body, Charsets.UTF_8))?.groupValues?.get(1)
                    }
                    continue
                }
                '0', '\u0000', '7' -> {
                    val out = File(dest, path)
                    if (out.canonicalPath.startsWith(root) && !path.startsWith("/")) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o -> copy(inp, o, size) }
                        skip(inp, padded - size)
                    } else {
                        skip(inp, padded)
                    }
                }
                else -> skip(inp, padded)
            }
            longName = null
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = cstr(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun skip(inp: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = inp.skip(left)
            if (k <= 0) {
                if (inp.read() < 0) throw EOFException()
                left--
            } else {
                left -= k
            }
        }
    }

    private fun copy(inp: InputStream, out: java.io.OutputStream, n: Long) {
        val buf = ByteArray(64 * 1024)
        var left = n
        while (left > 0) {
            val k = inp.read(buf, 0, min(buf.size.toLong(), left).toInt())
            if (k < 0) throw EOFException()
            out.write(buf, 0, k)
            left -= k
        }
    }
}

// -- figures -------------------------------------------------------------------

private data class Trim(val left: Float, val bottom: Float, val right: Float, val top: Float)

private val UNITS = mapOf("pt" to 72f / 72.27f, "bp" to 1f, "cm" to 72f / 2.54f, "mm" to 72f / 25.4f, "in" to 72f, "" to 1f)
private val INCLUDE = Regex("""\\includegraphics\s*(?:\[([^\]]*)\])?\s*\{([^}]+)\}""")

/** File stem -> trims (PDF points) used with it in the .tex files. */
private fun trims(source: File): Map<String, List<Trim>> {
    val out = HashMap<String, MutableList<Trim>>()
    source.walkTopDown().filter { it.isFile && it.extension.equals("tex", true) }.forEach { tex ->
        val text = runCatching { tex.readText() }.getOrNull()?.replace(Regex("""(?<!\\)%.*"""), "") ?: return@forEach
        for (m in INCLUDE.findAll(text)) {
            val opts = m.groupValues[1]
            val t = Regex("""trim\s*=\s*\{?([^},]*)\}?""").find(opts) ?: continue
            val parts = Regex("""(-?[\d.]+)\s*(pt|bp|cm|mm|in)?""").findAll(t.groupValues[1]).toList()
            if (parts.size != 4) continue
            val v = parts.map { (it.groupValues[1].toFloatOrNull() ?: 0f) * UNITS.getValue(it.groupValues[2]) }
            val stem = m.groupValues[2].trim().substringAfterLast('/').lowercase()
                .replace(Regex("""\.(pdf|png|jpe?g|eps)$"""), "")
            out.getOrPut(stem) { mutableListOf() }.add(Trim(v[0], v[1], v[2], v[3]))
        }
    }
    return out
}

private fun sameAspect(a: Float, b: Float, tol: Float = 0.04f) = b == 0f || abs(a - b) / b < tol

private fun imageSize(data: ByteArray): Pair<Int, Int> {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, o)
    return max(o.outWidth, 0) to max(o.outHeight, 0)
}

private fun Bitmap.png(): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

private class ArxivImages(
    private val pageUrl: String,
    private val prefix: String,
    source: File?,
    private val client: ArxivClient,
    private val progress: (String) -> Unit,
) {
    private val files = HashMap<String, MutableList<File>>()
    private val trims = source?.let(::trims) ?: emptyMap()
    val images = LinkedHashMap<String, ByteArray>()
    var fromSource = 0
    var fromHtml = 0

    init {
        source?.walkTopDown()?.filter { it.isFile && it.extension.lowercase() in setOf("pdf", "png", "jpg", "jpeg") }?.forEach { f ->
            val rel = f.relativeTo(source).path.substringBeforeLast('.').lowercase()
            files.getOrPut(rel) { mutableListOf() }.add(f)
            files.getOrPut(f.nameWithoutExtension.lowercase()) { mutableListOf() }.add(f)
        }
    }

    /** Returns the EPUB file name and pixel size for an image of the HTML, or null. */
    fun get(src: String, aspect: Float): Triple<String, Int, Int>? {
        var rel = URLDecoder.decode(src, "UTF-8")
        if (rel.startsWith(prefix)) rel = rel.removePrefix(prefix)
        val stem = rel.replace(Regex("""\.[A-Za-z0-9]+$"""), "").lowercase()
        val short = stem.substringAfterLast('/')
        progress("Figure ${images.size + 1}: $short")
        var data: ByteArray? = null
        for (f in files[stem] ?: files[short] ?: emptyList()) {
            data = fromSource(f, aspect, trims[f.nameWithoutExtension.lowercase()] ?: emptyList())
            if (data != null) {
                fromSource++
                break
            }
        }
        if (data == null) {
            data = client.get(URL(URL(pageUrl), src).toString()) ?: return null
            fromHtml++
        }
        val head = String(data, 0, min(300, data.size), Charsets.ISO_8859_1).trimStart()
        val ext = when {
            data.size > 2 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() -> "jpg"
            head.startsWith("<svg") || head.startsWith("<?xml") -> "svg"
            else -> "png"
        }
        val name = "%02d-%s.%s".format(images.size + 1, short.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40), ext)
        images[name] = data
        val (w, h) = if (ext == "svg") 0 to 0 else imageSize(data)
        return Triple(name, w, h)
    }

    private fun fromSource(f: File, aspect: Float, trims: List<Trim>): ByteArray? = try {
        if (f.extension.equals("pdf", true)) renderPdf(f, aspect, trims) else raster(f, aspect)
    } catch (e: Exception) {
        Log.w(TAG, "could not use ${f.name}", e)
        null
    }

    private fun renderPdf(f: File, aspect: Float, trims: List<Trim>): ByteArray? {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val w = page.width.toFloat()
                    val h = page.height.toFloat()
                    // The trims are what the author asked for, so they get more slack than the
                    // untrimmed page (arXiv's sizes are rounded and sometimes approximate).
                    val rects = trims.map { RectF(it.left, it.top, w - it.right, h - it.bottom) to 0.15f } + (RectF(0f, 0f, w, h) to 0.04f)
                    val clip = rects.firstOrNull { (r, tol) ->
                        r.width() > 1 && r.height() > 1 && sameAspect(r.width() / r.height(), aspect, tol)
                    }?.first ?: return null
                    val zoom = min(TARGET_IMAGE_PX / max(clip.width(), clip.height()), 12f)
                    val bw = (clip.width() * zoom).toInt().coerceAtLeast(1)
                    val bh = (clip.height() * zoom).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    try {
                        bmp.eraseColor(Color.WHITE)
                        val m = Matrix().apply {
                            setScale(zoom, zoom)
                            postTranslate(-clip.left * zoom, -clip.top * zoom)
                        }
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp.png()
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }
    }

    private fun raster(f: File, aspect: Float): ByteArray? {
        val data = f.readBytes()
        val (w, h) = imageSize(data)
        if (w == 0 || h == 0 || !sameAspect(w.toFloat() / h, aspect)) return null
        if (max(w, h) <= MAX_IMAGE_PX) return data
        val scale = MAX_IMAGE_PX.toFloat() / max(w, h)
        val full = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val small = Bitmap.createScaledBitmap(full, (w * scale).toInt(), (h * scale).toInt(), true)
        full.recycle()
        return small.png().also { small.recycle() }
    }
}

// -- HTML -> XHTML ---------------------------------------------------------------

private val HTML_TAGS = setOf(
    "a", "abbr", "b", "br", "caption", "cite", "code", "col", "colgroup", "dd", "div", "dl", "dt", "em",
    "figcaption", "figure", "hr", "i", "img", "li", "ol", "p", "pre", "q", "s", "small", "span", "strong",
    "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
)
private val HTML_ATTRS = setOf("id", "class", "href", "src", "alt", "width", "height", "colspan", "rowspan", "role")
private val KEEP_CLASSES = setOf("noteref", "reference", "footnote", "figure", "table", "scroll-x", "runin", "front")
private val DROP_CLASSES = setOf("ltx_ERROR", "ltx_pubnotes", "ltx_bib_cited", "ltx_rdf", "ltx_pagination", "ltx_role_newpage", "ltx_page_navbar")
private val DROP_TAGS = setOf("script", "style", "button", "annotation-xml", "svg", "input")
private val UNWRAP_CLASSES = setOf("ltx_transformed_outer", "ltx_transformed_inner")
private val BLOCK_TAGS = setOf("section", "article", "nav", "header", "footer", "aside", "blockquote")
/** LaTeXML writes tables in inline context (e.g. inside \resizebox) as spans with these classes. */
private val SPAN_TABLE = mapOf(
    "ltx_tabular" to "table", "ltx_thead" to "thead", "ltx_tbody" to "tbody", "ltx_tfoot" to "tfoot",
    "ltx_tr" to "tr", "ltx_td" to "td",
)
private val SECTION_LEVELS = linkedMapOf(
    "ltx_part" to 1, "ltx_chapter" to 1, "ltx_section" to 1, "ltx_appendix" to 1, "ltx_bibliography" to 1,
    "ltx_index" to 1, "ltx_subsection" to 2, "ltx_subsubsection" to 3,
)
private val JUNK = Regex(
    "has been altered|violates the \\w+ style|do not change the page layout|not able to reliably undo|^preprint\\b|^under review",
    RegexOption.IGNORE_CASE,
)

/**
 * Turns LaTeXML's span-built tables into real ones (with col/rowspans), and makes the
 * paragraphs/spans around them blocks so the XHTML stays sensible.
 */
private fun spanTables(el: Element) {
    val tables = mutableListOf<Element>()
    for (node in el.select("span, div")) {
        val kind = node.classNames().firstNotNullOfOrNull { SPAN_TABLE[it] } ?: continue
        node.tagName(kind)
        if (kind == "td") {
            for (c in node.classNames()) {
                Regex("""ltx_(col|row)span_(\d+)""").matchEntire(c)?.let {
                    node.attr("${it.groupValues[1]}span", it.groupValues[2])
                }
            }
        } else if (kind == "table") {
            tables.add(node)
        }
    }
    for (t in tables) {
        for (a in t.parents()) {
            if (a.normalName() == "p" || a.normalName() == "span") a.tagName("div")
            if (a === el) break
        }
    }
    if (tables.isNotEmpty() && (el.normalName() == "p" || el.normalName() == "span")) el.tagName("div")
}

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

/** Visible text, without the TeX source LaTeXML keeps inside <math>. */
private fun visibleText(el: Element): String {
    val c = el.clone()
    c.select("annotation, annotation-xml").remove()
    return c.text().trim()
}

private fun Element.isUnder(root: Element): Boolean {
    var e: Element? = this
    while (e != null) {
        if (e === root) return true
        e = e.parent()
    }
    return false
}

private fun removeComments(node: Node) {
    for (child in node.childNodes().toList()) {
        if (child is Comment) child.remove() else removeComments(child)
    }
}

private class Converter(
    private val pageUrl: String,
    private val base: String,
    private val images: ArxivImages,
) {
    val elements = mutableListOf<DocElement>()
    private val notes = mutableListOf<String>()
    private var runin: String? = null
    private var started = false
    var title = ""

    /** Serialises detached elements as XML (well-formed XHTML). */
    private val scratch = org.jsoup.nodes.Document("").apply {
        outputSettings().syntax(OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
            .charset("UTF-8").prettyPrint(false)
    }

    private fun xml(el: Element): String {
        scratch.appendChild(el)
        val s = el.outerHtml()
        el.remove()
        return s
    }

    // ---- cleaning ----

    /** A deep copy of [src] as clean XHTML text. */
    fun scrub(src: Element): String {
        val el = src.clone()
        removeComments(el)
        notes(el)
        el.select("*").filter { it !== el && (it.classNames().any { c -> c in DROP_CLASSES } || it.normalName() in DROP_TAGS) }
            .forEach { if (it.isUnder(el)) it.remove() }
        el.select("*").filter { it !== el && it.classNames().any { c -> c in UNWRAP_CLASSES } }.forEach { it.unwrap() }
        spanTables(el)
        val mathNodes = HashSet<Element>()
        el.select("math").forEach { mathNodes.addAll(it.select("*")) }
        for (node in el.select("*").toList()) {
            if (node !== el && !node.isUnder(el)) continue // removed with an ancestor
            if (node in mathNodes) {
                node.attributes().asList().map { it.key }
                    .filter { it == "style" || it == "intent" || ':' in it }
                    .forEach { node.removeAttr(it) }
                continue
            }
            var tag = node.normalName()
            if (tag == "img" || tag == "object") {
                img(node)
                if (node !== el && !node.isUnder(el)) continue
                tag = node.normalName()
            }
            if (tag == "a" && node.attr("href").startsWith("data:")) {
                node.remove() // LaTeXML's "download listing" links
                continue
            }
            if (tag !in HTML_TAGS) node.tagName(if (tag in BLOCK_TAGS) "div" else "span")
            node.attributes().asList().map { it.key }.filter { it !in HTML_ATTRS }.forEach { node.removeAttr(it) }
            val cls = node.classNames().filter { it.startsWith("ltx_") || it in KEEP_CLASSES }
            if (cls.isNotEmpty()) node.attr("class", cls.joinToString(" ")) else node.removeAttr("class")
            if (tag == "a") link(node)
        }
        for (t in el.select("table.ltx_tabular")) {
            val parent = t.parent()
            if (parent != null && "scroll-x" !in parent.classNames()) t.wrap("<div class=\"scroll-x\"></div>")
        }
        return Regex("<math\\b(?![^>]*xmlns)").replace(xml(el), "<math xmlns=\"$MATHML_NS\"")
    }

    private fun link(a: Element) {
        val href = a.attr("href")
        if (href.isEmpty()) return
        val url = runCatching { URL(URL(pageUrl), href).toString() }.getOrNull()
        if (url == null) {
            a.removeAttr("href")
            return
        }
        val page = url.substringBefore('#')
        val frag = url.substringAfter('#', "")
        if (frag.isNotEmpty() && page.trimEnd('/').replace(Regex("v\\d+$"), "").endsWith(base)) {
            a.attr("href", "#$frag")
            if (frag.startsWith("bib.")) {
                a.addClass("noteref")
                a.attr("role", "doc-biblioref")
            }
        } else if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
            a.attr("href", url)
        } else {
            a.removeAttr("href")
        }
    }

    private fun img(img: Element) {
        if (img.normalName() == "object") {
            if (!img.attr("type").startsWith("image/")) {
                img.remove()
                return
            }
            img.tagName("img")
            img.attr("src", img.attr("data"))
        }
        val src = img.attr("src")
        val w = img.attr("width").toFloatOrNull() ?: 0f
        val h = img.attr("height").toFloatOrNull() ?: 0f
        var aspect = if (w > 0 && h > 0) w / h else 0f
        Regex("""aspect-ratio:\s*([\d.]+)\s*/\s*([\d.]+)""").find(img.attr("style"))?.let {
            val b = it.groupValues[2].toFloat()
            if (b > 0) aspect = it.groupValues[1].toFloat() / b
        }
        val got = if (src.isNotEmpty()) images.get(src, aspect) else null
        if (got == null) {
            img.remove()
            return
        }
        val (name, pw, ph) = got
        img.attr("src", "images/$name")
        listOf("width", "height", "data", "type", "style").forEach { img.removeAttr(it) }
        if (pw > 0 && ph > 0) {
            img.attr("width", pw.toString())
            img.attr("height", ph.toString())
        }
        if (img.attr("alt").lowercase() in setOf("", "refer to caption")) {
            val cap = img.parents().firstOrNull { it.normalName() == "figure" }?.selectFirst("figcaption")
            img.attr("alt", cap?.let { visibleText(it).take(300) } ?: "")
        }
    }

    /** Replaces inline footnotes with links to footnote paragraphs. */
    private fun notes(el: Element) {
        while (true) {
            val note = el.select("span.ltx_note").firstOrNull { it !== el } ?: return
            val cls = note.classNames()
            if ("ltx_role_footnote" !in cls && "ltx_role_endnote" !in cls) {
                note.remove()
                continue
            }
            val mark = note.children().firstOrNull { it.normalName() == "sup" }
            val num = mark?.let(::visibleText) ?: "*"
            val fid = "fn-" + note.id().ifEmpty { "${notes.size + 1}" }
            note.selectFirst("span.ltx_note_content")?.let { found ->
                val content = found.clone()
                content.select("sup.ltx_note_mark, span.ltx_tag_note").remove()
                content.tagName("span")
                notes.add("<p class=\"footnote\" id=\"${xmlEscape(fid)}\"><sup>${xmlEscape(num)}</sup> ${scrub(content)}</p>")
            }
            note.before("<sup><a class=\"noteref\" href=\"#${xmlEscape(fid)}\">${xmlEscape(num)}</a></sup>")
            note.remove()
        }
    }

    /** Scrubbed contents of [el] without its own tag. */
    fun inner(el: Element): String {
        val wrapper = el.clone()
        wrapper.tagName("span")
        wrapper.attributes().asList().map { it.key }.forEach { wrapper.removeAttr(it) }
        val s = scrub(wrapper)
        if (Regex("^<(span|div)\\s*/>$").matches(s)) return ""
        return s.replace(Regex("^<(span|div)>"), "").replace(Regex("</(span|div)>$"), "")
    }

    // ---- structure ----

    private fun emit(html: String) {
        if (html.isNotBlank()) elements.add(Block(html))
    }

    private fun flushNotes() {
        notes.forEach { elements.add(Block(it)) }
        notes.clear()
    }

    fun document(article: Element) {
        article.children().forEach { node(it, 0) }
        flushNotes()
    }

    private fun front(src: Element) {
        var el = src
        val text = visibleText(el)
        if (text.isEmpty() || JUNK.containsMatchIn(text) || el.select(".ltx_ERROR").isNotEmpty()) return
        if (norm(text) == norm(title)) return // the title, set as a plain paragraph
        val cls = el.classNames()
        if ("ltx_rule" in cls) return
        if ("ltx_note" in cls) { // footnotetext with affiliations / correspondence
            val content = el.selectFirst("span.ltx_note_content")?.clone() ?: return
            content.select("sup.ltx_note_mark, span.ltx_note_type").remove()
            el = content
        }
        val paras = el.select("p").ifEmpty { listOf(el) }
        for (p in paras) {
            val body = inner(p).trim()
            if (body.isNotEmpty()) emit("<p class=\"front\">$body</p>")
        }
    }

    private fun idAttr(el: Element) = el.id().takeIf { it.isNotEmpty() }?.let { " id=\"${xmlEscape(it)}\"" } ?: ""

    private fun node(el: Element, level: Int) {
        val tag = el.normalName()
        val cls = el.classNames()
        if (cls.any { it in DROP_CLASSES } || tag in setOf("nav", "script", "style", "footer", "header")) return
        if ("ltx_title_document" in cls || "ltx_dates" in cls || "ltx_TOC" in cls) return
        if (level == 0 && !started) {
            if ("ltx_abstract" in cls || tag == "section" || cls.any { it in SECTION_LEVELS }) {
                started = true
            } else if ("ltx_authors" !in cls) {
                front(el)
                return
            }
        }
        if (level == 0 && el.select(".ltx_ERROR").isNotEmpty() && tag != "section" &&
            cls.none { it in setOf("ltx_abstract", "ltx_section", "ltx_appendix", "ltx_bibliography") }
        ) return // e.g. \metadata{...} that LaTeXML could not expand
        if ("ltx_authors" in cls) {
            val names = el.select("span.ltx_personname").map {
                it.wholeText().trim().split(Regex("\\s{2,}|\\n"))[0].trim(' ', ',', '*', '†', '‡', '∗')
            }.filter { it.isNotEmpty() }
            if (names.isNotEmpty()) emit("<p class=\"front\">${xmlEscape(names.joinToString(", "))}</p>")
            return
        }
        if ("ltx_abstract" in cls) {
            flushNotes()
            elements.add(Heading(1, "Abstract", "abstract"))
            el.children().filter { "ltx_title" !in it.classNames() }.forEach { node(it, 1) }
            return
        }
        if ("ltx_paragraph" in cls || "ltx_subparagraph" in cls) {
            val title = el.children().firstOrNull { "ltx_title" in it.classNames() }
            for (c in el.children()) {
                if (c === title) runin = "<b class=\"runin\"${idAttr(el)}>${inner(c)}</b> " else node(c, level)
            }
            runin = null
            return
        }
        val sectionLevel = SECTION_LEVELS.entries.firstOrNull { it.key in cls }?.value
        if (tag == "section" || sectionLevel != null) {
            section(el, sectionLevel ?: (level + 1))
            return
        }
        if ("ltx_para" in cls || "ltx_logical-block" in cls) {
            el.children().forEach { node(it, level) }
            return
        }
        if ("ltx_biblist" in cls) {
            el.children().filter { "ltx_bibitem" in it.classNames() }.forEach(::bibitem)
            return
        }
        when {
            tag == "p" -> {
                var body = inner(el)
                runin?.let {
                    body = it + body
                    runin = null
                }
                val ptag = if ("<table" in body) "div" else "p"
                emit("<$ptag${idAttr(el)}>$body</$ptag>")
            }
            tag == "table" && cls.any { it in setOf("ltx_equation", "ltx_equationgroup", "ltx_eqn_table") } ->
                emit("<div class=\"scroll-x\">${scrub(el)}</div>")
            tag == "figure" -> figure(el)
            tag in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> emit("<p><b>${inner(el)}</b></p>")
            else -> emit(scrub(el))
        }
    }

    private fun section(el: Element, level: Int) {
        flushNotes()
        val title = el.children().firstOrNull { "ltx_title" in it.classNames() }
        val id = el.id().ifEmpty { "sec-${elements.size}" }
        val label = title?.let(::inner) ?: if ("ltx_bibliography" in el.classNames()) "References" else ""
        elements.add(Heading(min(level, 3), label, id))
        el.children().filter { it !== title }.forEach { node(it, level) }
        flushNotes()
    }

    private fun figure(src: Element) {
        val kind = if ("ltx_table" in src.classNames()) "table" else "figure"
        val el = src.clone()
        el.attr("class", "$kind ${el.className()}".trim())
        emit(scrub(el))
    }

    private fun bibitem(item: Element) {
        val tag = item.children().firstOrNull { it.normalName() == "span" }
        var label = ""
        if (tag != null && "ltx_tag_bibitem" in tag.classNames()) {
            val t = visibleText(tag)
            if (Regex("""\[?\d+]?""").matches(t)) label = "[${t.trim('[', ']')}] "
        }
        val blocks = item.children()
            .filter { "ltx_bibblock" in it.classNames() && "ltx_bib_cited" !in it.classNames() }
            .map(::inner).filter { it.isNotEmpty() }
        val id = xmlEscape(item.id().ifEmpty { "bib-${elements.size}" })
        emit("<p class=\"reference\" id=\"$id\">${xmlEscape(label)}${blocks.joinToString(" ")}</p>")
    }
}

/**
 * Converts an arXiv paper into an EPUB file. Blocking (network and rendering); call off the main thread.
 * [progress] receives short status lines for the UI.
 */
fun convertArxiv(input: String, cacheDir: File, out: File, progress: (String) -> Unit): Document {
    val id = Arxiv.parseId(input) ?: Arxiv.findId(input) ?: throw IllegalArgumentException("Not an arXiv link or ID: $input")
    val client = ArxivClient(File(cacheDir, "arxiv"))
    progress("Fetching ${id.base} from arXiv")
    val (v, text) = client.fetchHtml(id.base, id.version) { Log.w(TAG, it); progress(it) }
    val pageUrl = "https://arxiv.org/html/${id.base}v$v"
    progress("Downloading LaTeX source")
    val source = client.fetchSource(id.base, v)
    val root = Jsoup.parse(text, pageUrl)
    val article = root.selectFirst("article") ?: throw IOException("Unexpected arXiv HTML layout (no <article>)")
    val images = ArxivImages(pageUrl, "${id.base}v$v/", source, client, progress)
    val conv = Converter(pageUrl, id.base, images)

    val titleEl = article.selectFirst(".ltx_title_document")
    var title = titleEl?.clone()?.apply { select(".ltx_note, .ltx_pubnotes").remove() }?.let(::visibleText) ?: id.base
    val (metaTitle, metaAuthors) = client.metadata(id.base)
    if (titleEl == null && metaTitle.isNotEmpty()) title = metaTitle
    val authors = metaAuthors.ifEmpty {
        article.select(".ltx_authors .ltx_personname").map {
            it.wholeText().trim().split(Regex("\\s{2,}|\\n"))[0].trim(' ', ',', '*', '†', '‡', '∗')
        }
    }.filter { it.isNotEmpty() }
    conv.title = title
    progress("Converting text")
    conv.document(article)
    Log.i(TAG, "arXiv ${id.base}v$v: figures from source ${images.fromSource}, from arXiv HTML ${images.fromHtml}")
    val doc = Document(title, authors, conv.elements, images.images)
    progress("Writing EPUB")
    EpubWriter.write(doc, out)
    return doc
}
