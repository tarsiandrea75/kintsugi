# Feature: gateway-mock

Locator: `odd/tasks/gateway-mock.md` · Engram mirror: `odd/gateway-mock/tasks` · Branch: `feat/gateway-mock` (from `feat/payment-service-04-order-flow` at `f38c563`)

## Objective

Build `lab/gateway-mock`, a simulated payment gateway whose latency is configurable through REST. Wire payment-service to it so that injected latency produces fault F1: orders stuck in `PAYMENT_PENDING` (`docs/vision.md:31`).

## Problem and why

The vision's week-1 Thursday task is "`gateway-mock` with latency configurable via REST (fault F1)" (`docs/vision.md:249`). payment-service currently uses `AlwaysAuthorizeGateway`, so nothing in the lab can be slowed down on purpose. The future fault injector needs a REST control point to break the lab and to restore it.

## Scope

- In:
  - `lab/gateway-mock`, a Spring Boot web app with no database and in-memory behavior state;
  - `POST /authorize`;
  - the admin endpoints `PUT`, `GET` and `DELETE /admin/behavior` (latency only);
  - in payment-service, an `HttpPaymentGateway` that replaces `AlwaysAuthorizeGateway`, with configurable timeouts;
  - a 503 from payment-service when the gateway is unavailable;
  - living specs and docs.
- Out:
  - the forced-error behavior and the 429 response (fault F2, decided when F2 is built);
  - the fault injector itself and its true-cause table;
  - Docker Compose and observability (the Friday task);
  - authentication on the admin endpoints (this is a lab).

## Design (approved by the human 2026-10-06)

- **gateway-mock:**
  - Package `dev.kintsugi.lab.gateway`, with `behavior/` (state) and `api/` (endpoints).
  - No database: a fake external gateway has nothing to persist. The ground truth about faults belongs to the future fault injector.
- **Gateway API:** `POST /authorize` with body `{orderId}` waits for the configured latency, then returns 200 `{status: "AUTHORIZED"}`. A missing or invalid `orderId` returns 400.
- **Admin API:**
  - `PUT /admin/behavior` with body `{latencyMs}` returns 200 with the new behavior. A negative or missing value returns 400. The maximum is 60000 ms.
  - `GET /admin/behavior` returns the current behavior.
  - `DELETE /admin/behavior` resets the latency to 0 and returns 200 with the reset behavior.
  - The behavior state is thread-safe, for example an `AtomicReference` holding an immutable record.
  - Default port: 8082.
- **payment-service:**
  - `HttpPaymentGateway` implements `PaymentGateway` with `RestClient`. It follows the same timeout technique as order-service's `PaymentClient`: `JdkClientHttpRequestFactory` with the JDK `HttpClient` connect timeout.
  - Properties `kintsugi.gateway.base-url` (default `http://localhost:8082`), `kintsugi.gateway.connect-timeout` (1s) and `kintsugi.gateway.read-timeout` (15s).
  - `AlwaysAuthorizeGateway` is removed.
- **Gateway failure:** a timeout, a connection error, a non-2xx response or an unknown status from the gateway makes payment-service answer 503 and store no payment. "Gateway unavailable" is not a decline. order-service already treats a 503 as unavailable, so the order stays `PAYMENT_PENDING`.
- **F1 timing:**
  - order → payment read timeout: 3s (existing);
  - payment → gateway read timeout: 15s;
  - with 10s of gateway latency, order-service times out first and the order stays `PAYMENT_PENDING`.
- **Tests:**
  - gateway-mock: MockMvc for the API and validation, a unit test for the behavior state, and one test with a small real latency.
  - payment-service: `HttpPaymentGateway` against a JDK `HttpServer` fake (authorized, slow, 500, unreachable), the 503 mapping in the API, and the end-to-end test with the fake gateway.
  - No new dependencies.

## Acceptance criteria

- `POST /authorize` with a valid `orderId` returns 200 and `AUTHORIZED` after at least the configured latency.
- `PUT /admin/behavior {latencyMs}` changes the latency, `GET` reports it, and `DELETE` resets it to 0. Invalid values return 400.
- `POST /payments` stores and returns the gateway's outcome when the gateway answers in time.
- `POST /payments` returns 503 and stores nothing when the gateway times out, is unreachable, or returns an error.
- The gateway base URL and timeouts are configurable by property.
- `./mvnw test` passes in gateway-mock, payment-service and order-service.

## Delivery

- Strategy: `ask-on-risk`; `feature-branch-chain` is expected to be proposed again. Forecast: about 700 authored lines, generated files excluded.
- Generated files go in their own commit.
- RDD: on (global). Reviews run per range with `--base-ref`, because full-branch candidates exceed the reviewer context.

## Tasks

- [ ] **T1 — Generated skeleton.** Spring Initializr `lab/gateway-mock` with the dependencies `web`, `validation` and `actuator` (no database). Route: delegated writer.
- [ ] **T2 — gateway-mock behavior and API.** The living spec `openspec/specs/gateway-mock/spec.md`, then tests first, then code. Route: delegated writer.
- [ ] **T3 — payment-service HTTP gateway.** `HttpPaymentGateway`, the properties, the 503 mapping, removal of the fake, and an update to the living spec `openspec/specs/payment-service/spec.md`. Tests first. Route: delegated writer.
- [ ] **T4 — Docs.** `docs/setup.md`: run gateway-mock on 8082, the gateway properties, and how to reproduce F1 by hand with `curl`. Route: with T3.

## Checks

- `cd lab/gateway-mock && ./mvnw test`
- `cd lab/payment-service && ./mvnw test`
- `cd lab/order-service && ./mvnw test`

## Progress

- 2026-10-06: design approved; branch created.

## Next step

T1 via a delegated writer.
