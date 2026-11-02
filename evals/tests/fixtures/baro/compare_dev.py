"""Compares the BARO port with the published pipeline (ADR-024): on RE2-OB repetition 1, the dev cases, and no other
repetition; or with `--system re2-tt` on every repetition of RE2-TT, which refuses a directory of any other name, since
every repetition of RE2-OB or RE2-SS is not ours to read.

Writes one TSV row per case and exits 1 unless there are exactly 30 cases (RE2-TT: 90) and at least one was compared, and
the port and the original agree on each: on refusing it, on its service ranking, and on every score within the tolerance.
A case both refuse is reported and is not a difference. A swap of two columns with the same services is reported too, since
float parsing can differ by an ulp (ADR-024). Run it in the virtual environment `generate_goldens.py` describes, from a
directory outside any extracted copy of the package:

    <venv>/bin/python -I evals/tests/fixtures/baro/compare_dev.py <RE2-OB dir> <out.tsv>
    <venv>/bin/python -I evals/tests/fixtures/baro/compare_dev.py --system re2-tt <RE2-TT dir> <out.tsv>
"""

import csv
import functools
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[2])]

import generate_goldens  # noqa: E402

from kintsugi_evals.rcaeval import baro as port  # noqa: E402

COLUMNS = ["recording", "outcome", "same_services", "same_columns", "max_score_difference", "port_top", "original_top"]
SCORE_TOLERANCE = 1e-9
REQUIRED_CASES = {"re2-ob": 30, "re2-tt": 90}
COMPARED = "compared"
REFUSED_BY_BOTH = "refused_by_both"


def score_difference(ours, theirs):
    """Both NaN are equal, one NaN is a difference; never left to `max` over NaN, whose answer depends on the order."""
    if math.isnan(ours) or math.isnan(theirs):
        return 0.0 if math.isnan(ours) and math.isnan(theirs) else math.inf
    return abs(ours - theirs)


def max_difference(ours, columns, scores):
    """The largest difference over the scores; a column only one side ranked has no score to agree with."""
    by_column = dict(zip(columns, scores))
    if set(ours.columns) != set(columns):
        return math.inf
    return max((score_difference(score, by_column[name]) for name, score in zip(ours.columns, ours.scores)), default=0.0)


def refused_row(recording, outcome):
    return {"recording": recording, "outcome": outcome, "same_services": "-", "same_columns": "-",
            "max_score_difference": "-", "port_top": "-", "original_top": "-"}


def compare(case_dir, recording, original):
    theirs = original(case_dir)
    try:
        ours = port.rank_case(case_dir)
    except ValueError:
        return refused_row(recording, REFUSED_BY_BOTH if "error" in theirs else "refused_by_port")
    if "error" in theirs:
        return refused_row(recording, "refused_by_original")
    return {"recording": recording, "outcome": COMPARED, "same_services": ours.services == theirs["services"],
            "same_columns": ours.columns == theirs["ranks"],
            "max_score_difference": f"{max_difference(ours, theirs['ranks'], theirs['scores']):.3e}",
            "port_top": ours.services[0] if ours.services else "-",
            "original_top": theirs["services"][0] if theirs["services"] else "-"}


def agrees(row):
    if row["outcome"] != COMPARED:
        return row["outcome"] == REFUSED_BY_BOTH
    return row["same_services"] and float(row["max_score_difference"]) <= SCORE_TOLERANCE


def cases_of(root, system):
    """The `(case, rep, directory)` to compare, refusing before it reads anything a root that is not RE2-TT's."""
    if system == "re2-ob":
        return [(path.name, 1, path / "1") for path in sorted(path for path in root.iterdir() if path.is_dir())]
    if root.resolve().name != port.SYSTEM_DIRS[system]:
        raise ValueError(f"--system {system} reads every repetition, so its directory must be {port.SYSTEM_DIRS[system]}, "
                         f"not {root.resolve().name}")
    return port.every_repetition(root)


def compare_cases(root, out, original, system="re2-ob"):
    """Compares every case under `root` with `original`, a function from a case directory to its answer, and writes `out`.
    Returns the exit code."""
    rows = [compare(case_dir, port.recording_of(system, case, rep), original) for case, rep, case_dir in cases_of(root, system)]
    with out.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, COLUMNS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    compared = [row for row in rows if row["outcome"] == COMPARED]
    both = sum(row["outcome"] == REFUSED_BY_BOTH for row in rows)
    print(f"{len(rows)} cases, {REQUIRED_CASES[system]} required: {len(compared)} compared, {both} refused by both, "
          f"{len(rows) - len(compared) - both} refused by one side; "
          f"{sum(row['same_services'] for row in compared)} same service ranking, "
          f"{sum(row['same_columns'] for row in compared)} same column ranking, "
          f"{sum(float(row['max_score_difference']) <= SCORE_TOLERANCE for row in compared)} within {SCORE_TOLERANCE} "
          f"on every score")
    return 0 if len(rows) == REQUIRED_CASES[system] and compared and all(agrees(row) for row in rows) else 1


def main(argv):
    system = "re2-ob"
    if argv[:1] == ["--system"] and len(argv) > 1:
        system, argv = argv[1], argv[2:]
    if len(argv) != 2 or argv[0] == "--system" or system not in REQUIRED_CASES:
        raise SystemExit(__doc__)
    baro, service_ranks = generate_goldens.load_original()
    original = functools.partial(generate_goldens.answer, baro, service_ranks, dataset=system)
    return compare_cases(Path(argv[0]), Path(argv[1]), original, system)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
