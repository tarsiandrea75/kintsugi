# Shared helpers for the lab checks (verify-*.sh, reset-volume.sh). Source it; do not run it.
# shellcheck shell=bash disable=SC2034 # its variables are read by the scripts that source it
# The caller sets "set -euo pipefail", defines usage(), then calls parse_args "$@" and start_lab.

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$REPO_DIR/infra/compose.yaml"
# fault-injector mounts the repository read-only at its host path and joins the Docker socket's group (ADR-009). The
# scripts derive both from this machine, so the lab starts the same from any working directory; an exported value wins.
# There is no fallback gid: when the socket cannot be read, DOCKER_GID stays empty and compose refuses to start.
export KINTSUGI_REPO_DIR="${KINTSUGI_REPO_DIR:-$REPO_DIR}"
DOCKER_GID="${DOCKER_GID:-$(stat -c %g /var/run/docker.sock 2>/dev/null || true)}"
export DOCKER_GID
# The compose variables the fault injector sets for F3 and F4. start_lab removes them from the "up" environment, so a
# value left in this shell cannot start the lab with a fault already applied.
FAULT_VARIABLES=(ORDER_PAYMENT_BASEURL INVENTORY_POOL_SIZE INVENTORY_RESERVATION_DELAY)
# The diagnosing agent's service. start_lab leaves it out: no other check needs it, and on a knowledge-data volume
# created before the agent database it exits at startup. verify-agent.sh starts it with start_agent.
AGENT_SERVICE=agent
ORDERS=http://127.0.0.1:18080
GATEWAY=http://127.0.0.1:18082
INJECTOR=http://127.0.0.1:18084
PROMETHEUS=http://127.0.0.1:19090
SERVICES=(order-service payment-service gateway-mock inventory-service)
LOKI_WAIT_SECONDS=180
CHECK_WAIT_SECONDS=60
# Transport cap for POST /orders (curl --max-time), not a pass threshold: order-service answers within its 3 s inventory
# and 3 s payment read timeouts; the margin covers the rest of the request.
ORDER_MAX_SECONDS=15
CURL_MAX_SECONDS=5
# Transport cap for POST /faults and POST /faults/{id}/restore: an F3 or F4 step is a recreate that waits for health,
# bounded by the injector's recreate-wait-timeout (120 s) plus its 30 s kill margin.
INJECTOR_MAX_SECONDS=180

down=false
failures=0
started=$SECONDS
# The injection this script activated; the exit trap restores it through the injector.
injection_id=""

# Parses --down and --help; usage() is defined by the caller.
parse_args() {
  for arg in "$@"; do
    case "$arg" in
      --down) down=true ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
  done
}

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; failures=$((failures + 1)); }
info() { echo "....  $*"; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# The file compose reads its variables from: .env in the compose file's directory (compose's project directory). A
# variable set in the shell, even to an empty value, wins over it.
ENV_FILE="$REPO_DIR/infra/.env"

# True when ENV_FILE gives variable $1 a non-empty value (the last definition wins, as in compose; "export " and quotes
# around the value are allowed). Prints nothing, so it never shows a value.
env_file_has_value() {
  local line value="" name=$1
  [[ -r "$ENV_FILE" ]] || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line#"${line%%[![:space:]]*}"}
    line=${line#export }
    [[ "$line" == "$name="* ]] || continue
    value=${line#"$name="}
    value=${value%%[[:space:]]#*}
    value=${value%"${value##*[![:space:]]}"}
    [[ "$value" == \"*\" || "$value" == \'*\' ]] && value=${value:1:${#value}-2}
  done <"$ENV_FILE"
  [[ -n "$value" ]]
}

# True when the cloud route has its key (ADR-012), in the shell or else in ENV_FILE. An empty shell value is unset
# first: compose lets a variable set in the shell, even to an empty value, win over ENV_FILE, so an empty one would hide
# the file's key. Call it directly, not in $(...), so the unset reaches the caller. Prints nothing.
cloud_key_available() {
  [[ -n "${ANTHROPIC_API_KEY:-}" ]] || unset ANTHROPIC_API_KEY
  [[ -n "${ANTHROPIC_API_KEY:-}" ]] || env_file_has_value ANTHROPIC_API_KEY
}
CLOUD_KEY_MISSING="The cloud route needs ANTHROPIC_API_KEY: set it in infra/.env (git-ignored), or export it in the shell"

# "docker compose up" "$@", quiet, without the fault variables (FAULT_VARIABLES): a value left in this shell cannot
# start a service with a fault already applied, and a service left faulted by an earlier run is put back on its defaults.
compose_up() {
  local unset_faults=() variable
  for variable in "${FAULT_VARIABLES[@]}"; do unset_faults+=(-u "$variable"); done
  env "${unset_faults[@]}" docker compose --progress quiet -f "$COMPOSE_FILE" up "$@"
}

# The services start_lab starts: every service of the compose file but the agent, one per line.
lab_services() { compose config --services 2>/dev/null | grep -vx "$AGENT_SERVICE"; }

# Prints the environment of service $1's container, one NAME=value per line; prints nothing when it is not running.
container_env() {
  local container
  container=$(compose ps -q "$1" 2>/dev/null) || return 0
  [[ -n "$container" ]] || return 0
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" 2>/dev/null || true
}

reset_gateway() {
  if curl -fsS --max-time "$CURL_MAX_SECONDS" -o /dev/null -X DELETE "$GATEWAY/admin/behavior" 2>/dev/null; then
    info "gateway behavior reset"
  else
    echo "WARN  could not reset the gateway behavior (DELETE $GATEWAY/admin/behavior)" >&2
  fi
}

# Restores the injection on every exit, even when a check fails or the script is interrupted. Restore is idempotent,
# so a script that already restored it calls the injector once more for nothing.
on_exit() {
  local status=$?
  if [[ -n "$injection_id" ]]; then
    if restore_injection; then
      info "injection $injection_id restored"
    else
      echo "WARN  could not restore injection $injection_id; run: curl -X POST $INJECTOR/faults/$injection_id/restore" >&2
    fi
  fi
  if [[ "$down" == true ]]; then
    info "tearing the lab down"
    compose down -v >/dev/null 2>&1 || echo "WARN  docker compose down failed" >&2
  fi
  if [[ $status -eq 0 && $failures -eq 0 ]]; then
    echo "RESULT: PASS"
  else
    echo "RESULT: FAIL ($failures failed check(s), exit status $status)"
    [[ $status -eq 0 ]] && status=1
  fi
  exit "$status"
}

# Runs "$@" every 2 s until it succeeds or $1 seconds pass.
poll() {
  local seconds=$1; shift
  local deadline=$((SECONDS + seconds))
  until "$@"; do
    (( SECONDS >= deadline )) && return 1
    sleep 2
  done
}

# Instant PromQL query; prints the raw JSON.
prom_query() { curl -fsS --max-time "$CURL_MAX_SECONDS" -G "$PROMETHEUS/api/v1/query" --data-urlencode "query=$1"; }

# True when the query returns at least one series (a comparison filters out the series that do not match).
# The output is captured first: piping into grep -q under pipefail can fail when grep exits before curl finishes.
prom_has_result() {
  local json
  json=$(prom_query "$1" 2>/dev/null) || return 1
  grep -q '"result":\[{' <<<"$json"
}

# Prints the first series' value of an instant query as an integer; fails while the series is missing or NaN.
prom_int() {
  local json value
  json=$(prom_query "$1" 2>/dev/null) || return 1
  value=$(grep -o '"value":\[[^]]*\]' <<<"$json" | head -n1 | cut -d'"' -f4)
  [[ "$value" =~ ^[0-9]+(\.0+)?$ ]] || return 1
  echo "${value%%.*}"
}

# Like prom_int, but only prints a value that two reads, one scrape interval apart, agree on. An order passes through
# PAYMENT_PENDING for under a second, so a single scrape can catch a healthy order mid-flight; a baseline read from
# that sample is one too high and the "rose by N" check then never passes.
# The wait must exceed scrape_interval in infra/prometheus/prometheus.yml (5s); update both together.
SCRAPE_SETTLE_SECONDS=6
prom_stable_int() {
  local first second
  first=$(prom_int "$1") || return 1
  sleep "$SCRAPE_SETTLE_SECONDS"
  second=$(prom_int "$1") || return 1
  [[ "$first" == "$second" ]] || return 1
  echo "$first"
}

# Loki has no host port; query it from the Grafana container, which has a shell and curl.
loki() { compose exec -T grafana curl -fsS --max-time "$CURL_MAX_SECONDS" -G "http://loki:3100$1" "${@:2}"; }

loki_ready() { [[ "$(loki /ready 2>/dev/null)" == ready* ]]; }

loki_has_all_services() {
  local values
  values=$(loki /loki/api/v1/label/service/values 2>/dev/null) || return 1
  for service in "${SERVICES[@]}"; do
    grep -q "\"$service\"" <<<"$values" || return 1
  done
}

# True when Loki has a line of service $1 that contains "WARN", $2 and, if given, the phrase $3, logged in the last
# 10 minutes. Pass the phrase a dashboard panel counts, so the check also proves that panel's filter matches.
loki_has_warn() {
  local json phrase=${3:+ |= \"$3\"}
  json=$(loki /loki/api/v1/query_range \
    --data-urlencode "query={service=\"$1\"} |= \"WARN\" |= \"$2\"$phrase" \
    --data-urlencode "since=10m" --data-urlencode "limit=5" 2>/dev/null) || return 1
  grep -q "$2" <<<"$json"
}

# Prints the value of JSON string field $1 in $2, or nothing. Never fails: grep finds no match on an error body.
json_field() { grep -o "\"$1\":\"[^\"]*\"" <<<"$2" | head -n1 | cut -d'"' -f4 || true; }

# A customer id for one order: "checkout-" and 8 random hex digits. Every value these scripts write into the lab (orders,
# events, logs) is neutral like this: the agent reads it through its tools, so it must never name a fault or a script.
neutral_customer() { printf 'checkout-%04x%04x' "$RANDOM" "$RANDOM"; }

# POST /orders as a new neutral customer (neutral_customer); always prints "<http code> <order id> <status> <seconds>",
# one line, even when curl fails: a transport failure (refused, timed out) prints "000 none none <seconds>". The order id is the "id" of a 201
# body, or the "orderId" of a 409 or 503 ProblemDetail body (the rejected order is stored too); a ProblemDetail has no
# order status, so status is "none" then. Callers read it with
#   read -r code id status seconds <<<"$(place_order)"
# and report a FAIL themselves, so a broken request never aborts the script before the exit trap restores the lab.
place_order() {
  local out rc=0 code seconds body id status
  out=$(curl -sS --max-time "$ORDER_MAX_SECONDS" -w '\n%{http_code} %{time_total}' -X POST "$ORDERS/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"customerId\":\"$(neutral_customer)\",\"items\":[{\"sku\":\"SKU-1\",\"quantity\":1}]}") || rc=$?
  read -r code seconds <<<"${out##*$'\n'}" || true
  body=${out%$'\n'*}
  (( rc == 0 )) || code=000
  id=$(json_field id "$body")
  [[ -n "$id" ]] || id=$(json_field orderId "$body")
  status=$(json_field status "$body")
  echo "${code:-000} ${id:-none} ${status:-none} ${seconds:-none}"
}

# Runs SQL $2 in database $1 (orders, payments or inventory) as lab_app, the services' non-superuser role; prints
# unaligned rows, "|" between columns.
psql_lab() { compose exec -T postgres psql -U lab_app -d "$1" -Atc "$2"; }

# Runs SQL $1 in the faults database as the faults role, the only non-superuser role that can connect to it.
psql_faults() { compose exec -T postgres psql -U faults -d faults -Atc "$1"; }

# --- Fault injector (ADR-009) ---

# Calls the injector: $1 method, $2 path, $3 optional JSON body. Prints "<http code> <body>" on one line, "000 " on a
# transport failure; never fails, so a broken call never aborts the script before the exit trap.
injector_call() {
  local out rc=0
  out=$(curl -sS --max-time "$INJECTOR_MAX_SECONDS" -w '\n%{http_code}' -X "$1" "$INJECTOR$2" \
    -H 'Content-Type: application/json' ${3:+-d "$3"} 2>/dev/null) || rc=$?
  (( rc == 0 )) || { echo "000 "; return 0; }
  echo "${out##*$'\n'} ${out%$'\n'*}" | tr -d '\n'
  echo
}

# POST /faults for fault $1. On 201 stores the injection id for the exit trap and passes; otherwise fails and exits.
# A failed activation (500) still carries an id: it is stored too, so the trap retries the restore. A transport failure
# (000) or a body without an id says nothing about the injector's side: the activation may have gone through after curl
# gave up. So the active injection is read back, and when there is one its id is stored for the trap too.
inject_fault() {
  local code body id=""
  read -r code body <<<"$(injector_call POST /faults "{\"fault\":\"$1\"}")" || true
  id=$(json_field id "$body") || true
  if is_uuid "$id"; then
    injection_id=$id
  else
    adopt_active_injection "POST /faults $1 got $code ${body:-with no body}"
  fi
  if [[ "$code" == 201 && -n "$injection_id" ]]; then
    pass "$1 injected through the fault injector (injection $injection_id)"
  else
    fail "POST /faults $1: expected 201 with an injection id, got $code ${body:-no body}"
    exit 1
  fi
}

# After a POST /faults without a usable answer ($1 describes it): stores the id of the active injection, if any, so the
# exit trap restores it.
adopt_active_injection() {
  local code body id=""
  read -r code body <<<"$(injector_call GET /faults/active)" || true
  if [[ "$code" == 200 ]]; then
    id=$(json_field id "$body") || true
  fi
  if is_uuid "$id"; then
    injection_id=$id
    fail "$1, but injection $id is active; the exit trap restores it"
  elif [[ "$code" != 204 ]]; then
    fail "$1, and GET /faults/active answered $code: an injection may be active; check $INJECTOR/faults/active"
  fi
}

# POST /faults/{id}/restore for this script's injection; true when the answer is 200 and the injection no longer
# blocks (RESTORED, or FAILED when its activation had already been undone).
restore_injection() {
  local code body status
  read -r code body <<<"$(injector_call POST "/faults/$injection_id/restore")"
  status=$(json_field status "$body")
  [[ "$code" == 200 && ( "$status" == RESTORED || "$status" == FAILED ) ]]
}

# Restores the injection through the injector as a check step.
expect_restored() {
  if restore_injection; then
    pass "injection $injection_id restored through the fault injector"
  else
    fail "could not restore injection $injection_id through the fault injector"
    exit 1
  fi
}

# Restores an injection that an earlier, interrupted run left blocking, so this run can inject its own.
restore_leftover_injection() {
  local code body leftover
  read -r code body <<<"$(injector_call GET /faults/active)"
  case "$code" in
    204) pass "the fault injector answers and no injection is active" ;;
    200)
      leftover=$(json_field id "$body")
      injection_id=$leftover
      if is_uuid "$leftover" && restore_injection; then
        pass "the fault injector restored injection $leftover left by an earlier run"
        injection_id=""
      else
        fail "an earlier injection ($body) is still blocking and could not be restored"
        exit 1
      fi
      ;;
    *) fail "GET $INJECTOR/faults/active: expected 200 or 204, got $code"; exit 1 ;;
  esac
}

# The truth row of this script's injection, read as the faults role: exactly one row, with fault $1, root-cause
# service $2, status RESTORED and a restored_at time.
check_truth_row() {
  local rows
  rows=$(psql_faults "select fault_id, root_cause_service, status, restored_at is not null
    from injections where id = '$injection_id'" 2>&1 || true)
  if [[ "$rows" == "$1|$2|RESTORED|t" ]]; then
    pass "truth: one injections row for $injection_id, fault $1, root cause service $2, RESTORED (read as faults)"
    info "truth row: $(psql_faults "select id, fault_id, target_service, root_cause_service, root_cause_category,
      status, injected_at, restored_at from injections where id = '$injection_id'")"
  else
    fail "truth: expected exactly '$1|$2|RESTORED|t' for injection $injection_id, got '${rows:-no row}'"
  fi
}

# Prints the root cause of injection $1 as "<service>|<category>", read as the faults role from the truth table; prints
# nothing when the injection has no row (or the database cannot be read). It is what an agent report is scored against.
truth_cause() {
  is_uuid "$1" || return 0
  psql_faults "select root_cause_service || '|' || root_cause_category from injections where id = '$1'" \
    2>/dev/null || true
}

# The truth is isolated from the services' role: lab_app and faults are not superusers (a superuser ignores
# REVOKE CONNECT), and lab_app cannot connect to the faults database.
check_truth_isolation() {
  local supers denied
  supers=$(psql_lab orders "select rolname || '=' || rolsuper from pg_roles
    where rolname in ('lab_app', 'faults') order by rolname" 2>&1 | tr '\n' ' ' || true)
  if [[ "$supers" == "faults=false lab_app=false " ]]; then
    pass "PostgreSQL: lab_app and faults are not superusers (rolsuper false)"
  else
    fail "PostgreSQL: expected lab_app and faults with rolsuper false, got '$supers'"
  fi
  if denied=$(compose exec -T postgres psql -U lab_app -d faults -Atc "select 1" 2>&1); then
    fail "PostgreSQL: lab_app connected to the faults database"
  elif grep -q 'permission denied for database "faults"' <<<"$denied"; then
    pass "PostgreSQL: lab_app cannot connect to the faults database (permission denied)"
  else
    fail "PostgreSQL: lab_app could not connect to faults, but not for lack of permission: $denied"
  fi
}

# The injector is invisible to the agent's data sources:
# - Loki has no service="fault-injector" label value and no such stream, although its own log (docker logs) has lines
#   for this injection, so the absence comes from the Alloy drop rule, not from silence;
# - Prometheus has no active target for it: no service="fault-injector" label and no fault-injector address.
check_injector_invisible() {
  local logs values series targets
  logs=$(compose logs --no-log-prefix fault-injector 2>/dev/null || true)
  if grep -q "injection_id=$injection_id" <<<"$logs"; then
    pass "docker logs: fault-injector logged injection $injection_id"
  else
    fail "docker logs: no fault-injector line for injection $injection_id, so the Loki check proves nothing"
  fi
  values=$(loki /loki/api/v1/label/service/values 2>/dev/null) || values="unreadable"
  series=$(loki /loki/api/v1/series --data-urlencode 'match[]={service="fault-injector"}' \
    --data-urlencode "since=1h" 2>/dev/null) || series="unreadable"
  if [[ "$values" == *'"status":"success"'* && "$values" != *'"fault-injector"'* && "$series" == *'"data":[]'* ]]; then
    pass "Loki: no service=\"fault-injector\" label value or stream"
  else
    fail "Loki: fault-injector is visible or Loki is unreadable (label values: $values; series: $series)"
  fi
  # Every label and address of every active target is in this body, so one string match covers both.
  targets=$(curl -fsS --max-time "$CURL_MAX_SECONDS" "$PROMETHEUS/api/v1/targets?state=active" 2>/dev/null) ||
    targets="unreadable"
  if [[ "$targets" == *'"activeTargets":[{'* && "$targets" != *fault-injector* ]]; then
    pass "Prometheus: no active target with service=\"fault-injector\" or a fault-injector address"
  else
    fail "Prometheus: fault-injector is an active target, or the targets are unreadable: $targets"
  fi
}

# The checks every verify script runs after its restore: the truth row, its isolation, and the injector's
# invisibility in Loki and Prometheus.
check_truth() {
  check_truth_row "$1" "$2"
  check_truth_isolation
  check_injector_invisible
}

# True when $1 is a UUID, so it is safe to put in SQL between quotes.
is_uuid() { [[ "$1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; }

# Prints one line per container of the lab, exited ones included: "<service> <state> <health or -> <exit code>".
lab_states() {
  compose ps -a --format '{{.Service}} {{.State}} {{if .Health}}{{.Health}}{{else}}-{{end}} {{.ExitCode}}' 2>/dev/null
}

# The only failure of "up --wait" start_lab accepts: the runbook knowledge base's Ollama path, which no evidence tool
# needs. True, with the reasons printed one per line, when every service but knowledge-indexer and ollama-proxy is
# running and, if it has a healthcheck, healthy, and when knowledge-indexer exited non-zero or ollama-proxy is not
# running and healthy (a failed bind: compose then runs the indexer anyway, and it fails without Ollama). Any other
# state, a service missing, stopped, restarting, starting or unhealthy, is false.
knowledge_only_failure() {
  local states service state health code expected reasons=() indexer_failed=false proxy_down=false
  states=$(lab_states) || return 1
  expected=$(lab_services | grep -vxE 'knowledge-indexer|ollama-proxy') || return 1
  [[ -n "$expected" ]] || return 1
  for service in $expected; do
    read -r _ state health _ <<<"$(grep -E "^$service " <<<"$states" | head -n1)"
    [[ "$state" == running && ( "$health" == - || "$health" == healthy ) ]] || return 1
  done
  read -r _ state health code <<<"$(grep -E '^ollama-proxy ' <<<"$states" | head -n1)"
  if [[ "$state" != running || "$health" != healthy ]]; then
    proxy_down=true
    reasons+=("ollama-proxy is ${state:-missing} (health ${health:--}): its bind on\
 ${OLLAMA_PROXY_BIND_IP:-172.17.0.1}:11434 may have failed because another process listens there; it restarts on its own until the address is free.\
 Logs: docker compose -f infra/compose.yaml logs ollama-proxy")
  fi
  read -r _ state health code <<<"$(grep -E '^knowledge-indexer ' <<<"$states" | head -n1)"
  if [[ "$state" == exited && "$code" != 0 ]]; then
    indexer_failed=true
    reasons+=("knowledge-indexer exited $code (is Ollama up and reachable through ollama-proxy?); the previous index,\
 if any, is intact. Logs: docker compose -f infra/compose.yaml logs knowledge-indexer")
  fi
  [[ "$indexer_failed" == true || "$proxy_down" == true ]] || return 1
  printf '%s\n' "${reasons[@]}"
}

# After a failed "up --wait", which stops waiting at its first error, waits up to 300 s for every service of
# lab_services but knowledge-indexer and ollama-proxy to be running and healthy, without building, recreating or
# starting either of those two. Compose's errors are appended to file $UP_ERRORS.
wait_without_knowledge() {
  local services
  services=$(lab_services | grep -vxE 'knowledge-indexer|ollama-proxy') || return 1
  # shellcheck disable=SC2086 # one word per service name
  compose_up -d --no-build --no-deps --wait --wait-timeout 300 $services >/dev/null 2>>"$UP_ERRORS"
}

# Starts or reuses the lab, every service but the agent (lab_services), waits for Loki, restores any injection an
# earlier run left blocking, and resets the gateway. Exits on failure. A failure of the knowledge base's Ollama path
# alone (knowledge_only_failure) is reported as WARN lines, with compose's own errors, and the lab is used: the
# evidence tools do not need Ollama. The agent is left as it is: running or not, it never fails a check that does not
# use it (start_agent starts it).
start_lab() {
  info "starting or reusing the lab, all but the agent (docker compose up -d --build --wait)"
  local reasons line services
  services=$(lab_services) || true
  [[ -n "$services" ]] || { fail "docker compose config lists no services"; exit 1; }
  UP_ERRORS=$(mktemp)
  # "--progress quiet": compose's stderr then holds only its warnings and errors, which a failure path prints.
  # shellcheck disable=SC2086 # one word per service name
  if compose_up -d --build --wait $services >/dev/null 2>"$UP_ERRORS"; then
    rm -f "$UP_ERRORS"
    pass "every container is up and healthy"
  elif wait_without_knowledge && reasons=$(knowledge_only_failure); then
    while IFS= read -r line; do echo "WARN  compose: $line" >&2; done <"$UP_ERRORS"
    rm -f "$UP_ERRORS"
    while IFS= read -r line; do echo "WARN  $line" >&2; done <<<"$reasons"
    echo "WARN  search_runbooks refuses until the indexer runs with Ollama reachable;" \
      "the evidence tools are unaffected." >&2
    pass "every container but knowledge-indexer and ollama-proxy is up and healthy"
  else
    cat "$UP_ERRORS" >&2
    rm -f "$UP_ERRORS"
    compose ps -a >&2 || true
    fail "docker compose up --wait did not get every container healthy"
    exit 1
  fi

  if poll "$LOKI_WAIT_SECONDS" loki_ready; then
    pass "Loki is ready"
  else
    fail "Loki is not ready after ${LOKI_WAIT_SECONDS}s"
    exit 1
  fi
  if poll "$LOKI_WAIT_SECONDS" loki_has_all_services; then
    pass "Loki has logs for ${SERVICES[*]}"
  else
    fail "Loki has no logs for every service after ${LOKI_WAIT_SECONDS}s"
    exit 1
  fi

  restore_leftover_injection
  # Start from a healthy gateway, whatever an earlier run left behind.
  reset_gateway
}

# --- The diagnosing agent's incident API (openspec/specs/agent) ---

AGENT=http://127.0.0.1:18086
# Seconds between two reads of a running incident: a run lasts minutes, one model call alone 40 to 420 s on a CPU.
INCIDENT_POLL_SECONDS=15
# Bounded wall clock for one incident: the agent's 45 min incident budget, plus its 300 s warm-up (which runs before
# that budget starts), plus a margin for the last call to be stored. Past it, the agent itself has ended the run.
INCIDENT_WAIT_SECONDS=$((45 * 60 + 300 + 120))
# How long an incident's window reaches past its fault's own traffic (incident_window): the tools take the window
# back from the time of each call, and the calls that read the lab's evidence come in the run's first minutes.
INCIDENT_WINDOW_MARGIN_MINUTES=10
AGENT_WAIT_SECONDS=180

# Builds and starts the agent (and what it depends on, if down) and waits for it to be healthy. On failure, prints
# the agent's last log lines and the usual cause, and fails.
start_agent() {
  info "starting or reusing the agent (docker compose up -d --build --wait $AGENT_SERVICE)"
  if compose_up -d --build --wait --wait-timeout "$AGENT_WAIT_SECONDS" "$AGENT_SERVICE" >/dev/null 2>&1; then
    pass "the agent is up and healthy"
    return 0
  fi
  compose logs --tail 15 "$AGENT_SERVICE" >&2 || true
  fail "the agent is not healthy after ${AGENT_WAIT_SECONDS}s. If its log says that database \"agent\" or role" \
    "\"agent_app\" does not exist, the knowledge-data volume predates the agent: run" \
    "infra/scripts/reset-volume.sh knowledge-data, then this script again"
  return 1
}

# Calls the agent: $1 method, $2 path, $3 optional JSON body. Prints "<http code> <body>" on one line, "000 " on a
# transport failure; never fails, like injector_call.
agent_call() {
  local out rc=0
  out=$(curl -sS --max-time "$CURL_MAX_SECONDS" -w '\n%{http_code}' -X "$1" "$AGENT$2" \
    -H 'Content-Type: application/json' ${3:+-d "$3"} 2>/dev/null) || rc=$?
  (( rc == 0 )) || { echo "000 "; return 0; }
  echo "${out##*$'\n'} ${out%$'\n'*}" | tr -d '\n'
  echo
}

# Prints the status of incident $1 (RUNNING, COMPLETED, ...), or "unreadable" when GET does not answer 200.
incident_status() {
  local code body status
  read -r code body <<<"$(agent_call GET "/incidents/$1")" || true
  status=$(json_field status "$body")
  [[ "$code" == 200 && -n "$status" ]] && echo "$status" || echo unreadable
}

# Waits until incident $1 is no longer RUNNING, for at most $2 seconds (INCIDENT_WAIT_SECONDS by default). Prints one
# progress line every 5 minutes. True when the incident ended; its final status is then in incident_final_status
# (RUNNING or unreadable when it did not end in time).
incident_final_status=""
wait_incident() {
  local id=$1 seconds=${2:-$INCIDENT_WAIT_SECONDS} deadline next_note=$((SECONDS + 300)) waited=$SECONDS status
  deadline=$((SECONDS + seconds))
  while true; do
    status=$(incident_status "$id")
    if [[ "$status" != RUNNING && "$status" != unreadable ]]; then
      incident_final_status=$status
      return 0
    fi
    (( SECONDS >= deadline )) && { incident_final_status=$status; return 1; }
    if (( SECONDS >= next_note )); then
      info "incident $id is $status after $((SECONDS - waited))s"
      next_note=$((SECONDS + 300))
    fi
    sleep "$INCIDENT_POLL_SECONDS"
  done
}

# The window (sinceMinutes) of an incident about a fault whose own traffic started at epoch second $1: the whole
# minutes since then, rounded up, plus INCIDENT_WINDOW_MARGIN_MINUTES, at most 360 (the API's limit). The window
# reaches back at most one minute before $1 plus the margin; space_out keeps the previous fault's traffic older.
incident_window() {
  local elapsed minutes
  elapsed=$(($(date +%s) - $1))
  (( elapsed > 0 )) || elapsed=0
  minutes=$(((elapsed + 59) / 60 + INCIDENT_WINDOW_MARGIN_MINUTES))
  (( minutes <= 360 )) || minutes=360
  echo "$minutes"
}

# Waits until INCIDENT_WINDOW_MARGIN_MINUTES + 1 minutes have passed since epoch second $1, the end of the previous
# fault (its restore), before the next fault's traffic starts. So no window from incident_window reaches back to the
# previous fault's orders, logs or restarts, however early that fault's incident ended. No wait when $1 is empty.
space_out() {
  [[ -n "$1" ]] || return 0
  local wait=$(($1 + (INCIDENT_WINDOW_MARGIN_MINUTES + 1) * 60 - $(date +%s)))
  if (( wait > 0 )); then
    info "waiting ${wait}s, so that the previous fault's traffic stays out of the next incident's window"
    sleep "$wait"
  fi
}

# POST /incidents with symptom $1 (no double quotes or backslashes) and the window incident_window gives for traffic
# that started at epoch second $2, computed for each attempt. Prints "<id> <window minutes>" of the new incident;
# prints nothing and fails on any other answer, after printing it on stderr. One incident runs at a time: on a 409
# naming a running incident (an earlier, interrupted run), it waits for that one to end (wait_incident) and tries once
# more, with a window recomputed after the wait.
open_incident() {
  local body code answer id attempt window
  for attempt in 1 2; do
    window=$(incident_window "$2")
    body="{\"symptom\":\"$1\",\"sinceMinutes\":$window}"
    read -r code answer <<<"$(agent_call POST /incidents "$body")" || true
    id=$(json_field id "$answer")
    if [[ "$code" == 202 ]] && is_uuid "$id"; then
      echo "$id $window"
      return 0
    fi
    if [[ "$code" == 409 && "$attempt" == 1 ]] && is_uuid "$id"; then
      echo "....  incident $id from an earlier run is still RUNNING; waiting for it to end" >&2
      wait_incident "$id" >&2 || { echo "POST /incidents: incident $id still RUNNING" >&2; return 1; }
      continue
    fi
    echo "POST /incidents: expected 202 with an id, got $code ${answer:-no body}" >&2
    return 1
  done
}

# Restarts the agent and waits for it to be healthy. The API has no cancel: the restart sweep ends an incident left
# RUNNING as FAILED ("agent restarted during the run"), so the next POST /incidents is accepted at once. By hand:
#   bash -c 'source infra/scripts/lib.sh && restart_agent'
restart_agent() {
  compose restart "$AGENT_SERVICE" >/dev/null 2>&1 || return 1
  compose_up -d --no-deps --no-build --wait --wait-timeout "$AGENT_WAIT_SECONDS" "$AGENT_SERVICE" >/dev/null 2>&1
}

# Ends incident $1 if it may still be RUNNING (RUNNING, or unreadable because the agent does not answer) by restarting
# the agent. True when the incident is then neither RUNNING nor unreadable.
end_running_incident() {
  local status
  status=$(incident_status "$1")
  [[ "$status" == RUNNING || "$status" == unreadable ]] || return 0
  info "restarting the agent to end incident $1, $status"
  restart_agent || { echo "WARN  could not restart the agent; incident $1 may still be RUNNING" >&2; return 1; }
  status=$(incident_status "$1")
  [[ "$status" != RUNNING && "$status" != unreadable ]] ||
    { echo "WARN  incident $1 is still $status after the restart" >&2; return 1; }
}

# Reads incident JSON $1 (the body of GET /incidents/{id}) with python3 and prints one tab-separated line: the status,
# the failure reason (- when none), the reported "<rootCauseService>|<rootCauseCategory>" read from .report alone
# (none when there is no report: a hypothesis in a plan or in tool arguments is never a report), the number of
# steps, the incident's own top-level mode and route (none when it has none; the report's copies are not read), and
# the totals of promptTokens and completionTokens over the steps, each counted on its own (none when no step has that
# count, so a 0 means the model reported 0), and the incident's model (none when it has none); every field is
# non-empty, so "IFS=$'\t' read" keeps them apart. Prints "unreadable - none 0 - - - - -", tab-separated, when $1 is
# not an incident. Tested by test-lib.sh.
incident_fields() {
  python3 -I -c '
import json, sys
try:
    incident = json.load(sys.stdin)
except ValueError:
    incident = None
if not isinstance(incident, dict):
    print("unreadable\t-\tnone\t0\t-\t-\t-\t-\t-")
    sys.exit(0)
report = incident.get("report")
cause = "none"
if isinstance(report, dict) and report.get("rootCauseService") and report.get("rootCauseCategory"):
    cause = "%s|%s" % (report["rootCauseService"], report["rootCauseCategory"])
steps = incident.get("steps")
steps = steps if isinstance(steps, list) else []
def total(name):
    counts = [step[name] for step in steps if isinstance(step, dict)
              and isinstance(step.get(name), int) and not isinstance(step.get(name), bool)]
    return str(sum(counts)) if counts else "none"
clean = lambda text: " ".join(str(text).split())
print("\t".join([clean(incident.get("status") or "unreadable"), clean(incident.get("failureReason") or "-"), cause,
                 str(len(steps)), clean(incident.get("mode") or "none"), clean(incident.get("route") or "none"),
                 total("promptTokens"), total("completionTokens"), clean(incident.get("model") or "none")]))
' <<<"$1" 2>/dev/null || printf 'unreadable\t-\tnone\t0\t-\t-\t-\t-\t-\n'
}

# Scores one agent incident and prints "<MATCH|MISS><tab><message>". Arguments: $1 the requested mode, $2 the
# incident's mode as incident_fields prints it (- when the incident was unreadable), $3 the requested route, $4 the
# incident's route (- when unreadable), $5 the status, $6 the failure reason (- when none), $7 the reported cause, $8
# the truth (empty when unreadable). A readable incident must have run in the requested mode and on the requested
# route; a mismatch is a miss that still shows the outcome. An unreadable one fails on its status. Tested by
# test-lib.sh.
agent_verdict() {
  local requested_mode=$1 recorded_mode=$2 requested_route=$3 recorded_route=$4 status=$5 reason=$6 reported=$7
  local truth=$8 why="" outcome mismatch=""
  [[ "$reason" == - ]] || why=" ($reason)"
  outcome="status $status$why, reported '$reported', truth '${truth:-unreadable}'"
  if [[ "$recorded_mode" != - && "$recorded_mode" != "$requested_mode" ]]; then
    mismatch="ran in mode '$recorded_mode', not the requested '$requested_mode'; "
  fi
  if [[ "$recorded_route" != - && "$recorded_route" != "$requested_route" ]]; then
    mismatch+="ran on route '$recorded_route', not the requested '$requested_route'; "
  fi
  if [[ -n "$mismatch" ]]; then
    printf 'MISS\t%s%s\n' "$mismatch" "$outcome"
  elif [[ "$status" == COMPLETED && -n "$truth" && "$reported" == "$truth" ]]; then
    printf 'MATCH\tthe report names %s, the truth\n' "$reported"
  else
    printf 'MISS\t%s\n' "$outcome"
  fi
}

# Places an order and requires 201 PAID; exits otherwise. $1 names the order in the messages ("healthy", ...).
expect_paid_order() {
  local code id status
  read -r code id status _ <<<"$(place_order)"
  if [[ "$code" == 201 && "$status" == PAID ]]; then
    pass "$1 order $id is PAID"
  else
    fail "$1 order: expected 201 PAID, got $code $status"
    exit 1
  fi
}
