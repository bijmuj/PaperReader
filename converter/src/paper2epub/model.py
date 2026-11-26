"""Intermediate document model between PDF extraction and EPUB writing.

Kept deliberately small and format-agnostic so the same model can be ported
to Kotlin for the on-device converter.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Run:
    """A run of text sharing one style and (optionally) one link."""

    text: str
    bold: bool = False
    italic: bool = False
    mono: bool = False
    vpos: str = ""  # "sup", "sub" or ""
    # "#<id>" for internal targets, a URL for external links, None otherwise.
    # During extraction internal links are "@<n>" placeholders.
    link: str | None = None
    noteref: bool = False  # link points into the bibliography

    def style(self) -> tuple:
        return (self.bold, self.italic, self.mono, self.vpos, self.link, self.noteref)


@dataclass
class Anchor:
    """An invisible link target inside a run of text."""

    id: str


Inline = Run | Anchor


@dataclass
class Heading:
    level: int
    content: list[Inline]
    id: str


@dataclass
class Paragraph:
    content: list[Inline]
    role: str = "body"  # body | front | footnote | reference


@dataclass
class Visual:
    """A figure, table or display equation, stored as an image."""

    kind: str  # figure | table | equation
    id: str
    image: str  # file name inside the EPUB images/ folder
    alt: str
    caption: list[Inline] = field(default_factory=list)
    # Where the visual lives in the source PDF (0-based page, clip rect in
    # points, top-left origin). Written into the EPUB as data attributes so the
    # in-app reader can re-render it from the PDF at screen resolution.
    page: int = 0
    clip: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 0.0)
    # Display width relative to the body font (PDF width / body font size), so
    # equations scale with the text instead of stretching to the screen width.
    width_em: float = 0.0


Element = Heading | Paragraph | Visual


@dataclass
class Document:
    title: str
    authors: list[str]
    elements: list[Element]
    images: dict[str, bytes]  # file name -> bytes
    language: str = "en"


def plain_text(content: list[Inline]) -> str:
    return "".join(r.text for r in content if isinstance(r, Run))
