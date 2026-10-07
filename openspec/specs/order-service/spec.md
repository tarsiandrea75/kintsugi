# Order Service Specification

## Purpose

`lab/order-service` creates and reads orders persisted in PostgreSQL. It is the entry point of the M1 lab and the first place where injected faults show symptoms. Design: [feature document](../../../odd/tasks/order-service-skeleton.md), for the payment step the [payment-service feature document](../../../odd/tasks/payment-service.md), and for the inventory step the [fault F4 feature document](../../../odd/tasks/fault-f4.md). Stack: [ADR-007](../../../docs/adr/ADR-007-lab-persistence-stack.md). Verified by `./mvnw test` from `lab/order-service`.

An order has an id (UUID), a customer id, a list of items (SKU and quantity), a status, and created and updated timestamps.

## Requirements

### Requirement: Create an order

`POST /orders` with a valid body MUST first reserve the order's items (see "Inventory step"), then store the order in PostgreSQL, take it through the payment step (see "Payment step"), and MUST return 201, a `Location` header pointing to `/orders/{id}`, and the order in its final status. Once the stock is reserved, the response is 201 whatever the payment outcome, including when payment-service is unavailable. When the stock cannot be reserved the response is 409 or 503 and nothing is stored.

#### Scenario: Valid order is created
- GIVEN inventory-service reserves the stock and payment-service authorizes payments
- AND a body with a non-blank customer id and one item with SKU `SKU-1` and quantity 2
- WHEN `POST /orders` is called
- THEN the response status is 201
- AND the `Location` header ends with `/orders/{id}` for the new order id
- AND the body contains the order with status `PAID`, a UUID id, and created and updated timestamps

#### Scenario: Created order is stored
- GIVEN an order created through `POST /orders` while inventory-service reserves the stock and payment-service authorizes payments
- WHEN the orders table in PostgreSQL is queried by its id
- THEN one row exists with the same customer id, items and status `PAID`

### Requirement: Inventory step

Before storing a new order, order-service MUST call `POST /reservations` on inventory-service with body `{"orderId": "<order id>", "items": [{"sku": "<sku>", "quantity": <quantity>}, ...]}`, holding the order only in memory. No database transaction spans the call. Then:

- when inventory-service answers 201 with status `RESERVED`, the order MUST go on to the payment step;
- when it answers 409 with status `INSUFFICIENT_STOCK`, `POST /orders` MUST return 409 with a problem body (`application/problem+json`, `detail` `Insufficient stock`), MUST NOT store the order and MUST NOT call payment-service;
- when the call times out (connect or read), cannot connect, gets any other status (a 5xx included), gets a body without a known status, or gets a status that contradicts the HTTP code, `POST /orders` MUST return 503 with a problem body (`detail` `Inventory unavailable`), MUST NOT store the order and MUST NOT call payment-service, and order-service MUST log one `WARN` line with the order id and the cause. For an I/O failure the cause MUST name the I/O exception and the URL called, for example `HttpTimeoutException calling POST http://inventory-service:8083/reservations`.

This is where fault F4 (inventory pool exhausted, so inventory answers 500) and later fault F8 (inventory slow, so the read timeout fires) show on order-service: as 503s and `WARN` lines, while the root cause is in inventory-service.

The client MUST read its settings from these properties, so the fault injector can change them:

| Property | Default | Meaning |
|----------|---------|---------|
| `kintsugi.inventory.base-url` | `http://localhost:8083` | inventory-service root URL |
| `kintsugi.inventory.connect-timeout` | `1s` | longest wait to open a connection |
| `kintsugi.inventory.read-timeout` | `3s` | longest wait for the response once the request is sent |

#### Scenario: Stock reserved
- GIVEN inventory-service answers 201 `RESERVED` and payment-service answers `AUTHORIZED`
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 201 and the order status is `PAID`
- AND inventory-service received the order id and its items

#### Scenario: Insufficient stock
- GIVEN inventory-service answers 409 `INSUFFICIENT_STOCK`
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 409
- AND no order is stored and payment-service is not called

#### Scenario: Inventory unavailable (fault F4)
- GIVEN inventory-service answers 500, or answers only after the read timeout, or refuses connections, or answers an unknown status
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 503
- AND no order is stored and payment-service is not called
- AND a `WARN` line names the order id and the cause

#### Scenario: Inventory settings are configurable
- GIVEN no `kintsugi.inventory.*` property is set
- WHEN the client settings are read
- THEN they are the defaults in the table above
- AND each one takes the value of its property when the property is set

### Requirement: Payment step

After reserving the stock of a new order, order-service MUST move it to `PAYMENT_PENDING`, save it, and only then call `POST /payments` on payment-service with body `{"orderId": "<order id>"}`. No database transaction spans the call. Then:

- when payment-service answers 2xx with status `AUTHORIZED`, the order MUST move to `PAID` and be saved;
- when it answers 2xx with status `DECLINED` or `FAILED`, the order MUST move to `PAYMENT_FAILED` and be saved. `FAILED` means the gateway rejected the payment with a 429 (fault F2); the order ends as it does after a decline, and the difference stays in payment-service's record;
- when the call times out (connect or read), cannot connect, gets a non-2xx response, or gets a body without a known status, the order MUST stay `PAYMENT_PENDING`, and order-service MUST log a `WARN` line with the order id and the cause. This is the visible symptom of fault F1 (slow gateway). For an I/O failure the cause MUST name the I/O exception and the URL called, for example `ConnectException calling POST http://payment-service:9999/payments`, because the JDK client's `ConnectException` carries no message; this line is the log evidence of fault F3 (wrong payment URL).

The client MUST read its settings from these properties, so the fault injector can change them:

| Property | Default | Meaning |
|----------|---------|---------|
| `kintsugi.payment.base-url` | `http://localhost:8081` | payment-service root URL |
| `kintsugi.payment.connect-timeout` | `1s` | longest wait to open a connection |
| `kintsugi.payment.read-timeout` | `3s` | longest wait for the response once the request is sent |

#### Scenario: Payment authorized
- GIVEN payment-service answers `AUTHORIZED`
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 201 and the order status is `PAID`
- AND payment-service received the order id
- AND the stored order has status `PAID`

#### Scenario: Payment declined
- GIVEN payment-service answers `DECLINED`
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 201 and the order status is `PAYMENT_FAILED`
- AND the stored order has status `PAYMENT_FAILED`

#### Scenario: Payment failed (fault F2)
- GIVEN payment-service answers `FAILED`
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 201 and the order status is `PAYMENT_FAILED`
- AND the stored order has status `PAYMENT_FAILED`

#### Scenario: Payment times out
- GIVEN payment-service answers only after the read timeout
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 201 and the order status is `PAYMENT_PENDING`
- AND the stored order has status `PAYMENT_PENDING`
- AND a `WARN` line names the order id and the cause

#### Scenario: Payment-service unreachable or failing
- GIVEN payment-service refuses connections, or does not complete the TCP handshake within the connect timeout, or answers a non-2xx status such as 500, or answers a status other than `AUTHORIZED`, `DECLINED` or `FAILED`
- WHEN an order is placed
- THEN the order stays `PAYMENT_PENDING`
- AND `POST /orders` still returns 201

#### Scenario: Refused connection names the failure (fault F3)
- GIVEN the payment base URL points at a port where nothing listens
- WHEN an order is placed
- THEN the `WARN` line contains the order id, `ConnectException` and the URL called
- AND it does not end in `null`

#### Scenario: Settings are configurable
- GIVEN no `kintsugi.payment.*` property is set
- WHEN the client settings are read
- THEN they are the defaults in the table above
- AND each one takes the value of its property when the property is set

### Requirement: Reject invalid input

`POST /orders` MUST return 400 and MUST NOT store an order when:

- the customer id or any SKU is blank, where blank means empty or made only of whitespace as defined by `String.isBlank()`, Unicode whitespace such as U+2003 and U+3000 included;
- the customer id or any SKU is longer than 64 characters;
- the item list is empty, missing, or holds more than 100 items;
- any item is null, or has a missing quantity or a quantity of 0 or less;
- the body is not well-formed JSON.

The API and the domain MUST apply the same rules, so input that passes validation is never rejected by the domain. If a domain rule is still violated while creating an order, the response MUST be 400, not 500.

`GET /orders/{id}` MUST return 400 when the id is not a UUID.

#### Scenario: No items
- GIVEN a body with a non-blank customer id and an empty item list
- WHEN `POST /orders` is called
- THEN the response status is 400
- AND no order is stored

#### Scenario: Non-positive quantity
- GIVEN a body with one item whose quantity is 0, and another body with one item whose quantity is -1
- WHEN `POST /orders` is called with each body
- THEN each response status is 400
- AND no order is stored

#### Scenario: Blank customer id
- GIVEN a body with a blank customer id and one valid item
- WHEN `POST /orders` is called
- THEN the response status is 400
- AND no order is stored

#### Scenario: Unicode whitespace id
- GIVEN a body whose customer id is only U+2003, or only U+3000, and another whose only SKU is only U+2003, or only U+3000
- WHEN `POST /orders` is called with each body
- THEN each response status is 400
- AND no order is stored

#### Scenario: Size limits
- GIVEN a body with a 64-character customer id, one with a 64-character SKU, and one with 100 items
- WHEN `POST /orders` is called with each body
- THEN each response status is 201
- AND the same bodies with a 65-character customer id, a 65-character SKU, or 101 items each get 400 and store nothing

#### Scenario: Malformed request shapes
- GIVEN a body with a null quantity, a body whose item list contains `null`, and a body that is not well-formed JSON
- WHEN `POST /orders` is called with each body
- THEN each response status is 400
- AND no order is stored

#### Scenario: Non-UUID id
- GIVEN the path id `not-a-uuid`
- WHEN `GET /orders/not-a-uuid` is called
- THEN the response status is 400

### Requirement: Read an order

`GET /orders/{id}` MUST return 200 with the stored order when the id exists, and MUST return 404 when no order has that id.

#### Scenario: Existing order
- GIVEN an order created through `POST /orders`
- WHEN `GET /orders/{id}` is called with its id
- THEN the response status is 200
- AND the body matches the stored order: id, customer id, items, status and timestamps

#### Scenario: Unknown order
- GIVEN no order with id `00000000-0000-0000-0000-000000000000`
- WHEN `GET /orders/00000000-0000-0000-0000-000000000000` is called
- THEN the response status is 404

### Requirement: Order state transitions

The domain MUST allow only these transitions and MUST throw on any other:

| From | To |
|------|----|
| `CREATED` | `PAYMENT_PENDING` |
| `PAYMENT_PENDING` | `PAID` |
| `PAYMENT_PENDING` | `PAYMENT_FAILED` |

`PAID` and `PAYMENT_FAILED` are terminal. A rejected transition MUST leave the status unchanged.

#### Scenario: Allowed transitions
- GIVEN an order in `CREATED`
- WHEN it moves to `PAYMENT_PENDING` and then to `PAID`
- THEN each transition succeeds and the status is `PAID`

#### Scenario: Payment failure
- GIVEN an order in `PAYMENT_PENDING`
- WHEN it moves to `PAYMENT_FAILED`
- THEN the transition succeeds and the status is `PAYMENT_FAILED`

#### Scenario: Invalid transition is rejected
- GIVEN an order in `PAID`
- WHEN it moves to `PAYMENT_PENDING`
- THEN the domain throws
- AND the status stays `PAID`

#### Scenario: Skipping a state is rejected
- GIVEN an order in `CREATED`
- WHEN it moves directly to `PAID`
- THEN the domain throws
- AND the status stays `CREATED`

### Requirement: Concurrent updates

Saving an order MUST use optimistic locking on its `version`. Saving a copy that was loaded before another save of the same order MUST fail with `OptimisticLockingFailureException` and MUST NOT overwrite the newer state. Saving after a transition MUST persist the new status and the new `updatedAt`.

#### Scenario: Stale copy is rejected
- GIVEN two copies of the same stored order, loaded one after the other
- WHEN the first copy moves to `PAYMENT_PENDING` and is saved, and then the second copy moves to `PAYMENT_PENDING` and is saved
- THEN the second save throws `OptimisticLockingFailureException`
- AND the stored order is the one saved from the first copy

#### Scenario: Updated timestamp is persisted
- GIVEN a stored order in `CREATED`
- WHEN it moves to `PAYMENT_PENDING` and is saved
- THEN the reloaded `updatedAt` equals the new value
- AND it is later than `createdAt`

### Requirement: Orders-by-status gauge

order-service MUST expose `/actuator/prometheus` and MUST export the gauge `lab.orders`, shown in Prometheus as `lab_orders`, with one series per `OrderStatus` and the tag `status` (`CREATED`, `PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`). Each series MUST hold the number of stored orders in that status, read from PostgreSQL at scrape time with one `SELECT status, count(*) FROM orders GROUP BY status` query. A status with no orders MUST report 0. A scrape MUST NOT wait on the database: a gauge read MUST return the current snapshot at once, and a snapshot older than 1 second MUST trigger at most one asynchronous refresh. The refresh MUST store the counts or a failure, timestamped when the query finishes, so all series share one query and a failing database is queried at most once per second. A failed or not yet completed refresh MUST report NaN. A snapshot older than 10 seconds MUST NOT be served: the series MUST report NaN, even while a refresh is still running. The count query MUST have its own statement timeout of 3 seconds, so a query blocked inside the database, for example by a lock, ends with a failure and releases the single-flight guard. The statement timeout does not bound the wait for a pool connection (up to Hikari's `connection-timeout`, 30 seconds by default) or a database or network that stops responding; there the guard stays held until the connection wait or the socket gives up, and the 10-second maximum served age is what keeps stale counts from being served. The statement timeout MUST NOT apply to any other query in the service, because fault F4 injects slow queries on purpose. A value MAY lag the database by about 1 second plus one query. Orders piling up in `PAYMENT_PENDING` is how fault F1 shows in the metrics.

#### Scenario: Counts per status
- GIVEN 3 stored orders in `PAID` and 2 in `PAYMENT_PENDING`
- WHEN the gauges are read
- THEN `lab_orders{status="PAID"}` is 3 and `lab_orders{status="PAYMENT_PENDING"}` is 2
- AND `lab_orders{status="CREATED"}` and `lab_orders{status="PAYMENT_FAILED"}` are 0

#### Scenario: One query per scrape
- GIVEN the four `lab.orders` series
- WHEN all four are read within 1 second
- THEN at most one refresh runs and the database is queried once
- AND a read more than 1 second after the refresh finished starts one new query

#### Scenario: Database failure gives NaN without a slow scrape
- GIVEN the count query fails or hangs, as under fault F4
- WHEN the gauges are read
- THEN every read returns at once, without waiting for the query
- AND once the failed refresh completes, every series is NaN
- AND the failing query runs at most once per second
- AND the counts come back after the next successful refresh

#### Scenario: A hung refresh turns the gauge NaN after the maximum served age
- GIVEN a successful snapshot and a later refresh that hangs after it has acquired a connection
- WHEN the gauges are read while the snapshot is at most 10 seconds old
- THEN they return the previous counts and no second refresh starts
- AND once the snapshot is more than 10 seconds old, every series is NaN while the refresh is still running

#### Scenario: A hung count query times out and the gauge recovers
- GIVEN the `orders` table is locked by another connection, so the count query blocks
- WHEN a refresh runs
- THEN the query is cancelled after its statement timeout and the series are NaN
- AND once the lock is released, the next read starts a new refresh, and after it completes the series report counts again

#### Scenario: Value lags by up to about one refresh
- GIVEN a snapshot older than 1 second
- WHEN the gauges are read
- THEN they return the previous counts and start one refresh
- AND a read after that refresh completes returns the new counts

#### Scenario: Scrape endpoint
- GIVEN order-service running against PostgreSQL 18.6 with one stored order in `CREATED`
- WHEN `GET /actuator/prometheus` is called until the first refresh has completed
- THEN the response status is 200
- AND the body contains `lab_orders{status="CREATED"}`, `lab_orders{status="PAID"} 0.0` and `http_server_requests_seconds_count`

### Requirement: Client metrics

order-service MUST record every call to payment-service and to inventory-service as the Micrometer observation `http.client.requests`, shown in Prometheus as `http_client_requests_seconds_count`, `_sum` and `_max` (plus `http_client_requests_active_seconds_*` while calls run). The series MUST carry these tags:

| Tag | Value |
|-----|-------|
| `client_name` | always `payment-service` or `inventory-service`, whatever host the base URL names |
| `method` | `POST` |
| `uri` | `/payments` or `/reservations` |
| `status` | the HTTP status code, or `CLIENT_ERROR` when there is no response |
| `outcome` | `SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR` and so on, or `UNKNOWN` when there is no response |
| `exception` | `none`, or the simple class name of the failure; for an I/O failure, the I/O exception inside Spring's `ResourceAccessException` |
| `error` | added by Micrometer: `none`, or the simple class name of the exception thrown, so `ResourceAccessException` for every I/O failure |

The `exception` tag MUST name the I/O exception rather than `ResourceAccessException`, which is Spring's default for every I/O failure: that is how a refused connection (fault F3, `ConnectException`) differs from a read timeout (fault F1, `HttpTimeoutException`) in the metrics.

#### Scenario: Successful call
- GIVEN payment-service answers 201 `AUTHORIZED`
- WHEN an order is placed
- THEN one call is recorded with `client_name="payment-service"`, `uri="/payments"`, `status="201"`, `outcome="SUCCESS"` and `exception="none"`

#### Scenario: Server error
- GIVEN payment-service answers 503
- WHEN an order is placed
- THEN one call is recorded with `status="503"` and `outcome="SERVER_ERROR"`

#### Scenario: Refused connection (fault F3)
- GIVEN the payment base URL points at a port where nothing listens
- WHEN an order is placed
- THEN one call is recorded with `status="CLIENT_ERROR"`, `outcome="UNKNOWN"` and `exception="ConnectException"`

#### Scenario: Read timeout (fault F1)
- GIVEN payment-service answers only after the read timeout
- WHEN an order is placed
- THEN one call is recorded with `status="CLIENT_ERROR"`, `outcome="UNKNOWN"` and `exception="HttpTimeoutException"`

#### Scenario: Inventory calls
- GIVEN inventory-service answers 201 `RESERVED`, then 409 `INSUFFICIENT_STOCK`, then 500 (fault F4), then only after the read timeout, then refuses the connection
- WHEN an order is placed each time
- THEN each call is recorded with `client_name="inventory-service"` and `uri="/reservations"`
- AND the tags are, in turn, `status="201"` and `outcome="SUCCESS"`; `status="409"` and `outcome="CLIENT_ERROR"`; `status="500"` and `outcome="SERVER_ERROR"`; `exception="HttpTimeoutException"`; and `exception="ConnectException"`

### Requirement: Schema and persistence tests

The database schema MUST be created by a Flyway migration. Customer ids and SKUs MUST be stored in `VARCHAR(64)` columns. Persistence and end-to-end tests MUST run against a real PostgreSQL 18.6 started by Testcontainers (`postgres:18.6` with `@ServiceConnection`), not an in-memory database. `./mvnw test` from `lab/order-service` MUST pass.

#### Scenario: Flyway creates the schema
- GIVEN an empty PostgreSQL 18.6 database started by Testcontainers
- WHEN the application context starts
- THEN Flyway applies the migrations
- AND the orders schema exists

#### Scenario: Test run
- GIVEN Docker is running
- WHEN `./mvnw test` runs in `lab/order-service`
- THEN the build succeeds
- AND the persistence tests ran against the `postgres:18.6` container

## Known limitations

- **A reservation is never released.** If the payment is declined or fails, or saving the order fails after the stock was reserved, the stock stays reserved. A read timeout on the inventory call leaves it unknown whether the stock was reserved, and the order is refused anyway. inventory-service has no release endpoint yet (see its spec).

- **A read timeout leaves the payment outcome unknown.** The request reached payment-service, which may already have stored a payment, but the order stays `PAYMENT_PENDING`.
- **Saving the outcome can fail after payment-service has decided.** If the save after the payment call fails (a database error, or an optimistic lock failure), `POST /orders` returns 500 and the order stays `PAYMENT_PENDING`, although payment-service has stored an `AUTHORIZED`, `DECLINED` or `FAILED` payment.
- **There is no reconciliation yet.** Nothing brings a `PAYMENT_PENDING` order in line with payment-service later. Reconciliation is a future feature, planned together with the fault injector, which may inject this inconsistency on purpose.
