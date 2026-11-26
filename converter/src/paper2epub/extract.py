"""PDF -> Document extraction using PyMuPDF.

Pipeline:
  1. Read every page as text lines (char-level, so links can be attached to
     the exact characters they cover), plus image and vector-drawing regions.
  2. Drop running headers/footers, page numbers and rotated margin text.
  3. Per page, find figures/tables (caption + nearby graphics), display
     equations (math-font blocks), footnotes, headings and front matter.
  4. Order what is left with a recursive XY-cut, which handles one- and
     two-column layouts.
  5. Resolve internal link targets (page + point) to the line or visual they
     land on, and drop anchors there.
  6. Assemble headings, paragraphs and visuals into a Document.
"""

from __future__ import annotations

import re
import statistics
from collections import Counter
from dataclasses import dataclass, field

import pymupdf

from .model import Anchor, Document, Heading, Paragraph, Run, Visual, plain_text

TEXT_FLAGS = (
    pymupdf.TEXTFLAGS_RAWDICT
    & ~pymupdf.TEXT_PRESERVE_LIGATURES
    & ~pymupdf.TEXT_PRESERVE_IMAGES
)

MATH_FONT_RE = re.compile(
    r"^(CMMI|CMSY|CMEX|CMBSY|CMMIB|MSAM|MSBM|EUFM|EUSM|EUEX|RSFS|STIX.*Math|"
    r"LMMath|.*-?Math|Symbol|esint|wasy|txsy|txex|pxsy|pxex)",
    re.I,
)
BOLD_RE = re.compile(r"bold|medi|semibold|demi|black|heavy|CMBX|-B$|\.B$", re.I)
ITALIC_RE = re.compile(r"ital|oblique|CMTI|-I$|\.I$", re.I)
MONO_RE = re.compile(r"mono|courier|CMTT|SFTT|consola|menlo|inconsolata", re.I)
CAPTION_RE = re.compile(
    r"^\s*(Figure|Fig\.|FIGURE|FIG\.|Table|TABLE)\s*([A-Z]?\d+|[IVXL]+)\s*[:.|]"
)
NUMBER_PREFIX_RE = re.compile(
    r"^(\d+(\.\d+)*\.?|[A-Z](\.\d+)+\.?|[A-Z]\.|[IVX]+\.)\s+(?=\S)"
)
KNOWN_HEADINGS = {
    "abstract", "introduction", "relatedwork", "background", "method", "methods",
    "methodology", "experiments", "results", "discussion", "conclusion",
    "conclusions", "references", "bibliography", "acknowledgments",
    "acknowledgements", "acknowledgment", "acknowledgement", "appendix",
    "limitations", "broaderimpact", "supplementarymaterial",
}

COL_GAP = 6.0  # minimum horizontal gap (pt) between columns
FIG_GAP = 30.0  # max vertical gap (pt) between a caption and its graphics
MAX_IMAGE_PX = 4000  # cap on rendered image size (longest side)


def _strip_subset(font: str) -> str:
    return font.split("+", 1)[-1]


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


# ---------------------------------------------------------------------------
# Page-level primitives


@dataclass
class Line:
    page: int
    bbox: pymupdf.Rect
    runs: list[Run]
    size: float  # dominant font size
    bold: bool  # all non-space chars are bold
    fonts: Counter  # font name -> non-space char count
    anchors: list[str] = field(default_factory=list)
    heading: int = 0  # heading level, 0 = not a heading
    max_size: float = 0.0  # largest glyph size (small-caps titles mix sizes)

    @property
    def text(self) -> str:
        return "".join(r.text for r in self.runs)

    @property
    def nchars(self) -> int:
        return sum(self.fonts.values())


@dataclass
class Block:
    page: int
    bbox: pymupdf.Rect
    lines: list[Line]
    role: str = "body"  # body | title | front | footnote | caption | used

    @property
    def text(self) -> str:
        return " ".join(ln.text.strip() for ln in self.lines)

    @property
    def size(self) -> float:
        sizes = Counter()
        for ln in self.lines:
            sizes[ln.size] += ln.nchars
        return sizes.most_common(1)[0][0] if sizes else 0.0

    @property
    def nchars(self) -> int:
        return sum(ln.nchars for ln in self.lines)

    @property
    def max_size(self) -> float:
        return max(ln.max_size for ln in self.lines)


@dataclass
class Graphic:
    bbox: pymupdf.Rect
    kind: str  # image | drawing | table
    xref: int = 0
    native: tuple[int, int] = (0, 0)  # pixel size for raster images
    upright: bool = True
    used: bool = False


@dataclass
class VisualRegion:
    """A visual found on a page, before it is rendered."""

    kind: str
    id: str
    clip: pymupdf.Rect  # area rendered into the image
    bbox: pymupdf.Rect  # clip plus caption, used for ordering and targets
    caption: Block | None
    graphics: list[Graphic]
    text_blocks: list[Block]
    alt: str = ""


@dataclass
class Page:
    number: int
    rect: pymupdf.Rect
    blocks: list[Block]
    graphics: list[Graphic]
    links: list[dict]
    visuals: list[VisualRegion] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Reading pages


def _read_links(page: pymupdf.Page, pno: int, registry: list[dict]) -> list[tuple]:
    """Returns [(rect, ref)] for hit-testing; registers internal links."""
    out = []
    for ln in page.get_links():
        kind = ln.get("kind")
        rect = pymupdf.Rect(ln["from"])
        if kind == pymupdf.LINK_URI and ln.get("uri"):
            out.append((rect, ln["uri"]))
        elif kind in (pymupdf.LINK_GOTO, pymupdf.LINK_NAMED) and ln.get("page", -1) >= 0:
            idx = len(registry)
            registry.append(
                {
                    "src_page": pno,
                    "page": ln["page"],
                    "to": ln.get("to"),
                    "name": ln.get("nameddest") or "",
                    "kind": kind,
                }
            )
            out.append((rect, f"@{idx}"))
    return out


def _make_line(pno: int, ln: dict, hit_links: list[tuple]) -> Line | None:
    spans = [s for s in ln["spans"] if s["chars"]]
    if not spans:
        return None
    maxsize = max(s["size"] for s in spans)
    base = next(s for s in spans if s["size"] == maxsize)["origin"][1]
    runs: list[Run] = []
    fonts: Counter = Counter()
    sizes: Counter = Counter()
    nbold = 0
    for s in spans:
        font = _strip_subset(s["font"])
        flags = s["flags"]
        bold = bool(flags & 16) or bool(BOLD_RE.search(font))
        italic = bool(flags & 2) or bool(ITALIC_RE.search(font))
        mono = bool(flags & 8) or bool(MONO_RE.search(font))
        vpos = ""
        if s["size"] < maxsize * 0.85:
            dy = s["origin"][1] - base
            if dy < -0.12 * maxsize:
                vpos = "sup"
            elif dy > 0.08 * maxsize:
                vpos = "sub"
        if not vpos and flags & 1 and s["size"] < maxsize * 0.95:
            vpos = "sup"
        for ch in s["chars"]:
            c = ch["c"]
            if c in "﻿​" or (ord(c) < 32 and c not in "\t"):
                continue
            x0, y0, x1, y1 = ch["bbox"]
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            link = None
            for r, ref in hit_links:
                if r.x0 - 0.5 <= cx <= r.x1 + 0.5 and r.y0 - 0.5 <= cy <= r.y1 + 0.5:
                    link = ref
                    break
            if not c.isspace():
                fonts[font] += 1
                sizes[round(s["size"] * 2) / 2] += 1
                nbold += bold
            run = Run(c, bold, italic, mono, vpos, link)
            if runs and runs[-1].style() == run.style():
                runs[-1].text += c
            else:
                runs.append(run)
    if not fonts:
        return None
    return Line(
        page=pno,
        bbox=pymupdf.Rect(ln["bbox"]),
        runs=runs,
        size=sizes.most_common(1)[0][0],
        bold=nbold == sum(fonts.values()),
        fonts=fonts,
        max_size=round(maxsize * 2) / 2,
    )


def _read_page(page: pymupdf.Page, pno: int, registry: list[dict]) -> Page:
    hit_links = _read_links(page, pno, registry)
    raw = page.get_text("rawdict", flags=TEXT_FLAGS)
    blocks = []
    for b in raw["blocks"]:
        if b.get("type") != 0:
            continue
        lines = []
        for ln in b["lines"]:
            dx, dy = ln["dir"]
            if abs(dx - 1) > 1e-3 or abs(dy) > 1e-3:
                continue  # rotated text, e.g. the arXiv stamp in the margin
            line = _make_line(pno, ln, hit_links)
            if line:
                lines.append(line)
        if lines:
            bbox = pymupdf.Rect(lines[0].bbox)
            for line in lines[1:]:
                bbox |= line.bbox
            blocks.append(Block(pno, bbox, lines))

    graphics: list[Graphic] = []
    for info in page.get_image_info(xrefs=True):
        r = pymupdf.Rect(info["bbox"]) & page.rect
        if r.is_empty or r.width < 4 or r.height < 4:
            continue
        a, b, c, d, _, _ = info["transform"]
        graphics.append(
            Graphic(
                r,
                "image",
                xref=info.get("xref", 0),
                native=(info.get("width", 0), info.get("height", 0)),
                upright=abs(b) < 1e-3 and abs(c) < 1e-3 and a > 0 and d > 0,
            )
        )
    page_area = page.rect.width * page.rect.height
    for r in page.cluster_drawings():
        r = pymupdf.Rect(r) & page.rect
        if r.is_empty or r.width * r.height > 0.9 * page_area:
            continue
        if r.width < 2 and r.height < 2:
            continue
        graphics.append(Graphic(r, "drawing"))
    try:
        for t in page.find_tables().tables:
            graphics.append(Graphic(pymupdf.Rect(t.bbox), "table"))
    except Exception:  # table detection is best-effort
        pass
    return Page(pno, page.rect, blocks, graphics, [])


# ---------------------------------------------------------------------------
# Document-wide statistics and cleanup


@dataclass
class Stats:
    body_font: str
    body_size: float

    def is_math(self, font: str) -> bool:
        if MATH_FONT_RE.match(font):
            return True
        return font.startswith("CMR") and not self.body_font.startswith("CMR")

    def math_ratio(self, lines: list[Line]) -> float:
        total = math = 0
        for ln in lines:
            for f, n in ln.fonts.items():
                total += n
                if self.is_math(f):
                    math += n
        return math / total if total else 0.0


def _stats(pages: list[Page]) -> Stats:
    fonts: Counter = Counter()
    sizes: Counter = Counter()
    for p in pages:
        for b in p.blocks:
            for ln in b.lines:
                fonts.update(ln.fonts)
                sizes[ln.size] += ln.nchars
    return Stats(
        body_font=fonts.most_common(1)[0][0] if fonts else "",
        body_size=sizes.most_common(1)[0][0] if sizes else 10.0,
    )


def _drop_page_furniture(pages: list[Page]) -> None:
    """Removes running headers/footers and page numbers."""

    def margin_lines(p: Page):
        h = p.rect.height
        for b in p.blocks:
            for ln in b.lines:
                if ln.bbox.y1 < h * 0.08 or ln.bbox.y0 > h * 0.92:
                    yield b, ln

    counts: Counter = Counter()
    for p in pages:
        seen = set()
        for _, ln in margin_lines(p):
            key = re.sub(r"\d+", "#", ln.text.strip().lower())
            seen.add(key)
        counts.update(seen)
    repeat = max(3, int(len(pages) * 0.3))
    for p in pages:
        drop = set()
        for b, ln in margin_lines(p):
            t = ln.text.strip()
            key = re.sub(r"\d+", "#", t.lower())
            if re.fullmatch(r"[\divxlc]+|page\s*\d+(\s*of\s*\d+)?", t, re.I) or (
                counts[key] >= repeat and len(pages) >= 3
            ):
                drop.add(id(ln))
        if not drop:
            continue
        for b in p.blocks:
            b.lines = [ln for ln in b.lines if id(ln) not in drop]
        p.blocks = [b for b in p.blocks if b.lines]
        for b in p.blocks:
            b.bbox = pymupdf.Rect(b.lines[0].bbox)
            for ln in b.lines[1:]:
                b.bbox |= ln.bbox


# ---------------------------------------------------------------------------
# Visuals: figures, tables, display equations


def _xoverlap(a: pymupdf.Rect, b: pymupdf.Rect) -> float:
    return max(0.0, min(a.x1, b.x1) - max(a.x0, b.x0))


def _hoverlap(a: pymupdf.Rect, b: pymupdf.Rect, slack: float = 0.0) -> bool:
    return a.x0 - slack < b.x1 and b.x0 - slack < a.x1


def _is_body_paragraph(b: Block, stats: Stats) -> bool:
    return (
        len(b.lines) >= 2
        and abs(b.size - stats.body_size) <= 1.0
        and len(b.text) > 120
        and stats.math_ratio(b.lines) < 0.3
    )


def _unique(base: str, used: set[str]) -> str:
    out, n = base, 1
    while out in used:
        n += 1
        out = f"{base}-{n}"
    used.add(out)
    return out


def _caption_convention(pages: list[Page], stats: Stats) -> dict[str, str]:
    """Learns which side of its graphic each caption kind sits on.

    Returns the side the graphic is on relative to the caption, per kind,
    from captions that only have a graphic on one side.
    """
    votes: dict[str, Counter] = {"figure": Counter(), "table": Counter()}
    for page in pages:
        for cap in page.blocks:
            m = CAPTION_RE.match(cap.text) if cap.role == "body" else None
            if not m:
                continue
            kind = "table" if m.group(1)[0] in "Tt" else "figure"
            sides = [d for d in ("above", "below") if _chain_graphics(page, cap, d, stats)]
            if len(sides) == 1:
                votes[kind][sides[0]] += 1
    default = {"figure": "above", "table": "below"}
    return {k: (v.most_common(1)[0][0] if v else default[k]) for k, v in votes.items()}


def _find_captioned(page: Page, stats: Stats, used_ids: set[str], convention: dict[str, str]) -> None:
    captions = [b for b in page.blocks if b.role == "body" and CAPTION_RE.match(b.text)]

    def gap(cap: Block, direction: str, chain: list[Graphic]) -> float:
        if direction == "above":
            return max(0.0, cap.bbox.y0 - max(g.bbox.y1 for g in chain))
        return max(0.0, min(g.bbox.y0 for g in chain) - cap.bbox.y1)

    # Rank every (caption, direction) pairing by its distance to the nearest
    # graphic and assign closest-first, so one caption cannot steal the
    # graphic that sits right against another caption.
    options = []
    for cap in captions:
        kind = "table" if CAPTION_RE.match(cap.text).group(1)[0] in "Tt" else "figure"
        for direction in ("above", "below"):
            chain = _chain_graphics(page, cap, direction, stats)
            if chain:
                penalty = 0 if direction == convention[kind] else 5
                options.append((gap(cap, direction, chain) + penalty, id(cap), cap, direction))
    options.sort(key=lambda o: (o[0], o[1]))
    done: set[int] = set()
    for _, key, cap, direction in options:
        if key in done:
            continue
        chosen = _chain_graphics(page, cap, direction, stats)
        if not chosen:
            continue
        done.add(key)
        m = CAPTION_RE.match(cap.text)
        kind = "table" if m.group(1)[0] in "Tt" else "figure"
        region = pymupdf.Rect(chosen[0].bbox)
        for g in chosen[1:]:
            region |= g.bbox
        # Text extraction sometimes glues a table's header row onto its caption.
        kept = [ln for ln in cap.lines if not region.contains(pymupdf.Point(ln.bbox.x0 + 1, (ln.bbox.y0 + ln.bbox.y1) / 2))]
        if kept and len(kept) < len(cap.lines):
            cap.lines = kept
            cap.bbox = _line_union(kept)
        # Drawing clusters sometimes run into the caption; never render it.
        if direction == "above":
            region.y1 = min(region.y1, cap.bbox.y0 - 1)
        else:
            region.y0 = max(region.y0, cap.bbox.y1 + 1)
        for g in chosen:
            g.used = True
        texts = _absorb_text(page, region, exclude=cap, stats=stats)
        for t in texts:
            region |= t.bbox
        cap.role = "caption"
        _add_captioned(page, cap, kind, m.group(2), region, chosen, texts, used_ids)

    # Tables drawn without any rules: take the compact block of small or
    # numeric text right next to the caption.
    for cap in captions:
        if id(cap) in done:
            continue
        m = CAPTION_RE.match(cap.text)
        if m.group(1)[0] not in "Tt":
            continue
        side = convention["table"]
        blocks = _text_table(page, cap, side, stats) or _text_table(page, cap, "above" if side == "below" else "below", stats)
        if not blocks:
            continue
        for b in blocks:
            b.role = "used"
        cap.role = "caption"
        _add_captioned(page, cap, "table", m.group(2), _union(blocks), [], blocks, used_ids)


def _add_captioned(page, cap, kind, number, region, graphics, texts, used_ids) -> None:
    prefix = "tab" if kind == "table" else "fig"
    page.visuals.append(
        VisualRegion(
            kind=kind,
            id=_unique(f"{prefix}-{number}", used_ids),
            clip=region,
            bbox=region | cap.bbox,
            caption=cap,
            graphics=graphics,
            text_blocks=texts,
        )
    )


def _line_union(lines: list[Line]) -> pymupdf.Rect:
    r = pymupdf.Rect(lines[0].bbox)
    for ln in lines[1:]:
        r |= ln.bbox
    return r


def _tableish(b: Block, stats: Stats) -> bool:
    toks = b.text.split()
    numeric = sum(bool(re.search(r"\d", t)) for t in toks) / max(len(toks), 1)
    short_lines = sum(len(ln.text.strip()) < 25 for ln in b.lines) / len(b.lines)
    return b.size <= stats.body_size * 0.92 or numeric >= 0.3 or (len(b.lines) >= 3 and short_lines > 0.7)


def _text_table(page: Page, cap: Block, direction: str, stats: Stats) -> list[Block]:
    """Blocks forming a rule-less table directly above/below a caption."""
    cands = [
        b for b in page.blocks
        if b.role == "body" and b is not cap and not CAPTION_RE.match(b.text) and _xoverlap(b.bbox, cap.bbox) > 0
    ]
    out: list[Block] = []
    if direction == "below":
        edge = cap.bbox.y1
        for b in sorted(cands, key=lambda b: b.bbox.y0):
            if b.bbox.y1 <= edge:
                continue
            if b.bbox.y0 - edge > 15 or not _tableish(b, stats) or _is_body_paragraph(b, stats):
                break
            out.append(b)
            edge = max(edge, b.bbox.y1)
    else:
        edge = cap.bbox.y0
        for b in sorted(cands, key=lambda b: -b.bbox.y1):
            if b.bbox.y0 >= edge:
                continue
            if edge - b.bbox.y1 > 15 or not _tableish(b, stats) or _is_body_paragraph(b, stats):
                break
            out.append(b)
            edge = min(edge, b.bbox.y0)
    # One short line is not a table.
    return out if sum(len(b.lines) for b in out) >= 3 else []


def _merge_caption_continuations(page: Page) -> None:
    """Re-joins captions that text extraction split into several blocks."""
    for cap in [b for b in page.blocks if b.role == "body" and CAPTION_RE.match(b.text)]:
        while True:
            nxt = next(
                (
                    b for b in page.blocks
                    if b.role == "body" and b is not cap
                    and 0 <= b.bbox.y0 - cap.bbox.y1 <= 3
                    and abs(b.bbox.x0 - cap.bbox.x0) <= 3
                    and abs(b.size - cap.size) <= 0.6
                    and len(b.lines) <= 4
                    and not CAPTION_RE.match(b.text)
                ),
                None,
            )
            if nxt is None:
                break
            cap.lines.extend(nxt.lines)
            cap.bbox |= nxt.bbox
            nxt.role = "merged"


def _chain_graphics(page: Page, cap: Block, direction: str, stats: Stats) -> list[Graphic]:
    """Collects graphics stacked above/below a caption, stopping at body text."""
    chosen: list[Graphic] = []
    edge = cap.bbox.y0 if direction == "above" else cap.bbox.y1
    xr = pymupdf.Rect(cap.bbox)
    body = [b for b in page.blocks if b.role == "body" and b is not cap and _is_body_paragraph(b, stats)]
    while True:
        added = False
        for g in page.graphics:
            # Must sit substantially within the horizontal span collected so far,
            # so a column-width caption cannot reach into the neighbouring column.
            if g.used or g in chosen or _xoverlap(g.bbox, xr) < 0.25 * g.bbox.width:
                continue
            # A graphic may overlap the caption a little (invisible drawing
            # paths often do) but must lie mostly on the requested side.
            if direction == "above":
                ok = g.bbox.y0 < cap.bbox.y0 - 5 and g.bbox.y1 >= edge - FIG_GAP
                ok = ok and g.bbox.y1 <= cap.bbox.y1 + 0.5 * max(cap.bbox.height, 30)
                lo, hi = min(g.bbox.y1, edge), edge
            else:
                ok = g.bbox.y1 > cap.bbox.y1 + 5 and g.bbox.y0 <= edge + FIG_GAP
                ok = ok and g.bbox.y0 >= cap.bbox.y0 - 0.5 * max(cap.bbox.height, 30)
                lo, hi = edge, max(g.bbox.y0, edge)
            if not ok:
                continue
            if any(_hoverlap(b.bbox, g.bbox) and lo - 1 < (b.bbox.y0 + b.bbox.y1) / 2 < hi + 1 for b in body):
                continue  # a paragraph sits between this graphic and the figure
            chosen.append(g)
            edge = min(edge, g.bbox.y0) if direction == "above" else max(edge, g.bbox.y1)
            xr |= g.bbox
            added = True
        if not added:
            return chosen


def _absorb_text(page: Page, region: pymupdf.Rect, exclude: Block | None, stats: Stats) -> list[Block]:
    """Pulls in text inside a figure region and short labels just outside it."""
    absorbed: list[Block] = []
    changed = True
    while changed:
        changed = False
        for b in page.blocks:
            if b.role != "body" or b is exclude or b in absorbed:
                continue
            inside = (b.bbox & region).get_area() > 0.5 * b.bbox.get_area() or region.contains(b.bbox)
            near = (
                not inside
                and len(b.lines) <= 2
                and len(b.text) <= 80
                and not CAPTION_RE.match(b.text)
                and not NUMBER_PREFIX_RE.match(b.text)
                and _hoverlap(b.bbox, region, slack=-5)
                and (abs(b.bbox.y1 - region.y0) < 14 or abs(b.bbox.y0 - region.y1) < 6)
                and not _is_body_paragraph(b, stats)
            )
            # Axis labels and annotations set beside the graphic.
            side = (
                not inside
                and len(b.lines) <= 3
                and len(b.text) <= 40
                and b.size < stats.body_size
                and region.y0 - 2 <= (b.bbox.y0 + b.bbox.y1) / 2 <= region.y1 + 2
                and (0 <= region.x0 - b.bbox.x1 <= 25 or 0 <= b.bbox.x0 - region.x1 <= 25)
            )
            if inside or near or side:
                absorbed.append(b)
                b.role = "used"
                region |= b.bbox
                changed = True
    return absorbed


def _find_uncaptioned(page: Page, stats: Stats, used_ids: set[str]) -> None:
    # Ruled tables without a caption ("the following table summarizes ...").
    for g in page.graphics:
        if g.used or g.kind != "table" or g.bbox.width < 40 or g.bbox.height < 15:
            continue
        inner = [b for b in page.blocks if b.role == "body" and (b.bbox & g.bbox).get_area() > 0.5 * b.bbox.get_area()]
        if not inner or any(_is_body_paragraph(b, stats) for b in inner):
            continue
        g.used = True
        for other in page.graphics:
            if not other.used and (other.bbox & g.bbox).get_area() > 0.8 * max(other.bbox.get_area(), 1):
                other.used = True
        region = pymupdf.Rect(g.bbox)
        texts = _absorb_text(page, region, exclude=None, stats=stats)
        for t in texts:
            region |= t.bbox
        page.visuals.append(
            VisualRegion("table", _unique(f"tab-p{page.number + 1}", used_ids), region, region, None, [g], texts)
        )
    for g in page.graphics:
        if g.used or g.kind == "table":
            continue
        big_image = g.kind == "image" and g.bbox.width > 40 and g.bbox.height > 40
        big_drawing = g.kind == "drawing" and g.bbox.width > 80 and g.bbox.height > 60
        if not (big_image or big_drawing):
            continue
        # A vector "figure" that is really a text box (framed abstract etc.)
        inner = [b for b in page.blocks if b.role == "body" and g.bbox.contains(b.bbox)]
        if g.kind == "drawing" and any(_is_body_paragraph(b, stats) for b in inner):
            continue
        g.used = True
        region = pymupdf.Rect(g.bbox)
        texts = _absorb_text(page, region, exclude=None, stats=stats)
        for t in texts:
            region |= t.bbox
        page.visuals.append(
            VisualRegion("figure", _unique(f"fig-p{page.number + 1}", used_ids), region, region, None, [g], texts)
        )


def _find_equations(page: Page, stats: Stats, used_ids: set[str]) -> None:
    cands = [
        b
        for b in page.blocks
        if b.role == "body"
        and len(b.lines) <= 6
        and not CAPTION_RE.match(b.text)
        and stats.math_ratio(b.lines) >= 0.35
    ]
    cands.sort(key=lambda b: b.bbox.y0)
    groups: list[list[Block]] = []
    for b in cands:
        if groups:
            box = _union(groups[-1])
            if b.bbox.y0 <= box.y1 + 3 and _hoverlap(b.bbox, box, slack=40):
                groups[-1].append(b)
                continue
        groups.append([b])
    for grp in groups:
        box = _union(grp)
        # Equation numbers and stray symbols on the same baseline.
        for b in page.blocks:
            if b.role != "body" or b in grp:
                continue
            ov = min(b.bbox.y1, box.y1) - max(b.bbox.y0, box.y0)
            if ov > 0.5 * b.bbox.height and len(b.text) <= 15:
                grp.append(b)
                box |= b.bbox
        if box.height < 4:
            continue
        for b in grp:
            b.role = "used"
        page.visuals.append(
            VisualRegion(
                "equation",
                _unique(_equation_id(grp, page.number), used_ids),
                box,
                box,
                None,
                [],
                grp,
                alt=" ".join(b.text for b in sorted(grp, key=lambda b: (b.bbox.y0, b.bbox.x0))),
            )
        )


def _equation_id(grp: list[Block], pno: int) -> str:
    """eq-<n> when the equation carries a printed number like "(3)"."""
    for b in sorted(grp, key=lambda b: -b.bbox.x1):
        m = re.search(r"\((\d+(?:\.\d+)?[a-z]?)\)\s*$", b.text)
        if m:
            return f"eq-{m.group(1)}"
    return f"eq-p{pno + 1}"


def _union(blocks: list[Block]) -> pymupdf.Rect:
    r = pymupdf.Rect(blocks[0].bbox)
    for b in blocks[1:]:
        r |= b.bbox
    return r


# ---------------------------------------------------------------------------
# Roles: title, front matter, headings, footnotes


def _mark_title_and_front(pages: list[Page], stats: Stats) -> str:
    if not pages:
        return ""
    first = pages[0]
    top = [b for b in first.blocks if b.role == "body" and b.bbox.y1 < first.rect.height * 0.5]
    if not top:
        return ""
    title_block = max(top, key=lambda b: (b.max_size, -b.bbox.y0))
    if title_block.max_size < stats.body_size * 1.2:
        return ""
    title_block.role = "title"
    # Titles often break into one block per line.
    grown = True
    while grown:
        grown = False
        for b in first.blocks:
            if b.role == "body" and abs(b.max_size - title_block.max_size) <= 0.5 and _hoverlap(b.bbox, title_block.bbox):
                gap = max(b.bbox.y0 - title_block.bbox.y1, title_block.bbox.y0 - b.bbox.y1)
                if gap <= title_block.max_size * 1.2:
                    b.role = "merged"
                    lines = sorted(title_block.lines + b.lines, key=lambda ln: ln.bbox.y0)
                    title_block.lines = lines
                    title_block.bbox |= b.bbox
                    grown = True
    first.blocks = [b for b in first.blocks if b.role != "merged"]
    for ln in title_block.lines:
        ln.heading = 0
    # Everything on page 1 before the first heading is front matter.
    heading_y = min(
        (b.bbox.y0 for b in first.blocks if b.role == "body" and any(ln.heading for ln in b.lines)),
        default=None,
    )
    if heading_y is not None:
        for b in first.blocks:
            if b.role == "body" and b.bbox.y1 <= heading_y and b is not title_block:
                b.role = "front"
    return re.sub(r"\s+", " ", title_block.text).strip()


def _mark_headings(pages: list[Page], toc: list, stats: Stats) -> None:
    by_page: dict[int, list[tuple[int, str]]] = {}
    for level, title, pno, *_ in toc:
        by_page.setdefault(pno - 1, []).append((level, _norm(title)))

    for p in pages:
        wanted = by_page.get(p.number, [])
        for b in p.blocks:
            if b.role != "body":
                continue
            # TOC match: whole block or its first line.
            for level, key in wanted:
                if not key:
                    continue
                if _norm(NUMBER_PREFIX_RE.sub("", b.text)) == key or _norm(b.text) == key:
                    for ln in b.lines:
                        ln.heading = level
                    break
                first = b.lines[0].text.strip()
                if _norm(NUMBER_PREFIX_RE.sub("", first)) == key:
                    b.lines[0].heading = level
                    break
            if any(ln.heading for ln in b.lines):
                continue
            _heuristic_heading(b, stats)


def _heuristic_heading(b: Block, stats: Stats) -> None:
    first = b.lines[0]
    text = first.text.strip()
    if not first.bold or len(text) > 100 or first.size < stats.body_size - 0.6:
        # Unnumbered headings set in a larger regular font (e.g. "Abstract").
        if not (len(b.lines) == 1 and _norm(text) in KNOWN_HEADINGS and first.size >= stats.body_size):
            return
    m = NUMBER_PREFIX_RE.match(text)
    known = _norm(NUMBER_PREFIX_RE.sub("", text)) in KNOWN_HEADINGS
    bigger = first.size >= stats.body_size * 1.15
    if not (m or known or bigger):
        return
    if m:
        num = m.group(1).rstrip(".")
        level = num.count(".") + 1 if re.match(r"^[\dA-Z]", num) else 1
    else:
        level = 1
    # A heading line followed by more bold lines is a wrapped heading.
    count = 1
    while count < len(b.lines) and b.lines[count].bold and count < 3:
        count += 1
    if count == len(b.lines) or not b.lines[count].bold:
        for ln in b.lines[:count]:
            ln.heading = min(level, 6)


def _mark_footnotes(pages: list[Page], stats: Stats) -> None:
    for p in pages:
        h = p.rect.height
        normal = [b for b in p.blocks if b.role == "body" and abs(b.size - stats.body_size) <= 0.6]
        for b in p.blocks:
            if b.role != "body" or b.bbox.y0 < h * 0.55:
                continue
            if b.size > stats.body_size * 0.92:
                continue
            if any(n.bbox.y0 > b.bbox.y1 and _hoverlap(n.bbox, b.bbox) for n in normal):
                continue
            b.role = "footnote"


# ---------------------------------------------------------------------------
# Reading order


class _Item:
    def __init__(self, bbox: pymupdf.Rect, obj):
        self.bbox = bbox
        self.obj = obj


def _split(items: list[_Item], axis: int, gap: float) -> list[list[_Item]]:
    lo = (lambda it: it.bbox.x0) if axis == 0 else (lambda it: it.bbox.y0)
    hi = (lambda it: it.bbox.x1) if axis == 0 else (lambda it: it.bbox.y1)
    s = sorted(items, key=lo)
    groups = [[s[0]]]
    end = hi(s[0])
    for it in s[1:]:
        if lo(it) > end + gap:
            groups.append([it])
        else:
            groups[-1].append(it)
        end = max(end, hi(it))
    return groups


def _xy_order(items: list[_Item], content_width: float) -> list[_Item]:
    if len(items) <= 1:
        return list(items)
    cols = _split(items, 0, COL_GAP)
    if len(cols) > 1:
        return [x for c in cols for x in _xy_order(c, content_width)]
    bands = _split(items, 1, 0.5)
    if len(bands) == 1:
        return sorted(items, key=lambda it: (it.bbox.y0, it.bbox.x0))

    def columnar(band: list[_Item]) -> bool:
        if len(band) > 1 and len(_split(band, 0, COL_GAP)) > 1:
            return True
        return all(it.bbox.width < 0.55 * content_width for it in band)

    groups: list[list[_Item]] = []
    cur: list[_Item] = []
    for band in bands:
        if columnar(band):
            cur.extend(band)
        else:
            if cur:
                groups.append(cur)
                cur = []
            groups.append(band)
    if cur:
        groups.append(cur)
    out = []
    for g in groups:
        if len(g) == len(items):
            # No progress possible: fall back to band order.
            for band in bands:
                out.extend(sorted(band, key=lambda it: (it.bbox.y0, it.bbox.x0)))
            return out
        out.extend(_xy_order(g, content_width))
    return out


# ---------------------------------------------------------------------------
# Link target resolution


def _resolve_targets(
    pages: list[Page], registry: list[dict], doc: pymupdf.Document, used_ids: set[str]
) -> dict[int, str]:
    """Maps link index -> anchor id, inserting anchors on target lines."""
    result: dict[int, str] = {}
    by_name: dict[str, str] = {}
    line_anchor: dict[int, str] = {}

    for idx, link in enumerate(registry):
        name = link["name"]
        if name and name in by_name:
            result[idx] = by_name[name]
            continue
        pno = link["page"]
        if pno >= len(pages):
            continue
        p = pages[pno]
        x, yt = _target_point(link, doc[pno])
        target = _target_visual(p, x, yt)
        if target is None:
            line = _target_line(p, x, yt, name)
            if line is not None:
                key = id(line)
                if key in line_anchor:
                    target = line_anchor[key]
                else:
                    target = _make_id(name or f"p{pno + 1}-{idx}", used_ids)
                    line.anchors.append(target)
                    line_anchor[key] = target
        if target is not None:
            result[idx] = target
            if name:
                by_name[name] = target
    return result


def _target_visual(p: Page, x: float, yt: float) -> str | None:
    """Picks the visual a link target lands on, preferring caption hits."""
    best = None
    for v in p.visuals:
        if x > 1 and not (v.bbox.x0 - 30 <= x <= v.bbox.x1 + 30):  # x == 0: target has no x
            continue
        if v.caption is not None and -20 <= v.caption.bbox.y0 - yt <= 12:
            score = (0, abs(v.caption.bbox.y0 - yt))
        elif v.bbox.y0 - 20 <= yt <= v.bbox.y1:
            score = (1, abs(v.bbox.y0 - yt))
        else:
            continue
        if best is None or score < best[0]:
            best = (score, v.id)
    return best[1] if best else None


def _target_point(link: dict, page: pymupdf.Page) -> tuple[float, float]:
    to = link.get("to")
    if to is None or (to.x == 0 and to.y == 0):
        return 0.0, 0.0
    # Named destinations come back in PDF space (origin bottom-left).
    if link["kind"] == pymupdf.LINK_NAMED:
        return to.x, page.rect.height - to.y
    return to.x, to.y


def _target_line(p: Page, x: float, yt: float, name: str = "") -> Line | None:
    lines = [ln for b in p.blocks if b.role not in ("used",) for ln in b.lines]
    if not lines:
        return None
    if x <= 1:
        # No x in the destination: in multi-column text several lines sit at
        # this height. Let the destination's name pick the right one.
        hit = _target_line_by_name(p, yt, name)
        if hit is not None:
            return hit
    for xcheck in ((True, False) if x > 1 else (False,)):
        best = None
        for ln in lines:
            if ln.bbox.y1 < yt - 1:
                continue
            if xcheck and not (ln.bbox.x0 - 15 <= x <= ln.bbox.x1):
                continue
            if best is None or (ln.bbox.y0, abs(ln.bbox.x0 - x)) < (best.bbox.y0, abs(best.bbox.x0 - x)):
                best = ln
        if best is not None:
            return best
    return max(lines, key=lambda ln: ln.bbox.y1)


def _target_line_by_name(p: Page, yt: float, name: str) -> Line | None:
    """Among lines just below [yt], prefer one whose entry mentions the cited author."""
    cands = []
    for b in p.blocks:
        if b.role == "used":
            continue
        for i, ln in enumerate(b.lines):
            if ln.bbox.y1 >= yt - 1 and ln.bbox.y0 - yt <= 20:
                context = _norm(" ".join(x.text for x in b.lines[i:i + 3]))
                # Bibliography entries use a hanging indent: the first line sticks out.
                starts = i == 0 or ln.bbox.x0 < b.lines[i - 1].bbox.x0 - 3 or (
                    i + 1 < len(b.lines) and ln.bbox.x0 < b.lines[i + 1].bbox.x0 - 3
                )
                cands.append((ln, context, starts))
    if not cands:
        return None
    m = re.match(r"(?:cite\.)?([A-Za-z]{3,})", name.split(":")[-1] if name.startswith("cite.") else "")
    token = m.group(1).lower() if m and name.startswith("cite.") else ""

    def score(c):
        ln, context, starts = c
        return (
            -(token != "" and token in context),
            -bool(ln.heading and re.match(r"(sub)*section|chapter|appendix", name)),
            -starts,
            ln.bbox.y0,
            ln.bbox.x0,
        )

    return min(cands, key=score)[0]


def _make_id(name: str, used: set[str]) -> str:
    base = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
    if not re.match(r"[A-Za-z_]", base):
        base = "a" + base
    return _unique(base, used)


# ---------------------------------------------------------------------------
# Rendering visuals


def _render(doc: pymupdf.Document, v: VisualRegion, zoom_default: float) -> tuple[bytes, str]:
    page = doc[v.clip_page]
    images = [g for g in v.graphics if g.kind == "image"]
    # A lone raster image with nothing drawn over it: ship the original bytes.
    if (
        len(v.graphics) == 1
        and images
        and not v.text_blocks
        and images[0].upright
        and images[0].xref
    ):
        native = _native_image(doc, images[0].xref)
        if native:
            return native
    zoom = zoom_default
    for g in images:
        if g.native[0] and g.bbox.width:
            zoom = max(zoom, g.native[0] / g.bbox.width)
    clip = (v.clip + (-2, -2, 2, 2)) & page.rect
    zoom = min(zoom, 8.0, MAX_IMAGE_PX / max(clip.width, clip.height, 1))
    pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), clip=clip, alpha=False)
    return pix.tobytes("png"), "png"


def _native_image(doc: pymupdf.Document, xref: int) -> tuple[bytes, str] | None:
    try:
        info = doc.extract_image(xref)
    except Exception:
        return None
    if not info:
        return None
    if info.get("ext") in ("jpeg", "jpg", "png") and not info.get("smask"):
        ext = "jpg" if info["ext"] in ("jpeg", "jpg") else "png"
        return info["image"], ext
    try:
        pix = pymupdf.Pixmap(doc, xref)
        if info.get("smask"):
            pix = pymupdf.Pixmap(pix, pymupdf.Pixmap(doc, info["smask"]))
        if pix.colorspace and pix.colorspace.n not in (1, 3):
            pix = pymupdf.Pixmap(pymupdf.csRGB, pix)
        return pix.tobytes("png"), "png"
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Assembly


def _resolve_runs(runs: list[Run], targets: dict[int, str], registry: list[dict]) -> list[Run]:
    out = []
    for r in runs:
        if r.link and r.link.startswith("@"):
            idx = int(r.link[1:])
            tgt = targets.get(idx)
            if tgt is None:
                r = Run(r.text, r.bold, r.italic, r.mono, r.vpos, None)
            else:
                name = registry[idx]["name"]
                r = Run(r.text, r.bold, r.italic, r.mono, r.vpos, "#" + tgt, name.startswith("cite"))
        out.append(r)
    return out


class _Dehyphenator:
    """Decides whether a hyphen at a line break is a real hyphen.

    Uses the document's own vocabulary: if "sequence-aligned" or both halves
    appear as words elsewhere, keep it; if "convolutional" appears, drop it.
    """

    WORD = re.compile(r"[A-Za-z]+(?:-[A-Za-z]+)*")

    def __init__(self, pages: list[Page]):
        self.vocab: Counter = Counter()
        for p in pages:
            for b in p.blocks:
                split_before = False
                for ln in b.lines:
                    text = ln.text.rstrip()
                    words = self.WORD.findall(text)
                    split_after = text.endswith("-")
                    # Skip the fragments either side of a line-break hyphen.
                    lo = 1 if split_before else 0
                    hi = len(words) - 1 if split_after else len(words)
                    self.vocab.update(w.lower() for w in words[lo:hi])
                    split_before = split_after

    def keep_hyphen(self, left: str, right: str) -> bool:
        lm = re.search(r"([A-Za-z]+(?:-[A-Za-z]+)*)-$", left)
        rm = re.match(r"[A-Za-z]+", right)
        if not lm or not rm:
            return True
        lw, rw = lm.group(1).lower(), rm.group(0).lower()
        joined, hyph = lw + rw, f"{lw}-{rw}"
        if self.vocab[hyph] > self.vocab[joined]:
            return True
        if self.vocab[joined]:
            return False
        last = lw.rsplit("-", 1)[-1]
        return len(last) > 1 and len(rw) > 1 and self.vocab[last] > 0 and self.vocab[rw] > 0


_REF_LABEL = re.compile(r"^\s*(\[[\w+.-]+\]|\d+\.\s)")
_SENTENCE_END = re.compile(r"[.!?:][\"')\]]*$")


class _Assembler:
    def __init__(self, targets: dict[int, str], registry: list[dict], dehyph: _Dehyphenator):
        self.targets = targets
        self.registry = registry
        self.dehyph = dehyph
        self.elements: list = []
        self.footnotes: list[Paragraph] = []
        self.heading_n = 0
        self.in_refs = False
        self.ref_labelled: bool | None = None

    # -- inline helpers ------------------------------------------------------

    def add_line(self, content: list, line: Line) -> None:
        for a in line.anchors:
            content.append(Anchor(a))
        runs = _resolve_runs(line.runs, self.targets, self.registry)
        if not runs:
            return
        prev = next((x for x in reversed(content) if isinstance(x, Run)), None)
        if prev is not None:
            ptext = prev.text.rstrip()
            nxt = runs[0].text
            if (
                ptext.endswith("-")
                and len(ptext) > 1
                and ptext[-2].isalpha()
                and nxt[:1].isalpha()
                and not self.dehyph.keep_hyphen(plain_text(content).rstrip(), nxt)
            ):
                prev.text = ptext[:-1]
            elif ptext.endswith("-") and nxt[:1].isalpha():
                prev.text = ptext
            elif not prev.text.endswith((" ", " ")) and not nxt.startswith(" "):
                prev.text += " "
        content.extend(runs)

    # -- elements ------------------------------------------------------------

    def last_paragraph(self) -> Paragraph | None:
        for e in reversed(self.elements):
            if isinstance(e, Paragraph):
                return e if e.role in ("body", "reference") else None
            if isinstance(e, Heading):
                return None
        return None

    def visual(self, v: Visual) -> None:
        self.elements.append(v)

    def front(self, blocks: list[Block]) -> None:
        """Front matter laid out in a grid (authors): one paragraph per cell."""
        rows: list[list[Block]] = []
        for b in sorted(blocks, key=lambda b: b.bbox.y0):
            if rows and b.bbox.y0 < max(x.bbox.y1 for x in rows[-1]) - 2:
                rows[-1].append(b)
            else:
                rows.append([b])
        for row in rows:
            for col in _split([_Item(b.bbox, b) for b in row], 0, 0.0):
                para = Paragraph([], "front")
                for it in sorted(col, key=lambda it: it.bbox.y0):
                    for ln in it.obj.lines:
                        self.add_line(para.content, ln)
                self.elements.append(para)

    def footnote(self, b: Block) -> None:
        para = Paragraph([], "footnote")
        for ln in b.lines:
            self.add_line(para.content, ln)
        self.footnotes.append(para)

    def flush_footnotes(self) -> None:
        self.elements.extend(self.footnotes)
        self.footnotes.clear()

    def heading(self, ln: Line, continues: bool) -> None:
        if continues and self.elements and isinstance(self.elements[-1], Heading):
            self.add_line(self.elements[-1].content, ln)
            return
        self.flush_footnotes()
        self.heading_n += 1
        h = Heading(ln.heading, [], f"sec-{self.heading_n}")
        self.add_line(h.content, ln)
        self.elements.append(h)
        key = _norm(NUMBER_PREFIX_RE.sub("", ln.text.strip()))
        self.in_refs = key in ("references", "bibliography", "literaturecited")
        self.ref_labelled = None

    def starts_reference(self, ln: Line, prev: Line | None, new_block: bool) -> bool:
        if self.ref_labelled is None:
            self.ref_labelled = bool(_REF_LABEL.match(ln.text))
        if self.ref_labelled:
            return bool(_REF_LABEL.match(ln.text))
        if prev is not None and not new_block:
            return ln.bbox.x0 < prev.bbox.x0 - 3  # hanging indent
        last = self.last_paragraph()
        text = ln.text.strip()
        return last is None or not (text[:1].islower() or not _SENTENCE_END.search(_content_text(last.content)))

    def body_block(self, block: Block) -> None:
        para: Paragraph | None = None
        prev: Line | None = None
        for ln in block.lines:
            if ln.heading:
                self.heading(ln, continues=prev is not None and prev.heading > 0)
                para, prev = None, ln
                continue
            if self.in_refs:
                if para is None or self.starts_reference(ln, prev, new_block=False):
                    if para is None and not self.starts_reference(ln, prev if prev and not prev.heading else None, new_block=prev is None):
                        para = self.last_paragraph()
                    else:
                        para = None
                    if para is None:
                        para = Paragraph([], "reference")
                        self.elements.append(para)
            else:
                new = para is None
                if para is not None and prev is not None and not _same_baseline(prev, ln):
                    indent = ln.bbox.x0 - block.bbox.x0
                    ptext = prev.text.rstrip()
                    if indent > 6 and _SENTENCE_END.search(ptext):
                        new = True
                    elif prev.bbox.x1 < block.bbox.x1 - 30 and _SENTENCE_END.search(ptext) and indent > 3:
                        new = True
                if new:
                    cand = self.last_paragraph() if para is None and prev is None else None
                    if cand is not None and cand.role == "body" and _is_continuation(cand, block):
                        para = cand
                    else:
                        para = Paragraph([], "body")
                        self.elements.append(para)
            self.add_line(para.content, ln)
            prev = ln

    def finish(self) -> list:
        self.flush_footnotes()
        return [
            e
            for e in self.elements
            if not isinstance(e, Paragraph)
            or _content_text(e.content)
            or any(isinstance(x, Anchor) for x in e.content)
        ]


def _same_baseline(a: Line, b: Line) -> bool:
    ov = min(a.bbox.y1, b.bbox.y1) - max(a.bbox.y0, b.bbox.y0)
    return ov > 0.5 * min(a.bbox.height, b.bbox.height)


def _content_text(content: list) -> str:
    return plain_text(content).strip()


def _is_continuation(prev: Paragraph, block: Block) -> bool:
    text = _content_text(prev.content)
    if not text:
        return False
    nxt = block.lines[0].text.strip()
    if not nxt:
        return False
    if text.endswith("-"):
        return True
    if nxt[0].islower() or nxt[0] in ",;)":
        return True
    # Mid-sentence break before a number, symbol or proper noun.
    return not _SENTENCE_END.search(text) and len(text.split()) >= 4


def extract(path: str, zoom: float = 4.0) -> Document:
    doc = pymupdf.open(path)
    registry: list[dict] = []
    pages = [_read_page(doc[i], i, registry) for i in range(doc.page_count)]
    stats = _stats(pages)
    _drop_page_furniture(pages)

    used_ids: set[str] = set()
    for p in pages:
        _merge_caption_continuations(p)
        p.blocks = [b for b in p.blocks if b.role != "merged"]
    convention = _caption_convention(pages, stats)
    for p in pages:
        _find_captioned(p, stats, used_ids, convention)
        _find_uncaptioned(p, stats, used_ids)
        _find_equations(p, stats, used_ids)
        for v in p.visuals:
            v.clip_page = p.number  # type: ignore[attr-defined]

    toc = doc.get_toc(simple=True)
    _mark_headings(pages, toc, stats)
    page_title = _mark_title_and_front(pages, stats)
    meta_title = ((doc.metadata or {}).get("title") or "").strip()
    # The metadata title usually has proper casing where the page has SMALL CAPS.
    title = meta_title if meta_title and (not page_title or _norm(meta_title) == _norm(page_title)) else page_title
    title = title or "Untitled"
    _mark_footnotes(pages, stats)
    targets = _resolve_targets(pages, registry, doc, used_ids)

    asm = _Assembler(targets, registry, _Dehyphenator(pages))

    # Render visuals.
    images: dict[str, bytes] = {}
    rendered: dict[str, Visual] = {}
    for p in pages:
        for v in p.visuals:
            data, ext = _render(doc, v, zoom)
            fname = f"{v.id}.{ext}"
            images[fname] = data
            caption: list = []
            if v.caption:
                for ln in v.caption.lines:
                    asm.add_line(caption, ln)
            alt = v.alt or (_content_text(caption) if caption else v.kind)
            rendered[v.id] = Visual(
                kind=v.kind,
                id=v.id,
                image=fname,
                alt=alt[:300],
                caption=caption,
                page=p.number,
                clip=(round(v.clip.x0, 2), round(v.clip.y0, 2), round(v.clip.x1, 2), round(v.clip.y1, 2)),
                width_em=round((v.clip.width + 4) / stats.body_size, 2) if v.kind == "equation" else 0.0,
            )

    # Order and assemble.
    for p in pages:
        front = [b for b in p.blocks if b.role == "front"]
        if front:
            asm.front(front)
        items = [_Item(b.bbox, b) for b in p.blocks if b.role == "body"]
        items += [_Item(v.bbox, v) for v in p.visuals]
        if items:
            xs = [it.bbox.x0 for it in items] + [it.bbox.x1 for it in items]
            for it in _xy_order(items, max(xs) - min(xs)):
                if isinstance(it.obj, VisualRegion):
                    asm.visual(rendered[it.obj.id])
                else:
                    asm.body_block(it.obj)
        for b in p.blocks:
            if b.role == "footnote":
                asm.footnote(b)

    authors_meta = (doc.metadata or {}).get("author", "")
    authors = [a.strip() for a in re.split(r",|;| and ", authors_meta) if a.strip()]
    return Document(title=title, authors=authors, elements=asm.finish(), images=images)
