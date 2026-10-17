#!/usr/bin/env bash
# The M2 check: the agent diagnoses F1, F2 and F3 on the local model, scored against the truth. Run with --help.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF'
The M2 check (docs/vision.md, odd/tasks/agent-loop.md): the diagnosing agent (openspec/specs/agent) must find the
root-cause service and category of F1, F2 and F3 with the local model.

  infra/scripts/verify-agent.sh                # F1, F2 and F3 in turn, leave the lab running
  infra/scripts/verify-agent.sh --only F2      # one fault (or a list: --only F1,F3)
  infra/scripts/verify-agent.sh --down         # same, then tear the lab down (containers and volumes)
  infra/scripts/verify-agent.sh --mode dossier # the agent in dossier mode (ADR-011); the default is explorer

For each fault it injects it through the fault injector, places a few orders as neutral customers, waits for the
metrics and logs to land, then opens an incident (POST /incidents on 127.0.0.1:18086) with the same neutral symptom
for every fault and a window that starts with that fault's own orders, and waits for the run to end (at most 52 min:
the agent's 45 min budget, its 5 min warm-up and a margin). It restores the fault, reads the injection's truth row as
the faults role, and scores the report: it matches when the incident is COMPLETED and both rootCauseService and
rootCauseCategory of its report equal the truth. Between two faults it waits until the previous fault's traffic is
older than the next window (11 min after its restore). Nothing it writes into the lab, and nothing in the symptom,
names a fault.
Every run, pass or fail, is logged under infra/scripts/out/verify-agent-<UTC time>/: run.log, and per fault the
incident with its steps and full tool outputs (<fault>-incident.json), and the score (<fault>-score.txt); summary.tsv
has one line per fault. --mode sets KINTSUGI_AGENT_MODE for the agent container, recreated when it changes; an
incident whose recorded mode is not the requested one is a miss.
Exits 0 when every selected fault is diagnosed correctly, non-zero otherwise. The injection is restored on every exit.
Needs docker (with compose), curl and python3 on the host, and the local Ollama on 127.0.0.1:11434 with
granite4.1:8b. Expect up to about 55 minutes per fault on a CPU.
EOF
}

ALL_FAULTS=(F1 F2 F3)
# The one symptom of every incident: what a user would report for each of the three faults, naming no cause.
SYMPTOM="Customers report that checkout orders are not completing as expected"
# Each incident's window starts with its fault's own orders (incident_window), and space_out keeps the previous fault's
# traffic out of it, so a score never depends on how long the previous incident ran.
HEALTHY_ORDERS=3
FAULT_ORDERS=6
# After the last order: Prometheus scrapes every 5 s, Loki needs a few seconds more to ingest Alloy's batch.
SETTLE_SECONDS=30
OLLAMA=http://127.0.0.1:11434
MODEL=granite4.1:8b

selected=()
mode=explorer
args=()
while (( $# )); do
  case "$1" in
    --only) [[ $# -ge 2 ]] || { echo "--only needs a fault list, e.g. --only F1,F3" >&2; exit 2; }
      IFS=, read -r -a selected <<<"$2"; shift 2 ;;
    --only=*) IFS=, read -r -a selected <<<"${1#--only=}"; shift ;;
    --mode) [[ $# -ge 2 ]] || { echo "--mode needs explorer or dossier" >&2; exit 2; }
      mode=$2; shift 2 ;;
    --mode=*) mode=${1#--mode=}; shift ;;
    *) args+=("$1"); shift ;;
  esac
done
parse_args "${args[@]+"${args[@]}"}"
(( ${#selected[@]} )) || selected=("${ALL_FAULTS[@]}")
for fault in "${selected[@]}"; do
  [[ " ${ALL_FAULTS[*]} " == *" $fault "* ]] || { echo "Unknown fault '$fault'; choose from ${ALL_FAULTS[*]}" >&2; exit 2; }
done
[[ "$mode" == explorer || "$mode" == dossier ]] || { echo "Unknown mode '$mode'; choose explorer or dossier" >&2; exit 2; }
# compose passes it to the agent container (infra/compose.yaml); start_agent recreates the container when it changes.
export KINTSUGI_AGENT_MODE=$mode

# Every run is logged, pass or fail: the whole output goes to run.log too. tee ignores INT and TERM, so a Ctrl-C (sent
# to the whole process group) still lets it record what the exit trap prints.
RUN_DIR="$REPO_DIR/infra/scripts/out/verify-agent-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RUN_DIR"
exec > >(trap '' INT TERM; exec tee -a "$RUN_DIR/run.log") 2>&1
printf 'fault\tmode\tinjection\tincident\tstatus\ttruth\treported\tsteps\twindow\tseconds\tverdict\n' >"$RUN_DIR/summary.tsv"

# The fault being diagnosed and its incident, until its score is written; the exit trap records an interrupted one.
current_fault=""
current_incident=""
# When the previous fault was restored (epoch seconds); space_out waits from it.
previous_fault_end=""
cleanup() {
  local status=$?
  if [[ -n "$current_fault" ]]; then
    fail "$current_fault: not scored, the run stopped before its score"
    printf '%s\t%s\t%s\t%s\tnot scored\t-\t-\t-\t-\t-\tMISS\n' "$current_fault" "$mode" "${injection_id:--}" \
      "${current_incident:--}" >>"$RUN_DIR/summary.tsv"
  fi
  # The API has no cancel: an incident left RUNNING would make the next run wait up to 52 min for it.
  if [[ -n "$current_incident" ]]; then
    end_running_incident "$current_incident" || true
  fi
  (exit "$status")
  on_exit
}
trap cleanup EXIT
info "logging to $RUN_DIR; faults: ${selected[*]}; mode: $mode"

# 1. python3 reads the incidents' JSON; the local model must be served, or every incident ends FAILED at its warm-up.
if command -v python3 >/dev/null; then
  pass "python3 is available"
else
  fail "python3 is not on PATH; it reads the incidents' JSON"
  exit 1
fi
if curl -fsS --max-time "$CURL_MAX_SECONDS" "$OLLAMA/api/tags" 2>/dev/null | grep -q "\"name\":\"$MODEL\""; then
  pass "Ollama serves $MODEL on $OLLAMA"
else
  fail "Ollama on $OLLAMA does not serve $MODEL (ollama pull $MODEL)"
  exit 1
fi

# 2. Start or reuse the lab, then the agent, which start_lab leaves out. start_lab tolerates a down ollama-proxy (the
# evidence tools do not need it); the agent does.
start_lab
read -r _ state health _ <<<"$(lab_states | grep -E '^ollama-proxy ' | head -n1)"
if [[ "$state" == running && "$health" == healthy ]]; then
  pass "ollama-proxy is running and healthy, so the agent reaches the model"
else
  fail "ollama-proxy is ${state:-missing} (health ${health:--}); the agent cannot reach the model"
  exit 1
fi
start_agent || exit 1

# Places $1 orders, one every 2 s, and logs each answer; the agent sees them through its tools.
place_orders() {
  local i code id status seconds
  for ((i = 1; i <= $1; i++)); do
    read -r code id status seconds <<<"$(place_order)"
    info "order $id: $code $status in ${seconds}s"
    sleep 2
  done
}

# Runs one fault: space out from the previous one, traffic, inject, traffic, incident, restore, score.
diagnose() {
  local fault=$1 incident window opened traffic_start started_at seconds body status reason reported truth steps
  local verdict injection why incident_mode
  info "--- $fault"
  current_fault=$fault current_incident=""
  space_out "$previous_fault_end"
  traffic_start=$(date +%s)
  place_orders "$HEALTHY_ORDERS"
  inject_fault "$fault"
  injection=$injection_id
  place_orders "$FAULT_ORDERS"
  sleep "$SETTLE_SECONDS"

  started_at=$SECONDS
  if ! opened=$(open_incident "$SYMPTOM" "$traffic_start"); then
    fail "$fault: the agent did not open an incident"
    exit 1
  fi
  read -r incident window <<<"$opened"
  pass "$fault: incident $incident opened, window $window min"
  current_incident=$incident
  if wait_incident "$incident"; then
    info "$fault: incident $incident ended $incident_final_status after $((SECONDS - started_at))s"
  else
    fail "$fault: incident $incident did not end within $((INCIDENT_WAIT_SECONDS / 60)) min" \
      "(last status ${incident_final_status:-unreadable}); ending it"
    # A RUNNING incident would block every later POST: end it now, or stop the run (the exit trap tries again).
    end_running_incident "$incident" || exit 1
  fi
  seconds=$((SECONDS - started_at))

  # The record: the incident with every step and the full tool outputs.
  curl -sS --max-time 30 -o "$RUN_DIR/$fault-incident.json" "$AGENT/incidents/$incident?full=true" ||
    echo "WARN  could not save incident $incident" >&2
  read -r _ body <<<"$(agent_call GET "/incidents/$incident")" || true
  IFS=$'\t' read -r status reason reported steps <<<"$(incident_fields "$body")"
  incident_mode=$(json_field mode "$body")

  expect_restored
  injection_id=""
  previous_fault_end=$(date +%s)
  truth=$(truth_cause "$injection")

  if [[ "$incident_mode" != "$mode" ]]; then
    verdict=MISS
    fail "$fault: incident $incident ran in mode '${incident_mode:-unreadable}', not the requested '$mode'"
  elif [[ "$status" == COMPLETED && -n "$truth" && "$reported" == "$truth" ]]; then
    verdict=MATCH
    pass "$fault: the report names $reported, the truth"
  else
    verdict=MISS
    if [[ "$reason" == - ]]; then why=""; else why=" ($reason)"; fi
    fail "$fault: status $status$why, reported '$reported', truth '${truth:-unreadable}'"
  fi
  {
    echo "fault: $fault"
    echo "mode (requested, recorded): $mode, ${incident_mode:-unreadable}"
    echo "injection: $injection"
    echo "incident: $incident"
    echo "status: $status"
    echo "failure reason: $reason"
    echo "truth (service|category): ${truth:-unreadable}"
    echo "reported (service|category, from the report): $reported"
    echo "steps: $steps"
    echo "window (minutes): $window"
    echo "seconds (incident opened to ended): $seconds"
    echo "verdict: $verdict"
  } >"$RUN_DIR/$fault-score.txt"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$fault" "$mode" "$injection" "$incident" "$status" \
    "${truth:-unreadable}" "$reported" "$steps" "$window" "$seconds" "$verdict" >>"$RUN_DIR/summary.tsv"
  current_fault="" current_incident=""
}

# 3. Each fault in turn.
for fault in "${selected[@]}"; do
  diagnose "$fault"
done

info "summary ($RUN_DIR/summary.tsv):"
column -t -s $'\t' "$RUN_DIR/summary.tsv" 2>/dev/null || command cat "$RUN_DIR/summary.tsv"
info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
