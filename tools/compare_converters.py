"""Regression check for the PDF converters.

Converts PDFs with the desktop converter and compares the EPUBs with either the phone's
converter (--phone) or EPUBs saved earlier (--against DIR), so a heuristic change in
converter/src/paper2epub/extract.py or app/.../convert/Pdf.kt can be checked on a set of papers.

  # before changing the desktop converter, save its output; after, compare with it
  uv run --project converter python tools/compare_converters.py --save base/ papers/*.pdf
  uv run --project converter python tools/compare_converters.py --against base/ papers/*.pdf

  # desktop vs phone (debug build installed, phone connected over adb)
  uv run --project converter python tools/compare_converters.py --phone papers/*.pdf

For each paper it reports title, authors, chapters, internal links that don't resolve,
figures/tables/equations found by only one side, images whose shape differs, and the text
blocks that differ.
"""

from __future__ import annotations

import argparse
import difflib
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

from lxml import etree

ROOT = pathlib.Path(__file__).resolve().parent.parent
XHTML = "{http://www.w3.org/1999/xhtml}"
PACKAGE = "com.paperreader"


def adb_path() -> str:
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    sdk = pathlib.Path.home() / "Android/Sdk/platform-tools/adb"
    return str(sdk) if sdk.exists() else "adb"


# ---------------------------------------------------------------------------
# Converting


def convert_desktop(pdf: pathlib.Path, out: pathlib.Path) -> None:
    sys.path.insert(0, str(ROOT / "converter/src"))
    from paper2epub.epub import write_epub
    from paper2epub.extract import extract

    write_epub(extract(str(pdf)), str(out))


def convert_phone(pdf: pathlib.Path, out: pathlib.Path) -> None:
    adb = adb_path()
    name = re.sub(r"[^A-Za-z0-9_-]", "_", pdf.stem)
    run_as = [adb, "shell", "run-as", PACKAGE]
    subprocess.run(run_as + ["mkdir", "-p", "files/debug"], check=True)
    with open(pdf, "rb") as f:
        subprocess.run([adb, "exec-in", "run-as", PACKAGE, "sh", "-c", f"cat > files/debug/{name}.pdf"], stdin=f, check=True)
    subprocess.run([adb, "logcat", "-c"], check=True)
    subprocess.run(
        [adb, "shell", "am", "broadcast", "-n", f"{PACKAGE}/.debug.ConvertReceiver", "--es", "name", name],
        check=True, capture_output=True,
    )
    deadline = time.time() + 180
    while True:
        log = subprocess.run([adb, "logcat", "-d", "-s", "PdfDebug:I"], capture_output=True, text=True).stdout
        if f"{name}: done" in log:
            break
        if f"{name}: failed" in log:
            raise RuntimeError(f"phone conversion failed; see adb logcat -s PdfDebug")
        if time.time() > deadline:
            raise TimeoutError("phone conversion timed out")
        time.sleep(0.5)
    with open(out, "wb") as f:
        subprocess.run([adb, "exec-out", "run-as", PACKAGE, "cat", f"files/debug/{name}.epub"], stdout=f, check=True)
    subprocess.run(run_as + ["rm", "-f", f"files/debug/{name}.pdf", f"files/debug/{name}.epub"], check=True)


# ---------------------------------------------------------------------------
# Reading an EPUB


def load(path: pathlib.Path) -> dict:
    z = zipfile.ZipFile(path)
    opf_name = next(n for n in z.namelist() if n.endswith(".opf"))
    opf = etree.fromstring(z.read(opf_name))
    base = opf_name.rsplit("/", 1)[0] + "/" if "/" in opf_name else ""
    manifest = {i.get("id"): i.get("href") for i in opf.iter("{*}item")}
    spine = [manifest[i.get("idref")] for i in opf.iter("{*}itemref")]
    docs = {ch: etree.fromstring(z.read(base + ch)) for ch in spine}

    ids = {e.get("id"): ch for ch, t in docs.items() for e in t.iter() if isinstance(e.tag, str) and e.get("id")}
    elements, links, broken = [], 0, []
    for ch, t in docs.items():
        for e in t.find(XHTML + "body"):
            tag = etree.QName(e).localname
            text = re.sub(r"\s+", " ", "".join(e.itertext())).strip()
            if tag == "figure":
                img = e.find(f".//{XHTML}img")
                size = (int(img.get("width") or 0), int(img.get("height") or 0)) if img is not None else (0, 0)
                elements.append(("figure", e.get("class"), e.get("id"), size))
            else:
                cls = e.get("class")
                page = e.get("data-pdf-page")
                key = (f"{tag}.{cls}" if cls else tag) + (f"@p{page}" if page is not None else "")
                elements.append(("text", key, text))
        for a in t.iter(XHTML + "a"):
            href = a.get("href")
            if a.get("data-missing-target") is not None:
                links += 1
                broken.append("(missing target)")
            elif href and not re.match(r"[a-z]+:", href) and "#" in href:
                links += 1
                f, frag = href.split("#", 1)
                if ids.get(frag) != (f or ch):
                    broken.append(href)
    return {
        "title": opf.findtext(".//{http://purl.org/dc/elements/1.1/}title"),
        "authors": [c.text for c in opf.iter("{http://purl.org/dc/elements/1.1/}creator")],
        "chapters": len(spine),
        "elements": elements,
        "links": links,
        "broken": broken,
    }


# ---------------------------------------------------------------------------
# Comparing


def compare(a: dict, b: dict, label_a: str, label_b: str, hunks: int) -> bool:
    """Prints the differences; returns True when the text is identical."""
    for key in ("title", "authors", "chapters"):
        if a[key] != b[key]:
            print(f"  {key}: {label_a} {a[key]!r}, {label_b} {b[key]!r}")
    for label, x in ((label_a, a), (label_b, b)):
        if x["broken"]:
            print(f"  {label}: {len(x['broken'])} of {x['links']} internal links broken, e.g. {x['broken'][:3]}")
    print(f"  links: {a['links']} / {b['links']}")

    fa = {e[2]: e for e in a["elements"] if e[0] == "figure"}
    fb = {e[2]: e for e in b["elements"] if e[0] == "figure"}
    print(f"  visuals: {len(fa)} / {len(fb)}")
    if only := [i for i in fa if i not in fb]:
        print(f"    only {label_a}: {only}")
    if only := [i for i in fb if i not in fa]:
        print(f"    only {label_b}: {only}")
    for i in fa.keys() & fb.keys():
        (w1, h1), (w2, h2) = fa[i][3], fb[i][3]
        if h1 and h2 and abs(w1 / h1 - w2 / h2) > 0.03 * w1 / h1:
            print(f"    shape differs: {i} {w1}x{h1} / {w2}x{h2}")

    ta = [f"{e[1]}| {e[2]}" for e in a["elements"] if e[0] == "text"]
    tb = [f"{e[1]}| {e[2]}" for e in b["elements"] if e[0] == "text"]
    sm = difflib.SequenceMatcher(None, ta, tb, autojunk=False)
    ops = [op for op in sm.get_opcodes() if op[0] != "equal"]
    print(f"  text blocks: {len(ta)} / {len(tb)}, {len(ops)} differing runs")
    for op, i1, i2, j1, j2 in ops[:hunks]:
        for x in ta[i1:i2][:3]:
            print(f"    - {x[:150]}")
        for x in tb[j1:j2][:3]:
            print(f"    + {x[:150]}")
        print()
    return not ops


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("pdfs", nargs="+", type=pathlib.Path)
    ap.add_argument("--phone", action="store_true", help="compare the desktop output with the phone's")
    ap.add_argument("--against", type=pathlib.Path, help="compare with EPUBs saved in this folder")
    ap.add_argument("--save", type=pathlib.Path, help="save the desktop EPUBs in this folder")
    ap.add_argument("--hunks", type=int, default=5, help="differing text runs to show per paper")
    args = ap.parse_args()
    if not (args.phone or args.against or args.save):
        ap.error("give --phone, --against DIR or --save DIR")

    work = pathlib.Path(tempfile.mkdtemp(prefix="paper2epub-cmp-"))
    same = 0
    try:
        for pdf in args.pdfs:
            print(f"=== {pdf.name}")
            desk = work / f"{pdf.stem}.desktop.epub"
            convert_desktop(pdf, desk)
            if args.save:
                args.save.mkdir(parents=True, exist_ok=True)
                shutil.copy(desk, args.save / f"{pdf.stem}.epub")
            other = label = None
            if args.phone:
                other, label = work / f"{pdf.stem}.phone.epub", "phone"
                convert_phone(pdf, other)
            elif args.against:
                other, label = args.against / f"{pdf.stem}.epub", "saved"
                if not other.exists():
                    print(f"  no {other}")
                    continue
            if other is not None:
                same += compare(load(desk), load(other), "desktop", label, args.hunks)
        if args.phone or args.against:
            print(f"\n{same} of {len(args.pdfs)} papers have identical text")
    finally:
        shutil.rmtree(work)


if __name__ == "__main__":
    main()
