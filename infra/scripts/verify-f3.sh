#!/usr/bin/env bash
# Checks that fault F3 (wrong payment URL in order-service) is visible in Prometheus and Loki. Run with --help for usage.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# F3 points order-service at a payment-service port where nothing listens, so every connection is refused at once.
F3_PAYMENT_BASEURL=http://payment-service:9999
# Pass threshold for POST /orders under F3: a refused connection fails in milliseconds, while a slow call (F1) takes the
# 3 s read timeout. ORDER_MAX_SECONDS in lib.sh is only curl's transport cap.
F3_FAST_FAIL_SECONDS=2
REFUSED_CALLS='sum(http_client_requests_seconds_count{service="order-service",client_name="payment-service",exception="ConnectException"}) or vector(0)'

usage() {
  cat <<'EOF'
Checks that fault F3 (wrong payment URL in order-service) is visible in Prometheus and Loki
(openspec/specs/lab-observability).

  infra/scripts/verify-f3.sh          # start or reuse the lab, run the checks, leave the lab running
  infra/scripts/verify-f3.sh --down   # same, then tear the lab down (containers and volumes)

F3 is injected and restored through the fault injector (POST /faults, POST /faults/{id}/restore), which recreates
order-service with ORDER_PAYMENT_BASEURL=http://payment-service:9999 and then without it. After the restore it checks
the injection's truth row (read as the faults role), that lab_app is not a superuser and cannot connect to the faults
database, and that Loki has no fault-injector stream.
Exits 0 when every check passes, non-zero otherwise. The injection is restored on every exit.
Needs docker (with compose), curl and awk on the host.
EOF
}

parse_args "$@"
trap on_exit EXIT

# 1. Start or reuse the lab; "up" puts order-service back on its default payment URL.
start_lab

# 2. A healthy order is paid.
expect_paid_order verify-f3 healthy

# 3. Inject F3 through the fault injector: a redeploy of order-service with a wrong payment URL.
# Wait one scrape interval first, so the healthy order's gateway call is scraped before the window that step 5 checks
# for gateway_authorize_inflight == 0 starts.
sleep "$SCRAPE_SETTLE_SECONDS"
injected_at=$SECONDS
inject_fault F3
configured=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' \
  "$(compose ps -q order-service)" 2>/dev/null | grep '^KINTSUGI_PAYMENT_BASEURL=' || true)
if [[ "$configured" == "KINTSUGI_PAYMENT_BASEURL=$F3_PAYMENT_BASEURL" ]]; then
  pass "order-service was recreated with $configured"
else
  fail "order-service runs with '${configured:-no KINTSUGI_PAYMENT_BASEURL}', expected $F3_PAYMENT_BASEURL"
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
read -r code pending_id status seconds <<<"$(place_order verify-f3)"
if [[ "$code" == 201 && "$status" == PAYMENT_PENDING ]]; then
  pass "order $pending_id under F3 is 201 PAYMENT_PENDING"
else
  fail "order under F3: expected 201 PAYMENT_PENDING, got $code $status"
fi
if [[ "$code" == 000 ]]; then
  fail "POST /orders under F3 got no HTTP answer (curl transport failure after ${seconds}s), so its speed is unknown"
elif awk -v s="$seconds" -v max="$F3_FAST_FAIL_SECONDS" 'BEGIN { exit !(s + 0 < max + 0) }'; then
  pass "POST /orders under F3 answered in ${seconds}s (under ${F3_FAST_FAIL_SECONDS}s)"
else
  fail "POST /orders under F3 took ${seconds}s, expected under ${F3_FAST_FAIL_SECONDS}s"
fi

# 5. Prometheus: the client metric shows the refused connection, and no authorization reached the gateway.
expected=$((refused_baseline + 1))
if poll "$CHECK_WAIT_SECONDS" prom_has_result "($REFUSED_CALLS) >= $expected"; then
  pass "Prometheus: refused payment calls (exception=\"ConnectException\") rose to at least $expected"
else
  fail "Prometheus: refused payment calls did not reach $expected within ${CHECK_WAIT_SECONDS}s"
fi

# The window starts at the injection; the settle wait before it keeps the healthy order's call out.
window=$((SECONDS - injected_at))
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

# 7. Recovery: the injector recreates order-service on its default URL, which pays orders again. The trap restores
# again on every exit.
expect_restored
expect_paid_order verify-f3 "after the restore, the"

# 8. The truth row and its isolation.
check_truth F3 order-service

info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
