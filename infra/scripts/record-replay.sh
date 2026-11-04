#!/usr/bin/env bash
# Copies verify-agent.sh incidents into the replay recordings (odd/tasks/replay-harness.md T3,
# odd/tasks/decision-instrument.md T3). Run with --help.
set -euo pipefail

usage() {
  cat <<'EOF'
Records verify-agent.sh runs for the replay harness (odd/tasks/replay-harness.md, odd/tasks/decision-instrument.md).

  infra/scripts/record-replay.sh --split dev infra/scripts/out/verify-agent-<UTC time> [<run dir>...]
  infra/scripts/record-replay.sh --split holdout --rounds 2,4,6,8,10 infra/scripts/out/verify-agent-<UTC time>
  infra/scripts/record-replay.sh --split holdout-2 infra/scripts/out/verify-agent-<UTC time>   # a --schedule run

  --split NAME       required: the split every recording joins: dev (to tune on), holdout (read once), stress
                     (windows that hold other faults), probe (faults never tuned on) or holdout-2 (holdout-2's
                     schedule cases, recorded after the freeze, ADR-026)
  --rounds LIST      only these rounds of a verify-agent.sh --rounds run: rounds or ranges, such as 1-5 or 1,3,5,7,9
                     (default: every incident of the run)

For each run directory and each <fault>-incident.json or <fault>-r<k>-incident.json in it, or <class>-c<case>-incident.json
of a verify-agent.sh --schedule run (class F1, F2, F3 or healthy, a healthy control), it reads the score file of the
same name (<fault>-score.txt, <fault>-r<k>-score.txt, <class>-c<case>-score.txt) and copies the incident to
agent/src/test/resources/replay/<fault>/<run dir name>-<fault>.json, or <run dir name>-r<k>-<fault>.json, or
<run dir name>-c<case>-<class>.json, then adds a line to agent/src/test/resources/replay/recordings.tsv (columns:
fault, mode, route, truth, file, incident, split, case; the recorded mode and route; file relative to the replay
directory; a healthy control's fault is healthy and its truth NONE|NONE). The case column is holdout-2's schedule case,
taken from the file name, and is empty in every other split; a holdout-2 recording that names no case is refused
(ADR-026, Decision 3). --rounds selects rounds only, so it skips a schedule run's files. Re-running it on the same run with the same split is
idempotent: the copy is replaced and the file's manifest line is replaced with the new values (unchanged when they are
the same), never duplicated.

Except with --split stress, every incident must first pass check-recordings.sh: a previous window of healthy orders
and traffic, and no restart in the incident window but the fault's own. A fault with no score file, or a score file
with no truth, is skipped with a warning. An incident is refused when it holds a key-like string ("sk-ant-",
ANTHROPIC_API_KEY= with a value, or "x-api-key"), is not a JSON object, is already recorded in another split (a
recording stays in one split; to move one, edit recordings.tsv), or fails check-recordings.sh: nothing is written for
it, and the script exits 1 after the other faults. A wrong option, a missing run directory, or a manifest that does
not start with the expected header exits 2.

REPLAY_DIR, when set, names the replay directory instead (used to try the script on a temporary directory). The tool
listing is not copied: the replay uses agent/src/test/resources/mcp-outputs/tools-list.json, a recorded listing of
mcp-tools' eight tools. It never contacts the lab.
EOF
}

(( $# )) || { usage >&2; exit 2; }
split=""
rounds=""
run_dirs=()
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --split|--rounds)
      [[ $# -ge 2 ]] || { echo "$1 needs a value; see --help" >&2; exit 2; }
      set -- "$1=$2" "${@:3}" ;;
    --split=*) split=${1#*=}; shift ;;
    --rounds=*) rounds=${1#*=}; shift ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;;
    *) run_dirs+=("$1"); shift ;;
  esac
done
# The splits a recording can join (odd/tasks/decision-instrument.md): the checks and messages below take them from here.
# They are ReplayCases.SPLITS, in its order (agent/src/test/java/dev/kintsugi/agent/loop/ReplayCases.java), the list
# the replay driver checks every manifest line against. They are listed again here so that the import needs no JDK;
# test-lib.sh checks that this script refuses an unknown split in the driver's words, list included.
SPLITS=(dev holdout stress probe holdout-2)
# Succeeds when $1 is one of SPLITS.
is_split() {
  local name
  for name in "${SPLITS[@]}"; do [[ "$1" == "$name" ]] && return 0; done
  return 1
}
# SPLITS as the messages name them: "dev, holdout, stress, probe or holdout-2".
split_choices=$(printf '%s, ' "${SPLITS[@]:0:${#SPLITS[@]}-1}")
split_choices="${split_choices%, } or ${SPLITS[-1]}"
[[ -n "$split" ]] || { echo "--split is required: $split_choices; see --help" >&2; exit 2; }
# The driver's refusal of an unknown split (ReplayCases.decisionCases), word for word, as replay-agent.sh shows it.
is_split "$split" || { echo "unknown split '$split': choose $split_choices" >&2; exit 2; }
# The rounds --rounds selects, as " 1 2 3 ": each item is a round or a range of rounds, from 1 to 999.
selected_rounds=" "
if [[ -n "$rounds" ]]; then
  IFS=, read -r -a items <<<"$rounds"
  for item in "${items[@]}"; do
    [[ "$item" =~ ^([1-9][0-9]{0,2})(-([1-9][0-9]{0,2}))?$ ]] \
      || { echo "--rounds needs rounds or ranges from 1 to 999, such as 1-5 or 1,3,5, not '$rounds'" >&2; exit 2; }
    first=${BASH_REMATCH[1]}
    last=${BASH_REMATCH[3]:-$first}
    (( first <= last )) || { echo "--rounds: the range $item ends before it starts" >&2; exit 2; }
    for (( k = first; k <= last; k++ )); do selected_rounds+="$k "; done
  done
fi
(( ${#run_dirs[@]} )) || { echo "No run directory; see --help" >&2; exit 2; }
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REPLAY_DIR="${REPLAY_DIR:-$REPO_DIR/agent/src/test/resources/replay}"
MANIFEST="$REPLAY_DIR/recordings.tsv"
HEADER=$'fault\tmode\troute\ttruth\tfile\tincident\tsplit\tcase'
CHECK_RECORDINGS="$(dirname "${BASH_SOURCE[0]}")/check-recordings.sh"
# A recording's name (check-recordings.sh reads the same): a fault's (1), with a round (2, 4) or a schedule case (2, 6),
# whose class may be a healthy control.
INCIDENT_NAME='^(F[0-9]+|healthy)((-r([0-9]+))|(-c([0-9]+)))?-incident\.json$'

# Succeeds when file $1 holds a JSON object.
is_json_object() { python3 -I -c 'import json, sys; sys.exit(0 if isinstance(json.load(open(sys.argv[1])), dict) else 1)' "$1" 2>/dev/null; }

# is_json_object needs python3: without it every incident would be refused as "not JSON", so say so instead.
command -v python3 >/dev/null || { echo "python3 is required to check the incidents" >&2; exit 2; }
# Every run directory is checked before anything is written.
for run_dir in "${run_dirs[@]}"; do
  [[ -d "$run_dir" ]] || { echo "Not a directory: $run_dir" >&2; exit 2; }
done
# A manifest from before a column (the split, the case) would get lines longer than its header.
if [[ -s "$MANIFEST" ]]; then
  IFS= read -r header <"$MANIFEST" || true
  [[ "$header" == "$HEADER" ]] || {
    echo "$MANIFEST does not start with the header ${HEADER//$'\t'/, }; nothing written" >&2
    exit 2
  }
fi

# Prints the split column of the manifest line for file $1 (relative to REPLAY_DIR), or nothing when there is no such
# line.
recorded_split() {
  [[ -s "$MANIFEST" ]] || return 0
  awk -F'\t' -v file="$1" 'NR > 1 && $5 == file { print $7; exit }' "$MANIFEST"
}

# Runs check-recordings.sh on incident file $1 alone, copied into a directory of its own. On a failure, prints the
# facts that fail it, and fails.
CHECK_DIR=$(mktemp -d)
trap 'rm -rf "$CHECK_DIR"' EXIT
windows_clean() {
  local output
  rm -f "$CHECK_DIR"/*
  cp "$1" "$CHECK_DIR/"
  if output=$("$CHECK_RECORDINGS" "$CHECK_DIR" 2>&1); then
    return 0
  fi
  output=${output%%$'\n'*}
  echo "${output#*-incident.json: }"
  return 1
}

refused=0
recorded=0
for run_dir in "${run_dirs[@]}"; do
  run_dir=${run_dir%/}
  run=$(basename "$run_dir")
  # The run's <fault>-incident.json, <fault>-r<k>-incident.json of the rounds selected, or <class>-c<case>-incident.json,
  # by fault then round or case.
  incidents=()
  shopt -s nullglob
  for incident_file in "$run_dir"/*-incident.json; do
    [[ "$(basename "$incident_file")" =~ $INCIDENT_NAME ]] || continue
    [[ -z "$rounds" || "$selected_rounds" == *" ${BASH_REMATCH[4]} "* ]] || continue
    incidents+=("$incident_file")
  done
  shopt -u nullglob
  if (( ${#incidents[@]} )); then
    mapfile -t incidents < <(printf '%s\n' "${incidents[@]}" | sort -V)
  else
    echo "WARN  $run: no F<n>-incident.json, F<n>-r<k>-incident.json or <class>-c<case>-incident.json${rounds:+ of rounds\
 $rounds}"
  fi
  for incident_file in "${incidents[@]}"; do
    [[ "$(basename "$incident_file")" =~ $INCIDENT_NAME ]]
    fault=${BASH_REMATCH[1]}
    # -r3 in a --rounds run, -c7 in a --schedule run, else empty.
    round=${BASH_REMATCH[2]}
    schedule_case=${BASH_REMATCH[6]}
    # The name its files share: F1, F1-r3 in a --rounds run, or F1-c7 or healthy-c7 in a --schedule run.
    label=$fault$round
    score_file="$run_dir/$label-score.txt"
    if [[ ! -f "$score_file" ]]; then
      echo "WARN  $run $label: no $label-score.txt, skipped"
      continue
    fi
    IFS=$'\t' read -r _ mode route truth incident <<<"$(score_fields "$(<"$score_file")")"
    if [[ ! "$truth" =~ ^[^|]+\|[^|]+$ ]]; then
      echo "WARN  $run $label: no truth in $label-score.txt, skipped"
      continue
    fi
    if holds_key_like_string "$incident_file"; then
      echo "FAIL  $run $label: refused, the incident holds a key-like string; nothing written"
      refused=$((refused + 1))
      continue
    fi
    if ! is_json_object "$incident_file"; then
      echo "FAIL  $run $label: refused, the incident is not a JSON object; nothing written"
      refused=$((refused + 1))
      continue
    fi
    # holdout-2's rows name their schedule case (ADR-026, Decision 3); no other split's do.
    if [[ "$split" == holdout-2 && -z "$schedule_case" ]]; then
      echo "FAIL  $run $label: refused, a holdout-2 recording must name its schedule case" \
        "(<class>-c<case>-incident.json, from verify-agent.sh --schedule); nothing written"
      refused=$((refused + 1))
      continue
    fi
    [[ "$split" == holdout-2 ]] || schedule_case=""
    file="$fault/$run$round-$fault.json"
    # A recording stays in one split: once in holdout, never moved into dev by a second import.
    previous=$(recorded_split "$file")
    if [[ -n "$previous" && "$previous" != "$split" ]]; then
      echo "FAIL  $run $label: refused, $file is recorded in split $previous, and a recording stays in one split;" \
        "nothing written"
      refused=$((refused + 1))
      continue
    fi
    if [[ "$split" != stress ]] && ! facts=$(windows_clean "$incident_file"); then
      echo "FAIL  $run $label: refused, check-recordings.sh: $facts; nothing written"
      refused=$((refused + 1))
      continue
    fi
    mkdir -p "$REPLAY_DIR/$fault"
    cp "$incident_file" "$REPLAY_DIR/$file"
    [[ -s "$MANIFEST" ]] || printf '%s\n' "$HEADER" >"$MANIFEST"
    row=$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' "$fault" "$mode" "$route" "$truth" "$file" "$incident" "$split" \
      "$schedule_case")
    case "$(manifest_upsert "$MANIFEST" "$row")" in
      added) echo "PASS  $run $label: $file recorded in $split ($mode, $route, truth $truth)" ;;
      replaced) echo "PASS  $run $label: $file re-recorded in $split, its recordings.tsv line updated ($mode, $route," \
        "truth $truth)" ;;
      unchanged) echo "....  $run $label: $file replaced, its recordings.tsv line unchanged" ;;
      *) echo "FAIL  $run $label: recordings.tsv not updated" >&2; exit 1 ;;
    esac
    recorded=$((recorded + 1))
  done
done

echo "$recorded recorded, $refused refused, manifest $MANIFEST"
(( refused == 0 ))
