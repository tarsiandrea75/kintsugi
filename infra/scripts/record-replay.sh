#!/usr/bin/env bash
# Copies verify-agent.sh incidents into the replay recordings (odd/tasks/replay-harness.md T3). Run with --help.
set -euo pipefail

usage() {
  cat <<'EOF'
Records verify-agent.sh runs for the replay harness (odd/tasks/replay-harness.md).

  infra/scripts/record-replay.sh infra/scripts/out/verify-agent-<UTC time>-dossier-cloud [<run dir>...]

For each run directory and each <fault>-incident.json in it, it reads <fault>-score.txt and copies the incident to
agent/src/test/resources/replay/<fault>/<run dir name>-<fault>.json, then adds a line to
agent/src/test/resources/replay/recordings.tsv (columns: fault, mode, route, truth, file, incident; the recorded mode
and route; file relative to the replay directory). Re-running it on the same run is idempotent: the copy is replaced
and the manifest gains no second line for the same file.

A fault with no score file, or a score file with no truth, is skipped with a warning. An incident that holds a
key-like string ("sk-ant-", ANTHROPIC_API_KEY= with a value, or "x-api-key"), or that is not a JSON object, is refused:
nothing is written for it and the script exits 1 after the other faults. A missing run directory exits 2.

REPLAY_DIR, when set, names the replay directory instead (used to try the script on a temporary directory). The tool
listing is not copied: the replay uses agent/src/test/resources/mcp-outputs/tools-list.json, a recorded listing of
mcp-tools' eight tools. It never contacts the lab.
EOF
}

(( $# )) || { usage >&2; exit 2; }
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REPLAY_DIR="${REPLAY_DIR:-$REPO_DIR/agent/src/test/resources/replay}"
MANIFEST="$REPLAY_DIR/recordings.tsv"
HEADER=$'fault\tmode\troute\ttruth\tfile\tincident'

# Succeeds when the manifest already has a line for file $1 (its fifth column).
in_manifest() { [[ -f "$MANIFEST" ]] && awk -F'\t' -v file="$1" '$5 == file { found = 1 } END { exit !found }' "$MANIFEST"; }

# Succeeds when file $1 holds a JSON object.
is_json_object() { python3 -I -c 'import json, sys; sys.exit(0 if isinstance(json.load(open(sys.argv[1])), dict) else 1)' "$1" 2>/dev/null; }

# Every run directory is checked before anything is written.
for run_dir in "$@"; do
  [[ -d "$run_dir" ]] || { echo "Not a directory: $run_dir" >&2; exit 2; }
done

refused=0
recorded=0
for run_dir in "$@"; do
  run_dir=${run_dir%/}
  run=$(basename "$run_dir")
  shopt -s nullglob
  incidents=("$run_dir"/F?-incident.json)
  shopt -u nullglob
  (( ${#incidents[@]} )) || echo "WARN  $run: no F?-incident.json"
  for incident_file in "${incidents[@]}"; do
    fault=$(basename "$incident_file" -incident.json)
    score_file="$run_dir/$fault-score.txt"
    if [[ ! -f "$score_file" ]]; then
      echo "WARN  $run $fault: no $fault-score.txt, skipped"
      continue
    fi
    IFS=$'\t' read -r _ mode route truth incident <<<"$(score_fields "$(<"$score_file")")"
    if [[ ! "$truth" =~ ^[^|]+\|[^|]+$ ]]; then
      echo "WARN  $run $fault: no truth in $fault-score.txt, skipped"
      continue
    fi
    if holds_key_like_string "$incident_file"; then
      echo "FAIL  $run $fault: refused, the incident holds a key-like string; nothing written"
      refused=$((refused + 1))
      continue
    fi
    if ! is_json_object "$incident_file"; then
      echo "FAIL  $run $fault: refused, the incident is not a JSON object; nothing written"
      refused=$((refused + 1))
      continue
    fi
    file="$fault/$run-$fault.json"
    mkdir -p "$REPLAY_DIR/$fault"
    cp "$incident_file" "$REPLAY_DIR/$file"
    [[ -s "$MANIFEST" ]] || printf '%s\n' "$HEADER" >"$MANIFEST"
    if in_manifest "$file"; then
      echo "....  $run $fault: $file replaced, already in recordings.tsv"
    else
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$fault" "$mode" "$route" "$truth" "$file" "$incident" >>"$MANIFEST"
      echo "PASS  $run $fault: $file recorded ($mode, $route, truth $truth)"
    fi
    recorded=$((recorded + 1))
  done
done

echo "$recorded recorded, $refused refused, manifest $MANIFEST"
(( refused == 0 ))
