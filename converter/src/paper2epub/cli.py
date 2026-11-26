"""Command line entry point: paper2epub paper.pdf [-o paper.epub]."""

from __future__ import annotations

import argparse
import pathlib
import sys

from .epub import write_epub
from .extract import extract


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Convert a research paper PDF into a reflowable EPUB.")
    ap.add_argument("pdf", type=pathlib.Path)
    ap.add_argument("-o", "--output", type=pathlib.Path, help="output .epub (default: next to the PDF)")
    ap.add_argument("--zoom", type=float, default=4.0, help="render scale for vector figures (4 = 288 dpi)")
    args = ap.parse_args(argv)
    out = args.output or args.pdf.with_suffix(".epub")
    doc = extract(str(args.pdf), zoom=args.zoom)
    info = write_epub(doc, str(out))
    print(f"{out}: {doc.title!r}, {info['chapters']} chapters, {info['images']} images, "
          f"{info['internal_link_runs']} internal links", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
