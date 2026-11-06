#!/usr/bin/env bash
# Tests for lib.sh's pure helpers (no lab, no docker): incident_fields and agent_verdict, which verify-agent.sh uses
# to score an incident, the windows and the baseline phase (sleep, the clock and the orders stubbed), and more; also
# the options of replay-agent.sh and verify-agent.sh, verify-agent.sh's own flow for one decision-mode fault (the lab
# stubbed), check-recordings.sh, and record-replay.sh on temporary directories. Run: infra/scripts/test-lib.sh. Exits
# non-zero on the first failing case.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

cases=0
expect() {
  cases=$((cases + 1))
  if [[ "$2" != "$3" ]]; then
    printf 'FAIL  %s\n  expected: %q\n  actual:   %q\n' "$1" "$3" "$2"
    exit 1
  fi
}
tab=$'\t'

# incident_fields: status, failure reason, reported cause, steps, mode, route, prompt tokens, completion tokens, model.
unreadable="unreadable${tab}-${tab}none${tab}0${tab}-${tab}-${tab}-${tab}-${tab}-"
expect "a completed dossier incident on the cloud route, with tokens on every model step" \
  "$(incident_fields '{"status":"COMPLETED","mode":"dossier","route":"cloud","model":"claude-haiku-5-5",
    "steps":[{"kind":"PLAN"},{"kind":"ACT","promptTokens":1200,"completionTokens":80},
             {"kind":"REPORT","promptTokens":3000,"completionTokens":400}],
    "report":{"mode":"dossier","route":"local","rootCauseService":"gateway-mock","rootCauseCategory":"DEPENDENCY_LATENCY"}}')" \
  "COMPLETED${tab}-${tab}gateway-mock|DEPENDENCY_LATENCY${tab}3${tab}dossier${tab}cloud${tab}4200${tab}480${tab}claude-haiku-5-5"
expect "the incident's own mode, route and model win over the report's" \
  "$(incident_fields '{"report":{"mode":"dossier","route":"cloud","model":"granite4.1:8b"},"status":"COMPLETED","mode":"explorer","route":"local"}')" \
  "COMPLETED${tab}-${tab}none${tab}0${tab}explorer${tab}local${tab}none${tab}none${tab}none"
expect "a readable incident with no mode and no route" \
  "$(incident_fields '{"status":"FAILED","failureReason":"no  report"}')" \
  "FAILED${tab}no report${tab}none${tab}0${tab}none${tab}none${tab}none${tab}none${tab}none"
expect "steps without token counts total none, not 0" \
  "$(incident_fields '{"status":"FAILED","route":"local","steps":[{"kind":"ACT"},{"kind":"ACT","promptTokens":null}]}')" \
  "FAILED${tab}-${tab}none${tab}2${tab}none${tab}local${tab}none${tab}none${tab}none"
expect "partial counts total each count on its own" \
  "$(incident_fields '{"status":"TIMED_OUT","route":"cloud","steps":[{"kind":"ACT","promptTokens":900},
    {"kind":"ACT","promptTokens":100,"completionTokens":0},{"kind":"ACT","completionTokens":"7"}]}')" \
  "TIMED_OUT${tab}-${tab}none${tab}3${tab}none${tab}cloud${tab}1000${tab}0${tab}none"
expect "a body that is not JSON" "$(incident_fields 'not json')" "$unreadable"
expect "an empty body" "$(incident_fields '')" "$unreadable"
expect "a JSON array" "$(incident_fields '[1]')" "$unreadable"

# agent_verdict: requested mode, incident mode, requested route, incident route, status, reason, reported, truth ->
# verdict and message.
truth='gateway-mock|DEPENDENCY_LATENCY'
expect "a match in the requested mode and route" \
  "$(agent_verdict dossier dossier cloud cloud COMPLETED - "$truth" "$truth")" \
  "MATCH${tab}the report names $truth, the truth"
expect "a right cause in another mode is a miss that keeps the outcome" \
  "$(agent_verdict dossier explorer local local COMPLETED - "$truth" "$truth")" \
  "MISS${tab}ran in mode 'explorer', not the requested 'dossier'; status COMPLETED, reported '$truth', truth '$truth'"
expect "a readable incident with no mode is a mode miss" \
  "$(agent_verdict dossier none local local FAILED 'no report' none "$truth")" \
  "MISS${tab}ran in mode 'none', not the requested 'dossier'; status FAILED (no report), reported 'none', truth '$truth'"
expect "a right cause on another route is a miss that keeps the outcome" \
  "$(agent_verdict explorer explorer local cloud COMPLETED - "$truth" "$truth")" \
  "MISS${tab}ran on route 'cloud', not the requested 'local'; status COMPLETED, reported '$truth', truth '$truth'"
expect "a readable incident with no route is a route miss" \
  "$(agent_verdict explorer explorer cloud none COMPLETED - "$truth" "$truth")" \
  "MISS${tab}ran on route 'none', not the requested 'cloud'; status COMPLETED, reported '$truth', truth '$truth'"
expect "a mode and a route mismatch are both named" \
  "$(agent_verdict dossier explorer cloud local COMPLETED - "$truth" "$truth")" \
  "MISS${tab}ran in mode 'explorer', not the requested 'dossier'; ran on route 'local', not the requested 'cloud';\
 status COMPLETED, reported '$truth', truth '$truth'"
expect "an unreadable incident fails on its status, not on its mode or route" \
  "$(agent_verdict dossier - cloud - unreadable - none "$truth")" \
  "MISS${tab}status unreadable, reported 'none', truth '$truth'"
expect "a timeout in the requested mode and route" \
  "$(agent_verdict explorer explorer local local TIMED_OUT 'ACT deadline' none "$truth")" \
  "MISS${tab}status TIMED_OUT (ACT deadline), reported 'none', truth '$truth'"
expect "a wrong category" \
  "$(agent_verdict explorer explorer local local COMPLETED - 'gateway-mock|DEPENDENCY_REJECTING' "$truth")" \
  "MISS${tab}status COMPLETED, reported 'gateway-mock|DEPENDENCY_REJECTING', truth '$truth'"
expect "an unreadable truth never matches" \
  "$(agent_verdict explorer explorer local local COMPLETED - "$truth" '')" \
  "MISS${tab}status COMPLETED, reported '$truth', truth 'unreadable'"
expect "a match in decision mode" \
  "$(agent_verdict decision decision cloud cloud COMPLETED - "$truth" "$truth")" \
  "MATCH${tab}the report names $truth, the truth"
expect "a dossier-mode incident is a miss when decision mode was asked" \
  "$(agent_verdict decision dossier cloud cloud COMPLETED - "$truth" "$truth")" \
  "MISS${tab}ran in mode 'dossier', not the requested 'decision'; status COMPLETED, reported '$truth', truth '$truth'"

# env_file_has_value: reads ENV_FILE, never prints the value.
ENV_FILE=$(mktemp)
trap 'rm -f "$ENV_FILE"' EXIT
has() { if env_file_has_value "$1"; then echo yes; else echo no; fi; }
printf '# KEY=commented\nOTHER=1\n' >"$ENV_FILE"
expect "a variable the file does not define" "$(has KEY)" no
printf 'KEY=\nKEY_SUFFIX=1\n' >"$ENV_FILE"
expect "an empty value, and a longer name that only starts with it" "$(has KEY)" no
printf 'KEY=""\n' >"$ENV_FILE"
expect "an empty quoted value" "$(has KEY)" no
printf 'KEY= # a comment\n' >"$ENV_FILE"
expect "a value that is only a comment" "$(has KEY)" no
printf '  export KEY="v"\n' >"$ENV_FILE"
expect "an exported, quoted value" "$(has KEY)" yes
printf 'KEY=v\nKEY=\n' >"$ENV_FILE"
expect "the last definition wins" "$(has KEY)" no
printf 'KEY=v' >"$ENV_FILE"
expect "a last line with no newline" "$(has KEY)" yes
expect "the output is empty" "$(env_file_has_value KEY)" ""
rm -f "$ENV_FILE"
expect "a missing file" "$(has KEY)" no

# drop_empty_cloud_key, then cloud_key_available: the shell's key, else ENV_FILE's; an empty shell value is dropped so
# it cannot hide the file's.
key() { drop_empty_cloud_key; if cloud_key_available; then echo "yes ${ANTHROPIC_API_KEY-unset}"; else echo "no ${ANTHROPIC_API_KEY-unset}"; fi; }
printf 'ANTHROPIC_API_KEY=from-file\n' >"$ENV_FILE"
expect "a key in the shell" "$(ANTHROPIC_API_KEY=k; key)" "yes k"
expect "an empty shell key is dropped for the file's" "$(ANTHROPIC_API_KEY=; key)" "yes unset"
expect "no shell key, a key in the file" "$(unset ANTHROPIC_API_KEY; key)" "yes unset"
printf 'ANTHROPIC_API_KEY=\n' >"$ENV_FILE"
expect "an empty shell key and an empty file key" "$(ANTHROPIC_API_KEY=; key)" "no unset"
expect "a key in the shell alone is enough" "$(ANTHROPIC_API_KEY=k; key)" "yes k"
rm -f "$ENV_FILE"
expect "no key and no file" "$(unset ANTHROPIC_API_KEY; key)" "no unset"
# The check fails here (no key, no file); "|| true" keeps the echo, and the brackets tell "set, empty" from no output.
expect "the check alone changes nothing" \
  "$(ANTHROPIC_API_KEY=; cloud_key_available || true; echo "[${ANTHROPIC_API_KEY-unset}]")" "[]"

# export_cloud_key: the key a child process sees (Maven in replay-agent.sh), the shell's else the file's; never printed.
child() { if export_cloud_key; then echo "yes $(bash -c 'echo "${ANTHROPIC_API_KEY-unset}"')"; else echo "no ${ANTHROPIC_API_KEY-unset}"; fi; }
printf 'export ANTHROPIC_API_KEY="from-file" # a comment\n' >"$ENV_FILE"
expect "the file's key reaches a child process" "$(unset ANTHROPIC_API_KEY; child)" "yes from-file"
expect "an empty shell key is dropped for the file's" "$(ANTHROPIC_API_KEY=; child)" "yes from-file"
expect "the shell's key wins and is exported" "$(declare +x ANTHROPIC_API_KEY=k; child)" "yes k"
expect "the export prints nothing" "$(unset ANTHROPIC_API_KEY; export_cloud_key)" ""
printf 'ANTHROPIC_API_KEY=\n' >"$ENV_FILE"
expect "no key anywhere fails and leaves it unset" "$(ANTHROPIC_API_KEY=; child)" "no unset"
rm -f "$ENV_FILE"

# record_fault_end and last_fault_end: the last restore time, kept in LAST_FAULT_END_FILE across runs.
FAULT_END_DIR=$(mktemp -d)
trap 'rm -f "$ENV_FILE"; rm -rf "$FAULT_END_DIR"' EXIT
LAST_FAULT_END_FILE="$FAULT_END_DIR/out/.last-fault-end"
expect "no file, no restore time" "$(last_fault_end)" ""
record_fault_end 1700000000
expect "a recorded restore time, in a directory created for it" "$(last_fault_end)" 1700000000
before=$(date +%s); record_fault_end; recorded=$(last_fault_end)
expect "the default restore time is now" "$(( recorded >= before && recorded <= $(date +%s) ))" 1
printf 'garbage\n' >"$LAST_FAULT_END_FILE"
expect "a file that holds no epoch second" "$(last_fault_end)" ""
printf '0\n' >"$LAST_FAULT_END_FILE"
expect "a zero epoch second" "$(last_fault_end)" ""
: >"$LAST_FAULT_END_FILE"
expect "an empty file" "$(last_fault_end)" ""
printf '%s\n' $(( $(date +%s) + 3600 )) >"$LAST_FAULT_END_FILE"
expect "a restore time in the future, which would wait without bound" "$(last_fault_end)" ""

# restore_injection records the restore time only when the injector confirms it; injector_call is stubbed.
rm -f "$LAST_FAULT_END_FILE"
injector_call() { echo "500 {}"; }
restore_injection || true
expect "a refused restore records nothing" "$(last_fault_end)" ""
injector_call() { echo '200 {"status":"RESTORED"}'; }
restore_injection
recorded=$(last_fault_end)
expect "a confirmed restore records its time" "$(( ${recorded:-0} >= $(date +%s) - 5 ))" 1
unset -f injector_call

# expect_no_injection (a healthy case's check, lab-realism-min T7): a transport failure (000, no status code) is asked
# again up to NO_INJECTION_ATTEMPTS times, with a pause, so an injector slow to answer does not end the session; a 204
# passes, a 200 (an injection is active) still fails and exits at once, and so does a persistent non-answer.
# injector_call answers from its arguments, one code per call (000 once they run out), counted in a file since it runs
# in a command substitution; sleep is stubbed, so no case waits. Each case runs in a subshell, since it exits.
no_injection() {
  (
    answers=("$@")
    calls_file="$FAULT_END_DIR/injector-calls"
    : >"$calls_file"
    injector_call() {
      local n
      n=$(wc -l <"$calls_file")
      echo "$1 $2" >>"$calls_file"
      echo "${answers[$n]:-000} "
    }
    sleep() { echo "slept $1"; }
    expect_no_injection healthy-c2
    echo "exit 0 after $(wc -l <"$calls_file") call(s)"
  ) 2>&1 || echo "exit $?"
}
pause=${NO_INJECTION_PAUSE_SECONDS:-unset}
attempts=${NO_INJECTION_ATTEMPTS:-unset}
expect "no injection active passes at the first answer" "$(no_injection 204)" \
  "PASS  healthy-c2: no injection is active, so none is in its windows"$'\n'"exit 0 after 1 call(s)"
expect "an injector that does not answer at first is asked again after a pause" "$(no_injection 000 000 204)" \
  "....  healthy-c2: GET /faults/active got no answer (attempt 1 of $attempts); asking again in ${pause}s
slept $pause
....  healthy-c2: GET /faults/active got no answer (attempt 2 of $attempts); asking again in ${pause}s
slept $pause
PASS  healthy-c2: no injection is active, so none is in its windows
exit 0 after 3 call(s)"
expect "an active injection fails and exits at once, with no retry" "$(no_injection 200)" \
  "FAIL  healthy-c2: GET /faults/active answered 200, not 204: an injection is active, so the healthy control is not\
 healthy; nothing opened"$'\n'"exit 1"
expect "an active injection after a non-answer still fails and exits" "$(no_injection 000 200 | tail -n 2)" \
  "FAIL  healthy-c2: GET /faults/active answered 200, not 204: an injection is active, so the healthy control is not\
 healthy; nothing opened"$'\n'"exit 1"
expect "an injector that never answers fails and exits after the last attempt, with no pause after it" \
  "$(no_injection | tail -n 3)" \
  "slept $pause
FAIL  healthy-c2: GET /faults/active got no answer in $attempts attempts: the injector at $INJECTOR may be\
 down, so whether an injection is active is unknown; nothing opened"$'\n'"exit 1"
expect "an injector that never answers is asked NO_INJECTION_ATTEMPTS times, the only call each time" \
  "$(no_injection >/dev/null; sort -u "$FAULT_END_DIR/injector-calls"; wc -l <"$FAULT_END_DIR/injector-calls")" \
  "GET /faults/active"$'\n'"$attempts"

# space_out, with sleep stubbed so no case waits: no wait without a restore time or once the margin has passed.
sleep() { echo "slept $1"; }
expect "no restore time, no wait" "$(space_out "")" ""
expect "a restore older than the margin, no wait" \
  "$(space_out $(( $(date +%s) - (INCIDENT_WINDOW_MARGIN_MINUTES + 2) * 60 )))" ""
waited=$(space_out "$(date +%s)" | tail -n 1)
expect "a restore just now waits the margin and a minute" \
  "$(( ${waited#slept } >= (INCIDENT_WINDOW_MARGIN_MINUTES + 1) * 60 - 1 ))" 1
unset -f sleep

# window_margin and incident_window: a window is the minutes since the fault's traffic started, rounded up, plus the
# margin of the agent's mode: 10 in explorer and dossier mode, 2 in decision mode, which gathers its dossier as the
# incident opens.
expect "explorer mode keeps the 10-minute margin" "$(window_margin explorer)" 10
expect "dossier mode keeps the 10-minute margin" "$(window_margin dossier)" 10
expect "decision mode has a 2-minute margin" "$(window_margin decision)" 2
expect "a window with no margin given keeps the 10 minutes" "$(incident_window $(( $(date +%s) - 50 )))" 11
expect "a decision-mode window over 50 s of traffic" \
  "$(incident_window $(( $(date +%s) - 50 )) "$(window_margin decision)")" 3
expect "a decision-mode window over 61 s of traffic" "$(incident_window $(( $(date +%s) - 61 )) 2)" 4

# baseline_phase, with the clock, sleep and the orders stubbed (each order's id is the second it was placed at): one
# healthy order every BASELINE_ORDER_SECONDS from the phase's start, each expected to be PAID, then it returns $1
# minutes after it started.
clock=1700000000
date() { echo "$clock"; }
sleep() { clock=$((clock + $1)); }
place_order() { echo "201 at-$((clock - 1700000000)) PAID 0.1"; }
phase=$(baseline_phase 19 F1-r1; echo "ended at $((clock - 1700000000))")
order_times=$(awk '/^PASS  baseline order at-[0-9]+ is PAID$/ { sub(/^PASS  baseline order at-/, ""); print $1 }' \
  <<<"$phase")
expect "one healthy order every 50 s, from the phase's start" "$(printf '%s ' $order_times)" \
  "0 50 100 150 200 250 300 350 400 450 500 550 600 650 700 750 800 850 900 950 1000 1050 1100 "
expect "the phase returns 19 minutes after it started" "$(tail -n 1 <<<"$phase")" "ended at 1140"
# The shortest previous window in decision mode is 3 minutes, whole minutes back from the gathering, and the clock reads
# whole seconds: any 179 s of the phase must hold 3 orders, which one order a minute would not.
fewest=$(awk -v span=179 -v phase=1140 '{ t[NR] = $1 }
  END {
    fewest = NR
    for (s = 0; s + span <= phase; s++) {
      n = 0
      for (i = 1; i <= NR; i++) if (t[i] >= s && t[i] < s + span) n++
      if (n < fewest) fewest = n
    }
    print fewest
  }' <<<"$order_times")
expect "any 179 s of the phase hold at least 3 orders" "$fewest" 3
# A baseline order that is not PAID is logged and the phase starts over from it, so that order too ages out of both
# windows: here the order at second 100 is refused, and the phase restarts there and ends 19 minutes later.
place_order() {
  if (( clock - 1700000000 == 100 )); then
    echo "503 at-100 none 3.0"
  else
    echo "201 at-$((clock - 1700000000)) PAID 0.1"
  fi
}
clock=1700000000
phase=$(baseline_phase 19 F3-r1 2>&1; echo "status $? ended at $((clock - 1700000000))")
expect "a refused baseline order restarts the phase, which ends 19 minutes after it" "$(tail -n 1 <<<"$phase")" \
  "status 0 ended at 1240"
expect "the refused order is logged with the restart" "$(grep -F 'WARN' <<<"$phase")" \
  "WARN  F3-r1: baseline order at-100: expected 201 PAID, got 503 none; the baseline starts over (restart 1 of 2)"
expect "the orders resume one interval after the restart" \
  "$(awk '/^PASS  baseline order at-[0-9]+ is PAID$/ { sub(/^PASS  baseline order at-/, ""); printf "%s ", $1 }' \
    <<<"$phase")" \
  "0 50 150 200 250 300 350 400 450 500 550 600 650 700 750 800 850 900 950 1000 1050 1100 1150 1200 "
# The third refused order fails the fault, after 2 restarts. The phase then returns instead of stopping the run, so the
# caller scores the fault a miss and goes on, and the next fault gets a fresh baseline: verify-agent.sh's loop, under
# set -e.
place_order() {
  if [[ "$fault_now" == F1-r1 ]]; then
    echo "503 at-$((clock - 1700000000)) none 3.0"
  else
    echo "201 at-$((clock - 1700000000)) PAID 0.1"
  fi
}
run=$( {
  set -e
  failures=0
  for fault_now in F1-r1 F2-r1; do
    clock=1700000000
    if baseline_phase 19 "$fault_now"; then echo "$fault_now: baseline clean"; else echo "$fault_now: scored a miss"; fi
  done
  echo "failures $failures"
} 2>&1 )
expect "three refused orders fail the fault, and the run goes on to the next fault" \
  "$(grep -v -e '^PASS  ' -e '^\.\.\.\.  ' <<<"$run")" \
  "WARN  F1-r1: baseline order at-0: expected 201 PAID, got 503 none; the baseline starts over (restart 1 of 2)
WARN  F1-r1: baseline order at-50: expected 201 PAID, got 503 none; the baseline starts over (restart 2 of 2)
FAIL  F1-r1: baseline order at-100: expected 201 PAID, got 503 none after 2 restarts; the baseline failed, so the\
 fault is not injected and is scored a miss
F1-r1: scored a miss
F2-r1: baseline clean
failures 1"
expect "the next fault's baseline is whole: 23 PAID orders" "$(grep -c '^PASS  baseline order' <<<"$run")" 23
unset -f date sleep place_order

# expect_baseline_window: in decision mode a window larger than N_max fails its fault, since its previous window then
# starts before the baseline phase.
failures=0
expect "a window of N_max passes, silently" \
  "$(expect_baseline_window F2 9 9; echo "status $? failures $failures")" "status 0 failures 0"
expect "a window larger than N_max fails the fault" \
  "$(expect_baseline_window F2-r3 10 9; echo "status $? failures $failures")" \
  "FAIL  F2-r3: window 10 min is larger than N_max (9 min): its previous window starts before the baseline phase, so\
 the baseline is not clean"$'\n'"status 1 failures 1"

# record_prefix: a fault's files carry the round only when the run was given --rounds.
expect "without --rounds, the names stay as they were" "$(record_prefix F1 1 false)" F1
expect "with --rounds, the name carries the round" "$(record_prefix F3 2 true)" F3-r2
expect "with --rounds 1 too" "$(record_prefix F1 1 true)" F1-r1

# holds_key_like_string: record-replay.sh refuses a recording that holds something shaped like an API key.
KEY_DIR=$(mktemp -d)
trap 'rm -f "$ENV_FILE"; rm -rf "$FAULT_END_DIR" "$KEY_DIR"' EXIT
key_like() { printf '%s' "$1" >"$KEY_DIR/f"; if holds_key_like_string "$KEY_DIR/f"; then echo yes; else echo no; fi; }
expect "an Anthropic key prefix" "$(key_like '{"toolOutput":"token sk-ant-api03-abc"}')" yes
expect "the key variable with a value" "$(key_like 'env: ANTHROPIC_API_KEY=abc123')" yes
expect "the key variable with a quoted value, JSON-escaped" "$(key_like '"ANTHROPIC_API_KEY=\"abc\""')" yes
expect "an x-api-key header" "$(key_like '{"headers":{"x-api-key":"v"}}')" yes
expect "an X-Api-Key header, any case" "$(key_like 'X-Api-Key: v')" yes
expect "the key variable with an empty value" "$(key_like 'ANTHROPIC_API_KEY=')" no
expect "the key variable with an empty quoted value" "$(key_like 'ANTHROPIC_API_KEY=""')" no
expect "the key variable followed by a space" "$(key_like 'ANTHROPIC_API_KEY= # unset')" no
expect "the key variable named without a value" "$(key_like 'needs ANTHROPIC_API_KEY in infra/.env')" no
expect "an ordinary incident" "$(key_like '{"steps":[{"toolOutput":"order-service WARN payment pending, ask-ant"}]}')" no
expect "the check prints nothing" "$(printf 'sk-ant-x' >"$KEY_DIR/f"; holds_key_like_string "$KEY_DIR/f")" ""
expect "a missing file is refused, not passed" \
  "$(if holds_key_like_string "$KEY_DIR/missing"; then echo yes; else echo no; fi)" yes

# score_fields: fault, recorded mode, recorded route, truth and incident from a verify-agent score file, none when
# absent.
score='fault: F1
mode (requested, recorded): dossier, explorer
route (requested, recorded): cloud, cloud
model: claude-haiku-5-5
incident: 34d23554-fc45-452d-8f71-cf64be2e2b87
truth (service|category): gateway-mock|DEPENDENCY_LATENCY
reported (service|category, from the report): payment-service|DEPENDENCY_LATENCY
verdict: MISS'
expect "a full score file, with the recorded mode and route" "$(score_fields "$score")" \
  "F1${tab}explorer${tab}cloud${tab}gateway-mock|DEPENDENCY_LATENCY${tab}34d23554-fc45-452d-8f71-cf64be2e2b87"
expect "a score file with no truth and no route" "$(score_fields $'fault: F2\nmode (requested, recorded): dossier, dossier\ntruth (service|category): ')" \
  "F2${tab}dossier${tab}none${tab}none${tab}none"
expect "an empty score file" "$(score_fields '')" "none${tab}none${tab}none${tab}none${tab}none"

# read_schedule: holdout2-schedule.py's schedule.tsv as verify-agent.sh --schedule runs it, one line per selected case:
# case, class, the parameters POST /faults takes (- for none) and the schedule's parameter as a note (- for none).
SCHEDULE_FILE="$KEY_DIR/schedule.tsv"
printf 'case\tclass\tparameter\tvalue\n1\tF2\tgateway_error_status\t503\n2\thealthy\t-\t-
3\tF1\tgateway_latency_ms\t5000\n4\tF3\twrong_port\t9999\n5\thealthy\t-\t-\n' >"$SCHEDULE_FILE"
expect "every case of a schedule, in its order, F1 and F2 with their nested parameter, F3 and healthy with none" \
  "$(read_schedule "$SCHEDULE_FILE")" \
  "1${tab}F2${tab}{\"gatewayErrorStatus\":503}${tab}gateway_error_status=503
2${tab}healthy${tab}-${tab}-
3${tab}F1${tab}{\"gatewayLatencyMs\":5000}${tab}gateway_latency_ms=5000
4${tab}F3${tab}-${tab}wrong_port=9999
5${tab}healthy${tab}-${tab}-"
expect "--cases picks cases and ranges, in the schedule's order whatever the list's" \
  "$(read_schedule "$SCHEDULE_FILE" 5,2-3 | cut -f 1,2)" "2${tab}healthy"$'\n'"3${tab}F1"$'\n'"5${tab}healthy"
# Prints what read_schedule printed on stderr and its status, for schedule $1 and cases $2.
schedule_refusal() { read_schedule "$1" "${2:-}" 2>&1 >/dev/null; echo "exit $?"; }
expect "a case the schedule does not hold is refused" "$(schedule_refusal "$SCHEDULE_FILE" 2,9)" \
  "--cases: case 9 is not in $SCHEDULE_FILE"$'\n'"exit 1"
expect "a range that ends before it starts is refused" "$(schedule_refusal "$SCHEDULE_FILE" 4-2)" \
  "--cases: the range 4-2 ends before it starts"$'\n'"exit 1"
expect "a list that is not cases is refused" "$(schedule_refusal "$SCHEDULE_FILE" 'one')" \
  "--cases needs cases or ranges, such as 1-5,9, not 'one'"$'\n'"exit 1"
expect "a missing schedule is refused" "$(schedule_refusal "$KEY_DIR/none.tsv")" \
  "--schedule: no file $KEY_DIR/none.tsv"$'\n'"exit 1"
# Writes schedule $1 with the header and row $2, and prints its refusal.
bad_schedule() { printf '%s\n' "${3:-case${tab}class${tab}parameter${tab}value}" "$2" >"$KEY_DIR/$1.tsv"; schedule_refusal "$KEY_DIR/$1.tsv"; }
expect "a file without the generator's header is refused" "$(bad_schedule header "1${tab}F1" "case${tab}class")" \
  "--schedule: $KEY_DIR/header.tsv does not start with the header case, class, parameter, value"$'\n'"exit 1"
expect "an unknown class is refused" "$(bad_schedule class "1${tab}F4${tab}-${tab}-")" \
  "--schedule: $KEY_DIR/class.tsv line 2: class F4 with parameter - and value -, which the harness cannot run"\
$'\n'"exit 1"
expect "an F1 latency that is not a number is refused" "$(bad_schedule latency "1${tab}F1${tab}gateway_latency_ms${tab}5s")" \
  "--schedule: $KEY_DIR/latency.tsv line 2: class F1 with parameter gateway_latency_ms and value 5s, which the harness\
 cannot run"$'\n'"exit 1"
expect "an F3 port the injector does not apply is refused" "$(bad_schedule port "1${tab}F3${tab}wrong_port${tab}8080")" \
  "--schedule: $KEY_DIR/port.tsv line 2: class F3 with parameter wrong_port and value 8080, which the harness cannot run"\
$'\n'"exit 1"
expect "a parameter on a healthy case is refused" "$(bad_schedule healthy "1${tab}healthy${tab}wrong_port${tab}9999")" \
  "--schedule: $KEY_DIR/healthy.tsv line 2: class healthy with parameter wrong_port and value 9999, which the harness\
 cannot run"$'\n'"exit 1"
expect "a case number twice is refused" "$(bad_schedule twice "1${tab}F3${tab}wrong_port${tab}9999"$'\n'"1${tab}healthy${tab}-${tab}-")" \
  "--schedule: $KEY_DIR/twice.tsv line 3: case 1 is listed twice"$'\n'"exit 1"
expect "a schedule with no case is refused" "$(bad_schedule empty "")" \
  "--schedule: $KEY_DIR/empty.tsv holds no case"$'\n'"exit 1"

# fault_request: the body of POST /faults, with the parameters nested under "parameters" (the injector ignores a
# parameter at the top level, odd/tasks/lab-realism-min.md T4), or none.
expect "an F1 request nests its latency" "$(fault_request F1 '{"gatewayLatencyMs":5000}')" \
  '{"fault":"F1","parameters":{"gatewayLatencyMs":5000}}'
expect "an F3 request has no parameters" "$(fault_request F3 -)" '{"fault":"F3"}'
expect "a request without parameters is today's" "$(fault_request F2)" '{"fault":"F2"}'
# inject_fault sends that body to the injector (stubbed: it records the request and answers 201).
injected=$(
  injector_call() { echo "$1 $2 ${3:-}" >"$KEY_DIR/injector-request"; echo '201 {"id":"6f1c2a4e-0d7b-4c3e-9a51-2b8e7d9c4f10"}'; }
  inject_fault F2 '{"gatewayErrorStatus":429}' >/dev/null
  cat "$KEY_DIR/injector-request"
)
expect "inject_fault posts the nested parameter" "$injected" \
  'POST /faults {"fault":"F2","parameters":{"gatewayErrorStatus":429}}'

# agent_verdict on a healthy control (truth NONE|NONE, ADR-015): UNKNOWN|UNKNOWN is right, a cause is a false alarm.
expect "a healthy control answered UNKNOWN|UNKNOWN is a match" \
  "$(agent_verdict decision decision cloud cloud COMPLETED - 'UNKNOWN|UNKNOWN' 'NONE|NONE')" \
  "MATCH${tab}the report names no cause (UNKNOWN|UNKNOWN), right on a healthy control"
expect "a healthy control answered with a cause is a false alarm" \
  "$(agent_verdict decision decision cloud cloud COMPLETED - "$truth" 'NONE|NONE')" \
  "MISS${tab}a false alarm on a healthy control: status COMPLETED, reported '$truth', truth 'NONE|NONE'"
expect "a healthy control with no report is a miss" \
  "$(agent_verdict decision decision cloud cloud FAILED 'no report' none 'NONE|NONE')" \
  "MISS${tab}status FAILED (no report), reported 'none', truth 'NONE|NONE'"
expect "the healthy truth is the scorer's" "$HEALTHY_TRUTH" \
  "$(grep -oE '^HEALTHY_TRUTH = "[^"]*"' "$REPO_DIR/evals/kintsugi_evals/scoring.py" | cut -d'"' -f2)"

# env_file_read writes the caller's variable even when it has the name of one of the function's own locals.
ENV_FILE="$KEY_DIR/env"
printf 'ANTHROPIC_API_KEY=dummy-for-test\n' >"$ENV_FILE"
read_into_value() { local value=""; env_file_read ANTHROPIC_API_KEY value; echo "[$value]"; }
expect "a caller variable named value" "$(read_into_value)" "[dummy-for-test]"
read_into_line() { local line=""; env_file_read ANTHROPIC_API_KEY line; echo "[$line]"; }
expect "a caller variable named line" "$(read_into_line)" "[dummy-for-test]"
expect "a target named like the function's own locals is refused" \
  "$(if env_file_read ANTHROPIC_API_KEY _efr_value 2>/dev/null; then echo yes; else echo no; fi)" no
rm -f "$ENV_FILE"

# manifest_upsert: record-replay.sh's manifest keeps one line per file (column 5), the latest values.
MANIFEST_FILE="$KEY_DIR/recordings.tsv"
printf 'fault\tmode\troute\ttruth\tfile\tincident\n' >"$MANIFEST_FILE"
row_a="F1${tab}dossier${tab}cloud${tab}a|B${tab}F1/run-F1.json${tab}id-1"
row_b="F2${tab}dossier${tab}cloud${tab}c|D${tab}F2/run-F2.json${tab}id-2"
expect "a new file is added" "$(manifest_upsert "$MANIFEST_FILE" "$row_a")" added
manifest_upsert "$MANIFEST_FILE" "$row_b" >/dev/null
expect "the same line again is unchanged" "$(manifest_upsert "$MANIFEST_FILE" "$row_a")" unchanged
row_a2="F1${tab}explorer${tab}cloud${tab}a|B${tab}F1/run-F1.json${tab}id-9"
expect "a re-recorded file replaces its line" "$(manifest_upsert "$MANIFEST_FILE" "$row_a2")" replaced
expect "the manifest keeps its order, one line per file, the new values" "$(<"$MANIFEST_FILE")" \
  "fault${tab}mode${tab}route${tab}truth${tab}file${tab}incident"$'\n'"$row_a2"$'\n'"$row_b"
chmod 444 "$MANIFEST_FILE"
expect "a manifest that cannot be written fails, with no outcome" \
  "$(manifest_upsert "$MANIFEST_FILE" "$row_a" 2>/dev/null; echo "exit $?")" "exit 1"
chmod 644 "$MANIFEST_FILE"

# replay-agent.sh bounds --samples' digits before multiplying, so the cloud bound cannot wrap. Run on the local route
# with a failing curl stub, so a regression stops at the Ollama check: never Maven, never the key.
STUB_DIR="$KEY_DIR/bin"
mkdir -p "$STUB_DIR"
printf '#!/bin/sh\nexit 7\n' >"$STUB_DIR/curl"
chmod +x "$STUB_DIR/curl"
samples_check() {
  local err code
  err=$(PATH="$STUB_DIR:$PATH" "$(dirname "${BASH_SOURCE[0]}")/replay-agent.sh" --route local --samples "$1" 2>&1 >/dev/null)
  code=$?
  echo "$code ${err%% *}"
}
expect "a 4-digit --samples exits 2 on the option itself" "$(samples_check 1000)" "2 --samples"
expect "a --samples that would wrap exits 2 on the option itself" "$(samples_check 30744573456182586)" "2 --samples"

# verify-agent.sh checks its options before it touches the lab. Run on the local route with the failing curl stub, so a
# run that passes its options stops at the Ollama check: never docker, never the lab.
verify_agent() {
  VERIFY_AGENT_RUN_DIR="$KEY_DIR/$1" PATH="$STUB_DIR:$PATH" "$(dirname "${BASH_SOURCE[0]}")/verify-agent.sh" \
    --route local "${@:2}" 2>&1
}
rounds_check() {
  local out
  out=$(verify_agent rounds --rounds "$1")
  echo "$? ${out%% *}"
}
expect "--rounds 0 exits 2 on the option itself" "$(rounds_check 0)" "2 --rounds"
expect "--rounds 21 exits 2 on the option itself" "$(rounds_check 21)" "2 --rounds"
expect "a --rounds that is not a number exits 2 on the option itself" "$(rounds_check 2x)" "2 --rounds"
decision=$(verify_agent decision --mode decision --rounds 2)
expect "decision mode with rounds passes the options and stops at the Ollama check" "$?" 1
expect "the run names its rounds" "$(grep -F '....  logging to' <<<"$decision")" \
  "....  logging to $KEY_DIR/decision; faults: F1 F2 F3; mode: decision; route: local; rounds: 2"
expect "decision mode's margin, N_max and baseline come from the script's constants" \
  "$(grep -F '....  decision mode:' <<<"$decision")" \
  "....  decision mode: window margin 2 min; N_max 9 min, so a 19-min baseline phase of healthy orders before each\
 fault"
expect "summary.tsv's last column is the round" "$(head -n 1 "$KEY_DIR/decision/summary.tsv")" \
  "fault${tab}mode${tab}route${tab}injection${tab}incident${tab}status${tab}truth${tab}reported${tab}steps${tab}window\
${tab}seconds${tab}verdict${tab}round"
expect "explorer mode logs as before: no rounds, no baseline" "$(verify_agent explorer | grep -F '....  ')" \
  "....  logging to $KEY_DIR/explorer; faults: F1 F2 F3; mode: explorer; route: local"
expect "without --schedule, summary.tsv has today's columns" "$(head -n 1 "$KEY_DIR/explorer/summary.tsv")" \
  "$(head -n 1 "$KEY_DIR/decision/summary.tsv")"
# --schedule runs holdout2-schedule.py's cases, --cases some of them; both are checked before the lab.
option_refusal() { local out; out=$(verify_agent "$@"); echo "$? $out"; }
expect "--schedule with a missing file exits 2" "$(option_refusal refused --schedule "$KEY_DIR/none.tsv")" \
  "2 --schedule: no file $KEY_DIR/none.tsv"
expect "--schedule refuses --only" "$(option_refusal refused --schedule "$SCHEDULE_FILE" --only F1)" \
  "2 --schedule runs the schedule's cases: it takes neither --only nor --rounds"
expect "--schedule refuses --rounds" "$(option_refusal refused --schedule "$SCHEDULE_FILE" --rounds 2)" \
  "2 --schedule runs the schedule's cases: it takes neither --only nor --rounds"
expect "--cases needs --schedule" "$(option_refusal refused --cases 1-3)" "2 --cases needs --schedule"
expect "--cases naming no case of the schedule exits 2" \
  "$(option_refusal refused --schedule "$SCHEDULE_FILE" --cases 7)" "2 --cases: case 7 is not in $SCHEDULE_FILE"
scheduled=$(verify_agent scheduled --mode decision --schedule "$SCHEDULE_FILE" --cases 2-3)
expect "a schedule's options pass, and the run stops at the Ollama check" "$?" 1
expect "the run names its schedule and cases" "$(grep -F '....  logging to' <<<"$scheduled")" \
  "....  logging to $KEY_DIR/scheduled; schedule: $SCHEDULE_FILE, cases 2 3; mode: decision; route: local"
expect "a schedule run's summary.tsv adds the case and its parameters" \
  "$(head -n 1 "$KEY_DIR/scheduled/summary.tsv")" "$(head -n 1 "$KEY_DIR/decision/summary.tsv")${tab}case${tab}parameters"

# verify-agent.sh's flow for one fault in decision mode: its own diagnose(), the place_orders() it calls and its exit
# trap, cleanup(). They are read from the script, each from its "<name>() {" line to the first "}" alone on a line, and
# defined in a subshell, so these cases run the script's code, not a copy; what they reach in the lab is stubbed there.
verify_agent_functions=$(awk '/^(cleanup|place_orders|diagnose)\(\) \{$/, /^}$/' \
  "$(dirname "${BASH_SOURCE[0]}")/verify-agent.sh")
expect "diagnose, place_orders and cleanup are read from verify-agent.sh" \
  "$(grep -cE '^(cleanup|place_orders|diagnose)\(\) \{$' <<<"$verify_agent_functions")" 3
diagnose_injection=6f1c2a4e-0d7b-4c3e-9a51-2b8e7d9c4f10
diagnose_incident=0b5e8d2c-7a14-4f6b-8e3d-5c9a1f2e7b64
# Runs fault $1 of round $2 as "verify-agent.sh --mode decision --route cloud --rounds K" does, into run directory $3,
# then ends the run as the script does after its last fault, through its exit trap; prints what the run printed. Every
# order is answered $4, PAID or refused. Past the baseline, the fault is injected as diagnose_injection, and its
# incident, diagnose_incident, opens with a window of $5 minutes and ends COMPLETED with a report that names the truth.
decision_fault() {
  (
    set -euo pipefail
    eval "$verify_agent_functions"
    # The script's settings for such a run.
    mode=decision route=cloud rounds_given=true RUN_DIR=$3
    SYMPTOM="Customers report that checkout orders are not completing as expected"
    HEALTHY_ORDERS=3 FAULT_ORDERS=6 ORDER_GAP_SECONDS=2 SETTLE_SECONDS=30
    window_margin_minutes=2 DECISION_WINDOW_MAX_MINUTES=9 BASELINE_MINUTES=19
    current_fault="" current_round="" current_label="" current_incident="" previous_fault_end="" failures=0
    # The restore's record (record_fault_end) goes to the run directory, not the repository's out/.
    LAST_FAULT_END_FILE="$3/.last-fault-end"
    # The clock and sleep as above. SECONDS, once unset, is a plain variable: frozen at 0, it fixes the seconds column.
    clock=1700000000
    date() { echo "$clock"; }
    sleep() { clock=$((clock + $1)); }
    unset SECONDS
    SECONDS=0
    # The stubs read stub_* variables of their own: bash's scoping is dynamic, so a stub called from diagnose would see
    # diagnose's locals (window, truth, body...) under those names.
    stub_answer=$4 stub_window=${5:-}
    # A schedule case, when the caller sets DF_CASE (with DF_PARAMS and DF_NOTE), as verify-agent.sh --schedule runs
    # it; DF_REPORT is the report's cause (default the F1 truth) and DF_ACTIVE the answer to GET /faults/active.
    stub_reported=${DF_REPORT:-gateway-mock|DEPENDENCY_LATENCY} stub_active=${DF_ACTIVE:-204 }
    # Every injector request, order and symptom is logged in the run directory, to compare a healthy case's run with
    # a fault case's.
    place_order() {
      echo order >>"$RUN_DIR/orders.log"
      if [[ "$stub_answer" == PAID ]]; then
        echo "201 at-$((clock - 1700000000)) PAID 0.1"
      else
        echo "503 at-$((clock - 1700000000)) none 3.0"
      fi
    }
    injector_call() {
      echo "$1 $2${3:+ $3}" >>"$RUN_DIR/injector.log"
      case "$1 $2" in
        "POST /faults") echo "201 {\"id\":\"$diagnose_injection\",\"status\":\"ACTIVE\"}" ;;
        "POST /faults/$diagnose_injection/restore") echo '200 {"status":"RESTORED"}' ;;
        "GET /faults/active") echo "$stub_active" ;;
        *) echo "000 " ;;
      esac
    }
    open_incident() { echo "$1 | $3" >>"$RUN_DIR/symptoms.log"; echo "$diagnose_incident $stub_window"; }
    stub_body='{"status":"COMPLETED","mode":"decision","route":"cloud","model":"claude-haiku-5-5",'
    stub_body+='"steps":[{"kind":"DOSSIER"},{"kind":"REPORT","promptTokens":5300,"completionTokens":550}],'
    stub_body+="\"report\":{\"rootCauseService\":\"${stub_reported%|*}\",\"rootCauseCategory\":\"${stub_reported#*|}\"}}"
    agent_call() { echo "200 $stub_body"; }
    # curl, which saves the incident's full record, fails: diagnose warns and goes on.
    curl() { return 7; }
    # The injection's truth row, read as the faults role.
    psql_faults() { echo 'gateway-mock|DEPENDENCY_LATENCY'; }
    : >"$RUN_DIR/summary.tsv"
    trap cleanup EXIT
    diagnose "$1" "$2" ${DF_CASE:+"$DF_CASE" "$DF_PARAMS" "$DF_NOTE"}
    # The script's last line.
    (( failures == 0 ))
  ) 2>&1
}
diagnose_dir="$KEY_DIR/diagnose"
mkdir -p "$diagnose_dir/baseline-failed" "$diagnose_dir/window-too-large"
# A failed baseline (every order refused, so the third one fails it, as above): the fault is not injected and has no
# incident, and its one summary.tsv line is a miss. diagnose then clears the fault it was running, so the exit trap
# finds none and writes no second line, "not scored".
fault_run=$(decision_fault F1 2 "$diagnose_dir/baseline-failed" refused)
expect "a failed baseline fails its fault once, and the run then ends through its exit trap with nothing more" \
  "$(grep -v '^WARN  ' <<<"$fault_run")" \
  "....  --- F1-r2
....  baseline: one healthy order every 50s for 19 min
FAIL  F1-r2: baseline order at-100: expected 201 PAID, got 503 none after 2 restarts; the baseline failed, so the\
 fault is not injected and is scored a miss
RESULT: FAIL (1 failed check(s), exit status 1)"
expect "a failed baseline's summary.tsv line, the fault's only one" "$(<"$diagnose_dir/baseline-failed/summary.tsv")" \
  "F1${tab}decision${tab}cloud${tab}-${tab}-${tab}baseline failed${tab}-${tab}-${tab}-${tab}-${tab}-${tab}MISS${tab}2"
# An incident whose window is larger than N_max, as after a wait for another running incident: the fault fails when its
# incident opens, and its score is a miss although the report names the truth, with the reason first.
fault_run=$(decision_fault F2 1 "$diagnose_dir/window-too-large" PAID 10)
expect "a window larger than N_max fails the fault, and a right report is scored a miss" \
  "$(grep -e '^FAIL  ' -e '^RESULT' <<<"$fault_run")" \
  "FAIL  F2-r1: window 10 min is larger than N_max (9 min): its previous window starts before the baseline phase, so\
 the baseline is not clean
FAIL  F2-r1: window 10 min, larger than N_max (9 min): the baseline is not clean; the report names\
 gateway-mock|DEPENDENCY_LATENCY, the truth
RESULT: FAIL (2 failed check(s), exit status 1)"
expect "its summary.tsv line is a miss that keeps the incident's outcome" \
  "$(<"$diagnose_dir/window-too-large/summary.tsv")" \
  "F2${tab}decision${tab}cloud${tab}$diagnose_injection${tab}$diagnose_incident${tab}COMPLETED\
${tab}gateway-mock|DEPENDENCY_LATENCY${tab}gateway-mock|DEPENDENCY_LATENCY${tab}2${tab}10${tab}0${tab}MISS${tab}1"
expect "its score file records the window and the miss" \
  "$(grep -e '^window' -e '^verdict' "$diagnose_dir/window-too-large/F2-r1-score.txt")" \
  "window (minutes): 10"$'\n'"verdict: MISS"
# A schedule's fault case and healthy case (verify-agent.sh --schedule): the fault case injects its fault with its
# nested parameter; the healthy case injects nothing, only checks that no injection is active where the fault case
# injects, and runs the same baseline, orders and symptom. Its truth is NONE|NONE, and UNKNOWN|UNKNOWN is its match.
mkdir -p "$diagnose_dir/case-fault" "$diagnose_dir/case-healthy" "$diagnose_dir/case-false-alarm" \
  "$diagnose_dir/case-active"
fault_run=$(DF_CASE=3 DF_PARAMS='{"gatewayLatencyMs":5000}' DF_NOTE=gateway_latency_ms=5000 \
  decision_fault F1 1 "$diagnose_dir/case-fault" PAID 5)
expect "a scheduled fault case is labelled by its case and matches" "$(grep -e '^PASS  F1-c3' -e '^RESULT' <<<"$fault_run")" \
  "PASS  F1-c3: incident $diagnose_incident opened, window 5 min
PASS  F1-c3: the report names gateway-mock|DEPENDENCY_LATENCY, the truth
RESULT: PASS"
expect "a scheduled fault case posts its nested parameter, then restores" "$(<"$diagnose_dir/case-fault/injector.log")" \
  "POST /faults {\"fault\":\"F1\",\"parameters\":{\"gatewayLatencyMs\":5000}}
POST /faults/$diagnose_injection/restore"
expect "its summary.tsv line ends with its case and parameters" "$(<"$diagnose_dir/case-fault/summary.tsv")" \
  "F1${tab}decision${tab}cloud${tab}$diagnose_injection${tab}$diagnose_incident${tab}COMPLETED\
${tab}gateway-mock|DEPENDENCY_LATENCY${tab}gateway-mock|DEPENDENCY_LATENCY${tab}2${tab}5${tab}0${tab}MATCH${tab}1\
${tab}3${tab}gateway_latency_ms=5000"
expect "its score file records its case and parameters" \
  "$(grep -e '^case' -e '^parameters' "$diagnose_dir/case-fault/F1-c3-score.txt")" \
  "case: 3"$'\n'"parameters: gateway_latency_ms=5000"
healthy_run=$(DF_CASE=2 DF_PARAMS=- DF_NOTE=- DF_REPORT='UNKNOWN|UNKNOWN' \
  decision_fault healthy 1 "$diagnose_dir/case-healthy" PAID 5)
expect "a healthy case answered UNKNOWN|UNKNOWN is a match" \
  "$(grep -e '^PASS  healthy' -e '^RESULT' <<<"$healthy_run")" \
  "PASS  healthy-c2: no injection is active, so none is in its windows
PASS  healthy-c2: incident $diagnose_incident opened, window 5 min
PASS  healthy-c2: the report names no cause (UNKNOWN|UNKNOWN), right on a healthy control
RESULT: PASS"
expect "a healthy case posts nothing to the injector: it only reads the active injection" \
  "$(<"$diagnose_dir/case-healthy/injector.log")" "GET /faults/active"
expect "a healthy case places as many orders as a fault case" "$(wc -l <"$diagnose_dir/case-healthy/orders.log")" \
  "$(wc -l <"$diagnose_dir/case-fault/orders.log")"
expect "a healthy case opens its incident with a fault case's symptom and margin" \
  "$(<"$diagnose_dir/case-healthy/symptoms.log")" "$(<"$diagnose_dir/case-fault/symptoms.log")"
expect "a healthy case's summary.tsv line: no injection, truth NONE|NONE, its case" \
  "$(<"$diagnose_dir/case-healthy/summary.tsv")" \
  "healthy${tab}decision${tab}cloud${tab}-${tab}$diagnose_incident${tab}COMPLETED${tab}NONE|NONE${tab}UNKNOWN|UNKNOWN\
${tab}2${tab}5${tab}0${tab}MATCH${tab}1${tab}2${tab}-"
expect "a healthy case's score file: no injection, truth NONE|NONE" \
  "$(grep -e '^injection' -e '^truth' -e '^case' "$diagnose_dir/case-healthy/healthy-c2-score.txt")" \
  "injection: -"$'\n'"truth (service|category): NONE|NONE"$'\n'"case: 2"
DF_CASE=5 DF_PARAMS=- DF_NOTE=- decision_fault healthy 1 "$diagnose_dir/case-false-alarm" PAID 5 >/dev/null
expect "a healthy case answered with a cause is a false alarm, a miss" \
  "$(cut -f 7,8,12 "$diagnose_dir/case-false-alarm/summary.tsv")" \
  "NONE|NONE${tab}gateway-mock|DEPENDENCY_LATENCY${tab}MISS"
active_run=$(DF_CASE=2 DF_PARAMS=- DF_NOTE=- DF_ACTIVE='200 {"id":"6f1c2a4e-0d7b-4c3e-9a51-2b8e7d9c4f10"}' \
  decision_fault healthy 1 "$diagnose_dir/case-active" PAID 5; echo "exit $?")
expect "a healthy case with an active injection stops the run before its incident" \
  "$(grep -e '^FAIL' -e '^exit' <<<"$active_run")" \
  "FAIL  healthy-c2: GET /faults/active answered 200, not 204: an injection is active, so the healthy control is not\
 healthy; nothing opened
FAIL  healthy-c2: not scored, the run stopped before its score
exit 1"
expect "its not-scored summary.tsv line carries its case" "$(<"$diagnose_dir/case-active/summary.tsv")" \
  "healthy${tab}decision${tab}cloud${tab}-${tab}-${tab}not scored${tab}-${tab}-${tab}-${tab}-${tab}-${tab}MISS${tab}1${tab}2${tab}-"

# check-recordings.sh: a clean decision-mode recording passes; the 2026-10-19 recordings (the committed replay copies,
# under the names verify-agent.sh gave them) fail, each on what an earlier fault or the lab's start left in its windows.
check_recordings="$(dirname "${BASH_SOURCE[0]}")/check-recordings.sh"
clean_run="$(dirname "${BASH_SOURCE[0]}")/testdata/check-recordings/clean"
expect "a clean decision-mode recording passes" "$("$check_recordings" "$clean_run" 2>&1; echo "exit $?")" \
  "PASS  $clean_run/F3-r1-incident.json: previous window: 5 PAID orders, no other state, all traffic SUCCESS;\
 incident window: order-service restarted once, F3's own recreate"$'\n'"RESULT: PASS (1 recording(s) clean)"$'\n'"exit 0"
old_run="$KEY_DIR/verify-agent-20261019T214629Z"
mkdir -p "$old_run"
for fault in F1 F2 F3; do
  cp "$REPO_DIR/agent/src/test/resources/replay/$fault/verify-agent-20261019T214629Z-$fault.json" \
    "$old_run/$fault-incident.json"
done
checked=$("$check_recordings" "$old_run" 2>&1; echo "exit $?")
expect "F1: no order in its previous window, the lab's start in its incident window" \
  "$(grep -F "/F1-incident.json:" <<<"$checked")" \
  "FAIL  $old_run/F1-incident.json: previous window: 0 PAID orders, at least 3 needed; incident window: restarts\
 besides the fault's own (F1 restarts nothing): gateway-mock 1, inventory-service 1, order-service 1, payment-service 1"
expect "F2: F1's pending orders and timeouts in its previous window" "$(grep -F "/F2-incident.json:" <<<"$checked")" \
  "FAIL  $old_run/F2-incident.json: previous window: orders not PAID: PAYMENT_PENDING 6; previous window: traffic not\
 SUCCESS: client order-service -> payment-service POST /payments CLIENT_ERROR UNKNOWN HttpTimeoutException x6"
expect "F3: F2's failed payments and 429s in its previous window" "$(grep -F "/F3-incident.json:" <<<"$checked")" \
  "FAIL  $old_run/F3-incident.json: previous window: orders not PAID: PAYMENT_FAILED 6; previous window: traffic not\
 SUCCESS: client payment-service -> gateway-mock POST /authorize 429 CLIENT_ERROR TooManyRequests x6, server\
 gateway-mock POST /authorize 429 CLIENT_ERROR x6"
expect "the 2026-10-19 recordings fail the check" "$(tail -n 2 <<<"$checked")" \
  "RESULT: FAIL (3 of 3 recording(s) failed)"$'\n'"exit 1"
mkdir -p "$KEY_DIR/explorer-run" "$KEY_DIR/empty-run"
printf '{"sinceMinutes":3,"mode":"explorer","steps":[{"kind":"ACT"}]}' >"$KEY_DIR/explorer-run/F1-incident.json"
expect "an incident with no DOSSIER step fails" "$("$check_recordings" "$KEY_DIR/explorer-run" 2>&1 | head -n 1)" \
  "FAIL  $KEY_DIR/explorer-run/F1-incident.json: no DOSSIER step"
expect "a run directory with no recording is a wrong argument" \
  "$("$check_recordings" "$KEY_DIR/empty-run" 2>&1; echo "exit $?")" \
  "No F<n>-incident.json, F<n>-r<k>-incident.json or <class>-c<case>-incident.json in $KEY_DIR/empty-run"$'\n'"exit 2"
# A schedule run's recordings (verify-agent.sh --schedule) are named by their case. A healthy control's (AC4): healthy
# orders and traffic in both windows, no restart at all, since nothing was injected.
healthy_run="$(dirname "${BASH_SOURCE[0]}")/testdata/check-recordings/healthy"
expect "a healthy control's recording passes, with no restart allowed" \
  "$("$check_recordings" "$healthy_run" 2>&1; echo "exit $?")" \
  "PASS  $healthy_run/healthy-c2-incident.json: previous window: 5 PAID orders, no other state, all traffic SUCCESS;\
 incident window: no restart"$'\n'"RESULT: PASS (1 recording(s) clean)"$'\n'"exit 0"
mkdir -p "$KEY_DIR/case-run"
cp "$clean_run/F3-r1-incident.json" "$KEY_DIR/case-run/F3-c4-incident.json"
cp "$clean_run/F3-r1-incident.json" "$KEY_DIR/case-run/healthy-c5-incident.json"
expect "a scheduled F3 case keeps F3's recreate; a healthy case restarts nothing" \
  "$("$check_recordings" "$KEY_DIR/case-run" 2>&1)" \
  "PASS  $KEY_DIR/case-run/F3-c4-incident.json: previous window: 5 PAID orders, no other state, all traffic SUCCESS;\
 incident window: order-service restarted once, F3's own recreate
FAIL  $KEY_DIR/case-run/healthy-c5-incident.json: incident window: restarts besides the fault's own (healthy restarts\
 nothing): order-service 1
RESULT: FAIL (1 of 2 recording(s) failed)"

# replay-agent.sh's decision-mode options and cases, checked before Maven, over a temporary manifest (REPLAY_DIR). Run
# on the local route with the failing curl stub, so a run that passes them stops at the Ollama check. The cases come
# from ReplayCases, the class ReplayRunTest picks them with, run from its source by the JDK: the count and the
# refusals below are the driver's own.
manifest_dir="$KEY_DIR/replay-manifest"
mkdir -p "$manifest_dir"
printf '%s\n' "fault${tab}mode${tab}route${tab}truth${tab}file${tab}incident${tab}split" \
  "F1${tab}decision${tab}cloud${tab}a|B${tab}F1/r1-F1.json${tab}id-1${tab}dev" \
  "F1${tab}dossier${tab}cloud${tab}a|B${tab}F1/r2-F1.json${tab}id-2${tab}dev" \
  "F1${tab}explorer${tab}cloud${tab}a|B${tab}F1/r3-F1.json${tab}id-3${tab}dev" \
  "F1${tab}decision${tab}cloud${tab}a|B${tab}F1/r4-F1.json${tab}id-4${tab}holdout" \
  "F3${tab}decision${tab}cloud${tab}c|D${tab}F3/r1-F3.json${tab}id-5${tab}dev" >"$manifest_dir/recordings.tsv"
# Runs replay-agent.sh over the replay directory $1, with the other arguments as its options.
replay_agent_over() {
  REPLAY_DIR="$1" PATH="$STUB_DIR:$PATH" "$(dirname "${BASH_SOURCE[0]}")/replay-agent.sh" --route local "${@:2}" 2>&1
}
# Runs replay-agent.sh over the temporary manifest, with these arguments as its options.
replay_agent() { replay_agent_over "$manifest_dir" "$@"; }
option_check() {
  local out
  out=$(replay_agent "$@")
  echo "$? ${out%% *}"
}
expect "decision mode refuses --samples, and points to --repeats" \
  "$(replay_agent --mode decision --samples 3; echo "exit $?")" \
  "--samples does not apply to decision mode, which replays each recording of the split once: use --repeats M to\
 run each one M times"$'\n'"exit 2"
expect "dossier mode refuses --split" "$(option_check --mode dossier --split dev)" "2 --split"
expect "explorer and dossier modes refuse --repeats" "$(option_check --mode both --repeats 2)" "2 --repeats"
expect "dossier mode refuses --view, which only decision mode reads" \
  "$(replay_agent --mode dossier --view sheets; echo "exit $?")" \
  "--view applies to decision mode only: explorer has no dossier, and dossier mode writes the tables"$'\n'"exit 2"
expect "an unknown view exits 2 on the option itself" "$(replay_agent --mode decision --view charts; echo "exit $?")" \
  "Unknown view 'charts'; choose tables or sheets"$'\n'"exit 2"
expect "dossier mode refuses --report, which only decision mode reads" \
  "$(replay_agent --mode dossier --report assessed; echo "exit $?")" \
  "--report applies to decision mode only: explorer and dossier mode always ask for the plain report"$'\n'"exit 2"
expect "an unknown report exits 2 on the option itself" \
  "$(replay_agent --mode decision --view sheets --report rich; echo "exit $?")" \
  "Unknown report 'rich'; choose plain, assessed or hybrid"$'\n'"exit 2"
# Config H (ADR-019; hybrid T4): the model picks among the rules' candidates, so the rules route, which calls no model,
# refuses the hybrid report; so do explorer and dossier mode, which read no report option.
expect "the rules route refuses the hybrid report before Maven" \
  "$(replay_agent --route rules --mode decision --report hybrid; echo "exit $?")" \
  "--report hybrid applies to --route local or cloud only, not rules: Config H has a model choose among the rules'\
 candidates, and Config 0 calls no model"$'\n'"exit 2"
expect "dossier mode refuses --report hybrid, which only decision mode reads" \
  "$(replay_agent --mode dossier --report hybrid; echo "exit $?")" \
  "--report applies to decision mode only: explorer and dossier mode always ask for the plain report"$'\n'"exit 2"
expect "the assessed report with the tables, the default view, stops the run before Maven" \
  "$(replay_agent --mode decision --report assessed; echo "exit $?")" \
  "--report assessed needs --view sheets, got tables: the assessed report's checks read the service that owns each\
 line of the sheets"$'\n'"exit 2"
# Kept for record-replay.sh's refusal of the same split, below.
driver_refusal=$(replay_agent --mode decision --split train; echo "exit $?")
expect "an unknown split exits 2 with the driver's reason, before Maven" "$driver_refusal" \
  "unknown split 'train': choose dev, holdout, stress, probe or holdout-2"$'\n'"exit 2"
# Config 0 (ADR-016) replays decision mode only, and the route is refused on the option itself before the manifest.
expect "the rules route refuses explorer and dossier mode before Maven" \
  "$(replay_agent --route rules --mode dossier; echo "exit $?")" \
  "--route rules replays decision mode only, not dossier: Config 0 diagnoses the gathered dossier and has no explorer\
 or dossier loop"$'\n'"exit 2"
expect "an unknown route exits 2 and names the three routes" "$(replay_agent --route gpu; echo "exit $?")" \
  "Unknown route 'gpu'; choose cloud, local or rules"$'\n'"exit 2"
expect "a 4-digit --repeats exits 2 on the option itself" "$(option_check --mode decision --repeats 1000)" \
  "2 --repeats"
expect "a zero --repeats exits 2 on the option itself" "$(option_check --mode decision --repeats 0)" "2 --repeats"
decision_run=$(replay_agent --mode decision --faults F1,F3 --repeats 2)
expect "decision mode's options pass, and the run stops at the Ollama check" "$?" 2
expect "one case per dev recording of the chosen faults made in decision or dossier mode, each run --repeats times" \
  "$(grep -F '....  ' <<<"$decision_run")" \
  "....  decision mode, split dev, view tables, report plain: 3 recording(s) of F1 F3, each replayed 2 time(s): 6\
 samples"
# The default view is written twice, as DossierView.DEFAULT and as replay-agent.sh's literal: they must agree.
[[ "$(<"$REPO_DIR/agent/src/main/java/dev/kintsugi/agent/dossier/DossierView.java")" \
  =~ String\ DEFAULT\ =\ \"([a-z]*)\" ]]
expect "replay-agent.sh's default view is the agent's own, DossierView.DEFAULT" \
  "$(grep -o 'view [a-z]*,' <<<"$decision_run")" "view ${BASH_REMATCH[1]-missing},"
# So is the default report, as DecisionReport.DEFAULT and as replay-agent.sh's literal.
[[ "$(<"$REPO_DIR/agent/src/main/java/dev/kintsugi/agent/loop/DecisionReport.java")" \
  =~ String\ DEFAULT\ =\ \"([a-z]*)\" ]]
expect "replay-agent.sh's default report is the agent's own, DecisionReport.DEFAULT" \
  "$(grep -o 'report [a-z]*:' <<<"$decision_run")" "report ${BASH_REMATCH[1]-missing}:"
sheets_run=$(replay_agent --mode decision --view sheets --faults F1)
expect "decision mode takes --view sheets, and the run stops at the Ollama check" "$?" 2
expect "the run names its view" "$(grep -F '....  ' <<<"$sheets_run")" \
  "....  decision mode, split dev, view sheets, report plain: 2 recording(s) of F1, each replayed 1 time(s): 2 samples"
assessed_run=$(replay_agent --mode decision --view sheets --report assessed --faults F1)
expect "decision mode takes --report assessed with the sheets, and the run stops at the Ollama check" "$?" 2
expect "the run names its report" "$(grep -F '....  ' <<<"$assessed_run")" \
  "....  decision mode, split dev, view sheets, report assessed: 2 recording(s) of F1, each replayed 1 time(s): 2\
 samples"
hybrid_run=$(replay_agent --mode decision --report hybrid --faults F1)
expect "the local route takes --report hybrid in either view, and the run stops at the Ollama check" "$?" 2
expect "the hybrid run names its report and its rules version" "$(grep -F '....  ' <<<"$hybrid_run")" \
  "....  decision mode, split dev, view tables, report hybrid, rules v1: 2 recording(s) of F1, each replayed 1\
 time(s): 2 samples"
# The default rules version is written twice, as RulesVersion.DEFAULT and as replay-agent.sh's literal: they must
# agree.
[[ "$(<"$REPO_DIR/agent/src/main/java/dev/kintsugi/agent/rules/RulesVersion.java")" \
  =~ String\ DEFAULT\ =\ \"([a-z0-9]*)\" ]]
expect "replay-agent.sh's default rules version is the agent's own, RulesVersion.DEFAULT" \
  "$(grep -o 'rules [a-z0-9]*:' <<<"$hybrid_run")" "rules ${BASH_REMATCH[1]-missing}:"
# --rules (resource-facts T5; ADR-021): v1 is Config 0, v2 Config 0R. Only the rules route and the hybrid report read
# it, so every other run refuses it before Maven.
hr_run=$(replay_agent --mode decision --report hybrid --rules v2 --faults F1)
expect "the hybrid report takes --rules v2 (HR), and the run stops at the Ollama check" "$?" 2
expect "the HR run names its rules version" "$(grep -F '....  ' <<<"$hr_run")" \
  "....  decision mode, split dev, view tables, report hybrid, rules v2: 2 recording(s) of F1, each replayed 1\
 time(s): 2 samples"
expect "an unknown rules version exits 2 on the option itself" \
  "$(replay_agent --mode decision --report hybrid --rules v3; echo "exit $?")" \
  "Unknown rules 'v3'; choose v1 or v2"$'\n'"exit 2"
expect "the plain report refuses --rules, which only the rules route and the hybrid report read" \
  "$(replay_agent --mode decision --rules v2; echo "exit $?")" \
  "--rules applies to --route rules or --report hybrid only: the other reports list no candidate, and the rules\
 choose none"$'\n'"exit 2"
expect "dossier mode refuses --rules, which only decision mode reads" \
  "$(replay_agent --mode dossier --rules v2; echo "exit $?")" \
  "--rules applies to decision mode only: the rules route and the hybrid report are decision mode's"$'\n'"exit 2"
expect "--rules needs a value" "$(replay_agent --mode decision --rules; echo "exit $?")" \
  "--rules needs a value; see --help"$'\n'"exit 2"
expect "a chosen fault with no recording in the split stops the run before Maven" \
  "$(replay_agent --mode decision --split holdout --faults F1,F3; echo "exit $?")" \
  "no decision or dossier recording of F3 in split holdout of $manifest_dir/recordings.tsv"$'\n'"exit 2"
# A manifest line the driver refuses (here the split 'test') stops the run before Maven, even for a fault it does not
# name, as it would fail the driver.
bad_dir="$KEY_DIR/replay-bad-line"
mkdir -p "$bad_dir"
bad_line="F2${tab}decision${tab}cloud${tab}c|D${tab}F2/r1-F2.json${tab}id-6${tab}test"
printf '%s\n' "$(<"$manifest_dir/recordings.tsv")" "$bad_line" >"$bad_dir/recordings.tsv"
expect "a manifest line the driver refuses stops the run before Maven" \
  "$(replay_agent_over "$bad_dir" --mode decision --faults F1; echo "exit $?")" \
  "a manifest line has the split 'test', not dev, holdout, stress, probe or holdout-2: $bad_line"$'\n'"exit 2"

# The healthy class (lab-realism-min T7; ADR-015, ADR-026): holdout-2's healthy controls, truth NONE|NONE, replay in
# decision mode like a fault's cases. A holdout-2 run takes them by default, with F1, F2 and F3; every other split's
# default stays F1, F2 and F3, since none of them holds a healthy row. Checked before Maven, as above.
holdout2_dir="$KEY_DIR/replay-holdout-2"
mkdir -p "$holdout2_dir"
printf '%s\n' "fault${tab}mode${tab}route${tab}truth${tab}file${tab}incident${tab}split${tab}case" \
  "F2${tab}decision${tab}local${tab}c|D${tab}F2/r1-c1-F2.json${tab}id-1${tab}holdout-2${tab}1" \
  "healthy${tab}decision${tab}local${tab}NONE|NONE${tab}healthy/r1-c2-healthy.json${tab}id-2${tab}holdout-2${tab}2" \
  "F1${tab}decision${tab}local${tab}a|B${tab}F1/r1-c3-F1.json${tab}id-3${tab}holdout-2${tab}3" \
  "F3${tab}decision${tab}local${tab}e|F${tab}F3/r1-c4-F3.json${tab}id-4${tab}holdout-2${tab}4" \
  "healthy${tab}decision${tab}local${tab}NONE|NONE${tab}healthy/r1-c5-healthy.json${tab}id-5${tab}holdout-2${tab}5" \
  >"$holdout2_dir/recordings.tsv"
expect "the healthy class alone replays a holdout-2 manifest's healthy rows" \
  "$(replay_agent_over "$holdout2_dir" --mode decision --split holdout-2 --faults healthy | grep -F '....  decision')" \
  "....  decision mode, split holdout-2, view tables, report plain: 2 recording(s) of healthy, each replayed 1\
 time(s): 2 samples"
expect "a holdout-2 run takes the healthy class by default, after F1, F2 and F3" \
  "$(replay_agent_over "$holdout2_dir" --mode decision --split holdout-2 | grep -F '....  decision')" \
  "....  decision mode, split holdout-2, view tables, report plain: 5 recording(s) of F1 F2 F3 healthy,\
 each replayed 1 time(s): 5 samples"
expect "another split's default stays F1, F2 and F3, so no healthy row is asked for" \
  "$(replay_agent --mode decision; echo "exit $?")" \
  "no decision or dossier recording of F2 in split dev of $manifest_dir/recordings.tsv"$'\n'"exit 2"
expect "a split with no healthy row refuses the healthy class before Maven" \
  "$(replay_agent --mode decision --faults F1,healthy; echo "exit $?")" \
  "no decision or dossier recording of healthy in split dev of $manifest_dir/recordings.tsv"$'\n'"exit 2"
expect "dossier mode refuses the healthy class, which has no stress recording to pool" \
  "$(replay_agent --mode dossier --faults healthy; echo "exit $?")" \
  "--faults healthy applies to decision mode only: a healthy control is a holdout-2 case, with no stress recording\
 to pool"$'\n'"exit 2"
expect "re2-ob has no healthy class" \
  "$(replay_agent_over "$REPO_DIR/agent/src/test/resources/rcaeval" --dataset re2-ob --mode decision \
    --faults healthy; echo "exit $?")" \
  "Unknown fault 'healthy'; choose from cpu mem disk delay loss socket"$'\n'"exit 2"

# --dataset (odd/tasks/rcaeval-converter.md T5): re2-ob replays the RCAEval converter's recordings, whose faults are
# RCAEval's, from a recordings directory that must be named, in decision mode only. The committed converter output
# (agent/src/test/resources/rcaeval/) holds one dev case of cpu. Checked before Maven, as above.
rcaeval_dir="$REPO_DIR/agent/src/test/resources/rcaeval"
expect "an unknown dataset exits 2 and names the four datasets" \
  "$(replay_agent --dataset re2-xx --mode decision; echo "exit $?")" \
  "Unknown dataset 're2-xx'; choose lab, re2-ob, re2-ss or re2-tt"$'\n'"exit 2"
expect "re2-ss takes RCAEval's faults, not the lab's" \
  "$(replay_agent_over "$rcaeval_dir" --dataset re2-ss --mode decision --faults F1; echo "exit $?")" \
  "Unknown fault 'F1'; choose from cpu mem disk delay loss socket"$'\n'"exit 2"
expect "re2-tt replays decision mode only" \
  "$(replay_agent_over "$rcaeval_dir" --dataset re2-tt --mode dossier; echo "exit $?")" \
  "--dataset re2-tt replays decision mode only, not dossier: the converter writes one decision case per RCAEval\
 case and no stress recordings"$'\n'"exit 2"
# RE2-TT's topology (ADR-032) is not written yet: until it is, re2-tt exits 2 naming the missing file.
if [[ ! -f "$REPO_DIR/agent/src/main/resources/topology/re2-tt.json" ]]; then
  expect "re2-tt exits 2 while its topology file is missing" \
    "$(replay_agent_over "$rcaeval_dir" --dataset re2-tt --mode decision; echo "exit $?")" \
    "--dataset re2-tt needs its topology, agent/src/main/resources/topology/re2-tt.json, which does not exist yet"\
$'\n'"exit 2"
fi
expect "the lab dataset keeps the lab's faults" \
  "$(replay_agent --dataset lab --mode decision --faults cpu; echo "exit $?")" \
  "Unknown fault 'cpu'; choose from F1 F2 F3 healthy"$'\n'"exit 2"
expect "re2-ob takes RCAEval's faults, not the lab's" \
  "$(replay_agent_over "$rcaeval_dir" --dataset re2-ob --mode decision --faults F1; echo "exit $?")" \
  "Unknown fault 'F1'; choose from cpu mem disk delay loss socket"$'\n'"exit 2"
expect "re2-ob replays decision mode only" \
  "$(replay_agent_over "$rcaeval_dir" --dataset re2-ob --mode dossier; echo "exit $?")" \
  "--dataset re2-ob replays decision mode only, not dossier: the converter writes one decision case per RCAEval\
 case and no stress recordings"$'\n'"exit 2"
expect "re2-ob needs its recordings directory, which has no default" \
  "$(env -u REPLAY_DIR PATH="$STUB_DIR:$PATH" "$(dirname "${BASH_SOURCE[0]}")/replay-agent.sh" --route local \
    --dataset re2-ob --mode decision 2>&1; echo "exit $?")" \
  "--dataset re2-ob needs REPLAY_DIR, the converter's output directory (convert-rcaeval --out); the recordings stay\
 outside the repository"$'\n'"exit 2"
expect "re2-ob's default faults are all six, so a directory without them stops the run before Maven" \
  "$(replay_agent_over "$rcaeval_dir" --dataset re2-ob --mode decision | grep -vF '....  dataset'; echo "exit $?")" \
  "no decision or dossier recording of mem, disk, delay, loss, socket in split dev of $rcaeval_dir/recordings.tsv\
"$'\n'"exit 2"
rcaeval_run=$(replay_agent_over "$rcaeval_dir" --dataset re2-ob --mode decision --faults cpu)
expect "re2-ob's options pass over the converter's manifest, and the run stops at the Ollama check" "$?" 2
expect "the re2-ob run names its dataset and topology" "$(grep -F 'dataset' <<<"$rcaeval_run")" \
  "....  dataset re2-ob: topology classpath:topology/re2-ob.json, recordings $rcaeval_dir"
expect "the re2-ob run counts the converter's case" "$(grep -F 'decision mode' <<<"$rcaeval_run")" \
  "....  decision mode, split dev, view tables, report plain: 1 recording(s) of cpu, each replayed 1 time(s): 1\
 samples"

# record-replay.sh: a round run imported split by split into a temporary replay directory (REPLAY_DIR), never the
# committed one. Round 1 holds a clean F3 (the check-recordings fixture) and the 2026-10-19 F2, whose windows fail the
# check; round 2 another clean F3.
record_replay="$(dirname "${BASH_SOURCE[0]}")/record-replay.sh"
replay_dir="$KEY_DIR/replay"
new_run="$KEY_DIR/runs/verify-agent-20261024T162600Z"
stress_run="$KEY_DIR/runs/verify-agent-20261019T214629Z"
key_run="$KEY_DIR/runs/verify-agent-key"
mkdir -p "$new_run" "$stress_run" "$key_run"
score_text() {
  printf 'fault: %s\nmode (requested, recorded): %s, %s\nroute (requested, recorded): cloud, cloud\nincident: %s
truth (service|category): %s\n' "$1" "$2" "$2" "$3" "$4"
}
for round in 1 2; do
  cp "$clean_run/F3-r1-incident.json" "$new_run/F3-r$round-incident.json"
  score_text F3 decision "id-f3-r$round" 'order-service|CONFIGURATION' >"$new_run/F3-r$round-score.txt"
done
cp "$REPO_DIR/agent/src/test/resources/replay/F2/verify-agent-20261019T214629Z-F2.json" "$new_run/F2-r1-incident.json"
score_text F2 decision id-f2-r1 'gateway-mock|DEPENDENCY_REJECTING' >"$new_run/F2-r1-score.txt"
cp "$new_run/F2-r1-incident.json" "$stress_run/F2-incident.json"
score_text F2 dossier id-f2 'gateway-mock|DEPENDENCY_REJECTING' >"$stress_run/F2-score.txt"
printf '{"steps":[{"toolOutput":"token sk-ant-api03-abc"}]}' >"$key_run/F1-incident.json"
score_text F1 dossier id-f1 'gateway-mock|DEPENDENCY_LATENCY' >"$key_run/F1-score.txt"
record() { REPLAY_DIR="$replay_dir" "$record_replay" "$@" 2>&1; }
manifest_header="fault${tab}mode${tab}route${tab}truth${tab}file${tab}incident${tab}split${tab}case"
f3_r1="F3${tab}decision${tab}cloud${tab}order-service|CONFIGURATION${tab}F3/verify-agent-20261024T162600Z-r1-F3.json\
${tab}id-f3-r1${tab}dev${tab}"
expect "--split is required" "$(record "$new_run"; echo "exit $?")" \
  "--split is required: dev, holdout, stress, probe or holdout-2; see --help"$'\n'"exit 2"
# record-replay.sh keeps its own list of the splits, so that the import needs no JDK. It refuses an unknown split in
# the words replay-agent.sh printed above, which are ReplayCases' own, list included: the two lists cannot drift apart.
expect "an unknown split exits 2 with the driver's reason" "$(record --split train "$new_run"; echo "exit $?")" \
  "$driver_refusal"
expect "two splits in one --split are no split" "$(record --split 'dev holdout' "$new_run"; echo "exit $?")" \
  "unknown split 'dev holdout': choose dev, holdout, stress, probe or holdout-2"$'\n'"exit 2"
expect "a range of rounds that ends before it starts exits 2" \
  "$(record --split dev --rounds 5-3 "$new_run"; echo "exit $?")" \
  "--rounds: the range 5-3 ends before it starts"$'\n'"exit 2"
expect "round 1 as dev: the clean F3 is recorded, and F2, whose windows fail the check, is refused" \
  "$(record --split dev --rounds 1 "$new_run"; echo "exit $?")" \
  "FAIL  verify-agent-20261024T162600Z F2-r1: refused, check-recordings.sh: previous window: orders not PAID:\
 PAYMENT_PENDING 6; previous window: traffic not SUCCESS: client order-service -> payment-service POST /payments\
 CLIENT_ERROR UNKNOWN HttpTimeoutException x6; nothing written
PASS  verify-agent-20261024T162600Z F3-r1: F3/verify-agent-20261024T162600Z-r1-F3.json recorded in dev (decision,\
 cloud, truth order-service|CONFIGURATION)
1 recorded, 1 refused, manifest $replay_dir/recordings.tsv
exit 1"
expect "the manifest gets its header and the round's line, with its split" "$(<"$replay_dir/recordings.tsv")" \
  "$manifest_header"$'\n'"$f3_r1"
expect "the recording is the incident, under the run's name and round" \
  "$(cmp -s "$new_run/F3-r1-incident.json" "$replay_dir/F3/verify-agent-20261024T162600Z-r1-F3.json" && echo same)" \
  same
expect "nothing is written for the refused F2" \
  "$(if [[ -e "$replay_dir/F2" ]]; then echo written; else echo none; fi)" none
expect "the same import again changes no line" "$(record --split dev --rounds 1 "$new_run" | grep -F 'F3-r1')" \
  "....  verify-agent-20261024T162600Z F3-r1: F3/verify-agent-20261024T162600Z-r1-F3.json replaced, its\
 recordings.tsv line unchanged"
expect "a recording of dev is never moved to holdout" \
  "$(record --split holdout --rounds 1 "$new_run" | grep -F 'F3-r1')" \
  "FAIL  verify-agent-20261024T162600Z F3-r1: refused, F3/verify-agent-20261024T162600Z-r1-F3.json is recorded in\
 split dev, and a recording stays in one split; nothing written"
expect "round 2 alone, as holdout" "$(record --split holdout --rounds 2 "$new_run"; echo "exit $?")" \
  "PASS  verify-agent-20261024T162600Z F3-r2: F3/verify-agent-20261024T162600Z-r2-F3.json recorded in holdout\
 (decision, cloud, truth order-service|CONFIGURATION)
1 recorded, 0 refused, manifest $replay_dir/recordings.tsv
exit 0"
expect "stress takes a recording whose windows fail the check, from a run without rounds" \
  "$(record --split stress "$stress_run"; echo "exit $?")" \
  "PASS  verify-agent-20261019T214629Z F2: F2/verify-agent-20261019T214629Z-F2.json recorded in stress (dossier,\
 cloud, truth gateway-mock|DEPENDENCY_REJECTING)
1 recorded, 0 refused, manifest $replay_dir/recordings.tsv
exit 0"
expect "a key-like string is refused, in stress too" "$(record --split stress "$key_run"; echo "exit $?")" \
  "FAIL  verify-agent-key F1: refused, the incident holds a key-like string; nothing written
0 recorded, 1 refused, manifest $replay_dir/recordings.tsv
exit 1"
expect "the manifest holds one line per recording, each with its split" \
  "$(cut -f 5,7 "$replay_dir/recordings.tsv" | tail -n +2)" \
  "F3/verify-agent-20261024T162600Z-r1-F3.json${tab}dev
F3/verify-agent-20261024T162600Z-r2-F3.json${tab}holdout
F2/verify-agent-20261019T214629Z-F2.json${tab}stress"
# A schedule run (verify-agent.sh --schedule): its files are named by case, a healthy control's class is "healthy" and
# its truth NONE|NONE. Into holdout-2 each row names its schedule case, in the case column; a recording without one is
# refused there (ADR-026, Decision 3). In any other split the case column stays empty.
schedule_run="$KEY_DIR/runs/verify-agent-20261202T090000Z"
mkdir -p "$schedule_run"
cp "$healthy_run/healthy-c2-incident.json" "$schedule_run/healthy-c2-incident.json"
score_text healthy decision id-h-c2 'NONE|NONE' >"$schedule_run/healthy-c2-score.txt"
cp "$clean_run/F3-r1-incident.json" "$schedule_run/F3-c4-incident.json"
score_text F3 decision id-f3-c4 'order-service|CONFIGURATION' >"$schedule_run/F3-c4-score.txt"
expect "a schedule run into holdout-2: a healthy case and a fault case, each with its case" \
  "$(record --split holdout-2 "$schedule_run"; echo "exit $?")" \
  "PASS  verify-agent-20261202T090000Z F3-c4: F3/verify-agent-20261202T090000Z-c4-F3.json recorded in holdout-2\
 (decision, cloud, truth order-service|CONFIGURATION)
PASS  verify-agent-20261202T090000Z healthy-c2: healthy/verify-agent-20261202T090000Z-c2-healthy.json recorded in\
 holdout-2 (decision, cloud, truth NONE|NONE)
2 recorded, 0 refused, manifest $replay_dir/recordings.tsv
exit 0"
expect "the holdout-2 rows carry their schedule case" "$(grep -F "${tab}holdout-2${tab}" "$replay_dir/recordings.tsv")" \
  "F3${tab}decision${tab}cloud${tab}order-service|CONFIGURATION${tab}F3/verify-agent-20261202T090000Z-c4-F3.json\
${tab}id-f3-c4${tab}holdout-2${tab}4
healthy${tab}decision${tab}cloud${tab}NONE|NONE${tab}healthy/verify-agent-20261202T090000Z-c2-healthy.json\
${tab}id-h-c2${tab}holdout-2${tab}2"
expect "the healthy recording is the incident, under its class" \
  "$(cmp -s "$schedule_run/healthy-c2-incident.json" \
    "$replay_dir/healthy/verify-agent-20261202T090000Z-c2-healthy.json" && echo same)" same
dev_schedule_run="$KEY_DIR/runs/verify-agent-20261103T220000Z"
mkdir -p "$dev_schedule_run"
cp "$schedule_run"/healthy-c2-* "$dev_schedule_run/"
expect "a schedule run into dev leaves the case column empty" \
  "$(record --split dev "$dev_schedule_run" >/dev/null; grep -F 'verify-agent-20261103T220000Z' "$replay_dir/recordings.tsv" | cut -f 7-)" \
  "dev${tab}"
# A schedule run's file joins holdout-2, or dev with its case dropped; never holdout, stress or probe, where it would
# pass for a holdout-1 or another split's recording.
case_run="$KEY_DIR/runs/verify-agent-20261103T230000Z"
mkdir -p "$case_run"
cp "$schedule_run"/healthy-c2-* "$case_run/"
for other_split in holdout stress probe; do
  expect "a schedule run into $other_split is refused" "$(record --split "$other_split" "$case_run"; echo "exit $?")" \
    "FAIL  verify-agent-20261103T230000Z healthy-c2: refused, a recording that names a schedule case\
 (<class>-c<case>-incident.json) joins only holdout-2 or dev, not $other_split; nothing written
0 recorded, 1 refused, manifest $replay_dir/recordings.tsv
exit 1"
done
expect "nothing is written for a refused schedule run" \
  "$(grep -cF 'verify-agent-20261103T230000Z' "$replay_dir/recordings.tsv"; \
    if [[ -e "$replay_dir/healthy/verify-agent-20261103T230000Z-c2-healthy.json" ]]; then echo written; else echo none; fi)" \
  "0"$'\n'"none"
expect "holdout-2 refuses a recording that names no schedule case" \
  "$(record --split holdout-2 --rounds 1 "$new_run" | grep -F 'F3-r1')" \
  "FAIL  verify-agent-20261024T162600Z F3-r1: refused, a holdout-2 recording must name its schedule case\
 (<class>-c<case>-incident.json, from verify-agent.sh --schedule); nothing written"
expect "the committed manifest starts with record-replay.sh's header" \
  "$(head -n 1 "$REPO_DIR/agent/src/test/resources/replay/recordings.tsv")" "$manifest_header"
expect "every committed manifest row has the case column, empty before holdout-2" \
  "$(tail -n +2 "$REPO_DIR/agent/src/test/resources/replay/recordings.tsv" | awk -F'\t' 'NF != 8 || $8 != ""' | wc -l)" 0
mkdir -p "$KEY_DIR/replay-6-columns"
printf 'fault\tmode\troute\ttruth\tfile\tincident\n' >"$KEY_DIR/replay-6-columns/recordings.tsv"
expect "a manifest without the split column is refused" \
  "$(REPLAY_DIR="$KEY_DIR/replay-6-columns" "$record_replay" --split stress "$stress_run" 2>&1; echo "exit $?")" \
  "$KEY_DIR/replay-6-columns/recordings.tsv does not start with the header fault, mode, route, truth, file,\
 incident, split, case; nothing written"$'\n'"exit 2"

echo "PASS  $cases cases"
