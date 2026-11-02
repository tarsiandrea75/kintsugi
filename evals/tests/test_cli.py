"""Tests of `python -m kintsugi_evals score` (openspec/specs/evals)."""

import csv
from pathlib import Path

import pytest

from kintsugi_evals.cli import main
from kintsugi_evals.scoring import read_summary, summarize, summarize_ranked
from test_scoring import RANK_TRUTH, TRUTH, pairs, write_ranked_tsv, write_tsv

EVALS = Path(__file__).resolve().parent.parent
COMMITTED = EVALS / "results/2026-11-02-baro-pipeline-re2ob-dev"
RE2_OB = EVALS.parent / "agent/src/main/resources/topology/re2-ob.json"


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


def test_score_prints_na_discordant_counts_for_repeated_cases(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    summary = local / "summary.tsv"
    lines = summary.read_text().splitlines()
    summary.write_text("\n".join(lines + lines[1:]) + "\n")
    out_dir = tmp_path / "out"
    main(["score", "--label", "A", str(local), "--label", "D", str(cloud), "--out", str(out_dir)])
    out = capsys.readouterr().out
    assert "D - A: 15 cases, D only n/a, A only n/a, exact McNemar n/a (repeated cases)" in out
    with (out_dir / "paired.tsv").open() as handle:
        [pair] = list(csv.DictReader(handle, delimiter="\t"))
    assert (pair["only_first"], pair["only_second"], pair["mcnemar_p"]) == ("", "", "")


def test_score_applies_holm_across_three_configs(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    out_dir = tmp_path / "out"
    main(["score", "--label", "A", str(local), "--label", "D", str(cloud), "--label", "E", str(cloud),
          "--out", str(out_dir)])
    out = capsys.readouterr().out
    assert "D - A: 15 cases, D only 9, A only 0, exact McNemar p = 0.0039 (Holm 0.012)" in out
    assert "E - D: 15 cases, E only 0, D only 0, exact McNemar p = 1 (Holm 1)" in out
    with (out_dir / "paired.tsv").open() as handle:
        pairs = list(csv.DictReader(handle, delimiter="\t"))
    assert [(p["first"], p["second"]) for p in pairs] == [("A", "D"), ("A", "E"), ("D", "E")]
    assert float(pairs[0]["holm_p"]) == pytest.approx(3 * 0.00390625)


def test_score_prints_the_healthy_false_alarm_line(tmp_path, capsys):
    run = tmp_path / "run"
    run.mkdir()
    write_tsv(run / "summary.tsv", [
        ("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1"),
        ("H", "COMPLETED", "NONE|NONE", "UNKNOWN|UNKNOWN", "r1-H"),
        ("H", "COMPLETED", "NONE|NONE", TRUTH, "r2-H"),
    ])
    main(["score", "--label", "A", str(run)])
    out = capsys.readouterr().out
    assert "1 fault cases, 2 healthy" in out
    assert "A  healthy  1/2   false alarms 50%  [9%, 91%]" in out


def error_of(argv, capsys):
    with pytest.raises(SystemExit) as raised:
        main(argv)
    return raised.value


def test_score_refuses_two_inputs_with_the_same_label(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    error = error_of(["score", "--label", "A", str(local), "--label", "A", str(cloud)], capsys)
    assert "label A" in str(error.code)


def test_score_refuses_labels_with_the_same_copy_name(tmp_path, capsys):
    local, cloud = two_runs(tmp_path)
    error = error_of(["score", "--label", "A/B", str(local), "--label", "A B", str(cloud), "--out",
                      str(tmp_path / "out")], capsys)
    assert "A_B-summary.tsv" in str(error.code)


def test_score_reports_a_missing_summary_without_a_traceback(tmp_path, capsys):
    error = error_of(["score", "--label", "A", str(tmp_path / "nowhere")], capsys)
    assert "summary.tsv" in str(error.code)


def test_score_reports_a_bad_tsv_without_a_traceback(tmp_path, capsys):
    (tmp_path / "summary.tsv").write_text("fault\tmode\nF1\tdecision\n")
    error = error_of(["score", str(tmp_path)], capsys)
    assert "truth" in str(error.code)


def test_score_reports_configs_without_a_shared_case(tmp_path, capsys):
    first, second = tmp_path / "a", tmp_path / "b"
    first.mkdir()
    second.mkdir()
    write_tsv(first / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")])
    write_tsv(second / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r2-F1")])
    error = error_of(["score", "--label", "A", str(first), "--label", "D", str(second)], capsys)
    assert "share no fault case" in str(error.code)


def test_score_rejects_iterations_below_one_at_parse_time(tmp_path, capsys):
    local, _ = two_runs(tmp_path)
    error = error_of(["score", str(local), "--iterations", "0"], capsys)
    assert error.code == 2
    assert "--iterations" in capsys.readouterr().err


def test_score_reports_candidate_recall_only_where_it_applies(tmp_path, capsys):
    from test_scoring import recall_rows, write_candidates_tsv

    rules, plain = tmp_path / "rules", tmp_path / "plain"
    rules.mkdir()
    plain.mkdir()
    write_candidates_tsv(rules / "summary.tsv", "rules", "plain", recall_rows())
    write_candidates_tsv(plain / "summary.tsv", "local", "plain", recall_rows())
    out_dir = tmp_path / "out"
    main(["score", "--label", "0", str(rules), "--label", "Q", str(plain), "--iterations", "50", "--out", str(out_dir)])
    out = capsys.readouterr().out
    assert "0  F1       1/3   AC@1  33%  [6%, 79%]  recall 67%" in out
    assert "  coverage 75%  selective 33%  recall 50%" in out
    assert "recall" not in "".join(line for line in out.splitlines() if line.startswith("Q "))
    with (out_dir / "scores.tsv").open() as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        assert reader.fieldnames[-1] == "candidate_recall"
        scores = {(s["config"], s["fault"]): s["candidate_recall"] for s in reader}
    assert scores["0", "F1"] == "0.6667"
    assert scores["0", "pooled"] == "0.5000"
    assert scores["0", "healthy"] == ""
    assert scores["Q", "F1"] == ""


def test_score_service_only_scores_the_service_with_a_wilson_ci(tmp_path, capsys):
    run = tmp_path / "rules"
    run.mkdir()
    truth = "checkoutservice|RESOURCE_EXHAUSTION"
    write_tsv(run / "summary.tsv", [
        ("cpu", "COMPLETED", truth, "checkoutservice|DEPENDENCY_LATENCY", "a"),
        ("cpu", "COMPLETED", truth, "frontend|RESOURCE_EXHAUSTION", "b"),
        ("delay", "COMPLETED", "currencyservice|DEPENDENCY_LATENCY", "currencyservice|DEPENDENCY_LATENCY", "c"),
    ])
    assert main(["score", "--label", "0", str(run)]) == 0
    default = capsys.readouterr().out
    assert "0  pooled   1/3   AC@1  33%" in default
    assert "service" not in default
    assert main(["score", "--service-only", "--label", "0", str(run)]) == 0
    out = capsys.readouterr().out
    assert "0: route cloud, mode decision, view -; 3 fault cases, 0 healthy; AC@1 on the service only" in out
    assert "0  pooled   2/3   AC@1  67%  [21%, 94%]" in out
    assert "0  cpu      1/2   AC@1  50%" in out


def ranked_run(tmp_path):
    run = tmp_path / "ranked"
    run.mkdir()
    write_ranked_tsv(run / "summary.tsv", [
        ("cpu", "COMPLETED", RANK_TRUTH, "frontend|UNKNOWN", "a",
         pairs("frontend", "checkoutservice", "cartservice", "redis", "adservice")),
        ("delay", "TIMED_OUT", RANK_TRUTH, "none", "b", pairs("checkoutservice")),
        ("none", "COMPLETED", "NONE|NONE", "UNKNOWN|UNKNOWN", "h", ""),
    ])
    return run


def topology(tmp_path, *services):
    path = tmp_path / "topology.json"
    path.write_text('{"services": [' + ", ".join(f'"{s}"' for s in services) + "]}")
    return path


def test_score_prints_the_ranked_lines_and_the_chance_levels(tmp_path, capsys):
    run = ranked_run(tmp_path)
    assert main(["score", "--service-only", "--topology", str(RE2_OB), "--label", "B", str(run)]) == 0
    out = capsys.readouterr().out
    assert "B  ranked cpu      1  AC@1 0/1 0% [0%, 79%]  AC@3 1/1 100% [21%, 100%]  AC@5 1/1 100% [21%, 100%]  " \
           "Avg@5 80%  mean list 5.00" in out
    assert "B  ranked delay    1  AC@1 0/1 0% [0%, 79%]  AC@3 0/1 0% [0%, 79%]  AC@5 0/1 0% [0%, 79%]  " \
           "Avg@5 0%  mean list 0.00" in out
    assert "B  ranked pooled   2  AC@1 0/2 0% [0%, 66%]  AC@3 1/2 50% [9%, 91%]  AC@5 1/2 50% [9%, 91%]  " \
           "Avg@5 40%  mean list 2.50" in out
    assert f"chance with 11 services ({RE2_OB}): AC@1 9.09%  AC@3 27.27%  AC@5 45.45%  Avg@5 27.27%" in out


def test_score_prints_chance_capped_at_one_with_three_services(tmp_path, capsys):
    run = ranked_run(tmp_path)
    path = topology(tmp_path, "a", "b", "c")
    main(["score", "--service-only", "--topology", str(path), "--label", "B", str(run)])
    assert f"chance with 3 services ({path}): AC@1 33.33%  AC@3 100.00%  AC@5 100.00%  Avg@5 80.00%" \
           in capsys.readouterr().out


def test_score_writes_ranked_tsv_next_to_scores_tsv(tmp_path, capsys):
    run = ranked_run(tmp_path)
    out_dir = tmp_path / "out"
    main(["score", "--service-only", "--topology", str(RE2_OB), "--label", "B", str(run), "--out", str(out_dir)])
    printed = capsys.readouterr().out
    assert (out_dir / "report.txt").read_text() == printed
    with (out_dir / "ranked.tsv").open() as handle:
        rows = {row["fault"]: row for row in csv.DictReader(handle, delimiter="\t")}
    assert list(rows) == ["cpu", "delay", "pooled"]
    pooled = rows["pooled"]
    assert (pooled["config"], pooled["n"]) == ("B", "2")
    assert [pooled[f"hits{k}"] for k in range(1, 6)] == ["0", "1", "1", "1", "1"]
    assert (pooled["ac1"], pooled["ac3"], pooled["ac5"], pooled["avg5"]) == ("0.0000", "0.5000", "0.5000", "0.4000")
    assert (pooled["ac3_low"], pooled["ac3_high"]) == ("0.0945", "0.9055")
    assert pooled["mean_length"] == "2.5000"
    assert pooled["services"] == "11"
    assert (pooled["chance_ac1"], pooled["chance_ac3"], pooled["chance_ac5"], pooled["chance_avg5"]) \
        == ("0.0909", "0.2727", "0.4545", "0.2727")


def test_score_refuses_topology_without_service_only(tmp_path, capsys):
    run = ranked_run(tmp_path)
    out_dir = tmp_path / "out"
    error = error_of(["score", "--topology", str(RE2_OB), "--label", "B", str(run), "--out", str(out_dir)], capsys)
    assert "--topology" in str(error.code) and "--service-only" in str(error.code)
    assert not out_dir.exists()
    assert capsys.readouterr().out == ""


def test_score_reports_a_malformed_topology_without_a_traceback(tmp_path, capsys):
    run = ranked_run(tmp_path)
    path = tmp_path / "topology.json"
    path.write_text("{not json")
    out_dir = tmp_path / "out"
    error = error_of(["score", "--service-only", "--topology", str(path), "--label", "B", str(run), "--out",
                      str(out_dir)], capsys)
    assert str(error.code).startswith("score: ") and "topology.json" in str(error.code)
    assert not out_dir.exists()


def committed_args():
    with (COMMITTED / "inputs.tsv").open() as handle:
        labels = [row["label"] for row in csv.DictReader(handle, delimiter="\t")]
    args = ["score", "--service-only"]
    for label in labels:
        args += ["--label", label, str(COMMITTED / "inputs" / f"{label}-summary.tsv")]
    return args


def test_without_topology_the_committed_result_regenerates_byte_for_byte(tmp_path, capsys):
    out_dir = tmp_path / "out"
    assert main(committed_args() + ["--out", str(out_dir)]) == 0
    assert capsys.readouterr().out == (COMMITTED / "report.txt").read_text()
    for name in ("report.txt", "scores.tsv", "paired.tsv"):
        assert (out_dir / name).read_bytes() == (COMMITTED / name).read_bytes(), name
    assert sorted(p.name for p in out_dir.iterdir()) == ["inputs", "inputs.tsv", "paired.tsv", "report.txt",
                                                         "scores.tsv"]


def test_topology_changes_neither_scores_nor_paired_nor_inputs(tmp_path, capsys):
    plain, flagged = tmp_path / "plain", tmp_path / "flagged"
    main(committed_args() + ["--out", str(plain)])
    main(committed_args() + ["--topology", str(RE2_OB), "--out", str(flagged)])
    capsys.readouterr()
    for name in ("scores.tsv", "paired.tsv", "inputs.tsv"):
        assert (flagged / name).read_bytes() == (plain / name).read_bytes(), name
    assert (flagged / "ranked.tsv").exists()
    # The report only gains lines: dropping the ranked and chance lines gives the plain report back.
    kept = [line for line in (flagged / "report.txt").read_text().splitlines()
            if "  ranked " not in line and not line.startswith("chance with ")]
    assert kept == (plain / "report.txt").read_text().splitlines()


def test_ranked_ac1_equals_the_committed_ac1_on_every_committed_input():
    args = committed_args()
    for label, path in zip(args[3::3], args[4::3]):
        rows = read_summary(path, label=label, service_only=True)
        [score] = summarize(rows)
        [ranks] = summarize_ranked(rows)
        assert ranks.pooled.hits[0] == score.pooled.correct, label
