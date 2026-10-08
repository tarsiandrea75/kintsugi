# Shared helpers for the fault visibility checks (verify-f1.sh … verify-f4.sh). Source it; do not run it.
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

# POST /orders as customer $1; always prints "<http code> <order id> <status> <seconds>", one line, even when curl
# fails: a transport failure (refused, timed out) prints "000 none none <seconds>". The order id is the "id" of a 201
# body, or the "orderId" of a 409 or 503 ProblemDetail body (the rejected order is stored too); a ProblemDetail has no
# order status, so status is "none" then. Callers read it with
#   read -r code id status seconds <<<"$(place_order customer)"
# and report a FAIL themselves, so a broken request never aborts the script before the exit trap restores the lab.
place_order() {
  local out rc=0 code seconds body id status
  out=$(curl -sS --max-time "$ORDER_MAX_SECONDS" -w '\n%{http_code} %{time_total}' -X POST "$ORDERS/orders" \
    -H 'Content-Type: application/json' -d "{\"customerId\":\"$1\",\"items\":[{\"sku\":\"SKU-1\",\"quantity\":1}]}") || rc=$?
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
  expected=$(compose config --services 2>/dev/null | grep -vxE 'knowledge-indexer|ollama-proxy') || return 1
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

# After a failed "up --wait", which stops waiting at its first error, waits up to 300 s for every service but
# knowledge-indexer and ollama-proxy to be running and healthy, without building, recreating or starting either of
# those two. "$@" is the env options that unset the fault variables; compose's errors are appended to file $UP_ERRORS.
wait_without_knowledge() {
  local services
  services=$(compose config --services 2>/dev/null | grep -vxE 'knowledge-indexer|ollama-proxy') || return 1
  # shellcheck disable=SC2086 # one word per service name
  env "$@" docker compose --progress quiet -f "$COMPOSE_FILE" up -d --no-build --no-deps --wait --wait-timeout 300 \
    $services >/dev/null 2>>"$UP_ERRORS"
}

# Starts or reuses the lab, waits for Loki, restores any injection an earlier run left blocking, and resets the gateway.
# Exits on failure. A failure of the knowledge base's Ollama path alone (knowledge_only_failure) is reported as WARN
# lines, with compose's own errors, and the lab is used: the evidence tools do not need Ollama.
start_lab() {
  info "starting or reusing the lab (docker compose up -d --build --wait)"
  local unset_faults=() variable reasons line
  for variable in "${FAULT_VARIABLES[@]}"; do unset_faults+=(-u "$variable"); done
  UP_ERRORS=$(mktemp)
  # Without the fault variables, "up" also puts a service left faulted by an earlier run back on its defaults.
  # "--progress quiet": compose's stderr then holds only its warnings and errors, which a failure path prints.
  if env "${unset_faults[@]}" docker compose --progress quiet -f "$COMPOSE_FILE" up -d --build --wait \
    >/dev/null 2>"$UP_ERRORS"; then
    rm -f "$UP_ERRORS"
    pass "every container is up and healthy"
  elif wait_without_knowledge "${unset_faults[@]}" && reasons=$(knowledge_only_failure); then
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

# Places an order as customer $1 and requires 201 PAID; exits otherwise.
expect_paid_order() {
  local code id status
  read -r code id status _ <<<"$(place_order "$1")"
  if [[ "$code" == 201 && "$status" == PAID ]]; then
    pass "$2 order $id is PAID"
  else
    fail "$2 order: expected 201 PAID, got $code $status"
    exit 1
  fi
}
