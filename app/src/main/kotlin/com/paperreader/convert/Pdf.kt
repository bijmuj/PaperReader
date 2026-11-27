package com.paperreader.convert

import android.util.Log
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DefaultColorSpaces
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.PathWalker
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Shade
import com.artifex.mupdf.fitz.StrokeState
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.Text
import java.io.File
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import com.artifex.mupdf.fitz.Document as FzDocument
import com.artifex.mupdf.fitz.Page as FzPage

/*
 * PDF -> Document, ported from converter/src/paper2epub/extract.py (PyMuPDF) to MuPDF's
 * Java bindings. The pipeline and heuristics are the same; see extract.py for the reasoning
 * behind each step. Differences:
 *  - Ruled tables are found from the rules themselves (PyMuPDF's table finder isn't in MuPDF).
 *  - Visuals are always rendered from the page, never copied out as the embedded image file.
 */

private const val TAG = "PdfConvert"

private val MATH_FONT_RE = Regex(
    "^(CMMI|CMSY|CMEX|CMBSY|CMMIB|MSAM|MSBM|EUFM|EUSM|EUEX|RSFS|STIX.*Math|" +
        "LMMath|.*-?Math|Symbol|esint|wasy|txsy|txex|pxsy|pxex)",
    RegexOption.IGNORE_CASE,
)
private val BOLD_RE = Regex("bold|medi|semibold|demi|black|heavy|CMBX|-B$|\\.B$", RegexOption.IGNORE_CASE)
private val ITALIC_RE = Regex("ital|oblique|CMTI|-I$|\\.I$", RegexOption.IGNORE_CASE)
private val MONO_RE = Regex("mono|courier|CMTT|SFTT|consola|menlo|inconsolata", RegexOption.IGNORE_CASE)
private val CAPTION_RE = Regex("^\\s*(Figure|Fig\\.|FIGURE|FIG\\.|Table|TABLE)\\s*([A-Z]?\\d+|[IVXL]+)\\s*[:.|]")
private val NUMBER_PREFIX_RE = Regex("^(\\d+(\\.\\d+)*\\.?|[A-Z](\\.\\d+)+\\.?|[A-Z]\\.|[IVX]+\\.)\\s+(?=\\S)")
// IEEE style: "II. RELATED WORK" (small caps, body size, not bold) and "A. Overview" (italic).
private val IEEE_SECTION_RE = Regex("^[IVX]+\\.\\s+[A-Z][A-Z0-9 ,:;&()/'-]*[A-Z)]$")
private val IEEE_SUBSECTION_RE = Regex("^[A-Z]\\.\\s+[A-Z0-9]\\S*(\\s+\\S+){0,9}$")
private val ALL_CAPS_RE = Regex("^[A-Z0-9][A-Z0-9 ,:;&()/'-]*$")
private val KNOWN_HEADINGS = setOf(
    "abstract", "introduction", "relatedwork", "background", "method", "methods",
    "methodology", "experiments", "results", "discussion", "conclusion",
    "conclusions", "references", "bibliography", "acknowledgments",
    "acknowledgements", "acknowledgment", "acknowledgement", "appendix",
    "limitations", "broaderimpact", "supplementarymaterial",
)
private const val COL_GAP = 6f
private const val FIG_GAP = 30f
private const val MAX_IMAGE_PX = 4000f

private fun stripSubset(font: String) = font.substringAfter('+')
private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

/** Python's round(x * 2) / 2 (half-to-even). */
private fun half(x: Float) = (Math.rint(x * 2.0) / 2.0).toFloat()

private fun <K> MutableMap<K, Int>.inc(k: K, n: Int = 1) { this[k] = (this[k] ?: 0) + n }

/** First key with the highest count, like Counter.most_common(1). */
private fun <K> Map<K, Int>.mostCommon(): K? = entries.maxByOrNull { it.value }?.key

// ---------------------------------------------------------------------------
// Geometry

private class Box(var x0: Float, var y0: Float, var x1: Float, var y1: Float) {
    constructor(b: Box) : this(b.x0, b.y0, b.x1, b.y1)
    val width get() = x1 - x0
    val height get() = y1 - y0
    val isEmpty get() = x0 >= x1 || y0 >= y1
    val area get() = if (isEmpty) 0f else width * height
    fun union(o: Box): Box { x0 = min(x0, o.x0); y0 = min(y0, o.y0); x1 = max(x1, o.x1); y1 = max(y1, o.y1); return this }
    fun or(o: Box) = Box(this).union(o)
    fun and(o: Box) = Box(max(x0, o.x0), max(y0, o.y0), min(x1, o.x1), min(y1, o.y1))
    fun contains(o: Box) = o.x0 >= x0 && o.y0 >= y0 && o.x1 <= x1 && o.y1 <= y1
    fun contains(x: Float, y: Float) = x >= x0 && x < x1 && y >= y0 && y < y1
    override fun toString() = "(%.1f, %.1f, %.1f, %.1f)".format(x0, y0, x1, y1)

    companion object {
        fun of(r: Rect) = Box(r.x0, r.y0, r.x1, r.y1)
        fun of(q: Quad) = Box(
            minOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), minOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y),
            maxOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x), maxOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y),
        )
    }
}

private fun xoverlap(a: Box, b: Box) = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0))
private fun hoverlap(a: Box, b: Box, slack: Float = 0f) = a.x0 - slack < b.x1 && b.x0 - slack < a.x1

// ---------------------------------------------------------------------------
// Inline content

private sealed interface Inline

private class Run(
    var text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val mono: Boolean = false,
    val vpos: String = "",
    val link: String? = null,
    val noteref: Boolean = false,
) : Inline {
    fun sameStyle(o: Run) = bold == o.bold && italic == o.italic && mono == o.mono && vpos == o.vpos &&
        link == o.link && noteref == o.noteref
    fun withLink(link: String?, noteref: Boolean = false) = Run(text, bold, italic, mono, vpos, link, noteref)
}

private class Anchor(val id: String) : Inline

private fun plainText(content: List<Inline>) = content.joinToString("") { (it as? Run)?.text ?: "" }
private fun contentText(content: List<Inline>) = plainText(content).trim()

// ---------------------------------------------------------------------------
// Page-level primitives

private class Line(
    val page: Int,
    val bbox: Box,
    val runs: List<Run>,
    val size: Float,
    val bold: Boolean,
    val fonts: Map<String, Int>,
    val maxSize: Float,
) {
    val anchors = mutableListOf<String>()
    var heading = 0
    val text: String by lazy { runs.joinToString("") { it.text } }
    val nchars get() = fonts.values.sum()
}

private class TBlock(val page: Int, var bbox: Box, var lines: MutableList<Line>, var role: String = "body") {
    val text get() = lines.joinToString(" ") { it.text.trim() }
    val size: Float
        get() {
            val sizes = LinkedHashMap<Float, Int>()
            for (ln in lines) sizes.inc(ln.size, ln.nchars)
            return sizes.mostCommon() ?: 0f
        }
    val nchars get() = lines.sumOf { it.nchars }
    val maxSize get() = lines.maxOf { it.maxSize }
}

private class Graphic(
    val bbox: Box,
    val kind: String, // image | drawing | table
    val nativeWidth: Int = 0,
    val upright: Boolean = true,
    var used: Boolean = false,
    /** For ruled tables: the rules' extent, which is rendered along with the text inside. */
    val extent: Box? = null,
)

private class VisualRegion(
    val kind: String,
    val id: String,
    val clip: Box,
    val bbox: Box,
    val caption: TBlock?,
    val graphics: List<Graphic>,
    val textBlocks: List<TBlock>,
    val alt: String = "",
)

private class LinkInfo(val page: Int, val x: Float, val y: Float, val name: String)

private class PPage(
    val number: Int,
    val rect: Box,
    var blocks: MutableList<TBlock>,
    val graphics: MutableList<Graphic>,
    /** Horizontal rules (for ruled tables), each a (x0, y, x1) box of zero height. */
    val rules: List<Box>,
    /** Curves and slanted lines (see GraphicsCollector.marks). */
    val marks: List<Box>,
) {
    val visuals = mutableListOf<VisualRegion>()
}

// ---------------------------------------------------------------------------
// Reading pages

private class HitLink(val box: Box, val ref: String)

private fun readLinks(doc: FzDocument, page: FzPage, registry: MutableList<LinkInfo>): List<HitLink> {
    val out = mutableListOf<HitLink>()
    for (ln in page.links ?: emptyArray()) {
        val uri = ln.uri ?: continue
        val box = Box.of(ln.bounds)
        if (ln.isExternal) {
            out.add(HitLink(box, uri))
            continue
        }
        val dest = try { doc.resolveLinkDestination(uri) } catch (e: Exception) { null } ?: continue
        val pno = try { doc.pageNumberFromLocation(dest) } catch (e: Exception) { -1 }
        if (pno < 0) continue
        val name = when {
            uri.startsWith("#nameddest=") -> URLDecoder.decode(uri.removePrefix("#nameddest="), "UTF-8")
            uri.startsWith("#page=") -> ""
            uri.startsWith("#") -> URLDecoder.decode(uri.substring(1), "UTF-8")
            else -> ""
        }
        val x = if (dest.x.isNaN()) 0f else dest.x
        val y = if (dest.y.isNaN()) 0f else dest.y
        out.add(HitLink(box, "@${registry.size}"))
        registry.add(LinkInfo(pno, x, y, name))
    }
    return out
}

/** One character as PyMuPDF's rawdict reports it, grouped into spans by style. */
private class RawChar(val c: Int, val box: Box, val originY: Float)

private class RawSpan(val font: String, val size: Float, val flags: Int, val argb: Int, val originY: Float) {
    val chars = mutableListOf<RawChar>()
}

private class RawLine(val dirX: Float, val dirY: Float) {
    val spans = mutableListOf<RawSpan>()
    var firstOriginY = Float.NaN
}

private const val FLAG_SUPER = 1
private const val FLAG_ITALIC = 2
private const val FLAG_MONO = 8
private const val FLAG_BOLD = 16

/**
 * [metrics]: ascender and descender per font name, read from the font's characters. Spaces that
 * MuPDF inserts have boxes of other heights, so they use their font's figures from here.
 */
private class TextCollector(private val metrics: MutableMap<String, FloatArray>) : StructuredTextWalker {
    val blocks = mutableListOf<MutableList<RawLine>>()
    private var line: RawLine? = null
    private val fontInfo = HashMap<Font, Pair<String, Int>>()

    override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {}
    override fun beginTextBlock(bbox: Rect?, flags: Int) { blocks.add(mutableListOf()) }
    override fun endTextBlock() {}
    override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) {
        line = RawLine(dir?.x ?: 1f, dir?.y ?: 0f).also { blocks.lastOrNull()?.add(it) }
    }
    override fun endLine() { line = null }
    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
    override fun endStruct() {}
    override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}

    override fun onChar(c: Int, origin: Point, font: Font, size: Float, quad: Quad, argb: Int, flags: Int, bidi: Int) {
        val ln = line ?: return
        if (ln.firstOriginY.isNaN()) ln.firstOriginY = origin.y
        val (name, fontFlags) = fontInfo.getOrPut(font) {
            val f = (if (font.isItalic) FLAG_ITALIC else 0) or (if (font.isMono) FLAG_MONO else 0) or
                (if (font.isBold) FLAG_BOLD else 0)
            Pair(font.name ?: "", f)
        }
        // PyMuPDF's superscript test: the char sits above the line's first baseline.
        val sup = abs(ln.dirX - 1) < 1e-3 && abs(ln.dirY) < 1e-3 && origin.y < ln.firstOriginY - size * 0.1f
        val spanFlags = fontFlags or (if (sup) FLAG_SUPER else 0)
        var span = ln.spans.lastOrNull()
        if (span == null || span.font != name || span.size != size || span.flags != spanFlags || span.argb != argb) {
            span = RawSpan(name, size, spanFlags, argb, origin.y)
            ln.spans.add(span)
        }
        val horizontal = abs(ln.dirX - 1) < 1e-3 && abs(ln.dirY) < 1e-3
        val raw = Box.of(quad)
        var box = raw
        if (horizontal && size > 0) {
            val m = if (Character.isWhitespace(c)) metrics[name]
            else floatArrayOf((origin.y - raw.y0) / size, (origin.y - raw.y1) / size).also { metrics[name] = it }
            if (m != null) box = charBox(raw, origin, size, m[0], m[1])
        }
        span.chars.add(RawChar(c, box, origin.y))
    }
}

/**
 * PyMuPDF's JM_char_quad: when a font's ascender minus descender is under 1 (common with TeX
 * fonts), the box is stretched to the full font size. MuPDF's raw box spans the ascender to the
 * descender, so both are read back from it.
 */
private fun charBox(raw: Box, origin: Point, size: Float, ascender: Float, descender: Float): Box {
    var asc = ascender
    var dsc = descender
    var ascDsc = asc - dsc + 1.1920929e-7f
    if (ascDsc >= 1) return Box(raw.x0, origin.y - asc * size, raw.x1, origin.y - dsc * size)
    if (asc < 1e-3f) {
        dsc = -0.1f
        asc = 0.9f
        ascDsc = 1f
    }
    if (ascDsc < 1) {
        dsc /= ascDsc
        asc /= ascDsc
    }
    ascDsc = asc - dsc
    return Box(max(raw.x0, origin.x), origin.y - asc * size / ascDsc, raw.x1, origin.y - dsc * size / ascDsc)
}

private fun makeLine(pno: Int, raw: RawLine, hitLinks: List<HitLink>): Line? {
    val spans = raw.spans.filter { it.chars.isNotEmpty() }
    if (spans.isEmpty()) return null
    val maxsize = spans.maxOf { it.size }
    val base = spans.first { it.size == maxsize }.originY
    val runs = mutableListOf<Run>()
    val fonts = LinkedHashMap<String, Int>()
    val sizes = LinkedHashMap<Float, Int>()
    var nbold = 0
    var bbox: Box? = null
    for (s in spans) {
        val font = stripSubset(s.font)
        val bold = s.flags and FLAG_BOLD != 0 || BOLD_RE.containsMatchIn(font)
        val italic = s.flags and FLAG_ITALIC != 0 || ITALIC_RE.containsMatchIn(font)
        val mono = s.flags and FLAG_MONO != 0 || MONO_RE.containsMatchIn(font)
        var vpos = ""
        if (s.size < maxsize * 0.85f) {
            val dy = s.originY - base
            if (dy < -0.12f * maxsize) vpos = "sup" else if (dy > 0.08f * maxsize) vpos = "sub"
        }
        if (vpos.isEmpty() && s.flags and FLAG_SUPER != 0 && s.size < maxsize * 0.95f) vpos = "sup"
        for (ch in s.chars) {
            bbox = bbox?.union(ch.box) ?: Box(ch.box)
            val c = ch.c
            if (c == 0xFEFF || c == 0x200B || (c < 32 && c != '\t'.code)) continue
            val cs = String(Character.toChars(c))
            val cx = (ch.box.x0 + ch.box.x1) / 2
            val cy = (ch.box.y0 + ch.box.y1) / 2
            val link = hitLinks.firstOrNull { r ->
                r.box.x0 - 0.5f <= cx && cx <= r.box.x1 + 0.5f && r.box.y0 - 0.5f <= cy && cy <= r.box.y1 + 0.5f
            }?.ref
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                fonts.inc(font)
                sizes.inc(half(s.size))
                if (bold) nbold++
            }
            val run = Run(cs, bold, italic, mono, vpos, link)
            val last = runs.lastOrNull()
            if (last != null && last.sameStyle(run)) last.text += cs else runs.add(run)
        }
    }
    if (fonts.isEmpty()) return null
    return Line(pno, bbox!!, runs, sizes.mostCommon()!!, nbold == fonts.values.sum(), fonts, half(maxsize))
}

/** Records what get_image_info / get_drawings would report, plus horizontal rules. */
private class GraphicsCollector : Device() {
    class Img(val box: Box, val upright: Boolean, val width: Int)
    val images = mutableListOf<Img>()
    val paths = mutableListOf<Box>()
    val rules = mutableListOf<Box>()

    /** Centres of curves and slanted lines: plots and diagrams have them, tables don't. */
    val marks = mutableListOf<Box>()

    private fun addPath(path: Path, ctm: Matrix, stroked: Boolean) {
        val subpaths = mutableListOf<MutableList<FloatArray>>()
        var curved = false
        fun pt(x: Float, y: Float) = Point(x, y).transform(ctm).let { floatArrayOf(it.x, it.y) }
        fun mark(vararg ps: FloatArray) {
            marks.add(Box(ps.minOf { it[0] }, ps.minOf { it[1] }, ps.maxOf { it[0] }, ps.maxOf { it[1] }))
        }
        path.walk(object : PathWalker {
            override fun moveTo(x: Float, y: Float) { subpaths.add(mutableListOf(pt(x, y))) }
            override fun lineTo(x: Float, y: Float) {
                if (subpaths.isEmpty()) subpaths.add(mutableListOf())
                val p = pt(x, y)
                subpaths.last().lastOrNull()?.let { a ->
                    if (abs(a[0] - p[0]) > 0.5f && abs(a[1] - p[1]) > 0.5f) mark(a, p)
                }
                subpaths.last().add(p)
            }
            override fun curveTo(cx1: Float, cy1: Float, cx2: Float, cy2: Float, ex: Float, ey: Float) {
                if (subpaths.isEmpty()) subpaths.add(mutableListOf())
                val ps = listOf(pt(cx1, cy1), pt(cx2, cy2), pt(ex, ey))
                curved = true
                mark(*(listOfNotNull(subpaths.last().lastOrNull()) + ps).toTypedArray())
                subpaths.last().addAll(ps)
            }
            override fun closePath() {}
        })
        var all: Box? = null
        for (sp in subpaths) for (p in sp) {
            all = all?.apply { x0 = min(x0, p[0]); y0 = min(y0, p[1]); x1 = max(x1, p[0]); y1 = max(y1, p[1]) }
                ?: Box(p[0], p[1], p[0], p[1])
        }
        all?.let { paths.add(it) }
        if (curved) return // rounded boxes and other shapes, not rules
        for (sp in subpaths) {
            if (sp.isEmpty()) continue
            if (stroked) {
                // Each segment of a stroked path that runs horizontally.
                for (i in 1 until sp.size) {
                    val a = sp[i - 1]
                    val b = sp[i]
                    if (abs(a[1] - b[1]) < 0.5f && abs(a[0] - b[0]) >= 20f) {
                        rules.add(Box(min(a[0], b[0]), (a[1] + b[1]) / 2, max(a[0], b[0]), (a[1] + b[1]) / 2))
                    }
                }
            } else {
                val x0 = sp.minOf { it[0] }
                val x1 = sp.maxOf { it[0] }
                val y0 = sp.minOf { it[1] }
                val y1 = sp.maxOf { it[1] }
                if (y1 - y0 <= 1.5f && x1 - x0 >= 20f) rules.add(Box(x0, (y0 + y1) / 2, x1, (y0 + y1) / 2)) // a thin filled box
            }
        }
    }

    private fun addImage(ctm: Matrix, width: Int) {
        val r = Box.of(Rect(0f, 0f, 1f, 1f).transform(ctm))
        val upright = abs(ctm.b) < 1e-3 && abs(ctm.c) < 1e-3 && ctm.a > 0 && ctm.d > 0
        images.add(Img(r, upright, width))
    }

    override fun fillPath(path: Path, evenOdd: Boolean, ctm: Matrix, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) =
        addPath(path, ctm, false)
    override fun strokePath(path: Path, stroke: StrokeState?, ctm: Matrix, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) =
        addPath(path, ctm, true)
    override fun fillImage(image: Image, ctm: Matrix, alpha: Float, cp: Int) = addImage(ctm, image.width)
    override fun fillImageMask(image: Image, ctm: Matrix, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) =
        addImage(ctm, image.width)

    override fun close() {}
    override fun clipPath(path: Path?, evenOdd: Boolean, ctm: Matrix?) {}
    override fun clipStrokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?) {}
    override fun fillText(text: Text?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun strokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun clipText(text: Text?, ctm: Matrix?) {}
    override fun clipStrokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?) {}
    override fun ignoreText(text: Text?, ctm: Matrix?) {}
    override fun fillShade(shd: Shade?, ctm: Matrix?, alpha: Float, cp: Int) {}
    override fun clipImageMask(img: Image?, ctm: Matrix?) {}
    override fun popClip() {}
    override fun beginMask(area: Rect?, luminosity: Boolean, cs: ColorSpace?, bc: FloatArray?, cp: Int) {}
    override fun endMask() {}
    override fun beginGroup(area: Rect?, cs: ColorSpace?, isolated: Boolean, knockout: Boolean, blendmode: Int, alpha: Float) {}
    override fun endGroup() {}
    override fun beginTile(area: Rect?, view: Rect?, xstep: Float, ystep: Float, ctm: Matrix?, id: Int, doc_id: Int): Int = 0
    override fun endTile() {}
    override fun renderFlags(set: Int, clear: Int) {}
    override fun setDefaultColorSpaces(dcs: DefaultColorSpaces?) {}
    override fun beginLayer(name: String?) {}
    override fun endLayer() {}
    override fun beginStructure(standard: Int, raw: String?, uid: Int) {}
    override fun endStructure() {}
    override fun beginMetatext(meta: Int, text: String?) {}
    override fun endMetatext() {}
}

/** PyMuPDF's Page.cluster_drawings with its default tolerances. */
private fun clusterDrawings(paths: List<Box>, page: Box, dx: Float = 3f, dy: Float = 3f): List<Box> {
    fun neighbours(a: Box, b: Box) = !(a.x1 < b.x0 - dx || a.x0 > b.x1 + dx || a.y1 < b.y0 - dy || a.y0 > b.y1 + dy)
    var prects = paths.filter { it.x0 >= page.x0 && it.x1 <= page.x1 && it.y0 >= page.y0 && it.y1 <= page.y1 }
        .sortedWith(compareBy({ it.y1 }, { it.x0 })).toMutableList()
    val out = mutableListOf<Box>()
    while (prects.isNotEmpty()) {
        val r = Box(prects[0])
        var repeat = true
        while (repeat) {
            repeat = false
            for (i in prects.size - 1 downTo 1) {
                if (neighbours(prects[i], r)) {
                    r.union(prects[i])
                    prects.removeAt(i)
                    repeat = true
                }
            }
        }
        out.add(r)
        prects.removeAt(0)
        prects = prects.distinctBy { listOf(it.x0, it.y0, it.x1, it.y1) }.sortedWith(compareBy({ it.y1 }, { it.x0 })).toMutableList()
    }
    return out.distinctBy { listOf(it.x0, it.y0, it.x1, it.y1) }.sortedWith(compareBy({ it.y1 }, { it.x0 }))
        .filter { it.width > dx && it.height > dy }
}

private const val TEXT_OPTIONS = "preserve-whitespace,mediabox-clip,use-cid-for-unknown-unicode"

private fun readPage(
    doc: FzDocument, page: FzPage, pno: Int, registry: MutableList<LinkInfo>, metrics: MutableMap<String, FloatArray>,
): PPage {
    val rect = Box.of(page.bounds)
    val hitLinks = readLinks(doc, page, registry)
    val stext = page.toStructuredText(TEXT_OPTIONS)
    val collector = TextCollector(metrics)
    try { stext.walk(collector) } finally { stext.destroy() }
    val blocks = mutableListOf<TBlock>()
    for (rawLines in collector.blocks) {
        val lines = mutableListOf<Line>()
        for (raw in rawLines) {
            if (abs(raw.dirX - 1) > 1e-3 || abs(raw.dirY) > 1e-3) continue // rotated text (arXiv stamp)
            makeLine(pno, raw, hitLinks)?.let(lines::add)
        }
        if (lines.isNotEmpty()) blocks.add(TBlock(pno, lineUnion(lines), lines))
    }

    val dev = GraphicsCollector()
    try {
        page.run(dev, Matrix.Identity(), null)
    } finally {
        dev.close()
        dev.destroy()
    }
    val graphics = mutableListOf<Graphic>()
    for (img in dev.images) {
        val r = img.box.and(rect)
        if (r.isEmpty || r.width < 4 || r.height < 4) continue
        graphics.add(Graphic(r, "image", img.width, img.upright))
    }
    val pageArea = rect.width * rect.height
    for (c in clusterDrawings(dev.paths, rect)) {
        val r = c.and(rect)
        if (r.isEmpty || r.width * r.height > 0.9f * pageArea) continue
        if (r.width < 2 && r.height < 2) continue
        graphics.add(Graphic(r, "drawing"))
    }
    return PPage(pno, rect, blocks, graphics, dev.rules, dev.marks)
}

private fun lineUnion(lines: List<Line>): Box {
    val r = Box(lines[0].bbox)
    for (ln in lines.drop(1)) r.union(ln.bbox)
    return r
}

private fun union(blocks: List<TBlock>): Box {
    val r = Box(blocks[0].bbox)
    for (b in blocks.drop(1)) r.union(b.bbox)
    return r
}

// ---------------------------------------------------------------------------
// Document-wide statistics and cleanup

private class Stats(val bodyFont: String, val bodySize: Float) {
    fun isMath(font: String) = MATH_FONT_RE.containsMatchIn(font) || (font.startsWith("CMR") && !bodyFont.startsWith("CMR"))

    fun mathRatio(lines: List<Line>): Float {
        var total = 0
        var math = 0
        for (ln in lines) for ((f, n) in ln.fonts) {
            total += n
            if (isMath(f)) math += n
        }
        return if (total > 0) math.toFloat() / total else 0f
    }
}

private fun stats(pages: List<PPage>): Stats {
    val fonts = LinkedHashMap<String, Int>()
    val sizes = LinkedHashMap<Float, Int>()
    for (p in pages) for (b in p.blocks) for (ln in b.lines) {
        for ((f, n) in ln.fonts) fonts.inc(f, n)
        sizes.inc(ln.size, ln.nchars)
    }
    return Stats(fonts.mostCommon() ?: "", sizes.mostCommon() ?: 10f)
}

private val PAGE_NUMBER_RE = Regex("[\\divxlc]+|page\\s*\\d+(\\s*of\\s*\\d+)?", RegexOption.IGNORE_CASE)

private fun dropPageFurniture(pages: List<PPage>) {
    fun marginLines(p: PPage) = p.blocks.flatMap { b -> b.lines.map { b to it } }
        .filter { (_, ln) -> ln.bbox.y1 < p.rect.height * 0.08f || ln.bbox.y0 > p.rect.height * 0.92f }
    fun key(t: String) = t.trim().lowercase().replace(Regex("\\d+"), "#")

    val counts = HashMap<String, Int>()
    for (p in pages) marginLines(p).map { key(it.second.text) }.toSet().forEach { counts.inc(it) }
    val repeat = max(3, (pages.size * 0.3).toInt())
    for (p in pages) {
        val drop = HashSet<Line>()
        for ((_, ln) in marginLines(p)) {
            val t = ln.text.trim()
            if (PAGE_NUMBER_RE.matches(t) || ((counts[key(t)] ?: 0) >= repeat && pages.size >= 3)) drop.add(ln)
        }
        if (drop.isEmpty()) continue
        for (b in p.blocks) b.lines = b.lines.filter { it !in drop }.toMutableList()
        p.blocks = p.blocks.filter { it.lines.isNotEmpty() }.toMutableList()
        for (b in p.blocks) b.bbox = lineUnion(b.lines)
    }
}

// ---------------------------------------------------------------------------
// Ruled tables

/**
 * Tables drawn with full-width rules (booktabs, or a grid): two or more horizontal rules with
 * the same left and right ends, not separated by a caption, with table-like text between them.
 * Like PyMuPDF's table finder, the table's box is the text inside the rules.
 */
private fun findRuledTables(p: PPage, stats: Stats) {
    val groups = mutableListOf<MutableList<Box>>()
    for (r in p.rules.sortedBy { it.y0 }) {
        if (r.y0 < 0 || r.y0 > p.rect.height) continue
        val g = groups.lastOrNull { g -> g.last().let { abs(it.x0 - r.x0) <= 2f && abs(it.x1 - r.x1) <= 2f } }
        if (g != null && abs(g.last().y0 - r.y0) < 0.8f) continue // the same rule drawn twice
        val captionBetween = g != null && p.blocks.any { b ->
            CAPTION_RE.containsMatchIn(b.text) && hoverlap(b.bbox, r) && b.bbox.y0 > g.last().y0 && b.bbox.y1 < r.y0
        }
        if (g != null && !captionBetween) g.add(r) else groups.add(mutableListOf(r))
    }
    val boxes = groups.filter { it.size >= 2 }
        .map { g -> Box(g.minOf { it.x0 }, g.first().y0, g.maxOf { it.x1 }, g.last().y0) }.toMutableList()
    // Small tables set side by side on the same rows, without captions of their own, are one table.
    fun ownCaption(t: Box, other: Box) = p.blocks.any { b ->
        CAPTION_RE.containsMatchIn(b.text) && hoverlap(b.bbox, t) && !hoverlap(b.bbox, other) &&
            (t.y0 - b.bbox.y1 in -2f..40f || b.bbox.y0 - t.y1 in -2f..40f)
    }
    var merged = true
    while (merged) {
        merged = false
        loop@ for (i in boxes.indices) for (j in boxes.indices) {
            val a = boxes[i]
            val b = boxes[j]
            if (i == j || abs(a.y0 - b.y0) > 1.5f || abs(a.y1 - b.y1) > 1.5f || b.x0 - a.x1 !in 0f..80f) continue
            if (ownCaption(a, b) || ownCaption(b, a)) continue
            boxes[i] = a.or(b)
            boxes.removeAt(j)
            merged = true
            break@loop
        }
    }
    class Table(val rules: Box, val text: Box)
    val tables = mutableListOf<Table>()
    for (box in boxes) {
        if (box.width < 80 || box.height < 8) continue
        fun inside(b: Box) = box.contains((b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2)
        val inner = p.blocks.filter { inside(it.bbox) }
        // Rules above and below running text (framed boxes, page headers) are not tables.
        if (inner.any { b ->
                isBodyParagraph(b, stats) && !tableish(b, stats) &&
                    b.lines.map { it.bbox.width }.sorted()[b.lines.size / 2] >= 0.8f * box.width
            }
        ) continue
        // Plots and diagrams: curves or slanted lines between the rules.
        if (p.marks.any { m -> Box(box.x0 - 1, box.y0 - 1, box.x1 + 1, box.y1 + 1).contains((m.x0 + m.x1) / 2, (m.y0 + m.y1) / 2) }) continue
        val lines = p.blocks.flatMap { it.lines }.filter { inside(it.bbox) }
        if (lines.isEmpty()) continue
        val text = lineUnion(lines)
        // Plot frames and gridlines: the text inside fills only part of them.
        if (text.width < 0.6f * box.width || text.height < 0.6f * box.height) continue
        tables.add(Table(box, text))
    }
    for (t in tables) {
        if (tables.none { it !== t && it.rules.contains(t.rules) }) p.graphics.add(Graphic(t.text, "table", extent = t.rules))
    }
}

// ---------------------------------------------------------------------------
// Visuals: figures, tables, display equations

private fun isBodyParagraph(b: TBlock, stats: Stats) =
    b.lines.size >= 2 && abs(b.size - stats.bodySize) <= 1f && b.text.length > 120 && stats.mathRatio(b.lines) < 0.3f

private fun unique(base: String, used: MutableSet<String>): String {
    var out = base
    var n = 1
    while (out in used) {
        n++
        out = "$base-$n"
    }
    used.add(out)
    return out
}

private fun kindOf(m: MatchResult) = if (m.groupValues[1][0] in "Tt") "table" else "figure"

private fun captionConvention(pages: List<PPage>, stats: Stats): Map<String, String> {
    val votes = mapOf("figure" to LinkedHashMap<String, Int>(), "table" to LinkedHashMap())
    for (page in pages) for (cap in page.blocks) {
        val m = if (cap.role == "body") CAPTION_RE.find(cap.text) else null
        if (m == null) continue
        val sides = listOf("above", "below").filter { chainGraphics(page, cap, it, stats).isNotEmpty() }
        if (sides.size == 1) votes.getValue(kindOf(m)).inc(sides[0])
    }
    val default = mapOf("figure" to "above", "table" to "below")
    return votes.mapValues { (k, v) -> v.mostCommon() ?: default.getValue(k) }
}

private fun findCaptioned(page: PPage, stats: Stats, usedIds: MutableSet<String>, convention: Map<String, String>) {
    val captions = page.blocks.filter { it.role == "body" && CAPTION_RE.containsMatchIn(it.text) }

    fun gap(cap: TBlock, direction: String, chain: List<Graphic>) =
        if (direction == "above") max(0f, cap.bbox.y0 - chain.maxOf { it.bbox.y1 })
        else max(0f, chain.minOf { it.bbox.y0 } - cap.bbox.y1)

    class Option(val score: Float, val order: Int, val cap: TBlock, val direction: String)
    val options = mutableListOf<Option>()
    for (cap in captions) {
        val kind = kindOf(CAPTION_RE.find(cap.text)!!)
        for (direction in listOf("above", "below")) {
            val chain = chainGraphics(page, cap, direction, stats)
            if (chain.isNotEmpty()) {
                val penalty = if (direction == convention[kind]) 0f else 5f
                options.add(Option(gap(cap, direction, chain) + penalty, page.blocks.indexOf(cap), cap, direction))
            }
        }
    }
    options.sortWith(compareBy({ it.score }, { it.order }))
    val done = HashSet<TBlock>()
    for (o in options) {
        val cap = o.cap
        if (cap in done) continue
        val chosen = chainGraphics(page, cap, o.direction, stats)
        if (chosen.isEmpty()) continue
        done.add(cap)
        val m = CAPTION_RE.find(cap.text)!!
        val kind = kindOf(m)
        val region = Box(chosen[0].bbox)
        for (g in chosen.drop(1)) region.union(g.bbox)
        // Text extraction sometimes glues a table's header row onto its caption.
        val kept = cap.lines.filter { ln -> !region.contains(ln.bbox.x0 + 1, (ln.bbox.y0 + ln.bbox.y1) / 2) }
        if (kept.isNotEmpty() && kept.size < cap.lines.size) {
            cap.lines = kept.toMutableList()
            cap.bbox = lineUnion(kept)
        }
        // Drawing clusters sometimes run into the caption; never render it.
        if (o.direction == "above") region.y1 = min(region.y1, cap.bbox.y0 - 1) else region.y0 = max(region.y0, cap.bbox.y1 + 1)
        for (g in chosen) g.used = true
        val texts = absorbText(page, region, cap, stats, chosen)
        for (t in texts) region.union(t.bbox)
        cap.role = "caption"
        addCaptioned(page, cap, kind, m.groupValues[2], region, chosen, texts, usedIds)
    }

    // Tables drawn without any rules: the compact block of small or numeric text by the caption.
    for (cap in captions) {
        if (cap in done) continue
        val m = CAPTION_RE.find(cap.text)!!
        if (m.groupValues[1][0] !in "Tt") continue
        val side = convention.getValue("table")
        val blocks = textTable(page, cap, side, stats).ifEmpty {
            textTable(page, cap, if (side == "below") "above" else "below", stats)
        }
        if (blocks.isEmpty()) continue
        for (b in blocks) b.role = "used"
        cap.role = "caption"
        addCaptioned(page, cap, "table", m.groupValues[2], union(blocks), emptyList(), blocks, usedIds)
    }
}

private fun addCaptioned(
    page: PPage, cap: TBlock, kind: String, number: String, region: Box,
    graphics: List<Graphic>, texts: List<TBlock>, usedIds: MutableSet<String>,
) {
    val prefix = if (kind == "table") "tab" else "fig"
    page.visuals.add(VisualRegion(kind, unique("$prefix-$number", usedIds), region, region.or(cap.bbox), cap, graphics, texts))
}

private fun tableish(b: TBlock, stats: Stats): Boolean {
    val toks = b.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val numeric = toks.count { it.any(Char::isDigit) }.toFloat() / max(toks.size, 1)
    val shortLines = b.lines.count { it.text.trim().length < 25 }.toFloat() / b.lines.size
    return b.size <= stats.bodySize * 0.92f || numeric >= 0.3f || (b.lines.size >= 3 && shortLines > 0.7f)
}

private fun textTable(page: PPage, cap: TBlock, direction: String, stats: Stats): List<TBlock> {
    val cands = page.blocks.filter {
        it.role == "body" && it !== cap && !CAPTION_RE.containsMatchIn(it.text) && xoverlap(it.bbox, cap.bbox) > 0
    }
    val out = mutableListOf<TBlock>()
    if (direction == "below") {
        var edge = cap.bbox.y1
        for (b in cands.sortedBy { it.bbox.y0 }) {
            if (b.bbox.y1 <= edge) continue
            if (b.bbox.y0 - edge > 15 || !tableish(b, stats) || isBodyParagraph(b, stats)) break
            out.add(b)
            edge = max(edge, b.bbox.y1)
        }
    } else {
        var edge = cap.bbox.y0
        for (b in cands.sortedBy { -it.bbox.y1 }) {
            if (b.bbox.y0 >= edge) continue
            if (edge - b.bbox.y1 > 15 || !tableish(b, stats) || isBodyParagraph(b, stats)) break
            out.add(b)
            edge = min(edge, b.bbox.y0)
        }
    }
    return if (out.sumOf { it.lines.size } >= 3) out else emptyList()
}

private fun mergeCaptionContinuations(page: PPage) {
    for (cap in page.blocks.filter { it.role == "body" && CAPTION_RE.containsMatchIn(it.text) }) {
        while (true) {
            val nxt = page.blocks.firstOrNull { b ->
                b.role == "body" && b !== cap &&
                    b.bbox.y0 - cap.bbox.y1 in 0f..3f &&
                    abs(b.bbox.x0 - cap.bbox.x0) <= 3 &&
                    abs(b.size - cap.size) <= 0.6f &&
                    b.lines.size <= 4 &&
                    !CAPTION_RE.containsMatchIn(b.text)
            } ?: break
            cap.lines.addAll(nxt.lines)
            cap.bbox.union(nxt.bbox)
            nxt.role = "merged"
        }
    }
}

private fun chainGraphics(page: PPage, cap: TBlock, direction: String, stats: Stats): List<Graphic> {
    val chosen = mutableListOf<Graphic>()
    var edge = if (direction == "above") cap.bbox.y0 else cap.bbox.y1
    val xr = Box(cap.bbox)
    val body = page.blocks.filter { it.role == "body" && it !== cap && isBodyParagraph(it, stats) }
    val tableCaption = CAPTION_RE.find(cap.text)?.let { kindOf(it) } == "table"
    while (true) {
        var added = false
        for (g in page.graphics) {
            if (g.used || g in chosen || xoverlap(g.bbox, xr) < 0.25f * g.bbox.width) continue
            // A ruled table is complete: graphics beyond its rules belong to something else.
            val ruled = chosen.mapNotNull { if (it.kind == "table") it.extent else null }
            if (tableCaption && ruled.isNotEmpty() && ruled.none { r -> Box(r.x0 - 2, r.y0 - 2, r.x1 + 2, r.y1 + 2).contains(g.bbox) }) continue
            val ok: Boolean
            val lo: Float
            val hi: Float
            if (direction == "above") {
                ok = g.bbox.y0 < cap.bbox.y0 - 5 && g.bbox.y1 >= edge - FIG_GAP &&
                    g.bbox.y1 <= cap.bbox.y1 + 0.5f * max(cap.bbox.height, 30f)
                lo = min(g.bbox.y1, edge)
                hi = edge
            } else {
                ok = g.bbox.y1 > cap.bbox.y1 + 5 && g.bbox.y0 <= edge + FIG_GAP &&
                    g.bbox.y0 >= cap.bbox.y0 - 0.5f * max(cap.bbox.height, 30f)
                lo = edge
                hi = max(g.bbox.y0, edge)
            }
            if (!ok) continue
            if (body.any { b -> hoverlap(b.bbox, g.bbox) && (b.bbox.y0 + b.bbox.y1) / 2 in (lo - 1)..(hi + 1) }) continue
            chosen.add(g)
            edge = if (direction == "above") min(edge, g.bbox.y0) else max(edge, g.bbox.y1)
            xr.union(g.bbox)
            added = true
        }
        if (!added) return chosen
    }
}

private fun absorbText(page: PPage, region: Box, exclude: TBlock?, stats: Stats, own: List<Graphic>): List<TBlock> {
    val absorbed = mutableListOf<TBlock>()
    // Labels sitting on another figure's graphics belong to that figure.
    val others = page.graphics.filter { it !in own }.map { Box(it.bbox.x0 - 3, it.bbox.y0 - 3, it.bbox.x1 + 3, it.bbox.y1 + 3) }
    var changed = true
    while (changed) {
        changed = false
        for (b in page.blocks) {
            if (b.role != "body" || b === exclude || b in absorbed) continue
            val inside = b.bbox.and(region).area > 0.5f * b.bbox.area || region.contains(b.bbox)
            val near = !inside && b.lines.size <= 2 && b.text.length <= 80 &&
                !CAPTION_RE.containsMatchIn(b.text) && !NUMBER_PREFIX_RE.containsMatchIn(b.text) &&
                hoverlap(b.bbox, region, -5f) &&
                (abs(b.bbox.y1 - region.y0) < 14 || abs(b.bbox.y0 - region.y1) < 6) &&
                !isBodyParagraph(b, stats)
            val cy = (b.bbox.y0 + b.bbox.y1) / 2
            val side = !inside && b.lines.size <= 3 && b.text.length <= 40 && b.size < stats.bodySize &&
                cy in (region.y0 - 2)..(region.y1 + 2) &&
                (region.x0 - b.bbox.x1 in 0f..25f || b.bbox.x0 - region.x1 in 0f..25f)
            val elsewhere = !inside && others.any { it.contains((b.bbox.x0 + b.bbox.x1) / 2, cy) }
            if ((inside || near || side) && !elsewhere) {
                absorbed.add(b)
                b.role = "used"
                region.union(b.bbox)
                changed = true
            }
        }
    }
    return absorbed
}

private fun findUncaptioned(page: PPage, stats: Stats, usedIds: MutableSet<String>) {
    // Ruled tables without a caption.
    for (g in page.graphics) {
        if (g.used || g.kind != "table" || g.bbox.width < 40 || g.bbox.height < 15) continue
        if (page.visuals.any { it.clip.contains((g.bbox.x0 + g.bbox.x1) / 2, (g.bbox.y0 + g.bbox.y1) / 2) }) continue
        val inner = page.blocks.filter { it.role == "body" && it.bbox.and(g.bbox).area > 0.5f * it.bbox.area }
        if (inner.isEmpty() || inner.any { isBodyParagraph(it, stats) }) continue
        g.used = true
        for (other in page.graphics) {
            if (!other.used && other.bbox.and(g.bbox).area > 0.8f * max(other.bbox.area, 1f)) other.used = true
        }
        val region = Box(g.bbox)
        for (t in absorbText(page, region, null, stats, listOf(g))) region.union(t.bbox)
        page.visuals.add(VisualRegion("table", unique("tab-p${page.number + 1}", usedIds), region, region, null, listOf(g), emptyList()))
    }
    for (g in page.graphics) {
        if (g.used || g.kind == "table") continue
        val bigImage = g.kind == "image" && g.bbox.width > 40 && g.bbox.height > 40
        val bigDrawing = g.kind == "drawing" && g.bbox.width > 80 && g.bbox.height > 60
        if (!(bigImage || bigDrawing)) continue
        // A vector "figure" that is really a text box (framed abstract etc.)
        val inner = page.blocks.filter { it.role == "body" && g.bbox.contains(it.bbox) }
        if (g.kind == "drawing" && inner.any { isBodyParagraph(it, stats) }) continue
        g.used = true
        val region = Box(g.bbox)
        val texts = absorbText(page, region, null, stats, listOf(g))
        for (t in texts) region.union(t.bbox)
        page.visuals.add(VisualRegion("figure", unique("fig-p${page.number + 1}", usedIds), region, region, null, listOf(g), texts))
    }
}

private fun findEquations(page: PPage, stats: Stats, usedIds: MutableSet<String>) {
    val cands = page.blocks.filter {
        it.role == "body" && it.lines.size <= 6 && !CAPTION_RE.containsMatchIn(it.text) && stats.mathRatio(it.lines) >= 0.35f
    }.sortedBy { it.bbox.y0 }
    val groups = mutableListOf<MutableList<TBlock>>()
    for (b in cands) {
        if (groups.isNotEmpty()) {
            val box = union(groups.last())
            if (b.bbox.y0 <= box.y1 + 3 && hoverlap(b.bbox, box, 40f)) {
                groups.last().add(b)
                continue
            }
        }
        groups.add(mutableListOf(b))
    }
    for (grp in groups) {
        val box = union(grp)
        // Equation numbers and stray symbols on the same baseline.
        for (b in page.blocks) {
            if (b.role != "body" || b in grp) continue
            val ov = min(b.bbox.y1, box.y1) - max(b.bbox.y0, box.y0)
            if (ov > 0.5f * b.bbox.height && b.text.length <= 15) {
                grp.add(b)
                box.union(b.bbox)
            }
        }
        if (box.height < 4) continue
        for (b in grp) b.role = "used"
        val alt = grp.sortedWith(compareBy({ it.bbox.y0 }, { it.bbox.x0 })).joinToString(" ") { it.text }
        page.visuals.add(VisualRegion("equation", unique(equationId(grp, page.number), usedIds), box, box, null, emptyList(), grp, alt))
    }
}

private val EQ_NUMBER_RE = Regex("\\((\\d+(?:\\.\\d+)?[a-z]?)\\)\\s*$")

private fun equationId(grp: List<TBlock>, pno: Int): String {
    for (b in grp.sortedBy { -it.bbox.x1 }) {
        EQ_NUMBER_RE.find(b.text)?.let { return "eq-${it.groupValues[1]}" }
    }
    return "eq-p${pno + 1}"
}

// ---------------------------------------------------------------------------
// Roles: title, front matter, headings, footnotes

private fun markTitleAndFront(pages: List<PPage>, stats: Stats): String {
    val first = pages.firstOrNull() ?: return ""
    val top = first.blocks.filter { it.role == "body" && it.bbox.y1 < first.rect.height * 0.5f }
    if (top.isEmpty()) return ""
    val title = top.maxWithOrNull(compareBy({ it.maxSize }, { -it.bbox.y0 }))!!
    if (title.maxSize < stats.bodySize * 1.2f) return ""
    title.role = "title"
    // Titles often break into one block per line.
    var grown = true
    while (grown) {
        grown = false
        for (b in first.blocks) {
            if (b.role == "body" && abs(b.maxSize - title.maxSize) <= 0.5f && hoverlap(b.bbox, title.bbox)) {
                val gap = max(b.bbox.y0 - title.bbox.y1, title.bbox.y0 - b.bbox.y1)
                if (gap <= title.maxSize * 1.2f) {
                    b.role = "merged"
                    title.lines = (title.lines + b.lines).sortedBy { it.bbox.y0 }.toMutableList()
                    title.bbox.union(b.bbox)
                    grown = true
                }
            }
        }
    }
    first.blocks = first.blocks.filter { it.role != "merged" }.toMutableList()
    for (ln in title.lines) ln.heading = 0
    // Everything on page 1 before the first heading is front matter.
    val headingY = first.blocks.filter { b -> b.role == "body" && b.lines.any { it.heading > 0 } }.minOfOrNull { it.bbox.y0 }
    if (headingY != null) {
        for (b in first.blocks) if (b.role == "body" && b.bbox.y1 <= headingY && b !== title) b.role = "front"
    }
    return title.text.replace(Regex("\\s+"), " ").trim()
}

private class TocEntry(val level: Int, val title: String, val page: Int)

private fun toc(doc: FzDocument): List<TocEntry> {
    val out = mutableListOf<TocEntry>()
    fun walk(items: Array<Outline>?, level: Int) {
        for (o in items ?: return) {
            val page = try { doc.pageNumberFromLocation(doc.resolveLink(o)) } catch (e: Exception) { -1 }
            out.add(TocEntry(level, o.title ?: "", page))
            walk(o.down, level + 1)
        }
    }
    walk(try { doc.loadOutline() } catch (e: Exception) { null }, 1)
    return out
}

private fun markHeadings(pages: List<PPage>, toc: List<TocEntry>, stats: Stats) {
    val byPage = toc.groupBy({ it.page }, { it.level to norm(it.title) })
    for (p in pages) {
        val wanted = byPage[p.number].orEmpty()
        for (b in p.blocks) {
            if (b.role != "body") continue
            for ((level, key) in wanted) {
                if (key.isEmpty()) continue
                if (norm(NUMBER_PREFIX_RE.replace(b.text, "")) == key || norm(b.text) == key) {
                    for (ln in b.lines) ln.heading = level
                    break
                }
                if (norm(NUMBER_PREFIX_RE.replace(b.lines[0].text.trim(), "")) == key) {
                    b.lines[0].heading = level
                    break
                }
            }
            if (b.lines.any { it.heading > 0 }) continue
            heuristicHeading(b, stats)
        }
        // An IEEE section title wrapping onto a line of its own: "IV. DATASET FOR ..." / "RECONSTRUCTION".
        for ((prev, b) in p.blocks.zipWithNext()) {
            val last = prev.lines.last()
            if (last.heading == 1 && IEEE_SECTION_RE.containsMatchIn(prev.lines[0].text.trim()) && b.role == "body" &&
                b.lines.size == 1 && b.lines[0].heading == 0 && ALL_CAPS_RE.containsMatchIn(b.lines[0].text.trim()) &&
                b.bbox.y0 - last.bbox.y1 in 0f..last.maxSize && b.bbox.x0 < last.bbox.x1 && b.bbox.x1 > last.bbox.x0
            ) {
                b.lines[0].heading = 1
                prev.lines.add(b.lines[0]) // one block, so the two lines make one heading
                prev.bbox = Box(prev.bbox).union(b.bbox)
                b.role = "used"
            }
        }
    }
}

/**
 * (level, lines) of an IEEE-style heading starting [b], or (0, 0). These are neither bold nor
 * larger than the text: "II. RELATED WORK" in small caps (so judged by its largest glyphs) and
 * "A. Overview" in italics, either wrapping onto more lines.
 */
private fun ieeeHeading(b: TBlock, stats: Stats): Pair<Int, Int> {
    val first = b.lines[0]
    val text = first.text.trim()
    if (text.length > 80 || max(first.size, first.maxSize) < stats.bodySize - 0.6f || first.bold) return 0 to 0
    if (IEEE_SECTION_RE.containsMatchIn(text)) return 1 to 1
    fun italic(ln: Line) = ln.runs.filter { it.text.isNotBlank() }.all { it.italic }
    if (!(italic(first) && IEEE_SUBSECTION_RE.containsMatchIn(text))) return 0 to 0
    var count = 1
    while (count < min(b.lines.size, 3) && italic(b.lines[count])) count++
    if (count < b.lines.size && italic(b.lines[count])) return 0 to 0 // an italic paragraph
    if (b.lines[count - 1].text.trim().endsWith(".")) return 0 to 0
    return 2 to count
}

private fun heuristicHeading(b: TBlock, stats: Stats) {
    val first = b.lines[0]
    val text = first.text.trim()
    val (ieee, ieeeLines) = ieeeHeading(b, stats)
    if (ieee > 0) {
        for (ln in b.lines.take(ieeeLines)) ln.heading = ieee
        return
    }
    if (!first.bold || text.length > 100 || first.size < stats.bodySize - 0.6f) {
        // Unnumbered headings set in a larger regular font (e.g. "Abstract").
        if (!(b.lines.size == 1 && norm(text) in KNOWN_HEADINGS && first.size >= stats.bodySize)) return
    }
    val m = NUMBER_PREFIX_RE.find(text)
    val known = norm(NUMBER_PREFIX_RE.replace(text, "")) in KNOWN_HEADINGS
    val bigger = first.size >= stats.bodySize * 1.15f
    if (!(m != null || known || bigger)) return
    val level = if (m != null) {
        val num = m.groupValues[1].trimEnd('.')
        if (Regex("^[\\dA-Z]").containsMatchIn(num)) num.count { it == '.' } + 1 else 1
    } else 1
    // A heading line followed by more bold lines is a wrapped heading.
    var count = 1
    while (count < b.lines.size && b.lines[count].bold && count < 3) count++
    if (count == b.lines.size || !b.lines[count].bold) {
        for (ln in b.lines.take(count)) ln.heading = min(level, 6)
    }
}

private fun markFootnotes(pages: List<PPage>, stats: Stats) {
    for (p in pages) {
        val h = p.rect.height
        val normal = p.blocks.filter { it.role == "body" && abs(it.size - stats.bodySize) <= 0.6f }
        for (b in p.blocks) {
            if (b.role != "body" || b.bbox.y0 < h * 0.55f) continue
            if (b.size > stats.bodySize * 0.92f) continue
            if (normal.any { n -> n.bbox.y0 > b.bbox.y1 && hoverlap(n.bbox, b.bbox) }) continue
            b.role = "footnote"
        }
    }
}

// ---------------------------------------------------------------------------
// Reading order

private class Item(val bbox: Box, val obj: Any)

private fun split(items: List<Item>, axis: Int, gap: Float): List<List<Item>> {
    val lo: (Item) -> Float = if (axis == 0) { it -> it.bbox.x0 } else { it -> it.bbox.y0 }
    val hi: (Item) -> Float = if (axis == 0) { it -> it.bbox.x1 } else { it -> it.bbox.y1 }
    val s = items.sortedBy(lo)
    val groups = mutableListOf(mutableListOf(s[0]))
    var end = hi(s[0])
    for (it in s.drop(1)) {
        if (lo(it) > end + gap) groups.add(mutableListOf(it)) else groups.last().add(it)
        end = max(end, hi(it))
    }
    return groups
}

private val byPosition = compareBy<Item>({ it.bbox.y0 }, { it.bbox.x0 })

private fun xyOrder(items: List<Item>, contentWidth: Float): List<Item> {
    if (items.size <= 1) return items.toList()
    val cols = split(items, 0, COL_GAP)
    if (cols.size > 1) return cols.flatMap { xyOrder(it, contentWidth) }
    val bands = split(items, 1, 0.5f)
    if (bands.size == 1) return items.sortedWith(byPosition)

    fun columnar(band: List<Item>) =
        (band.size > 1 && split(band, 0, COL_GAP).size > 1) || band.all { it.bbox.width < 0.55f * contentWidth }

    val groups = mutableListOf<List<Item>>()
    var cur = mutableListOf<Item>()
    for (band in bands) {
        if (columnar(band)) {
            cur.addAll(band)
        } else {
            if (cur.isNotEmpty()) {
                groups.add(cur)
                cur = mutableListOf()
            }
            groups.add(band)
        }
    }
    if (cur.isNotEmpty()) groups.add(cur)
    val out = mutableListOf<Item>()
    for (g in groups) {
        if (g.size == items.size) {
            // No progress possible: fall back to band order.
            for (band in bands) out.addAll(band.sortedWith(byPosition))
            return out
        }
        out.addAll(xyOrder(g, contentWidth))
    }
    return out
}

// ---------------------------------------------------------------------------
// Link target resolution

private fun resolveTargets(pages: List<PPage>, registry: List<LinkInfo>, usedIds: MutableSet<String>): Map<Int, String> {
    val result = HashMap<Int, String>()
    val byName = HashMap<String, String>()
    val lineAnchor = HashMap<Line, String>()
    registry.forEachIndexed { idx, link ->
        val name = link.name
        if (name.isNotEmpty() && name in byName) {
            result[idx] = byName.getValue(name)
            return@forEachIndexed
        }
        val p = pages.getOrNull(link.page) ?: return@forEachIndexed
        var target = targetVisual(p, link.x, link.y)
        if (target == null) {
            val line = targetLine(p, link.x, link.y, name)
            if (line != null) {
                target = lineAnchor.getOrPut(line) {
                    makeId(name.ifEmpty { "p${link.page + 1}-$idx" }, usedIds).also { line.anchors.add(it) }
                }
            }
        }
        if (target != null) {
            result[idx] = target
            if (name.isNotEmpty()) byName[name] = target
        }
    }
    return result
}

private fun targetVisual(p: PPage, x: Float, yt: Float): String? {
    var best: Pair<Pair<Int, Float>, String>? = null
    for (v in p.visuals) {
        if (x > 1 && !(v.bbox.x0 - 30 <= x && x <= v.bbox.x1 + 30)) continue
        val cap = v.caption
        val score = when {
            cap != null && cap.bbox.y0 - yt in -20f..12f -> 0 to abs(cap.bbox.y0 - yt)
            yt in (v.bbox.y0 - 20)..v.bbox.y1 -> 1 to abs(v.bbox.y0 - yt)
            else -> continue
        }
        val b = best
        if (b == null || compareValuesBy(score, b.first, { it.first }, { it.second }) < 0) best = score to v.id
    }
    return best?.second
}

private fun targetLine(p: PPage, x: Float, yt: Float, name: String): Line? {
    val lines = p.blocks.filter { it.role != "used" }.flatMap { it.lines }
    if (lines.isEmpty()) return null
    if (x <= 1) {
        // No x in the destination: let the destination's name pick among lines at this height.
        targetLineByName(p, yt, name)?.let { return it }
    }
    for (xcheck in if (x > 1) listOf(true, false) else listOf(false)) {
        var best: Line? = null
        for (ln in lines) {
            if (ln.bbox.y1 < yt - 1) continue
            if (xcheck && !(ln.bbox.x0 - 15 <= x && x <= ln.bbox.x1)) continue
            val b = best
            if (b == null || compareValuesBy(ln, b, { it.bbox.y0 }, { abs(it.bbox.x0 - x) }) < 0) best = ln
        }
        if (best != null) return best
    }
    return lines.maxByOrNull { it.bbox.y1 }
}

private val SECTION_NAME_RE = Regex("^((sub)*section|chapter|appendix)")
private val CITE_TOKEN_RE = Regex("^(?:cite\\.)?([A-Za-z]{3,})")

private fun targetLineByName(p: PPage, yt: Float, name: String): Line? {
    class Cand(val ln: Line, val context: String, val starts: Boolean)
    val cands = mutableListOf<Cand>()
    for (b in p.blocks) {
        if (b.role == "used") continue
        b.lines.forEachIndexed { i, ln ->
            if (ln.bbox.y1 >= yt - 1 && ln.bbox.y0 - yt <= 20) {
                val context = norm(b.lines.subList(i, min(i + 3, b.lines.size)).joinToString(" ") { it.text })
                // Bibliography entries use a hanging indent: the first line sticks out.
                val starts = i == 0 || ln.bbox.x0 < b.lines[i - 1].bbox.x0 - 3 ||
                    (i + 1 < b.lines.size && ln.bbox.x0 < b.lines[i + 1].bbox.x0 - 3)
                cands.add(Cand(ln, context, starts))
            }
        }
    }
    if (cands.isEmpty()) return null
    val token = if (name.startsWith("cite.")) {
        CITE_TOKEN_RE.find(name.substringAfterLast(':'))?.groupValues?.get(1)?.lowercase() ?: ""
    } else ""
    val sectionLink = SECTION_NAME_RE.containsMatchIn(name)
    return cands.minWithOrNull(
        compareBy<Cand>(
            { if (token.isNotEmpty() && token in it.context) -1 else 0 },
            { if (it.ln.heading > 0 && sectionLink) -1 else 0 },
            { if (it.starts) -1 else 0 },
            { it.ln.bbox.y0 },
            { it.ln.bbox.x0 },
        ),
    )?.ln
}

private fun makeId(name: String, used: MutableSet<String>): String {
    var base = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    if (!Regex("^[A-Za-z_]").containsMatchIn(base)) base = "a$base"
    return unique(base, used)
}

// ---------------------------------------------------------------------------
// Rendering visuals

private class Rendered(val png: ByteArray, val width: Int, val height: Int)

private fun render(page: FzPage, pageRect: Box, v: VisualRegion, zoomDefault: Float): Rendered {
    var zoom = zoomDefault
    for (g in v.graphics) {
        if (g.kind == "image" && g.nativeWidth > 0 && g.bbox.width > 0) zoom = max(zoom, g.nativeWidth / g.bbox.width)
    }
    val area = Box(v.clip)
    for (g in v.graphics) g.extent?.let { area.union(it) }
    val clip = Box(area.x0 - 2, area.y0 - 2, area.x1 + 2, area.y1 + 2).and(pageRect)
    zoom = minOf(zoom, 8f, MAX_IMAGE_PX / maxOf(clip.width, clip.height, 1f))
    val x0 = floor(clip.x0 * zoom + 0.001f).toInt()
    val y0 = floor(clip.y0 * zoom + 0.001f).toInt()
    val x1 = ceil(clip.x1 * zoom - 0.001f).toInt()
    val y1 = ceil(clip.y1 * zoom - 0.001f).toInt()
    val pix = Pixmap(ColorSpace.DeviceRGB, x0, y0, max(1, x1 - x0), max(1, y1 - y0), false)
    try {
        pix.clear(255)
        val dev = DrawDevice(pix)
        try {
            page.run(dev, Matrix.Scale(zoom), null)
        } finally {
            dev.close()
            dev.destroy()
        }
        val buf = pix.asPNG()
        try {
            return Rendered(buf.asByteArray(), pix.width, pix.height)
        } finally {
            buf.destroy()
        }
    } finally {
        pix.destroy()
    }
}

// ---------------------------------------------------------------------------
// Assembly

private sealed interface Elem
private class HeadingE(val level: Int, val content: MutableList<Inline>, val id: String, val page: Int) : Elem
private class Paragraph(val content: MutableList<Inline>, val role: String = "body", val page: Int = -1) : Elem
private class VisualE(
    val kind: String, val id: String, val image: String, val alt: String, val caption: List<Inline>,
    val page: Int, val clip: Box, val widthEm: Float, val width: Int, val height: Int,
) : Elem

private class Dehyphenator(pages: List<PPage>) {
    private val word = Regex("[A-Za-z]+(?:-[A-Za-z]+)*")
    private val vocab = HashMap<String, Int>()

    init {
        for (p in pages) for (b in p.blocks) {
            var splitBefore = false
            for (ln in b.lines) {
                val text = ln.text.trimEnd()
                val words = word.findAll(text).map { it.value }.toList()
                val splitAfter = text.endsWith("-")
                // Skip the fragments either side of a line-break hyphen.
                val lo = if (splitBefore) 1 else 0
                val hi = if (splitAfter) words.size - 1 else words.size
                for (i in lo until hi) vocab.inc(words[i].lowercase())
                splitBefore = splitAfter
            }
        }
    }

    private fun count(w: String) = vocab[w] ?: 0

    fun keepHyphen(left: String, right: String): Boolean {
        val lm = Regex("([A-Za-z]+(?:-[A-Za-z]+)*)-$").find(left)
        val rm = Regex("^[A-Za-z]+").find(right)
        if (lm == null || rm == null) return true
        val lw = lm.groupValues[1].lowercase()
        val rw = rm.value.lowercase()
        val joined = lw + rw
        if (count("$lw-$rw") > count(joined)) return true
        if (count(joined) > 0) return false
        val last = lw.substringAfterLast('-')
        return last.length > 1 && rw.length > 1 && count(last) > 0 && count(rw) > 0
    }
}

private val REF_LABEL = Regex("^\\s*(\\[[\\w+.-]+\\]|\\d+\\.\\s)")
private val SENTENCE_END = Regex("[.!?:][\"')\\]]*$")

private class Assembler(val targets: Map<Int, String>, val registry: List<LinkInfo>, val dehyph: Dehyphenator) {
    val elements = mutableListOf<Elem>()
    private val footnotes = mutableListOf<Paragraph>()
    private var headingN = 0
    private var inRefs = false
    private var refLabelled: Boolean? = null

    private fun resolveRuns(runs: List<Run>): List<Run> = runs.map { r ->
        val link = r.link
        if (link == null || !link.startsWith("@")) return@map r
        val idx = link.substring(1).toInt()
        val tgt = targets[idx]
        if (tgt == null) r.withLink(null) else r.withLink("#$tgt", registry[idx].name.startsWith("cite"))
    }.map { Run(it.text, it.bold, it.italic, it.mono, it.vpos, it.link, it.noteref) } // fresh copies: text is mutated

    fun addLine(content: MutableList<Inline>, line: Line) {
        for (a in line.anchors) content.add(Anchor(a))
        val runs = resolveRuns(line.runs)
        if (runs.isEmpty()) return
        val prev = content.lastOrNull { it is Run } as Run?
        if (prev != null) {
            val ptext = prev.text.trimEnd()
            val nxt = runs[0].text
            val nxtAlpha = nxt.isNotEmpty() && nxt[0].isLetter()
            if (ptext.endsWith("-") && ptext.length > 1 && ptext[ptext.length - 2].isLetter() && nxtAlpha &&
                !dehyph.keepHyphen(plainText(content).trimEnd(), nxt)
            ) {
                prev.text = ptext.dropLast(1)
            } else if (ptext.endsWith("-") && nxtAlpha) {
                prev.text = ptext
            } else if (!prev.text.endsWith(" ") && !prev.text.endsWith(" ") && !nxt.startsWith(" ")) {
                prev.text += " "
            }
        }
        content.addAll(runs)
    }

    fun lastParagraph(): Paragraph? {
        for (e in elements.asReversed()) {
            if (e is Paragraph) return if (e.role == "body" || e.role == "reference") e else null
            if (e is HeadingE) return null
        }
        return null
    }

    fun visual(v: VisualE) { elements.add(v) }

    /** Front matter laid out in a grid (authors): one paragraph per cell. */
    fun front(blocks: List<TBlock>) {
        val rows = mutableListOf<MutableList<TBlock>>()
        for (b in blocks.sortedBy { it.bbox.y0 }) {
            if (rows.isNotEmpty() && b.bbox.y0 < rows.last().maxOf { it.bbox.y1 } - 2) rows.last().add(b) else rows.add(mutableListOf(b))
        }
        for (row in rows) {
            for (col in split(row.map { Item(it.bbox, it) }, 0, 0f)) {
                val para = Paragraph(mutableListOf(), "front", row[0].page)
                for (it in col.sortedBy { it.bbox.y0 }) for (ln in (it.obj as TBlock).lines) addLine(para.content, ln)
                elements.add(para)
            }
        }
    }

    fun footnote(b: TBlock) {
        val para = Paragraph(mutableListOf(), "footnote", b.page)
        for (ln in b.lines) addLine(para.content, ln)
        footnotes.add(para)
    }

    private fun flushFootnotes() {
        elements.addAll(footnotes)
        footnotes.clear()
    }

    private fun heading(ln: Line, continues: Boolean) {
        val last = elements.lastOrNull()
        if (continues && last is HeadingE) {
            addLine(last.content, ln)
            return
        }
        flushFootnotes()
        headingN++
        val h = HeadingE(ln.heading, mutableListOf(), "sec-$headingN", ln.page)
        addLine(h.content, ln)
        elements.add(h)
        val key = norm(NUMBER_PREFIX_RE.replace(ln.text.trim(), ""))
        inRefs = key in setOf("references", "bibliography", "literaturecited")
        refLabelled = null
    }

    private fun startsReference(ln: Line, prev: Line?, newBlock: Boolean): Boolean {
        val labelled = refLabelled ?: REF_LABEL.containsMatchIn(ln.text).also { refLabelled = it }
        if (labelled) return REF_LABEL.containsMatchIn(ln.text)
        if (prev != null && !newBlock) return ln.bbox.x0 < prev.bbox.x0 - 3 // hanging indent
        val last = lastParagraph()
        val text = ln.text.trim()
        return last == null || !((text.isNotEmpty() && text[0].isLowerCase()) || !SENTENCE_END.containsMatchIn(contentText(last.content)))
    }

    fun bodyBlock(block: TBlock) {
        var para: Paragraph? = null
        var prev: Line? = null
        for (ln in block.lines) {
            if (ln.heading > 0) {
                heading(ln, continues = prev != null && prev.heading > 0)
                para = null
                prev = ln
                continue
            }
            if (inRefs) {
                if (para == null || startsReference(ln, prev, newBlock = false)) {
                    val p0 = if (prev != null && prev.heading == 0) prev else null
                    para = if (para == null && !startsReference(ln, p0, newBlock = prev == null)) lastParagraph() else null
                    if (para == null) {
                        para = Paragraph(mutableListOf(), "reference", ln.page)
                        elements.add(para)
                    }
                }
            } else {
                var new = para == null
                if (para != null && prev != null && !sameBaseline(prev, ln)) {
                    val indent = ln.bbox.x0 - block.bbox.x0
                    val ptext = prev.text.trimEnd()
                    if (indent > 6 && SENTENCE_END.containsMatchIn(ptext)) {
                        new = true
                    } else if (prev.bbox.x1 < block.bbox.x1 - 30 && SENTENCE_END.containsMatchIn(ptext) && indent > 3) {
                        new = true
                    }
                }
                if (new) {
                    val cand = if (para == null && prev == null) lastParagraph() else null
                    if (cand != null && cand.role == "body" && isContinuation(cand, block)) {
                        para = cand
                    } else {
                        para = Paragraph(mutableListOf(), "body", ln.page)
                        elements.add(para)
                    }
                }
            }
            addLine(para!!.content, ln)
            prev = ln
        }
    }

    fun finish(): List<Elem> {
        flushFootnotes()
        return elements.filter { e -> e !is Paragraph || contentText(e.content).isNotEmpty() || e.content.any { it is Anchor } }
    }
}

private fun sameBaseline(a: Line, b: Line): Boolean {
    val ov = min(a.bbox.y1, b.bbox.y1) - max(a.bbox.y0, b.bbox.y0)
    return ov > 0.5f * min(a.bbox.height, b.bbox.height)
}

private fun isContinuation(prev: Paragraph, block: TBlock): Boolean {
    val text = contentText(prev.content)
    if (text.isEmpty()) return false
    val nxt = block.lines[0].text.trim()
    if (nxt.isEmpty()) return false
    if (text.endsWith("-")) return true
    if (nxt[0].isLowerCase() || nxt[0] in ",;)") return true
    // Mid-sentence break before a number, symbol or proper noun.
    return !SENTENCE_END.containsMatchIn(text) && text.trim().split(Regex("\\s+")).size >= 4
}

// ---------------------------------------------------------------------------
// XHTML

private val ADJ_LINK = Regex("(<a [^>]*?href=\"[^\"]*\">)((?:(?!</a>).)*)</a>\\1")

private fun inlinesHtml(content: List<Inline>): String {
    val out = StringBuilder()
    for (x in content) {
        when (x) {
            is Anchor -> out.append("<span id=\"${xmlEscape(x.id)}\"></span>")
            is Run -> {
                var text = xmlEscape(x.text)
                if (x.mono) text = "<code>$text</code>"
                if (x.italic) text = "<i>$text</i>"
                if (x.bold) text = "<b>$text</b>"
                if (x.vpos.isNotEmpty()) text = "<${x.vpos}>$text</${x.vpos}>"
                val link = x.link
                if (link != null) {
                    text = if (x.noteref) {
                        "<a class=\"noteref\" epub:type=\"noteref\" role=\"doc-biblioref\" href=\"${xmlEscape(link)}\">$text</a>"
                    } else {
                        "<a href=\"${xmlEscape(link)}\">$text</a>"
                    }
                }
                out.append(text)
            }
        }
    }
    var s = out.toString()
    while (true) {
        val merged = ADJ_LINK.replace(s, "$1$2")
        if (merged == s) return s
        s = merged
    }
}

private fun fmt(f: Float): String {
    val r = Math.round(f * 100) / 100.0
    return if (r == Math.floor(r)) "%.1f".format(java.util.Locale.ROOT, r) else r.toString()
}

private fun toDocElements(elems: List<Elem>): List<DocElement> = elems.map { e ->
    when (e) {
        is HeadingE -> Heading(e.level, inlinesHtml(e.content), e.id, e.page)
        is Paragraph -> Block(
            "<p${if (e.role != "body") " class=\"${e.role}\"" else ""}${if (e.page >= 0) " data-pdf-page=\"${e.page}\"" else ""}>" +
                "${inlinesHtml(e.content)}</p>",
        )
        is VisualE -> {
            val cap = if (e.caption.isNotEmpty()) "<figcaption>${inlinesHtml(e.caption)}</figcaption>" else ""
            val style = if (e.widthEm > 0) " style=\"width: ${fmt(e.widthEm)}em\"" else ""
            Block(
                "<figure class=\"${e.kind}\" id=\"${xmlEscape(e.id)}\" data-pdf-page=\"${e.page}\" " +
                    "data-pdf-clip=\"${fmt(e.clip.x0)},${fmt(e.clip.y0)},${fmt(e.clip.x1)},${fmt(e.clip.y1)}\">" +
                    "<img src=\"images/${xmlEscape(e.image)}\" alt=\"${xmlEscape(e.alt)}\" width=\"${e.width}\" " +
                    "height=\"${e.height}\"$style/>$cap</figure>",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Entry point

fun convertPdf(
    input: File,
    out: File,
    fallbackTitle: String = input.nameWithoutExtension,
    progress: (String) -> Unit,
): Document {
    val doc = FzDocument.openDocument(input.absolutePath)
    try {
        if (doc.needsPassword()) throw IllegalArgumentException("This PDF is password-protected")
        val n = doc.countPages()
        if (n == 0) throw IllegalArgumentException("This PDF has no pages")
        val registry = mutableListOf<LinkInfo>()
        val metrics = HashMap<String, FloatArray>()
        val pages = (0 until n).map { i ->
            progress("Reading page ${i + 1} of $n")
            val page = doc.loadPage(i)
            try { readPage(doc, page, i, registry, metrics) } finally { page.destroy() }
        }
        val stats = stats(pages)
        dropPageFurniture(pages)
        for (p in pages) findRuledTables(p, stats)

        val usedIds = HashSet<String>()
        for (p in pages) {
            mergeCaptionContinuations(p)
            p.blocks = p.blocks.filter { it.role != "merged" }.toMutableList()
        }
        val convention = captionConvention(pages, stats)
        for (p in pages) {
            findCaptioned(p, stats, usedIds, convention)
            findUncaptioned(p, stats, usedIds)
            findEquations(p, stats, usedIds)
        }

        markHeadings(pages, toc(doc), stats)
        val pageTitle = markTitleAndFront(pages, stats)
        val metaTitle = (doc.getMetaData(FzDocument.META_INFO_TITLE) ?: "").trim()
        // The metadata title usually has proper casing where the page has SMALL CAPS.
        val title = (if (metaTitle.isNotEmpty() && (pageTitle.isEmpty() || norm(metaTitle) == norm(pageTitle))) metaTitle else pageTitle)
            .ifEmpty { fallbackTitle.ifEmpty { "Untitled" } }
        markFootnotes(pages, stats)
        val targets = resolveTargets(pages, registry, usedIds)
        val asm = Assembler(targets, registry, Dehyphenator(pages))

        val images = LinkedHashMap<String, ByteArray>()
        val rendered = HashMap<String, VisualE>()
        for (p in pages) {
            if (p.visuals.isEmpty()) continue
            progress("Rendering figures on page ${p.number + 1} of $n")
            val page = doc.loadPage(p.number)
            try {
                for (v in p.visuals) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "p${p.number + 1} ${v.id} clip ${v.clip} from ${v.graphics.joinToString { "${it.kind} ${it.bbox}" }}")
                    }
                    val img = render(page, p.rect, v, 4f)
                    val fname = "${v.id}.png"
                    images[fname] = img.png
                    val caption = mutableListOf<Inline>()
                    v.caption?.lines?.forEach { asm.addLine(caption, it) }
                    val alt = v.alt.ifEmpty { if (caption.isNotEmpty()) contentText(caption) else v.kind }
                    rendered[v.id] = VisualE(
                        v.kind, v.id, fname, alt.take(300), caption, p.number, v.clip,
                        if (v.kind == "equation") Math.round((v.clip.width + 4) / stats.bodySize * 100) / 100f else 0f,
                        img.width, img.height,
                    )
                }
            } finally {
                page.destroy()
            }
        }

        progress("Assembling text")
        for (p in pages) {
            val front = p.blocks.filter { it.role == "front" }
            if (front.isNotEmpty()) asm.front(front)
            val items = p.blocks.filter { it.role == "body" }.map { Item(it.bbox, it) } + p.visuals.map { Item(it.bbox, it) }
            if (items.isNotEmpty()) {
                val xs = items.flatMap { listOf(it.bbox.x0, it.bbox.x1) }
                for (it in xyOrder(items, xs.max() - xs.min())) {
                    when (val o = it.obj) {
                        is VisualRegion -> asm.visual(rendered.getValue(o.id))
                        is TBlock -> asm.bodyBlock(o)
                    }
                }
            }
            for (b in p.blocks) if (b.role == "footnote") asm.footnote(b)
        }

        val authorsMeta = doc.getMetaData(FzDocument.META_INFO_AUTHOR) ?: ""
        val authors = authorsMeta.split(Regex(",|;| and ")).map { it.trim() }.filter { it.isNotEmpty() }
        Log.i(TAG, "${input.name}: $n pages, ${rendered.size} visuals, ${registry.size} internal links, ${targets.size} resolved")
        val result = Document(title, authors, toDocElements(asm.finish()), images)
        progress("Writing EPUB")
        EpubWriter.write(result, out)
        return result
    } finally {
        doc.destroy()
    }
}
