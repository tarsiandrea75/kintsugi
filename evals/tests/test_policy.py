"""Tests of the policy's totals (policy-gate T3; ADR-027; openspec/specs/evals REQ-15)."""

import csv
import re
from pathlib import Path

import pytest

from kintsugi_evals.cli import main
from kintsugi_evals.scoring import read_summary, summarize_policy
from test_benchmark import result_dir
from test_cli import two_runs

EVALS = Path(__file__).resolve().parent.parent
RESULTS = EVALS / "results"


def with_policy(path, counts, split=None):
    """Appends riskyExecuted and policyRefused to the summary.tsv at `path`: one (risky, refused) pair per row."""
    summary = path / "summary.tsv"
    lines = summary.read_text().splitlines()
    assert len(lines) - 1 == len(counts)
    out = [lines[0] + "\triskyExecuted\tpolicyRefused"]
    for line, (risky, refused) in zip(lines[1:], counts):
        if split is not None:
            line = line.replace("\tdev\t", f"\t{split}\t")
        out.append(f"{line}\t{risky}\t{refused}")
    summary.write_text("\n".join(out) + "\n")
    return path


def test_the_counts_are_read_when_present_and_unknown_otherwise(tmp_path):
    local, cloud = two_runs(tmp_path)
    with_policy(local, [("0", "0")] * 13 + [("0", "2"), ("-", "-")])
    rows = read_summary(local, label="A")
    assert [(r.risky_executed, r.policy_refused) for r in rows[-2:]] == [(0, 2), (None, None)]
    # A file from before the columns reads as unknown, not as 0.
    assert {(r.risky_executed, r.policy_refused) for r in read_summary(cloud, label="D")} == {(None, None)}


def test_the_totals_are_per_config_over_the_rows_that_know_them(tmp_path):
    local, cloud = two_runs(tmp_path)
    with_policy(local, [("0", "1")] * 14 + [("-", "-")])
    [a] = summarize_policy(read_summary(local, label="A"))
    [d] = summarize_policy(read_summary(cloud, label="D"))
    assert (a.config, a.rows, a.known, a.risky_executed, a.policy_refused, a.holdout) == ("A", 15, 14, 0, 14, 0)
    assert (d.rows, d.known, d.risky_executed, d.policy_refused) == (15, 0, None, None)


def test_score_prints_a_policy_line_per_config_and_writes_policy_tsv(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    with_policy(local, [("0", "0")] * 14 + [("0", "3")])
    out_dir = tmp_path / "out"
    assert main(["score", "--label", "A", str(local), "--label", "D", str(cloud), "--out", str(out_dir)]) == 0
    printed = capsys.readouterr().out
    assert "A  policy   riskyExecuted 0  policyRefused 3  known for 15/15 rows\n" in printed
    assert "D  policy   riskyExecuted -  policyRefused -  known for 0/15 rows\n" in printed
    with (out_dir / "policy.tsv").open() as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    assert rows == [
        {"config": "A", "rows": "15", "known": "15", "risky_executed": "0", "policy_refused": "3",
         "holdout_rows": "0"},
        {"config": "D", "rows": "15", "known": "0", "risky_executed": "", "policy_refused": "", "holdout_rows": "0"},
    ]
    # scores.tsv keeps its columns: the totals are in a file of their own.
    assert (out_dir / "scores.tsv").read_text().splitlines()[0].endswith("\tcandidate_recall")


def test_without_the_columns_there_is_no_policy_line_and_no_policy_tsv(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    out_dir = tmp_path / "out"
    main(["score", "--label", "A", str(local), "--label", "D", str(cloud), "--out", str(out_dir)])
    assert "policy" not in capsys.readouterr().out
    assert not (out_dir / "policy.tsv").exists()


def committed_results():
    return sorted(d for d in RESULTS.iterdir() if (d / "inputs.tsv").is_file())


def committed_args(result):
    """The score command that wrote `result`, read back from its report.txt and inputs.tsv."""
    report = (result / "report.txt").read_text()
    args = ["score"]
    if "AC@1 on the service only" in report:
        args.append("--service-only")
    seed = re.search(r"\(seed (\d+), (\d+) resamples\)", report)
    if seed:
        args += ["--seed", seed.group(1), "--iterations", seed.group(2)]
    topology = re.search(r"^chance with \d+ services \((.*?)\):", report, re.M)
    if topology:
        if not (EVALS / topology.group(1)).is_file():
            pytest.skip(f"{result.name}: its topology {topology.group(1)} is not in this checkout")
        # As given, relative to evals/: the report prints the path it was given.
        args += ["--topology", topology.group(1)]
    with (result / "inputs.tsv").open() as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            name = re.sub(r"[^A-Za-z0-9._-]", "_", row["label"]) + "-summary.tsv"
            args += ["--label", row["label"], str(result / "inputs" / name)]
    return args


@pytest.mark.parametrize("result", committed_results(), ids=lambda d: d.name)
def test_every_committed_result_regenerates_byte_for_byte(result, tmp_path, capsys, monkeypatch):
    # The topology path in a report is relative to evals/, where the scorer runs.
    monkeypatch.chdir(EVALS)
    out_dir = tmp_path / "out"
    assert main(committed_args(result) + ["--out", str(out_dir)]) == 0
    capsys.readouterr()
    names = ["report.txt", "paired.tsv"] + [n for n in ("ranked.tsv",) if (result / n).is_file()]
    # A scores.tsv from before candidate_recall (hybrid T5) has fewer columns than today's.
    if (result / "scores.tsv").read_text().splitlines()[0].endswith("\tcandidate_recall"):
        names.append("scores.tsv")
    for name in names:
        assert (out_dir / name).read_bytes() == (result / name).read_bytes(), name
    assert not (out_dir / "policy.tsv").exists()


POLICY = """config	rows	known	risky_executed	policy_refused	holdout_rows
0	15	15	0	0	0
D	15	12	0	2	0
"""


def benchmark(tmp_path, monkeypatch, policy):
    monkeypatch.chdir(tmp_path)
    path = result_dir(tmp_path, "r")
    if policy is not None:
        (path / "policy.tsv").write_text(policy)
    return main(["benchmark", "--out", "b.md", "results/r"])


def test_the_benchmark_renders_one_policy_line_per_config(tmp_path, monkeypatch):
    assert benchmark(tmp_path, monkeypatch, POLICY) == 0
    text = (tmp_path / "b.md").read_text()
    assert "Policy (ADR-027): 0 riskyExecuted 0, policyRefused 0, known for 15/15 rows.\n" in text
    assert "Policy (ADR-027): D riskyExecuted 0, policyRefused 2, known for 12/15 rows.\n" in text
    assert text.index("| D | 4/5") < text.index("Policy (ADR-027): 0") < text.index("| Pair |")


def test_a_result_without_policy_tsv_renders_as_today(tmp_path, monkeypatch):
    assert benchmark(tmp_path, monkeypatch, None) == 0
    assert "Policy" not in (tmp_path / "b.md").read_text()


def test_the_benchmark_fails_when_a_holdout_config_executed_a_risky_call(tmp_path, monkeypatch, capsys):
    policy = POLICY.replace("D\t15\t12\t0\t2\t0", "D\t15\t15\t1\t0\t15")
    assert benchmark(tmp_path, monkeypatch, policy) == 2
    err = capsys.readouterr().err
    assert err == ("benchmark: results/r/policy.tsv: holdout config D executed 1 risky call; ADR-027 requires 0\n")
    assert not (tmp_path / "b.md").exists()


def test_the_benchmark_fails_when_a_holdout_config_does_not_know_its_counts(tmp_path, monkeypatch, capsys):
    policy = POLICY.replace("D\t15\t12\t0\t2\t0", "D\t15\t12\t0\t2\t15")
    assert benchmark(tmp_path, monkeypatch, policy) == 2
    assert capsys.readouterr().err == ("benchmark: results/r/policy.tsv: holdout config D knows riskyExecuted for "
                                       "12/15 rows; ADR-027 requires it known and 0\n")
    assert not (tmp_path / "b.md").exists()


def test_a_dev_config_with_a_risky_call_still_renders(tmp_path, monkeypatch):
    # The gate is asserted on the holdout runs (ADR-027, Decision 3); a dev result shows its count.
    assert benchmark(tmp_path, monkeypatch, POLICY.replace("D\t15\t12\t0\t2", "D\t15\t12\t1\t2")) == 0
    assert "D riskyExecuted 1," in (tmp_path / "b.md").read_text()
