"""BARO on RCAEval RE2 cases, a standard-library port of `RCAEval.e2e.baro.baro` 1.11.0 (ADR-024, T7 of
odd/tasks/rcaeval-dev-baselines.md).

`tests/fixtures/baro/` holds the original's answers on synthetic series, which the port's tests compare against. Each
step below is the original's unless it says otherwise:

- The 600 s window is ours (vision §2), inclusive on both sides. The original gets the window from its caller.
- The scale is the original's `RobustScaler`, whose `_handle_zeros_in_scale` makes 1 of any scale under 10 machine
  epsilons, not only of a scale of 0. Percentiles follow numpy's linear interpolation arithmetic.
- An infinite value in a scored column, a segment with no sample and a non-numeric cell raise, as they do in the
  original. So does a repeated metric header, which pandas would silently rename: only a repeated `time` is known.
- `rank_case` takes a directory and reads two files in it. The truth comes from the directory's name, which only
  `run` looks at, and goes to the `truth` and `fault` columns, never to the ranking.
"""

import csv
import math
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from . import convert
from .trace_edges import normalize

WINDOW_SECONDS = 600
MEM_DIVISOR = 1e6
NEAR_ZERO_SCALE = 10 * sys.float_info.epsilon
TOP_SERVICES = 5

ROUTE = "baro"
UNKNOWN_CATEGORY = "UNKNOWN"
MODEL = "RCAEval-1.11.0"
TIME_COLUMNS = ("time", "time.1")  # "time.1" is how pandas reads a repeated "time" header

# What pandas' read_csv reads as NaN by default. Anything else that float() refuses makes a column the original refuses.
NA_TEXTS = {"", "#N/A", "#N/A N/A", "#NA", "-1.#IND", "-1.#QNAN", "-NaN", "-nan", "1.#IND", "1.#QNAN", "<NA>", "N/A",
            "NA", "NULL", "NaN", "None", "n/a", "nan", "null"}

# The replay driver's columns, so that the scorer and a reader see one format. Where BARO has no such thing it says "-".
SUMMARY_COLUMNS = ["fault", "mode", "route", "sample", "status", "truth", "reported", "verdict", "recorded",
                   "unrecorded", "coverage", "conflicts", "promptTokens", "completionTokens", "seconds", "split",
                   "recording", "view", "report", "model", "think", "reportCap", "reportTimeout", "candidates"]


@dataclass(frozen=True)
class Ranking:
    columns: list
    scores: list
    services: list


@dataclass(frozen=True)
class Summary:
    system: str
    rep: int
    split: str
    out: Path
    cases: int


def service_of(column):
    """`RCAEval.benchmark.scoring.to_service`: the text before the first underscore, with `-db` removed."""
    return column.split("_")[0].replace("-db", "")


def is_constant(values):
    """pandas' `(df != df.iloc[0]).any()`, negated: NaN differs from everything, itself included."""
    return not any(value != values[0] for value in values)


def _percentile(ordered, fraction):
    """numpy's linear percentile of sorted values, with its order of operations: NaN when there are none."""
    if not ordered:
        return math.nan
    position = (len(ordered) - 1) * fraction
    below = math.floor(position)
    weight = position - below
    low, high = ordered[below], ordered[min(below + 1, len(ordered) - 1)]
    if weight >= 0.5:
        return high - (high - low) * (1 - weight)
    return low + (high - low) * weight


def _median(ordered):
    """numpy's median of sorted values: the middle one, or the mean of the two middle ones; NaN when there are none."""
    if not ordered:
        return math.nan
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def column_score(normal, anomalous):
    """`RobustScaler` fitted on the normal segment, ignoring NaN. The score is Python's `max` over the scaled anomalous
    values in order: a NaN in first place stays the maximum, a NaN later is passed over."""
    if any(math.isinf(value) for value in normal + anomalous):
        raise ValueError("an infinite value in a scored column")
    ordered = sorted(value for value in normal if not math.isnan(value))
    center = _median(ordered)
    scale = _percentile(ordered, 0.75) - _percentile(ordered, 0.25)
    if scale < NEAR_ZERO_SCALE:
        scale = 1.0
    return max((value - center) / scale for value in anomalous)


def _number(text):
    return math.nan if text in NA_TEXTS else float(text)


def _metric_columns(header):
    """The `(position, name)` of each metric column; refuses a header pandas would have to rename."""
    if "time" not in header:
        raise ValueError("no time column")
    metrics = [(i, name) for i, name in enumerate(header) if name not in TIME_COLUMNS]
    names = Counter(name for _, name in metrics)
    names["time"] = header.count("time") - 1
    bad = sorted(name for name, count in names.items() if count > 1 or not name)
    if bad:
        raise ValueError(f"column names pandas would rename: {', '.join(repr(name) for name in bad)}")
    return metrics


def _read_metrics(path):
    """The header and the rows of `simple_metrics.csv` as floats; a short row reads as NaN in its missing cells."""
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.reader(handle)
        header = next(reader, [])
        rows = []
        for row in reader:
            if not row:
                continue
            if len(row) > len(header):
                raise ValueError(f"a row of {len(row)} cells under a header of {len(header)}")
            rows.append([_number(text) for text in row] + [math.nan] * (len(header) - len(row)))
    return header, rows


def _segment(rows, metrics):
    """One segment as `{column: values}`: each column constant in it dropped, then memory divided into megabytes."""
    if not rows:
        raise ValueError("a segment with no sample")
    columns = {name: [row[i] for row in rows] for i, name in metrics}
    return {name: [value / MEM_DIVISOR for value in values] if name.endswith("_mem") else values
            for name, values in columns.items() if not is_constant(values)}


def rank(header, rows, inject_time):
    """The columns, scores and services, best first: ADR-024 steps 1 to 7. A sample with a NaN time is in no segment."""
    metrics = _metric_columns(header)
    time = header.index("time")
    window = [row for row in rows if abs(row[time] - inject_time) <= WINDOW_SECONDS]
    normal = _segment([row for row in window if row[time] < inject_time], metrics)
    anomalous = _segment([row for row in window if row[time] >= inject_time], metrics)
    scored = [(name, column_score(values, anomalous[name])) for name, values in normal.items() if name in anomalous]
    scored = sorted(scored, key=lambda item: item[1], reverse=True)
    columns = [name for name, _ in scored]
    return Ranking(columns, [score for _, score in scored], list(dict.fromkeys(service_of(name) for name in columns)))


def rank_case(case_dir):
    """The ranking of one case directory, from `simple_metrics.csv` and `inject_time.txt` alone."""
    case_dir = Path(case_dir)
    inject = int((case_dir / "inject_time.txt").read_text(encoding="utf-8").strip())
    header, rows = _read_metrics(case_dir / "simple_metrics.csv")
    return rank(header, rows, inject)


def split_of(system, rep):
    """`dev`, or a refusal: BARO reads only RE2-OB repetition 1 until a committed plan names another (ADR-024)."""
    try:
        return convert.split_of(system, rep, None)
    except convert.HoldoutRefused:
        raise convert.HoldoutRefused(f"{convert.SYSTEM_DIRS[system]} repetition {rep} is a holdout: baro-rcaeval reads "
                                     f"only RE2-OB repetition 1 until a committed plan names another") from None


def recording_of(system, case, rep):
    """The converter's recording id of a case, so that BARO pairs with the replayed configs."""
    return convert.blinded(convert.SYSTEM_DIRS[system], case, rep)[0]


def _summary_row(case, case_dir, system, rep, split):
    try:
        services = rank_case(case_dir).services
        if not services:
            raise ValueError("no column is left to rank")
    except ValueError as error:
        raise ValueError(f"{case}: {error}") from None
    service, fault = convert.CASE_NAME.match(case).group("service", "fault")
    candidates = [f"{name}|{UNKNOWN_CATEGORY}" for name in services[:TOP_SERVICES]]
    values = {"fault": fault, "mode": "decision", "route": ROUTE, "sample": 1, "status": "COMPLETED",
              "truth": f"{normalize(service)}|{convert.CATEGORIES[fault]}", "reported": candidates[0],
              "promptTokens": 0, "completionTokens": 0, "split": split, "recording": recording_of(system, case, rep),
              "model": MODEL, "candidates": ";".join(candidates)}
    return [values.get(column, "-") for column in SUMMARY_COLUMNS]


def run(root, system, rep, out):
    """Ranks repetition `rep` of `system` under `root` into `out/summary.tsv`. Refuses a holdout before reading anything;
    a case that cannot be ranked fails the run and nothing is written."""
    split = split_of(system, rep)
    system_dir = Path(root).expanduser() / convert.SYSTEM_DIRS[system]
    chosen = convert.discover(system_dir, rep)
    if not chosen:
        raise ValueError(f"no case with repetition {rep} in {system_dir}")
    rows = [_summary_row(case, case_dir, system, rep, split) for case, case_dir in chosen]
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    convert._write_tsv(out / "summary.tsv", SUMMARY_COLUMNS, rows)
    return Summary(system, rep, split, out, len(rows))
