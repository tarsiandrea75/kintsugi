# Inventory Service Specification

## Purpose

`lab/inventory-service` holds the available stock per SKU in its own PostgreSQL database and reserves the items of an order. `order-service` calls it before it stores an order. An exhausted database connection pool here is fault F4: reservations fail with 500 because no connection is free. A slow inventory is where fault F8 will show up as timeouts on order-service. Design: [feature document](../../../odd/tasks/fault-f4.md); event recording: [feature document](../../../odd/tasks/event-recording.md). Stack: [ADR-007](../../../docs/adr/ADR-007-lab-persistence-stack.md). Verified by `./mvnw test` from `lab/inventory-service`.

A stock row has a SKU (at most 64 characters) and an available quantity that is never negative. A reservation has an order id (UUID) and one or more lines, each a SKU and a positive quantity.

## Requirements

### Requirement: Reserve stock atomically

`POST /reservations` with a valid body MUST, in one database transaction, subtract each line's quantity from the available stock of its SKU, and return 201 with `{"orderId": "<order id>", "status": "RESERVED"}`. Each SKU is a conditional update (`available = available - quantity` only where `available >= quantity`), so two concurrent reservations can never take the same units.

Lines for the same SKU add up: they are merged into one quantity per SKU before any update. The merged SKUs MUST then be applied in ascending SKU order, whatever order the request lists them in. Every reservation therefore locks its stock rows in the same order, so two concurrent reservations that name the same SKUs in different orders MUST both complete and never fail with a database deadlock.

#### Scenario: Stock is reserved
- GIVEN SKU `SKU-1` with available stock of at least 2 and `SKU-2` with at least 1
- WHEN `POST /reservations` is called with lines `SKU-1` × 2 and `SKU-2` × 1
- THEN the response status is 201
- AND the body is the order id with status `RESERVED`
- AND the available stock of `SKU-1` is 2 lower and that of `SKU-2` is 1 lower

#### Scenario: Repeated SKU lines add up
- GIVEN SKU A with available stock 3
- WHEN a reservation asks for A × 2 and A × 2
- THEN the result is `INSUFFICIENT_STOCK`, because together the lines ask for 4
- AND the available stock of A is still 3

#### Scenario: Opposite SKU orders do not deadlock
- GIVEN 40 SKUs with enough stock
- WHEN two reservations of those SKUs, one listing them in ascending and one in descending order, run at the same time, 20 times over
- THEN every reservation is `RESERVED`
- AND no reservation fails with a deadlock

### Requirement: Insufficient stock returns 409 and changes nothing

When any SKU's merged quantity is more than its available stock, or a line names a SKU that does not exist, `POST /reservations` MUST return 409 with `{"orderId": "<order id>", "status": "INSUFFICIENT_STOCK"}` and MUST roll back every line of the reservation, including lines that could have been reserved.

#### Scenario: Second line is short
- GIVEN SKU A with available stock 5 and SKU B with available stock 1
- WHEN a reservation asks for A × 2 and B × 3
- THEN the result is `INSUFFICIENT_STOCK` and the response status is 409
- AND the available stock of A is still 5 and that of B is still 1

#### Scenario: Unknown SKU
- GIVEN no stock row for SKU `NO-SUCH-SKU`
- WHEN a reservation asks for `SKU-1` × 1 and `NO-SUCH-SKU` × 1
- THEN the response status is 409
- AND the available stock of `SKU-1` is unchanged

### Requirement: Every reservation attempt is recorded

Every `POST /reservations` with a valid body that can reach the database MUST leave exactly one row in the append-only table `reservation_events`, whatever the outcome:

| Column | Value |
|--------|-------|
| `id` | a new UUID |
| `order_id` | the order id of the request |
| `occurred_at` | when the attempt was recorded (`timestamptz`, microseconds) |
| `type` | `RESERVATION_ATTEMPT`, the only type; a CHECK constraint rejects any other |
| `outcome` | `RESERVED` or `INSUFFICIENT_STOCK` |
| `detail` | the requested lines, as sent, in compact JSON, for example `[{"sku":"SKU-1","quantity":2}]` |
| `duration_ms` | how long the reservation took |

A `RESERVED` event MUST be inserted in the same transaction as the stock decrement, so a reservation and its event commit or roll back together. An `INSUFFICIENT_STOCK` event MUST be inserted in a new transaction after the reservation's rollback, so the rejected attempt is kept while the stock stays unchanged. The table is indexed by `order_id` and `occurred_at`. It is append-only: the code exposes insert and read only (by order id, and the latest N), with no update or delete, and a test checks it. A request rejected with 400 never reaches the reservation and leaves no event.

#### Scenario: Reserved attempt is recorded
- GIVEN a SKU with enough stock
- WHEN a reservation for it is made
- THEN the result is `RESERVED`
- AND exactly one `reservation_events` row exists for the order id, with type `RESERVATION_ATTEMPT`, outcome `RESERVED` and the lines in `detail`

#### Scenario: Rejected attempt is recorded and changes no stock
- GIVEN SKU A with available stock 5 and SKU B with available stock 1
- WHEN a reservation asks for A × 2 and B × 3
- THEN the result is `INSUFFICIENT_STOCK`
- AND the available stock of A is still 5 and that of B is still 1
- AND exactly one `reservation_events` row exists for the order id, with outcome `INSUFFICIENT_STOCK`

#### Scenario: Event table is append-only
- GIVEN the event repository
- WHEN its public methods are listed
- THEN they are insert, read by order id and read latest, and none updates or deletes

### Requirement: Database unavailable is logged, never dropped

This rule is shared by every lab service that records events: if the database itself is unreachable, the event goes to a structured log line instead. Nothing is silently dropped.

When a reservation cannot get a database connection (the pool is exhausted, fault F4, or the database is down), the attempt cannot be stored. The service MUST then log exactly one `WARN` line with `key=value` pairs, readable by Loki's `logfmt` parser:

```
No database connection available: event=RESERVATION_ATTEMPT order=<order id> outcome=DATABASE_UNAVAILABLE duration_ms=<ms> detail="<lines as JSON>" cause="<pool message>"
```

The order id is known because the body was parsed before the database was touched. When the reservation was rejected but its `INSUFFICIENT_STOCK` event cannot be stored, the response is still 409 and the service MUST log one `WARN` line `Reservation event not stored: event=RESERVATION_ATTEMPT order=<order id> outcome=INSUFFICIENT_STOCK ...`. Quoted values escape `\` and `"`.

This is a stated limitation: an attempt made while the database is unavailable exists only in the logs, not in `reservation_events`.

#### Scenario: No connection for a reservation
- GIVEN no database connection can be opened
- WHEN a reservation is made
- THEN no event row is written
- AND one `WARN` line contains `event=RESERVATION_ATTEMPT`, `order=<order id>` and `outcome=DATABASE_UNAVAILABLE`

### Requirement: Reject invalid input

`POST /reservations` MUST return 400 and MUST NOT change any stock when the body is not well-formed JSON, the `orderId` is missing or not a UUID, `items` is missing, empty or longer than 100 lines, or a line has a missing, blank or longer than 64 characters `sku`, or a missing, zero or negative `quantity`.

#### Scenario: Invalid body
- GIVEN a body that breaks one of the rules above
- WHEN `POST /reservations` is called
- THEN the response status is 400
- AND no stock changes

#### Scenario: Malformed JSON
- GIVEN a body that is not well-formed JSON
- WHEN `POST /reservations` is called
- THEN the response status is 400

### Requirement: Seeded SKUs

The Flyway migrations MUST create the `stock` table, with a check that `available` is never negative, and seed the SKUs `SKU-1` to `SKU-5`, each with 1,000,000 units, so tests and verify scripts have stock without setup.

#### Scenario: Seed exists
- GIVEN an empty PostgreSQL 18.6 database
- WHEN the application starts and Flyway applies the migrations
- THEN `SKU-1`, `SKU-2`, `SKU-3`, `SKU-4` and `SKU-5` exist, each with available stock 1,000,000

#### Scenario: Stock never goes negative
- GIVEN the migrated schema
- WHEN a row is written with a negative available quantity
- THEN the database rejects it

### Requirement: Read stock

`GET /stock/{sku}` MUST return 200 with `{"sku": "<sku>", "available": <quantity>}` when the SKU exists, and 404 when it does not.

#### Scenario: Existing SKU
- GIVEN the seeded SKU `SKU-3`
- WHEN `GET /stock/SKU-3` is called
- THEN the response status is 200 and the body contains the SKU and its available stock

#### Scenario: Unknown SKU
- GIVEN no stock row for `NO-SUCH-SKU`
- WHEN `GET /stock/NO-SUCH-SKU` is called
- THEN the response status is 404

### Requirement: Lab-only reservation delay

The property `kintsugi.inventory.reservation-delay` (a duration, default `0s`) is a **lab-only fault hook**: it exists so the fault injector can make the reservation query slow, and a real service would not have it. When it is above zero, the reservation MUST run `SELECT pg_sleep(<delay in seconds>)` inside its transaction, before the stock updates, so a pooled connection is held for the whole delay.

#### Scenario: No delay by default
- GIVEN `kintsugi.inventory.reservation-delay` is not set
- WHEN the service settings are read
- THEN the delay is zero and no `pg_sleep` runs

#### Scenario: Delay holds the connection
- GIVEN `kintsugi.inventory.reservation-delay=300ms`
- WHEN a reservation is made
- THEN it takes at least 300 ms
- AND the stock is still reserved

### Requirement: Explicit pool settings

The HikariCP pool MUST be configured explicitly in `application.properties`, so fault F4 can override it through environment variables:

| Property | Default | Meaning |
|----------|---------|---------|
| `spring.datasource.hikari.maximum-pool-size` | `10` | most database connections open at once |
| `spring.datasource.hikari.connection-timeout` | `2000` (ms) | longest wait for a free connection; short on purpose, so exhaustion becomes an error quickly |

F4 sets the pool size to 2 and the reservation delay to 5 s: a third concurrent reservation waits 2 s for a connection and fails.

### Requirement: Exhausted pool returns 500

When a reservation cannot get a database connection before the connection timeout, `POST /reservations` MUST answer 500 within about the connection timeout, not hang and not answer another status, and MUST log one `WARN` line that contains the pool's own timeout message (`Connection is not available, request timed out after …`) as `cause`, and the order id (see "Database unavailable is logged, never dropped"). A stock read with no connection also answers 500 and logs one `WARN` line `No database connection available: path=<path> outcome=DATABASE_UNAVAILABLE cause="<message>"`.

#### Scenario: Pool of one, two concurrent reservations
- GIVEN a pool of 1 connection, a connection timeout of 250 ms and a reservation delay of 1 s
- WHEN two reservations are sent at the same time
- THEN one answers 201
- AND the other answers 500
- AND one `WARN` line names the pool timeout and the order id of the rejected request

### Requirement: Prometheus metrics

The service MUST expose `/actuator/prometheus` and `/actuator/health`. The scrape MUST include the HikariCP pool metrics (`hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`) and the HTTP server metrics (`http_server_requests_seconds_*`). These are F4's evidence: pending connections, timeouts and 500s on `/reservations`.

#### Scenario: Scrape shows pool and HTTP metrics
- GIVEN a running service that has answered one request
- WHEN `/actuator/prometheus` is scraped
- THEN it contains `hikaricp_connections` and `http_server_requests`

### Requirement: Schema and persistence tests

The service listens on port 8083. Persistence and end-to-end tests MUST run against a real PostgreSQL 18.6 started by Testcontainers (`postgres:18.6` with `@ServiceConnection`). `./mvnw test` from `lab/inventory-service` MUST pass.

#### Scenario: Test run
- GIVEN Docker is running
- WHEN `./mvnw test` runs in `lab/inventory-service`
- THEN the build succeeds
- AND the persistence tests ran against the `postgres:18.6` container

## Known limitations

- **Reservations are never released.** Nothing gives stock back when the payment fails or the order is abandoned; there is no release endpoint and no expiry. The seed is large enough that the lab never runs out. Every lab order consumes stock, and under F4 a reservation that commits after order-service has timed out also leaks its units; with 1,000,000 units per SKU this is negligible, and a release mechanism belongs with the future reconciliation feature.
- **No prices.** Stock carries no price or product catalog yet; prices come with fault F6.
- **No idempotency.** Calling `POST /reservations` twice with the same order id reserves the stock twice. Each attempt leaves its own `reservation_events` row, so the duplicate is visible, but nothing deduplicates against it.
- **Attempts during a database outage are only logged.** They cannot be written to `reservation_events`; the structured `WARN` line is their only record.
