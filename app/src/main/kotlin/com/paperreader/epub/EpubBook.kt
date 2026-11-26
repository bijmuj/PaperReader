package com.paperreader.epub

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

data class TocEntry(val title: String, val href: String, val depth: Int)

/**
 * The parts of an unpacked EPUB the reader needs. All hrefs are relative to
 * the book root (e.g. "OEBPS/ch003.xhtml#sec-4").
 */
data class EpubBook(
    val root: File,
    val title: String,
    val authors: List<String>,
    val spine: List<String>,
    val toc: List<TocEntry>,
) {
    companion object {
        fun parse(root: File): EpubBook {
            val opfPath = rootfilePath(File(root, "META-INF/container.xml"))
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseOpf(File(root, opfPath))
            val spine = opf.spine.mapNotNull { opf.manifest[it]?.href }.map { resolve(opfDir, it) }
            require(spine.isNotEmpty()) { "EPUB has an empty spine" }
            val toc = opf.navHref
                ?.let { resolve(opfDir, it) }
                ?.let { nav -> runCatching { parseNav(File(root, nav), nav.substringBeforeLast('/', "")) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: spine.mapIndexed { i, href -> TocEntry("Section ${i + 1}", href, 0) }
            return EpubBook(root, opf.title.ifBlank { "Untitled" }, opf.creators, spine, toc)
        }

        /** Joins [href] onto [baseDir], resolving "." and ".." segments. */
        fun resolve(baseDir: String, href: String): String {
            val (path, frag) = href.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
            val parts = ArrayDeque<String>()
            val full = if (path.startsWith("/")) path else listOf(baseDir, path).filter { it.isNotEmpty() }.joinToString("/")
            for (seg in full.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8"))
                }
            }
            val joined = parts.joinToString("/")
            return if (frag != null) "$joined#$frag" else joined
        }

        private fun parser(file: File): XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(file.inputStream().buffered(), null)
        }

        private fun rootfilePath(container: File): String {
            val p = parser(container)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                    p.getAttributeValue(null, "full-path")?.let { return it }
                }
            }
            error("container.xml has no rootfile")
        }

        private class Item(val href: String, val properties: String)

        private class Opf(
            val title: String,
            val creators: List<String>,
            val manifest: Map<String, Item>,
            val spine: List<String>,
            val navHref: String?,
        )

        private fun parseOpf(file: File): Opf {
            val p = parser(file)
            var title = ""
            val creators = mutableListOf<String>()
            val manifest = mutableMapOf<String, Item>()
            val spine = mutableListOf<String>()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "title" -> if (title.isEmpty()) title = p.nextText().trim()
                    "creator" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let(creators::add)
                    "item" -> {
                        val id = p.getAttributeValue(null, "id") ?: continue
                        val href = p.getAttributeValue(null, "href") ?: continue
                        manifest[id] = Item(href, p.getAttributeValue(null, "properties") ?: "")
                    }
                    "itemref" -> p.getAttributeValue(null, "idref")?.let(spine::add)
                }
            }
            val nav = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.href
            return Opf(title, creators, manifest, spine, nav)
        }

        /** Reads the <nav epub:type="toc"> list, keeping its nesting depth. */
        private fun parseNav(file: File, navDir: String): List<TocEntry> {
            val p = parser(file)
            val out = mutableListOf<TocEntry>()
            var inToc = false
            var navDepth = 0
            var olDepth = 0
            var href: String? = null
            val text = StringBuilder()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "nav" -> {
                            navDepth++
                            val type = (0 until p.attributeCount)
                                .firstOrNull { p.getAttributeName(it) == "type" }
                                ?.let { p.getAttributeValue(it) }
                            if (type?.split(' ')?.contains("toc") == true) inToc = true
                        }
                        "ol" -> if (inToc) olDepth++
                        "a" -> if (inToc) {
                            href = p.getAttributeValue(null, "href")
                            text.clear()
                        }
                    }
                    XmlPullParser.TEXT -> if (href != null) text.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "a" -> if (href != null) {
                            val title = text.toString().replace(Regex("\\s+"), " ").trim()
                            out += TocEntry(title.ifEmpty { "-" }, resolve(navDir, href), (olDepth - 1).coerceAtLeast(0))
                            href = null
                        }
                        "ol" -> if (inToc) olDepth--
                        "nav" -> {
                            navDepth--
                            if (inToc) return out
                        }
                    }
                }
            }
            return out
        }
    }
}
