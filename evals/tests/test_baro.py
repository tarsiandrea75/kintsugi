"""Tests of the BARO port (ADR-024, openspec/specs/evals REQ-12): its ranking against the goldens of the pinned original
on `tests/fixtures/baro/`, and `baro-rcaeval` on synthetic trees named as the converter's fixture is. No real data
is read."""

import csv
import importlib.util
import json
import math
import shutil
import sys
from pathlib import Path

import pytest

from kintsugi_evals.cli import main
from kintsugi_evals.rcaeval import baro, convert
from kintsugi_evals.scoring import CORRECT, MISS, read_service_count, read_summary

FIXTURES = Path(__file__).parent / "fixtures"
RCAEVAL = FIXTURES / "rcaeval"
CASES = FIXTURES / "baro" / "cases"
GOLDENS = json.loads((FIXTURES / "baro" / "goldens.json").read_text(encoding="utf-8"))
TOLERANCE = 1e-9
INJECT = 1700000000


def case_dir(tmp_path, header, rows, inject=INJECT, name="case"):
    directory = tmp_path / name
    directory.mkdir(parents=True)
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
            "service_names", "sample_at_injection", "more_than_600_rows", "more_than_600_rows_unsorted", "few_normal_rows",
            "few_anomalous_rows", "gaps_across_injection", "gap_at_the_kept_edge", "leading_nan", "infinities",
            "latency_columns", "redis_column", "time_cells", "word_in_a_column", "train_ticket_columns"} <= set(GOLDENS)
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
    assert baro.service_of("ts-ui-dashboard_latency") == "ts-ui-dashboard"
    assert baro.service_of("ts-order-db_mem") == "ts-order"


def test_re2_tt_keeps_the_columns_outside_ts_as_the_original_does():
    # main.py:246 keeps only `ts-` columns on an `mm-tt` or `torai-TT` path; RE2-TT's is data/RE2/RE2-TT.
    golden = GOLDENS["train_ticket_columns"]
    assert "rabbitmq" in golden["services"] and "ts-ui-dashboard" in golden["services"]
    assert baro.rank_case(CASES / "train_ticket_columns").services == golden["services"]


def scores_of(directory):
    ranking = baro.rank_case(directory)
    return dict(zip(ranking.columns, ranking.scores))


NORMAL_ROWS = [[INJECT - 5 + k, value] for k, value in enumerate([1, 2, 3, 4, 5])]  # median 3, quartiles 2 and 4


def test_a_gap_is_filled_forward_across_the_injection(tmp_path):
    directory = case_dir(tmp_path, ["time", "a_cpu"], NORMAL_ROWS + [[INJECT, ""], [INJECT + 1, 3]])
    assert scores_of(directory) == {"a_cpu": 1.0}  # the first anomalous value is the normal segment's last, 5


def test_what_is_empty_before_a_first_value_becomes_zero(tmp_path):
    directory = case_dir(tmp_path, ["time", "a_cpu"], [[INJECT - 5, ""]] + NORMAL_ROWS[1:] + [[INJECT, 3], [INJECT + 1, 11]])
    assert scores_of(directory) == {"a_cpu": 4.0}  # 0, 2, 3, 4, 5: the median and the quartiles are as with a 1


def test_an_infinity_is_filled_as_an_empty_cell_is(tmp_path):
    directory = case_dir(tmp_path, ["time", "a_cpu"], NORMAL_ROWS + [[INJECT, "inf"], [INJECT + 1, 3]])
    assert scores_of(directory) == {"a_cpu": 1.0}
    other = tmp_path / "other"
    other.mkdir()
    directory = case_dir(other, ["time", "a_cpu"], NORMAL_ROWS + [[INJECT, "-inf"], [INJECT + 1, 3]])
    assert scores_of(directory) == {"a_cpu": 1.0}


def test_an_empty_time_is_filled_too(tmp_path):
    # The cell after the last normal time takes that time, so its row is normal and 90 is not an anomalous value.
    directory = case_dir(tmp_path, ["time", "a_cpu"], NORMAL_ROWS + [["", 90], [INJECT + 1, 3], [INJECT + 2, 11]])
    assert scores_of(directory) == {"a_cpu": 3.0}  # 1, 2, 3, 4, 5, 90: median 3.5, quartiles 2.25 and 4.75; then 3, 11


def test_a_latency_50_column_is_dropped_and_a_latency_90_is_renamed(tmp_path):
    rows = [[INJECT - 3, 1, 1, 1], [INJECT - 2, 2, 2, 2], [INJECT - 1, 3, 3, 3], [INJECT, 9, 9, 9], [INJECT + 1, 2, 2, 2]]
    directory = case_dir(tmp_path, ["time", "a_latency-50", "b_latency-90", "c_latency"], rows)
    assert baro.rank_case(directory).columns == ["b_latency", "c_latency"]


def many_rows(normal, anomalous):
    return [[INJECT - normal + k, k] for k in range(normal)] + [[INJECT + k, k] for k in range(anomalous)]


def test_only_the_last_600_normal_rows_and_the_first_600_anomalous_rows_count(tmp_path):
    directory = case_dir(tmp_path, ["time", "a_cpu"], many_rows(700, 700))
    # Normal 100..699: median 399.5, quartiles at 249.75 and 549.25. Anomalous 0..599: the largest is 599.
    assert scores_of(directory)["a_cpu"] == pytest.approx((599 - 399.5) / 299.5)


def test_a_side_with_fewer_rows_keeps_them_all(tmp_path):
    directory = case_dir(tmp_path, ["time", "a_cpu"], many_rows(5, 700))
    # Normal 0..4: median 2, quartiles 1 and 3. Anomalous 0..599.
    assert scores_of(directory)["a_cpu"] == pytest.approx((599 - 2) / 2)


def test_the_kept_rows_are_counted_in_file_order_not_by_time(tmp_path):
    rows = many_rows(700, 2)
    rows[:700] = list(reversed(rows[:700]))  # the file's last 600 normal rows now hold the earliest times, values 0..599
    directory = case_dir(tmp_path, ["time", "a_cpu"], rows)
    assert scores_of(directory)["a_cpu"] == pytest.approx((1 - 299.5) / 299.5)


def test_a_gap_at_the_edge_is_filled_from_a_row_that_is_not_kept(tmp_path):
    rows = many_rows(601, 2)
    rows[0][1] = 5000
    rows[1][1] = ""  # the first kept row, which takes the 5000 of the row before it
    directory = case_dir(tmp_path, ["time", "a_cpu"], rows)
    values = sorted([5000] + list(range(2, 601)))
    median = (values[299] + values[300]) / 2
    first_quartile = values[149] + 0.75 * (values[150] - values[149])
    third_quartile = values[449] + 0.25 * (values[450] - values[449])
    assert scores_of(directory)["a_cpu"] == pytest.approx((1 - median) / (third_quartile - first_quartile))


def test_only_the_first_line_of_the_injection_file_counts(tmp_path):
    directory = case_dir(tmp_path, ["time", "a_cpu"], NORMAL_ROWS + [[INJECT, 3], [INJECT + 1, 11]])
    (directory / "inject_time.txt").write_text(f"{INJECT}\nsomething else\n", encoding="utf-8")
    assert scores_of(directory) == {"a_cpu": 4.0}


def test_a_row_longer_than_the_header_is_refused(tmp_path):
    with pytest.raises(ValueError, match="a row of 3 cells"):
        baro.rank_case(case_dir(tmp_path, ["time", "a_cpu"], [[INJECT - 1, 1, 9], [INJECT, 3]]))


def test_a_rename_onto_an_existing_column_is_refused(tmp_path):
    rows = [[INJECT - 1, 1, 2], [INJECT, 3, 4]]
    with pytest.raises(ValueError, match="a_latency"):
        baro.rank_case(case_dir(tmp_path, ["time", "a_latency", "a_latency-90"], rows))


def test_a_repeated_dropped_header_is_refused(tmp_path):
    # pandas would read the second as `a_latency-50.1`, which the published pipeline then keeps as a metric.
    rows = [[INJECT - 1, 1, 2], [INJECT, 3, 4]]
    with pytest.raises(ValueError, match="a_latency-50"):
        baro.rank_case(case_dir(tmp_path, ["time", "a_latency-50", "a_latency-50"], rows))


def test_a_third_time_column_is_refused(tmp_path):
    rows = [[INJECT - 1, 1, INJECT - 1, INJECT - 1], [INJECT, 3, INJECT, INJECT]]
    with pytest.raises(ValueError, match="time"):
        baro.rank_case(case_dir(tmp_path, ["time", "a_cpu", "time", "time"], rows))


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


TT_COLUMNS = ("ts-auth-service_cpu", "ts-order-service_cpu", "ts-route-service_cpu")


def tt_case(root, case, rep, jumps, columns=TT_COLUMNS):
    """`<root>/RE2-TT/<case>/<rep>/`, where the column `jumps` moves at the injection and the others do not."""
    times = [INJECT - 40, INJECT - 30, INJECT - 20, INJECT - 10, INJECT, INJECT + 10]
    rows = [[t] + [1 + i % 3 + (90 if t >= INJECT and name == jumps else 0) for name in columns]
            for i, t in enumerate(times)]
    return case_dir(root, ["time", *columns], rows, name=f"RE2-TT/{case}/{rep}")


TT_FLAG = ["--allow-holdout", "TT"]


def run_tt(root, out):
    assert main(["baro-rcaeval", "--root", str(root), "--system", "re2-tt", "--rep", "all", "--out", str(out),
                 *TT_FLAG]) == 0
    return out


@pytest.fixture
def tt_tree(tmp_path):
    """Two cases of three repetitions. In repetition 3 of the first its own service is passed over, and a fourth service
    appears that no other repetition has."""
    root = tmp_path / "root"
    for rep in (1, 2):
        tt_case(root, "ts-auth-service_cpu", rep, "ts-auth-service_cpu")
    tt_case(root, "ts-auth-service_cpu", 3, "ts-train-service_mem", TT_COLUMNS + ("ts-train-service_mem",))
    for rep in (1, 2, 3):
        tt_case(root, "ts-order-service_delay", rep, "ts-order-service_cpu")
    return root


def test_rep_all_writes_every_repetitions_rows_into_one_summary(tt_tree, tmp_path):
    rows = summary_rows(run_tt(tt_tree, tmp_path / "out"))
    assert len(rows) == 6
    assert {row["split"] for row in rows} == {"reference"}
    assert [row["reported"] for row in rows if row["fault"] == "cpu"] == [
        "ts-auth-service|UNKNOWN", "ts-auth-service|UNKNOWN", "ts-train-service|UNKNOWN"]
    assert {row["truth"] for row in rows} == {"ts-auth-service|RESOURCE_EXHAUSTION", "ts-order-service|DEPENDENCY_LATENCY"}


def test_rep_all_keeps_the_case_ids_unique_across_repetitions(tt_tree, tmp_path):
    rows = summary_rows(run_tt(tt_tree, tmp_path / "out"))
    assert len({row["recording"] for row in rows}) == 6
    for rep in (1, 2, 3):
        assert baro.recording_of("re2-tt", "ts-order-service_delay", rep) in {row["recording"] for row in rows}


def test_rep_all_reads_a_repetition_beyond_the_third(tt_tree, tmp_path):
    tt_case(tt_tree, "ts-auth-service_cpu", 4, "ts-route-service_cpu")
    assert len(summary_rows(run_tt(tt_tree, tmp_path / "out"))) == 7


def test_the_scorer_reads_rep_all_on_the_topology_the_run_wrote(tt_tree, tmp_path, capsys):
    out = run_tt(tt_tree, tmp_path / "out")
    assert len(read_summary(out, label="BARO", service_only=True)) == 6
    capsys.readouterr()
    assert main(["score", "--service-only", "--topology", str(out / "services.json"), "--label", "BARO", str(out)]) == 0
    report = capsys.readouterr().out
    assert "BARO  pooled   5/6 " in report
    assert "AC@1 25.00%" in report  # chance on the four services of the run


def test_services_json_is_the_sorted_union_over_the_run(tt_tree, tmp_path):
    out = run_tt(tt_tree, tmp_path / "out")
    written = json.loads((out / "services.json").read_text(encoding="utf-8"))
    assert written == {"services": ["ts-auth-service", "ts-order-service", "ts-route-service", "ts-train-service"]}
    assert read_service_count(out / "services.json") == 4


def test_services_json_holds_every_service_ranked_not_only_the_top_five(tmp_path):
    columns = tuple(f"ts-s{k}-service_cpu" for k in range(7))
    tt_case(tmp_path, "ts-s0-service_cpu", 1, columns[0], columns)
    out = run_tt(tmp_path, tmp_path / "out")
    assert len(summary_rows(out)[0]["candidates"].split(";")) == 5
    assert read_service_count(out / "services.json") == 7


def test_rep_1_of_re2_ob_writes_services_json_too(ran):
    assert set(json.loads((ran / "services.json").read_text(encoding="utf-8"))["services"]) >= {"checkoutservice", "frontend"}


def test_a_case_that_fails_in_any_repetition_writes_nothing(tt_tree, tmp_path):
    (tt_tree / "RE2-TT" / "ts-order-service_delay" / "3" / "inject_time.txt").unlink()
    out = tmp_path / "out"
    with pytest.raises(SystemExit, match="inject_time"):
        main(["baro-rcaeval", "--root", str(tt_tree), "--system", "re2-tt", "--rep", "all", "--out", str(out), *TT_FLAG])
    assert not out.exists()


def test_two_rep_all_runs_write_the_same_bytes(tt_tree, tmp_path):
    first, second = run_tt(tt_tree, tmp_path / "one"), run_tt(tt_tree, tmp_path / "two")
    for name in ("summary.tsv", "services.json"):
        assert (first / name).read_bytes() == (second / name).read_bytes()


@pytest.mark.parametrize("system, rep, message", [("re2-ob", "all", "holdout"), ("re2-ss", "all", "holdout"),
                                                  ("re2-tt", "1", "--rep all"), ("re2-tt", "3", "--rep all")])
def test_rep_all_only_on_re2_tt_and_re2_tt_only_whole(system, rep, message, tmp_path, monkeypatch):
    opened = []
    real_open = Path.open
    monkeypatch.setattr(Path, "open", lambda self, *a, **k: opened.append(self) or real_open(self, *a, **k))
    out = tmp_path / "refused"
    with pytest.raises(SystemExit, match=message):
        main(["baro-rcaeval", "--root", str(RCAEVAL), "--system", system, "--rep", rep, "--out", str(out),
              *(TT_FLAG if system == "re2-tt" else [])])
    assert not out.exists()
    assert not [path for path in opened if "RE2" in str(path)]


@pytest.mark.parametrize("rep", ["all", "1"])
def test_re2_tt_needs_allow_holdout_tt_before_anything_is_read(rep, tt_tree, tmp_path, monkeypatch):
    # ADR-032 Decision 1: RE2-TT is holdout TT whole, read only behind --allow-holdout TT, as the converter reads it.
    opened = []
    real_open = Path.open
    monkeypatch.setattr(Path, "open", lambda self, *a, **k: opened.append(self) or real_open(self, *a, **k))
    out = tmp_path / "refused"
    with pytest.raises(SystemExit, match="--allow-holdout TT"):
        main(["baro-rcaeval", "--root", str(tt_tree), "--system", "re2-tt", "--rep", rep, "--out", str(out)])
    assert not out.exists()
    assert not [path for path in opened if "RE2" in str(path)]


@pytest.mark.parametrize("system, rep", [("re2-ob", "1"), ("re2-ob", "2"), ("re2-ss", "1")])
def test_allow_holdout_tt_opens_no_other_system(system, rep, tmp_path):
    out = tmp_path / "refused"
    with pytest.raises(SystemExit, match="--allow-holdout TT"):
        main(["baro-rcaeval", "--root", str(RCAEVAL), "--system", system, "--rep", rep, "--out", str(out), *TT_FLAG])
    assert not out.exists()


def test_a_rep_that_is_neither_a_number_nor_all_is_refused(tmp_path, capsys):
    with pytest.raises(SystemExit):
        main(["baro-rcaeval", "--root", str(RCAEVAL), "--system", "re2-tt", "--rep", "some", "--out", str(tmp_path / "out"),
              *TT_FLAG])
    assert "--rep" in capsys.readouterr().err


def test_the_converter_refuses_re2_tt_without_its_holdout_flag(tmp_path):
    # ADR-032 Decision 1: RE2-TT is holdout TT whole, converted only under --allow-holdout TT.
    with pytest.raises(convert.HoldoutRefused, match="--allow-holdout TT"):
        convert.convert(tmp_path, "re2-tt", 1, tmp_path / "out")
    with pytest.raises(SystemExit, match="--allow-holdout TT"):
        main(["convert-rcaeval", "--root", str(tmp_path), "--system", "re2-tt", "--rep", "1", "--out", str(tmp_path / "out")])
    assert not (tmp_path / "out").exists()


@pytest.fixture
def compare_dev(monkeypatch):
    """`compare_dev.py`, whose decisions need no pandas: the original's answers are handed to it."""
    monkeypatch.setattr(sys, "path", list(sys.path))  # the script prepends its own directories
    spec = importlib.util.spec_from_file_location("compare_dev", FIXTURES / "baro" / "compare_dev.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def dev_tree(root, size=30):
    for k in range(size):
        shutil.copytree(CASES / "ordinary", root / f"service{k}_cpu" / "1")
    return root


def the_ports_answer(directory):
    ranking = baro.rank_case(directory)
    return {"ranks": ranking.columns, "scores": ranking.scores, "services": ranking.services}


def changing(case, change):
    """The port's own answer everywhere, and `change(answer)` for repetition 1 of the case named."""
    def original(directory):
        answer = the_ports_answer(directory)
        return change(answer) if directory.parent.name == case else answer
    return original


@pytest.fixture
def compared(compare_dev, tmp_path):
    def run(original, size=30):
        root = tmp_path / "RE2-OB"
        root.mkdir()
        out = tmp_path / "compare.tsv"
        code = compare_dev.compare_cases(dev_tree(root, size), out, original)
        with out.open(newline="", encoding="utf-8") as handle:
            return code, list(csv.DictReader(handle, delimiter="\t"))
    return run


def test_thirty_equal_cases_exit_0(compared):
    code, rows = compared(the_ports_answer)
    assert code == 0
    assert len(rows) == 30
    assert {row["outcome"] for row in rows} == {"compared"}


@pytest.mark.parametrize("size", [0, 29, 31])
def test_any_other_number_of_cases_exits_1(compared, size):
    assert compared(the_ports_answer, size)[0] == 1


def test_a_case_refused_only_by_the_original_exits_1(compared):
    code, rows = compared(changing("service3_cpu", lambda _: {"error": "ValueError"}))
    assert code == 1
    assert [row["outcome"] for row in rows].count("refused_by_original") == 1


def dev_tree_with_a_case_the_port_refuses(root):
    dev_tree(root)
    (root / "service3_cpu" / "1" / "simple_metrics.csv").write_text(f"time,a_cpu\n{INJECT - 1},high\n{INJECT},1\n",
                                                                    encoding="utf-8")
    return root


def test_a_case_refused_only_by_the_port_exits_1(compare_dev, tmp_path):
    root = dev_tree_with_a_case_the_port_refuses(tmp_path / "RE2-OB")
    out = tmp_path / "compare.tsv"

    def original(directory):
        if directory.parent.name == "service3_cpu":
            return {"ranks": ["a_cpu"], "scores": [1.0], "services": ["a"]}
        return the_ports_answer(directory)

    assert compare_dev.compare_cases(root, out, original) == 1
    assert "refused_by_port" in out.read_text(encoding="utf-8")


def test_a_case_refused_by_both_is_reported_and_is_not_a_difference(compare_dev, tmp_path, capsys):
    root = dev_tree_with_a_case_the_port_refuses(tmp_path / "RE2-OB")
    out = tmp_path / "compare.tsv"

    def original(directory):
        return {"error": "ValueError"} if directory.parent.name == "service3_cpu" else the_ports_answer(directory)

    assert compare_dev.compare_cases(root, out, original) == 0
    assert "refused_by_both" in out.read_text(encoding="utf-8")
    assert "1 refused by both" in capsys.readouterr().out


def test_no_case_compared_exits_1_even_with_thirty_refused_by_both(compare_dev, tmp_path):
    root = tmp_path / "RE2-OB"
    for k in range(30):
        directory = root / f"service{k}_cpu" / "1"
        directory.mkdir(parents=True)
        (directory / "simple_metrics.csv").write_text("a_cpu\n1\n", encoding="utf-8")
        (directory / "inject_time.txt").write_text(f"{INJECT}\n", encoding="utf-8")
    assert compare_dev.compare_cases(root, tmp_path / "compare.tsv", lambda _: {"error": "KeyError"}) == 1


def with_scores(answer, score):
    return {**answer, "scores": [score] * len(answer["scores"])}


@pytest.mark.parametrize("score", [math.nan, 1e6])
def test_a_score_beyond_the_tolerance_exits_1(compared, score):
    code, rows = compared(changing("service3_cpu", lambda answer: with_scores(answer, score)))
    assert code == 1
    assert float(next(row for row in rows if row["max_score_difference"] != "0.000e+00")["max_score_difference"]) > 1e-9


def test_a_column_only_one_side_ranks_exits_1(compared):
    def without_last(answer):
        return {"ranks": answer["ranks"][:-1], "scores": answer["scores"][:-1], "services": answer["services"]}

    assert compared(changing("service3_cpu", without_last))[0] == 1


def test_a_different_service_ranking_exits_1(compared):
    code, rows = compared(changing("service3_cpu", lambda answer: {**answer, "services": answer["services"][::-1]}))
    assert code == 1
    assert [row["same_services"] for row in rows].count("False") == 1


def tt_compare_tree(root, cases=30, reps=(1, 2, 3)):
    for k in range(cases):
        for rep in reps:
            shutil.copytree(CASES / "ordinary", root / f"ts-s{k}-service_cpu" / str(rep))
    return root


def compare_tt(compare_dev, tmp_path, original, cases=30, reps=(1, 2, 3)):
    root = tt_compare_tree(tmp_path / "RE2-TT", cases, reps)
    out = tmp_path / "compare.tsv"
    code = compare_dev.compare_cases(root, out, original, system="re2-tt")
    with out.open(newline="", encoding="utf-8") as handle:
        return code, list(csv.DictReader(handle, delimiter="\t"))


def test_ninety_equal_re2_tt_cases_exit_0_one_row_per_repetition(compare_dev, tmp_path):
    code, rows = compare_tt(compare_dev, tmp_path, the_ports_answer)
    assert code == 0
    assert len(rows) == 90
    assert len({row["recording"] for row in rows}) == 90
    assert baro.recording_of("re2-tt", "ts-s0-service_cpu", 3) in {row["recording"] for row in rows}


@pytest.mark.parametrize("cases, reps", [(30, (1, 2)), (29, (1, 2, 3)), (31, (1, 2, 3)), (30, (1, 2, 3, 4))])
def test_any_other_number_of_re2_tt_cases_exits_1(compare_dev, tmp_path, cases, reps):
    assert compare_tt(compare_dev, tmp_path, the_ports_answer, cases, reps)[0] == 1


def test_a_difference_in_the_third_repetition_of_re2_tt_exits_1(compare_dev, tmp_path):
    def original(directory):
        answer = the_ports_answer(directory)
        if directory.parent.name == "ts-s3-service_cpu" and directory.name == "3":
            return {**answer, "services": answer["services"][::-1]}
        return answer

    code, rows = compare_tt(compare_dev, tmp_path, original)
    assert code == 1
    assert [row["same_services"] for row in rows].count("False") == 1


@pytest.mark.parametrize("name", ["RE2-OB", "RE2-SS", "anything"])
def test_re2_tt_mode_refuses_any_other_directory_before_reading_it(compare_dev, tmp_path, name):
    # Every repetition of RE2-OB or RE2-SS under this mode would be a holdout read.
    root = tt_compare_tree(tmp_path / name)
    read = []
    with pytest.raises(ValueError, match="RE2-TT"):
        compare_dev.compare_cases(root, tmp_path / "compare.tsv", lambda directory: read.append(directory), system="re2-tt")
    assert not read


def test_re2_ob_mode_reads_only_repetition_1_even_with_the_others_present(compare_dev, tmp_path):
    root = dev_tree(tmp_path / "RE2-OB")
    shutil.copytree(CASES / "ordinary", root / "service3_cpu" / "2")
    read = []

    def original(directory):
        read.append(directory.name)
        return the_ports_answer(directory)

    assert compare_dev.compare_cases(root, tmp_path / "compare.tsv", original) == 0
    assert set(read) == {"1"}


@pytest.mark.parametrize("argv", [["--system", "re2-ss", "x", "y"], ["--system", "re2-tt", "x"], ["--system"], ["x"]])
def test_compare_dev_refuses_other_arguments_before_loading_the_original(compare_dev, argv, monkeypatch):
    monkeypatch.setattr(compare_dev.generate_goldens, "load_original", lambda: pytest.fail("the original was loaded"))
    with pytest.raises(SystemExit):
        compare_dev.main(argv)


@pytest.mark.parametrize("ours, theirs, expected", [
    (math.nan, math.nan, 0.0),
    (math.nan, 1.0, math.inf),
    (1.0, math.nan, math.inf),
    (1.5, 1.0, 0.5),
    (1.0, 1.5, 0.5),
])
def test_a_score_difference_never_depends_on_max_over_nan(compare_dev, ours, theirs, expected):
    assert compare_dev.score_difference(ours, theirs) == expected
