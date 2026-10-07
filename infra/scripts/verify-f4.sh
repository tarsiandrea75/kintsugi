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

F4 is injected by recreating inventory-service with INVENTORY_POOL_SIZE=2 and INVENTORY_RESERVATION_DELAY=5s.
Exits 0 when every check passes, non-zero otherwise. inventory-service is recreated without the variables on every
exit. Needs docker (with compose) and curl on the host; PostgreSQL is queried through the postgres container.
EOF
}

# Recreates inventory-service with pool size $1 and reservation delay $2, or with the defaults from compose.yaml when
# they are empty. Inject and restore both go through here; compose only recreates the container when a value changed.
recreate_inventory_service() {
  env -u INVENTORY_POOL_SIZE -u INVENTORY_RESERVATION_DELAY \
    ${1:+"INVENTORY_POOL_SIZE=$1"} ${2:+"INVENTORY_RESERVATION_DELAY=$2"} \
    docker compose -f "$COMPOSE_FILE" up -d --no-deps --wait inventory-service >/dev/null 2>&1
}
restore_inventory_service() { recreate_inventory_service "" ""; }

before_exit() {
  if restore_inventory_service; then
    info "inventory-service restored with its default pool and no reservation delay"
  else
    echo "WARN  could not restore inventory-service; run: docker compose -f infra/compose.yaml up -d --no-deps --wait inventory-service" >&2
    return 1
  fi
}

parse_args "$@"
trap on_exit EXIT

# 1. Start or reuse the lab, with inventory-service on its defaults; a healthy order is paid.
start_lab
if ! restore_inventory_service; then
  fail "could not start inventory-service with its default pool"
  exit 1
fi
expect_paid_order verify-f4 healthy

# 2. Inject F4: a redeploy of inventory-service with a tiny pool and a slow reservation query.
injected_at=$SECONDS
if recreate_inventory_service "$F4_POOL_SIZE" "$F4_RESERVATION_DELAY"; then
  pass "F4 injected: inventory-service recreated with pool size $F4_POOL_SIZE and reservation delay $F4_RESERVATION_DELAY"
else
  fail "could not recreate inventory-service with the F4 settings"
  exit 1
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
  place_order verify-f4 >"$results/$i" &
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
for id in "${unavailable_ids[@]}"; do
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
if (( ${#unavailable_ids[@]} > 0 )) && poll 12 find_leaked_order; then
  pass "PostgreSQL: order $leaked_id was rejected by order-service and reserved by inventory-service"
  info "orders:             $(psql_lab orders "select id, status from orders where id = '$leaked_id'")"
  info "order_events:       $(psql_lab orders "select type, outcome, duration_ms, occurred_at from order_events
    where order_id = '$leaked_id' and type = 'INVENTORY_UNAVAILABLE'")"
  info "reservation_events: $(psql_lab inventory "select outcome, duration_ms, occurred_at, detail from reservation_events
    where order_id = '$leaked_id' and outcome = 'RESERVED'")"
else
  fail "PostgreSQL: no order answered 503 has a RESERVED reservation_events row within 12s"
fi

# 9. Recovery: inventory-service back on its defaults; the trap restores it again on every exit.
if restore_inventory_service; then
  pass "inventory-service recreated with its default pool and no reservation delay"
else
  fail "could not restore inventory-service"
  exit 1
fi

# 10. A new order is paid again.
expect_paid_order verify-f4 "after the restore, the"

info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
