"""`python -m kintsugi_evals benchmark`: docs/benchmark.md from committed result directories.

It computes nothing: every number is copied from the scores.tsv, paired.tsv and inputs.tsv that
`score --out` wrote, and formatted as report.txt formats it, so the document cannot disagree with them.
"""

import csv
import re
from pathlib import PurePath

from .cli import _pct, _points

FILES = ("inputs.tsv", "scores.tsv", "paired.tsv", "report.txt")
SPECIAL = ("pooled", "healthy")
SEED = re.compile(r"\(seed (\d+), (\d+) resamples\)")


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


def _sources(rows):
    return "Sources: " + "; ".join(f"{r['label']} `{r['source']}` sha256 `{r['sha256']}`" for r in rows) + "."


def render(out, results):
    """Markdown for `results`, a list of (dir as given, {file name: text}) in command-line order."""
    command = " ".join(["python3 -m kintsugi_evals benchmark --out", out, *[d for d, _ in results]])
    lines = ["# Benchmark", "",
             f"Generated from committed results by `cd evals && {command}`. Do not edit by hand: "
             "change the results and regenerate.", ""]
    for directory, files in results:
        lines += [f"## {PurePath(directory).name}", ""]
        lines += _scores(parse_tsv(files["scores.tsv"])) + [""]
        lines += _paired(parse_tsv(files["paired.tsv"]), files["report.txt"]) + [""]
        lines += [_sources(parse_tsv(files["inputs.tsv"])), ""]
    return "\n".join(lines[:-1]) + "\n"
