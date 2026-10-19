#!/usr/bin/env bash
# Tests for lib.sh's pure helpers (no lab, no docker): incident_fields and agent_verdict, which verify-agent.sh uses
# to score an incident. Run: infra/scripts/test-lib.sh. Exits non-zero on the first failing case.
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

# space_out, with sleep stubbed so no case waits: no wait without a restore time or once the margin has passed.
sleep() { echo "slept $1"; }
expect "no restore time, no wait" "$(space_out "")" ""
expect "a restore older than the margin, no wait" \
  "$(space_out $(( $(date +%s) - (INCIDENT_WINDOW_MARGIN_MINUTES + 2) * 60 )))" ""
waited=$(space_out "$(date +%s)" | tail -n 1)
expect "a restore just now waits the margin and a minute" \
  "$(( ${waited#slept } >= (INCIDENT_WINDOW_MARGIN_MINUTES + 1) * 60 - 1 ))" 1
unset -f sleep

echo "PASS  $cases cases"
