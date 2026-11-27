"""arXiv papers -> Document, from arXiv's LaTeXML HTML plus the LaTeX source.

arXiv publishes an HTML rendering of most papers (built with LaTeXML), with
the structure a PDF lacks: real sections, MathML equations, HTML tables, and
citations/footnotes/cross-references as links. Its figure images are small
PNGs, so figures are re-taken from the original files in the LaTeX source
(rendering vector PDFs at high resolution) whenever they can be matched.
"""

from __future__ import annotations

import copy
import gzip
import html
import io
import os
import pathlib
import re
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request

import lxml.html
from lxml import etree

from .model import Block, Document, Heading, Markup

USER_AGENT = "paper2epub/0.1"
MATHML_NS = "http://www.w3.org/1998/Math/MathML"
MAX_IMAGE_PX = 4000
TARGET_IMAGE_PX = 3000  # long side for vector figures rendered from the source

_NEW_ID = re.compile(r"(\d{4}\.\d{4,5})(v\d+)?")
_OLD_ID = re.compile(r"([a-z][a-z.-]+/\d{7})(v\d+)?", re.I)

HTML_TAGS = {
    "a", "abbr", "b", "br", "caption", "cite", "code", "col", "colgroup", "dd", "div", "dl", "dt", "em",
    "figcaption", "figure", "hr", "i", "img", "li", "ol", "p", "pre", "q", "s", "small", "span", "strong",
    "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
}
HTML_ATTRS = {"id", "class", "href", "src", "alt", "width", "height", "colspan", "rowspan", "role"}
DROP_CLASSES = {"ltx_ERROR", "ltx_pubnotes", "ltx_bib_cited", "ltx_rdf", "ltx_pagination", "ltx_role_newpage", "ltx_page_navbar"}
UNWRAP_CLASSES = {"ltx_transformed_outer", "ltx_transformed_inner"}
# LaTeXML writes tables in inline context (e.g. inside \resizebox) as spans with these classes.
SPAN_TABLE = {"ltx_tabular": "table", "ltx_thead": "thead", "ltx_tbody": "tbody", "ltx_tfoot": "tfoot",
              "ltx_tr": "tr", "ltx_td": "td"}
SECTION_LEVELS = {
    "ltx_part": 1, "ltx_chapter": 1, "ltx_section": 1, "ltx_appendix": 1, "ltx_bibliography": 1,
    "ltx_index": 1, "ltx_subsection": 2, "ltx_subsubsection": 3,
}


# -- identifiers and fetching -------------------------------------------------


def parse_id(text: str) -> tuple[str, int | None] | None:
    """'2604.14191v2', 'arXiv:2604.14191', an abs/pdf/html URL... -> ('2604.14191', 2)."""
    s = text.strip()
    s = re.sub(r"^(arxiv:)", "", s, flags=re.I)
    m = re.search(r"arxiv\.org/(?:abs|pdf|html|src)/(.+?)(?:\.pdf)?/?(?:[?#].*)?$", s, re.I)
    if m:
        s = m.group(1)
    for pat in (_NEW_ID, _OLD_ID):
        m = pat.fullmatch(s)
        if m:
            return m.group(1), int(m.group(2)[1:]) if m.group(2) else None
    return None


def _cache_dir() -> pathlib.Path:
    base = os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache")
    return pathlib.Path(base) / "paper2epub" / "arxiv"


_last_request = 0.0


def _get(url: str) -> bytes | None:
    """GET with a polite pause between requests; None on 404."""
    global _last_request
    wait = 1.0 - (time.monotonic() - _last_request)
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    finally:
        _last_request = time.monotonic()


def _cached(path: pathlib.Path, url: str) -> bytes | None:
    if path.exists():
        data = path.read_bytes()
        return data or None  # empty file = known 404
    data = _get(url)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data or b"")
    return data


_abs_pages: dict[str, bytes] = {}


def _abs_page(base: str) -> bytes:
    if base not in _abs_pages:
        _abs_pages[base] = _get(f"https://arxiv.org/abs/{base}") or b""
    return _abs_pages[base]


def latest_version(base: str) -> int:
    versions = [int(v) for v in re.findall(rf"/abs/{re.escape(base)}v(\d+)".encode(), _abs_page(base))]
    return max(versions, default=1)


def metadata(base: str) -> tuple[str, list[str]]:
    """Title and authors ("First Last") from the abstract page's citation tags."""
    page = _abs_page(base).decode("utf-8", "replace")
    title = re.search(r'<meta name="citation_title" content="([^"]*)"', page)
    authors = []
    for a in re.findall(r'<meta name="citation_author" content="([^"]*)"', page):
        a = html.unescape(a)
        last, _, first = a.partition(", ")
        authors.append(f"{first} {last}".strip())
    return (html.unescape(title.group(1)) if title else ""), authors


def fetch_html(base: str, version: int | None) -> tuple[int, str]:
    """HTML for [version] (default latest), else the nearest older version that has one."""
    latest = latest_version(base)
    want = min(version or latest, latest)
    for v in [want, *range(want - 1, 0, -1), *range(want + 1, latest + 1)]:
        data = _cached(_cache_dir() / f"{base.replace('/', '_')}v{v}" / "page.html", f"https://arxiv.org/html/{base}v{v}")
        if data and b"ltx_document" in data:
            if v != want:
                print(f"arXiv has no HTML for v{want}; using v{v} (text may differ from v{want})", file=sys.stderr)
            return v, data.decode("utf-8", "replace")
    raise LookupError(f"arXiv has no HTML version of {base}")


def fetch_source(base: str, v: int) -> pathlib.Path | None:
    """Unpacks the LaTeX source of [base]v[v]; returns its folder."""
    folder = _cache_dir() / f"{base.replace('/', '_')}v{v}"
    src = folder / "src"
    if src.is_dir():
        return src
    data = _cached(folder / "source.bin", f"https://arxiv.org/src/{base}v{v}")
    if not data:
        return None
    if data[:2] == b"\x1f\x8b":
        try:
            data = gzip.decompress(data)
        except OSError:
            return None
    src.mkdir(parents=True, exist_ok=True)
    try:
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            tar.extractall(src, filter="data")
    except tarfile.TarError:
        if data[:5] == b"%PDF-":
            return None  # PDF-only submission
        (src / "main.tex").write_bytes(data)
    return src


# -- figures -----------------------------------------------------------------


class _Images:
    """Finds the best available bytes for each <img> in the arXiv HTML."""

    def __init__(self, page_url: str, prefix: str, source: pathlib.Path | None):
        self.page_url = page_url
        self.prefix = prefix  # leading "<id>v<n>/" of image paths
        self.files: dict[str, list[pathlib.Path]] = {}
        if source:
            for f in source.rglob("*"):
                if f.is_file() and f.suffix.lower() in (".pdf", ".png", ".jpg", ".jpeg"):
                    rel = f.relative_to(source).with_suffix("").as_posix().lower()
                    self.files.setdefault(rel, []).append(f)
                    self.files.setdefault(f.stem.lower(), []).append(f)
        self.trims = _trims(source) if source else {}
        self.images: dict[str, bytes] = {}
        self.from_source = 0
        self.from_html = 0

    def get(self, src: str, aspect: float) -> tuple[str, int, int] | None:
        rel = urllib.parse.unquote(src)
        rel = rel[len(self.prefix):] if rel.startswith(self.prefix) else rel
        stem = re.sub(r"\.[a-z0-9]+$", "", rel, flags=re.I).lower()
        short = stem.rsplit("/", 1)[-1]
        data = None
        for f in self.files.get(stem, []) or self.files.get(short, []):
            key = f.with_suffix("").name.lower()
            data = self._from_source(f, aspect, self.trims.get(key, []))
            if data:
                self.from_source += 1
                break
        if data is None:
            data = _get(urllib.parse.urljoin(self.page_url, src))
            if not data:
                return None
            self.from_html += 1
        head = data[:300].lstrip()
        ext = "jpg" if data[:2] == b"\xff\xd8" else "svg" if head.startswith((b"<svg", b"<?xml")) else "png"
        name = f"{len(self.images) + 1:02d}-{re.sub(r'[^A-Za-z0-9_-]', '_', stem.rsplit('/', 1)[-1])[:40]}.{ext}"
        self.images[name] = data
        w, h = _size(data)
        return name, w, h

    @staticmethod
    def _from_source(f: pathlib.Path, aspect: float, trims: list[tuple[float, float, float, float]]) -> bytes | None:
        import pymupdf

        try:
            if f.suffix.lower() == ".pdf":
                with pymupdf.open(f) as doc:
                    page = doc[0]
                    full = page.rect
                    # \includegraphics[trim={l b r t},clip]: try each way the file was included.
                    # The trims are what the author asked for, so they get more slack than the
                    # untrimmed page (arXiv's sizes are rounded and sometimes approximate).
                    rects = [(pymupdf.Rect(full.x0 + l, full.y0 + t, full.x1 - r, full.y1 - b), 0.15)
                             for l, b, r, t in trims]
                    rects.append((full, 0.04))
                    clip = next((c for c, tol in rects if c.width > 1 and c.height > 1
                                 and _same_aspect(c.width / c.height, aspect, tol)), None)
                    if clip is None:
                        return None  # cropped some other way: keep arXiv's render
                    zoom = min(TARGET_IMAGE_PX / max(clip.width, clip.height), 12)
                    pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), clip=clip, alpha=False)
                    return pix.tobytes("png")
            data = f.read_bytes()
            w, h = _size(data)
            if not w or not _same_aspect(w / h, aspect):
                return None
            if max(w, h) > MAX_IMAGE_PX:
                pix = pymupdf.Pixmap(data)
                scale = MAX_IMAGE_PX / max(w, h)
                pix = pymupdf.Pixmap(pix, int(w * scale), int(h * scale), None)
                return pix.tobytes("png")
            return data
        except Exception as e:  # unreadable or unusual file: fall back to arXiv's PNG
            print(f"  could not use {f.name}: {e}", file=sys.stderr)
            return None


_UNITS = {"pt": 72 / 72.27, "bp": 1.0, "cm": 72 / 2.54, "mm": 72 / 25.4, "in": 72.0, "": 1.0}
_INCLUDE = re.compile(r"\\includegraphics\s*(?:\[([^\]]*)\])?\s*\{([^}]+)\}")


def _trims(source: pathlib.Path) -> dict[str, list[tuple[float, float, float, float]]]:
    """File stem -> trims (left, bottom, right, top in PDF points) used with it in the .tex files."""
    out: dict[str, list] = {}
    for tex in source.rglob("*.tex"):
        try:
            text = tex.read_text("utf-8", "replace")
        except OSError:
            continue
        text = re.sub(r"(?<!\\)%.*", "", text)  # comments
        for opts, name in _INCLUDE.findall(text):
            m = re.search(r"trim\s*=\s*\{?([^},]*)\}?", opts or "")
            if not m:
                continue
            parts = re.findall(r"(-?[\d.]+)\s*(pt|bp|cm|mm|in)?", m.group(1))
            if len(parts) != 4:
                continue
            trim = tuple(float(v) * _UNITS[u] for v, u in parts)
            stem = pathlib.PurePosixPath(name.strip()).name.lower()
            stem = re.sub(r"\.(pdf|png|jpe?g|eps)$", "", stem)
            out.setdefault(stem, []).append(trim)
    return out


def _same_aspect(a: float, b: float, tol: float = 0.04) -> bool:
    return not b or abs(a - b) / b < tol


def _size(data: bytes) -> tuple[int, int]:
    from .epub import _image_size

    return _image_size(data) or (0, 0)


# -- HTML -> XHTML -------------------------------------------------------------


def _classes(el) -> set[str]:
    return set((el.get("class") or "").split())


def _local(tag) -> str:
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def _span_tables(el) -> None:
    """Turns LaTeXML's span-built tables into real ones (with col/rowspans), and makes the
    paragraphs/spans around them blocks so the XHTML stays sensible."""
    tables = []
    for node in el.iter("span", "div"):
        cls = _classes(node)
        kind = next((SPAN_TABLE[c] for c in cls if c in SPAN_TABLE), None)
        if kind is None:
            continue
        node.tag = kind
        if kind == "td":
            for c in cls:
                m = re.fullmatch(r"ltx_(col|row)span_(\d+)", c)
                if m:
                    node.set(f"{m.group(1)}span", m.group(2))
        elif kind == "table":
            tables.append(node)
    for t in tables:
        for a in t.iterancestors():
            if a.tag in ("p", "span"):
                a.tag = "div"
            if a is el:
                break
    if tables and el.tag in ("p", "span"):
        el.tag = "div"


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def _text(el) -> str:
    """Visible text, without the TeX source LaTeXML keeps inside <math>."""
    el = copy.deepcopy(el)
    for a in list(el.iter("annotation", "annotation-xml")):
        a.drop_tree()
    return re.sub(r"\s+", " ", el.text_content()).strip()


class _Converter:
    def __init__(self, page_url: str, base: str, images: _Images):
        self.page_url = page_url
        self.base = base
        self.images = images
        self.elements: list = []
        self.notes: list[str] = []  # footnotes waiting for the end of the section
        self.runin: str | None = None
        self.seen_ids: set[str] = set()
        self.started = False  # past the front matter (title block)
        self.title = ""

    _JUNK = re.compile(r"has been altered|violates the \w+ style|do not change the page layout|"
                       r"not able to reliably undo|^preprint\b|^under review", re.I)

    def front(self, el) -> None:
        """Front matter LaTeXML did not recognise (e.g. ICML/ICLR title blocks)."""
        text = _text(el)
        if not text or self._JUNK.search(text) or el.xpath(".//*[contains(@class,'ltx_ERROR')]"):
            return
        if _norm(text) == _norm(self.title):
            return  # the title, set as a plain paragraph
        cls = _classes(el)
        if "ltx_rule" in cls:
            return
        if "ltx_note" in cls:  # footnotetext with affiliations / correspondence
            content = el.find(".//span[@class='ltx_note_content']")
            if content is None:
                return
            content = copy.deepcopy(content)
            for junk in content.xpath(".//sup[contains(@class,'ltx_note_mark')] | .//span[contains(@class,'ltx_note_type')]"):
                junk.drop_tree()
            el = content
        paras = el.xpath(".//p") or [el]
        for p in paras:
            body = self.inner(p).strip()
            if body:
                self.emit(f'<p class="front">{body}</p>')
    # ---- cleaning ----

    def scrub(self, el) -> str:
        """A deep copy of [el] as clean XHTML text."""
        el = copy.deepcopy(el)
        el.tail = None
        etree.strip_elements(el, etree.Comment, etree.ProcessingInstruction, with_tail=False)
        self._notes(el)
        for bad in [e for e in el.iter() if _classes(e) & DROP_CLASSES or _local(e.tag) in (
                "script", "style", "button", "annotation-xml", "svg", "input")]:
            if bad is not el:
                bad.drop_tree()
        for wrap in [e for e in el.iter() if _classes(e) & UNWRAP_CLASSES]:
            if wrap is not el:
                wrap.drop_tag()
        _span_tables(el)
        math_nodes = set()
        for m in el.iter("math"):
            math_nodes.update(m.iter())
        for node in el.iter():
            if not isinstance(node.tag, str):
                continue
            if node in math_nodes:
                for k in [k for k in node.attrib if k in ("style", "intent") or ":" in k]:
                    del node.attrib[k]
                continue
            tag = node.tag.lower()
            if tag in ("img", "object"):
                self._img(node)
                if node.getparent() is None and node is not el:
                    continue
                tag = node.tag
            if tag == "a" and (node.get("href") or "").startswith("data:"):
                node.drop_tree()  # LaTeXML's "download listing" links
                continue
            if tag not in HTML_TAGS:
                node.tag = "div" if tag in ("section", "article", "nav", "header", "footer", "aside", "blockquote") else "span"
            for k in [k for k in node.attrib if k not in HTML_ATTRS]:
                del node.attrib[k]
            cls = [c for c in (node.get("class") or "").split() if c.startswith("ltx_") or c in ("noteref", "reference", "footnote", "figure", "table", "scroll-x", "runin", "front")]
            if cls:
                node.set("class", " ".join(cls))
            elif "class" in node.attrib:
                del node.attrib["class"]
            if tag == "a":
                self._link(node)
        for t in [e for e in el.iter("table") if "ltx_tabular" in _classes(e)]:
            self._wrap_scroll(t)
        out = etree.tostring(el, encoding="unicode", method="xml", with_tail=False)
        return re.sub(r"<math\b(?![^>]*xmlns)", f'<math xmlns="{MATHML_NS}"', out)

    @staticmethod
    def _wrap_scroll(el) -> None:
        parent = el.getparent()
        if parent is None or "scroll-x" in _classes(parent):
            return
        box = lxml.html.Element("div")
        box.set("class", "scroll-x")
        el.addprevious(box)
        box.tail = el.tail
        el.tail = None
        box.append(el)

    def _link(self, a) -> None:
        href = a.get("href")
        if not href:
            return
        url = urllib.parse.urljoin(self.page_url, href)
        page, _, frag = url.partition("#")
        if frag and re.sub(r"v\d+$", "", page.rstrip("/")).endswith(self.base):
            a.set("href", "#" + frag)
            if frag.startswith("bib."):
                a.set("class", " ".join([*(a.get("class") or "").split(), "noteref"]))
                a.set("role", "doc-biblioref")
        elif url.startswith(("http://", "https://", "mailto:")):
            a.set("href", url)
        else:
            del a.attrib["href"]

    def _img(self, img) -> None:
        if img.tag == "object":
            if not (img.get("type") or "").startswith("image/"):
                img.drop_tree()
                return
            img.tag = "img"
            img.set("src", img.get("data") or "")
        src = img.get("src") or ""
        try:
            w, h = float(img.get("width") or 0), float(img.get("height") or 0)
        except ValueError:
            w = h = 0.0
        aspect = w / h if w and h else 0.0
        m = re.search(r"aspect-ratio:\s*([\d.]+)\s*/\s*([\d.]+)", img.get("style") or "")
        if m and float(m.group(2)):
            aspect = float(m.group(1)) / float(m.group(2))
        got = self.images.get(src, aspect) if src else None
        if not got:
            img.drop_tree()
            return
        name, pw, ph = got
        img.set("src", f"images/{name}")
        for k in ("width", "height", "data", "type", "style"):
            img.attrib.pop(k, None)
        if pw and ph:
            img.set("width", str(pw))
            img.set("height", str(ph))
        if (img.get("alt") or "").lower() in ("", "refer to caption"):
            fig = next((a for a in img.iterancestors() if a.tag == "figure"), None)
            cap = fig.find(".//figcaption") if fig is not None else None
            img.set("alt", _text(cap)[:300] if cap is not None else "")

    def _notes(self, el) -> None:
        """Replaces inline footnotes with links to footnote paragraphs."""
        while True:
            note = next((n for n in el.iter("span") if "ltx_note" in _classes(n) and n is not el), None)
            if note is None:
                return
            cls = _classes(note)
            if "ltx_role_footnote" not in cls and "ltx_role_endnote" not in cls:
                note.drop_tree()
                continue
            mark = note.find("sup")
            num = _text(mark) if mark is not None else "*"
            content = note.find(".//span[@class='ltx_note_content']")
            fid = "fn-" + (note.get("id") or f"{len(self.notes) + 1}")
            if content is not None:
                content = copy.deepcopy(content)
                for junk in content.xpath(".//sup[contains(@class,'ltx_note_mark')] | .//span[contains(@class,'ltx_tag_note')]"):
                    junk.drop_tree()
                content.tag = "span"
                body = self.scrub(content)
                self.notes.append(f'<p class="footnote" id="{html.escape(fid)}"><sup>{html.escape(num)}</sup> {body}</p>')
            sup = lxml.html.Element("sup")
            a = lxml.html.Element("a")
            a.set("class", "noteref")
            a.set("href", "#" + fid)
            a.text = num
            sup.append(a)
            sup.tail = note.tail
            note.addprevious(sup)
            note.getparent().remove(note)

    def inner(self, el) -> str:
        """Scrubbed contents of [el] without its own tag."""
        wrapper = copy.deepcopy(el)
        wrapper.tag = "span"
        wrapper.attrib.clear()
        s = self.scrub(wrapper)
        return re.sub(r"^<(span|div)>|</(span|div)>$", "", s) if s not in ("<span/>", "<div/>") else ""

    # ---- structure ----

    def emit(self, xhtml: str) -> None:
        if xhtml.strip():
            self.elements.append(Block(xhtml))

    def flush_notes(self) -> None:
        for n in self.notes:
            self.elements.append(Block(n))
        self.notes = []

    def document(self, article) -> None:
        for child in article:
            self.node(child, 0)
        self.flush_notes()

    def node(self, el, level: int) -> None:
        if not isinstance(el.tag, str):
            return
        tag, cls = el.tag.lower(), _classes(el)
        if cls & DROP_CLASSES or tag in ("nav", "script", "style", "footer", "header"):
            return
        if "ltx_title_document" in cls or "ltx_dates" in cls or "ltx_TOC" in cls:
            return
        if level == 0 and not self.started:
            if "ltx_abstract" in cls or tag == "section" or cls & set(SECTION_LEVELS):
                self.started = True
            elif not (cls & {"ltx_authors", "ltx_title_document"}):
                self.front(el)
                return
        if level == 0 and el.xpath(".//*[contains(@class,'ltx_ERROR')]") and not (
                cls & {"ltx_abstract", "ltx_section", "ltx_appendix", "ltx_bibliography"} or tag == "section"):
            return  # e.g. \\metadata{...} that LaTeXML could not expand
        if "ltx_authors" in cls:
            names = [_text(p) for p in el.xpath(".//span[contains(@class,'ltx_personname')]")]
            names = [re.split(r"\s{2,}|\n", n)[0].strip(" ,*†‡∗") for n in names]
            if names:
                self.emit(f'<p class="front">{html.escape(", ".join(n for n in names if n))}</p>')
            return
        if "ltx_abstract" in cls:
            self.flush_notes()
            self.elements.append(Heading(1, [Markup("Abstract")], "abstract"))
            for c in el:
                if not (isinstance(c.tag, str) and "ltx_title" in _classes(c)):
                    self.node(c, 1)
            return
        if "ltx_paragraph" in cls or "ltx_subparagraph" in cls:
            title = next((c for c in el if isinstance(c.tag, str) and "ltx_title" in _classes(c)), None)
            for c in el:
                if c is title:
                    ident = f' id="{html.escape(el.get("id"))}"' if el.get("id") else ""
                    self.runin = f'<b class="runin"{ident}>{self.inner(c)}</b> '
                else:
                    self.node(c, level)
            self.runin = None
            return
        section_level = next((v for k, v in SECTION_LEVELS.items() if k in cls), None)
        if tag == "section" or section_level:
            self.section(el, section_level or level + 1)
            return
        if "ltx_para" in cls or "ltx_logical-block" in cls:
            for c in el:
                self.node(c, level)
            return
        if "ltx_biblist" in cls:
            for item in el:
                if isinstance(item.tag, str) and "ltx_bibitem" in _classes(item):
                    self.bibitem(item)
            return
        if tag == "p":
            body = self.inner(el)
            if self.runin:
                body, self.runin = self.runin + body, None
            ident = f' id="{html.escape(el.get("id"))}"' if el.get("id") else ""
            ptag = "div" if "<table" in body else "p"
            self.emit(f"<{ptag}{ident}>{body}</{ptag}>")
            return
        if tag == "table" and cls & {"ltx_equation", "ltx_equationgroup", "ltx_eqn_table"}:
            self.emit(f'<div class="scroll-x">{self.scrub(el)}</div>')
            return
        if tag == "figure":
            self.figure(el)
            return
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self.emit(f"<p><b>{self.inner(el)}</b></p>")
            return
        self.emit(self.scrub(el))

    def section(self, el, level: int) -> None:
        self.flush_notes()
        title = next((c for c in el if isinstance(c.tag, str) and "ltx_title" in _classes(c)), None)
        ident = el.get("id") or f"sec-{len(self.elements)}"
        label = self.inner(title) if title is not None else ("References" if "ltx_bibliography" in _classes(el) else "")
        self.elements.append(Heading(min(level, 3), [Markup(label)], ident))
        for c in el:
            if c is not title:
                self.node(c, level)
        self.flush_notes()

    def figure(self, el) -> None:
        kind = "table" if "ltx_table" in _classes(el) else "figure"
        el = copy.deepcopy(el)
        el.set("class", f"{kind} {el.get('class') or ''}".strip())
        self.emit(self.scrub(el))

    def bibitem(self, item) -> None:
        tag = item.find("span")
        label = ""
        if tag is not None and "ltx_tag_bibitem" in _classes(tag):
            t = _text(tag)
            if re.fullmatch(r"\[?\d+\]?", t):
                label = f"[{t.strip('[]')}] "
        blocks = [self.inner(b) for b in item if isinstance(b.tag, str) and "ltx_bibblock" in _classes(b)
                  and "ltx_bib_cited" not in _classes(b)]
        ident = html.escape(item.get("id") or f"bib-{len(self.elements)}")
        self.emit(f'<p class="reference" id="{ident}">{html.escape(label)}{" ".join(b for b in blocks if b)}</p>')


def convert(arg: str) -> Document:
    ident = parse_id(arg)
    if ident is None:
        raise ValueError(f"not an arXiv id or URL: {arg}")
    base, version = ident
    v, text = fetch_html(base, version)
    page_url = f"https://arxiv.org/html/{base}v{v}"
    source = fetch_source(base, v)
    if source is None:
        print("no LaTeX source available; using arXiv's figure images", file=sys.stderr)
    root = lxml.html.document_fromstring(text)
    article = root.find(".//article")
    if article is None:
        raise LookupError("unexpected arXiv HTML layout (no <article>)")
    images = _Images(page_url, f"{base}v{v}/", source)
    conv = _Converter(page_url, base, images)

    title_el = next((e for e in article.iter() if "ltx_title_document" in _classes(e)), None)
    if title_el is not None:
        t = copy.deepcopy(title_el)
        for n in t.xpath(".//*[contains(@class,'ltx_note') or contains(@class,'ltx_pubnotes')]"):
            n.drop_tree()
        title = _text(t)
    else:
        title = base
    meta_title, meta_authors = metadata(base)
    if title_el is None and meta_title:
        title = meta_title
    authors = meta_authors or [
        re.split(r"\s{2,}|\n", _text(p))[0].strip(" ,*†‡∗")
        for p in article.xpath(".//div[contains(@class,'ltx_authors')]//span[contains(@class,'ltx_personname')]")
    ]
    conv.title = title
    conv.document(article)
    print(f"arXiv {base}v{v}: figures from source {images.from_source}, from arXiv HTML {images.from_html}",
          file=sys.stderr)
    return Document(title=title, authors=[a for a in authors if a], elements=conv.elements, images=images.images)
