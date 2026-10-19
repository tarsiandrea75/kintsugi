#!/usr/bin/env bash
# Replays the recorded faults through the real agent loop and scores every sample (odd/tasks/replay-harness.md T4).
# Run with --help.
set -euo pipefail

usage() {
  cat <<'EOF'
Replays recorded faults through the agent loop, without the lab (odd/tasks/replay-harness.md, agent spec "Replay of
recorded tool outputs").

  infra/scripts/replay-agent.sh                       # one dossier sample of F1, F2 and F3, cloud route
  infra/scripts/replay-agent.sh --samples 5 --faults F1,F3
  infra/scripts/replay-agent.sh --route local         # the local model on Ollama, 127.0.0.1:11434

  --route cloud|local            the model's route (default cloud)
  --mode dossier|explorer|both   the loop's mode (default dossier)
  --samples N                    samples per fault and mode, 1 to 999 (default 1)
  --faults F1,F2,F3              the recorded faults to replay (default F1,F2,F3)

It runs ReplayRunTest (./mvnw test -Preplay) in agent/: for each fault, mode and sample, the real loop and the route's
real model over the recorded tool outputs in agent/src/test/resources/replay/, scored against the recorded truth. It
writes infra/scripts/out/replay-<UTC time>-<route>/: <fault>-<mode>-<n>.json (the incident with its steps),
summary.tsv (one line per sample: verdict, coverage, conflicts, tokens, seconds) and aggregate.tsv (per fault and mode:
matches out of samples, mean coverage, tokens and, on the cloud route, the estimated cost at $0.10 and $0.50 per
million prompt and completion tokens; then the covered samples, coverage at least 0.8, and the matches among them,
and the samples whose dossier was fully recorded). A sample with coverage below 1 had calls no recording holds: read
it as such.

EXPLORER REPLAYS ARE NOT VALID MEASUREMENTS: under exact matching the explorer's model-chosen calls are mostly not
recorded (coverage 0.00-0.38 in the T4 smoke check), so its samples measure the replay's gaps, not the agent. Explorer
and both stay available to inspect that, not to score the agent.

COST: on the cloud route every sample is paid model calls. Run it only with the human's go-ahead, and record the cost.
The cloud route needs ANTHROPIC_API_KEY, from the shell or infra/.env; it reaches Maven through the environment only
and is never printed. The local route needs Ollama serving granite4.1:8b. Both need docker (Testcontainers' Postgres).
Exit codes: 0 when every sample ran (a MISS is a measurement), 1 when the run failed, 2 on
a bad option, a missing key or a missing model.
EOF
}

OLLAMA=http://127.0.0.1:11434
MODEL=granite4.1:8b
ALL_FAULTS=(F1 F2 F3)
# The most samples one cloud run may make (about $0.005 each on claude-haiku-5-5, ADR-012).
MAX_CLOUD_SAMPLES=60

route=cloud
mode=dossier
samples=1
faults=F1,F2,F3
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --route|--mode|--samples|--faults)
      [[ $# -ge 2 ]] || { echo "$1 needs a value; see --help" >&2; exit 2; }
      set -- "$1=$2" "${@:3}" ;;
    --route=*) route=${1#*=}; shift ;;
    --mode=*) mode=${1#*=}; shift ;;
    --samples=*) samples=${1#*=}; shift ;;
    --faults=*) faults=${1#*=}; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ "$route" == cloud || "$route" == local ]] || { echo "Unknown route '$route'; choose cloud or local" >&2; exit 2; }
[[ "$mode" == explorer || "$mode" == dossier || "$mode" == both ]] \
  || { echo "Unknown mode '$mode'; choose explorer, dossier or both" >&2; exit 2; }
# At most 3 digits, so the bound on cloud samples below cannot wrap around in bash's 64-bit arithmetic.
[[ "$samples" =~ ^[1-9][0-9]{0,2}$ ]] || { echo "--samples needs an integer from 1 to 999, not '$samples'" >&2; exit 2; }
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

if [[ "$route" == cloud ]]; then
  # The key goes to Maven's environment, never to its command line, and its value is never printed.
  export_cloud_key || { echo "$CLOUD_KEY_MISSING" >&2; exit 2; }
  modes=$([[ "$mode" == both ]] && echo 2 || echo 1)
  total=$(( ${#selected[@]} * modes * samples ))
  # A typo in --samples must not turn into an unbounded paid run: a larger batch is split into several runs.
  (( total <= MAX_CLOUD_SAMPLES )) \
    || { echo "$total samples exceed the $MAX_CLOUD_SAMPLES a cloud run may make; split the batch" >&2; exit 2; }
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
info "replaying ${selected[*]}; mode: $mode; route: $route; samples: $samples; out: $OUT_DIR"
status=0
(cd "$REPO_DIR/agent" && ./mvnw -q test -Preplay -Dtest=ReplayRunTest \
  "-Dreplay.route=$route" "-Dreplay.mode=$mode" "-Dreplay.samples=$samples" "-Dreplay.faults=$faults" \
  "-Dreplay.out=$OUT_DIR") || status=$?

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
