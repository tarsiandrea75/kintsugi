"""Tests of the replay TSV reader and the scoring rule (ADR-015, openspec/specs/evals)."""

import pytest

from kintsugi_evals.scoring import (
    ABSTAIN,
    CORRECT,
    FALSE_ALARM,
    MISS,
    outcome,
    paired,
    read_summary,
    summarize,
)

HEADER = [
    "fault", "mode", "route", "sample", "status", "truth", "reported", "verdict", "recorded", "unrecorded",
    "coverage", "conflicts", "promptTokens", "completionTokens", "seconds", "split", "recording",
]

TRUTH = "gateway-mock|DEPENDENCY_LATENCY"


def write_tsv(path, rows, view=None):
    header = HEADER + (["view"] if view is not None else [])
    lines = ["\t".join(header)]
    for fault, status, truth, reported, recording in rows:
        values = [fault, "decision", "cloud", "1", status, truth, reported, "-", "33", "0", "1.00", "0", "1", "1",
                  "1.0", "dev", recording]
        if view is not None:
            values.append(view)
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


def test_reader_uses_the_given_label(tmp_path):
    write_tsv(tmp_path / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")])
    [row] = read_summary(tmp_path, label="D")
    assert row.config == "D"


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
    assert result.diff == pytest.approx(0.6)
