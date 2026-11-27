package com.paperreader.convert

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Document model and EPUB 3 writer, ported from converter/src/paper2epub/{model,epub}.py.
 * Converters produce ready-made XHTML (headings and blocks); internal links inside it
 * are written as href="#id" and pointed at the right chapter file here.
 */

sealed interface DocElement

/** A heading; [html] is inline XHTML. Level-1 headings start a new chapter. */
data class Heading(val level: Int, val html: String, val id: String) : DocElement

/** A ready-made XHTML block (paragraph, table, equation, figure, list). */
data class Block(val html: String) : DocElement

class Document(
    val title: String,
    val authors: List<String>,
    val elements: List<DocElement>,
    /** File name inside OEBPS/images -> bytes. */
    val images: Map<String, ByteArray>,
    val language: String = "en",
)

private val ID_ATTR = Regex("""\sid="([^"]+)"""")
private val HREF_LOCAL = Regex("""href="#([^"]+)"""")
private val XML_BAD = Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\ufffe\\uffff]")

fun xmlEscape(s: String): String = XML_BAD.replace(s, "")
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#x27;")

fun xmlUnescape(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#x27;", "'").replace("&#39;", "'").replace("&amp;", "&")

/** Visible text of an XHTML fragment (TeX annotations dropped). */
fun markupText(html: String): String {
    var s = Regex("<annotation\\b.*?</annotation>", RegexOption.DOT_MATCHES_ALL).replace(html, "")
    s = Regex("<[^>]+>").replace(s, "")
    return xmlUnescape(s).replace(Regex("\\s+"), " ")
}

const val EPUB_CSS = """body { font-family: serif; line-height: 1.5; margin: 0 0.6em; }
h1, h2, h3, h4, h5, h6 { font-family: sans-serif; line-height: 1.25; margin: 1.2em 0 0.5em; }
h1.title { font-size: 1.5em; text-align: center; margin-top: 0.5em; }
p { margin: 0 0 0.7em; text-align: justify; hyphens: auto; -webkit-hyphens: auto; }
p.front { text-align: center; font-size: 0.85em; margin-bottom: 0.3em; }
p.footnote { font-size: 0.8em; border-left: 2px solid #999; padding-left: 0.5em; }
p.reference { font-size: 0.9em; text-align: left; padding-left: 1.5em; text-indent: -1.5em; }
figure { margin: 1em 0; text-align: center; page-break-inside: avoid; break-inside: avoid; }
figure img { max-width: 100%; height: auto; }
figure.equation { margin: 0.6em 0; }
figure.equation img { max-width: 100%; }
figcaption { font-size: 0.85em; text-align: left; margin-top: 0.4em; }
a { color: inherit; text-decoration-color: #888; }
a.noteref { text-decoration: none; color: #2a6ad3; }
code { font-family: monospace; }
/* Markup from LaTeXML (arXiv HTML). */
.ltx_font_bold { font-weight: bold; }
.ltx_font_italic, .ltx_emph { font-style: italic; }
.ltx_font_typewriter { font-family: monospace; }
.ltx_font_smallcaps { font-variant: small-caps; }
.ltx_font_sansserif { font-family: sans-serif; }
.ltx_align_left { text-align: left; }
.ltx_align_center { text-align: center; }
.ltx_align_right { text-align: right; }
.scroll-x { overflow-x: auto; max-width: 100%; }
cite { font-style: normal; }
figure.table .scroll-x { margin-top: 0.4em; }
table.ltx_tabular { border-collapse: collapse; margin: 0 auto; font-size: 0.8em; line-height: 1.3; }
.ltx_tabular td, .ltx_tabular th { padding: 0.15em 0.45em; white-space: nowrap; }
.ltx_border_t { border-top: 1px solid; }
.ltx_border_b { border-bottom: 1px solid; }
.ltx_border_tt { border-top: 2px solid; }
.ltx_border_bb { border-bottom: 2px solid; }
.ltx_border_l { border-left: 1px solid; }
.ltx_border_r { border-right: 1px solid; }
table.ltx_equation, table.ltx_equationgroup { width: 100%; border-collapse: collapse; margin: 0.5em 0; }
td.ltx_eqn_cell { padding: 0; }
td.ltx_eqn_eqno { text-align: right; white-space: nowrap; font-size: 0.9em; padding-left: 0.6em; }
td.ltx_eqn_center_padleft, td.ltx_eqn_center_padright { width: 50%; }
.ltx_flex_figure { display: flex; flex-wrap: wrap; justify-content: center; align-items: flex-end; gap: 0.6em; }
.ltx_flex_cell { flex: 1 1 40%; }
.ltx_flex_break { flex-basis: 100%; height: 0; }
.ltx_listing { font-family: monospace; font-size: 0.75em; overflow-x: auto; text-align: left; }
.ltx_listingline { white-space: pre; }
ul.ltx_itemize, ol.ltx_enumerate { padding-left: 1.4em; }
li.ltx_item { list-style: none; }
li.ltx_item > .ltx_tag { float: left; margin-left: -1.3em; }
b.runin { margin-right: 0.3em; }
div.ltx_theorem, div.ltx_proof { margin: 0.6em 0; }
"""

object EpubWriter {
    private fun chapterFile(i: Int) = "ch%03d.xhtml".format(i)

    fun write(doc: Document, out: File) {
        // Chapters split at level-1 headings.
        val chapters = mutableListOf<MutableList<DocElement>>(mutableListOf())
        for (e in doc.elements) {
            if (e is Heading && e.level == 1 && chapters.last().isNotEmpty()) chapters.add(mutableListOf())
            chapters.last().add(e)
        }
        val idFile = HashMap<String, String>()
        chapters.forEachIndexed { i, ch ->
            for (e in ch) {
                val html = when (e) {
                    is Heading -> {
                        idFile[e.id] = chapterFile(i)
                        e.html
                    }
                    is Block -> e.html
                }
                ID_ATTR.findAll(html).forEach { idFile[xmlUnescape(it.groupValues[1])] = chapterFile(i) }
            }
        }

        fun fixLinks(html: String) = HREF_LOCAL.replace(html) { m ->
            val target = xmlUnescape(m.groupValues[1])
            val file = idFile[target]
            if (file != null) "href=\"${xmlEscape(file)}#${xmlEscape(target)}\"" else "data-missing-target=\"\""
        }

        val chapterTexts = chapters.mapIndexed { i, ch ->
            val body = StringBuilder()
            if (i == 0) body.append("<h1 class=\"title\">${xmlEscape(doc.title)}</h1>\n")
            for (e in ch) {
                when (e) {
                    is Heading -> {
                        val lvl = (e.level.coerceAtLeast(1) + 1).coerceAtMost(6) // h1 is the paper title
                        body.append("<h$lvl id=\"${xmlEscape(e.id)}\">${fixLinks(e.html)}</h$lvl>\n")
                    }
                    is Block -> body.append(fixLinks(e.html)).append('\n')
                }
            }
            val first = ch.firstOrNull { it is Heading } as Heading?
            val title = if (i > 0 && first != null) markupText(first.html).trim() else doc.title
            """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${doc.language}" lang="${doc.language}">
<head><meta charset="utf-8"/><title>${xmlEscape(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
$body</body>
</html>
"""
        }

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            fun text(name: String, s: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(s.toByteArray())
                zip.closeEntry()
            }
            text(
                "META-INF/container.xml",
                "<?xml version=\"1.0\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
                    "</rootfiles></container>\n",
            )
            text("OEBPS/content.opf", opf(doc, chapterTexts))
            text("OEBPS/nav.xhtml", nav(doc, chapters))
            text("OEBPS/style.css", EPUB_CSS)
            chapterTexts.forEachIndexed { i, s -> text("OEBPS/${chapterFile(i)}", s) }
            for ((name, data) in doc.images) {
                zip.putNextEntry(ZipEntry("OEBPS/images/$name"))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    private fun nav(doc: Document, chapters: List<List<DocElement>>): String {
        val entries = mutableListOf(Triple(1, doc.title, chapterFile(0)))
        chapters.forEachIndexed { i, ch ->
            for (e in ch) if (e is Heading) entries.add(Triple(e.level, markupText(e.html).trim(), "${chapterFile(i)}#${e.id}"))
        }
        // Nested <ol> lists; a level may only go one deeper than its parent.
        val out = StringBuilder("<ol>")
        var depth = 1
        entries.forEachIndexed { n, (lvl, label, href) ->
            val level = if (n == 0) 1 else lvl.coerceIn(1, depth + 1)
            if (n > 0 && level > depth) {
                out.append("<ol>")
                depth++
            } else if (n > 0) {
                out.append("</li>")
                while (depth > level) {
                    out.append("</ol></li>")
                    depth--
                }
            }
            out.append("<li><a href=\"${xmlEscape(href)}\">${xmlEscape(label).ifEmpty { "-" }}</a>")
        }
        out.append("</li>")
        while (depth > 1) {
            out.append("</ol></li>")
            depth--
        }
        out.append("</ol>")
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head>
<body>
<nav epub:type="toc" id="toc"><h1>Contents</h1>
$out
</nav>
</body>
</html>
"""
    }

    private fun opf(doc: Document, chapters: List<String>): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val items = mutableListOf(
            """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""",
            """<item id="css" href="style.css" media-type="text/css"/>""",
        )
        val spine = mutableListOf<String>()
        chapters.forEachIndexed { i, text ->
            val props = if ("<math" in text) " properties=\"mathml\"" else ""
            items.add("""<item id="ch$i" href="${chapterFile(i)}" media-type="application/xhtml+xml"$props/>""")
            spine.add("""<itemref idref="ch$i"/>""")
        }
        doc.images.keys.sorted().forEachIndexed { n, name ->
            val type = when (name.substringAfterLast('.')) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                else -> "image/jpeg"
            }
            items.add("""<item id="img$n" href="images/${xmlEscape(name)}" media-type="$type"/>""")
        }
        val creators = doc.authors.joinToString("") { "<dc:creator>${xmlEscape(it)}</dc:creator>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier>
<dc:title>${xmlEscape(doc.title)}</dc:title>
$creators
<dc:language>${doc.language}</dc:language>
<meta property="dcterms:modified">$now</meta>
</metadata>
<manifest>
${items.joinToString("\n")}
</manifest>
<spine>
${spine.joinToString("\n")}
</spine>
</package>
"""
    }
}
