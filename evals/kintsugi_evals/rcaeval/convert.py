"""RCAEval RE2 cases -> replay recordings the unchanged agent reads (ADR-020; odd/tasks/rcaeval-converter.md T3).

One case directory, `<root>/RE2-<SYS>/<service>_<fault>/<rep>/` with `simple_metrics.csv`, `logs.csv` and
`inject_time.txt`, becomes one incident JSON in the replay format `ReplayLabTools` reads: a fixed symptom, `startedAt`
= inject time + 10 min, `sinceMinutes` = 10, and one `DOSSIER` step holding exactly the calls `DossierGatherer` makes
for a topology without table or source owners, plus its resource calls (ADR-021), each with the output mcp-tools
would have answered. The truth goes only to the `recordings.tsv` row; the file name is a blinded hash.

Choices, with their evidence in the feature document's T3 entry:

- Latency is `<svc>_latency-50`, already in seconds (`LATENCY_TO_SECONDS`), resampled to the lab's step for a 20-minute
  window (21 s): each point is the mean of the 1 s rows in (t - 21 s, t]; a bucket with no finite value is "NaN".
- The resource metrics of ADR-021 (`RESOURCE_COLUMNS`: `_cpu`, `_mem`, `_socket`) are resampled the same way, in the
  exporter's unit, one series per service labelled with the service only. `_diskio` is not mapped. The dossier also
  holds their calls, one per service and metric, which the gatherer makes from resource-facts T4 on.
- A log line's level is its `level` column when that names one, else read from the message (`level_of`).
- Restarts are an empty answer. Traffic comes from Online Boutique's traces (`traffic.py`, T4); Sock Shop has no
  traces, so its traffic is an empty answer and no `traces.csv` is read for it.
- Every input is read as UTF-8, whatever the locale. `logs.csv` decodes with `errors="replace"`: it is the system's
  free text, and a bad byte becomes U+FFFD in `untrustedText` instead of failing the case. The structured files
  (metrics, traces, inject time, topology) decode strictly, so a bad byte there fails the conversion.
- RE2-TT is holdout TT whole (ADR-032 Decision 1). It has traces, but its spans are HTTP, not gRPC, and `traffic.py`
  reads Online Boutique's gRPC names and status codes only, so TT's traffic is an empty answer, as Sock Shop's.
- `check` (`--check-only`) validates discovery and headers without converting: it reads directory names, the first
  line of each CSV and the inject time, prints counts per fault and anomalies with no case name, and writes nothing.
- A refused holdout reads no data file and writes nothing. A conversion writes into a staging directory beside
  `--out` and moves its files into `--out` only after the leakage scan passed; a run that fails leaves nothing.
"""

import csv
import hashlib
import json
import math
import os
import re
import shutil
import tempfile
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from . import leakage, traffic
from .trace_edges import normalize

REPO = Path(__file__).resolve().parents[3]

SYSTEM_DIRS = {"re2-ob": "RE2-OB", "re2-ss": "RE2-SS", "re2-tt": "RE2-TT"}

# The holdout name of each system's non-dev repetitions (ADR-020 §7, ADR-032 Decision 1).
HOLDOUTS = {"re2-ob": "A", "re2-ss": "B", "re2-tt": "TT"}

# Systems whose traffic the converter answers from `traces.csv` (ADR-020): Sock Shop has none, and Train Ticket's are
# HTTP spans that `traffic.py`'s gRPC names and status codes do not read.
TRACED_SYSTEMS = {"re2-ob"}

# One sentence per system, defined here only. It names the entry service, which is the entry of every case, and no
# fault, resource or root cause (leakage.py fails a recording whose symptom holds a fault word).
SYMPTOMS = {
    "re2-ob": "Users of the online shop see degraded responses from frontend.",
    "re2-ss": "Users of the online shop see degraded responses from front-end.",
    "re2-tt": "Users of the ticket booking site see degraded responses from ts-ui-dashboard.",
}

# ADR-020 §5, pre-registered. Used only for category accuracy: AC@1 on RCAEval is service-level.
CATEGORIES = {"cpu": "RESOURCE_EXHAUSTION", "mem": "RESOURCE_EXHAUSTION", "disk": "RESOURCE_EXHAUSTION",
              "socket": "RESOURCE_EXHAUSTION", "delay": "DEPENDENCY_LATENCY", "loss": "DEPENDENCY_LATENCY"}

CASE_NAME = re.compile(r"^(?P<service>[a-z0-9-]+)_(?P<fault>" + "|".join(CATEGORIES) + r")$")

# Metric prefixes that name no application component (T2): not services, so not reported as missing ones.
EXTRA_PREFIXES = {"frontend-external", "istio-init", "rabbitmq-exporter"}

SINCE_MINUTES = 10
LOG_LEVELS = ("WARN", "ERROR")
LOG_LINES = 20  # DossierGatherer.LOG_LINES, the cap McpLabTools applies to search_logs
MAX_LINE_LENGTH = 1000  # mcp-tools ObservabilityTools.MAX_LINE_LENGTH
TRUNCATED_MARKER = "[truncated]"
LATENCY_METRIC = "latency_avg"
LATENCY_COLUMN = "{service}_latency-50"
# RE2-OB repetition 1 (T3 entry): in productcatalogservice_delay, recommendationservice's own trace spans grow by about
# 200 ms while its latency-50 (Istio, sidecar-measured) grows from 0.0075 to 0.175. Read as milliseconds, the sidecar's
# latency would be shorter than the spans it contains, so the values are seconds already.
LATENCY_TO_SECONDS = 1.0
# ADR-021: metric -> RE2-OB column. The names carry no fault word, so they pass the leakage guard unchanged; the
# column names never reach a recording. `_diskio` exists for 4 of 11 services and the lab has no disk exporter: out.
RESOURCE_COLUMNS = {"processor_usage": "{service}_cpu", "memory_used": "{service}_mem",
                    "open_files": "{service}_socket"}
# Every `query_metric` series the converter answers: its column, its labels after `service` (mcp-tools groups the
# resource metrics `by (service)` alone) and the factor to the lab's unit.
SERIES = {LATENCY_METRIC: (LATENCY_COLUMN, {"uri": "/"}, LATENCY_TO_SECONDS),
          **{metric: (column, {}, 1.0) for metric, column in RESOURCE_COLUMNS.items()}}


def step_seconds(since_minutes):
    """mcp-tools `MetricCatalog.stepSeconds`: the smallest step, at least 5 s, that keeps a window within 60 points."""
    return max(5, (since_minutes * 60 + 58) // 59)


LATENCY_WINDOW_SECONDS = 2 * SINCE_MINUTES * 60  # query_metric is asked for both windows at once, every metric
LATENCY_STEP_SECONDS = step_seconds(2 * SINCE_MINUTES)

_LEVEL_COLUMN = {"warn": "WARN", "warning": "WARN", "error": "ERROR", "err": "ERROR", "fatal": "ERROR",
                 "critical": "ERROR", "crit": "ERROR", "severe": "ERROR", "panic": "ERROR"}
_ERROR_WORDS = re.compile(r"\b(error|exception|traceback|fatal|panic)\b", re.IGNORECASE)
_WARN_WORDS = re.compile(r"\bwarn(ing)?\b", re.IGNORECASE)

csv.field_size_limit(2**31 - 1)


class HoldoutRefused(Exception):
    """A holdout split asked for without naming it."""


@dataclass
class Summary:
    system: str
    rep: int
    split: str
    out: Path
    cases: int
    untrusted_hits: int


def split_of(system, rep, allow_holdout):
    """The manifest split of `system` repetition `rep`: `dev` for RE2-OB repetition 1, else `holdout`, which is
    refused unless `allow_holdout` names it (A: RE2-OB repetitions 2-3; B: RE2-SS; TT: RE2-TT)."""
    if system not in SYSTEM_DIRS:
        raise ValueError(f"unknown system {system!r}: choose {', '.join(SYSTEM_DIRS)}")
    if rep not in (1, 2, 3):
        raise ValueError(f"repetition {rep} does not exist: RE2 has repetitions 1 to 3")
    if system == "re2-ob" and rep == 1:
        return "dev"
    name = HOLDOUTS[system]
    if allow_holdout != name:
        raise HoldoutRefused(f"{SYSTEM_DIRS[system]} repetition {rep} is holdout {name}, read once under its "
                             f"committed plan: pass --allow-holdout {name} to convert it")
    return "holdout"


def discover(system_dir, rep, cases=None):
    """The case directories `(name, <case>/<rep>)` of a system, sorted by name; with `cases`, only those."""
    found = sorted((p.name, p / str(rep)) for p in Path(system_dir).iterdir()
                   if p.is_dir() and CASE_NAME.match(p.name) and (p / str(rep)).is_dir())
    if cases is None:
        return found
    names = {name for name, _ in found}
    missing = [case for case in cases if case not in names]
    if missing:
        raise ValueError(f"no case {', '.join(missing)} with repetition {rep} in {system_dir}")
    return [(name, path) for name, path in found if name in cases]


def blinded(system_dir_name, case, rep):
    """The recording's file stem and incident id: both from sha256 of `<RE2-SYS>/<case>/<rep>`."""
    digest = hashlib.sha256(f"{system_dir_name}/{case}/{rep}".encode()).digest()
    return digest.hex()[:16], str(uuid.UUID(bytes=digest[:16], version=4))


def level_of(column, message):
    """WARN, ERROR or None. The `level` column decides when it is set: `warning` is WARN, `error` or `fatal` ERROR,
    anything else (info, debug) neither. A blank column (cartservice, redis, tracebacks) is read from the message: a
    whole word error, exception, traceback, fatal or panic is ERROR, else warn or warning is WARN."""
    level = column.strip().lower()
    if level:
        return _LEVEL_COLUMN.get(level)
    if _ERROR_WORDS.search(message):
        return "ERROR"
    if _WARN_WORDS.search(message):
        return "WARN"
    return None


def instant_text(nanos):
    """Java's `Instant.toString()` of epoch nanoseconds: the fraction in groups of three digits, none when zero."""
    seconds, fraction = divmod(nanos, 10**9)
    text = datetime.fromtimestamp(seconds, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    if fraction == 0:
        return text + "Z"
    if fraction % 10**6 == 0:
        return f"{text}.{fraction // 10**6:03d}Z"
    if fraction % 10**3 == 0:
        return f"{text}.{fraction // 10**3:06d}Z"
    return f"{text}.{fraction:09d}Z"


def _compact(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def _result(items, truncated=False):
    """mcp-tools' envelope, keys in its order."""
    return _compact({"outcome": "OK", "returned": len(items), "truncated": truncated, "items": items})


def _finite(text):
    try:
        value = float(text)
    except ValueError:
        return None
    return value if math.isfinite(value) else None


def _metrics(case_dir, services, end, report):
    """The `query_metric` items per metric of `SERIES` and per service: one series, or none without its column."""
    start = end - LATENCY_WINDOW_SECONDS
    count = LATENCY_WINDOW_SECONDS // LATENCY_STEP_SECONDS + 1
    with (case_dir / "simple_metrics.csv").open(newline="", encoding="utf-8") as handle:
        reader = csv.reader(handle)
        header = [name.strip().lower() for name in next(reader)]
        prefixes = {normalize(name) for name in header if name != "time"}
        for prefix in sorted(prefixes - set(services) - EXTRA_PREFIXES):
            report.append(("unknown_metric_prefix", prefix, 1))
        columns = {(metric, s): header.index(column.format(service=s))
                   for metric, (column, _, _) in SERIES.items() for s in services
                   if column.format(service=s) in header}
        buckets = {key: defaultdict(list) for key in columns}
        time_column = header.index("time")
        for row in reader:
            time = int(float(row[time_column]))
            # Point k sits at start + k * step and holds the rows in (its time - step, its time].
            k = -(-(time - start) // LATENCY_STEP_SECONDS)
            if not 0 <= k < count:
                continue
            for (metric, service), column in columns.items():
                value = _finite(row[column]) if column < len(row) else None
                if value is not None:
                    buckets[(metric, service)][k].append(value * SERIES[metric][2])
    items = {metric: {} for metric in SERIES}
    for metric, (_, labels, _) in SERIES.items():
        for service in services:
            if (metric, service) not in columns:
                items[metric][service] = []
                continue
            points = []
            for k in range(count):
                values = buckets[(metric, service)].get(k)
                points.append([start + k * LATENCY_STEP_SECONDS,
                               repr(sum(values) / len(values)) if values else "NaN"])
            items[metric][service] = [{"labels": {"service": service, **labels}, "points": points}]
    return items


def _logs(case_dir, services, inject, report):
    """The WARN and ERROR lines per service in the incident window [inject, inject + 10 min), file order."""
    lo, hi = inject * 10**9, (inject + SINCE_MINUTES * 60) * 10**9
    lines = defaultdict(list)
    unknown = Counter()
    with (case_dir / "logs.csv").open(newline="", encoding="utf-8", errors="replace") as handle:
        for row in csv.DictReader(handle):
            service = normalize(row["container_name"])
            if service not in services:
                unknown[service] += 1
                continue
            nanos = int(row["timestamp"])
            if not lo <= nanos < hi:
                continue
            level = level_of(row["level"] or "", row["message"])
            if level is not None:
                lines[(service, level)].append((nanos, row["message"]))
    for name, n in sorted(unknown.items()):
        report.append(("unknown_log_container", name, n))
    return lines


def _search(service, level, lines):
    """`search_logs` as mcp-tools answers it: the newest `LOG_LINES` lines, each cut at `MAX_LINE_LENGTH`, and
    `truncated` when a returned line was cut."""
    newest = sorted(lines, key=lambda line: line[0], reverse=True)[:LOG_LINES]
    items, truncated = [], False
    for nanos, text in newest:
        if len(text) > MAX_LINE_LENGTH:
            text = text[:MAX_LINE_LENGTH] + TRUNCATED_MARKER
            truncated = True
        items.append({"timestamp": instant_text(nanos), "service": service, "level": level, "untrustedText": text})
    return _result(items, truncated)


def _call(tool, args, output):
    return {"tool": tool, "args": args, "outcome": "OK", "output": output}


def convert_case(case_dir, system, case, rep, services):
    """The incident document, the manifest row (without the split) and the report rows of one case directory."""
    service, fault = CASE_NAME.match(case).group("service", "fault")
    truth_service = normalize(service)
    if truth_service not in services:
        raise ValueError(f"{case}: the root-cause service {truth_service} is not in the topology")
    inject = int((case_dir / "inject_time.txt").read_text(encoding="utf-8").strip())
    end = inject + SINCE_MINUTES * 60
    report = []
    metrics = _metrics(case_dir, services, end, report)
    logs = _logs(case_dir, services, inject, report)
    previous, current = [], []
    if system in TRACED_SYSTEMS:
        # The gatherer's previous window [inject - 10 min, inject) and incident window [inject, inject + 10 min).
        windows = [(inject - SINCE_MINUTES * 60, inject), (inject, end)]
        previous, current = traffic.counts(case_dir / "traces.csv", services, windows, report)
    incident_args = {"sinceMinutes": SINCE_MINUTES}
    calls = [_call("service_restarts", dict(incident_args), _result([])),
             _call("traffic_counts", {"sinceMinutes": 2 * SINCE_MINUTES, "untilMinutesAgo": SINCE_MINUTES},
                   traffic.check_size(_result(previous))),
             _call("traffic_counts", dict(incident_args), traffic.check_size(_result(current)))]
    for s in services:
        calls.append(_call("query_metric", {"metric": LATENCY_METRIC, "service": s,
                                            "sinceMinutes": 2 * SINCE_MINUTES}, _result(metrics[LATENCY_METRIC][s])))
    for s in services:
        for metric in RESOURCE_COLUMNS:
            calls.append(_call("query_metric", {"metric": metric, "service": s, "sinceMinutes": 2 * SINCE_MINUTES},
                               _result(metrics[metric][s])))
    for s in services:
        for level in LOG_LEVELS:
            found = logs.get((s, level), [])
            args = {"service": s, "level": level, "sinceMinutes": SINCE_MINUTES}
            calls.append(_call("count_logs", args, _result([{"service": s, "count": len(found)}])))
            calls.append(_call("search_logs", {**args, "limit": LOG_LINES}, _search(s, level, found)))
    stem, incident_id = blinded(SYSTEM_DIRS[system], case, rep)
    incident = {"id": incident_id, "symptom": SYMPTOMS[system], "sinceMinutes": SINCE_MINUTES,
                "startedAt": instant_text(end * 10**9),
                "steps": [{"stepNo": 1, "kind": "DOSSIER", "phase": "DOSSIER", "toolOutput": _compact(calls)}]}
    row = [fault, "decision", "none", f"{truth_service}|{CATEGORIES[fault]}", f"{stem}.json", incident_id]
    return incident, row, report


def _write_tsv(path, header, rows):
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def _services(system, topology):
    topology = Path(topology) if topology else REPO / "agent/src/main/resources/topology" / f"{system}.json"
    return json.loads(topology.read_text(encoding="utf-8"))["services"]


def convert(root, system, rep, out, allow_holdout=None, cases=None, topology=None):
    """Converts repetition `rep` of `system` under `root` into `out`: one recording per case, `recordings.tsv` and
    `converter-report.tsv`. Refuses a holdout before reading anything. Everything is written to a staging directory
    beside `out` and moved into `out` only after the leakage scan passed, so a leak or any other failure leaves no
    file behind; other files already in `out` are kept."""
    split = split_of(system, rep, allow_holdout)
    system_dir = Path(root).expanduser() / SYSTEM_DIRS[system]
    services = _services(system, topology)
    chosen = discover(system_dir, rep, cases)
    if not chosen:
        raise ValueError(f"no case with repetition {rep} in {system_dir}")
    case_names = [p.name for p in system_dir.iterdir() if p.is_dir() and CASE_NAME.match(p.name)]
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{out.name}.", suffix=".partial", dir=out.parent))
    try:
        summary = _convert_into(staging, chosen, system, rep, split, services, case_names)
        out.mkdir(exist_ok=True)
        for path in sorted(staging.iterdir()):
            os.replace(path, out / path.name)
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    summary.out = out
    return summary


def _convert_into(out, chosen, system, rep, split, services, case_names):
    """Writes the recordings, the manifest and the report of `chosen` into `out`; raises on a leak."""
    rows, report, written = [], [], []
    for case, case_dir in chosen:
        incident, row, case_report = convert_case(case_dir, system, case, rep, services)
        path = out / row[4]
        path.write_text(json.dumps(incident, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
        written.append(path)
        rows.append(row + [split])
        report += [(row[4],) + entry for entry in case_report]
    hits = 0
    for path in written:
        found = leakage.scan(path.read_text(encoding="utf-8"), case_names)
        if found.strict:
            raise leakage.LeakageError(f"{path.name} leaks {', '.join(found.strict)}; no recording was kept")
        for word, n in sorted(found.untrusted.items()):
            report.append((path.name, "untrusted_fault_word", word, n))
            hits += n
    _write_tsv(out / "recordings.tsv", ["fault", "mode", "route", "truth", "file", "incident", "split"], rows)
    _write_tsv(out / "converter-report.tsv", ["file", "kind", "detail", "count"], report)
    return Summary(system, rep, split, out, len(rows), hits)


# The columns `convert_case` reads from each file; traces only for `TRACED_SYSTEMS`.
REQUIRED_COLUMNS = {"simple_metrics.csv": ("time",),
                    "logs.csv": ("timestamp", "container_name", "message", "level"),
                    "traces.csv": ("spanID", "parentSpanID", "serviceName", "operationName", "startTime",
                                   "statusCode")}


@dataclass
class Check:
    """What `check` found. `faults` counts cases per fault; `anomalies` are `(kind, detail, cases)` and never name a
    case or a root-cause service: a detail is a file, a column or a metric prefix, or "-"."""
    system: str
    rep: int
    split: str
    holdout: str | None
    cases: int
    faults: Counter
    stray: int
    anomalies: list


def _header(path):
    """The first line of a CSV, each name stripped; nothing past it is read."""
    with path.open(newline="", encoding="utf-8") as handle:
        line = handle.readline()
    return [name.strip() for name in next(csv.reader([line]), [])]


def check(root, system, rep, allow_holdout=None, topology=None):
    """Validates repetition `rep` of `system` without converting it: the case directories (counted per fault), the
    files `convert_case` opens, their header columns, the inject time, the metric prefixes against the topology, and
    whether each root cause is a topology service. It reads only directory names, the first line of each CSV and
    `inject_time.txt`, writes nothing, and refuses a holdout unless named, like `convert`."""
    split = split_of(system, rep, allow_holdout)
    services = set(_services(system, topology))
    system_dir = Path(root).expanduser() / SYSTEM_DIRS[system]
    chosen = discover(system_dir, rep)
    stray = sum(1 for p in system_dir.iterdir() if p.is_dir() and not CASE_NAME.match(p.name))
    files = ["simple_metrics.csv", "logs.csv", "inject_time.txt"] + (["traces.csv"] if system in TRACED_SYSTEMS
                                                                     else [])
    faults, anomalies = Counter(), Counter()
    for case, case_dir in chosen:
        service, fault = CASE_NAME.match(case).group("service", "fault")
        faults[fault] += 1
        if normalize(service) not in services:
            anomalies[("root_cause_outside_topology", "-")] += 1
        for name in files:
            path = case_dir / name
            if not path.is_file():
                anomalies[("missing_file", name)] += 1
            elif name == "inject_time.txt":
                if not re.fullmatch(r"\d+", path.read_text(encoding="utf-8").strip()):
                    anomalies[("bad_inject_time", "-")] += 1
            else:
                header = _header(path)
                if name == "simple_metrics.csv":  # `_metrics` lowercases this header; the others are read as is
                    header = [column.lower() for column in header]
                for column in REQUIRED_COLUMNS[name]:
                    if column not in header:
                        anomalies[("missing_column", f"{name}:{column}")] += 1
                if name == "simple_metrics.csv":
                    prefixes = {normalize(column) for column in header if column != "time"}
                    for prefix in prefixes - services - EXTRA_PREFIXES:
                        anomalies[("unknown_metric_prefix", prefix)] += 1
    rows = sorted((kind, detail, n) for (kind, detail), n in anomalies.items())
    return Check(system, rep, split, HOLDOUTS[system] if split == "holdout" else None, len(chosen), faults, stray,
                 rows)
