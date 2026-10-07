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
- RDD: on; consent is a standing grant.

## Tasks

- [ ] **T1 — inventory-service reservation events.** Route: delegated writer.
- [ ] **T2 — payment-service payment events.** Route: delegated writer.
- [ ] **T3 — order-service rejected statuses and order events.** Route: delegated writer.
- [ ] **T4 — gateway-mock structured logs, verify scripts and the F4 evidence check, runbooks and docs.** Route: delegated writer.

## Progress

- 2026-10-07: requirement and design approved by the human; branch created.

## Next step

T1 and T2 via delegated writers. They run one after the other, because only one writer works at a time.
