#!/usr/bin/env bash
# Tests for lib.sh's pure helpers (no lab, no docker): incident_fields and agent_verdict, which verify-agent.sh uses
# to score an incident, the windows and the baseline phase (sleep, the clock and the orders stubbed), and more; also
# the options of replay-agent.sh and verify-agent.sh, check-recordings.sh, and record-replay.sh on temporary
# directories. Run: infra/scripts/test-lib.sh. Exits non-zero on the first failing case.
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
  "No F<n>-incident.json or F<n>-r<k>-incident.json in $KEY_DIR/empty-run"$'\n'"exit 2"

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
# replay-agent.sh over the replay directory $1, then over the temporary manifest.
replay_agent_over() {
  REPLAY_DIR="$1" PATH="$STUB_DIR:$PATH" "$(dirname "${BASH_SOURCE[0]}")/replay-agent.sh" --route local "${@:2}" 2>&1
}
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
expect "an unknown split exits 2 with the driver's reason, before Maven" \
  "$(replay_agent --mode decision --split train; echo "exit $?")" \
  "unknown split 'train': choose dev, holdout, stress or probe"$'\n'"exit 2"
expect "a 4-digit --repeats exits 2 on the option itself" "$(option_check --mode decision --repeats 1000)" \
  "2 --repeats"
expect "a zero --repeats exits 2 on the option itself" "$(option_check --mode decision --repeats 0)" "2 --repeats"
decision_run=$(replay_agent --mode decision --faults F1,F3 --repeats 2)
expect "decision mode's options pass, and the run stops at the Ollama check" "$?" 2
expect "one case per dev recording of the chosen faults made in decision or dossier mode, each run --repeats times" \
  "$(grep -F '....  ' <<<"$decision_run")" \
  "....  decision mode, split dev: 3 recording(s) of F1 F3, each replayed 2 time(s): 6 samples"
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
  "a manifest line has the split 'test', not dev, holdout, stress or probe: $bad_line"$'\n'"exit 2"

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
manifest_header="fault${tab}mode${tab}route${tab}truth${tab}file${tab}incident${tab}split"
f3_r1="F3${tab}decision${tab}cloud${tab}order-service|CONFIGURATION${tab}F3/verify-agent-20261024T162600Z-r1-F3.json\
${tab}id-f3-r1${tab}dev"
expect "--split is required" "$(record "$new_run"; echo "exit $?")" \
  "--split is required: dev, holdout, stress or probe; see --help"$'\n'"exit 2"
expect "an unknown split exits 2" "$(record --split train "$new_run"; echo "exit $?")" \
  "Unknown split 'train'; choose dev, holdout, stress or probe"$'\n'"exit 2"
expect "two splits in one --split are no split" "$(record --split 'dev holdout' "$new_run"; echo "exit $?")" \
  "Unknown split 'dev holdout'; choose dev, holdout, stress or probe"$'\n'"exit 2"
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
mkdir -p "$KEY_DIR/replay-6-columns"
printf 'fault\tmode\troute\ttruth\tfile\tincident\n' >"$KEY_DIR/replay-6-columns/recordings.tsv"
expect "a manifest without the split column is refused" \
  "$(REPLAY_DIR="$KEY_DIR/replay-6-columns" "$record_replay" --split stress "$stress_run" 2>&1; echo "exit $?")" \
  "$KEY_DIR/replay-6-columns/recordings.tsv does not start with the header fault, mode, route, truth, file,\
 incident, split; nothing written"$'\n'"exit 2"

echo "PASS  $cases cases"
