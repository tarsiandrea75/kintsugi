"""Compares the BARO port with the pinned original on RE2-OB repetition 1, the dev cases (ADR-024). No other repetition.

Writes one TSV row per case and exits 1 if any case's service ranking differs, or if the port and the original disagree
on refusing a case. Run it in the virtual environment `generate_goldens.py` describes, from a directory outside any
extracted copy of the package:

    <venv>/bin/python -I evals/tests/fixtures/baro/compare_dev.py <RE2-OB dir> <out.tsv>
"""

import csv
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[2])]

import generate_goldens  # noqa: E402

from kintsugi_evals.rcaeval import baro as port  # noqa: E402

COLUMNS = ["recording", "same_services", "same_columns", "max_score_difference", "port_top", "original_top"]
SCORE_TOLERANCE = 1e-9


def original_answer(pandas, baro, service_ranks, case_dir):
    inject = int((case_dir / "inject_time.txt").read_text(encoding="utf-8").strip())
    data = pandas.read_csv(case_dir / "simple_metrics.csv")
    data = data[(data["time"] - inject).abs() <= generate_goldens.WINDOW_SECONDS]
    result = baro(data, inject_time=inject, dataset="re2-ob")
    return result["ranks"], [result["scores"][name] for name in result["ranks"]], \
        [node.entity for node in service_ranks(result["ranks"])]


def compare(case_dir, recording, pandas, baro, service_ranks):
    ours = port.rank_case(case_dir)
    columns, scores, services = original_answer(pandas, baro, service_ranks, case_dir)
    same_columns = sorted(ours.columns) == sorted(columns)
    by_column = dict(zip(columns, scores))
    differences = [abs(score - by_column[name]) for name, score in zip(ours.columns, ours.scores) if name in by_column]
    return {"recording": recording, "same_services": ours.services == services,
            "same_columns": ours.columns == columns and same_columns,
            "max_score_difference": f"{max(differences, default=0.0):.3e}",
            "port_top": ours.services[0] if ours.services else "-", "original_top": services[0] if services else "-"}


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__)
    root, out = Path(argv[0]), Path(argv[1])
    import pandas

    baro, service_ranks = generate_goldens.load_original()
    rows = []
    for case_root in sorted(path for path in root.iterdir() if path.is_dir()):
        recording = port.recording_of("re2-ob", case_root.name, 1)
        rows.append(compare(case_root / "1", recording, pandas, baro, service_ranks))
    with out.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, COLUMNS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    same = sum(row["same_services"] for row in rows)
    within = sum(float(row["max_score_difference"]) <= SCORE_TOLERANCE for row in rows)
    print(f"{len(rows)} cases: {same} same service ranking, {sum(row['same_columns'] for row in rows)} same column "
          f"ranking, {within} within {SCORE_TOLERANCE} on every score")
    return 0 if same == len(rows) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
