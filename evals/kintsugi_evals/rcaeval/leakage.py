"""The leakage guard of the RCAEval converter (ADR-020 §4, odd/tasks/rcaeval-converter.md AC2).

A recording must not tell the agent the answer: no case directory name, no category of the answer key, and no fault
word (cpu, mem, disk, delay, loss, socket) in anything the converter itself writes. Those are strict: one hit fails
the conversion.

`untrustedText` is different: it is the system's own log message, copied as is, which the agent reads as data. A
real log line may say "delay" or "loss" for reasons of its own, and rewriting it would make the recording lie about
the logs. Hits there are counted and reported, never masked and never fatal. The leak this guards against is the
converter's (a name, a label, a sentence it writes); a word the system logged during the incident is evidence, the
same evidence a human on call would read. The case name and the categories stay strict even there, since no log
line can hold them by chance.
"""

import json
import re
from collections import Counter
from dataclasses import dataclass, field

FAULT_WORDS = ("cpu", "mem", "disk", "delay", "loss", "socket")

CATEGORIES = ("RESOURCE_EXHAUSTION", "DEPENDENCY_LATENCY")

# A whole word, case-insensitive. An underscore breaks words too (`x_mem_y` is a hit), so a metric-like name cannot
# carry a fault word; letters and digits do not (`memory`, `socketed` are not).
FAULT_WORD = re.compile(r"(?<![A-Za-z0-9])(" + "|".join(FAULT_WORDS) + r")(?![A-Za-z0-9])", re.IGNORECASE)

UNTRUSTED_KEY = "untrustedText"


class LeakageError(Exception):
    """A converted recording holds a strict hit."""


@dataclass
class Found:
    strict: list = field(default_factory=list)
    untrusted: Counter = field(default_factory=Counter)


def _strings(node, strict, untrusted, key=None):
    """Every string of a JSON value, keys included. A string that is itself a JSON object or array (the dossier
    step's `toolOutput` and each call's `output`) is read as JSON, so the guard sees through the nesting."""
    if isinstance(node, dict):
        for k, v in node.items():
            strict.append(k)
            _strings(v, strict, untrusted, k)
    elif isinstance(node, list):
        for item in node:
            _strings(item, strict, untrusted, key)
    elif isinstance(node, str):
        if key == UNTRUSTED_KEY:
            untrusted.append(node)
            return
        if node[:1] in ("{", "["):
            try:
                _strings(json.loads(node), strict, untrusted, key)
                return
            except ValueError:
                pass
        strict.append(node)
    elif node is not None:
        strict.append(str(node))


def scan(text, case_names):
    """The hits in one recording's text: strict ones as descriptions, in order of first appearance, and the fault
    words of `untrustedText` as counts by lowercase word."""
    strict_texts, untrusted_texts = [], []
    _strings(json.loads(text), strict_texts, untrusted_texts)
    found = Found()

    def hit(description):
        if description not in found.strict:
            found.strict.append(description)

    for value in strict_texts:
        for match in FAULT_WORD.finditer(value):
            hit(f"fault word '{match.group(1)}'")
    for value in untrusted_texts:
        found.untrusted.update(match.group(1).lower() for match in FAULT_WORD.finditer(value))
    for value in strict_texts + untrusted_texts:
        lowered = value.lower()
        for name in case_names:
            if name.lower() in lowered:
                hit(f"case name '{name}'")
        for category in CATEGORIES:
            if category.lower() in lowered:
                hit(f"category '{category}'")
    return found
