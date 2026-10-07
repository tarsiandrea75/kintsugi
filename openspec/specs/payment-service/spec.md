# Payment Service Specification

## Purpose

`lab/payment-service` records the payment of an order and stores it in its own PostgreSQL database. `order-service` calls it after creating an order. A slow or failing payment path is where fault F1 shows up, and a gateway rejecting with 429 is where fault F2 shows up as failed payments. Design: [feature document](../../../odd/tasks/payment-service.md), for `FAILED` [fault F2](../../../odd/tasks/fault-f2.md), and for `payment_events` [event recording](../../../odd/tasks/event-recording.md). Stack: [ADR-007](../../../docs/adr/ADR-007-lab-persistence-stack.md). Verified by `./mvnw test` from `lab/payment-service`.

A payment has an id (UUID), an order id (UUID), a status (`AUTHORIZED`, `DECLINED` or `FAILED`) and a created timestamp. `FAILED` means the gateway rejected the request with a 429 instead of deciding the payment.

## Requirements

### Requirement: Create a payment

`POST /payments` with a valid body MUST ask the payment gateway for the outcome, store the payment in PostgreSQL with that outcome as its status, and return 201, a `Location` header pointing to `/payments/{id}`, and the created payment (`id`, `orderId`, `status`, `createdAt`).

#### Scenario: Valid payment is created
- GIVEN a body with an `orderId` that is a UUID
- WHEN `POST /payments` is called
- THEN the response status is 201
- AND the `Location` header ends with `/payments/{id}` for the new payment id
- AND the body contains the payment with the same `orderId`, the gateway's outcome as its status, a UUID id and a created timestamp

#### Scenario: Created payment is stored
- GIVEN a payment created through `POST /payments`
- WHEN the payments table in PostgreSQL is queried by its id
- THEN one row exists with the same order id and the status returned in the response

### Requirement: Payment outcome comes from the gateway

The payment status MUST be the outcome that the payment gateway returns for the order id; the service MUST NOT decide it on its own. The gateway is `gateway-mock`, called over HTTP: payment-service sends `POST /authorize` with body `{"orderId": "<order id>"}` and reads `status` (`AUTHORIZED` or `DECLINED`) from a 2xx answer. A `429 Too Many Requests` answer is also an outcome: the payment is `FAILED`.

The client MUST read its settings from these properties, so the fault injector can change them:

| Property | Default | Meaning |
|----------|---------|---------|
| `kintsugi.gateway.base-url` | `http://localhost:8082` | gateway-mock root URL |
| `kintsugi.gateway.connect-timeout` | `1s` | longest wait to open a connection |
| `kintsugi.gateway.read-timeout` | `15s` | longest wait for the response once the request is sent |

The read timeout is longer than order-service's 3s wait for payment-service. With a slow gateway, order-service gives up first and the order stays `PAYMENT_PENDING`: this is fault F1.

#### Scenario: Authorized by the gateway
- GIVEN the gateway answers 200 with status `AUTHORIZED`
- WHEN `POST /payments` is called with a valid body
- THEN the gateway received the order id
- AND the response status is 201 and the stored payment has status `AUTHORIZED`

#### Scenario: Declined by the gateway
- GIVEN a gateway that declines the order
- WHEN a payment is created for that order
- THEN the stored payment has status `DECLINED`

#### Scenario: Rate-limited by the gateway
- GIVEN the gateway answers 429
- WHEN `POST /payments` is called with a valid body
- THEN the response status is 201 and the body's status is `FAILED`
- AND the stored payment has status `FAILED`
- AND one `WARN` line is logged that names the order id and the gateway status 429

#### Scenario: Settings are configurable
- GIVEN no `kintsugi.gateway.*` property is set
- WHEN the client settings are read
- THEN they are the defaults in the table above
- AND each one takes the value of its property when the property is set

### Requirement: Gateway unavailable returns 503 and stores no payment

When the gateway call times out (connect or read), cannot connect, gets a non-2xx response other than 429, or gets a body without a known status (including `FAILED`, which the gateway never sends), `POST /payments` MUST return 503 and MUST NOT store a payment. It still records the attempt as a `GATEWAY_UNAVAILABLE` event (see "Every payment attempt is recorded"). An unavailable gateway is not a decline: the outcome is unknown. Other failures MUST NOT be turned into 503.

#### Scenario: Gateway too slow
- GIVEN the gateway answers only after the read timeout
- WHEN `POST /payments` is called with a valid body
- THEN the response status is 503
- AND no payment is stored
- AND one `GATEWAY_UNAVAILABLE` event is stored for the order, with a duration of at least the read timeout

#### Scenario: Gateway failing or unreachable
- GIVEN the gateway refuses connections, or answers a non-2xx status such as 400, 500 or 503, or answers a status other than `AUTHORIZED` or `DECLINED`
- WHEN `POST /payments` is called with a valid body
- THEN the response status is 503
- AND no payment is stored
- AND one `GATEWAY_UNAVAILABLE` event is stored for the order, with the exception class and the gateway URL in `detail`

#### Scenario: Other failures keep their status
- GIVEN a failure that is not an unavailable gateway
- WHEN a payment is created
- THEN the failure propagates to the caller and is not answered with 503
- AND no payment is stored

### Requirement: Every payment attempt is recorded

Every `POST /payments` with a valid body MUST leave exactly one row in the append-only table `payment_events`, whatever the outcome:

| Column | Value |
|--------|-------|
| `id` | a new UUID |
| `order_id` | the order id of the request |
| `occurred_at` | when the attempt was recorded (`timestamptz`, microseconds) |
| `type` | `PAYMENT_ATTEMPT`, the only type; a CHECK constraint rejects any other |
| `outcome` | `AUTHORIZED`, `DECLINED`, `FAILED` or `GATEWAY_UNAVAILABLE` |
| `detail` | compact JSON: `{"paymentId":"<id>"}` for a stored outcome; `{"exception":"<class>","url":"<gateway /authorize URL>","message":"<message>"}` for `GATEWAY_UNAVAILABLE`, where the class is the most specific cause, for example `java.net.http.HttpTimeoutException` |
| `duration_ms` | how long the attempt took, gateway call included |

For `AUTHORIZED`, `DECLINED` and `FAILED` the event MUST be inserted in the same transaction as the `payments` row, so a payment and its event commit or roll back together. For `GATEWAY_UNAVAILABLE` no payment is stored and the event MUST be inserted in a transaction of its own; the response is still 503. The gateway call runs outside any transaction. The table is indexed by `order_id` and `occurred_at`. It is append-only: the code exposes insert and read only (by order id, and the latest N), with no update or delete, and a test checks it. A request rejected with 400 leaves no event.

#### Scenario: One event per stored outcome
- GIVEN the gateway answers `AUTHORIZED`, `DECLINED` or 429
- WHEN `POST /payments` is called with a valid body
- THEN exactly one `payment_events` row exists for the order id, with type `PAYMENT_ATTEMPT`, the stored status as outcome and the payment id in `detail`

#### Scenario: Unavailable gateway is recorded without a payment
- GIVEN the gateway answers 500 or 503, or answers after the read timeout
- WHEN `POST /payments` is called with a valid body
- THEN the response status is 503
- AND no `payments` row exists for the order id
- AND exactly one `payment_events` row exists for it, with outcome `GATEWAY_UNAVAILABLE`, the exception class and the URL in `detail`, and a duration

#### Scenario: Event table is append-only
- GIVEN the event repository
- WHEN its public methods are listed
- THEN they are insert, read by order id and read latest, and none updates or deletes

### Requirement: Database unavailable is logged, never dropped

This rule is shared by every lab service that records events: if the database itself is unreachable, the event goes to a structured log line instead. Nothing is silently dropped.

When an event cannot be stored because no database connection is available, the service MUST log exactly one `WARN` line with `key=value` pairs, readable by Loki's `logfmt` parser:

```
Payment event not stored: event=PAYMENT_ATTEMPT order=<order id> outcome=<outcome> duration_ms=<ms> detail="<detail JSON>" cause="<database message>"
```

For `GATEWAY_UNAVAILABLE` the response is still 503. For a stored outcome the payment cannot be stored either, so the failure propagates as before (500). Quoted values escape `\` and `"`. This is a stated limitation: an attempt made while the database is unavailable exists only in the logs.

#### Scenario: Event of an unavailable gateway cannot be stored
- GIVEN the gateway is unavailable and no database connection can be opened
- WHEN a payment is attempted
- THEN the gateway failure still propagates, so the response is 503
- AND one `WARN` line contains `event=PAYMENT_ATTEMPT`, `order=<order id>` and `outcome=GATEWAY_UNAVAILABLE`

### Requirement: Reject invalid input

`POST /payments` MUST return 400 and MUST NOT store a payment when the `orderId` is missing, null or not a UUID, or when the body is not well-formed JSON. `GET /payments/{id}` MUST return 400 when the id is not a UUID.

#### Scenario: Missing order id
- GIVEN an empty JSON object, and another body whose `orderId` is `null`
- WHEN `POST /payments` is called with each body
- THEN each response status is 400
- AND no payment is stored

#### Scenario: Order id is not a UUID
- GIVEN a body whose `orderId` is `not-a-uuid`
- WHEN `POST /payments` is called
- THEN the response status is 400
- AND no payment is stored

#### Scenario: Malformed JSON
- GIVEN a body that is not well-formed JSON
- WHEN `POST /payments` is called
- THEN the response status is 400
- AND no payment is stored

#### Scenario: Non-UUID path id
- GIVEN the path id `not-a-uuid`
- WHEN `GET /payments/not-a-uuid` is called
- THEN the response status is 400

### Requirement: Read a payment

`GET /payments/{id}` MUST return 200 with the stored payment when the id exists, and MUST return 404 when no payment has that id.

#### Scenario: Existing payment
- GIVEN a payment created through `POST /payments`
- WHEN `GET /payments/{id}` is called with its id
- THEN the response status is 200
- AND the body matches the stored payment: id, order id, status and created timestamp

#### Scenario: Unknown payment
- GIVEN no payment with id `00000000-0000-0000-0000-000000000000`
- WHEN `GET /payments/00000000-0000-0000-0000-000000000000` is called
- THEN the response status is 404

### Requirement: Expose payments by status

The service MUST expose the Micrometer gauge `lab.payments` (`lab_payments` in Prometheus) with one series per payment status (tag `status`), counted from the database. A status with no payments MUST report 0. A burst of `FAILED` payments is how fault F2 shows in the metrics. The design copies order-service's `lab.orders`: a scrape never waits on the database; it reads a snapshot refreshed asynchronously at most once per second, the count query has its own 3 s statement timeout, a failed refresh reports NaN, and a snapshot older than 10 s is never served.

#### Scenario: Scrape shows every status
- GIVEN one stored payment with status `FAILED` and no other payments
- WHEN `/actuator/prometheus` is scraped
- THEN it contains `lab_payments{status="FAILED"} 1.0`
- AND `lab_payments{status="AUTHORIZED"} 0.0` and `lab_payments{status="DECLINED"} 0.0`

#### Scenario: Database failure
- GIVEN the count query fails or exceeds its timeout
- WHEN the gauge is read
- THEN every series reports NaN, and the scrape does not wait for the database

### Requirement: Schema and persistence tests

The database schema MUST be created by Flyway migrations. The status column MUST accept only `AUTHORIZED`, `DECLINED` and `FAILED`: `V1` created it with the first two, and `V2` widens the constraint, because `V1` may already be applied on existing databases and is never edited. `V3` creates `payment_events`. Timestamps MUST be cut to microseconds, so a payment read back equals the payment written. The service listens on port 8081, so it can run next to order-service (8080) locally. Persistence and end-to-end tests MUST run against a real PostgreSQL 18.6 started by Testcontainers (`postgres:18.6` with `@ServiceConnection`), not an in-memory database. `./mvnw test` from `lab/payment-service` MUST pass.

#### Scenario: Flyway creates the schema
- GIVEN an empty PostgreSQL 18.6 database started by Testcontainers
- WHEN the application context starts
- THEN Flyway applies the migrations, `V2` included
- AND the payments table exists

#### Scenario: Status constraint
- GIVEN the migrated schema
- WHEN a row with status `FAILED` is inserted, and then a row with status `PENDING`
- THEN the `FAILED` row is stored
- AND the `PENDING` row is rejected

#### Scenario: Timestamp precision
- GIVEN a clock reading `2026-10-06T10:15:30.123456789Z`
- WHEN a payment is created
- THEN its created timestamp is `2026-10-06T10:15:30.123456Z`

#### Scenario: Payment round trip
- GIVEN a new payment
- WHEN it is saved and read back by id
- THEN the id, order id, status and created timestamp are equal to the saved ones

#### Scenario: Test run
- GIVEN Docker is running
- WHEN `./mvnw test` runs in `lab/payment-service`
- THEN the build succeeds
- AND the persistence tests ran against the `postgres:18.6` container

## Known limitations

- **No amount.** Orders have no prices yet; prices come with the product catalog in `inventory-service` (fault F6). A payment carries only the order id.
- **No idempotency.** Calling `POST /payments` twice for the same order creates two payments. There is no idempotency key and no unique constraint on the order id; retries are out of scope. Each attempt leaves its own `payment_events` row, so the duplicate is visible.
- **Attempts during a database outage are only logged.** They cannot be written to `payment_events`; the structured `WARN` line is their only record.
- **No retry on 429.** A rate-limited payment is stored as `FAILED` at once; there is no retry, backoff or `Retry-After` handling.
