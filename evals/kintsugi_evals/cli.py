"""`python -m kintsugi_evals score`: AC@1 with Wilson CIs and paired differences (ADR-015).

Prints the report and, with --out, writes report.txt, scores.tsv, paired.tsv, inputs.tsv and a
copy of every input summary.tsv, so the result regenerates from committed files alone. With --topology it adds
AC@3, AC@5, Avg@5 and their chance levels to the report, and writes ranked.tsv. When an input records the policy's
counts (ADR-027), it adds one policy line per config and writes policy.tsv; without them the output is unchanged.
When an input has the level column (ADR-028), it adds a verified-correct and an L1-rate line for that config; without
it the output is unchanged.

`python -m kintsugi_evals convert-rcaeval` turns RCAEval RE2 cases into replay recordings (ADR-020).

`python -m kintsugi_evals baro-rcaeval` ranks RCAEval RE2 dev cases with BARO into a summary.tsv (ADR-024).

`python -m kintsugi_evals audit-latency` classifies the latency values a run's evidence cites (REQ-14).

`python -m kintsugi_evals escalate` computes Config C from A's and D's summaries over ADR-030's grid, selects its rule,
and writes grid.tsv, escalation.tsv, C-summary.tsv and escalate.txt (openspec/specs/config-c).
"""

import argparse
import csv
import hashlib
import os
import re
import shutil
import sys
from pathlib import Path

from . import escalation
from .scoring import (
    CORRECT,
    chance_ac,
    chance_avg5,
    paired,
    read_service_count,
    read_summary,
    summarize,
    summarize_levels,
    summarize_policy,
    summarize_ranked,
    summary_path,
)
from .stats import holm, wilson

DEFAULT_SEED = 1
DEFAULT_ITERATIONS = 10000
# The cuts that get a Wilson interval, in the report and ranked.tsv.
RANKED_CUTS = (1, 3, 5)


def _pct(x):
    return f"{round(100 * x)}%"


def _pct2(x):
    return f"{100 * x:.2f}%"


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


def _repetition(text):
    return text if text == "all" else int(text)


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
    score.add_argument("--service-only", action="store_true",
                       help="score AC@1 on the service part of the truth only (RCAEval's AC@1, ADR-020)")
    score.add_argument("--topology", metavar="PATH",
                       help="with --service-only: add AC@3, AC@5, Avg@5 and their chance levels over this topology's "
                            "services, and write ranked.tsv")
    bench = commands.add_parser("benchmark", help="render docs/benchmark.md from committed result dirs")
    bench.add_argument("--out", required=True, help="the Markdown file to write")
    bench.add_argument("dirs", nargs="+", help="result dirs written by score --out, in document order")
    rca = commands.add_parser("convert-rcaeval", help="convert RCAEval RE2 cases into replay recordings")
    rca.add_argument("--root", help="the extracted dataset, holding RE2-OB/ and RE2-SS/ "
                                    "(default: $KINTSUGI_RCAEVAL_DIR, else ~/data/rcaeval)")
    rca.add_argument("--system", required=True, choices=["re2-ob", "re2-ss"])
    rca.add_argument("--rep", required=True, type=int, help="the repetition, 1 to 3")
    rca.add_argument("--out", help="the recordings directory (default: evals/out/rcaeval/<system>-rep<rep>/)")
    rca.add_argument("--allow-holdout", metavar="NAME", help="convert holdout NAME (A: RE2-OB repetitions 2-3, "
                                                              "B: RE2-SS), only under its committed plan")
    rca.add_argument("--case", action="append", dest="cases", metavar="NAME",
                     help="convert only this case directory, e.g. checkoutservice_cpu; repeatable")
    rca.add_argument("--topology", help="the topology file (default: the agent's topology/<system>.json)")
    bar = commands.add_parser("baro-rcaeval", help="rank RCAEval RE2 cases with BARO into a summary.tsv")
    bar.add_argument("--root", help="the extracted dataset, holding RE2-OB/, RE2-SS/ and RE2-TT/ "
                                    "(default: $KINTSUGI_RCAEVAL_DIR, else ~/data/rcaeval)")
    bar.add_argument("--system", required=True, choices=["re2-ob", "re2-ss", "re2-tt"])
    bar.add_argument("--rep", required=True, type=_repetition, metavar="N|all",
                     help="the repetition; only RE2-OB repetition 1, and 'all' on RE2-TT, are accepted")
    bar.add_argument("--out", help="the output directory (default: evals/out/rcaeval/baro-<system>-rep<rep>/)")
    audit = commands.add_parser("audit-latency", help="classify the latency values a run's evidence cites")
    audit.add_argument("--label", nargs=2, action="append", default=[], metavar=("NAME", "DIR"),
                       help="a replay run dir and its label; repeatable")
    audit.add_argument("--out", help="write audit.tsv and report.txt to this directory")
    esc = commands.add_parser("escalate", help="compute Config C from A's and D's summaries (ADR-030)")
    esc.add_argument("--a", required=True, metavar="DIR", help="Config A's replay dir (or summary.tsv)")
    esc.add_argument("--d", required=True, metavar="DIR", help="Config D's replay dir (or summary.tsv)")
    esc.add_argument("--out", required=True, help="write grid.tsv, escalation.tsv, C-summary.tsv and escalate.txt here")
    esc.add_argument("--seed", type=int, default=DEFAULT_SEED, help="cluster bootstrap seed")
    esc.add_argument("--iterations", type=_positive, default=DEFAULT_ITERATIONS, help="bootstrap resamples")
    esc.add_argument("--service-only", action="store_true",
                     help="score AC@1 on the service part of the truth only (RCAEval's AC@1, ADR-020)")
    esc.add_argument("--topology", metavar="PATH", help="with --service-only: add chance AC@1 over this topology")
    return parser


def _ranked_line(config, score):
    cuts = "  ".join(f"AC@{k} {score.hits[k - 1]}/{score.n} {_pct(score.ac(k))} "
                     f"[{_pct(score.ci(k)[0])}, {_pct(score.ci(k)[1])}]" for k in RANKED_CUTS)
    return (f"{config}  ranked {score.fault:<7} {score.n:>2}  {cuts}  Avg@5 {_pct(score.avg5)}  "
            f"mean list {score.mean_length:.2f}")


def _chance_line(topology, services):
    cuts = "  ".join(f"AC@{k} {_pct2(chance_ac(k, services))}" for k in RANKED_CUTS)
    return f"chance with {services} services ({topology}): {cuts}  Avg@5 {_pct2(chance_avg5(services))}"


def _policy_line(totals):
    def shown(value):
        return "-" if value is None else value

    return (f"{totals.config}  {'policy':<7}  riskyExecuted {shown(totals.risky_executed)}  "
            f"policyRefused {shown(totals.policy_refused)}  known for {totals.known}/{totals.rows} rows")


def _rate_line(config, name, metric, k, n, rate, ci, without, rows):
    """One level rate as the other rates are printed: k/n, percentage and Wilson 95% CI, then the rows left out."""
    shown = f"{_pct(rate):>4}  [{_pct(ci[0])}, {_pct(ci[1])}]" if n else "n/a"
    return f"{config}  {name:<8} {k:>2}/{n:<2}  {metric} {shown}  without a level: {without} {rows}"


def _level_lines(score):
    return [_rate_line(score.config, "verified", "verified-correct", score.verified, score.fault_rows,
                       score.verified_rate, score.verified_ci, score.fault_without, "fault rows"),
            _rate_line(score.config, "L1", "L1 rate", score.l1, score.rows, score.l1_rate, score.l1_ci, score.without,
                       "rows")]


def _report(inputs, scores, pairs, holm_p, seed, iterations, service_only=False, ranked=None, topology=None,
            services=None, policy=None, levels=None):
    lines = []
    for i, ((label, source, rows), score) in enumerate(zip(inputs, scores)):
        first = rows[0]
        faulty = score.pooled.n if score.pooled else 0
        healthy = score.healthy.n if score.healthy else 0
        scope = "; AC@1 on the service only" if service_only else ""
        lines.append(f"{score.config}: route {first.route}, mode {first.mode}, view {first.view}; "
                     f"{faulty} fault cases, {healthy} healthy{scope}")
        for fault in score.faults + ([score.pooled] if score.pooled else []):
            low, high = fault.ci
            line = (f"{score.config}  {fault.fault:<7} {fault.correct:>2}/{fault.n:<2}  AC@1 {_pct(fault.ac1):>4}  "
                    f"[{_pct(low)}, {_pct(high)}]")
            if fault is score.pooled:
                selective = "-" if fault.selective is None else _pct(fault.selective)
                line += f"  coverage {_pct(fault.coverage)}  selective {selective}"
            if fault.candidate_recall is not None:
                line += f"  recall {_pct(fault.candidate_recall)}"
            lines.append(line)
        if score.healthy:
            h = score.healthy
            low, high = h.ci
            lines.append(f"{score.config}  healthy {h.false_alarms:>2}/{h.n:<2}  false alarms {_pct(h.false_alarm_rate)}"
                         f"  [{_pct(low)}, {_pct(high)}]")
        if policy:
            lines.append(_policy_line(policy[i]))
        if levels and levels[i]:
            lines += _level_lines(levels[i])
        if ranked:
            mine = ranked[i]
            lines += [_ranked_line(mine.config, r) for r in mine.faults + ([mine.pooled] if mine.pooled else [])]
    if ranked:
        lines.append(_chance_line(topology, services))
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
            recall = "" if fault.candidate_recall is None else f"{fault.candidate_recall:.4f}"
            score_rows.append([score.config, fault.fault, fault.n, fault.correct, fault.abstained,
                               f"{fault.ac1:.4f}", f"{low:.4f}", f"{high:.4f}", f"{fault.coverage:.4f}", selective, "", "", "", "",
                               recall])
        if score.healthy:
            h = score.healthy
            low, high = h.ci
            score_rows.append([score.config, "healthy", h.n, h.correct, "", "", "", "", "", "",
                               h.false_alarms, f"{h.false_alarm_rate:.4f}", f"{low:.4f}", f"{high:.4f}", ""])
    _write_tsv(out / "scores.tsv", ["config", "fault", "n", "correct", "abstained", "ac1", "wilson_low",
                                    "wilson_high", "coverage", "selective", "false_alarms", "false_alarm_rate",
                                    "fa_wilson_low", "fa_wilson_high", "candidate_recall"], score_rows)
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


def _write_policy(out, policy):
    """policy.tsv: each config's policy totals (ADR-027), apart from scores.tsv so that its bytes stay as they were."""
    _write_tsv(out / "policy.tsv", ["config", "rows", "known", "risky_executed", "policy_refused", "holdout_rows"],
               [[t.config, t.rows, t.known, "" if t.risky_executed is None else t.risky_executed,
                 "" if t.policy_refused is None else t.policy_refused, t.holdout] for t in policy])


def _write_ranked(out, ranked, services):
    """ranked.tsv: every count behind the report's ranked lines, and chance on each row so the file reads alone."""
    chance = [services, *(f"{chance_ac(k, services):.4f}" for k in RANKED_CUTS), f"{chance_avg5(services):.4f}"]
    rows = []
    for config in ranked:
        for r in config.faults + ([config.pooled] if config.pooled else []):
            cuts = [f"{value:.4f}" for k in RANKED_CUTS for value in (r.ac(k), *r.ci(k))]
            rows.append([config.config, r.fault, r.n, *r.hits, *cuts, f"{r.avg5:.4f}", f"{r.mean_length:.4f}", *chance])
    _write_tsv(out / "ranked.tsv",
               ["config", "fault", "n", "hits1", "hits2", "hits3", "hits4", "hits5",
                *(f"ac{k}{part}" for k in RANKED_CUTS for part in ("", "_low", "_high")), "avg5", "mean_length",
                "services", *(f"chance_ac{k}" for k in RANKED_CUTS), "chance_avg5"], rows)


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
    if args.topology is not None and not args.service_only:
        raise SystemExit("score: --topology needs --service-only: the ranked metrics name the service alone")
    services = read_service_count(args.topology) if args.topology is not None else None
    sources = [(name, path) for name, path in args.label] + [(None, path) for path in args.dirs]
    if not sources:
        raise SystemExit("score needs at least one replay dir")
    inputs = []
    for label, path in sources:
        rows = read_summary(path, label=label, service_only=args.service_only)
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
    ranked = [summarize_ranked(rows)[0] for _, _, rows in inputs] if args.topology is not None else None
    # The policy's totals only when some input records them, so a result from before the columns is unchanged.
    policy = [summarize_policy(rows)[0] for _, _, rows in inputs]
    policy = policy if any(t.known for t in policy) else None
    # The level rates only for an input with the level column, so a result from before it is unchanged.
    levels = [summarize_levels(rows)[0] for _, _, rows in inputs]
    report = _report(inputs, scores, pairs, holm_p, args.seed, args.iterations, args.service_only, ranked,
                     args.topology, services, policy, levels)
    sys.stdout.write(report)
    if args.out:
        _write(Path(args.out), inputs, scores, pairs, holm_p, report)
        if ranked:
            _write_ranked(Path(args.out), ranked, services)
        if policy:
            _write_policy(Path(args.out), policy)
    return 0


def benchmark(args):
    from .benchmark import FILES, OPTIONAL, BenchmarkError, render

    results = []
    for directory in args.dirs:
        files = {}
        for name in FILES:
            path = Path(directory) / name
            if not path.is_file():
                print(f"benchmark: {path} not found", file=sys.stderr)
                return 2
            files[name] = path.read_text(encoding="utf-8")
        for name in OPTIONAL:
            path = Path(directory) / name
            if path.is_file():
                files[name] = path.read_text(encoding="utf-8")
        results.append((directory, files))
    try:
        text = render(args.out, results)
    except BenchmarkError as error:
        print(f"benchmark: {error}", file=sys.stderr)
        return 2
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    return 0


def convert_rcaeval(args):
    from .rcaeval import convert, leakage

    root = args.root or os.environ.get("KINTSUGI_RCAEVAL_DIR") or "~/data/rcaeval"
    out = args.out or convert.REPO / "evals" / "out" / "rcaeval" / f"{args.system}-rep{args.rep}"
    try:
        summary = convert.convert(root, args.system, args.rep, out, allow_holdout=args.allow_holdout,
                                  cases=args.cases, topology=args.topology)
    except (convert.HoldoutRefused, leakage.LeakageError, ValueError, OSError) as error:
        raise SystemExit(f"convert-rcaeval: {error}") from None
    print(f"converted {summary.cases} cases of {convert.SYSTEM_DIRS[summary.system]} repetition {summary.rep} "
          f"(split {summary.split}) to {summary.out}; fault words in untrustedText: {summary.untrusted_hits} "
          f"(converter-report.tsv)")
    return 0


def baro_rcaeval(args):
    from .rcaeval import baro, convert

    root = args.root or os.environ.get("KINTSUGI_RCAEVAL_DIR") or "~/data/rcaeval"
    out = args.out or convert.REPO / "evals" / "out" / "rcaeval" / f"baro-{args.system}-rep{args.rep}"
    try:
        summary = baro.run(root, args.system, args.rep, out)
    except (convert.HoldoutRefused, ValueError, OSError) as error:
        raise SystemExit(f"baro-rcaeval: {error}") from None
    print(f"ranked {summary.cases} cases of {baro.SYSTEM_DIRS[summary.system]} repetition {summary.rep} "
          f"(split {summary.split}) to {summary.out / 'summary.tsv'}")
    return 0


def audit_latency(args):
    from .audit_latency import audit_latency as run

    return run(args)


ESCALATE_OUTPUTS = ("grid.tsv", "escalation.tsv", "C-summary.tsv", "escalate.txt")
# A column one side lacks is written with its "unknown" value, so read_summary reads the row as it read the source.
UNKNOWN_VALUE = {"riskyExecuted": "-", "policyRefused": "-", "level": "-"}


def _accuracy_line(name, correct, n):
    low, high = wilson(correct, n)
    return f"{name}  AC@1 {correct:>2}/{n:<2} {_pct(correct / n):>4}  [{_pct(low)}, {_pct(high)}]"


def _rule_line(e):
    p = e.pair
    triggers = " ".join(f"{name} {e.triggers[name]}" for name in escalation.TRIGGERS)
    return (f"{e.rule.label:<16} escalations {e.escalations:>2}/{e.cases:<2} {_pct(e.rate):>4}  "
            f"[{_pct(e.rate_ci[0])}, {_pct(e.rate_ci[1])}]  C AC@1 {e.c_correct:>2}/{e.cases:<2} {_pct(e.ac1):>4}  "
            f"[{_pct(e.ac1_ci[0])}, {_pct(e.ac1_ci[1])}]  C - D {_points(p.diff)} (C only {p.only_second}, "
            f"D only {p.only_first}, exact McNemar p = {p.mcnemar_p:.2g})  {triggers}  "
            f"{'eligible' if e.eligible else 'over the cap'}")


def _escalate_report(args, pairs, evaluations, chosen, cap_fails, services):
    n = len(pairs)
    scope = "; AC@1 on the service only" if args.service_only else ""
    lines = [f"C from A ({args.a}) and D ({args.d}): {n} paired fault cases{scope}",
             _accuracy_line("A", sum(a.outcome == CORRECT for a, _ in pairs), n),
             _accuracy_line("D", sum(d.outcome == CORRECT for _, d in pairs), n)]
    lines += [_rule_line(e) for e in evaluations]
    if services is not None:
        lines.append(_chance_line(args.topology, services))
    cap = f"{round(100 * escalation.CAP)}%"
    if cap_fails:
        lines.append(f"selected: {chosen.rule.label}; the cost cap fails on this split: every rule escalates more "
                     f"than {cap} of the {n} paired cases")
    else:
        lines.append(f"selected: {chosen.rule.label} (the highest C AC@1 among the rules escalating at most {cap} of "
                     f"the {n} paired cases)")
    return "\n".join(lines) + "\n"


def _write_grid(out, evaluations, chosen):
    rows = []
    for e in evaluations:
        p = e.pair
        rows.append([e.rule.k, escalation.format_t(e.rule.t), e.cases, e.escalations, f"{e.rate:.4f}",
                     f"{e.rate_ci[0]:.4f}", f"{e.rate_ci[1]:.4f}", e.c_correct, f"{e.ac1:.4f}", f"{e.ac1_ci[0]:.4f}",
                     f"{e.ac1_ci[1]:.4f}", p.only_second, p.only_first, f"{p.diff:.4f}", repr(p.mcnemar_p),
                     f"{p.boot_low:.4f}", f"{p.boot_high:.4f}", *(e.triggers[name] for name in escalation.TRIGGERS),
                     "yes" if e.eligible else "no", "yes" if e is chosen else "no"])
    _write_tsv(out / "grid.tsv", ["k", "t", "cases", "escalations", "rate", "esc_wilson_low", "esc_wilson_high",
                                  "c_correct", "c_ac1", "c_wilson_low", "c_wilson_high", "c_only", "d_only",
                                  "c_minus_d", "mcnemar_p", "boot_low", "boot_high",
                                  *(name.replace("-", "_") for name in escalation.TRIGGERS), "eligible", "selected"],
               rows)


def _raw_summary(path):
    """A summary's header and its raw rows by case, so C-summary.tsv copies each chosen row's own values."""
    with summary_path(path).open(newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        return list(reader.fieldnames), {(raw["split"], raw["recording"]): raw for raw in reader}


def _write_c_summary(out, args, choices):
    """C-summary.tsv: each chosen row as its source wrote it, under the union of both headers, A's order first."""
    (a_header, a_raw), (d_header, d_raw) = _raw_summary(args.a), _raw_summary(args.d)
    header = list(dict.fromkeys(a_header + d_header))
    raws = {"A": a_raw, "D": d_raw}
    rows = []
    for choice in choices:
        raw = raws[choice.source][choice.case]
        rows.append([raw[column] if column in raw else UNKNOWN_VALUE.get(column, "") for column in header])
    _write_tsv(out / "C-summary.tsv", header, rows)


def escalate(args):
    try:
        return _escalate(args)
    except ValueError as error:
        raise SystemExit(f"escalate: {error}") from None


def _escalate(args):
    if args.topology is not None and not args.service_only:
        raise SystemExit("escalate: --topology needs --service-only: chance names the service alone")
    out = Path(args.out)
    for name in ESCALATE_OUTPUTS:
        if (out / name).exists():
            raise SystemExit(f"escalate: {out / name} exists: escalate never overwrites its outputs")
    services = read_service_count(args.topology) if args.topology is not None else None
    pairs = escalation.pair(read_summary(args.a, label="A", service_only=args.service_only),
                            read_summary(args.d, label="D", service_only=args.service_only))
    evaluations = [escalation.evaluate(pairs, rule, args.seed, args.iterations) for rule in escalation.grid(pairs)]
    chosen, cap_fails = escalation.select(evaluations)
    report = _escalate_report(args, pairs, evaluations, chosen, cap_fails, services)
    sys.stdout.write(report)
    out.mkdir(parents=True, exist_ok=True)
    _write_grid(out, evaluations, chosen)
    choices = escalation.compose(pairs, chosen.rule)
    _write_tsv(out / "escalation.tsv", ["split", "recording", "source", "trigger"],
               [[*c.case, c.source, c.trigger or "-"] for c in choices])
    _write_c_summary(out, args, choices)
    (out / "escalate.txt").write_text(report)
    return 0


def main(argv=None):
    args = _parser().parse_args(argv)
    commands = {"benchmark": benchmark, "convert-rcaeval": convert_rcaeval, "baro-rcaeval": baro_rcaeval,
                "audit-latency": audit_latency, "escalate": escalate}
    return commands.get(args.command, score)(args)
