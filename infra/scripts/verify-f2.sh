#!/usr/bin/env bash
# Checks that fault F2 (gateway rejecting with 429) is visible in Prometheus and Loki. Run with --help for usage.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

F2_ORDERS=3
FAILED_PAYMENTS='lab_payments{status="FAILED"}'
FAILED_ORDERS='lab_orders{status="PAYMENT_FAILED"}'

usage() {
  cat <<'EOF'
Checks that fault F2 (gateway rejecting with 429) is visible in Prometheus and Loki (openspec/specs/lab-observability).

  infra/scripts/verify-f2.sh          # start or reuse the lab, run the checks, leave the lab running
  infra/scripts/verify-f2.sh --down   # same, then tear the lab down (containers and volumes)

Exits 0 when every check passes, non-zero otherwise. The gateway behavior is reset on every exit.
Needs docker (with compose) and curl on the host.
EOF
}

parse_args "$@"
trap on_exit EXIT

# 1. Start or reuse the lab.
start_lab

# 2. A healthy order is paid.
expect_paid_order verify-f2 healthy

payments_baseline=""
orders_baseline=""
read_baselines() {
  payments_baseline=$(prom_stable_int "$FAILED_PAYMENTS") && orders_baseline=$(prom_stable_int "$FAILED_ORDERS")
}
if poll "$CHECK_WAIT_SECONDS" read_baselines; then
  info "before F2: $FAILED_PAYMENTS = $payments_baseline, $FAILED_ORDERS = $orders_baseline"
else
  fail "$FAILED_PAYMENTS or $FAILED_ORDERS has no numeric value in Prometheus"
  exit 1
fi

# 3. Inject F2: every authorization is rejected with 429.
if set_gateway_behavior '{"latencyMs":0,"errorStatus":429}'; then
  pass "F2 injected: gateway answers 429"
else
  fail "could not set the gateway error status"
  exit 1
fi

failed_id=none
for ((i = 1; i <= F2_ORDERS; i++)); do
  read -r code id status < <(place_order verify-f2)
  if [[ "$code" == 201 && "$status" == PAYMENT_FAILED ]]; then
    pass "order $id under F2 is 201 PAYMENT_FAILED"
    failed_id=$id
  else
    fail "order $i under F2: expected 201 PAYMENT_FAILED, got $code $status"
  fi
done

# 4. Prometheus sees F2 in both services.
expected=$((payments_baseline + F2_ORDERS))
if poll "$CHECK_WAIT_SECONDS" prom_has_result "$FAILED_PAYMENTS >= $expected"; then
  pass "Prometheus: $FAILED_PAYMENTS rose to at least $expected"
else
  fail "Prometheus: $FAILED_PAYMENTS did not reach $expected within ${CHECK_WAIT_SECONDS}s"
fi

expected=$((orders_baseline + F2_ORDERS))
if poll "$CHECK_WAIT_SECONDS" prom_has_result "$FAILED_ORDERS >= $expected"; then
  pass "Prometheus: $FAILED_ORDERS rose to at least $expected"
else
  fail "Prometheus: $FAILED_ORDERS did not reach $expected within ${CHECK_WAIT_SECONDS}s"
fi

# 5. Loki sees the payment-service WARN line for a failed order.
if [[ "$failed_id" != none ]] && poll "$CHECK_WAIT_SECONDS" loki_has_warn payment-service "$failed_id" "Gateway rejected the payment"; then
  pass "Loki: payment-service WARN line for order $failed_id"
else
  fail "Loki: no payment-service WARN line for order $failed_id within ${CHECK_WAIT_SECONDS}s"
fi

# 6. Recovery: after a reset, orders are paid again. The trap resets the gateway again on every exit.
reset_gateway
expect_paid_order verify-f2 "after the reset, the"

info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
