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
- Chain strategy: `feature-branch-chain` (decided 2026-10-06). Tracker `feat/payment-service` (feature doc `b133bf9`, skeleton `486a34b`). PR 1 `feat/payment-service-01-domain` → tracker: spec, domain, gateway, persistence, skeleton tidy-up (about 420 lines, slightly over budget). PR 2 `feat/payment-service-02-api` → PR 1: application service and REST API (about 350 lines). PR 3 `feat/payment-service-03-order-payment` → PR 2: payment-service review follow-ups plus the order payment client (about 430 lines). PR 4 `feat/payment-service-04-order-flow` → PR 3: `OrderService` payment step, specs and docs (about 430 lines). This split came from one slicing pass over T3+T4 (857 lines).
- RDD: on (global).

## Tasks

- [x] **T1 — Generated skeleton.** Spring Initializr project `lab/payment-service` (the same ids as order-service, image pinned to `postgres:18.6`). Route: delegated writer.
- [x] **T2 — payment-service domain, persistence and API.** The living spec `openspec/specs/payment-service/spec.md`, then tests first, then code. Route: delegated writer.
- [x] **T3 — order-service payment step.** `OrderService`, the payment client with configurable timeouts, a decision on the test HTTP server (ADR if it is a new dependency), tests first including the timeout case. Update the living spec `openspec/specs/order-service/spec.md`. Route: delegated writer.
- [x] **T4 — Docs.** `docs/setup.md` (payment-service, and how the two services are configured together). Route: with T3.
- [x] **T5 — Known limitations and connect-timeout test (from review `review-bba7549d921f6874`, accepted 2026-10-06).** Document in the order-service living spec that an unknown payment outcome (a read timeout, or the post-payment save failing with a 500) leaves the order `PAYMENT_PENDING` with no reconciliation; reconciliation is a future feature, planned with the fault injector. Add a deterministic connect-timeout test and remove the closed-port race in `PaymentClientTest`. Route: delegated writer. Branch: `feat/payment-service-04-order-flow`.

## Checks

- `cd lab/payment-service && ./mvnw test`
- `cd lab/order-service && ./mvnw test`

## Progress

- 2026-10-06: design approved; branch created.
- 2026-10-06: T1 done: the Initializr skeleton (same request as order-service), with the image pinned to `postgres:18.6` and `TestcontainersConfiguration` made public. Wrapper and git files are byte-identical to order-service. `./mvnw test`: 1 test, 0 failures. No meaningful RED for scaffolding.
- 2026-10-06: skeleton review: the full-branch candidate failed with `lens_context_budget_exceeded` (no authority created). It was reduced to `5d84ecb..486a34b`, consent was granted again, and the 4-lens review approved and was acknowledged (lineage `review-98f28bf288c48aa9`). Findings were minor and are folded into T2.
- 2026-10-06: T2 done by a delegated writer (tests first). RED: compile failure, then 1 failure and 3 errors from deliberate defects (gateway outcome ignored, `@Valid` missing). GREEN: 28 tests (parent re-run); order-service still 74. Decision: `PaymentService` lives in a new `application` package, so the domain does not depend on the gateway. Skeleton tidy-up: pom placeholders replaced by a description in both services; ADR-007 comment on the image tag; Hikari pool size 10.
- 2026-10-06: review of `486a34b..93dfbbc` (4 lenses, high risk because of the payments migration): approved, lineage `review-e611373a428b8acd`. Accepted: the spec wording, a gateway-failure test and a stronger truncation test. Out of scope: authentication (lab) and idempotency (see Scope).
- 2026-10-06: T3 + T4 done by a delegated writer (tests first). RED: order-service 16 failures and 3 errors with stub code; payment-service truncation test red without truncation. GREEN: order-service 96, payment-service 29 (parent re-run). RestClient timeouts use `JdkClientHttpRequestFactory` plus the JDK `HttpClient` connect timeout, because `spring-boot-http-client` is not on the classpath. The fake payment server is the JDK `HttpServer`, so no new dependency and no ADR. Decisions kept: the controller still calls `Order.create`, so domain errors stay 400 and `OrderService.place(Order)` handles payment; a 2xx with an unknown status counts as unavailable, so the order stays pending. Commits: `b20f2ed` (payment-service follow-ups), `59aa239` (payment client; verified alone with 87 tests). Not verified: the manual run guide in `docs/setup.md`.
- 2026-10-06: review of `93dfbbc..e1e1f21` (reliability): approved, lineage `review-bba7549d921f6874`. Its findings were accepted as T5.
- 2026-10-06: T5 done by a delegated writer. Spec "Known limitations" added. Connect-timeout test: a loopback `ServerSocket` with backlog 1 that never accepts, filled until a probe times out, so Linux drops further SYNs. It asserts `HttpConnectTimeoutException` and an elapsed time between 200 ms and 3 s, because the JDK reports its whole-request timeout with the same exception type. RED: with `connectTimeout` removed, it failed after 11.1 s. GREEN: about 0.3 s, stable over 3 runs. The unreachable test now targets a bound but non-listening `Socket`, which is refused at once and has no port race. order-service: 97 tests (parent re-run). Linux-only assumption (the lab targets Linux/WSL).
- 2026-10-06: review of `e1e1f21..510e0ee` (reliability): approved, lineage `review-b4a172258b5ce544`. Fixed inline: the connect-timeout test is now `@EnabledOnOs(LINUX)` with a comment explaining why, and the fixtures bind the IPv4 loopback `127.0.0.1` to match the client URL. 97 tests, 0 skipped on Linux.

## Next step

Close the session (STATE, journal) and refresh the git bundle. Push and PRs are human decisions.
