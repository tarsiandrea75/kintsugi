# Feature: order-service-skeleton

Locator: `odd/tasks/order-service-skeleton.md` · Engram mirror: `odd/order-service-skeleton/tasks` · Branch: `feat/order-service-skeleton` (from `feat/model-smoke-test-03-adr`)

## Objective

Build the first lab microservice: `lab/order-service`, which creates and reads orders persisted in PostgreSQL, with a living spec and an ADR for the persistence and test stack.

## Problem and why

M1 needs three services that a fault injector can break (`docs/vision.md:22-25`). Fault F1 shows up as orders stuck in `PAYMENT_PENDING` (`docs/vision.md:31`). Fault F8 shows up as timeouts in order while the root cause is inventory (`docs/vision.md:38`). The order service is the entry point of the lab and the first place where symptoms show.

## Scope

- In: a standalone Maven project, the order model and states, the Flyway schema, persistence, `POST /orders`, `GET /orders/{id}`, tests with Testcontainers, ADR-007, the living spec `openspec/specs/order-service/spec.md`, and `docs/setup.md`.
- Out: the call to `payment-service` (Wednesday task), inventory reservation, Docker Compose and observability (Friday task), the fault injector, and authentication.

## Design (approved by the human 2026-10-05)

- **Persistence:** Spring Data JDBC with Flyway, not JPA. Why: this service exists to be broken. Explicit SQL means an injected slow query is exactly the query that runs; lazy loading, N+1 queries and open-in-view would blur the ground-truth cause. HikariCP is the pool either way, so the pool metrics are the same. JPA stays a possible later "realistic ORM" fault.
- **Module:** `lab/order-service` is a standalone Maven project with its own Maven Wrapper, like `agent/`. There is no aggregator pom until one is needed (YAGNI).
- **Package:** `dev.kintsugi.lab.order`, with `domain/` (Order, OrderStatus), `persistence/` (repository), and `api/` (controller, DTOs).
- **States:** `CREATED` → `PAYMENT_PENDING` → `PAID` | `PAYMENT_FAILED`. Only `PAYMENT_PENDING` comes from the vision. Allowed transitions are enforced in the domain; an invalid transition throws.
- **The skeleton today:** `POST /orders` creates an order in `CREATED` and returns 201 with a `Location` header. `GET /orders/{id}` returns the order, or 404. An order has an id (UUID), a customer id, an item list (SKU and quantity, quantity > 0, at least one item), a status, and created/updated timestamps. Invalid input returns 400.
- **Database:** one PostgreSQL 18.6 for the whole lab, with one database per service (wired in Compose later). Tests use Testcontainers `postgres:18.6` with `@ServiceConnection`. This is separate from the agent's pgvector database (ADR-002, M2).
- **Pool:** HikariCP size set explicitly through properties, so the fault injector can shrink it later. Pool metrics stay a shared convention across services.

## Versions (verified 2026-10-05; all except the image are managed by Spring Boot 4.1.1)

PostgreSQL 18.6 (`postgres:18.6`), Testcontainers 2.0.5 (`testcontainers-postgresql`, `testcontainers-junit-jupiter`), Flyway 12.4.0 with `flyway-database-postgresql`, PostgreSQL JDBC 42.7.13, HikariCP 7.0.2. Boot 4 starters: `spring-boot-starter-data-jdbc`, `spring-boot-starter-flyway`, `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, `spring-boot-testcontainers`.

## Acceptance criteria

- `POST /orders` with a valid body returns 201, a `Location` header and the order in `CREATED`. The order is stored in PostgreSQL.
- `POST /orders` with no items, a quantity of 0 or less, or a blank customer id returns 400.
- `GET /orders/{id}` returns the stored order; an unknown id returns 404.
- The domain rejects an invalid state transition, for example `PAID` → `PAYMENT_PENDING`.
- The Flyway migration creates the schema; the tests run against a real PostgreSQL 18.6 through Testcontainers.
- `./mvnw test` passes from `lab/order-service`.

## Delivery

- Strategy: `ask-on-risk`. Forecast: about 450 authored lines (generated files excluded), so the chain strategy is asked before the commit that crosses 400.
- Generated files (wrapper, Initializr skeleton) go in their own commit, separate from authored code (lesson from session 02).
- RDD: on (global).

## Tasks

- [x] **T1 — ADR-007 and living spec.** `docs/adr/ADR-007-lab-persistence-stack.md` (ADR-001 headings) and `openspec/specs/order-service/spec.md` (requirements plus GIVEN/WHEN/THEN scenarios, from the acceptance criteria). Also add an addendum to `docs/journal/2026-10-05-03.md` correcting the SDD and Docker notes (review finding). Route: delegated writer (2+ non-trivial files).
- [x] **T2 — Generated skeleton.** Spring Initializr project in `lab/order-service` with the starters above, plus the Maven Wrapper. Compiles; the context-load test passes against Testcontainers. Route: delegated with T3–T4.
- [ ] **T3 — Domain and persistence.** `Order`, `OrderStatus` with transition rules, the Flyway `V1__create_orders.sql`, and the repository. Unit tests for transitions; repository tests with Testcontainers (RED first). Route: delegated writer.
- [ ] **T4 — REST API.** `POST /orders` and `GET /orders/{id}` with validation and errors. MockMvc tests plus one end-to-end test with Testcontainers (RED first). Route: delegated writer.
- [ ] **T5 — Docs.** `docs/setup.md`: how to build and test order-service. Route: with T4.

## Checks

- `cd lab/order-service && ./mvnw test`

## Progress

- 2026-10-05: research done (versions, environment, JPA vs JDBC); design approved; branch created.
- 2026-10-05: T1 done by one delegated writer (trigger: 2+ non-trivial files). ADR-007 (67 lines), living spec with 5 requirements and 13 scenarios (112 lines), journal addendum (+5). Passive docs: the structural checks passed (headings match ADR-001, 11 links resolve, every acceptance criterion maps to a scenario).
- 2026-10-05: T2 done: Spring Initializr (Boot 4.1.1, ids data-jdbc, flyway, postgresql, web, validation, testcontainers, actuator). Only deviation: Testcontainers image pinned from `postgres:latest` to `postgres:18.6`. The wrapper matches `agent/` (3.3.4, Maven 3.9.16). Testcontainers 2.x package: `org.testcontainers.postgresql.PostgreSQLContainer`, not generic (closes the ADR-007 open point). `./mvnw test`: 1 test, 0 failures, against a real PostgreSQL 18.6. No meaningful RED for generated scaffolding.

## Next step

T2–T5 via one delegated writer (generated skeleton in its own commit).
