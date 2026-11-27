// Builds the whole book into one page: each spine document becomes a
// horizontally-snapping column that scrolls vertically on its own.
// Talks to the app through window.PaperReader.
(function () {
  'use strict';
  var bridge = window.PaperReader || null;
  var cfg = JSON.parse(document.querySelector('meta[name="pr-config"]').getAttribute('content'));
  var strip = document.getElementById('pr-strip');
  var sections = [];
  var chapterOf = {}; // URL path -> chapter index
  var history = [];   // positions to return to with Back
  var current = 0;
  var ready = false;

  cfg.chapters.forEach(function (c, i) { chapterOf[new URL(c.url).pathname] = i; });

  window.prSetTheme = function (name) {
    var link = document.getElementById('pr-theme');
    if (link) link.setAttribute('href', link.getAttribute('href').replace(/theme-\w+\.css/, 'theme-' + name + '.css'));
  };

  // ---- building ---------------------------------------------------------

  function parse(text) {
    var doc = new DOMParser().parseFromString(text, 'application/xhtml+xml');
    if (doc.getElementsByTagName('parsererror').length) doc = new DOMParser().parseFromString(text, 'text/html');
    return doc;
  }

  // Ids are prefixed per chapter (c3-fig-1) so chapters cannot clash.
  function localId(chapter, id) { return 'c' + chapter + '-' + id; }

  function rewriteLink(href, base, chapter) {
    var url;
    try { url = new URL(href, base); } catch (e) { return href; }
    if (url.origin !== location.origin) return url.href;
    var target = chapterOf[url.pathname];
    if (target === undefined) return url.href;
    var frag = decodeURIComponent(url.hash.slice(1));
    return '#' + (frag ? localId(target, frag) : 'c' + target);
  }

  function build(docs) {
    var head = document.head;
    var ours = document.getElementById('pr-base-css');
    var seenCss = {};
    docs.forEach(function (doc, i) {
      var base = cfg.chapters[i].url;
      Array.prototype.forEach.call(doc.querySelectorAll('link[rel~="stylesheet"][href]'), function (l) {
        var href = new URL(l.getAttribute('href'), base).href;
        if (seenCss[href]) return;
        seenCss[href] = true;
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = href;
        head.insertBefore(link, ours); // book styles first, ours override
      });
      var section = document.createElement('section');
      section.className = 'pr-chapter';
      section.id = 'c' + i;
      section.dataset.index = i;
      var page = document.createElement('div');
      page.className = 'pr-page';
      var body = doc.body || doc.documentElement;
      Array.prototype.forEach.call(body.childNodes, function (n) { page.appendChild(document.importNode(n, true)); });
      Array.prototype.forEach.call(page.querySelectorAll('[id]'), function (el) { el.id = localId(i, el.id); });
      Array.prototype.forEach.call(page.querySelectorAll('a[href]'), function (a) {
        a.setAttribute('href', rewriteLink(a.getAttribute('href'), base, i));
      });
      Array.prototype.forEach.call(page.querySelectorAll('img[src]'), function (img) {
        img.setAttribute('src', new URL(img.getAttribute('src'), base).href);
      });
      if (i + 1 < cfg.chapters.length) {
        var next = document.createElement('div');
        next.className = 'pr-next';
        var small = document.createElement('small');
        small.textContent = 'Swipe for next →';
        next.appendChild(small);
        next.appendChild(document.createTextNode(cfg.chapters[i + 1].title || ''));
        page.appendChild(next);
      }
      section.appendChild(page);
      section.addEventListener('scroll', onSectionScroll, { passive: true });
      sections.push(section);
    });
    strip.textContent = '';
    sections.forEach(function (s) { strip.appendChild(s); });
  }

  function loadAll() {
    return Promise.all(cfg.chapters.map(function (c) {
      return fetch(c.url).then(function (r) { return r.text(); }).then(parse);
    }));
  }

  // Positions are restored once images have their final size; never wait more than 3 s.
  function imagesReady() {
    var imgs = Array.prototype.slice.call(document.images);
    var all = Promise.all(imgs.map(function (img) {
      if (img.complete) return null;
      return new Promise(function (resolve) {
        img.addEventListener('load', resolve, { once: true });
        img.addEventListener('error', resolve, { once: true });
      });
    }));
    return Promise.race([all, new Promise(function (r) { setTimeout(r, 3000); })]);
  }

  // ---- position ---------------------------------------------------------

  function width() { return strip.clientWidth || window.innerWidth; }

  function fractionOf(section) {
    var m = section.scrollHeight - section.clientHeight;
    return m > 0 ? section.scrollTop / m : 0;
  }

  function setFraction(section, f) {
    section.scrollTop = f * Math.max(0, section.scrollHeight - section.clientHeight);
  }

  function visibleChapter() {
    return Math.max(0, Math.min(sections.length - 1, Math.round(strip.scrollLeft / width())));
  }

  function showChapter(i) {
    strip.scrollLeft = i * width();
    setCurrent(i);
  }

  function setCurrent(i) {
    if (i === current && ready) return;
    current = i;
    if (bridge) bridge.onChapter(i);
  }

  var stripTimer = null;
  strip.addEventListener('scroll', function () {
    clearTimeout(stripTimer);
    stripTimer = setTimeout(function () { setCurrent(visibleChapter()); }, 120);
  }, { passive: true });

  var progressTimers = {};
  function onSectionScroll(e) {
    var s = e.currentTarget;
    var i = +s.dataset.index;
    clearTimeout(progressTimers[i]);
    progressTimers[i] = setTimeout(function () {
      if (bridge && ready) bridge.onProgress(i, fractionOf(s));
    }, 400);
  }

  // Keep the current chapter aligned when the width changes (rotation, text size).
  window.addEventListener('resize', function () { if (ready) strip.scrollLeft = current * width(); });
  window.prHistoryLength = function () { return history.length; };

  // ---- navigation -------------------------------------------------------

  function flash(el) {
    var block = el.closest('p, figure, h1, h2, h3, h4, h5, h6, li') || el;
    block.classList.remove('pr-flash');
    void block.offsetWidth;
    block.classList.add('pr-flash');
  }

  function jumpTo(el, remember) {
    var section = el.closest('.pr-chapter');
    if (!section) return;
    if (remember) {
      var here = visibleChapter();
      history.push({ chapter: here, top: sections[here].scrollTop });
    }
    showChapter(+section.dataset.index);
    if (el !== section) {
      var top = el.getBoundingClientRect().top - section.getBoundingClientRect().top + section.scrollTop;
      section.scrollTop = Math.max(0, top - 16);
      flash(el);
    }
  }

  window.prBack = function () {
    var p = history.pop();
    if (!p) return false;
    showChapter(p.chapter);
    sections[p.chapter].scrollTop = p.top;
    return true;
  };

  // Jump to a local id (from the app's popups: "Go to reference").
  window.prJump = function (id) {
    var el = document.getElementById(id);
    if (el) jumpTo(el, true);
  };

  // Jump to a book URL (from the table of contents).
  window.prGo = function (href) {
    var target = rewriteLink(href, location.href, current);
    if (target.charAt(0) !== '#') return;
    var el = document.getElementById(target.slice(1));
    if (el) jumpTo(el, true);
  };

  function textOf(el) { return (el.textContent || '').replace(/\s+/g, ' ').trim(); }

  document.addEventListener('click', function (e) {
    var a = e.target.closest('a[href]');
    if (a) {
      var href = a.getAttribute('href');
      if (href.charAt(0) !== '#') return; // external: the app opens a browser
      e.preventDefault();
      var id = href.slice(1);
      var target = document.getElementById(id);
      if (!target) return;
      var fig = target.closest('figure');
      if (fig && bridge && fig.querySelector('img')) {
        var img = fig.querySelector('img');
        var cap = fig.querySelector('figcaption');
        bridge.onFigureRef(fig.id || id, img ? img.src : '', cap ? textOf(cap) : '');
        return;
      }
      var p = target.closest('p');
      if (p && bridge && (a.classList.contains('noteref') || p.classList.contains('reference') || p.classList.contains('footnote'))) {
        bridge.onNoteref(id, textOf(p));
        return;
      }
      jumpTo(target, true);
      return;
    }
    var mark = e.target.closest('mark.pr-hl');
    if (mark && bridge && window.getSelection().isCollapsed) {
      e.preventDefault();
      bridge.onHighlight(+mark.dataset.hl);
      return;
    }
    var image = e.target.closest('img');
    if (image && bridge) {
      e.preventDefault();
      bridge.onImage(image.src);
    }
  });

  // ---- highlights -----------------------------------------------------------
  //
  // A highlight is anchored by character offsets into the visible text of its chapter
  // (MathML's TeX annotations and our own "next chapter" box excluded), plus the quoted
  // text and some context so it can be found again if the text has moved.

  var SKIP = 'annotation, annotation-xml, script, style, .pr-next';
  var highlights = {};

  function textIndex(section) {
    var nodes = [];
    var pos = 0;
    var walker = document.createTreeWalker(section, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        return n.parentElement && n.parentElement.closest(SKIP) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    });
    var n;
    while ((n = walker.nextNode())) {
      nodes.push({ node: n, start: pos });
      pos += n.data.length;
    }
    return { nodes: nodes, length: pos, text: nodes.map(function (x) { return x.node.data; }).join('') };
  }

  function pointOffset(idx, container, offset) {
    if (container.nodeType === 3) {
      for (var i = 0; i < idx.nodes.length; i++) {
        if (idx.nodes[i].node === container) return idx.nodes[i].start + offset;
      }
    }
    var r = document.createRange();
    r.setStart(container, offset);
    r.collapse(true);
    for (var j = 0; j < idx.nodes.length; j++) {
      if (r.comparePoint(idx.nodes[j].node, 0) >= 0) return idx.nodes[j].start;
    }
    return idx.length;
  }

  function outerMath(node) {
    var el = node.nodeType === 1 ? node : node.parentElement;
    var math = el && el.closest('math');
    while (math && math.parentElement && math.parentElement.closest('math')) math = math.parentElement.closest('math');
    return math;
  }

  function chapterOfNode(node) {
    var el = node.nodeType === 1 ? node : node.parentElement;
    return el ? el.closest('.pr-chapter') : null;
  }

  function captureSelection() {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return null;
    var range = sel.getRangeAt(0).cloneRange();
    // Selections inside formulas are fiddly (each symbol is its own piece) and highlights cover
    // whole formulas anyway, so a selection touching a formula takes in all of it.
    var startMath = outerMath(range.startContainer);
    var endMath = outerMath(range.endContainer);
    if (startMath) range.setStartBefore(startMath);
    if (endMath) range.setEndAfter(endMath);
    var section = chapterOfNode(range.startContainer);
    if (!section) return null;
    var idx = textIndex(section);
    var start = pointOffset(idx, range.startContainer, range.startOffset);
    var end = chapterOfNode(range.endContainer) === section ? pointOffset(idx, range.endContainer, range.endOffset) : idx.length;
    while (start < end && /\s/.test(idx.text.charAt(start))) start++;
    while (end > start && /\s/.test(idx.text.charAt(end - 1))) end--;
    if (end <= start) return null;
    return {
      chapter: +section.dataset.index,
      start: start,
      end: end,
      quote: idx.text.slice(start, end),
      prefix: idx.text.slice(Math.max(0, start - 40), start),
      suffix: idx.text.slice(end, end + 40)
    };
  }

  var lastSelection = null;
  var selectionTimer = null;
  document.addEventListener('selectionchange', function () {
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(function () {
      var s = captureSelection();
      if (s) lastSelection = s;
    }, 60);
  });

  // Called by the app's "Highlight"/"Note" selection actions.
  window.prTakeSelection = function () {
    var s = captureSelection() || lastSelection;
    lastSelection = null;
    var sel = window.getSelection();
    if (sel) sel.removeAllRanges();
    return s;
  };

  // Where [h.quote] is now, preferring matching context and the old position; -1 if gone.
  function relocate(text, h) {
    var best = -1;
    var bestScore = -Infinity;
    for (var i = text.indexOf(h.quote); i >= 0; i = text.indexOf(h.quote, i + 1)) {
      var score = 0;
      var before = text.slice(Math.max(0, i - h.prefix.length), i);
      var after = text.slice(i + h.quote.length, i + h.quote.length + h.suffix.length);
      for (var a = 1; a <= Math.min(before.length, h.prefix.length) && before.charAt(before.length - a) === h.prefix.charAt(h.prefix.length - a); a++) score++;
      for (var b = 0; b < Math.min(after.length, h.suffix.length) && after.charAt(b) === h.suffix.charAt(b); b++) score++;
      score -= Math.abs(i - h.start) / 10000;
      if (score > bestScore) {
        bestScore = score;
        best = i;
      }
    }
    return best;
  }

  function markClass(h) {
    return 'pr-hl pr-hl-' + h.color + (h.note ? ' pr-hl-note' : '');
  }

  function applyHighlight(h) {
    var section = sections[h.chapter];
    if (!section || !h.quote) return false;
    var idx = textIndex(section);
    var start = h.start;
    var end = h.end;
    if (idx.text.slice(start, end) !== h.quote) {
      start = relocate(idx.text, h);
      if (start < 0) return false;
      end = start + h.quote.length;
    }
    var maths = [];
    idx.nodes.forEach(function (n) {
      var s = n.start;
      var e = s + n.node.data.length;
      if (e <= start || s >= end) return;
      var node = n.node;
      var math = node.parentElement && node.parentElement.closest('math');
      if (math) { // marks can't go inside MathML: mark the whole formula
        if (maths.indexOf(math) < 0) maths.push(math);
        return;
      }
      if (!/\S/.test(node.data) && /^(table|tbody|thead|tfoot|tr|ul|ol|dl)$/i.test(node.parentNode.nodeName)) return;
      var a = Math.max(start, s) - s;
      var b = Math.min(end, e) - s;
      if (b < node.data.length) node.splitText(b);
      if (a > 0) node = node.splitText(a);
      var mark = document.createElement('mark');
      mark.className = markClass(h);
      mark.dataset.hl = h.id;
      node.parentNode.insertBefore(mark, node);
      mark.appendChild(node);
    });
    maths.forEach(function (math) {
      var mark = document.createElement('mark');
      // Display equations are blocks: an inline mark would paint nothing behind them.
      var block = math.getAttribute('display') === 'block' || getComputedStyle(math).display === 'block';
      mark.className = markClass(h) + (block ? ' pr-hl-block' : '');
      mark.dataset.hl = h.id;
      math.parentNode.insertBefore(mark, math);
      mark.appendChild(math);
    });
    highlights[h.id] = h;
    return true;
  }

  function marksOf(id) {
    return document.querySelectorAll('mark.pr-hl[data-hl="' + id + '"]');
  }

  window.prAddHighlight = function (h) { return applyHighlight(h); };

  window.prUpdateHighlight = function (id, color, hasNote) {
    var h = highlights[id];
    if (h) {
      h.color = color;
      h.note = hasNote;
    }
    marksOf(id).forEach(function (m) {
      m.className = markClass({ color: color, note: hasNote }) + (m.classList.contains('pr-hl-block') ? ' pr-hl-block' : '');
    });
  };

  window.prRemoveHighlight = function (id) {
    marksOf(id).forEach(function (m) {
      var parent = m.parentNode;
      while (m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
      parent.normalize();
    });
    delete highlights[id];
  };

  window.prShowHighlight = function (id) {
    var m = marksOf(id)[0];
    if (m) jumpTo(m, true);
    return !!m;
  };

  // ---- start --------------------------------------------------------------

  loadAll().then(function (docs) {
    build(docs);
    var lost = (cfg.highlights || []).filter(function (h) { return !applyHighlight(h); }).map(function (h) { return h.id; });
    if (lost.length && bridge) bridge.onLostHighlights(JSON.stringify(lost));
    return imagesReady();
  }).then(function () {
    sections.forEach(function (s, i) {
      var f = cfg.fractions[i];
      if (f > 0) setFraction(s, f);
    });
    showChapter(Math.min(cfg.current || 0, sections.length - 1));
    ready = true;
    window.prReady = true;
    if (bridge) bridge.onChapter(current);
    if (cfg.target) window.prGo(cfg.target);
  }).catch(function (err) {
    console.error('reader failed: ' + err + '\n' + (err && err.stack));
    strip.textContent = 'Could not open this book: ' + err;
  });
})();
