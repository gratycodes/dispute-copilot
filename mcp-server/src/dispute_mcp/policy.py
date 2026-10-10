"""Retrieval over the synthetic policy document: section chunking plus BM25.

The policy is small (about 25 sections), so a dependency-free BM25 is enough
and keeps results deterministic. Each hit carries the section id the agent
must cite.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

_HEADING = re.compile(r"^###\s+(P\d+\.\d+)\s+(.*)$")
_TOKEN = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
_STOP = frozenset("a an and are as at be by for from has if in is it its not of on or the this "
                  "to was with without".split())

# Reason codes map to words that appear in the policy, so a query of just the
# code still finds the right sections.
REASON_HINTS = {
    "FRAUD_CNP": "fraud card not present authenticated 3-d secure device prior use",
    "ITEM_NOT_RECEIVED": "item not received tracking delivery transit",
    "NOT_AS_DESCRIBED": "not as described description return received",
    "DUPLICATE": "duplicate processing same order reference charged twice",
    "CREDIT_NOT_PROCESSED": "credit not processed refund issued return",
    "CANCELLED_RECURRING": "cancelled recurring subscription cancellation usage",
}


@dataclass(frozen=True)
class Section:
    id: str
    title: str
    text: str


def tokenize(text: str) -> list[str]:
    return [t for t in _TOKEN.findall(text.lower()) if t not in _STOP]


def parse_sections(markdown: str) -> list[Section]:
    sections: list[Section] = []
    current: tuple[str, str] | None = None
    body: list[str] = []
    parent = ""
    for line in markdown.splitlines():
        if line.startswith("## "):
            parent = line[3:].strip()
        m = _HEADING.match(line)
        if m or line.startswith("## "):
            if current:
                sections.append(Section(current[0], current[1], " ".join(body).strip()))
            current, body = None, []
            if m:
                current = (m.group(1), f"{parent} / {m.group(2).strip()}")
            continue
        if current and line.strip():
            body.append(line.strip())
    if current:
        sections.append(Section(current[0], current[1], " ".join(body).strip()))
    return sections


class PolicyIndex:
    def __init__(self, sections: list[Section], *, k1: float = 1.5, b: float = 0.75) -> None:
        if not sections:
            raise ValueError("policy has no sections")
        self.sections = sections
        self._docs = [tokenize(f"{s.title} {s.title} {s.text}") for s in sections]
        self._tf = [Counter(d) for d in self._docs]
        self._avgdl = sum(len(d) for d in self._docs) / len(self._docs)
        df: Counter[str] = Counter()
        for d in self._docs:
            df.update(set(d))
        n = len(self._docs)
        self._idf = {t: math.log(1 + (n - c + 0.5) / (c + 0.5)) for t, c in df.items()}
        self._k1, self._b = k1, b
        self._by_id = {s.id: s for s in sections}

    @classmethod
    def from_file(cls, path: str | Path) -> PolicyIndex:
        return cls(parse_sections(Path(path).read_text()))

    def get(self, section_id: str) -> Section | None:
        return self._by_id.get(section_id)

    def search(self, query: str, k: int = 3) -> list[tuple[Section, float]]:
        terms = tokenize(" ".join(REASON_HINTS.get(w, w) for w in query.split()))
        scores = []
        for i, tf in enumerate(self._tf):
            dl = len(self._docs[i])
            s = 0.0
            for t in terms:
                f = tf.get(t, 0)
                if f:
                    s += self._idf[t] * f * (self._k1 + 1) / (
                        f + self._k1 * (1 - self._b + self._b * dl / self._avgdl))
            scores.append((s, i))
        scores.sort(key=lambda x: (-x[0], x[1]))
        return [(self.sections[i], round(s, 4)) for s, i in scores[:k] if s > 0]
