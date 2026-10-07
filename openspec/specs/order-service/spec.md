# Order Service Specification

## Purpose

`lab/order-service` creates and reads orders persisted in PostgreSQL. It is the entry point of the M1 lab and the first place where injected faults show symptoms. Design: [feature document](../../../odd/tasks/order-service-skeleton.md), for the payment step the [payment-service feature document](../../../odd/tasks/payment-service.md), for the inventory step the [fault F4 feature document](../../../odd/tasks/fault-f4.md), and for the order events the [event-recording feature document](../../../odd/tasks/event-recording.md). Stack: [ADR-007](../../../docs/adr/ADR-007-lab-persistence-stack.md). Verified by `./mvnw test` from `lab/order-service`.

An order has an id (UUID), a customer id, a list of items (SKU and quantity), a status, and created and updated timestamps.

## Requirements

### Requirement: Create an order

`POST /orders` with a valid body MUST first store the order as `CREATED` (see "Every order attempt is recorded"), then reserve its items (see "Inventory step"), take it through the payment step (see "Payment step"), and MUST return 201, a `Location` header pointing to `/orders/{id}`, and the order in its final status. Once the stock is reserved, the response is 201 whatever the payment outcome, including when payment-service is unavailable. When the stock cannot be reserved the response is 409 or 503, the order is stored in a terminal `REJECTED_*` status, and the problem body carries its id as `orderId`.

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

After storing the new order as `CREATED`, order-service MUST call `POST /reservations` on inventory-service with body `{"orderId": "<order id>", "items": [{"sku": "<sku>", "quantity": <quantity>}, ...]}`. No database transaction spans the call. Then, writing the new status and its event in one transaction:

- when inventory-service answers 201 with status `RESERVED`, the order MUST move to `PAYMENT_PENDING` with an `INVENTORY_RESERVED` event and go on to the payment step;
- when it answers 409 with status `INSUFFICIENT_STOCK`, the order MUST move to `REJECTED_OUT_OF_STOCK` with an `INVENTORY_REJECTED` event, `POST /orders` MUST return 409 with a problem body (`application/problem+json`, `detail` `Insufficient stock`, `orderId` the stored order's id), and MUST NOT call payment-service;
- when the call times out (connect or read), cannot connect, gets any other status (a 5xx included), gets a body without a known status, or gets a status that contradicts the HTTP code, the order MUST move to `REJECTED_INVENTORY_UNAVAILABLE` with an `INVENTORY_UNAVAILABLE` event, `POST /orders` MUST return 503 with a problem body (`detail` `Inventory unavailable`, `orderId` the stored order's id), MUST NOT call payment-service, and order-service MUST log one `WARN` line with `key=value` pairs:

```
Inventory unavailable for order <id>, order rejected: event=INVENTORY_UNAVAILABLE order=<id> outcome=REJECTED_INVENTORY_UNAVAILABLE duration_ms=<ms> url="<url>" exception=<class> cause="<cause>"
```

For an I/O failure the cause MUST name the I/O exception and the URL called, for example `HttpTimeoutException calling POST http://inventory-service:8083/reservations`. The `Inventory unavailable for order` prefix is stable: runbooks search for it.

This is where fault F4 (inventory pool exhausted, so inventory answers 500) and later fault F8 (inventory slow, so the read timeout fires) show on order-service: as 503s, `REJECTED_INVENTORY_UNAVAILABLE` orders and `WARN` lines, while the root cause is in inventory-service.

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
- THEN the response status is 409 and the body's `orderId` names the stored order
- AND the order is stored as `REJECTED_OUT_OF_STOCK` with the events `ORDER_RECEIVED` and `INVENTORY_REJECTED`
- AND payment-service is not called

#### Scenario: Inventory unavailable (fault F4)
- GIVEN inventory-service answers 500, or answers only after the read timeout, or refuses connections, or answers an unknown status
- WHEN `POST /orders` is called with a valid body
- THEN the response status is 503 and the body's `orderId` names the stored order
- AND the order is stored as `REJECTED_INVENTORY_UNAVAILABLE` with the events `ORDER_RECEIVED` and `INVENTORY_UNAVAILABLE`, whose `detail` holds the exception class and the URL and whose `duration_ms` is set
- AND payment-service is not called
- AND a `WARN` line names the order id and the cause

#### Scenario: Inventory settings are configurable
- GIVEN no `kintsugi.inventory.*` property is set
- WHEN the client settings are read
- THEN they are the defaults in the table above
- AND each one takes the value of its property when the property is set

### Requirement: Payment step

After the order is stored as `PAYMENT_PENDING` (see "Inventory step"), order-service MUST call `POST /payments` on payment-service with body `{"orderId": "<order id>"}`. No database transaction spans the call. Then, writing the new status and its event in one transaction:

- when payment-service answers 2xx with status `AUTHORIZED`, the order MUST move to `PAID` with a `PAYMENT_AUTHORIZED` event;
- when it answers 2xx with status `DECLINED` or `FAILED`, the order MUST move to `PAYMENT_FAILED` with a `PAYMENT_DECLINED` or `PAYMENT_FAILED` event. `FAILED` means the gateway rejected the payment with a 429 (fault F2); the order ends as it does after a decline, and the event type and payment-service's record keep the difference;
- when the call times out (connect or read), cannot connect, gets a non-2xx response, or gets a body without a known status, the order MUST stay `PAYMENT_PENDING`, a `PAYMENT_UNAVAILABLE` event MUST be stored in a transaction of its own, and order-service MUST log a `WARN` line with the order id and the cause: `Payment of order <id> unavailable, leaving it in PAYMENT_PENDING: event=PAYMENT_UNAVAILABLE order=<id> outcome=PAYMENT_PENDING duration_ms=<ms> url="<url>" exception=<class> cause="<cause>"`. This is the visible symptom of fault F1 (slow gateway). For an I/O failure the cause MUST name the I/O exception and the URL called, for example `ConnectException calling POST http://payment-service:9999/payments`, because the JDK client's `ConnectException` carries no message; this line is the log evidence of fault F3 (wrong payment URL).

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
- AND its events are `ORDER_RECEIVED`, `INVENTORY_RESERVED` and `PAYMENT_UNAVAILABLE`, the last with the exception class and the URL in `detail` and a `duration_ms` of at least the read timeout
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

### Requirement: Every order attempt is recorded

Every `POST /orders` with a valid body that reaches order-service and its database MUST leave an `orders` row and at least one row in the append-only table `order_events`. The order is first stored as `CREATED` together with an `ORDER_RECEIVED` event, in one transaction, before inventory-service is called. Each later status change is written in one transaction with the event that explains it, so a status never exists without its event. The calls to inventory-service and payment-service run outside any transaction.

| Column | Value |
|--------|-------|
| `id` | a new UUID |
| `order_id` | the order's id |
| `occurred_at` | when the event was recorded (`timestamptz`, microseconds) |
| `type` | `ORDER_RECEIVED`, `INVENTORY_RESERVED`, `INVENTORY_REJECTED`, `INVENTORY_UNAVAILABLE`, `PAYMENT_AUTHORIZED`, `PAYMENT_DECLINED`, `PAYMENT_FAILED` or `PAYMENT_UNAVAILABLE`; a CHECK constraint rejects any other |
| `outcome` | the order's status after the event |
| `detail` | compact JSON: `{"customerId":"<id>","items":[...]}` for `ORDER_RECEIVED`; `{"exception":"<class>","url":"<url called>","message":"<message>"}` for the two `*_UNAVAILABLE` types, where the class is the most specific cause; empty otherwise |
| `duration_ms` | how long the call to inventory-service or payment-service took; empty for `ORDER_RECEIVED` |

The table is indexed by `order_id` and `occurred_at`. It is append-only: the code exposes insert and read only (by order id, and the latest N), with no update or delete, and a test checks it. A request rejected with 400 leaves no order and no event.

A failure to record an event follows the rule shared by every lab service: it is logged as one `WARN` line `Order event not stored: event=<type> order=<id> outcome=<status> duration_ms=<ms> detail="<detail>" record_failure=<exception class> cause="<message>"`, whatever the runtime exception. When the event shares its transaction with a status change, both roll back and the request fails (500). When it changes no status (`PAYMENT_UNAVAILABLE`), the answer is unchanged (201 with `PAYMENT_PENDING`).

#### Scenario: Happy path is recorded in order
- GIVEN inventory-service answers 201 `RESERVED` and payment-service answers `AUTHORIZED`
- WHEN `POST /orders` is called with a valid body
- THEN the order's events are, oldest first, `ORDER_RECEIVED`, `INVENTORY_RESERVED` and `PAYMENT_AUTHORIZED`

#### Scenario: Event shares a failed transaction
- GIVEN storing the `INVENTORY_RESERVED` event fails
- WHEN an order is placed
- THEN the move to `PAYMENT_PENDING` is rolled back, payment-service is not called and the request fails
- AND one `WARN` line contains `Order event not stored`, the order id and `record_failure=<exception class>`

#### Scenario: Unavailable-payment event cannot be stored
- GIVEN payment-service is unavailable and storing the `PAYMENT_UNAVAILABLE` event fails
- WHEN an order is placed
- THEN the order is still returned in `PAYMENT_PENDING`
- AND one `WARN` line contains `Order event not stored` and `event=PAYMENT_UNAVAILABLE`

#### Scenario: Event table is append-only
- GIVEN the event repository
- WHEN its public methods are listed
- THEN they are insert, read by order id and read latest, and none updates or deletes

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
| `CREATED` | `REJECTED_OUT_OF_STOCK` |
| `CREATED` | `REJECTED_INVENTORY_UNAVAILABLE` |
| `PAYMENT_PENDING` | `PAID` |
| `PAYMENT_PENDING` | `PAYMENT_FAILED` |

`PAID`, `PAYMENT_FAILED`, `REJECTED_OUT_OF_STOCK` and `REJECTED_INVENTORY_UNAVAILABLE` are terminal. A rejected transition MUST leave the status unchanged. The two rejected statuses were added by a later Flyway migration that widens the status CHECK; the first migration is not edited.

#### Scenario: Allowed transitions
- GIVEN an order in `CREATED`
- WHEN it moves to `PAYMENT_PENDING` and then to `PAID`
- THEN each transition succeeds and the status is `PAID`

#### Scenario: Payment failure
- GIVEN an order in `PAYMENT_PENDING`
- WHEN it moves to `PAYMENT_FAILED`
- THEN the transition succeeds and the status is `PAYMENT_FAILED`

#### Scenario: Rejection
- GIVEN an order in `CREATED`
- WHEN it moves to `REJECTED_OUT_OF_STOCK` or `REJECTED_INVENTORY_UNAVAILABLE`
- THEN the transition succeeds
- AND every further transition is rejected

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

order-service MUST expose `/actuator/prometheus` and MUST export the gauge `lab.orders`, shown in Prometheus as `lab_orders`, with one series per `OrderStatus` and the tag `status` (`CREATED`, `PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`, `REJECTED_OUT_OF_STOCK`, `REJECTED_INVENTORY_UNAVAILABLE`). Each series MUST hold the number of stored orders in that status, read from PostgreSQL at scrape time with one `SELECT status, count(*) FROM orders GROUP BY status` query. A status with no orders MUST report 0. A scrape MUST NOT wait on the database: a gauge read MUST return the current snapshot at once, and a snapshot older than 1 second MUST trigger at most one asynchronous refresh. The refresh MUST store the counts or a failure, timestamped when the query finishes, so all series share one query and a failing database is queried at most once per second. A failed or not yet completed refresh MUST report NaN. A snapshot older than 10 seconds MUST NOT be served: the series MUST report NaN, even while a refresh is still running. The count query MUST have its own statement timeout of 3 seconds, so a query blocked inside the database, for example by a lock, ends with a failure and releases the single-flight guard. The statement timeout does not bound the wait for a pool connection (up to Hikari's `connection-timeout`, 30 seconds by default) or a database or network that stops responding; there the guard stays held until the connection wait or the socket gives up, and the 10-second maximum served age is what keeps stale counts from being served. The statement timeout MUST NOT apply to any other query in the service, because fault F4 injects slow queries on purpose. A value MAY lag the database by about 1 second plus one query. Orders piling up in `PAYMENT_PENDING` is how fault F1 shows in the metrics.

#### Scenario: Counts per status
- GIVEN 3 stored orders in `PAID` and 2 in `PAYMENT_PENDING`
- WHEN the gauges are read
- THEN `lab_orders{status="PAID"}` is 3 and `lab_orders{status="PAYMENT_PENDING"}` is 2
- AND `lab_orders{status="CREATED"}` and `lab_orders{status="PAYMENT_FAILED"}` are 0

#### Scenario: Rejected statuses are reported
- GIVEN 4 stored orders in `REJECTED_OUT_OF_STOCK` and none in `REJECTED_INVENTORY_UNAVAILABLE`
- WHEN the gauges are read
- THEN `lab_orders{status="REJECTED_OUT_OF_STOCK"}` is 4 and `lab_orders{status="REJECTED_INVENTORY_UNAVAILABLE"}` is 0

#### Scenario: One query per scrape
- GIVEN the six `lab.orders` series
- WHEN all six are read within 1 second
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

- **A reservation is never released.** If the payment is declined or fails, or saving the order fails after the stock was reserved, the stock stays reserved. A read timeout on the inventory call leaves it unknown whether the stock was reserved, and the order is stored as `REJECTED_INVENTORY_UNAVAILABLE` anyway. inventory-service has no release endpoint yet (see its spec); the `order_events` and `reservation_events` rows of the same order id are the evidence a later reconciliation needs.
- **An order attempt made while order-service's own database is unavailable is not stored.** The request fails before inventory-service is called; only the `WARN` line records it.

- **A read timeout leaves the payment outcome unknown.** The request reached payment-service, which may already have stored a payment, but the order stays `PAYMENT_PENDING`.
- **Saving the outcome can fail after payment-service has decided.** If the save after the payment call fails (a database error, or an optimistic lock failure), `POST /orders` returns 500 and the order stays `PAYMENT_PENDING`, although payment-service has stored an `AUTHORIZED`, `DECLINED` or `FAILED` payment.
- **There is no reconciliation yet.** Nothing brings a `PAYMENT_PENDING` order in line with payment-service later. Reconciliation is a future feature, planned together with the fault injector, which may inject this inconsistency on purpose.
