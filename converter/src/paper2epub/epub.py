"""Document -> EPUB 3 writer (no third-party dependencies)."""

from __future__ import annotations

import datetime
import html
import re
import uuid
import zipfile

from .model import Anchor, Document, Heading, Paragraph, Run, Visual, plain_text

_XML_BAD = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")

CSS = """\
body { font-family: serif; line-height: 1.5; margin: 0 0.6em; }
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
"""


def _esc(s: str) -> str:
    return html.escape(_XML_BAD.sub("", s), quote=True)


class _Writer:
    def __init__(self, doc: Document):
        self.doc = doc
        self.chapters: list[list] = []
        self.id_file: dict[str, str] = {}

    # -- chaptering -------------------------------------------------------

    def split(self) -> None:
        cur: list = []
        for e in self.doc.elements:
            if isinstance(e, Heading) and e.level == 1 and cur:
                self.chapters.append(cur)
                cur = []
            cur.append(e)
        self.chapters.append(cur)
        for i, ch in enumerate(self.chapters):
            fname = self.chapter_file(i)
            for e in ch:
                for ident in _ids(e):
                    self.id_file[ident] = fname

    @staticmethod
    def chapter_file(i: int) -> str:
        return f"ch{i:03d}.xhtml"

    # -- inline rendering ----------------------------------------------------

    def href(self, link: str) -> str | None:
        if link.startswith("#"):
            target = link[1:]
            fname = self.id_file.get(target)
            return f"{fname}#{target}" if fname else None
        return link

    def inlines(self, content: list) -> str:
        out = []
        for x in content:
            if isinstance(x, Anchor):
                out.append(f'<span id="{_esc(x.id)}"></span>')
                continue
            text = _esc(x.text)
            if x.mono:
                text = f"<code>{text}</code>"
            if x.italic:
                text = f"<i>{text}</i>"
            if x.bold:
                text = f"<b>{text}</b>"
            if x.vpos:
                text = f"<{x.vpos}>{text}</{x.vpos}>"
            if x.link:
                href = self.href(x.link)
                if href:
                    if x.noteref:
                        text = f'<a class="noteref" epub:type="noteref" role="doc-biblioref" href="{_esc(href)}">{text}</a>'
                    else:
                        text = f'<a href="{_esc(href)}">{text}</a>'
            out.append(text)
        return _merge_adjacent_links("".join(out))

    # -- blocks ---------------------------------------------------------------

    def element(self, e) -> str:
        if isinstance(e, Heading):
            lvl = min(max(e.level, 1) + 1, 6)  # h1 is the paper title
            return f'<h{lvl} id="{_esc(e.id)}">{self.inlines(e.content)}</h{lvl}>'
        if isinstance(e, Paragraph):
            cls = f' class="{e.role}"' if e.role != "body" else ""
            return f"<p{cls}>{self.inlines(e.content)}</p>"
        if isinstance(e, Visual):
            x0, y0, x1, y1 = e.clip
            cap = f"<figcaption>{self.inlines(e.caption)}</figcaption>" if e.caption else ""
            style = f' style="width: {e.width_em}em"' if e.width_em else ""
            dims = _image_size(self.doc.images.get(e.image, b""))
            size = f' width="{dims[0]}" height="{dims[1]}"' if dims else ""
            return (
                f'<figure class="{e.kind}" id="{_esc(e.id)}" data-pdf-page="{e.page}" '
                f'data-pdf-clip="{x0},{y0},{x1},{y1}">'
                f'<img src="images/{_esc(e.image)}" alt="{_esc(e.alt)}"{size}{style}/>{cap}</figure>'
            )
        raise TypeError(e)

    def chapter(self, i: int, elements: list) -> str:
        body = []
        if i == 0:
            body.append(f'<h1 class="title">{_esc(self.doc.title)}</h1>')
        body.extend(self.element(e) for e in elements)
        title = self.doc.title
        first = next((e for e in elements if isinstance(e, Heading)), None)
        if first is not None and i > 0:
            title = plain_text(first.content).strip()
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
            '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" '
            f'xml:lang="{self.doc.language}" lang="{self.doc.language}">\n'
            f'<head><meta charset="utf-8"/><title>{_esc(title)}</title>'
            '<link rel="stylesheet" type="text/css" href="style.css"/></head>\n<body>\n'
            + "\n".join(body)
            + "\n</body>\n</html>\n"
        )

    # -- navigation -----------------------------------------------------------

    def nav(self) -> str:
        entries = [(1, self.doc.title, f"{self.chapter_file(0)}")]
        for i, ch in enumerate(self.chapters):
            for e in ch:
                if isinstance(e, Heading):
                    entries.append((e.level, plain_text(e.content).strip(), f"{self.chapter_file(i)}#{e.id}"))
        # Nested <ol> lists; a level may only go one deeper than its parent.
        out = ["<ol>"]
        depth = 1
        for n, (level, label, href) in enumerate(entries):
            level = 1 if n == 0 else max(1, min(level, depth + 1))
            if n and level > depth:
                out.append("<ol>")
                depth += 1
            elif n:
                out.append("</li>")
                while depth > level:
                    out.append("</ol></li>")
                    depth -= 1
            out.append(f'<li><a href="{_esc(href)}">{_esc(label) or "-"}</a>')
        out.append("</li>")
        while depth > 1:
            out.append("</ol></li>")
            depth -= 1
        out.append("</ol>")
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
            '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">\n'
            "<head><title>Contents</title></head>\n<body>\n"
            '<nav epub:type="toc" id="toc"><h1>Contents</h1>\n' + "".join(out) + "\n</nav>\n</body>\n</html>\n"
        )

    def opf(self, ident: str) -> str:
        now = datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
        items = [
            '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
            '<item id="css" href="style.css" media-type="text/css"/>',
        ]
        spine = []
        for i in range(len(self.chapters)):
            items.append(f'<item id="ch{i}" href="{self.chapter_file(i)}" media-type="application/xhtml+xml"/>')
            spine.append(f'<itemref idref="ch{i}"/>')
        for n, name in enumerate(sorted(self.doc.images)):
            mt = "image/png" if name.endswith(".png") else "image/jpeg"
            items.append(f'<item id="img{n}" href="images/{_esc(name)}" media-type="{mt}"/>')
        creators = "".join(f"<dc:creator>{_esc(a)}</dc:creator>" for a in self.doc.authors)
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">\n'
            '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n'
            f'<dc:identifier id="uid">urn:uuid:{ident}</dc:identifier>\n'
            f"<dc:title>{_esc(self.doc.title)}</dc:title>\n{creators}\n"
            f"<dc:language>{self.doc.language}</dc:language>\n"
            f'<meta property="dcterms:modified">{now}</meta>\n'
            "</metadata>\n<manifest>\n" + "\n".join(items) + "\n</manifest>\n"
            "<spine>\n" + "\n".join(spine) + "\n</spine>\n</package>\n"
        )


def _image_size(data: bytes) -> tuple[int, int] | None:
    """Pixel size of a PNG or JPEG, read from its header."""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) >= 24:
        return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                return None
            marker = data[i + 1]
            length = int.from_bytes(data[i + 2:i + 4], "big")
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                return int.from_bytes(data[i + 7:i + 9], "big"), int.from_bytes(data[i + 5:i + 7], "big")
            i += 2 + length
    return None


def _ids(e) -> list[str]:
    ids = []
    if isinstance(e, (Heading, Visual)):
        ids.append(e.id)
    content = e.caption if isinstance(e, Visual) else e.content
    ids.extend(x.id for x in content if isinstance(x, Anchor))
    return ids


_ADJ_LINK = re.compile(r'(<a [^>]*?href="[^"]*">)((?:(?!</a>).)*)</a>\1')


def _merge_adjacent_links(s: str) -> str:
    """Joins neighbouring <a> tags with the same href (split by styling)."""
    while True:
        s, n = _ADJ_LINK.subn(r"\1\2", s)
        if not n:
            return s


def write_epub(doc: Document, path: str) -> dict:
    w = _Writer(doc)
    w.split()
    ident = str(uuid.uuid4())
    chapters = [w.chapter(i, ch) for i, ch in enumerate(w.chapters)]
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
            "</rootfiles></container>\n",
            compress_type=zipfile.ZIP_DEFLATED,
        )
        z.writestr("OEBPS/content.opf", w.opf(ident), compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/nav.xhtml", w.nav(), compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/style.css", CSS, compress_type=zipfile.ZIP_DEFLATED)
        for i, text in enumerate(chapters):
            z.writestr(f"OEBPS/{w.chapter_file(i)}", text, compress_type=zipfile.ZIP_DEFLATED)
        for name, data in doc.images.items():
            z.writestr(f"OEBPS/images/{name}", data, compress_type=zipfile.ZIP_STORED)
    links = sum(
        1
        for e in doc.elements
        for x in (e.caption if isinstance(e, Visual) else e.content)
        if isinstance(x, Run) and x.link and x.link.startswith("#")
    )
    return {"chapters": len(chapters), "images": len(doc.images), "internal_link_runs": links}
