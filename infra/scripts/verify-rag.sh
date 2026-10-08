#!/usr/bin/env bash
# Checks the runbook knowledge base end to end: the store, the indexer and search_runbooks over MCP. Run with --help.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF'
Checks the runbook knowledge base (openspec/specs/knowledge-base, ADR-002) and search_runbooks (openspec/specs/mcp-tools)
on the running lab, with the local Ollama reached through ollama-proxy.

  infra/scripts/verify-rag.sh          # start or reuse the lab, run the checks, leave the lab running
  infra/scripts/verify-rag.sh --down   # same, then tear the lab down (containers and volumes)

It waits for the one-shot knowledge-indexer to exit 0, then checks with psql inside knowledge-db that index_metadata
records nomic-embed-text, its digest and 768 dimensions, and that every service runbook has chunks. Through MCP on
http://127.0.0.1:18085/mcp it asks search_runbooks symptom-worded questions and expects the right runbook in the top 3,
and never the README's "Authoring rule" section. It checks that kintsugi_agent cannot write the knowledge store, that
the store has no faults database, and that kintsugi_agent cannot connect to faults on the lab's postgres. It runs the
indexer a second time and expects every runbook skipped, then starts a one-off mcp-tools configured with another model
and expects search_runbooks to refuse it.
No fault is injected. Exits 0 when every check passes, non-zero otherwise.
Needs docker (with compose), curl and the local Ollama on 127.0.0.1:11434 with nomic-embed-text.
EOF
}

MCP=http://127.0.0.1:18085/mcp
# Transport cap for one MCP call: Ollama's read timeout is 5 s, the store's 3 s.
MCP_MAX_SECONDS=15
INDEXER_WAIT_SECONDS=300
MODEL=nomic-embed-text
DIGEST=0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f
# The one-off mcp-tools configured with another model; removed on every exit.
MISMATCH_CONTAINER=kintsugi-lab-verify-rag-mismatch

parse_args "$@"

cleanup() {
  local status=$?
  docker rm -f "$MISMATCH_CONTAINER" >/dev/null 2>&1 || true
  (exit "$status")
  on_exit
}
trap cleanup EXIT

# Posts JSON-RPC method $1 with params $2 to /mcp (as verify-mcp.sh does). Prints "<http code> <body>" on one line,
# "000 " on a transport failure; never fails.
mcp_call() {
  local out rc=0
  out=$(curl -sS --max-time "$MCP_MAX_SECONDS" -w '\n%{http_code}' -X POST "$MCP" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":$2}" 2>/dev/null) || rc=$?
  (( rc == 0 )) || { echo "000 "; return 0; }
  echo "${out##*$'\n'} ${out%$'\n'*}" | tr -d '\n'
  echo
}

# Calls search_runbooks with the JSON arguments $1; prints the body with the escaping backslashes removed.
search() {
  local code body
  read -r code body <<<"$(mcp_call tools/call "{\"name\":\"search_runbooks\",\"arguments\":$1}")" || true
  [[ "$code" == 200 ]] || { echo "HTTP $code"; return 0; }
  tr -d '\\' <<<"$body"
}

# The hits of a search body, one per line, nearest first: "<service or README>|<section>|<score>".
hits() {
  grep -oE '("service":"[a-z-]+",)?"section":"[^"]*","score":[0-9.]+' |
    sed -E 's/^"service":"([a-z-]+)",/\1|/; s/^"section"/README|"section"/; s/"section":"([^"]*)","score":/\1|/'
}

# Runs SQL $1 in the knowledge database as kintsugi_agent, over the container's Unix socket.
psql_knowledge() { compose exec -T knowledge-db psql -U kintsugi_agent -d knowledge -v ON_ERROR_STOP=1 -Atc "$1" "${@:2}"; }

indexer_state() { docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' "$1" 2>/dev/null || echo "missing"; }

# 1. Start or reuse the lab; compose runs the indexer before mcp-tools.
start_lab

indexer=$(compose ps -a -q knowledge-indexer 2>/dev/null || true)
indexer_done() { [[ "$(indexer_state "$indexer")" == "exited "* ]]; }
if [[ -n "$indexer" ]] && poll "$INDEXER_WAIT_SECONDS" indexer_done && [[ "$(indexer_state "$indexer")" == "exited 0" ]]; then
  pass "knowledge-indexer exited 0"
  info "indexer: $(docker logs "$indexer" 2>&1 | grep -oE '(Indexed with|Index unchanged).*' | tail -n1)"
else
  fail "knowledge-indexer: expected an exit with 0, got '$(indexer_state "${indexer:-none}")'"
  docker logs "${indexer:-none}" 2>&1 | tail -n 20 >&2 || true
  exit 1
fi

# 2. The index, read as the agent's role.
metadata=$(psql_knowledge "select model_name || '|' || model_digest || '|' || dimensions from index_metadata" 2>&1 || true)
if [[ "$metadata" == "$MODEL|$DIGEST|768" ]]; then
  pass "index_metadata: $MODEL, digest ${DIGEST:0:12}…, 768 dimensions"
else
  fail "index_metadata: expected '$MODEL|$DIGEST|768', got '$metadata'"
fi

counts=$(psql_knowledge "select service || '=' || chunks from (select coalesce(metadata->>'service', 'README') as service,
  count(*) as chunks from runbook_chunks group by 1) as per_service order by 1" 2>&1 | tr '\n' ' ' || true)
missing=""
for service in "${SERVICES[@]}"; do
  [[ " $counts" =~ \ $service=[1-9][0-9]*\  ]] || missing+=" $service"
done
if [[ -z "$missing" ]]; then
  pass "runbook_chunks: every service has chunks ($counts)"
else
  fail "runbook_chunks: no chunks for$missing ($counts)"
fi

# 3. Symptom-worded searches through MCP. None copies a heading: each describes what an operator sees.
read -r code body <<<"$(mcp_call initialize \
  '{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-rag","version":"1"}}')" || true
if [[ "$code" != 200 ]]; then
  fail "MCP initialize: expected 200, got $code"
  exit 1
fi

# Expects one of the services in $2 (space-separated) among the top 3 hits of query $1, and no "Authoring rule" hit
# among the top 10.
expect_top3() {
  local body top
  body=$(search "{\"query\":\"$1\",\"limit\":10}")
  if [[ "$body" != *'"outcome":"OK"'* ]]; then
    fail "search_runbooks '$1': expected OK, got ${body:0:300}"
    return
  fi
  top=$(hits <<<"$body" | head -n 3)
  info "'$1' top 3: $(tr '\n' ';' <<<"$top")"
  if grep -qE "^($(tr ' ' '|' <<<"$2"))\|" <<<"$top"; then
    pass "search_runbooks '$1': $2 in the top 3"
  else
    fail "search_runbooks '$1': expected $2 in the top 3"
  fi
  if hits <<<"$body" | grep -q '|Authoring rule|'; then
    fail "search_runbooks '$1': a hit has section 'Authoring rule'"
  else
    pass "search_runbooks '$1': no hit from the authoring rule"
  fi
}

expect_top3 "orders stuck waiting for payment after a timeout" "order-service payment-service"
expect_top3 "reservations are very slow" "inventory-service"
expect_top3 "gateway answering too many requests" "gateway-mock"
expect_top3 "customers get service unavailable when they check out" "order-service payment-service"

body=$(search '{"query":"checkout fails","service":"inventory-service","limit":3}')
services=$(hits <<<"$body" | cut -d'|' -f1 | sort -u | tr '\n' ' ')
if [[ "$body" == *'"outcome":"OK"'* && "$services" == "inventory-service " ]]; then
  pass "search_runbooks with service=inventory-service returns only inventory-service chunks"
else
  fail "search_runbooks with service=inventory-service: got services '$services' ${body:0:200}"
fi

# 4. The roles.
# The read-only session default is turned off first, so only the missing grant can stop the insert.
if denied=$(psql_knowledge "set default_transaction_read_only = off" \
  -c "insert into runbook_chunks (content, metadata, embedding)
      select content, metadata, embedding from runbook_chunks limit 1" 2>&1); then
  fail "knowledge store: kintsugi_agent inserted into runbook_chunks"
elif grep -q 'permission denied for table runbook_chunks' <<<"$denied"; then
  pass "knowledge store: kintsugi_agent cannot insert into runbook_chunks (permission denied)"
else
  fail "knowledge store: the insert as kintsugi_agent failed, but not for lack of permission: $denied"
fi

databases=$(psql_knowledge "select count(*) from pg_database where datname = 'faults'" 2>&1 || true)
if [[ "$databases" == 0 ]]; then
  pass "knowledge store: no faults database"
else
  fail "knowledge store: expected no faults database, got '$databases'"
fi

if denied=$(compose exec -T postgres psql -U kintsugi_agent -d faults -Atc "select 1" 2>&1); then
  fail "lab postgres: kintsugi_agent connected to the faults database"
elif grep -q 'permission denied for database "faults"' <<<"$denied"; then
  pass "lab postgres: kintsugi_agent cannot connect to the faults database (permission denied)"
else
  fail "lab postgres: kintsugi_agent could not connect to faults, but not for lack of permission: $denied"
fi

# 5. A second run skips every unchanged runbook and embeds nothing.
second_rc=0
second=$(compose run --rm --no-deps knowledge-indexer 2>&1) || second_rc=$?
report=$(grep -o 'Index unchanged, every runbook skipped.*' <<<"$second" | tail -n1 || true)
if (( second_rc == 0 )) && [[ "$report" == *"embedded=[]"* && "$report" == *"removed=[]"* \
  && "$report" == *"docs/runbooks/README.md"* && "$report" == *"docs/runbooks/payment-service.md"* ]]; then
  pass "a second indexer run exits 0 and skips every runbook ($report)"
else
  fail "a second indexer run: expected exit 0 with embedded=[] and every runbook skipped, got $second_rc '$report'"
fi

# 6. A one-off mcp-tools configured with another model refuses to search the index.
compose run -d --no-deps --name "$MISMATCH_CONTAINER" -e KINTSUGI_MCP_KNOWLEDGE_MODEL_NAME=mxbai-embed-large \
  mcp-tools >/dev/null 2>&1 || true
mismatch_up() { docker exec "$MISMATCH_CONTAINER" curl -fsS -o /dev/null http://localhost:8085/actuator/health 2>/dev/null; }
if poll 120 mismatch_up; then
  body=$(docker exec "$MISMATCH_CONTAINER" curl -sS --max-time "$MCP_MAX_SECONDS" -X POST http://localhost:8085/mcp \
    -H 'Host: localhost:18085' -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_runbooks","arguments":{"query":"reservations are very slow"}}}' \
    2>&1 | tr -d '\\' || true)
  if [[ "$body" == *'"outcome":"UPSTREAM_ERROR"'* \
    && "$body" == *"index built with model $MODEL, configured mxbai-embed-large"* ]]; then
    pass "mcp-tools configured with mxbai-embed-large refuses: index built with model $MODEL"
  else
    fail "model mismatch: expected UPSTREAM_ERROR naming both models, got ${body:0:300}"
  fi
else
  fail "the one-off mcp-tools with another model did not become healthy within 120s"
fi

info "checks finished in $((SECONDS - started))s"
(( failures == 0 ))
