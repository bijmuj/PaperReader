# paper2epub

Converts research-paper PDFs into reflowable EPUB 3 files for reading on a phone.

```sh
uv run paper2epub paper.pdf            # writes paper.epub next to it
uv run paper2epub paper.pdf -o out.epub --zoom 4
uv run paper2epub 2604.14191           # arXiv id or URL: writes 2604.14191.epub
uv run python tools/check_epub.py out.epub --text   # link/XML check + text dump
```

## arXiv papers

Given an arXiv id or URL (`2604.14191`, `2604.14191v2`, `arxiv.org/abs/...`,
`arxiv.org/pdf/...`), the EPUB is built from arXiv's HTML rendering
(LaTeXML) instead of the PDF. This gives exact text, real MathML equations
(typeset by the reader, scale with the text size, follow the theme), HTML
tables, and LaTeX's own cross-references, citations and footnotes.

- If the requested version has no HTML (arXiv's conversion sometimes fails),
  the nearest version that has one is used, with a warning.
- Figures are taken from the LaTeX source: vector PDFs are rendered at
  ~3000 px (applying any `\includegraphics[trim=...,clip]`), rasters are
  copied unchanged. When a figure cannot be matched, arXiv's own image
  (often SVG) is used.
- Title and authors come from the arXiv metadata.
- Downloads are cached in `~/.cache/paper2epub/arxiv/`.

What it does:

- **Text**: reading order via XY-cut (one- and two-column layouts), paragraphs
  rebuilt across columns/pages, line-break hyphens resolved against the
  paper's own vocabulary, bold/italic/sub/superscript kept, running
  headers/footers and margin stamps removed. Footnotes go at the end of
  their section.
- **Headings** from the PDF outline when it has one, otherwise from numbering
  and font heuristics. Level-1 sections become separate chapters; the EPUB
  table of contents is nested.
- **Figures / tables** are found from their captions plus nearby graphics.
  A figure that is a single embedded raster is copied out unchanged (original
  resolution and format). Composite or vector figures, tables and display
  equations are rendered at `--zoom` (default 4×, ≈288 dpi), or higher when
  they contain rasters, so embedded images keep their native resolution.
- **Internal links** (citations, figure/table/equation/section refs,
  footnotes) are resolved from the PDF's link targets to the exact line or
  figure they land on. Citation links are marked `epub:type="noteref"` so
  readers that support it show them as pop-ups.
- Every `<figure>` carries `data-pdf-page` and `data-pdf-clip` (points, top-left
  origin), and paragraphs and headings carry `data-pdf-page` (0-based), so the
  app can show where something is in the original PDF.

Known gaps: tables are images, not HTML tables; inline math is plain text
(display equations are images); figure detection is heuristic.

## Checking changes

The Android app has a Kotlin port of the PDF converter (`app/src/main/kotlin/com/paperreader/convert/Pdf.kt`)
that follows `extract.py` heuristic for heuristic, so change both together. From the repository root:

```sh
# save the desktop output, change extract.py, then see what changed
uv run --project converter python tools/compare_converters.py --save base/ papers/*.pdf
uv run --project converter python tools/compare_converters.py --against base/ papers/*.pdf

# desktop vs phone (debug build installed, phone connected over adb)
uv run --project converter python tools/compare_converters.py --phone papers/*.pdf
```

`tools/reader_harness.py book.epub` runs the app's reader script (links, highlights, search) in headless Chromium.
