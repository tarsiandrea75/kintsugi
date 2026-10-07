# Feature: fault-f4

Locator: `odd/tasks/fault-f4.md` · Engram mirror: `odd/fault-f4/tasks` · Branch: `feat/fault-f4` (from `feat/fault-f3-02-check` at `25aa1b1`)

## Objective

Add `inventory-service`, the third lab microservice (`docs/vision.md:22`). Wire order → inventory, and make fault F4 injectable and visible in Grafana. F4 is "DB pool exhausted": a pool of 2 plus a slow query, with the symptom "500 errors on inventory" (`docs/vision.md:34`). It is the last fault of the M1 check (`docs/vision.md:152`). The order → inventory call also prepares F8 (inventory slow → timeouts on order, `docs/vision.md:38`).

## Problem and why

The lab has no inventory, so F4 and F8 cannot exist. F4 is the first fault whose root cause is inside a service's own resources (the connection pool) rather than in a dependency. The agent must learn to read pool metrics.

## Scope

- In:
  - `lab/inventory-service` with its own database and REST API;
  - the reserve-stock call from order-service;
  - F4 injection through compose environment variables;
  - pool and error metrics;
  - a dashboard panel;
  - `verify-f4.sh`;
  - living specs and an ADR only if a new library appears;
  - docs.
- Out:
  - prices and amounts (F6);
  - releasing reservations on payment failure (a known limitation, written into the spec);
  - F8 injection (it comes later, but the timeouts are configurable now);
  - the fault injector.

## Design (decided autonomously on 2026-10-07 while the user was away; **to be reviewed by the human**)

- **inventory-service:**
  - The same stack as the other lab services (ADR-007): Spring Data JDBC, Flyway, PostgreSQL 18.6 with its own `inventory` database, Testcontainers, the Prometheus registry and the Dockerfile pattern. Package `dev.kintsugi.lab.inventory`. Port 8083, published on host port `127.0.0.1:18083`.
  - Schema: a `stock(sku VARCHAR(64) PRIMARY KEY, available INT CHECK (available >= 0))` table, seeded by a Flyway migration with a few SKUs (`SKU-1`…`SKU-5`, each with enough stock for tests and verify runs, for example 1,000,000).
  - API:
    - `POST /reservations {orderId, items:[{sku, quantity}]}` reserves all lines atomically in one transaction (`UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?`). It returns 201 `{orderId, status:"RESERVED"}`, or 409 `{status:"INSUFFICIENT_STOCK"}` when any line cannot be reserved, in which case nothing changes. An unknown SKU is an insufficient line.
    - `GET /stock/{sku}`.
    - Validation errors return 400.
  - A slow query exists only to support the fault. The property `kintsugi.inventory.reservation-delay` (default 0) runs `SELECT pg_sleep(?)` inside the reservation transaction, while it holds a pooled connection. It is a lab-only fault hook and is documented as such.
  - The pool is explicit: `spring.datasource.hikari.maximum-pool-size` (default 10) and `connection-timeout` (default 2 s, short on purpose, so exhaustion becomes a 500 quickly). An exhausted pool surfaces as a 500 from `POST /reservations`. This is Spring's default for `CannotGetJdbcConnectionException`; verify it, and map it if needed.
- **order-service:**
  - `OrderService` calls inventory **before** creating the order record: create (in memory) → reserve → save → pay.
  - Responses:
    - inventory 409 → `POST /orders` returns 409 and stores nothing;
    - inventory unavailable (5xx, timeout, connection error) → `POST /orders` returns 503 and stores nothing, with a WARN line naming the cause.
  - A new `InventoryClient` mirrors `PaymentClient`: `RestClient`, connect and read timeouts from `kintsugi.inventory.*` (base URL `http://localhost:8083`, connect 1 s, read 3 s), and client metrics with `client_name="inventory-service"` and the inner I/O exception tag.
  - Why this order (reserve before save): stock exists before the order is accepted, and the symptom of an inventory failure is an error on order. That is the F8 shape: "timeouts appear on order, root cause is inventory".
- **F4 injection:** compose variables `INVENTORY_POOL_SIZE` (default 10) and `INVENTORY_RESERVATION_DELAY` (default `0s`). F4 sets `INVENTORY_POOL_SIZE=2` and `INVENTORY_RESERVATION_DELAY=5s`, then recreates inventory-service. This is the same mechanism as F3.
- **Evidence:**
  - Hikari metrics on inventory: `hikaricp_connections_pending` > 0, `hikaricp_connections_active` = 2, and `hikaricp_connections_timeout_total` rising;
  - inventory `http_server_requests_seconds_count{status="500",uri="/reservations"}` rising;
  - order-service 503s with client `status="500"` from inventory;
  - order-service WARN lines.
- **Dashboard:** two new panels, "inventory DB pool" (active, idle and pending connections, and timeouts) and "inventory 5xx rate".
- **Check:** `infra/scripts/verify-f4.sh`. It:
  1. places a healthy order and checks it is `PAID`;
  2. injects F4 by recreating inventory-service;
  3. fires about 6 concurrent orders, enough to exhaust a pool of 2 with a 5 s query and a 2 s connection timeout;
  4. asserts that at least one returns 503;
  5. asserts in Prometheus that the inventory 500 count rose and that Hikari pending reached at least 1 or timeouts rose;
  6. asserts the order-service WARN line in Loki;
  7. restores inventory-service, always, through a trap;
  8. places a final order and checks it is `PAID`.

## Acceptance criteria

- inventory-service: reserving is atomic, 409 leaves stock unchanged, 400 on invalid input, and the seeded SKUs exist. Tests run with Testcontainers.
- order-service: an order with stock is reserved and paid (`PAID`); a 409 from inventory gives 409 with nothing stored; an unavailable inventory gives 503 with nothing stored and a WARN line.
- Compose runs inventory-service healthy, and `verify-f1.sh`, `verify-f2.sh` and `verify-f3.sh` still pass.
- `verify-f4.sh` passes.
- `./mvnw test` passes in inventory-service and order-service.

## Delivery

- `auto-chain` with `feature-branch-chain`. Forecast: about 1,500 lines, sliced per task.
- The generated skeleton goes in its own commit.
- Slices: tracker `feat/fault-f4` (doc `7657927`, skeleton `c4fb992`); PR 1 `-01-inventory-data`: spec, domain, persistence, migrations, pool settings; PR 2 `-02-inventory-api`: application service, fault hook, API, exhaustion handler; then T3, then T4.
- RDD: on; consent is a standing grant. Reviews run per slice.

## Tasks

- [x] **T1 — inventory-service skeleton.** Initializr skeleton with the same ids as payment-service, the image pinned, `TestcontainersConfiguration` made public, and the pom tidied. Route: delegated writer.
- [x] **T2 — inventory-service domain, persistence and API.** Spec first, tests first, the seed migration, atomic reservation, the delay hook and explicit pool settings. Route: delegated writer.
- [ ] **T3 — order-service inventory step.** `InventoryClient` with metrics, `OrderService` reserve-before-save, 409 and 503 handling, and the spec. Route: delegated writer.
- [ ] **T4 — Compose, dashboard, verify-f4 and docs.** The inventory service and its database in compose; the F4 variables; the panels; `verify-f4.sh`; all four verify scripts run for real. Route: delegated writer.

## Checks

- `./mvnw test` in `lab/inventory-service` and `lab/order-service`
- `infra/scripts/verify-f1.sh` … `verify-f4.sh`

## Progress

- 2026-10-07: design decided autonomously (the user was away, autonomy authorized until the quota ends); branch created.
- 2026-10-07: T1 done: the Initializr skeleton with the same ids as payment-service, tidied the same way (image pinned and public test config, pom description, Prometheus registry and exposure, port 8083, Dockerfile). mvn exit 0, 1 test against PostgreSQL 18.6. Generated scaffolding has no meaningful RED.
- 2026-10-07: T2 done by the delegated writer (tests first).
  - RED: compile failure, then 1 failure and 2 errors without an exhaustion handler.
  - Finding: Hikari exhaustion surfaces as `CannotCreateTransactionException`, because the transaction cannot start. Spring already returns 500 for it, but logs an ERROR stack trace. A handler scoped to the controller keeps the 500 and logs one readable WARN line naming the pool timeout. Kept, and to review.
  - GREEN: mvn exit 0, 52 tests, two runs (parent re-run). The exhaustion test (pool 1, timeout 250 ms, delay 1 s) takes about 3.5 s.
  - Duplicate SKU lines in one reservation add up (written in the spec).

## Next step

T3 (order-service inventory step) via a delegated writer.
