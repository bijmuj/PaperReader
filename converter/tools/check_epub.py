"""Sanity-checks a generated EPUB: XML well-formedness, internal links, images.

Usage: python tools/check_epub.py book.epub [--text]
"""
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

import pymupdf

NS = {"x": "http://www.w3.org/1999/xhtml"}


def main(path: str, show_text: bool) -> int:
    z = zipfile.ZipFile(path)
    names = z.namelist()
    assert names[0] == "mimetype", "mimetype must be first"
    problems = []
    ids: dict[str, set] = {}
    hrefs = []
    docs = sorted(n for n in names if n.endswith(".xhtml"))
    for n in docs:
        try:
            root = ET.fromstring(z.read(n))
        except ET.ParseError as e:
            problems.append(f"{n}: not well-formed: {e}")
            continue
        fname = n.split("/")[-1]
        ids[fname] = {el.get("id") for el in root.iter() if el.get("id")}
        for a in root.iter("{http://www.w3.org/1999/xhtml}a"):
            hrefs.append((fname, a.get("href", ""), "".join(a.itertext())))
    internal = [h for h in hrefs if not re.match(r"^[a-z]+:", h[1])]
    for src, href, text in internal:
        f, _, frag = href.partition("#")
        f = f or src
        if f not in ids or (frag and frag not in ids[f]):
            problems.append(f"{src}: broken link {href!r} ({text!r})")
    print(f"{len(docs)} xhtml files, {len(internal)} internal links, {len(hrefs) - len(internal)} external")
    for n in names:
        if n.startswith("OEBPS/images/"):
            pix = pymupdf.Pixmap(z.read(n))
            print(f"  {n.split('/')[-1]:14s} {pix.width}x{pix.height}  {len(z.read(n)) // 1024} KiB")
    for p in problems:
        print("PROBLEM:", p)
    if show_text:
        for n in docs:
            print(f"\n===== {n}")
            body = z.read(n).decode()
            body = re.sub(r"<img [^>]*alt=\"([^\"]*)\"[^>]*/>", r"[IMG: \1]", body)
            body = re.sub(r"<a [^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", r"\2→{\1}", body)
            body = re.sub(r"<(h\d)[^>]*>", r"\n## ", body)
            body = re.sub(r"<p class=\"(\w+)\">", r"\n[\1] ", body)
            body = re.sub(r"<(p|figure|figcaption)[^>]*>", "\n", body)
            body = re.sub(r"<span id=\"([^\"]*)\"></span>", r"⚓\1 ", body)
            body = re.sub(r"<head>.*?</head>", "", body, flags=re.S)
            body = re.sub(r"<(sup|sub)>(.*?)</\1>", lambda m: ("^" if m[1] == "sup" else "_") + "{" + m[2] + "}", body)
            print(re.sub(r"<[^>]+>", "", body).strip())
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], "--text" in sys.argv))
