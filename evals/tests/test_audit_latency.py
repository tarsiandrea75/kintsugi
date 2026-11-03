"""Tests of `python -m kintsugi_evals audit-latency` (openspec/specs/evals REQ-14)."""

import csv
import json
from decimal import Decimal

import pytest

from kintsugi_evals.audit_latency import classify, latency_row, why_numbers
from kintsugi_evals.cli import main

# The 880fd526 pattern: previous mean, incident mean, previous max, incident max.
ROW = "| frontend | / | 0.054 | 1.325 | 0.061 | 1.477 | 29/29 | 29/29 |"
RESOURCE = "| processor_usage | 0.2752 | 17.98 | 0.3068 | 20.01 | 29/29 | 29/29 |"


def test_the_880fd526_pattern_is_previous_only():
    assert classify(ROW, "mean 0.054s incident vs 0.061s before") == "previous_only"


def test_a_why_naming_the_incident_mean_is_incident_cited():
    assert classify(ROW, "frontend latency mean rose to 1.325 from 0.054") == "incident_cited"


def test_a_why_naming_the_incident_max_is_incident_cited():
    assert classify(ROW, "max 1.477s in the incident window") == "incident_cited"


def test_milliseconds_are_divided_by_1000():
    assert why_numbers("54 ms, then 1325ms") == [Decimal("0.054"), Decimal("1.325")]
    assert classify(ROW, "mean 54 ms against 61 ms") == "previous_only"
    assert classify(ROW, "mean rose to 1325 ms") == "incident_cited"


def test_a_why_without_a_row_number_is_no_number():
    assert classify(ROW, "frontend latency is high, max 2.5s on another uri") == "no_number"
    assert classify(ROW, "frontend is slow") == "no_number"


def test_a_non_latency_quote_is_ignored():
    assert latency_row(RESOURCE) is None
    assert classify(RESOURCE, "mean 0.2752") is None
    assert classify("emailservice open_files rose", "0.054") is None


@pytest.mark.parametrize("why, expected", [
    ("1.3255", "incident_cited"),  # exactly half a unit above 1.325
    ("1.3245", "incident_cited"),  # exactly half a unit below
    ("1.3256", "no_number"),
    ("1.3244", "no_number"),
])
def test_half_a_unit_of_the_last_printed_digit_on_both_sides(why, expected):
    assert classify(ROW, f"mean {why}") == expected


def test_the_tolerance_follows_the_printed_digits():
    row = "| a | / | 2 | 7.5 | 3 | 9 | 1/2 | 2/2 |"
    assert classify(row, "max 9.4s") == "incident_cited"
    assert classify(row, "max 9.6s") == "no_number"
    assert classify(row, "mean 7.56") == "no_number"


def test_rendering_variants_of_the_row():
    assert latency_row("D51 " + ROW) == ("frontend", "/", "0.054", "1.325", "0.061", "1.477", "29/29", "29/29")
    assert latency_row("| a | /x\\|y | - | 0.300 | - | 0.400 | 0/29 | 29/29 |")[1] == "/x\\|y"
    assert classify("| a |  | - | 0.300 | - | 0.400 | 0/29 | 29/29 |", "0.3s now") == "incident_cited"
    assert latency_row("| a | / | - | - | - | - | - | - |") is None


def test_numbers_inside_words_and_other_units_are_not_read():
    assert why_numbers("D35 shows 45.08MB, about 0.061.") == [Decimal("0.061")]
    assert why_numbers("0.002-0.008s") == [Decimal("0.002"), Decimal("0.008")]


HEADER = ["fault", "mode", "route", "sample", "status", "truth", "reported", "split", "recording"]


def item(quote, why, line):
    return {"quote": quote, "why": why, "line": line, "tool": "dossier", "toolCallId": "dossier"}


def write_run(path, samples):
    """samples: (fault, recording, rootCauseService, evidence) per sample 1."""
    path.mkdir()
    with (path / "summary.tsv").open("w", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(HEADER)
        for fault, recording, service, evidence in samples:
            writer.writerow([fault, "decision", "cloud", 1, "COMPLETED", "x|y", f"{service}|X", "dev", recording])
            incident = {"status": "COMPLETED",
                        "report": {"rootCauseService": service, "evidence": evidence, "summary": "1.325 ignored"}}
            (path / f"{fault}-decision-{recording}-1.json").write_text(json.dumps(incident))
    return path


def a_run(tmp_path, name="run"):
    return write_run(tmp_path / name, [
        ("delay", "880fd5269a9d8b3c", "UNKNOWN", [
            item(ROW, "mean 0.054s incident vs 0.061s before", 51),
            item(RESOURCE, "mean 17.98", 87),
            item("| checkoutservice | / | 0.104 | 2.043 | 0.146 | 2.545 | 29/29 | 29/29 |", "max rose to 2.545s", 52),
        ]),
        ("cpu", "1bc72e388176b043", "checkoutservice", [
            item(ROW, "frontend is slow", 51),
            item(ROW, "mean 0.054 vs max 0.061", 51),
        ]),
    ])


def test_the_report_splits_abstaining_and_answering_samples(tmp_path, capsys):
    run = a_run(tmp_path)
    assert main(["audit-latency", "--label", "D", str(run)]) == 0
    out = capsys.readouterr().out
    assert "D: 2 samples, 1 abstained; 4 latency-row items" in out
    assert "D  abstained  incident_cited 1  previous_only 1  no_number 0" in out
    assert "D  answered   incident_cited 0  previous_only 1  no_number 1" in out
    assert "D  all        incident_cited 1  previous_only 2  no_number 1" in out
    assert ("D  previous_only  delay 880fd5269a9d8b3c sample 1 abstained line 51: " + ROW
            + "  why: mean 0.054s incident vs 0.061s before") in out


def test_out_writes_one_audit_row_per_latency_item_and_the_report(tmp_path, capsys):
    run, out_dir = a_run(tmp_path), tmp_path / "out"
    main(["audit-latency", "--label", "D", str(run), "--out", str(out_dir)])
    printed = capsys.readouterr().out
    assert (out_dir / "report.txt").read_text() == printed
    with (out_dir / "audit.tsv").open(newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    assert len(rows) == 4
    assert rows[0] == {"label": "D", "fault": "delay", "recording": "880fd5269a9d8b3c", "sample": "1",
                       "abstained": "true", "line": "51", "class": "previous_only", "quote": ROW,
                       "why": "mean 0.054s incident vs 0.061s before"}
    assert [r["class"] for r in rows] == ["previous_only", "incident_cited", "no_number", "previous_only"]


def test_two_runs_are_reported_in_label_order(tmp_path, capsys):
    first, second = a_run(tmp_path, "one"), a_run(tmp_path, "two")
    main(["audit-latency", "--label", "D1", str(first), "--label", "D2", str(second)])
    out = capsys.readouterr().out
    assert out.index("D1: 2 samples") < out.index("D2: 2 samples")


def test_refuses_a_duplicate_label(tmp_path):
    run = a_run(tmp_path)
    with pytest.raises(SystemExit, match="two inputs have the label D"):
        main(["audit-latency", "--label", "D", str(run), "--label", "D", str(run)])


def test_refuses_a_missing_dir(tmp_path):
    with pytest.raises(SystemExit, match="audit-latency: .*nowhere.* does not exist"):
        main(["audit-latency", "--label", "D", str(tmp_path / "nowhere")])


def test_refuses_a_missing_incident_file(tmp_path):
    run = a_run(tmp_path)
    (run / "cpu-decision-1bc72e388176b043-1.json").unlink()
    with pytest.raises(SystemExit, match="audit-latency: .*cpu-decision-1bc72e388176b043-1.json"):
        main(["audit-latency", "--label", "D", str(run)])


def test_refuses_no_run(tmp_path):
    with pytest.raises(SystemExit, match="audit-latency needs at least one --label"):
        main(["audit-latency"])
