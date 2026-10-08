#!/usr/bin/env bash
# Checks that mcp-tools serves the agent's tools over MCP while F1 is active. Run with --help for usage.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF'
Checks mcp-tools (openspec/specs/mcp-tools, ADR-004) through MCP while F1 (slow payment gateway) is active.

  infra/scripts/verify-mcp.sh          # start or reuse the lab, run the checks, leave the lab running
  infra/scripts/verify-mcp.sh --down   # same, then tear the lab down (containers and volumes)

It speaks JSON-RPC over HTTP to http://127.0.0.1:18085/mcp, as the agent will: initialize, tools/list (exactly the
seven tools), then tools/call. Under F1 it checks that search_logs finds order-service's WARN line for a pending
order, that query_metric shows the PAYMENT_PENDING orders rising, and that get_order_trace returns the order's
PAYMENT_UNAVAILABLE event. It checks that an invalid parameter answers INVALID_PARAMETER, that an Origin header gets
403 and an unknown Host 421, and, with psql as kintsugi_agent, that the role cannot connect to faults, cannot insert
into orders and is not a superuser. F1 is injected and restored through the fault injector, then the truth checks of
verify-f1.sh run.
A volume created before mcp-tools has no kintsugi_agent role; the script then recreates the lab with "down -v".
Exits 0 when every check passes, non-zero otherwise. The injection is restored on every exit.
Needs docker (with compose), curl and awk on the host.
EOF
}

MCP=http://127.0.0.1:18085/mcp
# Transport cap for one MCP call: the tools' own backend timeouts are at most 5 s.
MCP_MAX_SECONDS=15
TOOLS="count_logs get_order_trace query_metric recent_events search_logs service_restarts status_summary"

parse_args "$@"
trap on_exit EXIT

# Posts JSON-RPC method $1 with params $2 to /mcp, with any extra curl arguments after them (such as headers). Prints
# "<http code> <body>" on one line, "000 " on a transport failure; never fails.
mcp_call() {
  local out rc=0
  out=$(curl -sS --max-time "$MCP_MAX_SECONDS" -w '\n%{http_code}' -X POST "$MCP" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' "${@:3}" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":$2}" 2>/dev/null) || rc=$?
  (( rc == 0 )) || { echo "000 "; return 0; }
  echo "${out##*$'\n'} ${out%$'\n'*}" | tr -d '\n'
  echo
}

# Calls tool $1 with the JSON arguments $2; prints the response body. The tool result is JSON inside the "text" string,
# so its quotes arrive escaped; the backslashes are removed to match it as plain JSON.
tool_call() {
  local code body
  read -r code body <<<"$(mcp_call tools/call "{\"name\":\"$1\",\"arguments\":$2}")" || true
  [[ "$code" == 200 ]] || { echo "HTTP $code"; return 0; }
  tr -d '\\' <<<"$body"
}

# The values of the series whose labels include "$1":"$2", in a tool_call body of query_metric, one per line, oldest
# first. Each point is [unixSeconds,"value"].
series_values() {
  awk -v label="\"$1\":\"$2\"" '{
    n = split($0, series, "\"labels\":");
    for (i = 2; i <= n; i++) {
      if (index(series[i], label) == 0) continue;
      rest = series[i];
      while (match(rest, /\[[0-9]+,"[^"]*"\]/)) {
        point = substr(rest, RSTART, RLENGTH);
        sub(/^\[[0-9]+,"/, "", point); sub(/"\]$/, "", point);
        print point;
        rest = substr(rest, RSTART + RLENGTH);
      }
    }
  }'
}

# True when search_logs finds order-service's WARN line for order $1 through MCP.
mcp_finds_warn() {
  local body
  body=$(tool_call search_logs "{\"service\":\"order-service\",\"contains\":\"$1\",\"level\":\"WARN\",\"sinceMinutes\":10}")
  [[ "$body" == *'"outcome":"OK"'* && "$body" == *"Payment of order $1 unavailable, leaving it in PAYMENT_PENDING"* ]]
}

# True when query_metric's lab_orders series for PAYMENT_PENDING ends at least one above where it started; prints
# "<first> -> <last>".
mcp_sees_pending_rise() {
  local body values first last
  body=$(tool_call query_metric '{"metric":"lab_orders","service":"order-service","sinceMinutes":10}')
  [[ "$body" == *'"outcome":"OK"'* ]] || return 1
  values=$(series_values status PAYMENT_PENDING <<<"$body" | grep -E '^[0-9]+(\.[0-9]+)?$' || true)
  [[ -n "$values" ]] || return 1
  first=$(head -n1 <<<"$values"); last=$(tail -n1 <<<"$values")
  awk -v a="$first" -v b="$last" 'BEGIN { exit !(b >= a + 1) }' || return 1
  echo "$first -> $last"
}

# Runs SQL $2 in database $1 as kintsugi_agent, the read-only role of mcp-tools; extra psql arguments follow.
psql_agent() { compose exec -T postgres psql -U kintsugi_agent -d "$1" -v ON_ERROR_STOP=1 -Atc "$2" "${@:3}"; }

# 0. A volume created before mcp-tools has no kintsugi_agent role, and the init script runs only on an empty volume.
if [[ -n "$(compose ps -q postgres 2>/dev/null || true)" ]]; then
  role=$(psql_lab orders "select count(*) from pg_roles where rolname = 'kintsugi_agent'" 2>/dev/null || true)
  if [[ "$role" != 1 ]]; then
    info "the postgres volume predates kintsugi_agent; recreating the lab (docker compose down -v)"
    compose down -v >/dev/null 2>&1
  fi
fi

# 1. Start or reuse the lab, mcp-tools included.
start_lab

# 2. MCP handshake and the tool list.
read -r code body <<<"$(mcp_call initialize \
  '{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-mcp","version":"1"}}')" || true
if [[ "$code" == 200 && "$body" == *'"protocolVersion":"2025-11-25"'* && "$body" == *'"name":"mcp-tools"'* ]]; then
  pass "MCP initialize: mcp-tools answers with protocol 2025-11-25"
else
  fail "MCP initialize: expected 200 with protocol 2025-11-25, got $code ${body:0:300}"
  exit 1
fi

read -r code body <<<"$(mcp_call tools/list '{}')" || true
listed=$(grep -o '"name":"[a-z_]*"' <<<"$body" | cut -d'"' -f4 | sort | tr '\n' ' ' | sed 's/ $//' || true)
if [[ "$code" == 200 && "$listed" == "$TOOLS" ]]; then
  pass "MCP tools/list: exactly the 7 tools ($listed)"
else
  fail "MCP tools/list: expected '$TOOLS', got $code '$listed'"
fi

# 3. A healthy order, then F1 through the fault injector and an order left PAYMENT_PENDING.
expect_paid_order verify-mcp healthy
inject_fault F1

read -r code pending_id status _ <<<"$(place_order verify-mcp)"
if [[ "$code" == 201 && "$status" == PAYMENT_PENDING ]] && is_uuid "$pending_id"; then
  pass "order $pending_id under F1 is 201 PAYMENT_PENDING"
else
  fail "order under F1: expected 201 PAYMENT_PENDING, got $code $status"
  exit 1
fi

# 4. The evidence, through the tools.
if poll "$CHECK_WAIT_SECONDS" mcp_finds_warn "$pending_id"; then
  pass "search_logs: order-service WARN line for order $pending_id"
else
  fail "search_logs: no order-service WARN line for order $pending_id within ${CHECK_WAIT_SECONDS}s"
fi

rise=""
read_rise() { rise=$(mcp_sees_pending_rise); }
if poll "$CHECK_WAIT_SECONDS" read_rise; then
  pass "query_metric: lab_orders{status=\"PAYMENT_PENDING\"} rose ($rise)"
else
  fail "query_metric: lab_orders{status=\"PAYMENT_PENDING\"} did not rise within ${CHECK_WAIT_SECONDS}s"
fi

body=$(tool_call get_order_trace "{\"orderId\":\"$pending_id\"}")
if [[ "$body" == *'"outcome":"OK"'* && "$body" == *'"status":"PAYMENT_PENDING"'* \
  && "$body" == *'"type":"PAYMENT_UNAVAILABLE"'* ]]; then
  pass "get_order_trace: order $pending_id is PAYMENT_PENDING with a PAYMENT_UNAVAILABLE event"
else
  fail "get_order_trace: expected PAYMENT_PENDING and a PAYMENT_UNAVAILABLE event, got ${body:0:400}"
fi

# 5. What the tools refuse.
body=$(tool_call search_logs '{"service":"fault-injector","sinceMinutes":10}')
if [[ "$body" == *'"outcome":"INVALID_PARAMETER"'* && "$body" == *'"isError":true'* ]]; then
  pass "search_logs on fault-injector answers INVALID_PARAMETER"
else
  fail "search_logs on fault-injector: expected INVALID_PARAMETER, got ${body:0:300}"
fi

read -r code _ <<<"$(mcp_call tools/list '{}' -H 'Origin: http://localhost:18085')" || true
if [[ "$code" == 403 ]]; then
  pass "a request with an Origin header gets 403"
else
  fail "a request with an Origin header: expected 403, got $code"
fi

read -r code _ <<<"$(mcp_call tools/list '{}' -H 'Host: evil.example:18085')" || true
if [[ "$code" == 421 ]]; then
  pass "a request with an unknown Host gets 421"
else
  fail "a request with an unknown Host: expected 421, got $code"
fi

# 6. The kintsugi_agent role, with psql inside the postgres container.
if denied=$(psql_agent faults "select 1" 2>&1); then
  fail "PostgreSQL: kintsugi_agent connected to the faults database"
elif grep -q 'permission denied for database "faults"' <<<"$denied"; then
  pass "PostgreSQL: kintsugi_agent cannot connect to the faults database (permission denied)"
else
  fail "PostgreSQL: kintsugi_agent could not connect to faults, but not for lack of permission: $denied"
fi

# The read-only session default is turned off first, so only the missing grant can stop the insert.
if denied=$(psql_agent orders "set default_transaction_read_only = off" \
  -c "insert into orders (id, customer_id, status, version, created_at, updated_at)
      values (gen_random_uuid(), 'verify-mcp', 'CREATED', 0, now(), now())" 2>&1); then
  fail "PostgreSQL: kintsugi_agent inserted into orders"
elif grep -q 'permission denied for table orders' <<<"$denied"; then
  pass "PostgreSQL: kintsugi_agent cannot insert into orders (permission denied)"
else
  fail "PostgreSQL: the insert as kintsugi_agent failed, but not for lack of permission: $denied"
fi

super=$(psql_agent orders "select rolsuper from pg_roles where rolname = 'kintsugi_agent'" 2>&1 || true)
if [[ "$super" == f ]]; then
  pass "PostgreSQL: kintsugi_agent is not a superuser (rolsuper false)"
else
  fail "PostgreSQL: expected rolsuper false for kintsugi_agent, got '$super'"
fi

# 7. Every tool call above is recorded by mcp-tools, one INFO line per call.
# The logs are captured first: piping into grep -q under pipefail can fail when grep exits before docker finishes.
mcp_logs=$(compose logs --no-log-prefix mcp-tools 2>/dev/null || true)
if grep "tool=get_order_trace " <<<"$mcp_logs" | grep -q "orderId=$pending_id"; then
  pass "docker logs: mcp-tools recorded the get_order_trace call"
else
  fail "docker logs: no mcp-tools call record for get_order_trace on $pending_id"
fi

# 8. Restore through the injector (the trap restores again on every exit), then check the truth and its isolation.
expect_restored
check_truth F1 gateway-mock

info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
