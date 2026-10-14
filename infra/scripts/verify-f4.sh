#!/usr/bin/env bash
# Checks that fault F4 (inventory DB pool exhausted) is visible in Prometheus and Loki, and that its cross-service
# evidence is stored in PostgreSQL. Run with --help for usage.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# F4 shrinks inventory-service's pool to 2 and makes every reservation hold its connection for 5 s. With the 2 s
# connection timeout, concurrent reservations beyond the second find no free connection and fail with a 500.
F4_POOL_SIZE=2
F4_RESERVATION_DELAY=5s
# Enough concurrent orders to exhaust a pool of 2 several times over.
F4_ORDERS=6
# Inventory commits a reservation after its 5 s delay; wait a little longer than that for the RESERVED row.
LEAK_WAIT_SECONDS=12
INVENTORY_5XX='sum(http_server_requests_seconds_count{service="inventory-service",uri="/reservations",status=~"5.."}) or vector(0)'
POOL_TIMEOUTS='sum(hikaricp_connections_timeout_total{service="inventory-service"}) or vector(0)'
POOL_PENDING='hikaricp_connections_pending{service="inventory-service"}'

usage() {
  cat <<'EOF'
Checks that fault F4 (inventory DB pool exhausted) is visible in Prometheus and Loki, and that PostgreSQL holds the
evidence of the leak: an order rejected by order-service whose reservation inventory-service still committed
(openspec/specs/lab-observability).

  infra/scripts/verify-f4.sh          # start or reuse the lab, run the checks, leave the lab running
  infra/scripts/verify-f4.sh --down   # same, then tear the lab down (containers and volumes)

F4 is injected and restored through the fault injector (POST /faults, POST /faults/{id}/restore), which recreates
inventory-service with INVENTORY_POOL_SIZE=2 and INVENTORY_RESERVATION_DELAY=5s and then without them. After the
restore it checks the injection's truth row (read as the faults role), that lab_app is not a superuser and cannot
connect to the faults database, and that Loki has no fault-injector stream.
Exits 0 when every check passes, non-zero otherwise. The injection is restored on every exit.
Needs docker (with compose) and curl on the host; PostgreSQL is queried through the postgres container.
EOF
}

parse_args "$@"
trap on_exit EXIT

# 1. Start or reuse the lab ("up", run without the fault variables, puts inventory-service back on its defaults); a
# healthy order is paid.
start_lab
expect_paid_order healthy

# 2. Inject F4 through the fault injector: a redeploy of inventory-service with a tiny pool and a slow reservation query.
injected_at=$SECONDS
inject_fault F4
environment=$(container_env inventory-service)
configured=$(grep -E '^(SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE|KINTSUGI_INVENTORY_RESERVATIONDELAY)=' <<<"$environment" |
  sort | tr '\n' ' ' || true)
if [[ "$configured" == "KINTSUGI_INVENTORY_RESERVATIONDELAY=$F4_RESERVATION_DELAY SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=$F4_POOL_SIZE " ]]; then
  pass "inventory-service was recreated with pool size $F4_POOL_SIZE and reservation delay $F4_RESERVATION_DELAY"
else
  fail "inventory-service runs with '${configured:-neither variable}', expected pool $F4_POOL_SIZE and delay $F4_RESERVATION_DELAY"
fi

# 3. The baselines are read after the recreate: the new process starts its counters from zero.
errors_baseline=""
timeouts_baseline=""
read_baselines() {
  errors_baseline=$(prom_stable_int "$INVENTORY_5XX") && timeouts_baseline=$(prom_stable_int "$POOL_TIMEOUTS")
}
if poll "$CHECK_WAIT_SECONDS" read_baselines; then
  info "after the recreate: inventory 5xx = $errors_baseline, pool timeouts = $timeouts_baseline"
else
  fail "the inventory 5xx or pool timeout query has no stable numeric value in Prometheus"
  exit 1
fi

# 4. Concurrent orders: each runs in a background subshell and writes its place_order line to its own file.
results=$(mktemp -d)
for ((i = 1; i <= F4_ORDERS; i++)); do
  place_order >"$results/$i" &
done
wait

# 5. At least one order is refused with 503; every answer is either 201 PAID or 503. The 503 order ids feed step 8.
unavailable=0
unavailable_ids=()
for ((i = 1; i <= F4_ORDERS; i++)); do
  read -r code id status seconds <"$results/$i" || code=000
  if [[ "$code" == 503 ]]; then
    unavailable=$((unavailable + 1))
    if is_uuid "$id"; then
      unavailable_ids+=("$id")
    else
      fail "order $i under F4 answered 503 without an orderId in its body"
    fi
  # Rarely reached with these settings: the 5 s query outlasts order-service's 3 s inventory read timeout, so even an
  # order that got a pooled connection answers 503. Kept so a faster delay or a longer timeout does not fail the check.
  elif [[ "$code" == 201 && "$status" == PAID ]]; then
    info "order $id under F4 got a connection and is PAID"
  else
    fail "order $i under F4: expected 503 or 201 PAID, got $code $status after ${seconds}s"
  fi
done
rm -rf "$results"
if (( unavailable >= 1 )); then
  pass "$unavailable of $F4_ORDERS concurrent orders under F4 answered 503"
else
  fail "no concurrent order under F4 answered 503"
fi

# 6. Prometheus: inventory answered 5xx, and the pool had waiting requests or timed some out.
expected=$((errors_baseline + 1))
if poll "$CHECK_WAIT_SECONDS" prom_has_result "($INVENTORY_5XX) >= $expected"; then
  pass "Prometheus: inventory-service 5xx on /reservations rose to at least $expected"
else
  fail "Prometheus: inventory-service 5xx on /reservations did not reach $expected within ${CHECK_WAIT_SECONDS}s"
fi

# A request waits for a connection for at most 2 s, so a 5 s scrape can miss pending; the timeout counter cannot.
pool_exhausted() {
  local window=$((SECONDS - injected_at))
  prom_has_result "max_over_time(${POOL_PENDING}[${window}s]) >= 1" ||
    prom_has_result "($POOL_TIMEOUTS) >= $((timeouts_baseline + 1))"
}
if poll "$CHECK_WAIT_SECONDS" pool_exhausted; then
  pass "Prometheus: the inventory pool had pending requests or connection timeouts"
else
  fail "Prometheus: no pending requests and no connection timeouts on the inventory pool within ${CHECK_WAIT_SECONDS}s"
fi

# 7. Loki: order-service names the unavailable inventory, or inventory-service names the missing connection.
# Unlike loki_has_warn (last 10 minutes), the window starts at the injection, so lines from an earlier run do not count.
loki_has_warn_since_injection() {
  local json
  json=$(loki /loki/api/v1/query_range \
    --data-urlencode "query={service=\"$1\"} |= \"WARN\" |= \"$2\"" \
    --data-urlencode "since=$((SECONDS - injected_at + 1))s" --data-urlencode "limit=5" 2>/dev/null) || return 1
  grep -q "$2" <<<"$json"
}
loki_sees_f4() {
  loki_has_warn_since_injection order-service "Inventory unavailable" ||
    loki_has_warn_since_injection inventory-service "No database connection available"
}
if poll "$CHECK_WAIT_SECONDS" loki_sees_f4; then
  pass "Loki: WARN line for the unavailable inventory or the exhausted pool"
else
  fail "Loki: no F4 WARN line in order-service or inventory-service within ${CHECK_WAIT_SECONDS}s"
fi

# 8. PostgreSQL evidence of the leak, checked before the restore: recreating inventory-service now would kill a
# reservation still sleeping on its connection, and its RESERVED row would never commit.
# Every 503 order must be stored by order-service as REJECTED_INVENTORY_UNAVAILABLE with an INVENTORY_UNAVAILABLE event:
# order-service commits both before it answers, so this holds for each of them at once.
rejected_ok=0
# Guarded: expanding an empty array under set -u fails on bash 4.3 and older.
for id in ${unavailable_ids[@]+"${unavailable_ids[@]}"}; do
  order_status=$(psql_lab orders "select status from orders where id = '$id'" 2>/dev/null || true)
  order_event=$(psql_lab orders "select type, outcome, duration_ms from order_events
    where order_id = '$id' and type = 'INVENTORY_UNAVAILABLE'" 2>/dev/null || true)
  if [[ "$order_status" == REJECTED_INVENTORY_UNAVAILABLE && -n "$order_event" ]]; then
    rejected_ok=$((rejected_ok + 1))
  else
    fail "PostgreSQL: 503 order $id has status '${order_status:-none}' and order_events '${order_event:-none}'"
  fi
done
if (( ${#unavailable_ids[@]} > 0 && rejected_ok == ${#unavailable_ids[@]} )); then
  pass "PostgreSQL: all $rejected_ok orders answered 503 are REJECTED_INVENTORY_UNAVAILABLE with an INVENTORY_UNAVAILABLE event"
fi

# The leak: at least one of those orders also has a RESERVED reservation_events row, because inventory-service kept its
# connection, slept past order-service's 3 s read timeout and then committed the decrement. Not every 503 order has one:
# an order whose reservation never got a pooled connection was refused by inventory-service and reserved nothing. So
# the check is "at least one 503 order has both records". The reservation commits 5 s after it started, so poll.
leaked_id=""
find_leaked_order() {
  local id
  for id in "${unavailable_ids[@]}"; do
    if [[ -n "$(psql_lab inventory "select 1 from reservation_events
      where order_id = '$id' and outcome = 'RESERVED'" 2>/dev/null || true)" ]]; then
      leaked_id=$id
      return 0
    fi
  done
  return 1
}
if (( ${#unavailable_ids[@]} > 0 )) && poll "$LEAK_WAIT_SECONDS" find_leaked_order; then
  pass "PostgreSQL: order $leaked_id was rejected by order-service and reserved by inventory-service"
  info "orders:             $(psql_lab orders "select id, status from orders where id = '$leaked_id'")"
  info "order_events:       $(psql_lab orders "select type, outcome, duration_ms, occurred_at from order_events
    where order_id = '$leaked_id' and type = 'INVENTORY_UNAVAILABLE'")"
  info "reservation_events: $(psql_lab inventory "select outcome, duration_ms, occurred_at, detail from reservation_events
    where order_id = '$leaked_id' and outcome = 'RESERVED'")"
else
  fail "PostgreSQL: no order answered 503 has a RESERVED reservation_events row within ${LEAK_WAIT_SECONDS}s"
fi

# 9. Recovery: the injector recreates inventory-service on its defaults; the trap restores again on every exit.
expect_restored

# 10. A new order is paid again.
expect_paid_order "after the restore, the"

# 11. The truth row and its isolation.
check_truth F4 inventory-service

info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
