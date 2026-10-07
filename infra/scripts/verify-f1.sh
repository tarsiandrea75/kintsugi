#!/usr/bin/env bash
# Checks that fault F1 (slow payment gateway) is visible in Prometheus and Loki. Run with --help for usage.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF'
Checks that fault F1 (slow payment gateway) is visible in Prometheus and Loki (openspec/specs/lab-observability).

  infra/scripts/verify-f1.sh          # start or reuse the lab, run the checks, leave the lab running
  infra/scripts/verify-f1.sh --down   # same, then tear the lab down (containers and volumes)

Exits 0 when every check passes, non-zero otherwise. The gateway behavior is reset on every exit.
Needs docker (with compose) and curl on the host.
EOF
}

parse_args "$@"
trap on_exit EXIT

# 1. Start or reuse the lab.
start_lab

# 2. A healthy order is paid.
expect_paid_order verify-f1 healthy

baseline=""
read_baseline() { baseline=$(prom_stable_int 'lab_orders{status="PAYMENT_PENDING"}'); }
if poll "$CHECK_WAIT_SECONDS" read_baseline; then
  info "lab_orders{status=\"PAYMENT_PENDING\"} before F1: $baseline"
else
  fail "lab_orders{status=\"PAYMENT_PENDING\"} has no numeric value in Prometheus"
  exit 1
fi

# 3. Inject F1: the gateway answers after 10 s, past order-service's 3 s read timeout.
if set_gateway_behavior '{"latencyMs":10000}'; then
  pass "F1 injected: gateway latency 10000 ms"
else
  fail "could not set the gateway latency"
  exit 1
fi

read -r code pending_id status < <(place_order verify-f1)
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
if [[ "$pending_id" != none ]] && poll "$CHECK_WAIT_SECONDS" loki_has_warn order-service "$pending_id"; then
  pass "Loki: order-service WARN line for order $pending_id"
else
  fail "Loki: no order-service WARN line for order $pending_id within ${CHECK_WAIT_SECONDS}s"
fi

# 6. The trap resets the gateway on every exit.
info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
