# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-07 (session 04)

## Milestone

M1 — The lab (Oct 5–25). **The M1 check is met:** F1–F4 are visible in Grafana, and each fault has a passing verify script. The service runbooks are done. **One M1 item is still open:** the fault injector with the true cause recorded (`docs/vision.md:148`). It is required before M2's check (F1–F3 correctly diagnosed), because diagnoses are scored against that recorded truth.

## Last action

`event-recording` is done ([feature document](../odd/tasks/event-recording.md)). It implements the human's requirement that every attempt and every event is recorded.
- **order-service:** rejected orders are stored as `REJECTED_OUT_OF_STOCK` or `REJECTED_INVENTORY_UNAVAILABLE`. An append-only `order_events` table has 8 event types.
- **inventory-service:** append-only `reservation_events`. A rejection is recorded through a savepoint on the same connection.
- **payment-service:** append-only `payment_events`, with the outcomes AUTHORIZED, DECLINED, FAILED, GATEWAY_UNAVAILABLE, ERROR and COMMIT_UNKNOWN.
- **gateway-mock:** one key=value log line per call and per behavior change.
- **Database unreachable:** the event goes to a structured WARN line in Loki. Nothing is dropped silently.
- **F4 stock leak:** it can now be proven from data. `verify-f4.sh` step 8 finds an order that order-service rejected but inventory-service still reserved.
- **Living docs:** `docs/vision.md:33` (F3 symptom), the specs, the runbooks (section "Where the evidence is") and setup all match the code.
- **Tests:** order-service 175, payment-service 86, inventory-service 70, gateway-mock 76. All four verify scripts pass.
- **Status:** every range is reviewed and approved. Nothing is pushed.

## Next action

1. `fault-injector` (closes M1). A REST service that activates a fault through the existing mechanisms (the gateway-mock admin API, and compose environment variables plus a recreate), records the true cause in its own database that the agent cannot read, and restores the lab.
2. M2, in this order: MCP server with read-only tools (Loki logs, Prometheus metrics, `queryDb` on the event tables); RAG on the runbooks with pgvector (ADR-002); the agent loop with `IncidentState` and a JSON report with cited evidence; the Python eval harness for the M2 check.

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

Lab services:
- `POST /orders` and `POST /payments` are not idempotent, so a retry after a timeout can create duplicates. This is a candidate fault.
- gateway-mock: a reset does not shorten in-flight requests.
- The order-service 400 safety net is untested by design.
- No test proves that the `gateway.authorize.inflight` gauge keeps its counter after a GC.
- The status-count gauges have a 100 ms wall-clock test bound, which may be flaky on a loaded host. An unknown status row is untested.
- The 429 WARN line is logged in `HttpPaymentGateway`, before the payment is saved.
- No retry or `Retry-After` handling on a 429; this is out of scope for F2.

Verify scripts (review suggestions):
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
