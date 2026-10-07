# Shared helpers for the fault visibility checks (verify-f1.sh, verify-f2.sh, verify-f3.sh). Source it; do not run it.
# The caller sets "set -euo pipefail", defines usage(), then calls parse_args "$@" and start_lab.

COMPOSE_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/compose.yaml"
ORDERS=http://127.0.0.1:18080
GATEWAY=http://127.0.0.1:18082
PROMETHEUS=http://127.0.0.1:19090
SERVICES=(order-service payment-service gateway-mock)
LOKI_WAIT_SECONDS=180
CHECK_WAIT_SECONDS=60
# Transport cap for POST /orders (curl --max-time), not a pass threshold: order-service answers within its 3 s payment
# read timeout; the margin covers the rest of the request.
ORDER_MAX_SECONDS=15
CURL_MAX_SECONDS=5

down=false
failures=0
started=$SECONDS

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

reset_gateway() {
  if curl -fsS --max-time "$CURL_MAX_SECONDS" -o /dev/null -X DELETE "$GATEWAY/admin/behavior" 2>/dev/null; then
    info "gateway behavior reset"
  else
    echo "WARN  could not reset the gateway behavior (DELETE $GATEWAY/admin/behavior)" >&2
  fi
}

# PUT /admin/behavior with the given JSON body; fails on a non-2xx answer.
set_gateway_behavior() {
  curl -fsS --max-time "$CURL_MAX_SECONDS" -o /dev/null -X PUT "$GATEWAY/admin/behavior" \
    -H 'Content-Type: application/json' -d "$1"
}

# A check that changes more than the gateway defines before_exit(); it runs first on every exit, so its restore
# happens even when a check fails or the script is interrupted.
on_exit() {
  local status=$?
  if declare -F before_exit >/dev/null; then
    before_exit || echo "WARN  before_exit failed" >&2
  fi
  reset_gateway
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
# fails: a transport failure (refused, timed out) prints "000 none none <seconds>". Callers read it with
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
  status=$(json_field status "$body")
  echo "${code:-000} ${id:-none} ${status:-none} ${seconds:-none}"
}

# Starts or reuses the lab, waits for Loki, and resets the gateway. Exits on failure.
start_lab() {
  info "starting or reusing the lab (docker compose up -d --build --wait)"
  local up_errors
  up_errors=$(mktemp)
  if compose up -d --build --wait >/dev/null 2>"$up_errors"; then
    rm -f "$up_errors"
    pass "every container is up and healthy"
  else
    cat "$up_errors" >&2
    rm -f "$up_errors"
    compose ps >&2 || true
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
