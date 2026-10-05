# ADR-007: Lab persistence and test stack

## Status

Accepted

## Date

2026-10-05

## Context

M1 needs three lab services that a fault injector can break ([vision](../vision.md), section on M1). The first one, `lab/order-service`, creates and reads orders stored in PostgreSQL ([feature document](../../odd/tasks/order-service-skeleton.md)). Its symptoms are what the agent diagnoses: fault F1 leaves orders stuck in `PAYMENT_PENDING`, and fault F8 shows up as timeouts in order while the root cause is inventory.

The lab services exist to be broken. The persistence layer must make an injected fault visible exactly where it was injected, so the diagnosis can be scored against ground truth. The test stack must exercise the same database the lab runs.

The number is 007 because the vision reserves ADR-002 to ADR-005 for other decisions and ADR-006 is taken.

## Decision

Persistence:

- **Spring Data JDBC with Flyway**, not JPA. Explicit SQL means an injected slow query is exactly the query that runs. Lazy loading, N+1 queries and open-in-view would blur the ground-truth cause. HikariCP is the pool either way, so pool metrics are the same. JPA stays a possible later "realistic ORM" fault.
- **PostgreSQL 18.6** (`postgres:18.6`). One instance for the whole lab, with one database per service; the Compose wiring comes later. This instance is separate from the agent's pgvector database, the subject of ADR-002 (reserved for M2).
- **HikariCP pool size set explicitly** through properties in each service, so the fault injector can shrink it. Pool metrics stay a shared convention across services.

Tests:

- **Testcontainers 2.0.5** with `@ServiceConnection` and the `postgres:18.6` image. Tests run against the same database engine and version as the lab.

Build:

- **Each lab service is a standalone Maven project** with its own Maven Wrapper, like `agent/`. There is no aggregator pom until one is needed (YAGNI).

Versions (verified 2026-10-05; all except the image are managed by Spring Boot 4.1.1, see [ADR-006](ADR-006-stack-versions.md)):

| Component | Version |
|-----------|---------|
| PostgreSQL image | 18.6 (`postgres:18.6`) |
| Testcontainers | 2.0.5 (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) |
| Flyway | 12.4.0, with `flyway-database-postgresql` |
| PostgreSQL JDBC | 42.7.13 |
| HikariCP | 7.0.2 |

Boot 4 starters: `spring-boot-starter-data-jdbc`, `spring-boot-starter-flyway`, `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, `spring-boot-testcontainers`.

Confirmed on the first build: in Testcontainers 2.x the container class is `org.testcontainers.postgresql.PostgreSQLContainer`, and it is no longer generic (`PostgreSQLContainer`, not `PostgreSQLContainer<?>`).

## Alternatives considered

- **JPA with Hibernate 7.4.5**: the most common choice in company backends, but the generated SQL, lazy loading and session caching sit between the injected fault and the observed symptom. Kept as a candidate for a later fault.
- **H2 for tests**: faster startup, but it is not the real database. It would hide PostgreSQL behavior such as locking, timeouts and SQL dialect differences, which are exactly what faults exercise.
- **An aggregator pom for all lab services**: one build command, but it couples services that are meant to be independent, and nothing needs it yet.

## Consequences

### Positive

- An injected database fault maps directly to the SQL that runs, which keeps the ground truth clear.
- Tests use the same PostgreSQL version as the lab, so database behavior in tests matches the lab.
- Spring Boot manages every library version, so upgrades follow the Boot version.

### Negative

- Tests need Docker running.
- More hand-written SQL and mapping than with JPA.
- Each service has its own wrapper and build, so shared changes are repeated per service.
