"""`python -m kintsugi_evals score`: AC@1 with Wilson CIs and paired differences (ADR-015).

Prints the report and, with --out, writes report.txt, scores.tsv, paired.tsv, inputs.tsv and a
copy of every input summary.tsv, so the result regenerates from committed files alone.
"""

import argparse
import csv
import hashlib
import re
import shutil
import sys
from pathlib import Path

from .scoring import paired, read_summary, summarize, summary_path
from .stats import holm

DEFAULT_SEED = 1
DEFAULT_ITERATIONS = 10000


def _pct(x):
    return f"{round(100 * x)}%"


def _points(x):
    return f"{round(100 * x):+d}"


def _na(value):
    return "n/a" if value is None else value


def _positive(text):
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError(f"must be at least 1, got {value}")
    return value


def _copy_name(label):
    return re.sub(r"[^A-Za-z0-9._-]", "_", label) + "-summary.tsv"


def _parser():
    parser = argparse.ArgumentParser(prog="python -m kintsugi_evals")
    commands = parser.add_subparsers(dest="command", required=True)
    score = commands.add_parser("score", help="score replay summary.tsv files")
    score.add_argument("--label", nargs=2, action="append", default=[], metavar=("NAME", "DIR"),
                       help="a replay output dir (or summary.tsv) and its config label; repeatable")
    score.add_argument("dirs", nargs="*", help="unlabelled dirs, labelled route-mode-view from their columns")
    score.add_argument("--out", help="write the result files to this directory")
    score.add_argument("--seed", type=int, default=DEFAULT_SEED, help="cluster bootstrap seed")
    score.add_argument("--iterations", type=_positive, default=DEFAULT_ITERATIONS, help="bootstrap resamples")
    return parser


def _report(inputs, scores, pairs, holm_p, seed, iterations):
    lines = []
    for (label, source, rows), score in zip(inputs, scores):
        first = rows[0]
        faulty = score.pooled.n if score.pooled else 0
        healthy = score.healthy.n if score.healthy else 0
        lines.append(f"{score.config}: route {first.route}, mode {first.mode}, view {first.view}; "
                     f"{faulty} fault cases, {healthy} healthy")
        for fault in score.faults + ([score.pooled] if score.pooled else []):
            low, high = fault.ci
            line = (f"{score.config}  {fault.fault:<7} {fault.correct:>2}/{fault.n:<2}  AC@1 {_pct(fault.ac1):>4}  "
                    f"[{_pct(low)}, {_pct(high)}]")
            if fault is score.pooled:
                selective = "-" if fault.selective is None else _pct(fault.selective)
                line += f"  coverage {_pct(fault.coverage)}  selective {selective}"
            lines.append(line)
        if score.healthy:
            h = score.healthy
            low, high = h.ci
            lines.append(f"{score.config}  healthy {h.false_alarms:>2}/{h.n:<2}  false alarms {_pct(h.false_alarm_rate)}"
                         f"  [{_pct(low)}, {_pct(high)}]")
    for pair, adjusted in zip(pairs, holm_p):
        if pair.mcnemar_p is None:
            test = "exact McNemar n/a (repeated cases)"
        else:
            test = f"exact McNemar p = {pair.mcnemar_p:.2g}"
            if len(pairs) > 1:
                test += f" (Holm {adjusted:.2g})"
        lines.append(f"{pair.second} - {pair.first}: {pair.cases} cases, {pair.second} only {_na(pair.only_second)}, "
                     f"{pair.first} only {_na(pair.only_first)}, {test}; difference {_points(pair.diff)} points, "
                     f"cluster bootstrap 95% [{_points(pair.boot_low)}, {_points(pair.boot_high)}] "
                     f"(seed {seed}, {iterations} resamples)")
    return "\n".join(lines) + "\n"


def _write_tsv(path, header, rows):
    with path.open("w", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def _write(out, inputs, scores, pairs, holm_p, report):
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.txt").write_text(report)
    score_rows = []
    for score in scores:
        for fault in score.faults + ([score.pooled] if score.pooled else []):
            low, high = fault.ci
            selective = "" if fault.selective is None else f"{fault.selective:.4f}"
            score_rows.append([score.config, fault.fault, fault.n, fault.correct, fault.abstained,
                               f"{fault.ac1:.4f}", f"{low:.4f}", f"{high:.4f}", f"{fault.coverage:.4f}", selective, "", "", "", ""])
        if score.healthy:
            h = score.healthy
            low, high = h.ci
            score_rows.append([score.config, "healthy", h.n, h.correct, "", "", "", "", "", "",
                               h.false_alarms, f"{h.false_alarm_rate:.4f}", f"{low:.4f}", f"{high:.4f}"])
    _write_tsv(out / "scores.tsv", ["config", "fault", "n", "correct", "abstained", "ac1", "wilson_low",
                                    "wilson_high", "coverage", "selective", "false_alarms", "false_alarm_rate",
                                    "fa_wilson_low", "fa_wilson_high"], score_rows)
    _write_tsv(out / "paired.tsv", ["first", "second", "cases", "only_first", "only_second", "mcnemar_p", "holm_p",
                                    "diff", "boot_low", "boot_high"],
               [[p.first, p.second, p.cases, "" if p.only_first is None else p.only_first,
                 "" if p.only_second is None else p.only_second,
                 "" if p.mcnemar_p is None else repr(p.mcnemar_p), "" if adjusted is None else repr(adjusted),
                 f"{p.diff:.4f}", f"{p.boot_low:.4f}", f"{p.boot_high:.4f}"] for p, adjusted in zip(pairs, holm_p)])
    copies = out / "inputs"
    copies.mkdir(exist_ok=True)
    provenance = []
    for label, source, rows in inputs:
        path = summary_path(source)
        name = _copy_name(label)
        shutil.copyfile(path, copies / name)
        provenance.append([label, source, hashlib.sha256(path.read_bytes()).hexdigest(), len(rows)])
    _write_tsv(out / "inputs.tsv", ["label", "source", "sha256", "rows"], provenance)


def _check_names(inputs):
    """Two inputs with one label, or one copy name, would merge or overwrite each other."""
    labels, copies = {}, {}
    for label, path, _ in inputs:
        if label in labels:
            raise SystemExit(f"two inputs have the label {label}: {labels[label]} and {path}; give each its own --label")
        labels[label] = path
        name = _copy_name(label)
        if name in copies:
            raise SystemExit(f"labels {copies[name]} and {label} share the copy name {name}; rename one")
        copies[name] = label


def score(args):
    try:
        return _score(args)
    except ValueError as error:
        raise SystemExit(f"score: {error}") from None


def _score(args):
    sources = [(name, path) for name, path in args.label] + [(None, path) for path in args.dirs]
    if not sources:
        raise SystemExit("score needs at least one replay dir")
    inputs = []
    for label, path in sources:
        rows = read_summary(path, label=label)
        if not rows:
            raise SystemExit(f"{path} holds no rows")
        inputs.append((rows[0].config, path, rows))
    _check_names(inputs)
    scores = [summarize(rows)[0] if len({r.config for r in rows}) == 1 else None for _, _, rows in inputs]
    if any(s is None for s in scores):
        raise SystemExit("an unlabelled summary.tsv mixes configs; label it with --label")
    pairs = [paired(inputs[i][2], inputs[j][2], seed=args.seed, iterations=args.iterations)
             for i in range(len(inputs)) for j in range(i + 1, len(inputs))]
    tested = [i for i, p in enumerate(pairs) if p.mcnemar_p is not None]
    holm_p = [None] * len(pairs)
    for i, adjusted in zip(tested, holm([pairs[i].mcnemar_p for i in tested])):
        holm_p[i] = adjusted
    report = _report(inputs, scores, pairs, holm_p, args.seed, args.iterations)
    sys.stdout.write(report)
    if args.out:
        _write(Path(args.out), inputs, scores, pairs, holm_p, report)
    return 0


def main(argv=None):
    args = _parser().parse_args(argv)
    return score(args)
