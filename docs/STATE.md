# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-15 (session 02)

## Milestone

**M2 — First agent: in progress.** Done: the MCP tools server, RAG on the runbooks, and the agent loop through T5c12. Open: the M2 check, "F1–F3 correctly diagnosed with a local model" (`agent-loop` T5c3).

## Last action

- **Backups automated:** `infra/scripts/backup-bundle.sh` writes a full bundle and its sha256 to `C:\Users\andrea.tarsi\kintsugi-backups\`. The session-close protocol in `CLAUDE.md` runs it.
- **T5c12 done** (`3c77f6f`): an `ACT` answer with no tool call gets one retry per phase; out-of-phase `nextTools` entries are dropped; the model-call budget is 15. Review approved.
- **Runs 9 and 10** (`infra/scripts/out/verify-agent-20261015T161014Z/`, `...T173441Z/`): 0/3 and 1/3, both on a slow host. In run 9, Ollama fell from 13-14 to under 5.4 prompt tokens/s. In run 10 it was about 9 prompt and 3 generation tokens/s.
  - Run 10: F2 MATCH, with T5c12 working end to end: two retries, and KNOWLEDGE searched the runbooks.
  - F1 and F3 timed out on the first INVESTIGATE answer, which sits right on the 300 s `ACT` deadline at that speed.
- Branch `feat/agent-loop-04-check`, clean tree, nothing pushed. Every code commit is reviewed. The next review base is `3e6edc2`; later commits are docs only.
- The human is restarting the PC to rule out a degraded host.

## Next action

1. After the restart, check Ollama's speed against run 8 (prompt eval 13-14 tokens/s, generation about 4), then run the eleventh full run (T5c3).
   - If the host is still slow, the human decides: investigate the host (power profile, `.wslconfig`), or raise the `ACT` deadline.
2. Proposed earlier, not approved: a replay harness that replays recorded tool outputs without the lab, to measure several samples per fault quickly.
3. Waiting for the human: add `AGENT_DB_PASSWORD=agent_app` to the protected `infra/.env.example`, and the other lines listed in the 2026-10-08 state:
   - `KINTSUGI_REPO_DIR=`
   - `DOCKER_GID=`
   - `POSTGRES_BOOTSTRAP_PASSWORD=postgres`
   - `LAB_APP_DB_PASSWORD=lab_app`
   - `FAULTS_DB_PASSWORD=faults`
   - `KINTSUGI_AGENT_DB_PASSWORD=kintsugi_agent`
   - `KNOWLEDGE_BOOTSTRAP_PASSWORD=postgres`
   - `KNOWLEDGE_INDEXER_DB_PASSWORD=knowledge_indexer`
   - `KNOWLEDGE_AGENT_DB_PASSWORD=knowledge_agent`
   - `OLLAMA_PROXY_BIND_IP=172.17.0.1`
4. Waiting for the human (needs sudo): `sudo apt-get install --only-upgrade docker-compose-plugin=5.6.0-1~ubuntu.24.04~noble`, then record "host Compose = v5.6.0" in ADR-009 and `docs/setup.md`.
5. Waiting for the human: the "to review" decisions in `odd/tasks/runbook-rag.md` and `odd/tasks/mcp-tools.md`.

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`
- `feat/gateway-mock` → `-01-behavior` → `-02-authorize` → `-03-payment-gateway`
- `feat/lab-observability` → `-01-decisions` → `-02-metrics` → `-03-stack`
- `feat/fault-f2` → `-01-gateway` → `-02-payment-failed` → `-03-payment-gauge` → `-04-order` → `-05-check`
- `feat/fault-f3` → `-01-client-metrics` → `-02-check`
- `feat/fault-f4` → `-01-inventory-data` → `-02-inventory-api` → `-03-order-inventory` → `-04-check`
- `feat/service-runbooks`
- `feat/event-recording` → `-01-inventory` → `-02-payment` → `-03-order` → `-04-gateway-check`
- `feat/fault-injector` → `-01-core` → `-02-api` → `-03-lab-control` → `-04-wiring`
- `feat/mcp-tools` → `-01-observability-tools` → `-02-evidence-tools` → `-03-wiring`
- `feat/runbook-rag` (one branch)
- `feat/agent-loop` → `-01-state` → `-02-tools` → `-03-loop` → `-04-check` (current, clean)

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- No posts, no pushes (decided 2026-10-07). The repository stays local. Backups are full git bundles plus a sha256 checksum, kept outside the laptop (`kintsugi-YYYY-MM-DD-full.bundle`).

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.
- Review per commit. The stop hook's whole-history candidate (base `49d55bd`) is skipped, because it exceeds the reviewer context budget.

## Open questions

- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service still stores an AUTHORIZED payment after order-service has timed out (observed).
- Datasource socket timeout. Without one, a database that stops responding keeps the count refresh busy until TCP gives up. The gauges still show NaN after 10 s. A socket timeout would apply to every query, including F4's slow queries.
- Reconciliation and reservation release: the evidence now exists in the event tables (order/payment and order/reservation mismatches); deciding what to do with it is a future feature.
- Runbooks avoid naming the dashboard ("Lab — Faults", uid `lab-f1`) because of the leakage grep; a neutral dashboard name would allow linking it.

## Follow-ups

agent-loop (details in `odd/tasks/agent-loop.md`):
- The 45 min incident budget does not hold the worst case (every `ACT` and `REPORT` call at its deadline).
- A runbook chunk can escape the summary cap.
- A call that failed transiently is refused as a duplicate on retry.
- `<init>`-like search terms count as placeholders.
- `DefaultBudgetsTest` only adds up constants.
- The recorded `tools-list.json` holds old tool descriptions.
- Log templates mask every number, so `answered=429` and `answered=200` merge into one group (review WARNING on T5c10).

runbook-rag (details in the feature document):
- Retrieval: the gateway runbook has no rate-limit section, so a 429-worded query ranks it 2nd.
- `start_lab`'s `knowledge_only_failure` may read a stale indexer state, and it has no test.
- The separator test uses invisible Unicode literals.
- Token counts use cl100k as an approximation of nomic's tokenizer.
- Any container on this host can reach Ollama through the proxy (accepted, recorded in ADR-002).

mcp-tools (details in the feature document):
- "connection pool busy" is also reported when connection attempts are still running at the 2 s timeout (WARNING).
- The level filter depends on the console log format.
- Non-integer arguments are rejected by Spring AI before validation, so they get no call log.
- Restarts less than one step apart merge.
- The `verify-mcp.sh` probe has no wall-clock bound and its failure branches were not exercised.
- Small doc and test suggestions from the T5 reviews.
- No lab service has a restart policy or a memory limit.


Lab services:
- `POST /orders` and `POST /payments` are not idempotent, so a retry after a timeout can create duplicates. This is a candidate fault.
- gateway-mock: a reset does not shorten in-flight requests.
- The order-service 400 safety net is untested by design.
- No test proves that the `gateway.authorize.inflight` gauge keeps its counter after a GC.
- The status-count gauges have a 100 ms wall-clock test bound, which may be flaky on a loaded host. An unknown status row is untested.
- The 429 WARN line is logged in `HttpPaymentGateway`, before the payment is saved.
- No retry or `Retry-After` handling on a 429; this is out of scope for F2.

Verify scripts (review suggestions):
- The fault-variable list in `lib.sh` duplicates the catalog; `start_lab` bypasses the compose helper; the Prometheus invisibility check is a substring match; a 204 race can follow an injector transport failure.
- `LEAK_WAIT_SECONDS` in `verify-f4.sh` is not derived from `F4_RESERVATION_DELAY`.
- The `lib.sh` caller contract does not mention `trap on_exit EXIT`.
- `expect_paid_order` takes an awkward label fragment.
- The rejection phrase is a literal that is not linked to the panel filter, and it is not escaped.
- The reset-on-failure path has not been observed.

Smoke-test harness (non-blocking):
- The report is skipped on a partial run.
- There is no per-prompt timeout.
- The validity rule does not check the zone.
- The report omits `think=false`, misses 12-hour formats and breaks on backticks.
- ADR-003 omits P7's minimum call count.

Carried over:
- A test for the `demo` profile.
- `DemoRunner` with `HttpConnectTimeoutException`.
- Non-ASCII characters in the Windows console.

## Blockers

None.
