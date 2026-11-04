#!/usr/bin/env bash
# The M2 check: the agent diagnoses F1, F2 and F3 on the local route, scored against the truth (the cloud route is for
# development, ADR-012). Run with --help.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF'
The M2 check (docs/vision.md, odd/tasks/agent-loop.md): the diagnosing agent (openspec/specs/agent) must find the
root-cause service and category of F1, F2 and F3. The M2 check is the local route (the local model); the cloud route
(claude-haiku-5-5 through Anthropic's API) is the development route of ADR-012 and cannot pass M2.

  infra/scripts/verify-agent.sh                 # F1, F2 and F3 in turn, leave the lab running
  infra/scripts/verify-agent.sh --only F2       # one fault (or a list: --only F1,F3)
  infra/scripts/verify-agent.sh --down          # same, then tear the lab down (containers and volumes)
  infra/scripts/verify-agent.sh --mode dossier  # the agent in dossier mode (ADR-011); the default is explorer
  infra/scripts/verify-agent.sh --mode decision # the dossier, then one report call; a baseline before each fault
  infra/scripts/verify-agent.sh --route cloud   # the agent on the cloud route (ADR-012); the default is local
  infra/scripts/verify-agent.sh --rounds 5      # the fault list 5 times (1 to 20)
  infra/scripts/verify-agent.sh --mode decision --schedule infra/holdout2/schedule.tsv            # holdout-2's cases
  infra/scripts/verify-agent.sh --mode decision --schedule infra/holdout2/schedule.tsv --cases 1-5,9  # some of them

For each fault it injects it through the fault injector, places a few orders as neutral customers, waits for the
metrics and logs to land, then opens an incident (POST /incidents on 127.0.0.1:18086) with the same neutral symptom
for every fault and a window that starts with that fault's own orders, and waits for the run to end (at most 52 min:
the agent's 45 min budget, its 5 min warm-up and a margin). It restores the fault, reads the injection's truth row as
the faults role, and scores the report: it matches when the incident is COMPLETED and both rootCauseService and
rootCauseCategory of its report equal the truth. In explorer and dossier mode, between two faults it waits until the
previous fault's traffic is older than the next window (11 min after its restore), across runs too: the last restore
time is kept in infra/scripts/out/.last-fault-end (delete it to skip the wait). Nothing it writes into the lab, and
nothing in the symptom, names a fault.
In decision mode the agent gathers its dossier as the incident opens, over the window (N minutes) and the N minutes
before it (the previous window), so the window's margin is 2 min instead of 10. Each fault, the first included, starts
with a baseline phase instead of that wait: 19 min of healthy orders, one every 50 s. That is 2 * N_max + 1 min, where
N_max (9 min) is the largest window a fault can get in decision mode (its traffic and settle with every step at its
time cap, rounded up to whole minutes, plus the margin). So the previous window holds only the phase's healthy orders,
and the previous fault and the lab's start are older than both windows. A baseline order that is not PAID starts the
phase over, at most twice; a third fails that fault (not injected, scored a miss, "baseline failed" in summary.tsv),
and the run goes on with the next fault. A fault whose window is larger than N_max anyway (after waiting for another
running incident) fails, and is scored a miss.
Every run, pass or fail, is logged under infra/scripts/out/verify-agent-<UTC time>/: run.log, and per fault the
incident with its steps and full tool outputs (<fault>-incident.json), and the score (<fault>-score.txt); summary.tsv
has one line per fault (VERIFY_AGENT_RUN_DIR, when set, names the directory instead). --rounds K runs the fault list K
times; each fault's files then carry the round (<fault>-r<k>-incident.json, <fault>-r<k>-score.txt). summary.tsv's
last column is the round, 1 without --rounds. infra/scripts/check-recordings.sh checks the recorded windows. --mode
sets KINTSUGI_AGENT_MODE and --route KINTSUGI_AGENT_ROUTE for the agent container, recreated when either changes; an
incident whose recorded mode or route is not the requested one is a miss. The score also records the model and the
incident's prompt and completion tokens.
Exits 0 when every selected fault is diagnosed correctly, non-zero otherwise. The injection is restored on every exit.
Needs docker (with compose), curl and python3 on the host. The local route needs the local Ollama on 127.0.0.1:11434
with granite4.1:8b, reached through ollama-proxy; expect up to about 55 minutes per fault on a CPU. The cloud route
needs ANTHROPIC_API_KEY, in infra/.env (git-ignored) or in the shell, and costs money: run a cloud batch only with the
human's go-ahead (ADR-012).

--schedule FILE runs the cases of a schedule written by infra/scripts/holdout2-schedule.py (ADR-026), in its order, or
with --cases LIST only those cases (cases or ranges, such as 1-5,9), still in the schedule's order; it takes neither
--only nor --rounds. A fault case is injected with its drawn parameter, nested under "parameters" in POST /faults (F1's
gatewayLatencyMs, F2's gatewayErrorStatus; F3 takes none, its wrong port is the injector's own), and is scored against
the injector's truth. A healthy case (class healthy) injects nothing: where a fault case injects it checks that GET
/faults/active answers 204, and otherwise it runs exactly as a fault case does, with the same baseline phase or wait,
the same orders, settle time and symptom; its truth is NONE|NONE (ADR-015), and only a COMPLETED report naming
UNKNOWN|UNKNOWN matches it. Each case's files carry its class and case (F1-c7-incident.json, healthy-c3-score.txt);
its score file records the case and its parameters, and summary.tsv gains two last columns, case and parameters
(<name>=<value>, or -). A schedule the harness cannot run (no file, another header, a class or parameter it does not
know, a case listed twice) and a --cases case the schedule does not hold exit 2 before the lab is touched.
EOF
}

ALL_FAULTS=(F1 F2 F3)
# The one symptom of every incident: what a user would report for each of the three faults, naming no cause.
SYMPTOM="Customers report that checkout orders are not completing as expected"
# Each incident's window starts with its fault's own orders (incident_window), and space_out (the baseline phase in
# decision mode) keeps the previous fault's traffic out of it, so a score never depends on how long the previous
# incident ran.
HEALTHY_ORDERS=3
FAULT_ORDERS=6
# The pause after each of those orders.
ORDER_GAP_SECONDS=2
# After the last order: Prometheus scrapes every 5 s, Loki needs a few seconds more to ingest Alloy's batch.
SETTLE_SECONDS=30
OLLAMA=http://127.0.0.1:11434
MODEL=granite4.1:8b
# Decision mode gathers its dossier as the incident opens, over the window, N minutes, and the N minutes before it, the
# previous window. N_max is the largest N incident_window can give a fault in this flow: its traffic and settle with
# every step at its time cap (each order at ORDER_MAX_SECONDS plus its gap, the injection at INJECTOR_MAX_SECONDS, then
# SETTLE_SECONDS: 363 s today), rounded up to whole minutes (7), plus the decision margin (2): 9 minutes.
FAULT_PHASE_MAX_SECONDS=$(((HEALTHY_ORDERS + FAULT_ORDERS) * (ORDER_MAX_SECONDS + ORDER_GAP_SECONDS) +
  INJECTOR_MAX_SECONDS + SETTLE_SECONDS))
DECISION_WINDOW_MAX_MINUTES=$(((FAULT_PHASE_MAX_SECONDS + 59) / 60 + DECISION_WINDOW_MARGIN_MINUTES))
# The baseline phase before each decision-mode fault lasts B = 2 * N_max + 1 minutes (19 today). The previous window
# then starts at least a minute after the phase did: the previous fault, its restore and the lab's start are older than
# both windows, and the phase's healthy orders fill the previous one.
BASELINE_MINUTES=$((2 * DECISION_WINDOW_MAX_MINUTES + 1))

selected=()
schedule=""
cases_list=""
mode=explorer
route=local
rounds=1
rounds_given=false
args=()
while (( $# )); do
  case "$1" in
    --only) [[ $# -ge 2 ]] || { echo "--only needs a fault list, e.g. --only F1,F3" >&2; exit 2; }
      IFS=, read -r -a selected <<<"$2"; shift 2 ;;
    --only=*) IFS=, read -r -a selected <<<"${1#--only=}"; shift ;;
    --mode) [[ $# -ge 2 ]] || { echo "--mode needs explorer, dossier or decision" >&2; exit 2; }
      mode=$2; shift 2 ;;
    --mode=*) mode=${1#--mode=}; shift ;;
    --route) [[ $# -ge 2 ]] || { echo "--route needs local or cloud" >&2; exit 2; }
      route=$2; shift 2 ;;
    --route=*) route=${1#--route=}; shift ;;
    --rounds) [[ $# -ge 2 ]] || { echo "--rounds needs a number from 1 to 20" >&2; exit 2; }
      rounds=$2 rounds_given=true; shift 2 ;;
    --rounds=*) rounds=${1#--rounds=} rounds_given=true; shift ;;
    --schedule) [[ $# -ge 2 ]] || { echo "--schedule needs a schedule file" >&2; exit 2; }
      schedule=$2; shift 2 ;;
    --schedule=*) schedule=${1#--schedule=}; shift ;;
    --cases) [[ $# -ge 2 ]] || { echo "--cases needs cases or ranges, such as 1-5,9" >&2; exit 2; }
      cases_list=$2; shift 2 ;;
    --cases=*) cases_list=${1#--cases=}; shift ;;
    *) args+=("$1"); shift ;;
  esac
done
parse_args "${args[@]+"${args[@]}"}"
# The schedule's cases, one "<case> TAB <class> TAB <parameters> TAB <note>" line each (read_schedule), or none.
scheduled_cases=()
if [[ -n "$schedule" ]]; then
  (( ${#selected[@]} == 0 )) && [[ "$rounds_given" == false ]] ||
    { echo "--schedule runs the schedule's cases: it takes neither --only nor --rounds" >&2; exit 2; }
  schedule_text=$(read_schedule "$schedule" "$cases_list") || exit 2
  mapfile -t scheduled_cases <<<"$schedule_text"
elif [[ -n "$cases_list" ]]; then
  echo "--cases needs --schedule" >&2
  exit 2
fi
(( ${#selected[@]} )) || selected=("${ALL_FAULTS[@]}")
for fault in "${selected[@]}"; do
  [[ " ${ALL_FAULTS[*]} " == *" $fault "* ]] || { echo "Unknown fault '$fault'; choose from ${ALL_FAULTS[*]}" >&2; exit 2; }
done
[[ "$mode" == explorer || "$mode" == dossier || "$mode" == decision ]] ||
  { echo "Unknown mode '$mode'; choose explorer, dossier or decision" >&2; exit 2; }
[[ "$route" == local || "$route" == cloud ]] || { echo "Unknown route '$route'; choose local or cloud" >&2; exit 2; }
# Checked as text before any arithmetic, so a long number cannot wrap.
[[ "$rounds" =~ ^[1-9][0-9]?$ ]] && (( rounds <= 20 )) ||
  { echo "--rounds needs a number from 1 to 20, not '$rounds'" >&2; exit 2; }
# The cloud route's key, from the shell or infra/.env (cloud_key_available); its value is never printed.
drop_empty_cloud_key
if [[ "$route" == cloud ]] && ! cloud_key_available; then echo "$CLOUD_KEY_MISSING" >&2; exit 2; fi
# compose passes them to the agent container (infra/compose.yaml); start_agent recreates the container when one changes.
export KINTSUGI_AGENT_MODE=$mode KINTSUGI_AGENT_ROUTE=$route
# How far each window reaches before its fault's own traffic: 10 min, or 2 in decision mode (window_margin).
window_margin_minutes=$(window_margin "$mode")

# Every run is logged, pass or fail: the whole output goes to run.log too. tee ignores INT and TERM, so a Ctrl-C (sent
# to the whole process group) still lets it record what the exit trap prints.
RUN_DIR="${VERIFY_AGENT_RUN_DIR:-$REPO_DIR/infra/scripts/out/verify-agent-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$RUN_DIR"
exec > >(trap '' INT TERM; exec tee -a "$RUN_DIR/run.log") 2>&1
# A schedule run adds the case and its parameters as the last two columns; without --schedule the columns are as before.
summary_columns='fault\tmode\troute\tinjection\tincident\tstatus\ttruth\treported\tsteps\twindow\tseconds\tverdict\tround'
[[ -z "$schedule" ]] || summary_columns+='\tcase\tparameters'
printf "%b\n" "$summary_columns" >"$RUN_DIR/summary.tsv"

# The fault being diagnosed, its round, the name prefix of its files, its incident and, in a schedule run, the
# summary.tsv columns of its case, until its score is written; the exit trap records an interrupted one.
current_fault=""
current_round=""
current_case_columns=""
current_label=""
current_incident=""
# When the previous fault of this run was restored (epoch seconds); space_out waits from it. Until the first restore,
# diagnose reads the last restore lib.sh recorded on disk, so the first fault also waits for a run that ended just
# before, or for a leftover injection start_lab restored.
previous_fault_end=""
cleanup() {
  local status=$?
  # The trap runs to its end. Under set -e, the "(exit "$status")" below, which hands on_exit a failed run's status,
  # would end it before on_exit: no restore of an active injection, no --down and no RESULT line.
  set +e
  if [[ -n "$current_fault" ]]; then
    fail "$current_label: not scored, the run stopped before its score"
    printf '%s\t%s\t%s\t%s\t%s\tnot scored\t-\t-\t-\t-\t-\tMISS\t%s%s\n' "$current_fault" "$mode" "$route" \
      "${injection_id:--}" \
      "${current_incident:--}" "$current_round" "$current_case_columns" >>"$RUN_DIR/summary.tsv"
  fi
  # The API has no cancel: an incident left RUNNING would make the next run wait up to 52 min for it.
  if [[ -n "$current_incident" ]]; then
    end_running_incident "$current_incident" || true
  fi
  (exit "$status")
  on_exit
}
trap cleanup EXIT
rounds_note=""
if [[ "$rounds_given" == true ]]; then rounds_note="; rounds: $rounds"; fi
if [[ -n "$schedule" ]]; then
  case_numbers=$(printf '%s\n' "${scheduled_cases[@]}" | cut -f 1 | paste -sd ' ')
  info "logging to $RUN_DIR; schedule: $schedule, cases $case_numbers; mode: $mode; route: $route"
else
  info "logging to $RUN_DIR; faults: ${selected[*]}; mode: $mode; route: $route$rounds_note"
fi
if [[ "$mode" == decision ]]; then
  info "decision mode: window margin $window_margin_minutes min; N_max $DECISION_WINDOW_MAX_MINUTES min, so a" \
    "$BASELINE_MINUTES-min baseline phase of healthy orders before each fault"
fi

# 1. python3 reads the incidents' JSON; on the local route the model must be served, or every incident ends FAILED at
# its warm-up. The cloud route has no local model to check.
if command -v python3 >/dev/null; then
  pass "python3 is available"
else
  fail "python3 is not on PATH; it reads the incidents' JSON"
  exit 1
fi
if [[ "$route" == cloud ]]; then
  info "cloud route: the local model ($MODEL on $OLLAMA) is not checked"
elif curl -fsS --max-time "$CURL_MAX_SECONDS" "$OLLAMA/api/tags" 2>/dev/null | grep -q "\"name\":\"$MODEL\""; then
  pass "Ollama serves $MODEL on $OLLAMA"
else
  fail "Ollama on $OLLAMA does not serve $MODEL (ollama pull $MODEL)"
  exit 1
fi

# 2. Start or reuse the lab, then the agent, which start_lab leaves out. start_lab tolerates a down ollama-proxy (the
# evidence tools do not need it); the agent on the local route does. On the cloud route only search_runbooks still
# needs it, and start_lab has already warned if it is down.
start_lab
read -r _ state health _ <<<"$(lab_states | grep -E '^ollama-proxy ' | head -n1)"
if [[ "$route" == cloud ]]; then
  info "cloud route: ollama-proxy is ${state:-missing} (health ${health:--}); the agent does not need it for the model"
elif [[ "$state" == running && "$health" == healthy ]]; then
  pass "ollama-proxy is running and healthy, so the agent reaches the model"
else
  fail "ollama-proxy is ${state:-missing} (health ${health:--}); the agent cannot reach the model"
  exit 1
fi
start_agent || exit 1

# Places $1 orders, one every ORDER_GAP_SECONDS after the previous answer, and logs each answer; the agent sees them
# through its tools.
place_orders() {
  local i code id status seconds
  for ((i = 1; i <= $1; i++)); do
    read -r code id status seconds <<<"$(place_order)"
    info "order $id: $code $status in ${seconds}s"
    sleep "$ORDER_GAP_SECONDS"
  done
}

# Runs fault $1 in round $2: space out from the previous one (in decision mode, a baseline phase), traffic, inject,
# traffic, incident, restore, score. In a schedule run, $3 is the case, $4 its parameters object (- for none) and $5 its
# parameters as summary.tsv shows them; $1 is then the case's class, and a healthy one (HEALTHY_CLASS) runs the same
# steps with no injection and no restore, scored against HEALTHY_TRUTH. test-lib.sh runs it in decision mode, with
# place_orders and cleanup, each read from this file from its "<name>() {" line to the first "}" alone on a line.
diagnose() {
  local fault=$1 round=$2 case_number=${3:-} parameters=${4:--} parameter_note=${5:--} label incident window opened
  local traffic_start started_at seconds body status reason reported case_columns=""
  local truth steps verdict injection incident_mode incident_route prompt_tokens completion_tokens incident_model
  local message baseline_clean=true
  if [[ -n "$case_number" ]]; then
    label="$fault-c$case_number" case_columns=$'\t'"$case_number"$'\t'"$parameter_note"
  else
    label=$(record_prefix "$fault" "$round" "$rounds_given")
  fi
  info "--- $label"
  current_fault=$fault current_round=$round current_label=$label current_incident="" current_case_columns=$case_columns
  if [[ "$mode" == decision ]]; then
    # A failed baseline (baseline_phase has said why) costs this fault only: it is not injected, it is a miss, and the
    # next fault gets a baseline of its own.
    if ! baseline_phase "$BASELINE_MINUTES" "$label"; then
      printf '%s\t%s\t%s\t-\t-\tbaseline failed\t-\t-\t-\t-\t-\tMISS\t%s%s\n' "$fault" "$mode" "$route" "$round" \
        "$case_columns" >>"$RUN_DIR/summary.tsv"
      current_fault="" current_round="" current_label="" current_incident="" current_case_columns=""
      return 0
    fi
  else
    space_out "${previous_fault_end:-$(last_fault_end)}"
  fi
  traffic_start=$(date +%s)
  place_orders "$HEALTHY_ORDERS"
  # A healthy control injects nothing; it checks, where a fault case injects, that no injection is active.
  if [[ "$fault" == "$HEALTHY_CLASS" ]]; then
    expect_no_injection "$label"
    injection=-
  else
    inject_fault "$fault" "$parameters"
    injection=$injection_id
  fi
  place_orders "$FAULT_ORDERS"
  sleep "$SETTLE_SECONDS"

  started_at=$SECONDS
  if ! opened=$(open_incident "$SYMPTOM" "$traffic_start" "$window_margin_minutes"); then
    fail "$label: the agent did not open an incident"
    exit 1
  fi
  read -r incident window <<<"$opened"
  pass "$label: incident $incident opened, window $window min"
  current_incident=$incident
  # The baseline phase covers windows up to N_max. A larger one (after a wait for a running incident) reaches back
  # before the phase: the fault fails and is scored a miss, but its run is still waited for and recorded.
  if [[ "$mode" == decision ]] && ! expect_baseline_window "$label" "$window" "$DECISION_WINDOW_MAX_MINUTES"; then
    baseline_clean=false
  fi
  if wait_incident "$incident"; then
    info "$label: incident $incident ended $incident_final_status after $((SECONDS - started_at))s"
  else
    fail "$label: incident $incident did not end within $((INCIDENT_WAIT_SECONDS / 60)) min" \
      "(last status ${incident_final_status:-unreadable}); ending it"
    # A RUNNING incident would block every later POST: end it now, or stop the run (the exit trap tries again).
    end_running_incident "$incident" || exit 1
  fi
  seconds=$((SECONDS - started_at))

  # The record: the incident with every step and the full tool outputs.
  curl -sS --max-time 30 -o "$RUN_DIR/$label-incident.json" "$AGENT/incidents/$incident?full=true" ||
    echo "WARN  could not save incident $incident" >&2
  read -r _ body <<<"$(agent_call GET "/incidents/$incident")" || true
  IFS=$'\t' read -r status reason reported steps incident_mode incident_route prompt_tokens completion_tokens incident_model \
    <<<"$(incident_fields "$body")"

  if [[ "$fault" == "$HEALTHY_CLASS" ]]; then
    truth=$HEALTHY_TRUTH
  else
    expect_restored
    injection_id=""
    truth=$(truth_cause "$injection")
  fi
  # A healthy case spaces the next one out as a fault case does, so the next window holds neither case's traffic.
  previous_fault_end=$(date +%s)

  IFS=$'\t' read -r verdict message <<<"$(agent_verdict "$mode" "$incident_mode" "$route" "$incident_route" "$status" \
    "$reason" "$reported" "$truth")"
  if [[ "$baseline_clean" == false ]]; then
    verdict=MISS
    message="window $window min, larger than N_max ($DECISION_WINDOW_MAX_MINUTES min): the baseline is not clean;\
 $message"
  fi
  if [[ "$verdict" == MATCH ]]; then pass "$label: $message"; else fail "$label: $message"; fi
  {
    echo "fault: $fault"
    echo "mode (requested, recorded): $mode, $incident_mode"
    echo "route (requested, recorded): $route, $incident_route"
    echo "model: $incident_model"
    echo "tokens (prompt, completion): $prompt_tokens, $completion_tokens"
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
    if [[ -n "$case_number" ]]; then
      echo "case: $case_number"
      echo "parameters: $parameter_note"
    fi
  } >"$RUN_DIR/$label-score.txt"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%s\n' "$fault" "$mode" "$route" "$injection" \
    "$incident" "$status" "${truth:-unreadable}" "$reported" "$steps" "$window" "$seconds" "$verdict" "$round" \
    "$case_columns" >>"$RUN_DIR/summary.tsv"
  current_fault="" current_round="" current_label="" current_incident="" current_case_columns=""
}

# 3. Each fault in turn, the whole list once per round; or each case of the schedule, in its order.
if [[ -n "$schedule" ]]; then
  for scheduled in "${scheduled_cases[@]}"; do
    IFS=$'\t' read -r case_number class parameters parameter_note <<<"$scheduled"
    diagnose "$class" 1 "$case_number" "$parameters" "$parameter_note"
  done
else
  for ((round = 1; round <= rounds; round++)); do
    for fault in "${selected[@]}"; do
      diagnose "$fault" "$round"
    done
  done
fi

info "summary ($RUN_DIR/summary.tsv):"
column -t -s $'\t' "$RUN_DIR/summary.tsv" 2>/dev/null || command cat "$RUN_DIR/summary.tsv"
info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
