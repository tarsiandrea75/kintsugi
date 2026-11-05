"""Tests of the replay TSV reader and the scoring rule (ADR-015, openspec/specs/evals)."""

from pathlib import Path

import pytest

from kintsugi_evals.scoring import (
    ABSTAIN,
    CORRECT,
    FALSE_ALARM,
    MISS,
    chance_ac,
    chance_avg5,
    outcome,
    paired,
    ranking_of,
    read_service_count,
    read_summary,
    summarize,
    summarize_ranked,
)
from kintsugi_evals.stats import wilson

HEADER = [
    "fault", "mode", "route", "sample", "status", "truth", "reported", "verdict", "recorded", "unrecorded",
    "coverage", "conflicts", "promptTokens", "completionTokens", "seconds", "split", "recording",
]

TRUTH = "gateway-mock|DEPENDENCY_LATENCY"


def write_tsv(path, rows, view=None, report=None):
    header = HEADER + (["view"] if view is not None else []) + (["report"] if report is not None else [])
    lines = ["\t".join(header)]
    for fault, status, truth, reported, recording in rows:
        values = [fault, "decision", "cloud", "1", status, truth, reported, "-", "33", "0", "1.00", "0", "1", "1",
                  "1.0", "dev", recording]
        if view is not None:
            values.append(view)
        if report is not None:
            values.append(report)
        lines.append("\t".join(values))
    path.write_text("\n".join(lines) + "\n")
    return path


def test_fault_named_correctly_is_correct():
    assert outcome("COMPLETED", TRUTH, TRUTH) == CORRECT


def test_fault_named_wrongly_or_without_report_is_a_miss():
    assert outcome("COMPLETED", TRUTH, "payment-service|DEPENDENCY_LATENCY") == MISS
    assert outcome("FAILED", TRUTH, "none") == MISS
    assert outcome("FAILED", TRUTH, TRUTH) == MISS


def test_unknown_on_a_fault_is_an_abstention():
    assert outcome("COMPLETED", TRUTH, "UNKNOWN|UNKNOWN") == ABSTAIN


def test_half_unknown_on_a_fault_is_a_miss():
    assert outcome("COMPLETED", TRUTH, "gateway-mock|UNKNOWN") == MISS


def test_unknown_on_a_healthy_case_is_correct():
    assert outcome("COMPLETED", "NONE|NONE", "UNKNOWN|UNKNOWN") == CORRECT


def test_any_cause_on_a_healthy_case_is_a_false_alarm():
    assert outcome("COMPLETED", "NONE|NONE", TRUTH) == FALSE_ALARM
    assert outcome("COMPLETED", "NONE|NONE", "gateway-mock|UNKNOWN") == FALSE_ALARM


def test_no_report_on_a_healthy_case_is_a_miss_not_a_false_alarm():
    assert outcome("FAILED", "NONE|NONE", "none") == MISS


def test_reader_keys_cases_and_labels_from_columns(tmp_path):
    path = write_tsv(tmp_path / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")], view="tables")
    [row] = read_summary(path)
    assert row.case == ("dev", "r1-F1")
    assert row.config == "cloud-decision-tables"
    assert row.outcome == CORRECT


def test_reader_treats_a_missing_view_column_as_dash(tmp_path):
    write_tsv(tmp_path / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")])
    [row] = read_summary(tmp_path)
    assert row.view == "-"
    assert row.config == "cloud-decision--"


def test_reader_reads_a_report_column_after_the_view(tmp_path):
    # The replay's summary.tsv gained "report" after "view" (service-assessment T2r): the row is scored as before,
    # and the default config tells V1 (sheets, plain) from V2 (sheets, assessed).
    write_tsv(tmp_path / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")], view="sheets",
              report="assessed")
    [row] = read_summary(tmp_path)
    assert row.view == "sheets"
    assert row.report == "assessed"
    assert row.config == "cloud-decision-sheets-assessed"
    assert row.case == ("dev", "r1-F1")
    assert row.outcome == CORRECT


def test_reader_treats_a_missing_report_column_as_dash(tmp_path):
    write_tsv(tmp_path / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")], view="tables")
    [row] = read_summary(tmp_path)
    assert row.report == "-"
    # A TSV from before the report column keeps its config name, so committed results stay comparable.
    assert row.config == "cloud-decision-tables"


def test_reader_uses_the_given_label(tmp_path):
    write_tsv(tmp_path / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")])
    [row] = read_summary(tmp_path, label="D")
    assert row.config == "D"


def test_reader_reads_a_summary_with_the_numctx_column(tmp_path):
    # The replay's summary.tsv gained numCtx after reportTimeout (context-full-cut T3), so the columns after it moved
    # by one: the reader takes them by name, and the row scores as before.
    header = HEADER + ["view", "report", "model", "think", "reportCap", "reportTimeout", "numCtx", "candidates",
                       "ranked", "riskyExecuted", "policyRefused", "level"]
    values = ["F1", "decision", "local", "1", "COMPLETED", TRUTH, TRUTH, "MATCH", "33", "0", "1.00", "0", "1", "1",
              "1.0", "dev", "r1-F1", "tables", "hybrid", "granite4.1:8b", "default", "4096", "600s", "12288",
              TRUTH + ";order-service|CONFIGURATION", "", "0", "1", "L2"]
    (tmp_path / "summary.tsv").write_text("\t".join(header) + "\n" + "\t".join(values) + "\n")
    [row] = read_summary(tmp_path)
    assert row.config == "local-decision-tables-hybrid"
    assert row.outcome == CORRECT
    assert row.candidates == (TRUTH, "order-service|CONFIGURATION")
    assert (row.risky_executed, row.policy_refused, row.level) == (0, 1, "L2")


def test_reader_rejects_a_file_without_the_needed_columns(tmp_path):
    (tmp_path / "summary.tsv").write_text("fault\tmode\nF1\tdecision\n")
    with pytest.raises(ValueError, match="truth"):
        read_summary(tmp_path)


def synthetic(tmp_path):
    rows = [
        ("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1"),
        ("F1", "COMPLETED", TRUTH, "UNKNOWN|UNKNOWN", "r2-F1"),
        ("F1", "COMPLETED", TRUTH, "payment-service|DEPENDENCY_LATENCY", "r3-F1"),
        ("H", "COMPLETED", "NONE|NONE", "UNKNOWN|UNKNOWN", "r1-H"),
        ("H", "COMPLETED", "NONE|NONE", TRUTH, "r2-H"),
    ]
    return read_summary(write_tsv(tmp_path / "summary.tsv", rows), label="A")


def test_summary_separates_faults_from_healthy_cases(tmp_path):
    [score] = summarize(synthetic(tmp_path))
    assert score.config == "A"
    pooled = score.pooled
    assert (pooled.n, pooled.correct, pooled.abstained) == (3, 1, 1)
    assert pooled.ac1 == pytest.approx(1 / 3)
    assert pooled.coverage == pytest.approx(2 / 3)
    assert pooled.selective == pytest.approx(1 / 2)
    assert [f.fault for f in score.faults] == ["F1"]
    assert (score.healthy.n, score.healthy.false_alarms) == (2, 1)
    assert score.healthy.false_alarm_rate == pytest.approx(1 / 2)


def m0_like(tmp_path):
    first, second = [], []
    for i in range(15):
        recording = f"r{i}"
        right_first = i < 5
        right_second = i < 14
        first.append(("F1", "COMPLETED", TRUTH, TRUTH if right_first else "x|y", recording))
        second.append(("F1", "COMPLETED", TRUTH, TRUTH if right_second else "x|y", recording))
    (tmp_path / "a").mkdir()
    (tmp_path / "d").mkdir()
    rows_a = read_summary(write_tsv(tmp_path / "a" / "summary.tsv", first), label="A")
    rows_d = read_summary(write_tsv(tmp_path / "d" / "summary.tsv", second), label="D")
    return rows_a, rows_d


def test_paired_counts_discordant_cases_and_runs_mcnemar(tmp_path):
    rows_a, rows_d = m0_like(tmp_path)
    result = paired(rows_a, rows_d, seed=1, iterations=2000)
    assert (result.cases, result.only_second, result.only_first) == (15, 9, 0)
    assert result.mcnemar_p == pytest.approx(0.00390625)
    assert result.diff == pytest.approx(0.6)
    assert result.boot_low < 0.6 < result.boot_high


def test_paired_skips_mcnemar_when_cases_have_repetitions(tmp_path):
    rows_a, rows_d = m0_like(tmp_path)
    result = paired(rows_a + rows_a, rows_d, seed=1, iterations=200)
    assert result.mcnemar_p is None
    assert (result.only_first, result.only_second) == (None, None)
    assert result.diff == pytest.approx(0.6)


def test_a_fault_row_with_no_answer_lowers_coverage(tmp_path):
    rows = [
        ("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1"),
        ("F1", "TIMED_OUT", TRUTH, "none", "r2-F1"),
        ("F1", "INVALID_REPORT", TRUTH, "none", "r3-F1"),
        ("F1", "COMPLETED", TRUTH, "payment-service|DEPENDENCY_LATENCY", "r4-F1"),
    ]
    [score] = summarize(read_summary(write_tsv(tmp_path / "summary.tsv", rows), label="A"))
    pooled = score.pooled
    assert (pooled.n, pooled.correct, pooled.abstained, pooled.unanswered) == (4, 1, 0, 2)
    assert pooled.ac1 == pytest.approx(1 / 4)
    assert pooled.coverage == pytest.approx(2 / 4)
    assert pooled.selective == pytest.approx(1 / 2)


SECOND = "payment-service|DEPENDENCY_LATENCY"


def write_candidates_tsv(path, route, report, rows):
    """A summary.tsv with route, report and the trailing candidates column (hybrid T4)."""
    header = HEADER + ["view", "report", "candidates"]
    lines = ["\t".join(header)]
    for fault, truth, reported, recording, candidates in rows:
        values = [fault, "decision", route, "1", "COMPLETED", truth, reported, "-", "33", "0", "1.00", "0", "0", "0",
                  "0.1", "dev", recording, "tables", report, candidates]
        lines.append("\t".join(values))
    path.write_text("\n".join(lines) + "\n")
    return path


def recall_rows():
    return [
        ("F1", TRUTH, TRUTH, "r1-F1", f"{TRUTH};{SECOND}"),
        ("F1", TRUTH, SECOND, "r2-F1", f"{SECOND};{TRUTH}"),
        ("F1", TRUTH, "UNKNOWN|UNKNOWN", "r3-F1", ""),
        ("F2", TRUTH, SECOND, "r1-F2", SECOND),
        ("H", "NONE|NONE", "UNKNOWN|UNKNOWN", "r1-H", ""),
    ]


def test_reader_parses_candidates_on_rules_rows(tmp_path):
    rows = read_summary(write_candidates_tsv(tmp_path / "summary.tsv", "rules", "plain", recall_rows()))
    assert rows[0].candidates == (TRUTH, SECOND)
    assert rows[2].candidates == ()


def test_candidate_recall_counts_fault_cases_whose_truth_is_listed(tmp_path):
    rows = read_summary(write_candidates_tsv(tmp_path / "summary.tsv", "local", "hybrid", recall_rows()))
    [score] = summarize(rows)
    f1, f2 = score.faults
    assert (f1.recalled, f1.candidate_recall) == (2, pytest.approx(2 / 3))
    assert (f2.recalled, f2.candidate_recall) == (0, 0)
    assert (score.pooled.recalled, score.pooled.n) == (2, 4)


def test_candidate_recall_does_not_apply_to_other_rows(tmp_path):
    rows = read_summary(write_candidates_tsv(tmp_path / "summary.tsv", "local", "plain", recall_rows()))
    assert rows[0].candidates is None
    [score] = summarize(rows)
    assert score.pooled.recalled is None
    assert score.pooled.candidate_recall is None


def test_candidate_recall_does_not_apply_without_the_column(tmp_path):
    [row] = read_summary(write_tsv(tmp_path / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")],
                                   view="tables", report="plain"))
    assert row.candidates is None
    [score] = summarize([row])
    assert score.faults[0].candidate_recall is None


# --service-only (rcaeval-converter T5): RCAEval's AC@1 names the root-cause service only (vision §5).

def test_service_only_compares_the_service_part():
    assert outcome("COMPLETED", TRUTH, "gateway-mock|RESOURCE_EXHAUSTION", service_only=True) == CORRECT
    assert outcome("COMPLETED", TRUTH, "gateway-mock|UNKNOWN", service_only=True) == CORRECT
    assert outcome("COMPLETED", TRUTH, "payment-service|DEPENDENCY_LATENCY", service_only=True) == MISS
    assert outcome("FAILED", TRUTH, TRUTH, service_only=True) == MISS


def test_service_only_keeps_abstentions_and_healthy_cases():
    assert outcome("COMPLETED", TRUTH, "UNKNOWN|UNKNOWN", service_only=True) == ABSTAIN
    assert outcome("COMPLETED", "NONE|NONE", "UNKNOWN|UNKNOWN", service_only=True) == CORRECT
    assert outcome("COMPLETED", "NONE|NONE", "gateway-mock|UNKNOWN", service_only=True) == FALSE_ALARM


def test_the_default_still_compares_service_and_category():
    assert outcome("COMPLETED", TRUTH, "gateway-mock|RESOURCE_EXHAUSTION") == MISS


def test_read_summary_service_only_scores_the_service(tmp_path):
    path = write_tsv(tmp_path / "summary.tsv", [
        ("cpu", "COMPLETED", "checkoutservice|RESOURCE_EXHAUSTION", "checkoutservice|DEPENDENCY_LATENCY", "a"),
        ("cpu", "COMPLETED", "checkoutservice|RESOURCE_EXHAUSTION", "frontend|RESOURCE_EXHAUSTION", "b"),
    ])
    assert [r.outcome for r in read_summary(path)] == [MISS, MISS]
    assert [r.outcome for r in read_summary(path, service_only=True)] == [CORRECT, MISS]
    [score] = summarize(read_summary(path, service_only=True))
    assert (score.pooled.correct, score.pooled.n) == (1, 2)


def test_service_only_candidate_recall_matches_the_service(tmp_path):
    path = tmp_path / "summary.tsv"
    path.write_text("\t".join(HEADER + ["view", "report", "candidates"]) + "\n" + "\t".join(
        ["cpu", "decision", "rules", "1", "COMPLETED", "checkoutservice|RESOURCE_EXHAUSTION",
         "frontend|DEPENDENCY_LATENCY", "-", "58", "0", "1.00", "0", "0", "0", "0.1", "dev", "a", "tables", "plain",
         "frontend|DEPENDENCY_LATENCY;checkoutservice|DEPENDENCY_LATENCY"]) + "\n")
    [default] = summarize(read_summary(path))
    [service] = summarize(read_summary(path, service_only=True))
    assert default.pooled.recalled == 0
    assert service.pooled.recalled == 1


RANK_TRUTH = "checkoutservice|RESOURCE_EXHAUSTION"


def write_ranked_tsv(path, rows, route="baro", report="-"):
    """A summary.tsv whose rows are (fault, status, truth, reported, recording, candidates[, ranked]).

    Rows with a seventh value add the trailing ranked column (ranked-report T3); every row then needs one.
    """
    with_ranked = any(len(row) == 7 for row in rows)
    lines = ["\t".join(HEADER + ["view", "report", "candidates"] + (["ranked"] if with_ranked else []))]
    for fault, status, truth, reported, recording, candidates, *rest in rows:
        lines.append("\t".join([fault, "decision", route, "1", status, truth, reported, "-", "-", "-", "-", "-", "0",
                                "0", "-", "dev", recording, "-", report, candidates] + rest))
    path.write_text("\n".join(lines) + "\n")
    return path


def ranked(tmp_path, rows, **kwargs):
    [score] = summarize_ranked(read_summary(write_ranked_tsv(tmp_path / "summary.tsv", rows, **kwargs),
                                            service_only=True))
    return score


def pairs(*services):
    return ";".join(f"{service}|UNKNOWN" for service in services)


def test_ranking_is_reported_then_candidates_with_first_occurrences_and_no_unknown():
    assert ranking_of("COMPLETED", "a|X", "b|Y;a|Z;UNKNOWN|UNKNOWN;c|Y;b|Z") == ("a", "b", "c")


def test_ranking_of_a_row_that_did_not_complete_is_empty():
    assert ranking_of("TIMED_OUT", "a|X", "a|X;b|Y") == ()
    assert ranking_of("FAILED", "none", "") == ()


def test_ranking_without_an_answer_is_empty():
    assert ranking_of("COMPLETED", "UNKNOWN|UNKNOWN", "") == ()
    assert ranking_of("COMPLETED", "none", "") == ()
    assert ranking_of("COMPLETED", "", None) == ()


def test_ranking_of_a_row_that_abstains_is_empty_even_with_candidates():
    # REQ-7 counts this row as no answer, so its first candidate must not become a ranked AC@1 hit.
    assert ranking_of("COMPLETED", "UNKNOWN|UNKNOWN", "a|X;b|Y") == ()
    assert ranking_of("COMPLETED", "none", "a|X;b|Y") == ()
    assert ranking_of("COMPLETED", "", "a|X;b|Y") == ()


def test_ranking_of_a_row_with_no_answer_is_empty_even_with_ranked():
    # A reported none or empty is no answer, as UNKNOWN is: neither ranked nor candidates may stand in for it.
    assert ranking_of("COMPLETED", "none", "a|X", "b|Y") == ()
    assert ranking_of("COMPLETED", "", "a|X", "b|Y") == ()
    assert ranking_of("COMPLETED", "", None, "b|Y") == ()


def test_ranking_of_a_file_without_the_column_is_the_reported_service():
    assert ranking_of("COMPLETED", "a|X", None) == ("a",)


def test_truth_at_rank_two_of_five(tmp_path):
    score = ranked(tmp_path, [("cpu", "COMPLETED", RANK_TRUTH, "frontend|UNKNOWN", "a",
                               pairs("frontend", "checkoutservice", "cartservice", "redis", "adservice"))])
    assert score.pooled.n == 1
    assert (score.pooled.ac(1), score.pooled.ac(3), score.pooled.ac(5)) == (0, 1, 1)
    assert score.pooled.avg5 == pytest.approx(0.8)
    assert score.pooled.mean_length == 5


def test_an_empty_ranking_is_a_miss_at_every_k_and_stays_in_the_denominator(tmp_path):
    score = ranked(tmp_path, [
        ("cpu", "COMPLETED", RANK_TRUTH, "UNKNOWN|UNKNOWN", "a", ""),
        ("cpu", "COMPLETED", RANK_TRUTH, RANK_TRUTH, "b", pairs("checkoutservice")),
    ])
    assert score.pooled.n == 2
    assert score.pooled.hits == (1, 1, 1, 1, 1)
    assert (score.pooled.ac(1), score.pooled.ac(3), score.pooled.ac(5), score.pooled.avg5) == (0.5, 0.5, 0.5, 0.5)
    assert score.pooled.mean_length == 0.5


def test_a_repeated_service_counts_once_before_the_cut(tmp_path):
    # Undeduplicated, the truth would be fourth, a miss at k = 3. Deduplicated, it is second.
    score = ranked(tmp_path, [("cpu", "COMPLETED", RANK_TRUTH, "frontend|A", "a",
                               "frontend|A;frontend|B;frontend|C;checkoutservice|A")])
    assert score.pooled.hits == (0, 1, 1, 1, 1)
    assert score.pooled.ac(3) == 1
    assert score.pooled.mean_length == 2


def test_a_timed_out_row_with_candidates_has_an_empty_ranking(tmp_path):
    score = ranked(tmp_path, [("cpu", "TIMED_OUT", RANK_TRUTH, RANK_TRUTH, "a", pairs("checkoutservice", "frontend"))])
    assert score.pooled.hits == (0, 0, 0, 0, 0)
    assert score.pooled.avg5 == 0
    assert score.pooled.mean_length == 0


def test_a_single_answer_has_every_ranked_metric_equal_to_ac1(tmp_path):
    score = ranked(tmp_path, [
        ("cpu", "COMPLETED", RANK_TRUTH, RANK_TRUTH, "a", ""),
        ("cpu", "COMPLETED", RANK_TRUTH, "frontend|UNKNOWN", "b", ""),
    ], route="cloud")
    assert (score.pooled.ac(1), score.pooled.ac(3), score.pooled.ac(5), score.pooled.avg5) == (0.5, 0.5, 0.5, 0.5)
    assert score.pooled.mean_length == 1


def test_healthy_rows_stay_out_of_the_ranked_metrics(tmp_path):
    score = ranked(tmp_path, [
        ("cpu", "COMPLETED", RANK_TRUTH, RANK_TRUTH, "a", pairs("checkoutservice")),
        ("none", "COMPLETED", "NONE|NONE", "frontend|UNKNOWN", "h", pairs("frontend", "NONE")),
    ])
    assert score.pooled.n == 1
    assert [fault.fault for fault in score.faults] == ["cpu"]
    assert score.pooled.ac(1) == 1


def test_a_config_with_healthy_rows_only_has_no_ranked_score(tmp_path):
    score = ranked(tmp_path, [("none", "COMPLETED", "NONE|NONE", "UNKNOWN|UNKNOWN", "h", "")])
    assert score.pooled is None
    assert score.faults == []


def test_ranked_metrics_per_fault_and_pooled_with_wilson_on_the_cuts(tmp_path):
    score = ranked(tmp_path, [
        ("cpu", "COMPLETED", RANK_TRUTH, RANK_TRUTH, "a", pairs("checkoutservice", "frontend")),
        ("cpu", "COMPLETED", RANK_TRUTH, "frontend|UNKNOWN", "b", pairs("frontend", "adservice", "checkoutservice")),
        ("delay", "COMPLETED", RANK_TRUTH, "frontend|UNKNOWN", "c", pairs("frontend", "adservice")),
    ])
    assert [(f.fault, f.hits) for f in score.faults] == [("cpu", (1, 1, 2, 2, 2)), ("delay", (0, 0, 0, 0, 0))]
    assert score.pooled.hits == (1, 1, 2, 2, 2)
    assert score.pooled.avg5 == pytest.approx(8 / 15)
    assert score.pooled.mean_length == pytest.approx(7 / 3)
    for k, hits in ((1, 1), (3, 2), (5, 2)):
        assert score.pooled.ci(k) == wilson(hits, 3)


def test_the_ranking_ignores_the_route_while_candidate_recall_does_not(tmp_path):
    path = write_ranked_tsv(tmp_path / "summary.tsv", [
        ("cpu", "COMPLETED", RANK_TRUTH, "frontend|UNKNOWN", "a", pairs("frontend", "checkoutservice"))],
        route="local", report="plain")
    rows = read_summary(path, service_only=True)
    assert rows[0].candidates is None
    assert rows[0].ranking == ("frontend", "checkoutservice")
    assert summarize(rows)[0].pooled.candidate_recall is None


def test_ranking_reads_the_ranked_column_between_reported_and_candidates():
    assert ranking_of("COMPLETED", "a|X", "c|Y;d|Y", "b|Z;UNKNOWN|UNKNOWN;none") == ("a", "b", "c", "d")


def test_a_service_in_ranked_and_candidates_counts_once_at_its_first_place():
    assert ranking_of("COMPLETED", "a|X", "c|Y;b|Y", "b|Z;c|Z") == ("a", "b", "c")
    assert ranking_of("COMPLETED", "a|X", "b|Y", "a|Z;b|Z") == ("a", "b")


def test_a_row_that_abstains_has_an_empty_ranking_even_with_ranked():
    assert ranking_of("COMPLETED", "UNKNOWN|UNKNOWN", "a|X", "b|Y") == ()
    assert ranking_of("TIMED_OUT", "a|X", "", "b|Y") == ()


def test_an_empty_ranked_column_is_today_s_ranking():
    assert ranking_of("COMPLETED", "a|X", "b|Y", "") == ranking_of("COMPLETED", "a|X", "b|Y")
    assert ranking_of("COMPLETED", "a|X", None, None) == ("a",)


def test_the_ranked_column_moves_the_truth_up_in_the_file(tmp_path):
    path = write_ranked_tsv(tmp_path / "summary.tsv", [
        ("cpu", "COMPLETED", RANK_TRUTH, "frontend|UNKNOWN", "a", pairs("cartservice", "checkoutservice"),
         "checkoutservice|RESOURCE_EXHAUSTION")], route="local", report="plain")
    rows = read_summary(path, service_only=True)
    assert rows[0].ranking == ("frontend", "checkoutservice", "cartservice")
    # ranked is not candidates: candidate recall still reads the candidates column alone.
    assert rows[0].candidates is None
    [score] = summarize_ranked(rows)
    assert score.pooled.hits == (0, 1, 1, 1, 1)


# The ranked hits and mean list length of each committed input, as scored before the ranked column (ranked-report T3).
COMMITTED_RANKED = {
    "0": ((4, 5, 5, 5, 5), 19 / 30),
    "0R": ((20, 22, 24, 24, 24), 38 / 30),
    "DP1": ((16, 16, 16, 16, 16), 18 / 30),
    "DP2": ((17, 17, 17, 17, 17), 17 / 30),
    "DP3": ((15, 15, 15, 15, 15), 15 / 30),
    "DW": ((25, 25, 25, 25, 25), 27 / 30),
}


@pytest.mark.parametrize("label", sorted(COMMITTED_RANKED))
def test_a_committed_input_without_the_ranked_column_scores_as_before(label):
    inputs = Path(__file__).resolve().parents[1] / "results/2026-11-03-resource-warrant-re2ob-dev/inputs"
    path = inputs / f"{label}-summary.tsv"
    assert "ranked" not in path.read_text().splitlines()[0].split("\t")
    rows = read_summary(path, label=label, service_only=True)
    [score] = summarize(rows)
    [ranked_score] = summarize_ranked(rows)
    hits, mean_length = COMMITTED_RANKED[label]
    assert ranked_score.pooled.hits[0] == score.pooled.correct
    assert ranked_score.pooled.hits == hits
    assert ranked_score.pooled.mean_length == pytest.approx(mean_length)


def test_chance_with_eleven_services():
    assert chance_ac(1, 11) == pytest.approx(0.0909, abs=5e-5)
    assert chance_ac(3, 11) == pytest.approx(0.2727, abs=5e-5)
    assert chance_ac(5, 11) == pytest.approx(0.4545, abs=5e-5)
    assert chance_avg5(11) == pytest.approx(0.2727, abs=5e-5)


def test_chance_caps_at_one_when_the_list_covers_the_services():
    assert chance_ac(1, 3) == pytest.approx(1 / 3)
    assert chance_ac(3, 3) == 1
    assert chance_ac(5, 3) == 1
    assert chance_avg5(3) == pytest.approx(0.8)


def test_service_count_of_the_real_topology():
    topology = Path(__file__).resolve().parents[2] / "agent/src/main/resources/topology/re2-ob.json"
    assert read_service_count(topology) == 11


@pytest.mark.parametrize("content", ["{not json", "[]", "{}", '{"services": "a"}', '{"services": []}',
                                     '{"services": ["a", 1]}', '{"services": ["a", ""]}', '{"services": ["a", "a"]}'])
def test_a_malformed_topology_is_refused_naming_the_file(tmp_path, content):
    path = tmp_path / "topology.json"
    path.write_text(content)
    with pytest.raises(ValueError, match="topology.json"):
        read_service_count(path)


def test_a_missing_topology_is_refused_naming_the_file(tmp_path):
    with pytest.raises(ValueError, match="nowhere.json"):
        read_service_count(tmp_path / "nowhere.json")
