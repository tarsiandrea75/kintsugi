#!/usr/bin/env bash
# Runs verify-agent.sh N times per agent mode, alternating explorer and dossier, so a slow spell of the host weighs
# on both modes alike (ADR-011, odd/tasks/evidence-dossier.md T6). Run with --help.
set -uo pipefail

usage() {
  cat <<'EOF'
Compares the agent's explorer and dossier modes on F1, F2 and F3 (ADR-011).

  infra/scripts/compare-agent-modes.sh          # 3 rounds: explorer, dossier, explorer, dossier, explorer, dossier
  infra/scripts/compare-agent-modes.sh --runs 1 # one round

Each run is a full infra/scripts/verify-agent.sh --mode <mode>, logged under its own
infra/scripts/out/verify-agent-<UTC time>/ as usual; a failed run (a miss) does not stop the next one. At the end it
prints every run's directory and its summary.tsv lines, which carry the mode. Expect up to about 3 hours per run on a
CPU. Exits 0 when every run exits 0.
EOF
}

runs=3
while (( $# )); do
  case "$1" in
    --runs) [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]*$ ]] || { echo "--runs needs a positive integer" >&2; exit 2; }
      runs=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument '$1'; run with --help" >&2; exit 2 ;;
  esac
done

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPTS_DIR/out"
failed=0
dirs=()
for (( round = 1; round <= runs; round++ )); do
  for mode in explorer dossier; do
    echo "=== round $round/$runs, mode $mode, started $(date -u +%FT%TZ)"
    before=$(ls -1d "$OUT_DIR"/verify-agent-* 2>/dev/null | tail -n1)
    "$SCRIPTS_DIR/verify-agent.sh" --mode "$mode" || failed=$((failed + 1))
    after=$(ls -1d "$OUT_DIR"/verify-agent-* 2>/dev/null | tail -n1)
    [[ -n "$after" && "$after" != "$before" ]] && dirs+=("$after")
  done
done

echo "=== summary of $((runs * 2)) runs ($failed exited non-zero)"
for dir in "${dirs[@]+"${dirs[@]}"}"; do
  echo "$dir"
  tail -n +2 "$dir/summary.tsv"
done
(( failed == 0 ))
