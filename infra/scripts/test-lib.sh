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

# incident_fields: status, failure reason, reported cause, steps, mode.
expect "a completed dossier incident" \
  "$(incident_fields '{"status":"COMPLETED","mode":"dossier","steps":[{},{}],
    "report":{"mode":"dossier","rootCauseService":"gateway-mock","rootCauseCategory":"DEPENDENCY_LATENCY"}}')" \
  "COMPLETED${tab}-${tab}gateway-mock|DEPENDENCY_LATENCY${tab}2${tab}dossier"
expect "the incident's own mode wins over the report's" \
  "$(incident_fields '{"report":{"mode":"dossier"},"status":"COMPLETED","mode":"explorer"}')" \
  "COMPLETED${tab}-${tab}none${tab}0${tab}explorer"
expect "a readable incident with no mode" \
  "$(incident_fields '{"status":"FAILED","failureReason":"no  report"}')" \
  "FAILED${tab}no report${tab}none${tab}0${tab}none"
expect "a body that is not JSON" "$(incident_fields 'not json')" "unreadable${tab}-${tab}none${tab}0${tab}-"
expect "an empty body" "$(incident_fields '')" "unreadable${tab}-${tab}none${tab}0${tab}-"
expect "a JSON array" "$(incident_fields '[1]')" "unreadable${tab}-${tab}none${tab}0${tab}-"

# agent_verdict: requested mode, incident mode, status, reason, reported, truth -> verdict and message.
truth='gateway-mock|DEPENDENCY_LATENCY'
expect "a match in the requested mode" \
  "$(agent_verdict dossier dossier COMPLETED - "$truth" "$truth")" \
  "MATCH${tab}the report names $truth, the truth"
expect "a right cause in another mode is a miss that keeps the outcome" \
  "$(agent_verdict dossier explorer COMPLETED - "$truth" "$truth")" \
  "MISS${tab}ran in mode 'explorer', not the requested 'dossier'; status COMPLETED, reported '$truth', truth '$truth'"
expect "a readable incident with no mode is a mode miss" \
  "$(agent_verdict dossier none FAILED 'no report' none "$truth")" \
  "MISS${tab}ran in mode 'none', not the requested 'dossier'; status FAILED (no report), reported 'none', truth '$truth'"
expect "an unreadable incident fails on its status, not on its mode" \
  "$(agent_verdict dossier - unreadable - none "$truth")" \
  "MISS${tab}status unreadable, reported 'none', truth '$truth'"
expect "a timeout in the requested mode" \
  "$(agent_verdict explorer explorer TIMED_OUT 'ACT deadline' none "$truth")" \
  "MISS${tab}status TIMED_OUT (ACT deadline), reported 'none', truth '$truth'"
expect "a wrong category" \
  "$(agent_verdict explorer explorer COMPLETED - 'gateway-mock|DEPENDENCY_REJECTING' "$truth")" \
  "MISS${tab}status COMPLETED, reported 'gateway-mock|DEPENDENCY_REJECTING', truth '$truth'"
expect "an unreadable truth never matches" \
  "$(agent_verdict explorer explorer COMPLETED - "$truth" '')" \
  "MISS${tab}status COMPLETED, reported '$truth', truth 'unreadable'"

echo "PASS  $cases cases"
