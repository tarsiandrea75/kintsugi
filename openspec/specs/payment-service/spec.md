# Payment Service Specification

## Purpose

`lab/payment-service` records the payment of an order and stores it in its own PostgreSQL database. `order-service` calls it after creating an order. A slow or failing payment path is where fault F1 shows up. Design: [feature document](../../../odd/tasks/payment-service.md). Stack: [ADR-007](../../../docs/adr/ADR-007-lab-persistence-stack.md). Verified by `./mvnw test` from `lab/payment-service`.

A payment has an id (UUID), an order id (UUID), a status (`AUTHORIZED` or `DECLINED`) and a created timestamp.

## Requirements

### Requirement: Create a payment

`POST /payments` with a valid body MUST ask the payment gateway for the outcome, store the payment in PostgreSQL with that outcome as its status, and return 201, a `Location` header pointing to `/payments/{id}`, and the created payment (`id`, `orderId`, `status`, `createdAt`).

#### Scenario: Valid payment is created
- GIVEN a body with an `orderId` that is a UUID
- WHEN `POST /payments` is called
- THEN the response status is 201
- AND the `Location` header ends with `/payments/{id}` for the new payment id
- AND the body contains the payment with the same `orderId`, the gateway's outcome as its status (`AUTHORIZED` with the current fake gateway), a UUID id and a created timestamp

#### Scenario: Created payment is stored
- GIVEN a payment created through `POST /payments`
- WHEN the payments table in PostgreSQL is queried by its id
- THEN one row exists with the same order id and the status returned in the response

### Requirement: Payment outcome comes from the gateway

The payment status MUST be the outcome that the `PaymentGateway` returns for the order id; the service MUST NOT decide it on its own. Until the HTTP client to `gateway-mock` exists, the gateway is a fake that always returns `AUTHORIZED`.

#### Scenario: Gateway failure
- GIVEN a gateway that throws while deciding the outcome
- WHEN a payment is created
- THEN the failure propagates to the caller
- AND no payment is stored

#### Scenario: Declined by the gateway
- GIVEN a gateway that declines the order
- WHEN a payment is created for that order
- THEN the stored payment has status `DECLINED`

#### Scenario: Fake gateway authorizes
- GIVEN the default fake gateway
- WHEN it is asked for the outcome of any order
- THEN it returns `AUTHORIZED`

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

### Requirement: Schema and persistence tests

The database schema MUST be created by a Flyway migration. The status column MUST accept only `AUTHORIZED` and `DECLINED`. Timestamps MUST be cut to microseconds, so a payment read back equals the payment written. The service listens on port 8081, so it can run next to order-service (8080) locally. Persistence and end-to-end tests MUST run against a real PostgreSQL 18.6 started by Testcontainers (`postgres:18.6` with `@ServiceConnection`), not an in-memory database. `./mvnw test` from `lab/payment-service` MUST pass.

#### Scenario: Flyway creates the schema
- GIVEN an empty PostgreSQL 18.6 database started by Testcontainers
- WHEN the application context starts
- THEN Flyway applies the migrations
- AND the payments table exists

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
- **No idempotency.** Calling `POST /payments` twice for the same order creates two payments. There is no idempotency key and no unique constraint on the order id; retries are out of scope.
