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
- Chain strategy: `feature-branch-chain` (decided 2026-10-06). Tracker `feat/gateway-mock` (feature doc `f425f73`, skeleton `fc5bb75`). PR 1 `feat/gateway-mock-01-behavior` → tracker: spec, behavior store, admin API (about 384 lines). PR 2 `feat/gateway-mock-02-authorize` → PR 1: `POST /authorize` with latency (about 156 lines). PR 3 `feat/gateway-mock-03-payment-gateway` → PR 2: payment-service HTTP gateway, the 503 mapping, spec and docs (about 680 lines). PR 3 is a size exception: `HttpPaymentGateway` is a `@Component` that replaces `AlwaysAuthorizeGateway`, so the client, the removal of the fake, the 503 mapping and the integration test only build and pass together.
- RDD: on (global). Reviews run per range with `--base-ref`, because full-branch candidates exceed the reviewer context.

## Tasks

- [x] **T1 — Generated skeleton.** Spring Initializr `lab/gateway-mock` with the dependencies `web`, `validation` and `actuator` (no database). Route: delegated writer.
- [x] **T2 — gateway-mock behavior and API.** The living spec `openspec/specs/gateway-mock/spec.md`, then tests first, then code. Route: delegated writer.
- [x] **T3 — payment-service HTTP gateway.** `HttpPaymentGateway`, the properties, the 503 mapping, removal of the fake, and an update to the living spec `openspec/specs/payment-service/spec.md`. Tests first. Route: delegated writer.
- [x] **T4 — Docs.** `docs/setup.md`: run gateway-mock on 8082, the gateway properties, and how to reproduce F1 by hand with `curl`. Route: with T3.

## Checks

- `cd lab/gateway-mock && ./mvnw test`
- `cd lab/payment-service && ./mvnw test`
- `cd lab/order-service && ./mvnw test`

## Progress

- 2026-10-06: design approved; branch created.
- 2026-10-06: T1 done: Initializr skeleton (`web`, `validation`, `actuator`), pom placeholders replaced by a description, `server.port=8082`. Wrapper and git files are identical to payment-service. `./mvnw test`: 1 test, 0 failures. Generated scaffolding has no meaningful RED.
- 2026-10-06: T2–T4 done by the delegated writer (tests first, with compiling stubs for a behavioral RED). gateway-mock: 26 of 31 red, then 31 green. payment-service: 17 of 47 red, then 47 green. order-service unchanged at 97 (parent re-run of all three). Characterization tests: `unavailableGatewayStoresNothing`, `otherFailuresAreNotServiceUnavailable`, `everyPropertyCanBeOverridden`, and the unknown/missing status cases. A `@BeforeEach` reset is needed because the cached test context shares the behavior store. Not run by hand: the "Reproduce F1 by hand" recipe in `docs/setup.md`. Open: with 10 s of latency, payment-service still stores an AUTHORIZED payment after order-service has timed out (expected from the timeouts, not observed). An interrupted sleep in `/authorize` returns 503, which the spec does not cover.

## Next step

Review the range `f38c563..HEAD`; then close the session (STATE, journal, bundle).
