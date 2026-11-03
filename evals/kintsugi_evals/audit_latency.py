"""`python -m kintsugi_evals audit-latency`: which latency values a report's evidence cites (REQ-14, d-abstention).

For every evidence item whose quote is a row of the dossier's latency table (DossierRenderer.latency), in either of
its shapes, the numbers in its `why` are matched to the row's four values. The eight-cell shape of D1-D4,
`| service | uri | previous mean | incident mean | previous max | incident max | points | points |`, and the
five-cell shape of latency-pairs T1, `| service | uri | mean, p → i | max, p → i | points, p → i |`, give the same
four values, so the same rule classifies both. An item is `incident_cited` when it names the incident mean or max,
`previous_only` when it names a previous value and neither incident value, the signature of a model that compares two
previous values as if one were the incident, and `no_number` otherwise. Only `report.evidence` is read: the summary
is not tied to a row. The rule cannot see an incident value cited wrongly, or a misread in the summary, so its
`previous_only` count is a lower bound.
"""

import csv
import json
import re
import sys
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

CLASSES = ("incident_cited", "previous_only", "no_number")
# The line label DossierRenderer.linePrefix puts on a dossier line, in case a quote keeps it.
LINE_LABEL = re.compile(r"^D\d+ ")
# A cell separator: a pipe that DossierRenderer.cell did not escape.
SEPARATOR = re.compile(r"(?<!\\)\|")
VALUE = re.compile(r"^\d+(?:\.\d+)?$")
NO_VALUE = "-"
# Between a pair cell's previous and incident values (DossierRenderer.ARROW).
ARROW = " → "
# A number not inside a word or another number, with an optional unit; MB or any other unit is not a time.
WHY_NUMBER = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)(?:\s*(ms|s))?(?!\w)(?!\.\d)")
SUMMARY_COLUMNS = ("fault", "mode", "sample", "recording")
AUDIT_HEADER = ["label", "fault", "recording", "sample", "abstained", "line", "class", "quote", "why"]


def latency_row(quote):
    """The eight cells of a latency-table row, or None for any other quote.

    The shape decides: eight cells whose middle four are each a number or `-` (a window without values), with at
    least one number. A five-cell row whose last three cells are each `previous → incident` is split into the same
    eight cells, in the same order. A resource row has seven cells, so it never matches.
    """
    text = LINE_LABEL.sub("", quote.strip())
    if not (text.startswith("|") and text.endswith("|")) or len(text) < 2:
        return None
    cells = tuple(cell.strip() for cell in SEPARATOR.split(text[1:-1]))
    if len(cells) == 5:
        cells = _unpaired(cells)
        if cells is None:
            return None
    values = cells[2:6]
    if len(cells) != 8 or not all(VALUE.match(v) or v == NO_VALUE for v in values):
        return None
    return cells if any(VALUE.match(v) for v in values) else None


def _unpaired(cells):
    """A five-cell pair row as the eight cells of the old shape, or None when a value cell is not a pair."""
    pairs = [cell.split(ARROW) for cell in cells[2:]]
    if not all(len(pair) == 2 for pair in pairs):
        return None
    (previous_mean, incident_mean), (previous_max, incident_max), (previous_points, incident_points) = (
        [side.strip() for side in pair] for pair in pairs)
    return (cells[0], cells[1], previous_mean, incident_mean, previous_max, incident_max, previous_points,
            incident_points)


def why_numbers(why):
    """The times in a why, in seconds: a number with `ms` is divided by 1000, one with `s` or no unit is seconds."""
    numbers = []
    for match in WHY_NUMBER.finditer(why):
        value = Decimal(match.group(1))
        numbers.append(value / 1000 if match.group(2) == "ms" else value)
    return numbers


def _matches(number, printed):
    """True when number is within half a unit of printed's last digit, both ends included."""
    if not VALUE.match(printed):
        return False
    value = Decimal(printed)
    half = Decimal(5).scaleb(value.as_tuple().exponent - 1)
    return abs(number - value) <= half


def classify(quote, why):
    """incident_cited, previous_only or no_number for a latency-row quote; None for any other quote."""
    cells = latency_row(quote)
    if cells is None:
        return None
    previous, incident = (cells[2], cells[4]), (cells[3], cells[5])
    numbers = why_numbers(why or "")
    if any(_matches(n, v) for n in numbers for v in incident):
        return "incident_cited"
    if any(_matches(n, v) for n in numbers for v in previous):
        return "previous_only"
    return "no_number"


@dataclass(frozen=True)
class Item:
    label: str
    fault: str
    recording: str
    sample: str
    abstained: bool
    line: object
    kind: str
    quote: str
    why: str


@dataclass(frozen=True)
class Run:
    label: str
    samples: int
    abstained: int
    items: tuple


def audit_run(label, directory):
    """The latency-row items of one replay run, in summary.tsv order, then evidence order."""
    directory = Path(directory)
    summary = directory / "summary.tsv"
    if not summary.is_file():
        raise ValueError(f"{summary} does not exist: expected a replay run directory")
    with summary.open(newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        missing = [c for c in SUMMARY_COLUMNS if c not in (reader.fieldnames or [])]
        if missing:
            raise ValueError(f"{summary} lacks the columns {', '.join(missing)}")
        rows = list(reader)
    items, abstained = [], 0
    for row in rows:
        # replay-agent.sh writes <fault>-<mode>-<recording>-<n>.json next to summary.tsv.
        path = directory / f"{row['fault']}-{row['mode']}-{row['recording']}-{row['sample']}.json"
        if not path.is_file():
            raise ValueError(f"{path} does not exist: summary.tsv names it")
        report = json.loads(path.read_text(encoding="utf-8")).get("report") or {}
        abstains = report.get("rootCauseService") in (None, "", "UNKNOWN")
        abstained += abstains
        for evidence in report.get("evidence") or []:
            kind = classify(evidence.get("quote") or "", evidence.get("why"))
            if kind is not None:
                items.append(Item(label, row["fault"], row["recording"], row["sample"], abstains,
                                  evidence.get("line"), kind, evidence.get("quote") or "", evidence.get("why") or ""))
    return Run(label, len(rows), abstained, tuple(items))


def _counts(label, name, items):
    counts = "  ".join(f"{kind} {sum(i.kind == kind for i in items)}" for kind in CLASSES)
    return f"{label}  {name:<10} {counts}"


def report(runs):
    lines = []
    for run in runs:
        lines.append(f"{run.label}: {run.samples} samples, {run.abstained} abstained; "
                     f"{len(run.items)} latency-row items")
        lines.append(_counts(run.label, "abstained", [i for i in run.items if i.abstained]))
        lines.append(_counts(run.label, "answered", [i for i in run.items if not i.abstained]))
        lines.append(_counts(run.label, "all", run.items))
        for i in run.items:
            if i.kind == "previous_only":
                state = "abstained" if i.abstained else "answered"
                lines.append(f"{run.label}  previous_only  {i.fault} {i.recording} sample {i.sample} {state} "
                             f"line {i.line}: {i.quote}  why: {i.why}")
    return "\n".join(lines) + "\n"


def _write(out, runs, text):
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.txt").write_text(text)
    with (out / "audit.tsv").open("w", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(AUDIT_HEADER)
        for run in runs:
            for i in run.items:
                writer.writerow([i.label, i.fault, i.recording, i.sample, "true" if i.abstained else "false",
                                 "" if i.line is None else i.line, i.kind, i.quote, i.why])


def audit_latency(args):
    if not args.label:
        raise SystemExit("audit-latency needs at least one --label NAME DIR")
    labels = {}
    for name, path in args.label:
        if name in labels:
            raise SystemExit(f"two inputs have the label {name}: {labels[name]} and {path}; give each its own --label")
        labels[name] = path
    try:
        runs = [audit_run(name, path) for name, path in args.label]
    except (ValueError, OSError) as error:
        raise SystemExit(f"audit-latency: {error}") from None
    text = report(runs)
    sys.stdout.write(text)
    if args.out:
        _write(Path(args.out), runs, text)
    return 0
