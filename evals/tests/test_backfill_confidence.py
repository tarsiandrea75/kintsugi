"""Tests of the confidence backfill of an existing summary.tsv (config-c T2; ADR-030 Decision 6)."""

import json

import pytest

from kintsugi_evals.backfill_confidence import backfill, main
from kintsugi_evals.scoring import read_summary

TRUTH = "checkoutservice|RESOURCE_EXHAUSTION"
HEADER = ["fault", "mode", "route", "sample", "status", "truth", "reported", "split", "recording", "level"]


def summary(path, rows):
    """A summary.tsv of (fault, sample, status, reported, recording, level) rows."""
    lines = ["\t".join(HEADER)]
    for fault, sample, status, reported, recording, level in rows:
        lines.append("\t".join([fault, "decision", "local", sample, status, TRUTH, reported, "dev", recording, level]))
    path.write_text("\n".join(lines) + "\n")
    return path


def incident(replay, fault, recording, sample, report, status="COMPLETED"):
    """The incident file the replay driver writes for one row: <fault>-<mode>-<recording>-<sample>.json."""
    body = {"status": status}
    if report is not None:
        body["report"] = report
    (replay / f"{fault}-decision-{recording}-{sample}.json").write_text(json.dumps(body))


def answer(reported, confidence):
    service, category = reported.split("|")
    return {"rootCauseService": service, "rootCauseCategory": category, "confidence": confidence}


@pytest.fixture
def run(tmp_path):
    replay = tmp_path / "replay"
    replay.mkdir()
    rows = [("cpu", "1", "COMPLETED", TRUTH, "r1", "L3"),
            ("cpu", "1", "COMPLETED", "UNKNOWN|UNKNOWN", "r2", "L1"),
            ("delay", "2", "FAILED", "none", "r3", "-")]
    incident(replay, "cpu", "r1", "1", answer(TRUTH, 0.95))
    incident(replay, "cpu", "r2", "1", answer("UNKNOWN|UNKNOWN", 0.0))
    incident(replay, "delay", "r3", "2", None, status="FAILED")
    return summary(tmp_path / "summary.tsv", rows), replay


def test_each_row_gets_its_stored_reports_confidence_last(run):
    path, replay = run
    lines = backfill(path, replay)
    original = path.read_text().splitlines()
    assert lines[0] == original[0] + "\tconfidence"
    # Every other column is kept byte for byte; a forced abstention stores 0.0, a run with no report gets none.
    assert lines[1:] == [original[1] + "\t0.95", original[2] + "\t0.0", original[3] + "\t"]


def test_the_backfilled_file_scores_as_the_input_with_its_confidences(run, tmp_path):
    path, replay = run
    out = tmp_path / "out" / "summary.tsv"
    assert main([str(path), str(replay), str(out)]) == 0
    before, after = read_summary(path), read_summary(out)
    assert [row.confidence for row in after] == [0.95, 0.0, None]
    assert [row.outcome for row in after] == [row.outcome for row in before]
    assert [row.level for row in after] == [row.level for row in before]


def test_a_completed_row_without_its_incident_file_fails(run):
    path, replay = run
    (replay / "cpu-decision-r2-1.json").unlink()
    with pytest.raises(ValueError, match="line 3: .*cpu-decision-r2-1.json"):
        backfill(path, replay)


def test_an_incident_whose_answer_differs_from_the_row_fails(run):
    path, replay = run
    incident(replay, "cpu", "r1", "1", answer("frontend|RESOURCE_EXHAUSTION", 0.9))
    with pytest.raises(ValueError, match="line 2: .*frontend"):
        backfill(path, replay)


def test_a_summary_that_already_has_the_column_is_refused(run, tmp_path):
    path, replay = run
    out = tmp_path / "once.tsv"
    main([str(path), str(replay), str(out)])
    with pytest.raises(ValueError, match="already has a confidence column"):
        backfill(out, replay)


def test_an_existing_output_is_never_overwritten(run, tmp_path):
    path, replay = run
    out = tmp_path / "taken.tsv"
    out.write_text("keep\n")
    assert main([str(path), str(replay), str(out)]) == 1
    assert out.read_text() == "keep\n"


def test_a_completed_answer_without_a_numeric_confidence_fails(run):
    # Empty would read as "no confidence", which never triggers C's t: a lost number would change escalations unseen.
    path, replay = run
    incident(replay, "cpu", "r1", "1", answer(TRUTH, "high"))
    with pytest.raises(ValueError, match="line 2: .*cpu-decision-r1-1.json has no numeric report.confidence"):
        backfill(path, replay)


def test_a_completed_answer_whose_incident_has_no_report_fails(run):
    path, replay = run
    incident(replay, "cpu", "r1", "1", None)
    with pytest.raises(ValueError, match="line 2: .*no numeric report.confidence"):
        backfill(path, replay)


def test_an_abstention_without_a_confidence_gets_an_empty_one(run):
    # An abstention escalates before t is read (ADR-030), so its confidence is legitimately absent.
    path, replay = run
    incident(replay, "cpu", "r2", "1", {"rootCauseService": "UNKNOWN", "rootCauseCategory": "UNKNOWN"})
    assert backfill(path, replay)[2].endswith("\tL1\t")


def test_a_row_with_another_number_of_fields_than_the_header_fails(run):
    path, replay = run
    lines = path.read_text().splitlines()
    lines[2] = "\t".join(lines[2].split("\t")[:4])
    path.write_text("\n".join(lines) + "\n")
    with pytest.raises(ValueError, match=r"summary.tsv: line 3: 4 fields, the header has 10"):
        backfill(path, replay)
