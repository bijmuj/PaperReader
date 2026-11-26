"""Command line entry point: paper2epub paper.pdf [-o paper.epub]."""

from __future__ import annotations

import argparse
import pathlib
import sys

from . import arxiv
from .epub import write_epub
from .extract import extract


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Convert a research paper into a reflowable EPUB. Give a PDF file, or an arXiv id/URL "
        "to build it from arXiv's HTML (real math and tables) and the LaTeX source (full-resolution figures).",
    )
    ap.add_argument("pdf", help="paper.pdf, or an arXiv id/URL such as 2604.14191 or arxiv.org/abs/2604.14191v2")
    ap.add_argument("-o", "--output", type=pathlib.Path, help="output .epub (default: next to the PDF)")
    ap.add_argument("--zoom", type=float, default=4.0, help="render scale for vector figures (4 = 288 dpi)")
    args = ap.parse_args(argv)
    path = pathlib.Path(args.pdf)
    if path.is_file():
        out = args.output or path.with_suffix(".epub")
        doc = extract(str(path), zoom=args.zoom)
    elif (ident := arxiv.parse_id(args.pdf)) is not None:
        out = args.output or pathlib.Path(f"{ident[0].replace('/', '_')}.epub")
        doc = arxiv.convert(args.pdf)
    else:
        ap.error(f"{args.pdf}: no such file, and not an arXiv id or URL")
    info = write_epub(doc, str(out))
    print(f"{out}: {doc.title!r}, {info['chapters']} chapters, {info['images']} images, "
          f"{info['internal_link_runs']} internal links", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
