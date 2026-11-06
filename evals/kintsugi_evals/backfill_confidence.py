"""Adds the `confidence` column to a summary.tsv written before it (config-c T2; ADR-030 Decision 6).

Each row is joined to the incident file the replay driver stored for it, `<fault>-<mode>-<recording>-<sample>.json`
in the run's directory, and gets that file's top-1 `report.confidence`, as ReplayDriver now writes it: the number, or
empty when the run stored no report. Every other byte of the row is kept, so the copy scores as its input does.

A COMPLETED row without its incident file, whose file names another answer than the row's `reported`, or that answers
a cause while its file holds no numeric `report.confidence`, fails the whole backfill: a silently empty confidence would
read as "no confidence", which never triggers C's cut, and change Config C's escalations. An abstention may lack one:
it escalates before the cut is read. A row with another number of fields than the header fails too.

    python -m kintsugi_evals.backfill_confidence SUMMARY REPLAY_DIR OUT
"""

import json
import sys
from pathlib import Path

from .scoring import NO_REPORT, UNKNOWN_ANSWER, parse_confidence

COLUMN = "confidence"
KEY = ("fault", "mode", "recording", "sample")


def confidence_of(incident):
    """The incident's top-1 confidence as summary.tsv writes it: the number, or empty for no report."""
    report = incident.get("report")
    value = report.get("confidence") if isinstance(report, dict) else None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return ""
    return str(float(value))


def answer_of(incident):
    report = incident.get("report")
    if not isinstance(report, dict):
        return None
    return f"{report.get('rootCauseService')}|{report.get('rootCauseCategory')}"


def backfill(summary, replay_dir):
    """The lines of `summary` with the confidence column appended, each from its row's incident file in `replay_dir`."""
    summary, replay_dir = Path(summary), Path(replay_dir)
    lines = summary.read_text().splitlines()
    if not lines:
        raise ValueError(f"{summary} is empty")
    header = lines[0].split("\t")
    if COLUMN in header:
        raise ValueError(f"{summary} already has a confidence column")
    missing = [column for column in KEY + ("status", "reported") if column not in header]
    if missing:
        raise ValueError(f"{summary} lacks the columns {', '.join(missing)}")
    out = [lines[0] + "\t" + COLUMN]
    for number, line in enumerate(lines[1:], start=2):
        fields = line.split("\t")
        if len(fields) != len(header):
            raise ValueError(f"{summary}: line {number}: {len(fields)} fields, the header has {len(header)}")
        row = dict(zip(header, fields))
        name = "{fault}-{mode}-{recording}-{sample}.json".format(**{column: row[column] for column in KEY})
        path = replay_dir / name
        if not path.is_file():
            if row["status"] == "COMPLETED":
                raise ValueError(f"{summary}: line {number}: a COMPLETED row has no incident file {path}")
            out.append(line + "\t")
            continue
        incident = json.loads(path.read_text())
        answer = answer_of(incident)
        if row["status"] == "COMPLETED" and answer is not None and answer != row["reported"]:
            raise ValueError(f"{summary}: line {number}: {path} answers {answer}, the row reports {row['reported']}")
        value = confidence_of(incident)
        if value == "" and row["status"] == "COMPLETED" and row["reported"] not in ("", NO_REPORT, UNKNOWN_ANSWER):
            raise ValueError(f"{summary}: line {number}: {path} has no numeric report.confidence for the answer "
                             f"{row['reported']}")
        parse_confidence(value)
        out.append(line + "\t" + value)
    return out


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 3:
        print("usage: python -m kintsugi_evals.backfill_confidence SUMMARY REPLAY_DIR OUT", file=sys.stderr)
        return 2
    summary, replay_dir, out = (Path(arg) for arg in args)
    if out.exists():
        print(f"{out} exists: the backfill never overwrites a file", file=sys.stderr)
        return 1
    try:
        lines = backfill(summary, replay_dir)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n")
    values = sorted({line.rsplit("\t", 1)[1] for line in lines[1:]})
    print(f"{out}: {len(lines) - 1} rows, confidences {' '.join(value or '(empty)' for value in values)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
