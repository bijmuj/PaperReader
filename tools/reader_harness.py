"""Runs the app's reader.js in headless Chromium against an EPUB, without a phone.

Serves the EPUB like the app's WebViewAssetLoader does, replaces the Android
bridge with a recorder, drives the page (links, jumps, back, restore) and
prints the checks as JSON.

Usage: python3 tools/reader_harness.py book.epub
"""
import http.server
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import threading
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/reader"

MOCK_AND_TESTS = r"""
<script>
window.calls = [];
window.PaperReader = new Proxy({}, { get: (_, name) => (...args) => window.calls.push([name, ...args]) });
</script>
<script defer>
(async function () {
  const wait = (ms) => new Promise(r => setTimeout(r, ms));
  const results = {};
  for (let i = 0; i < 150 && !window.prReady; i++) await wait(100);
  results.ready = !!window.prReady;
  const strip = document.getElementById('pr-strip');
  const sections = [...document.querySelectorAll('.pr-chapter')];
  const W = strip.clientWidth;
  results.chapters = sections.length;
  results.debug = { W, scrollWidth: strip.scrollWidth, scrollLeft: strip.scrollLeft, widths: sections.slice(0, 3).map(s => [s.offsetWidth, s.clientWidth, s.scrollHeight, s.clientHeight]), bodyW: document.body.clientWidth, stripStyle: getComputedStyle(strip).display + '/' + getComputedStyle(strip).overflowX };
  results.columnsFullWidth = sections.every(s => Math.abs(s.offsetWidth - W) <= 1);
  results.restoredChapter = Math.round(strip.scrollLeft / W);
  const restore = sections[CONFIG_RESTORE_CHAPTER];
  results.restoredFraction = +(restore.scrollTop / (restore.scrollHeight - restore.clientHeight)).toFixed(3);
  const links = [...document.querySelectorAll('a[href^="#"]')];
  results.internalLinks = links.length;
  results.brokenLinks = links.filter(a => !document.getElementById(a.getAttribute('href').slice(1))).map(a => a.getAttribute('href')).slice(0, 10);
  results.idsUnique = new Set([...document.querySelectorAll('[id]')].map(e => e.id)).size === document.querySelectorAll('[id]').length;
  results.imagesLoaded = [...document.images].every(i => i.complete && i.naturalWidth > 0);

  calls.length = 0;
  document.querySelector('a.noteref')?.click();
  results.noteref = calls.filter(c => c[0] === 'onNoteref').map(c => [c[1], c[2].slice(0, 60)]);

  calls.length = 0;
  const figLink = links.find(a => /-(fig|tab)-/.test(a.getAttribute('href')));
  figLink?.click();
  results.figureRef = calls.filter(c => c[0] === 'onFigureRef').map(c => [c[1], c[2].split('/').pop(), c[3].slice(0, 40)]);

  // A link to a section heading should jump (switch column) and be undoable.
  strip.scrollLeft = 0; await wait(300);
  const secLink = links.find(a => {
    const t = document.getElementById(a.getAttribute('href').slice(1));
    return t && !t.closest('figure') && !t.closest('p.reference, p.footnote') && !a.classList.contains('noteref')
      && t.closest('.pr-chapter') !== a.closest('.pr-chapter');
  });
  if (secLink) {
    const from = +secLink.closest('.pr-chapter').dataset.index;
    strip.scrollLeft = from * W; await wait(300);
    secLink.click(); await wait(300);
    const target = document.getElementById(secLink.getAttribute('href').slice(1));
    const to = +target.closest('.pr-chapter').dataset.index;
    const r = target.getBoundingClientRect();
    results.crossChapterJump = { from, to, landedOn: Math.round(strip.scrollLeft / W), targetVisible: r.top >= 0 && r.top < innerHeight };
    results.historyAfterJump = prHistoryLength();
    results.backHandled = prBack(); await wait(300);
    results.backReturnedTo = Math.round(strip.scrollLeft / W);
    results.backEmpty = prBack();
  }

  // Table of contents jump by book URL.
  prGo(CONFIG_TOC_URL); await wait(300);
  results.tocJumpChapter = Math.round(strip.scrollLeft / W);

  // Scrolling a column reports per-chapter progress.
  calls.length = 0;
  sections[1].scrollTop = 200; sections[1].dispatchEvent(new Event('scroll')); await wait(700);
  results.progressCalls = calls.filter(c => c[0] === 'onProgress').map(c => [c[1], +c[2].toFixed(2)]);

  document.title = 'RESULT:' + JSON.stringify(results);
})();
</script>
"""


def main(epub_path: str) -> None:
    work = pathlib.Path(tempfile.mkdtemp(prefix="reader-harness-"))
    book = work / "book"
    zipfile.ZipFile(epub_path).extractall(book)
    opf_rel = re.search(r'full-path="([^"]+)"', (book / "META-INF/container.xml").read_text()).group(1)
    opf = (book / opf_rel).read_text()
    base = opf_rel.rsplit("/", 1)[0] + "/" if "/" in opf_rel else ""
    items = dict(re.findall(r'<item id="([^"]+)" href="([^"]+)"', opf))
    spine = [base + items[i] for i in re.findall(r'<itemref idref="([^"]+)"', opf)]

    port_holder = {}

    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            origin = f"http://127.0.0.1:{port_holder['port']}"
            path = self.path.split("?")[0]
            if path == "/shell":
                restore_ch = min(2, len(spine) - 1)
                cfg = {
                    "chapters": [{"url": f"{origin}/book/{h}", "title": f"Chapter {i}"} for i, h in enumerate(spine)],
                    "fractions": {str(restore_ch): 0.5},
                    "current": restore_ch,
                }
                tests = (MOCK_AND_TESTS.replace("CONFIG_RESTORE_CHAPTER", str(restore_ch))
                         .replace("CONFIG_TOC_URL", json.dumps(f"{origin}/book/{spine[-1]}")))
                attr = json.dumps(cfg).replace("&", "&amp;").replace('"', "&quot;")
                html = (f'<!DOCTYPE html><html><head><meta charset="utf-8"/>'
                        f'<meta name="viewport" content="width=device-width, initial-scale=1"/>'
                        f'<meta name="pr-config" content="{attr}"/>'
                        f'<link id="pr-base-css" rel="stylesheet" href="/assets/reader/reader.css"/>'
                        f'<link id="pr-theme" rel="stylesheet" href="/assets/reader/theme-light.css"/>'
                        f'{tests}<script src="/assets/reader/reader.js" defer></script>'
                        f'</head><body><div id="pr-strip"></div></body></html>')
                return self._send(html.encode(), "text/html")
            if path.startswith("/assets/reader/"):
                f = ASSETS / path.removeprefix("/assets/reader/")
            elif path.startswith("/book/"):
                f = book / path.removeprefix("/book/")
            else:
                f = None
            if not f or not f.is_file():
                self.send_error(404)
                return
            mime = {".xhtml": "application/xhtml+xml", ".css": "text/css", ".js": "text/javascript",
                    ".png": "image/png", ".jpg": "image/jpeg"}.get(f.suffix, "application/octet-stream")
            self._send(f.read_bytes(), mime)

        def _send(self, data, mime):
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port_holder["port"] = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        out = subprocess.run(
            ["chromium-browser", "--headless", "--enable-logging=stderr", "--v=0", "--disable-gpu", "--no-sandbox", f"--user-data-dir={work / 'profile'}",
             "--window-size=412,915", "--virtual-time-budget=20000", "--dump-dom",
             f"http://127.0.0.1:{port_holder['port']}/shell"],
            capture_output=True, text=True, timeout=120,
        )
        for line in out.stderr.splitlines():
            if "CONSOLE" in line:
                print(line[line.index("CONSOLE"):][:400], file=sys.stderr)
        out = out.stdout
    finally:
        server.shutdown()
    m = re.search(r"<title>RESULT:(.*?)</title>", out, re.S)
    if not m:
        print("no result; page title:", re.search(r"<title>(.*?)</title>", out, re.S))
        sys.exit(1)
    print(json.dumps(json.loads(m.group(1).replace("&quot;", '"').replace("&amp;", "&")), indent=1))


if __name__ == "__main__":
    main(sys.argv[1])
