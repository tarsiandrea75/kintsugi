#!/usr/bin/env bash
# Replays the recorded faults through the real agent loop and scores every sample (odd/tasks/replay-harness.md T4,
# odd/tasks/decision-instrument.md T3). Run with --help.
set -euo pipefail

usage() {
  cat <<'EOF'
Replays recorded faults through the agent loop, without the lab (odd/tasks/replay-harness.md, agent spec "Replay of
recorded tool outputs").

  infra/scripts/replay-agent.sh                       # one dossier sample of F1, F2 and F3, cloud route
  infra/scripts/replay-agent.sh --samples 5 --faults F1,F3
  infra/scripts/replay-agent.sh --route local         # the local model on Ollama, 127.0.0.1:11434
  infra/scripts/replay-agent.sh --mode decision       # each dev recording once: the dossier, then one report call
  infra/scripts/replay-agent.sh --mode decision --split holdout --repeats 3

  --route cloud|local            the model's route (default cloud)
  --mode MODE                    the loop's mode: dossier (default), explorer, decision, or both (explorer and dossier)
  --samples N                    explorer and dossier: samples per fault and mode, 1 to 999 (default 1)
  --split NAME                   decision: the recordings to replay, dev (default), holdout, stress or probe
  --repeats M                    decision: runs of each recording, 1 to 999 (default 1)
  --faults F1,F2,F3              the recorded faults to replay (default F1,F2,F3)

It runs ReplayRunTest (./mvnw test -Preplay) in agent/: the real loop and the route's real model over the recorded
tool outputs in agent/src/test/resources/replay/, each sample scored against the recorded truth. It writes
infra/scripts/out/replay-<UTC time>-<route>/: <fault>-<mode>-<recording>-<n>.json (the incident with its steps),
summary.tsv (one line per sample: verdict, coverage, conflicts, tokens, seconds, then its split and recording) and
aggregate.tsv (per fault, mode and split: the distinct recordings, the samples, matches out of samples, mean coverage,
tokens and, on the cloud route, the estimated cost at $0.10 and $0.50 per million prompt and completion tokens; then
the covered samples, coverage at least 0.8, and the matches among them, and the samples whose dossier was fully
recorded). A sample with coverage below 1 had calls no recording holds: read it as such.

A decision run (agent spec, "Decision mode") has one case per recording of the split: each line of recordings.tsv
for a chosen fault, in that split, recorded in decision or dossier mode. Each case replays its own recording alone,
which holds every call the dossier makes, --repeats times: the dossier, then the report call, with no phase and no
drill-down call. Before Maven, the script lists the cases with ReplayCases, the class ReplayRunTest picks them with,
run from its source by the JDK (about a second): an unknown split, a chosen fault with no recording in the split, or a
manifest line the driver refuses stops the run there. The splits: dev to tune on, holdout read once, stress for the
2026-10-19 recordings whose windows hold other faults, probe for faults never tuned on.

Explorer and dossier samples pool the fault's stress recordings, the ones they were measured on, the first one in the
sample's mode served first, so recordings imported into dev, holdout or probe never change them; --split and
--repeats are refused there, as --samples is in decision mode.

EXPLORER REPLAYS ARE NOT VALID MEASUREMENTS: under exact matching the explorer's model-chosen calls are mostly not
recorded (coverage 0.00-0.38 in the T4 smoke check), so its samples measure the replay's gaps, not the agent. Explorer
and both stay available to inspect that, not to score the agent.

COST: on the cloud route every sample is paid model calls. Run it only with the human's go-ahead, and record the cost.
The cloud route needs ANTHROPIC_API_KEY, from the shell or infra/.env; it reaches Maven through the environment only
and is never printed. The local route needs Ollama serving granite4.1:8b. Both need docker (Testcontainers' Postgres).
REPLAY_DIR, when set, names the replay directory instead (used to try the options on a temporary manifest).
Exit codes: 0 when every sample ran (a MISS is a measurement), 1 when the run failed, 2 on a bad option, a missing
key or a missing model, and in decision mode on a missing recording or a manifest line the driver refuses.
EOF
}

OLLAMA=http://127.0.0.1:11434
MODEL=granite4.1:8b
ALL_FAULTS=(F1 F2 F3)
# The most samples one cloud run may make (about $0.005 each on claude-haiku-5-5, ADR-012).
MAX_CLOUD_SAMPLES=60

route=cloud
mode=dossier
faults=F1,F2,F3
# Unset until given: decision mode refuses --samples, and the other modes refuse --split and --repeats.
unset samples split repeats
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --route|--mode|--samples|--split|--repeats|--faults)
      [[ $# -ge 2 ]] || { echo "$1 needs a value; see --help" >&2; exit 2; }
      set -- "$1=$2" "${@:3}" ;;
    --route=*) route=${1#*=}; shift ;;
    --mode=*) mode=${1#*=}; shift ;;
    --samples=*) samples=${1#*=}; shift ;;
    --split=*) split=${1#*=}; shift ;;
    --repeats=*) repeats=${1#*=}; shift ;;
    --faults=*) faults=${1#*=}; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ "$route" == cloud || "$route" == local ]] || { echo "Unknown route '$route'; choose cloud or local" >&2; exit 2; }
[[ "$mode" == explorer || "$mode" == dossier || "$mode" == decision || "$mode" == both ]] \
  || { echo "Unknown mode '$mode'; choose explorer, dossier, decision or both" >&2; exit 2; }
# At most 3 digits for --samples and --repeats, so the bound on cloud samples below cannot wrap around in bash's 64-bit
# arithmetic.
if [[ "$mode" == decision ]]; then
  [[ -z "${samples+given}" ]] || { echo "--samples does not apply to decision mode, which replays each recording of" \
    "the split once: use --repeats M to run each one M times" >&2; exit 2; }
  # The split is checked with the cases below, by the driver's own rule.
  split=${split-dev}
  repeats=${repeats-1}
  [[ "$repeats" =~ ^[1-9][0-9]{0,2}$ ]] \
    || { echo "--repeats needs an integer from 1 to 999, not '$repeats'" >&2; exit 2; }
else
  [[ -z "${split+given}" ]] || { echo "--split applies to decision mode only: explorer and dossier samples pool the" \
    "fault's stress recordings" >&2; exit 2; }
  [[ -z "${repeats+given}" ]] \
    || { echo "--repeats applies to decision mode only: explorer and dossier replays take --samples" >&2; exit 2; }
  samples=${samples-1}
  [[ "$samples" =~ ^[1-9][0-9]{0,2}$ ]] \
    || { echo "--samples needs an integer from 1 to 999, not '$samples'" >&2; exit 2; }
fi
IFS=, read -r -a selected <<<"$faults"
(( ${#selected[@]} )) || { echo "--faults needs at least one fault" >&2; exit 2; }
seen=" "
for fault in "${selected[@]}"; do
  [[ " ${ALL_FAULTS[*]} " == *" $fault "* ]] || { echo "Unknown fault '$fault'; choose from ${ALL_FAULTS[*]}" >&2; exit 2; }
  [[ "$seen" != *" $fault "* ]] || { echo "Fault '$fault' is listed twice" >&2; exit 2; }
  seen+="$fault "
done

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REPLAY_DIR="${REPLAY_DIR:-$REPO_DIR/agent/src/test/resources/replay}"
MANIFEST="$REPLAY_DIR/recordings.tsv"
[[ -f "$MANIFEST" ]] || { echo "No recordings manifest $MANIFEST" >&2; exit 2; }
# Maven runs in agent/, so the test gets the directory as an absolute path.
REPLAY_DIR=$(cd "$REPLAY_DIR" && pwd)

if [[ "$mode" == decision ]]; then
  # The cases, one line each, listed by ReplayCases, the class ReplayRunTest picks them with, run from its source by the
  # JDK's launcher (about a second; no Maven, no docker). Counted here to bound the run. On an unknown split, a chosen
  # fault with no case, or a manifest line the driver refuses, it says why and exits 2, and so does the run, before
  # Maven; any other failure of the listing fails the run.
  java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
  listing=$("$java_bin" "$REPO_DIR/agent/src/test/java/dev/kintsugi/agent/loop/ReplayCases.java" "$REPLAY_DIR" \
    "$split" "$faults") || exit $(( $? == 2 ? 2 : 1 ))
  mapfile -t cases <<<"$listing"
  recordings=${#cases[@]}
  total=$((recordings * repeats))
  info "decision mode, split $split: $recordings recording(s) of ${selected[*]}, each replayed $repeats time(s):" \
    "$total samples"
else
  modes=$([[ "$mode" == both ]] && echo 2 || echo 1)
  total=$(( ${#selected[@]} * modes * samples ))
fi

if [[ "$route" == cloud ]]; then
  # The key goes to Maven's environment, never to its command line, and its value is never printed.
  export_cloud_key || { echo "$CLOUD_KEY_MISSING" >&2; exit 2; }
  # A typo in --samples or --repeats must not turn into an unbounded paid run: a larger batch is divided into several
  # runs.
  (( total <= MAX_CLOUD_SAMPLES )) \
    || { echo "$total samples exceed the $MAX_CLOUD_SAMPLES a cloud run may make; divide the batch" >&2; exit 2; }
  echo "COST WARNING: $total samples on the cloud route, each one paid model calls."
  echo "Run this only with the human's go-ahead, and record the cost from aggregate.tsv."
else
  # The local route never needs the key: keep it out of the test JVM.
  unset ANTHROPIC_API_KEY
  if ! curl -fsS --max-time "$CURL_MAX_SECONDS" "$OLLAMA/api/tags" 2>/dev/null | grep -q "\"name\":\"$MODEL\""; then
    echo "Ollama on $OLLAMA does not serve $MODEL (ollama pull $MODEL)" >&2
    exit 2
  fi
fi

OUT_DIR="$REPO_DIR/infra/scripts/out/replay-$(date -u +%Y%m%dT%H%M%SZ)-$route"
if [[ "$mode" == decision ]]; then
  info "replaying ${selected[*]}; mode: decision; split: $split; repeats: $repeats; route: $route; out: $OUT_DIR"
  options=("-Dreplay.split=$split" "-Dreplay.repeats=$repeats")
else
  info "replaying ${selected[*]}; mode: $mode; route: $route; samples: $samples; out: $OUT_DIR"
  options=("-Dreplay.samples=$samples")
fi
status=0
(cd "$REPO_DIR/agent" && ./mvnw -q test -Preplay -Dtest=ReplayRunTest \
  "-Dreplay.route=$route" "-Dreplay.mode=$mode" "-Dreplay.faults=$faults" "${options[@]}" \
  "-Dreplay.recordings=$REPLAY_DIR" "-Dreplay.out=$OUT_DIR") || status=$?

echo "out: $OUT_DIR"
if [[ -f "$OUT_DIR/summary.tsv" ]]; then
  echo "--- summary.tsv"
  cat "$OUT_DIR/summary.tsv"
fi
if [[ -f "$OUT_DIR/aggregate.tsv" ]]; then
  echo "--- aggregate.tsv"
  cat "$OUT_DIR/aggregate.tsv"
fi
if (( status != 0 )); then
  echo "The replay run failed (exit $status): see agent/target/surefire-reports/" >&2
  exit 1
fi
