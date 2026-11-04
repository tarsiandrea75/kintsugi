"""Tests of verified-correct and the L1 rate (verification-levels T5; ADR-028; openspec/specs/evals REQ-16)."""

import pytest

from kintsugi_evals.cli import main
from kintsugi_evals.scoring import parse_level, read_summary, summarize_levels
from kintsugi_evals.stats import wilson
from test_cli import error_of, two_runs
from test_scoring import TRUTH, write_tsv

HEALTHY = "NONE|NONE"
UNKNOWN = "UNKNOWN|UNKNOWN"

# One config's rows and the level each stored report states: (fault, status, truth, reported, recording, level).
ROWS = [
    ("F1", "COMPLETED", TRUTH, TRUTH, "a", "L3"),  # correct at L3: verified
    ("F1", "COMPLETED", TRUTH, TRUTH, "b", "L2"),  # correct below L3: not verified
    ("F2", "COMPLETED", TRUTH, "x|y", "c", "L0"),  # a miss with a dropped citation
    ("F2", "COMPLETED", TRUTH, UNKNOWN, "d", "L1"),  # an abstention: never verified
    ("F3", "FAILED", TRUTH, "none", "e", "-"),  # no stored report: out of both denominators
    ("H", "COMPLETED", HEALTHY, UNKNOWN, "f", "L3"),  # a correct healthy row: under the false-alarm rule only
    ("H", "COMPLETED", HEALTHY, TRUTH, "g", "L1"),  # a false alarm
    ("H", "FAILED", HEALTHY, "none", "h", "-"),
]


def with_levels(path, levels):
    """Appends a level column to the summary.tsv at `path`, one value per row."""
    summary = path / "summary.tsv"
    lines = summary.read_text().splitlines()
    assert len(lines) - 1 == len(levels)
    out = [lines[0] + "\tlevel"] + [f"{line}\t{level}" for line, level in zip(lines[1:], levels)]
    summary.write_text("\n".join(out) + "\n")
    return path


def levelled(tmp_path, rows=ROWS):
    write_tsv(tmp_path / "summary.tsv", [row[:5] for row in rows])
    return with_levels(tmp_path, [row[5] for row in rows])


def test_a_level_is_read_when_present_and_none_without_the_column(tmp_path):
    rows = read_summary(levelled(tmp_path), label="D")
    assert [r.level for r in rows] == [row[5] for row in ROWS]
    plain = tmp_path / "plain"
    plain.mkdir()
    write_tsv(plain / "summary.tsv", [row[:5] for row in ROWS])
    assert {r.level for r in read_summary(plain, label="D")} == {None}


@pytest.mark.parametrize("raw", ["L0", "L1", "L2", "L3", "-", None])
def test_a_level_is_l0_to_l3_a_dash_or_unknown(raw):
    assert parse_level(raw) == raw


@pytest.mark.parametrize("raw", ["", "L4", "l1", "L", "1", " L1"])
def test_any_other_level_is_malformed(raw):
    with pytest.raises(ValueError, match="is not a level"):
        parse_level(raw)


def test_score_refuses_a_malformed_level_in_one_line(tmp_path, capsys):
    rows = list(ROWS)
    rows[1] = rows[1][:5] + ("L4",)
    error = error_of(["score", "--label", "D", str(levelled(tmp_path, rows))], capsys)
    message = str(error.code)
    assert message.startswith("score: ") and "line 3: level 'L4'" in message and "\n" not in message


def test_verified_correct_counts_fault_rows_correct_at_l3_over_fault_rows_with_a_level(tmp_path):
    [score] = summarize_levels(read_summary(levelled(tmp_path), label="D"))
    # Of the four fault rows with a level, only the right answer at L3 is verified: the right answer at L2 is not,
    # and the abstention never is. The healthy rows stay out, even the right one at L3.
    assert (score.verified, score.fault_rows, score.fault_without) == (1, 4, 1)
    assert score.verified_rate == 1 / 4
    assert score.verified_ci == wilson(1, 4)


def test_an_abstention_is_never_verified_whatever_its_level(tmp_path):
    rows = [("F1", "COMPLETED", TRUTH, UNKNOWN, "a", "L3"), ("F1", "COMPLETED", TRUTH, TRUTH, "b", "L3")]
    [score] = summarize_levels(read_summary(levelled(tmp_path, rows), label="D"))
    assert (score.verified, score.fault_rows) == (1, 2)


def test_the_service_only_rule_decides_what_is_correct(tmp_path):
    rows = [("F1", "COMPLETED", TRUTH, "gateway-mock|CONFIGURATION", "a", "L3")]
    path = levelled(tmp_path, rows)
    assert summarize_levels(read_summary(path, label="D"))[0].verified == 0
    assert summarize_levels(read_summary(path, label="D", service_only=True))[0].verified == 1


def test_the_l1_rate_counts_rows_at_l1_or_above_over_rows_with_a_level(tmp_path):
    [score] = summarize_levels(read_summary(levelled(tmp_path), label="D"))
    # Every row with a stored report, healthy ones included: all but the L0 miss are at L1 or above.
    assert (score.l1, score.rows, score.without) == (5, 6, 2)
    assert score.l1_rate == 5 / 6
    assert score.l1_ci == wilson(5, 6)


def test_a_config_whose_rows_have_no_level_has_no_rate(tmp_path):
    rows = [row[:5] + ("-",) for row in ROWS]
    [score] = summarize_levels(read_summary(levelled(tmp_path, rows), label="0"))
    assert (score.fault_rows, score.fault_without, score.rows, score.without) == (0, 5, 0, 8)
    assert score.verified_rate is None and score.verified_ci is None
    assert score.l1_rate is None and score.l1_ci is None


def test_a_file_without_the_column_has_no_level_score(tmp_path):
    write_tsv(tmp_path / "summary.tsv", [row[:5] for row in ROWS])
    assert summarize_levels(read_summary(tmp_path, label="D")) == [None]


def test_score_prints_verified_correct_and_the_l1_rate_with_wilson_cis(tmp_path, capsys):
    assert main(["score", "--label", "D", str(levelled(tmp_path))]) == 0
    out = capsys.readouterr().out
    assert "D  verified  1/4   verified-correct  25%  [5%, 70%]  without a level: 1 fault rows\n" in out
    assert "D  L1        5/6   L1 rate  83%  [44%, 97%]  without a level: 2 rows\n" in out


def test_score_prints_no_rate_for_a_config_with_no_level(tmp_path, capsys):
    rows = [row[:5] + ("-",) for row in ROWS]
    assert main(["score", "--label", "0", str(levelled(tmp_path, rows))]) == 0
    out = capsys.readouterr().out
    assert "0  verified  0/0   verified-correct n/a  without a level: 5 fault rows\n" in out
    assert "0  L1        0/0   L1 rate n/a  without a level: 8 rows\n" in out


def test_a_file_without_the_column_scores_exactly_as_before(tmp_path, capsys):
    """The level column only adds the two lines: the rest of the report and every table keep their bytes."""
    (tmp_path / "plain").mkdir()
    (tmp_path / "levelled").mkdir()
    plain_local, plain_cloud = two_runs(tmp_path / "plain")
    levelled_local, levelled_cloud = two_runs(tmp_path / "levelled")
    with_levels(levelled_local, ["L1"] * 15)
    with_levels(levelled_cloud, ["L3"] * 14 + ["-"])
    plain_out, levelled_out = tmp_path / "plain-out", tmp_path / "levelled-out"
    main(["score", "--label", "A", str(plain_local), "--label", "D", str(plain_cloud), "--out", str(plain_out)])
    plain = capsys.readouterr().out
    main(["score", "--label", "A", str(levelled_local), "--label", "D", str(levelled_cloud),
          "--out", str(levelled_out)])
    levelled_report = capsys.readouterr().out
    assert "verified" not in plain and "L1 rate" not in plain
    kept = [line for line in levelled_report.splitlines() if "  verified " not in line and "  L1 " not in line]
    assert kept == plain.splitlines()
    assert len(levelled_report.splitlines()) == len(kept) + 4
    for name in ("scores.tsv", "paired.tsv"):
        assert (levelled_out / name).read_bytes() == (plain_out / name).read_bytes(), name
    assert sorted(p.name for p in levelled_out.iterdir()) == sorted(p.name for p in plain_out.iterdir())
