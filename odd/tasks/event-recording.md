# Feature: event-recording

Locator: `odd/tasks/event-recording.md` · Engram mirror: `odd/event-recording/tasks` · Branch: `feat/event-recording` (from `feat/service-runbooks` at `6359b31`)

## Objective

Every attempt and every event in the lab is recorded, never just answered and dropped. Each record says what happened, when, to which order, with what outcome and how long it took. This turns every incident into evidence that can be reconstructed, both for humans and for the agent's future read-only `queryDb` tool (`docs/vision.md:73`).

## Problem and why

Today several paths answer and keep nothing:
- order-service answers 409 or 503 on inventory problems and stores nothing;
- payment-service answers 503 when the gateway is unavailable and stores nothing;
- inventory-service stores only the stock decrement, with no record of who reserved what, or of rejected attempts;
- gateway-mock keeps nothing.

The F4 stock leak shows the cost. Inventory commits a reservation for an order that order-service never stored, and nothing links the two. The human's requirement (2026-10-07): "ogni tentativo, ogni evento deve essere registrato" — every attempt, every event must be recorded — "più papisti del papa".

## Scope

- In:
  - order-service:
    - rejected orders are stored, with new terminal statuses;
    - append-only `order_events`.
  - inventory-service:
    - append-only `reservation_events`;
    - every reservation attempt is recorded, with order id and outcome.
  - payment-service:
    - append-only `payment_events`;
    - every attempt is recorded, including gateway unavailable.
  - gateway-mock:
    - one structured INFO log line per `/authorize` request and per admin change (it has no database by design);
    - the log lines reach Loki.
  - Living specs, runbooks and docs.
- Out:
  - reconciliation and releasing stock (they use these records later);
  - idempotency keys;
  - an event bus;
  - the agent's `queryDb` tool;
  - the fault injector's ground-truth table (it stays separate and unreadable by the agent).

## Design (approved by the human on 2026-10-07)

- **Stored records, always.** A request that reaches a service and can reach its database leaves a database record, whatever the outcome.
- **Append-only event tables.** One per service:
  - columns: `id` (UUID), `order_id`, `occurred_at` (`timestamptz`, microseconds), `type`, `outcome`, `detail` (TEXT, for example the exception class and the URL), `duration_ms`;
  - nothing ever updates or deletes them; repositories expose insert and read only;
  - indexed by `order_id` and `occurred_at`.
- **order-service:**
  - New terminal statuses `REJECTED_OUT_OF_STOCK` and `REJECTED_INVENTORY_UNAVAILABLE`, both reachable only from `CREATED`. A Flyway migration widens the status CHECK; the existing migrations are not edited.
  - The flow is: save the order `CREATED` together with the event `ORDER_RECEIVED` → call inventory and record `INVENTORY_RESERVED`, `INVENTORY_REJECTED` or `INVENTORY_UNAVAILABLE` (with the exception and the duration) → set the status → call payment and record `PAYMENT_AUTHORIZED`, `PAYMENT_DECLINED`, `PAYMENT_FAILED` or `PAYMENT_UNAVAILABLE` (with the exception and the duration) → set the status.
  - Each status change and its event are written in one transaction. External calls happen outside transactions.
  - HTTP responses are unchanged: 201 / 409 / 503. The 409 and 503 bodies now carry the stored order id.
  - The `lab_orders` gauge reports the new statuses too.
- **inventory-service:**
  - `reservation_events` records every reservation attempt: `RESERVED` (written in the same transaction as the stock decrement) or `INSUFFICIENT_STOCK` (written in a separate transaction after the rollback). The line items go in `detail` as compact JSON.
  - A database failure, such as pool exhaustion or the database being down, cannot be written to the database. It is recorded as a structured WARN log line with the order id, which reaches Loki. This limitation is stated explicitly.
- **payment-service:**
  - `payment_events` records `AUTHORIZED`, `DECLINED`, `FAILED` and `GATEWAY_UNAVAILABLE` (with the exception and the duration).
  - A gateway-unavailable attempt still answers 503 and stores no `payments` row, but it does store the event.
- **gateway-mock:** a structured INFO log line for each `/authorize` call (order id, configured latency, the answer returned, actual duration) and for each behavior change, which is already logged. No database.
- **Shared rule (written in the specs):** if the database itself is unreachable, the event goes to a structured log line instead. Nothing is silently dropped.

## Acceptance criteria

- Every `POST /orders` that reaches order-service and its database leaves an `orders` row and at least one `order_events` row. Rejected orders keep their terminal status.
- Every `POST /reservations` with a reachable database leaves a `reservation_events` row with the order id. On database failure there is a structured WARN line with the order id.
- Every `POST /payments` leaves a `payment_events` row, including gateway unavailable.
- Every gateway-mock `/authorize` leaves a structured INFO line.
- The event tables are append-only: the code exposes no update or delete, and a test proves it.
- All existing verify scripts still pass (`verify-f1.sh` … `verify-f4.sh`). The F4 run shows the leak as evidence: an `order_events INVENTORY_UNAVAILABLE` row and a `reservation_events RESERVED` row for the same order id.
- `./mvnw test` passes in all four services.

## Delivery

- `auto-chain` with `feature-branch-chain`. Forecast: about 2,000 lines, sliced per service.
- Slices: tracker `feat/event-recording` (doc `e357fa6`); PR 1 `-01-inventory` T1; PR 2 `-02-payment` T2; PR 3 `-03-order` T3; PR 4 `-04-gateway-check` T4.
- RDD: on; consent is a standing grant.

## Tasks

- [x] **T1 — inventory-service reservation events.** Route: delegated writer.
- [x] **T2 — payment-service payment events.** Route: delegated writer.
- [x] **T3 — order-service rejected statuses and order events.** Route: delegated writer.
- [x] **T4 — gateway-mock structured logs, verify scripts and the F4 evidence check, runbooks and docs.** Route: delegated writer.

## Progress

- 2026-10-07: requirement and design approved by the human; branch created.
- 2026-10-07: T1 + T2 done by a delegated writer. RED: compile failures. GREEN (parent re-run, mvn exit 0): inventory-service 66 tests, payment-service 79.
  - Event types: `RESERVATION_ATTEMPT` with outcome `RESERVED` (same transaction as the stock decrement) or `INSUFFICIENT_STOCK` (separate transaction after the rollback); `PAYMENT_ATTEMPT` with outcome `AUTHORIZED`, `DECLINED` or `FAILED` (same transaction as the payment row) or `GATEWAY_UNAVAILABLE` (own transaction, still 503).
  - When the database is unavailable, the event becomes a structured key=value WARN line with `outcome=DATABASE_UNAVAILABLE`. The `No database connection available` prefix is kept, because the scripts and runbooks match on it.
  - Open, added to T3/T4: a non-gateway exception during payment is still not recorded; the payment `Payment gateway unavailable` WARN line has no key=value pairs yet; the runbooks still show the old WARN format.
- 2026-10-07: reviews of T1 (`review-d05f2b2f18e05ed0`) and T2 (`review-9523a64b33994c01`) approved. Their WARNINGs were fixed, together with the open payment points:
  - `INSUFFICIENT_STOCK` is recorded through a savepoint on the same connection (a test proves only 1 connection is borrowed).
  - Event-recording paths now catch broadly and never mask the primary outcome; a failure on a shared transaction rolls back and fails the request, and is logged.
  - A payment-plus-event atomicity test was added.
  - A payment `ERROR` outcome is recorded for unexpected exceptions.
  - The payment gateway-unavailable WARN line uses key=value.
  - The flaky `latest()` tests were fixed.

  Tests: inventory-service 70, payment-service 84.
- 2026-10-07: T3 done.
  - order-service has the `REJECTED_OUT_OF_STOCK` and `REJECTED_INVENTORY_UNAVAILABLE` statuses (V2) and an append-only `order_events` table (V3) with 8 types; each status change and its event commit together.
  - 409/503 bodies carry the `orderId`. 175 tests.
  - Accepted: if storing a rejection fails, the request returns 500, because a shared transaction must fail as a whole.
  - Known limitation: order-service's own database is now needed before inventory is called.
  - T4 must update the runbooks and setup for the changed WARN lines (`order rejected:`, key=value pairs).

  Parent re-run: all three suites green, mvn exit 0.
- 2026-10-07: reviews of the fixes (`review-ff1b88ef94bd4ebe`) and of T3 (`review-f0d4588adc82cca5`) approved. Their WARNING about commit ambiguity is fixed in T4 part A.
- 2026-10-07: T4 done by a delegated writer.
  - A: payment-service records `COMMIT_UNKNOWN` instead of `ERROR` when the commit fails after both rows were written. 86 tests.
  - B: gateway-mock logs one key=value line per `/authorize` and per behavior change (Loki's `| logfmt` parses them). 76 tests.
  - C: scripts, runbooks (new section "Where the evidence is"), setup and the lab-observability spec are aligned with the new log lines and statuses. `verify-f4.sh` step 8 checks the database evidence: every 503 order is `REJECTED_INVENTORY_UNAVAILABLE` with an `INVENTORY_UNAVAILABLE` event, and at least one of them also has a `RESERVED` reservation event. This is the F4 stock leak, now provable from data (example: order `7993d1bf…`, order-service gave up at 3002 ms, inventory reserved at 5024 ms).
  - Real runs: F4 PASS in 140 s, F1 in 59 s, F2 in 62 s, F3 in 70 s. The leakage grep is clean. Parent re-run: payment 86, gateway 76, script syntax OK.
- 2026-10-07: T4 review (4 lenses, `review-5ea9bcb2655dade3`) approved.
  - The WARNING about the `COMMIT_UNKNOWN` classification was answered with a code comment: Spring Data JDBC and JdbcClient execute immediately (no write-behind), so once both writes return, any later failure is the commit.
  - Suggestions applied: the payment runbook no longer overstates the guarantee; `LEAK_WAIT_SECONDS` is a named constant; the empty-array loop is guarded.
  - Parent re-run: payment mvn exit 0; `verify-f4.sh` PASS in 128 s on a fresh stack (the leak was proven for order `cc271329…`).

## Next step

Feature done: every attempt and every event is recorded.
