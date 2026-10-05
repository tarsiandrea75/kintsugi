# Order Service Specification

## Purpose

`lab/order-service` creates and reads orders persisted in PostgreSQL. It is the entry point of the M1 lab and the first place where injected faults show symptoms. Design: [feature document](../../../odd/tasks/order-service-skeleton.md). Stack: [ADR-007](../../../docs/adr/ADR-007-lab-persistence-stack.md). Verified by `./mvnw test` from `lab/order-service`.

An order has an id (UUID), a customer id, a list of items (SKU and quantity), a status, and created and updated timestamps.

## Requirements

### Requirement: Create an order

`POST /orders` with a valid body MUST store the order in PostgreSQL with status `CREATED` and MUST return 201, a `Location` header pointing to `/orders/{id}`, and the created order.

#### Scenario: Valid order is created
- GIVEN a body with a non-blank customer id and one item with SKU `SKU-1` and quantity 2
- WHEN `POST /orders` is called
- THEN the response status is 201
- AND the `Location` header ends with `/orders/{id}` for the new order id
- AND the body contains the order with status `CREATED`, a UUID id, and created and updated timestamps

#### Scenario: Created order is stored
- GIVEN an order created through `POST /orders`
- WHEN the orders table in PostgreSQL is queried by its id
- THEN one row exists with the same customer id, items and status `CREATED`

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
