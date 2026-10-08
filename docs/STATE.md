# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-08 (session 01, autonomous work while the human was away)

## Milestone

**M2 — First agent: in progress.** M1 is complete. The first M2 item, the MCP tools server, is done.

## Last action

`mcp-tools` is done ([feature document](../odd/tasks/mcp-tools.md), [ADR-004](adr/ADR-004-tools-via-mcp.md), [spec](../openspec/specs/mcp-tools/spec.md)).
- **What it is:** an MCP server on Spring AI 2.0.1 with MCP SDK 2.0.0 and protocol 2025-11-25. It uses STATELESS Streamable HTTP on `/mcp`, published only on `127.0.0.1:18085`.
  - Origin validation is on: any request with an `Origin` header gets 403.
  - Host allow-list: `mcp-tools:8085`, `localhost:18085`, `127.0.0.1:18085`; any other Host gets 421.
- **7 read-only tools:**
  - `search_logs`, `count_logs`, `query_metric` and `service_restarts` read Loki and Prometheus;
  - `get_order_trace`, `recent_events` and `status_summary` read the event tables.
  - Every query is a fixed template. Parameters are validated enums with caps, output is capped at 8 KB, log and event text is returned as `untrustedText`, and each call writes one INFO line.
- **`kintsugi_agent` role:**
  - not a superuser; CONNECT on `orders`, `payments` and `inventory` only, never `faults`;
  - SELECT on the six evidence tables only, granted by an event trigger as Flyway creates them;
  - read-only and timeout settings, plus read-only connections from mcp-tools.
- **Checks:**
  - `verify-mcp.sh` calls the tools through MCP under F1 and checks the role isolation;
  - mcp-tools has 140 tests;
  - `verify-mcp.sh` and `verify-f1.sh` … `verify-f4.sh` all pass.
- **Status:** every range is reviewed and approved. Nothing is pushed.
- **Running the lab by hand** needs one `down -v` on an old volume, because the role is new.

## Next action

1. Waiting for the human: review the decisions taken autonomously in `odd/tasks/mcp-tools.md` (marked "to review"), mainly:
   - the design and its STATELESS transport (ADR-004);
   - the event-trigger grant;
   - `latency_avg`/`latency_max` instead of p95;
   - the `get_order_trace` shape;
   - the size exceptions of T3 and T4;
   - the disabled database health check.
2. Waiting for the human (approved, needs sudo): `sudo apt-get install --only-upgrade docker-compose-plugin=5.6.0-1~ubuntu.24.04~noble`, then record "host Compose = v5.6.0" in ADR-009 and `docs/setup.md`.
3. Waiting for the human: add these lines to the protected `infra/.env.example`:
   - `KINTSUGI_REPO_DIR=`
   - `DOCKER_GID=`
   - `POSTGRES_BOOTSTRAP_PASSWORD=postgres`
   - `LAB_APP_DB_PASSWORD=lab_app`
   - `FAULTS_DB_PASSWORD=faults`
   - `KINTSUGI_AGENT_DB_PASSWORD=kintsugi_agent`
4. M2 continues:
   - RAG on `docs/runbooks/` with pgvector (ADR-002);
   - the agent loop with `IncidentState` and a JSON report with cited evidence, calling mcp-tools as an MCP client;
   - the Python eval harness for the M2 check (F1–F3 correctly diagnosed).

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

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- No posts, no pushes (decided 2026-10-07). The repository stays local. Backups are full git bundles plus a sha256 checksum, kept outside the laptop (`kintsugi-YYYY-MM-DD-full.bundle`).

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.

## Open questions

- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service still stores an AUTHORIZED payment after order-service has timed out (observed).
- Datasource socket timeout. Without one, a database that stops responding keeps the count refresh busy until TCP gives up. The gauges still show NaN after 10 s. A socket timeout would apply to every query, including F4's slow queries.
- Reconciliation and reservation release: the evidence now exists in the event tables (order/payment and order/reservation mismatches); deciding what to do with it is a future feature.
- Runbooks avoid naming the dashboard ("Lab — Faults", uid `lab-f1`) because of the leakage grep; a neutral dashboard name would allow linking it.

## Follow-ups

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
