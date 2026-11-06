#!/usr/bin/env bash
# Re-checks the verification levels of the answers a replay run stored, with no model call (verification-levels T6,
# ADR-028). Run with --help.
set -euo pipefail

usage() {
  cat <<'EOF'
Re-checks the verification level (L0 to L3) of every answer a replay run stored, with today's ReportCheck and no model
call (odd/tasks/verification-levels.md T6, ADR-028).

  infra/scripts/recheck-levels.sh infra/scripts/out/replay-20261029T010641Z-cloud
  REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob \
    infra/scripts/out/replay-20261103T150905Z-cloud

  --dataset lab|re2-ob|re2-ss|re2-tt   the recorded system (default lab); the re2-* datasets need REPLAY_DIR
  -h, --help             this text

The argument is a replay run's output directory (replay-agent.sh's out), with its summary.tsv and one incident file
per row. It runs LevelRecheckRunTest (./mvnw test -Preplay) in agent/. For each incident it gathers the dossier again
from the run's recording, on a clock fixed at the stored dossier's "Gathered at" instant, and checks two guards: the
re-rendered dossier message equals the stored DOSSIER step's byte for byte, and the re-check of the stored REPORT
answer verifies exactly the evidence the run stored. Only then is the level the run would have stored written.

Only decision samples of the dev split with the tables and the plain or the hybrid report are read: a row of any
other split (a holdout) or report refuses the run before anything is written. A hybrid row is checked against the
candidates its summary.tsv row lists, as the run's report task listed them; one that listed none is re-checked as the
loop stores it, UNKNOWN|UNKNOWN at L1.

REPLAY_DIR names the replay directory the run read, agent/src/test/resources/replay by default (the lab's); an
RCAEval run must name it, since its recordings stay outside the repository. --dataset re2-ob reads Online Boutique's
topology, classpath:topology/re2-ob.json, re2-ss and re2-tt theirs, classpath:topology/<dataset>.json (a dataset
whose topology file is missing exits 2); lab runs read classpath:topology/lab.json.

The output, infra/scripts/out/recheck-<UTC time>-<input name>/ (git-ignored):
  recheck.txt        one line per incident, PASS with its level or REFUSED with why, then the RESULT line
  summary.tsv        the input's rows unchanged but the last column, level (added when the input predates it);
                     written only when every incident passed, so the scorer reads it as a replay's summary
  levels-detail.tsv  one row per stored evidence item: the sample's level, failed checks and log-only flag, and the
                     cited line's owner, section and change; written only when every incident passed

Exit status: Maven's; 0 also when some incident was refused (see recheck.txt), 2 on a usage error.
EOF
}

dataset=lab
input=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dataset) [[ $# -ge 2 ]] || { echo "--dataset needs a value" >&2; exit 2; }; dataset=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    *) [[ -z "$input" ]] || { echo "One replay run directory only (see --help)" >&2; exit 2; }; input=$1; shift ;;
  esac
done

case "$dataset" in
  lab) topology=classpath:topology/lab.json ;;
  re2-ob|re2-ss|re2-tt) topology=classpath:topology/$dataset.json ;;
  *) echo "--dataset is lab, re2-ob, re2-ss or re2-tt, not $dataset" >&2; exit 2 ;;
esac
[[ -n "$input" ]] || { echo "Name the replay run directory to re-check (see --help)" >&2; exit 2; }
[[ -f "$input/summary.tsv" ]] || { echo "No summary.tsv in $input" >&2; exit 2; }
[[ "$dataset" == lab || -n "${REPLAY_DIR:-}" ]] || { echo "--dataset $dataset needs REPLAY_DIR, the replay" \
  "directory the run read" >&2; exit 2; }

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

topology_file="$REPO_DIR/agent/src/main/resources/topology/${topology#classpath:topology/}"
[[ -f "$topology_file" ]] || { echo "--dataset $dataset needs its topology, ${topology_file#"$REPO_DIR"/}, which does" \
  "not exist yet" >&2; exit 2; }

REPLAY_DIR="${REPLAY_DIR:-$REPO_DIR/agent/src/test/resources/replay}"
[[ -f "$REPLAY_DIR/recordings.tsv" ]] || { echo "No recordings manifest $REPLAY_DIR/recordings.tsv" >&2; exit 2; }
# Maven runs in agent/, so the test gets absolute paths.
REPLAY_DIR=$(cd "$REPLAY_DIR" && pwd)
input=$(cd "$input" && pwd)
OUT_DIR="$REPO_DIR/infra/scripts/out/recheck-$(date -u +%Y%m%dT%H%M%SZ)-$(basename "$input")"
info "re-checking $input; dataset $dataset, topology $topology, recordings $REPLAY_DIR; out: $OUT_DIR"

status=0
(cd "$REPO_DIR/agent" && ./mvnw -q test -Preplay -Dtest=LevelRecheckRunTest "-Drecheck.in=$input" \
  "-Drecheck.out=$OUT_DIR" "-Dreplay.recordings=$REPLAY_DIR" "-Dkintsugi.agent.topology=$topology") || status=$?

echo "out: $OUT_DIR"
if [[ -f "$OUT_DIR/recheck.txt" ]]; then
  tail -n 1 "$OUT_DIR/recheck.txt"
fi
exit "$status"
