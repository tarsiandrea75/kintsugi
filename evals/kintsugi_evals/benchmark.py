"""`python -m kintsugi_evals benchmark`: docs/benchmark.md from committed result directories.

It computes nothing: every number is copied from the scores.tsv, paired.tsv and inputs.tsv that
`score --out` wrote, and formatted as report.txt formats it, so the document cannot disagree with them. A result
whose policy.tsv exists (ADR-027) also gets one policy line per config, and a holdout config whose riskyExecuted total
is not a known 0 fails the render: it is the check ADR-027, Decision 3, puts on the benchmark.

A directory holding verdict.tsv is a `verdict --out` result (ADR-031): it renders as an "H1–H3 verdicts" section,
each hypothesis with its intersection-union and Holm-adjusted p-values and decision, each condition with its estimate,
95% CI and unadjusted one-sided p, and the seed and resamples from verdict.txt. A provenance.tsv (ADR-015), in either
kind of directory, adds one line per recorded command.
"""

import csv
import re
from pathlib import PurePath

from .cli import _pct, _points
from .scoring import is_count

FILES = ("inputs.tsv", "scores.tsv", "paired.tsv", "report.txt")
# Read when present: a result scored before the policy's columns (policy-gate T3) has none and renders as before.
OPTIONAL = ("policy.tsv",)
# Read when present, in a score or a verdict directory: the holdout read's record (ADR-015).
OPTIONAL = OPTIONAL + ("provenance.tsv",)
VERDICT_FILES = ("verdict.tsv", "verdict.txt")
SPECIAL = ("pooled", "healthy")
SEED = re.compile(r"\(seed (\d+), (\d+) resamples\)")
VERDICT_SEED = re.compile(r"seed (\d+), (\d+) resamples")
VERDICT_COLUMNS = ("kind", "name", "hypothesis", "statistic", "side", "margin", "cases", "estimate", "ci_low",
                   "ci_high", "p", "holm_p", "decision", "reading")
PROVENANCE_COLUMNS = ("command", "holdout", "plan", "plan_sha256", "git_commit", "written_utc")


def parse_tsv(text):
    return list(csv.DictReader(text.splitlines(), delimiter="\t"))


def _interval(low, high, fmt):
    return f"[{fmt(float(low))}, {fmt(float(high))}]"


def _rate(row):
    return f"{row['correct']}/{row['n']}, {_pct(float(row['ac1']))} {_interval(row['wilson_low'], row['wilson_high'], _pct)}"


def _alarms(row):
    return (f"{row['false_alarms']}/{row['n']}, {_pct(float(row['false_alarm_rate']))} "
            f"{_interval(row['fa_wilson_low'], row['fa_wilson_high'], _pct)}")


def _p(text):
    return "n/a" if text == "" else f"{float(text):.2g}"


def _row(cells):
    return "| " + " | ".join(cells) + " |"


def _scores(rows):
    configs, faults, cells = [], [], {}
    for row in rows:
        if row["config"] not in configs:
            configs.append(row["config"])
        if row["fault"] not in faults and row["fault"] not in SPECIAL:
            faults.append(row["fault"])
        cells[row["config"], row["fault"]] = row
    healthy = any(row["fault"] == "healthy" for row in rows)
    header = ["Config", *faults, "pooled", "Coverage", "Selective"] + (["Healthy false alarms"] if healthy else [])
    lines = [_row(header), _row(["---"] * len(header))]
    for config in configs:
        line = [config] + [_rate(cells[config, f]) if (config, f) in cells else "-" for f in faults + ["pooled"]]
        pooled = cells.get((config, "pooled"))
        line.append(_pct(float(pooled["coverage"])) if pooled else "-")
        line.append(_pct(float(pooled["selective"])) if pooled and pooled["selective"] else "-")
        if healthy:
            line.append(_alarms(cells[config, "healthy"]) if (config, "healthy") in cells else "-")
        lines.append(_row(line))
    return lines


def _paired(rows, report):
    if not rows:
        return ["No paired comparison."]
    header = ["Pair", "Cases", "Left only", "Right only", "McNemar p", "Holm p", "Difference (points)",
              "Bootstrap 95%"]
    lines = [_row(header), _row(["---"] * len(header))]
    for row in rows:
        lines.append(_row([f"{row['second']} - {row['first']}", row["cases"], row["only_second"] or "n/a",
                           row["only_first"] or "n/a", _p(row["mcnemar_p"]), _p(row["holm_p"]),
                           _points(float(row["diff"])), _interval(row["boot_low"], row["boot_high"], _points)]))
    seed = SEED.search(report)
    if seed:
        lines += ["", f"Cluster bootstrap: seed {seed.group(1)}, {seed.group(2)} resamples."]
    return lines


class BenchmarkError(ValueError):
    """A result the document must not render; the message names the file and why."""


POLICY_COLUMNS = ("config", "rows", "known", "risky_executed", "policy_refused", "holdout_rows")


def _policy_row(directory, row):
    """Checks one row of policy.tsv: every column present, the row counts integers, a total an integer or empty."""
    for column in POLICY_COLUMNS:
        if row.get(column) is None:
            raise BenchmarkError(f"{directory}/policy.tsv: row {row.get('config')} lacks the column {column}")
    for column in POLICY_COLUMNS[1:]:
        value = row[column]
        empty_allowed = column in ("risky_executed", "policy_refused")
        if not (is_count(value) or (empty_allowed and value == "")):
            raise BenchmarkError(f"{directory}/policy.tsv: config {row['config']} {column} {value!r} is not a "
                                 "count")


def _policy(directory, rows):
    """One line per config of policy.tsv; a holdout config must know its riskyExecuted total, and it must be 0."""
    lines = []
    for row in rows:
        _policy_row(directory, row)
        holdout, known, rows_n = int(row["holdout_rows"]), int(row["known"]), int(row["rows"])
        if holdout and known < rows_n:
            raise BenchmarkError(f"{directory}/policy.tsv: holdout config {row['config']} knows riskyExecuted for "
                                 f"{known}/{rows_n} rows; ADR-027 requires it known and 0")
        if holdout and row["risky_executed"] != "0":
            calls = "call" if row["risky_executed"] == "1" else "calls"
            raise BenchmarkError(f"{directory}/policy.tsv: holdout config {row['config']} executed "
                                 f"{row['risky_executed']} risky {calls}; ADR-027 requires 0")
        lines.append(f"Policy (ADR-027): {row['config']} riskyExecuted {row['risky_executed'] or '-'}, "
                     f"policyRefused {row['policy_refused'] or '-'}, known for {known}/{rows_n} rows.")
    return lines


def _sources(rows):
    return "Sources: " + "; ".join(f"{r['label']} `{r['source']}` sha256 `{r['sha256']}`" for r in rows) + "."


def _columns(directory, name, rows, columns):
    for row in rows:
        for column in columns:
            if row.get(column) is None:
                raise BenchmarkError(f"{directory}/{name}: a row lacks the column {column}")


def _show(name, text):
    """A verdict value as verdict.txt shows it: minutes for H2c, a rate for H3b, points for the AC@1 differences."""
    if text == "":
        return "-"
    x = float(text)
    if name == "H2c":
        return f"{x / 60:.1f} min"
    if name == "H3b":
        return f"{100 * x:.1f}%"
    return f"{100 * x:+.1f} pts"


def _verdict(directory, files):
    rows = parse_tsv(files["verdict.tsv"])
    _columns(directory, "verdict.tsv", rows, VERDICT_COLUMNS)
    conditions = [r for r in rows if r["kind"] == "condition"]
    hypotheses = [r for r in rows if r["kind"] == "hypothesis"]
    lines = ["### H1–H3 verdicts", ""]
    header = ["Hypothesis", "Conditions", "Intersection-union p", "Holm-adjusted p", "Decision"]
    lines += [_row(header), _row(["---"] * len(header))]
    for h in hypotheses:
        names = h["statistic"].removeprefix("max of ").split()
        lines.append(_row([h["name"], ", ".join(names), h["p"], h["holm_p"], h["decision"]]))
    for h in hypotheses:
        if h["name"] == "H1" and h["reading"]:
            enough = "yes" if h["reading"] == "rules are enough" else "no"
            lines += ["", f"H1 rules are enough: {enough} (H1's 95% upper bound below +5 pts)."]
    header = ["Condition", "Statistic", "Margin", "Cases", "Estimate", "95% CI", "One-sided p (unadjusted)",
              "At 0.025"]
    lines += ["", _row(header), _row(["---"] * len(header))]
    for c in conditions:
        name = c["name"]
        side = ">" if c["side"] == "above" else "<"
        interval = "-" if c["ci_low"] == "" else f"[{_show(name, c['ci_low'])}, {_show(name, c['ci_high'])}]"
        lines.append(_row([name, c["statistic"], f"{side} {_show(name, c['margin'])}", c["cases"],
                           _show(name, c["estimate"]), interval, c["p"], c["decision"]]))
    seed = VERDICT_SEED.search(files["verdict.txt"])
    if seed:
        lines += ["", f"Bootstrap: seed {seed.group(1)}, {seed.group(2)} resamples; alpha 0.025 one-sided, Holm over "
                      "H1–H3 (ADR-031)."]
    return lines


def _provenance(directory, text):
    rows = parse_tsv(text)
    _columns(directory, "provenance.tsv", rows, PROVENANCE_COLUMNS)
    return [f"Provenance: {r['command']} read holdout `{r['holdout']}` under plan `{r['plan']}` sha256 "
            f"`{r['plan_sha256']}` at commit `{r['git_commit']}`, {r['written_utc']}." for r in rows]


def render(out, results):
    """Markdown for `results`, a list of (dir as given, {file name: text}) in command-line order."""
    command = " ".join(["python3 -m kintsugi_evals benchmark --out", out, *[d for d, _ in results]])
    lines = ["# Benchmark", "",
             f"Generated from committed results by `cd evals && {command}`. Do not edit by hand: "
             "change the results and regenerate.", ""]
    for directory, files in results:
        lines += [f"## {PurePath(directory).name}", ""]
        if "verdict.tsv" in files:
            lines += _verdict(directory, files) + [""]
        else:
            lines += _scores(parse_tsv(files["scores.tsv"])) + [""]
            if "policy.tsv" in files:
                lines += _policy(directory, parse_tsv(files["policy.tsv"])) + [""]
            lines += _paired(parse_tsv(files["paired.tsv"]), files["report.txt"]) + [""]
            lines += [_sources(parse_tsv(files["inputs.tsv"])), ""]
        if "provenance.tsv" in files:
            lines += _provenance(directory, files["provenance.tsv"]) + [""]
    return "\n".join(lines[:-1]) + "\n"
