#!/usr/bin/env bash
# Writes a copy of a summary.tsv with the confidence column filled from its replay run's incident files (config-c T2,
# ADR-030 Decision 6). Run with --help.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: infra/scripts/backfill-confidence.sh SUMMARY REPLAY_DIR OUT

Writes OUT, a copy of SUMMARY (a summary.tsv written before the confidence column) with that column appended: each
row's top-1 report.confidence, read from <fault>-<mode>-<recording>-<sample>.json in REPLAY_DIR, the replay run that
wrote the row (odd/tasks/config-c.md T2, ADR-030). Every other byte of each row is kept.

  infra/scripts/backfill-confidence.sh \
    evals/results/2026-11-05-l3-callee-recheck-re2ob/inputs/AS-summary.tsv \
    infra/scripts/out/replay-20261103T151645Z-local /tmp/AS-summary.tsv

It fails, writing nothing, when a COMPLETED row has no incident file or its file names another answer than the row,
when SUMMARY already has the column, or when OUT exists. A row that did not complete and has no file gets an empty
confidence, as ReplayDriver writes for a run that stored no report.

Exit status: 0 on success, 1 on a failed backfill, 2 on a usage error.
USAGE
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac
if [[ $# -ne 3 ]]; then
  usage >&2
  exit 2
fi

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
python="$repo/evals/.venv/bin/python"
[[ -x "$python" ]] || python=python3
summary=$(realpath -m "$1")
replay=$(realpath -m "$2")
out=$(realpath -m "$3")

PYTHONPATH="$repo/evals" exec "$python" -m kintsugi_evals.backfill_confidence "$summary" "$replay" "$out"
