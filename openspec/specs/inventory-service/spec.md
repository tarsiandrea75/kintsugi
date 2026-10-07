# Inventory Service Specification

## Purpose

`lab/inventory-service` holds the available stock per SKU in its own PostgreSQL database and reserves the items of an order. `order-service` calls it before it stores an order. An exhausted database connection pool here is fault F4: reservations fail with 500 because no connection is free. A slow inventory is where fault F8 will show up as timeouts on order-service. Design: [feature document](../../../odd/tasks/fault-f4.md). Stack: [ADR-007](../../../docs/adr/ADR-007-lab-persistence-stack.md). Verified by `./mvnw test` from `lab/inventory-service`.

A stock row has a SKU (at most 64 characters) and an available quantity that is never negative. A reservation has an order id (UUID) and one or more lines, each a SKU and a positive quantity.

## Requirements

### Requirement: Reserve stock atomically

`POST /reservations` with a valid body MUST, in one database transaction, subtract each line's quantity from the available stock of its SKU, and return 201 with `{"orderId": "<order id>", "status": "RESERVED"}`. Each line is a conditional update (`available = available - quantity` only where `available >= quantity`), so two concurrent reservations can never take the same units.

#### Scenario: Stock is reserved
- GIVEN SKU `SKU-1` with available stock of at least 2 and `SKU-2` with at least 1
- WHEN `POST /reservations` is called with lines `SKU-1` × 2 and `SKU-2` × 1
- THEN the response status is 201
- AND the body is the order id with status `RESERVED`
- AND the available stock of `SKU-1` is 2 lower and that of `SKU-2` is 1 lower

### Requirement: Insufficient stock returns 409 and changes nothing

When any line asks for more than the available stock of its SKU, or names a SKU that does not exist, `POST /reservations` MUST return 409 with `{"orderId": "<order id>", "status": "INSUFFICIENT_STOCK"}` and MUST roll back every line of the reservation, including lines that could have been reserved.

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

When a reservation cannot get a database connection before the connection timeout, `POST /reservations` MUST answer 500 within about the connection timeout, not hang and not answer another status, and MUST log one `WARN` line that contains the pool's own timeout message (`Connection is not available, request timed out after …`).

#### Scenario: Pool of one, two concurrent reservations
- GIVEN a pool of 1 connection, a connection timeout of 250 ms and a reservation delay of 1 s
- WHEN two reservations are sent at the same time
- THEN one answers 201
- AND the other answers 500
- AND a `WARN` line names the pool timeout

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

- **Reservations are never released.** Nothing gives stock back when the payment fails or the order is abandoned; there is no release endpoint and no expiry. The seed is large enough that the lab never runs out.
- **No prices.** Stock carries no price or product catalog yet; prices come with fault F6.
- **No idempotency.** Calling `POST /reservations` twice with the same order id reserves the stock twice. Reservations are not stored, so there is no record to deduplicate against.
