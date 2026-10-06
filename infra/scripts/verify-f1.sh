#!/usr/bin/env bash
# Checks that fault F1 (slow payment gateway) is visible in Prometheus and Loki (openspec/specs/lab-observability).
#
#   infra/scripts/verify-f1.sh          # start or reuse the lab, run the checks, leave the lab running
#   infra/scripts/verify-f1.sh --down   # same, then tear the lab down (containers and volumes)
#
# Exits 0 when every check passes, non-zero otherwise. The gateway behavior is reset on every exit.
# Needs docker (with compose) and curl on the host.
set -euo pipefail

COMPOSE_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/compose.yaml"
ORDERS=http://127.0.0.1:18080
GATEWAY=http://127.0.0.1:18082
PROMETHEUS=http://127.0.0.1:19090
SERVICES=(order-service payment-service gateway-mock)
LOKI_WAIT_SECONDS=180
CHECK_WAIT_SECONDS=60

down=false
for arg in "$@"; do
  case "$arg" in
    --down) down=true ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

failures=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; failures=$((failures + 1)); }
info() { echo "....  $*"; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

reset_gateway() {
  if curl -fsS -o /dev/null -X DELETE "$GATEWAY/admin/behavior" 2>/dev/null; then
    info "gateway behavior reset"
  else
    echo "WARN  could not reset the gateway behavior (DELETE $GATEWAY/admin/behavior)" >&2
  fi
}

on_exit() {
  local status=$?
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
trap on_exit EXIT

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
prom_query() { curl -fsS -G "$PROMETHEUS/api/v1/query" --data-urlencode "query=$1"; }

# True when the query returns at least one series (a comparison filters out the series that do not match).
prom_has_result() { prom_query "$1" 2>/dev/null | grep -q '"result":\[{'; }

# Loki has no host port; query it from the Grafana container, which has a shell and curl.
loki() { compose exec -T grafana curl -fsS -G "http://loki:3100$1" "${@:2}"; }

loki_ready() { [[ "$(loki /ready 2>/dev/null)" == ready* ]]; }

loki_has_all_services() {
  local values
  values=$(loki /loki/api/v1/label/service/values 2>/dev/null) || return 1
  for service in "${SERVICES[@]}"; do
    grep -q "\"$service\"" <<<"$values" || return 1
  done
}

# POST /orders; prints "<http code> <order id> <status>".
place_order() {
  local body code
  body=$(curl -sS -w '\n%{http_code}' -X POST "$ORDERS/orders" -H 'Content-Type: application/json' \
    -d '{"customerId":"verify-f1","items":[{"sku":"SKU-1","quantity":1}]}')
  code=${body##*$'\n'}
  body=${body%$'\n'*}
  local id status
  id=$(grep -o '"id":"[^"]*"' <<<"$body" | head -n1 | cut -d'"' -f4)
  status=$(grep -o '"status":"[^"]*"' <<<"$body" | head -n1 | cut -d'"' -f4)
  echo "$code ${id:-none} ${status:-none}"
}

# Current lab_orders{status="PAYMENT_PENDING"} as an integer; fails while the series is missing or NaN.
pending_count() {
  local value
  value=$(prom_query 'lab_orders{status="PAYMENT_PENDING"}' 2>/dev/null \
    | grep -o '"value":\[[^]]*\]' | head -n1 | cut -d'"' -f4)
  [[ "$value" =~ ^[0-9]+(\.0+)?$ ]] || return 1
  echo "${value%%.*}"
}

started=$SECONDS

# 1. Start or reuse the lab.
info "starting or reusing the lab (docker compose up -d --build --wait)"
if compose up -d --build --wait >/dev/null 2>&1; then
  pass "every container is up and healthy"
else
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

# 2. A healthy order is paid.
read -r code id status < <(place_order)
if [[ "$code" == 201 && "$status" == PAID ]]; then
  pass "healthy order $id is PAID"
else
  fail "healthy order: expected 201 PAID, got $code $status"
  exit 1
fi

baseline=""
read_baseline() { baseline=$(pending_count); }
if poll "$CHECK_WAIT_SECONDS" read_baseline; then
  info "lab_orders{status=\"PAYMENT_PENDING\"} before F1: $baseline"
else
  fail "lab_orders{status=\"PAYMENT_PENDING\"} has no numeric value in Prometheus"
  exit 1
fi

# 3. Inject F1: the gateway answers after 10 s, past order-service's 3 s read timeout.
if curl -fsS -o /dev/null -X PUT "$GATEWAY/admin/behavior" -H 'Content-Type: application/json' \
  -d '{"latencyMs":10000}'; then
  pass "F1 injected: gateway latency 10000 ms"
else
  fail "could not set the gateway latency"
  exit 1
fi

read -r code pending_id status < <(place_order)
if [[ "$code" == 201 && "$status" == PAYMENT_PENDING ]]; then
  pass "order $pending_id under F1 is 201 PAYMENT_PENDING"
else
  fail "order under F1: expected 201 PAYMENT_PENDING, got $code $status"
fi

# 4. Prometheus sees F1.
expected=$((baseline + 1))
if poll "$CHECK_WAIT_SECONDS" prom_has_result "lab_orders{status=\"PAYMENT_PENDING\"} >= $expected"; then
  pass "Prometheus: lab_orders{status=\"PAYMENT_PENDING\"} rose to at least $expected"
else
  fail "Prometheus: lab_orders{status=\"PAYMENT_PENDING\"} did not reach $expected within ${CHECK_WAIT_SECONDS}s"
fi

if poll "$CHECK_WAIT_SECONDS" prom_has_result 'max_over_time(gateway_authorize_inflight[1m]) > 0'; then
  pass "Prometheus: max_over_time(gateway_authorize_inflight[1m]) > 0"
else
  fail "Prometheus: gateway_authorize_inflight stayed at 0 within ${CHECK_WAIT_SECONDS}s"
fi

# 5. Loki sees the order-service WARN line for the pending order.
loki_has_warn() {
  loki /loki/api/v1/query_range \
    --data-urlencode "query={service=\"order-service\"} |= \"WARN\" |= \"$pending_id\"" \
    --data-urlencode "since=10m" --data-urlencode "limit=5" 2>/dev/null | grep -q "$pending_id"
}
if [[ "$pending_id" != none ]] && poll "$CHECK_WAIT_SECONDS" loki_has_warn; then
  pass "Loki: order-service WARN line for order $pending_id"
else
  fail "Loki: no order-service WARN line for order $pending_id within ${CHECK_WAIT_SECONDS}s"
fi

# 6. The trap resets the gateway on every exit.
info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
