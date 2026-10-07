#!/usr/bin/env bash
# Checks that fault F3 (wrong payment URL in order-service) is visible in Prometheus and Loki. Run with --help for usage.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# F3 points order-service at a payment-service port where nothing listens, so every connection is refused at once.
F3_PAYMENT_BASEURL=http://payment-service:9999
# A refused connection fails in milliseconds; a slow call (F1) takes the 3 s read timeout.
F3_ORDER_MAX_SECONDS=2
REFUSED_CALLS='sum(http_client_requests_seconds_count{service="order-service",client_name="payment-service",exception="ConnectException"}) or vector(0)'

usage() {
  cat <<'EOF'
Checks that fault F3 (wrong payment URL in order-service) is visible in Prometheus and Loki
(openspec/specs/lab-observability).

  infra/scripts/verify-f3.sh          # start or reuse the lab, run the checks, leave the lab running
  infra/scripts/verify-f3.sh --down   # same, then tear the lab down (containers and volumes)

F3 is injected by recreating order-service with ORDER_PAYMENT_BASEURL=http://payment-service:9999.
Exits 0 when every check passes, non-zero otherwise. order-service is recreated without the variable on every exit.
Needs docker (with compose), curl and awk on the host.
EOF
}

# Recreates order-service with the payment URL from compose.yaml; compose only recreates it when the URL changed.
restore_order_service() {
  env -u ORDER_PAYMENT_BASEURL docker compose -f "$COMPOSE_FILE" up -d --no-deps --wait order-service >/dev/null 2>&1
}

before_exit() {
  if restore_order_service; then
    info "order-service restored with its default payment URL"
  else
    echo "WARN  could not restore order-service; run: docker compose -f infra/compose.yaml up -d --no-deps --wait order-service" >&2
    return 1
  fi
}

# POST /orders as customer $1; prints "<http code> <order id> <status> <seconds>".
place_timed_order() {
  local out code seconds body id status
  out=$(curl -sS --max-time "$ORDER_MAX_SECONDS" -w '\n%{http_code} %{time_total}' -X POST "$ORDERS/orders" \
    -H 'Content-Type: application/json' -d "{\"customerId\":\"$1\",\"items\":[{\"sku\":\"SKU-1\",\"quantity\":1}]}")
  read -r code seconds <<<"${out##*$'\n'}"
  body=${out%$'\n'*}
  id=$(grep -o '"id":"[^"]*"' <<<"$body" | head -n1 | cut -d'"' -f4)
  status=$(grep -o '"status":"[^"]*"' <<<"$body" | head -n1 | cut -d'"' -f4)
  echo "$code ${id:-none} ${status:-none} $seconds"
}

parse_args "$@"
trap on_exit EXIT

# 1. Start or reuse the lab, with order-service on its default payment URL.
start_lab
if ! restore_order_service; then
  fail "could not start order-service with its default payment URL"
  exit 1
fi

# 2. A healthy order is paid.
expect_paid_order verify-f3 healthy

# 3. Inject F3: a redeploy of order-service with a wrong payment URL.
injected_at=$SECONDS
if ORDER_PAYMENT_BASEURL=$F3_PAYMENT_BASEURL compose up -d --no-deps --wait order-service >/dev/null 2>&1; then
  pass "F3 injected: order-service recreated with payment URL $F3_PAYMENT_BASEURL"
else
  fail "could not recreate order-service with the wrong payment URL"
  exit 1
fi

# The baseline is read after the recreate: the new process starts its counters from zero.
refused_baseline=""
read_baseline() { refused_baseline=$(prom_stable_int "$REFUSED_CALLS"); }
if poll "$CHECK_WAIT_SECONDS" read_baseline; then
  info "after the recreate: refused payment calls = $refused_baseline"
else
  fail "the refused payment calls query has no stable numeric value in Prometheus"
  exit 1
fi

# 4. The order is created, stays pending, and the answer is fast because the connection is refused at once.
read -r code pending_id status seconds < <(place_timed_order verify-f3)
if [[ "$code" == 201 && "$status" == PAYMENT_PENDING ]]; then
  pass "order $pending_id under F3 is 201 PAYMENT_PENDING"
else
  fail "order under F3: expected 201 PAYMENT_PENDING, got $code $status"
fi
if awk -v s="$seconds" -v max="$F3_ORDER_MAX_SECONDS" 'BEGIN { exit !(s + 0 < max + 0) }'; then
  pass "POST /orders under F3 answered in ${seconds}s (under ${F3_ORDER_MAX_SECONDS}s)"
else
  fail "POST /orders under F3 took ${seconds}s, expected under ${F3_ORDER_MAX_SECONDS}s"
fi

# 5. Prometheus: the client metric shows the refused connection, and no authorization reached the gateway.
expected=$((refused_baseline + 1))
if poll "$CHECK_WAIT_SECONDS" prom_has_result "($REFUSED_CALLS) >= $expected"; then
  pass "Prometheus: refused payment calls (exception=\"ConnectException\") rose to at least $expected"
else
  fail "Prometheus: refused payment calls did not reach $expected within ${CHECK_WAIT_SECONDS}s"
fi

window=$((SECONDS - injected_at + 10))
if prom_has_result "max_over_time(gateway_authorize_inflight[${window}s]) == 0"; then
  pass "Prometheus: gateway_authorize_inflight stayed 0 since the injection (${window}s)"
else
  fail "Prometheus: gateway_authorize_inflight was above 0 (or missing) in the last ${window}s"
fi

# 6. Loki: the order-service WARN line names the order and the refused connection.
if [[ "$pending_id" != none ]] && poll "$CHECK_WAIT_SECONDS" loki_has_warn order-service "$pending_id" "ConnectException"; then
  pass "Loki: order-service WARN line with ConnectException for order $pending_id"
else
  fail "Loki: no order-service WARN line with ConnectException for order $pending_id within ${CHECK_WAIT_SECONDS}s"
fi

# 7. Recovery: order-service back on its default URL pays orders again. The trap restores it again on every exit.
if restore_order_service; then
  pass "order-service recreated with its default payment URL"
else
  fail "could not restore order-service"
  exit 1
fi
expect_paid_order verify-f3 "after the restore, the"

info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
