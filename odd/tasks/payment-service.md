# Feature: payment-service

Locator: `odd/tasks/payment-service.md` · Engram mirror: `odd/payment-service/tasks` · Branch: `feat/payment-service` (from `feat/order-service-skeleton-02-api` at `5d84ecb`)

## Objective

Build `lab/payment-service` and wire the order → payment call, so that a slow or failing payment path leaves orders in `PAYMENT_PENDING`. That is the visible symptom of fault F1 (`docs/vision.md:31`).

## Problem and why

The vision's week-1 Wednesday task is "`payment-service` and order → payment call" (`docs/vision.md:248`). F1 (slow gateway) must show up as orders stuck in `PAYMENT_PENDING`, and F8 as timeouts in order. Both need a real network call with explicit timeouts between services.

## Scope

- In:
  - a standalone `payment-service` project with its own database;
  - `POST /payments` and `GET /payments/{id}`;
  - a `PaymentGateway` port with a fake that always authorizes;
  - in `order-service`, a payment client and the payment step after an order is created;
  - living specs for both services;
  - docs.
- Out:
  - `gateway-mock` and the real HTTP gateway client (Thursday task);
  - amounts and prices (they come with `inventory-service`, see F6);
  - retries and idempotency keys;
  - Docker Compose and observability (Friday task);
  - message brokers.

## Design (approved by the human 2026-10-06)

- **Synchronous REST with explicit timeouts.**
  - `POST /orders` creates the order, moves it to `PAYMENT_PENDING`, saves it, then calls `POST /payments` on payment-service.
  - The response decides the next state: `AUTHORIZED` → `PAID`, `DECLINED` → `PAYMENT_FAILED`.
  - A timeout or a connection error leaves the order in `PAYMENT_PENDING`, which is the F1 symptom, and `POST /orders` still returns 201 with the order in that state.
  - No broker: it would add infrastructure and change the kind of fault.
- **payment-service stack:** the same as order-service (ADR-007): Spring Data JDBC, Flyway, PostgreSQL 18.6, Testcontainers. Each service has its own database.
  - Package `dev.kintsugi.lab.payment`, with `domain/`, `persistence/`, `gateway/` and `api/`.
  - A payment has an id (UUID), an order id, a status (`AUTHORIZED` | `DECLINED`) and a created timestamp.
- **Gateway port:** a `PaymentGateway` interface with an `AlwaysAuthorizeGateway` fake. Thursday replaces the fake with an HTTP client to `gateway-mock`, which is where F1 is injected.
- **No amount yet:** the orders have no prices, and the vision puts prices in the product catalog (F6). The payment carries only `orderId`. The living spec records this as a known limitation.
- **HTTP client:** Spring `RestClient`, with connect and read timeouts read from properties (`kintsugi.payment.base-url`, `kintsugi.payment.connect-timeout`, `kintsugi.payment.read-timeout`), so the fault injector can change them. Verify the exact Boot 4.1.1 API before writing it.
- **Order flow:** add an application service in `order-service` (`OrderService`) that owns create → save → pay → save. The controller stops calling the repository directly; the order-service review finding anticipated this.
- **Tests:**
  - payment-service: domain unit tests, a repository test with Testcontainers, MockMvc API tests, and one end-to-end test.
  - order-service: the payment client tested against a fake HTTP server, including a timeout that leaves the order in `PAYMENT_PENDING`. Prefer a tool that can delay a real response. If a new test dependency is needed (for example WireMock), check its current version and record it in an ADR.

## Acceptance criteria

- `POST /payments` with a valid `orderId` returns 201 and a payment in `AUTHORIZED`, stored in PostgreSQL.
- `POST /payments` with a missing or invalid `orderId` returns 400.
- `GET /payments/{id}` returns the payment; an unknown id returns 404.
- `POST /orders` ends with the order in `PAID` when payment-service authorizes, and in `PAYMENT_FAILED` when it declines.
- `POST /orders` leaves the order in `PAYMENT_PENDING` when payment-service times out or is unreachable, and still returns 201.
- The payment base URL and timeouts are configurable by property.
- `./mvnw test` passes in both `lab/order-service` and `lab/payment-service`.

## Delivery

- Strategy: `ask-on-risk`, with `feature-branch-chain` proposed again (the same as the two previous features). The forecast is about 800 authored lines, generated files excluded, so slicing is needed.
- Generated files go in their own commit.
- RDD: on (global).

## Tasks

- [x] **T1 — Generated skeleton.** Spring Initializr project `lab/payment-service` (the same ids as order-service, image pinned to `postgres:18.6`). Route: delegated writer.
- [ ] **T2 — payment-service domain, persistence and API.** The living spec `openspec/specs/payment-service/spec.md`, then tests first, then code. Route: delegated writer.
- [ ] **T3 — order-service payment step.** `OrderService`, the payment client with configurable timeouts, a decision on the test HTTP server (ADR if it is a new dependency), tests first including the timeout case. Update the living spec `openspec/specs/order-service/spec.md`. Route: delegated writer.
- [ ] **T4 — Docs.** `docs/setup.md` (payment-service, and how the two services are configured together). Route: with T3.

## Checks

- `cd lab/payment-service && ./mvnw test`
- `cd lab/order-service && ./mvnw test`

## Progress

- 2026-10-06: design approved; branch created.
- 2026-10-06: T1 done: the Initializr skeleton (same request as order-service), with the image pinned to `postgres:18.6` and `TestcontainersConfiguration` made public. Wrapper and git files are byte-identical to order-service. `./mvnw test`: 1 test, 0 failures. No meaningful RED for scaffolding.

## Next step

Run the review preflight on the skeleton commit (the stop hook asked for it while files were untracked). Then T2 via a delegated writer.
