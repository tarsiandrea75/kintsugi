"""Tests of the BARO port (ADR-024, openspec/specs/evals REQ-12): its ranking against the goldens of the pinned original
on `tests/fixtures/baro/`, and `baro-rcaeval` on synthetic trees named as the converter's fixture is. No real data
is read."""

import csv
import json
import math
import shutil
from pathlib import Path

import pytest

from kintsugi_evals.cli import main
from kintsugi_evals.rcaeval import baro, convert
from kintsugi_evals.scoring import CORRECT, MISS, read_summary

FIXTURES = Path(__file__).parent / "fixtures"
RCAEVAL = FIXTURES / "rcaeval"
CASES = FIXTURES / "baro" / "cases"
GOLDENS = json.loads((FIXTURES / "baro" / "goldens.json").read_text(encoding="utf-8"))
TOLERANCE = 1e-9
INJECT = 1700000000


def case_dir(tmp_path, header, rows, inject=INJECT):
    directory = tmp_path / "case"
    directory.mkdir()
    with (directory / "simple_metrics.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)
    (directory / "inject_time.txt").write_text(f"{inject}\n", encoding="utf-8")
    return directory


def summary_rows(out):
    with (out / "summary.tsv").open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


@pytest.mark.parametrize("name", sorted(name for name, golden in GOLDENS.items() if "error" not in golden))
def test_the_ranking_and_scores_equal_the_originals(name):
    golden = GOLDENS[name]
    ranking = baro.rank_case(CASES / name)
    assert ranking.columns == golden["ranks"]
    assert ranking.services == golden["services"]
    assert len(ranking.scores) == len(golden["scores"])
    for ours, theirs in zip(ranking.scores, golden["scores"]):
        assert math.isnan(ours) == math.isnan(theirs)
        assert math.isnan(ours) or abs(ours - theirs) <= TOLERANCE


@pytest.mark.parametrize("name", sorted(name for name, golden in GOLDENS.items() if "error" in golden))
def test_a_case_the_original_refuses_is_refused(name):
    with pytest.raises(ValueError):
        baro.rank_case(CASES / name)


def test_the_goldens_cover_the_branches_the_adr_names():
    assert {"ordinary", "mem_column", "constant_normal_only", "constant_anomalous_only", "zero_iqr", "nan_values", "ties",
            "service_names", "window", "sample_at_injection"} <= set(GOLDENS)
    assert all((CASES / name / "simple_metrics.csv").is_file() for name in GOLDENS)


def test_a_score_is_the_largest_scaled_anomalous_value():
    normal = [1.0, 2.0, 3.0, 4.0, 5.0]  # median 3, quartiles 2 and 4, so scale 2
    assert baro.column_score(normal, [3.0, 11.0, 7.0]) == 4.0


def test_a_near_zero_scale_becomes_one_as_in_the_original():
    assert baro.column_score([0.0, 1e-17, 2e-17, 3e-17, 4e-17], [0.5]) == pytest.approx(0.5)
    assert baro.column_score([0.0, 3e-15, 6e-15, 9e-15, 12e-15], [6e-15 + 6e-15]) == pytest.approx(1.0)


def test_nan_decides_a_score_only_in_the_first_position():
    normal = [1.0, 2.0, 3.0, 4.0, 5.0]
    assert math.isnan(baro.column_score(normal, [math.nan, 11.0]))
    assert baro.column_score(normal, [11.0, math.nan]) == 4.0


def test_a_later_nan_makes_a_column_not_constant():
    assert not baro.is_constant([1.0, math.nan, 1.0])
    assert not baro.is_constant([math.nan, 1.0])
    assert not baro.is_constant([math.nan])
    assert baro.is_constant([1.0, 1.0])


def test_a_service_is_the_text_before_the_first_underscore_without_db():
    assert baro.service_of("carts-db_mem") == "carts"
    assert baro.service_of("frontend-external_latency-50") == "frontend-external"
    assert baro.service_of("redis_cpu") == "redis"
    assert baro.service_of("plain") == "plain"


def test_a_repeated_metric_header_is_refused(tmp_path):
    directory = case_dir(tmp_path, ["time", "a_cpu", "a_cpu"], [[INJECT - 1, 1, 2], [INJECT, 3, 4]])
    with pytest.raises(ValueError, match="a_cpu"):
        baro.rank_case(directory)


def test_a_case_without_a_time_column_is_refused(tmp_path):
    with pytest.raises(ValueError, match="time"):
        baro.rank_case(case_dir(tmp_path, ["a_cpu"], [[1], [2]]))


def test_a_word_in_a_metric_column_is_refused(tmp_path):
    with pytest.raises(ValueError):
        baro.rank_case(case_dir(tmp_path, ["time", "a_cpu"], [[INJECT - 1, 1], [INJECT, "high"]]))


def case_tree(root, cases):
    """`<root>/RE2-OB/<case>/1/` for each case name -> directory holding its two files."""
    for case, directory in cases.items():
        shutil.copytree(directory, root / "RE2-OB" / case / "1")
    return root


def run_baro(root, out):
    assert main(["baro-rcaeval", "--root", str(root), "--system", "re2-ob", "--rep", "1", "--out", str(out)]) == 0
    return out


@pytest.fixture
def tree(tmp_path):
    """The converter fixture's two case names: BARO ranks the first's own service first, and another for the second."""
    times = [INJECT - 40, INJECT - 30, INJECT - 20, INJECT - 10, INJECT, INJECT + 10]
    jump = case_dir(tmp_path, ["time", "frontend_cpu", "checkoutservice_cpu"],
                    [[t, 1 + i % 3, 2 + i % 3 + (90 if t >= INJECT else 0)] for i, t in enumerate(times)])
    return case_tree(tmp_path / "root", {"checkoutservice_cpu": jump, "currencyservice_delay": CASES / "ordinary"})


@pytest.fixture
def ran(tree, tmp_path):
    return run_baro(tree, tmp_path / "baro")


def test_the_summary_has_one_answer_per_case_in_the_scorers_columns(ran):
    rows = summary_rows(ran)
    assert len(rows) == 2
    for row in rows:
        assert (row["route"], row["status"], row["split"], row["sample"]) == ("baro", "COMPLETED", "dev", "1")
        assert row["reported"].endswith("|UNKNOWN")
        assert row["candidates"].split(";")[0] == row["reported"]
        assert (row["verdict"], row["view"], row["report"]) == ("-", "-", "-")
    first = next(row for row in rows if row["fault"] == "cpu")
    assert first["reported"] == "checkoutservice|UNKNOWN"
    assert first["candidates"] == "checkoutservice|UNKNOWN;frontend|UNKNOWN"


def test_the_candidates_hold_the_top_five_services(tmp_path):
    rows = summary_rows(run_baro(case_tree(tmp_path / "root", {"redis_cpu": CASES / "service_names"}), tmp_path / "out"))
    assert rows[0]["candidates"] == ("frontend-external|UNKNOWN;frontend|UNKNOWN;redis|UNKNOWN;carts|UNKNOWN;"
                                     "catalogue|UNKNOWN")


def test_the_summary_pairs_with_the_converters_recordings(ran, tmp_path):
    convert.convert(RCAEVAL, "re2-ob", 1, tmp_path / "recordings")
    with (tmp_path / "recordings" / "recordings.tsv").open(newline="") as handle:
        manifest = {Path(r["file"]).stem: r for r in csv.DictReader(handle, delimiter="\t")}
    rows = summary_rows(ran)
    assert {row["recording"] for row in rows} == set(manifest)
    for row in rows:
        recorded = manifest[row["recording"]]
        assert (row["fault"], row["mode"], row["truth"], row["split"]) == (
            recorded["fault"], recorded["mode"], recorded["truth"], recorded["split"])


def test_the_recording_id_is_the_committed_results_id():
    # evals/results/2026-11-02-a-deadline-re2ob-dev/inputs/recordings.tsv, the first row.
    assert baro.recording_of("re2-ob", "checkoutservice_cpu", 1) == "4490c7f4fb1d2bf7"


def test_the_scorer_reads_the_summary_on_the_service_only(ran, capsys):
    outcomes = {row.fault: row.outcome for row in read_summary(ran, label="BARO", service_only=True)}
    assert outcomes == {"cpu": CORRECT, "delay": MISS}
    assert main(["score", "--service-only", "--label", "BARO", str(ran)]) == 0
    assert "BARO  pooled   1/2 " in capsys.readouterr().out


def test_renaming_a_case_directory_leaves_its_ranking_unchanged(ran, tree, tmp_path):
    original = tree / "RE2-OB" / "checkoutservice_cpu" / "1"
    assert baro.rank_case(original) == baro.rank_case(shutil.copytree(original, tmp_path / "anything" / "1"))

    renamed = case_tree(tmp_path / "renamed", {"emailservice_mem": tree / "RE2-OB" / "checkoutservice_cpu" / "1",
                                               "paymentservice_loss": tree / "RE2-OB" / "currencyservice_delay" / "1"})
    answers = {row["truth"].split("|")[0]: row for row in summary_rows(run_baro(renamed, tmp_path / "out"))}
    before = {row["truth"].split("|")[0]: row for row in summary_rows(ran)}
    assert answers["emailservice"]["fault"] == "mem"
    for new, old in (("emailservice", "checkoutservice"), ("paymentservice", "currencyservice")):
        assert (answers[new]["reported"], answers[new]["candidates"]) == (before[old]["reported"], before[old]["candidates"])


def test_two_runs_write_the_same_bytes(ran, tree, tmp_path):
    assert (run_baro(tree, tmp_path / "again") / "summary.tsv").read_bytes() == (ran / "summary.tsv").read_bytes()


@pytest.mark.parametrize("system, rep", [("re2-ob", 2), ("re2-ob", 3), ("re2-ss", 1), ("re2-ss", 2)])
def test_a_holdout_is_refused_before_anything_is_read(system, rep, tmp_path, monkeypatch):
    opened = []
    real_open = Path.open
    monkeypatch.setattr(Path, "open", lambda self, *a, **k: opened.append(self) or real_open(self, *a, **k))
    out = tmp_path / "refused"
    with pytest.raises(SystemExit, match="holdout"):
        main(["baro-rcaeval", "--root", str(RCAEVAL), "--system", system, "--rep", str(rep), "--out", str(out)])
    assert not out.exists()
    assert not [path for path in opened if "RE2" in str(path)]


def test_a_repetition_that_does_not_exist_is_refused(tmp_path):
    with pytest.raises(SystemExit, match="repetition 4"):
        main(["baro-rcaeval", "--root", str(RCAEVAL), "--system", "re2-ob", "--rep", "4",
              "--out", str(tmp_path / "out")])


def test_a_case_with_no_ranking_fails_the_run_and_writes_nothing(tmp_path):
    root = case_tree(tmp_path / "root", {"checkoutservice_cpu": CASES / "no_common_column"})
    out = tmp_path / "out"
    with pytest.raises(SystemExit, match="checkoutservice_cpu"):
        main(["baro-rcaeval", "--root", str(root), "--system", "re2-ob", "--rep", "1", "--out", str(out)])
    assert not out.exists()
