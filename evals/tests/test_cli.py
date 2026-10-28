"""Tests of `python -m kintsugi_evals score` (openspec/specs/evals)."""

import csv

from kintsugi_evals.cli import main
from test_scoring import TRUTH, write_tsv


def two_runs(tmp_path):
    local, cloud = tmp_path / "local", tmp_path / "cloud"
    local.mkdir()
    cloud.mkdir()
    rows_local, rows_cloud = [], []
    for fault in ("F1", "F2", "F3"):
        for i in range(5):
            recording = f"r{i}-{fault}"
            local_right = fault == "F2"
            cloud_right = not (fault == "F1" and i == 2)
            rows_local.append((fault, "COMPLETED", TRUTH, TRUTH if local_right else "x|y", recording))
            rows_cloud.append((fault, "COMPLETED", TRUTH, TRUTH if cloud_right else "x|y", recording))
    write_tsv(local / "summary.tsv", rows_local)
    write_tsv(cloud / "summary.tsv", rows_cloud)
    return local, cloud


def test_score_prints_pooled_rates_and_the_paired_test(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    assert main(["score", "--label", "A", str(local), "--label", "D", str(cloud)]) == 0
    out = capsys.readouterr().out
    assert "A  pooled   5/15  AC@1  33%  [15%, 58%]" in out
    assert "D  pooled  14/15  AC@1  93%  [70%, 99%]" in out
    assert "D - A: 15 cases, D only 9, A only 0, exact McNemar p = 0.0039" in out


def test_score_writes_tables_report_and_input_copies(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    out_dir = tmp_path / "results" / "m0"
    main(["score", "--label", "A", str(local), "--label", "D", str(cloud), "--out", str(out_dir)])
    printed = capsys.readouterr().out
    assert (out_dir / "report.txt").read_text() == printed
    with (out_dir / "scores.tsv").open() as handle:
        scores = list(csv.DictReader(handle, delimiter="\t"))
    pooled = [s for s in scores if s["fault"] == "pooled"]
    assert [(s["config"], s["correct"], s["n"]) for s in pooled] == [("A", "5", "15"), ("D", "14", "15")]
    with (out_dir / "paired.tsv").open() as handle:
        [pair] = list(csv.DictReader(handle, delimiter="\t"))
    assert (pair["first"], pair["second"], pair["mcnemar_p"]) == ("A", "D", "0.00390625")
    assert (out_dir / "inputs" / "A-summary.tsv").read_text() == (local / "summary.tsv").read_text()
    assert (out_dir / "inputs" / "D-summary.tsv").exists()


def test_score_is_deterministic_for_a_seed(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    args = ["score", "--label", "A", str(local), "--label", "D", str(cloud), "--seed", "3", "--iterations", "500"]
    main(args)
    first = capsys.readouterr().out
    main(args)
    assert capsys.readouterr().out == first


def test_score_labels_an_unlabelled_dir_from_its_columns(tmp_path, capsys):
    local, _ = two_runs(tmp_path)
    main(["score", str(local)])
    assert "cloud-decision--  pooled   5/15" in capsys.readouterr().out


def test_scorer_imports_only_the_standard_library():
    import ast
    import sys
    from pathlib import Path

    package = Path(__file__).resolve().parent.parent / "kintsugi_evals"
    for module in package.glob("*.py"):
        for node in ast.walk(ast.parse(module.read_text())):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.level == 0:
                names = [node.module]
            else:
                continue
            for name in names:
                assert name.split(".")[0] in sys.stdlib_module_names, f"{module.name} imports {name}"


def test_report_regenerates_from_the_committed_input_copies(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    out_dir = tmp_path / "result"
    main(["score", "--label", "A", str(local), "--label", "D", str(cloud), "--out", str(out_dir)])
    capsys.readouterr()
    inputs = out_dir / "inputs"
    main(["score", "--label", "A", str(inputs / "A-summary.tsv"), "--label", "D", str(inputs / "D-summary.tsv")])
    assert capsys.readouterr().out == (out_dir / "report.txt").read_text()
