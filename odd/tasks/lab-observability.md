# Feature: lab-observability

Locator: `odd/tasks/lab-observability.md` · Engram mirror: `odd/lab-observability/tasks` · Branch: `feat/lab-observability` (from `feat/gateway-mock-03-payment-gateway` at `29dcf1a`)

## Objective

Run the whole M1 lab with one Docker Compose command, collecting metrics in Prometheus and logs in Loki. Provide a Grafana dashboard where fault F1 is visible while it is active. This is the F1 part of the M1 check: "every fault F1–F4 is visible in Grafana" (`docs/vision.md:152`).

## Problem and why

The vision's week-1 Friday task is "Docker Compose with OpenTelemetry, Prometheus, Grafana; dashboard showing F1 when active" (`docs/vision.md:250`). Today the services expose only `health`, nothing counts orders by status, and `infra/` is empty. In M2 the agent will read logs and metrics through MCP tools (`getLogs`, `getMetrics`, `docs/vision.md:73`), so Prometheus (PromQL) and Loki (LogQL) are also the future query APIs.

## Scope

- In:
  - a Prometheus registry and `/actuator/prometheus` in the three services;
  - an orders-by-status gauge in order-service;
  - Dockerfiles for the three services;
  - `infra/compose.yaml` with one PostgreSQL holding two databases, the three services, Prometheus, Loki, Alloy and Grafana;
  - Grafana provisioning, meaning datasources and a "Lab — F1" dashboard;
  - an automated F1 visibility check script;
  - ADR-008;
  - living specs and docs.
- Out:
  - traces and Tempo (they belong to the agent, M2+);
  - OTLP export (it can be added later without rework);
  - alerting and Alertmanager (they come with the agent's perception module);
  - faults F2–F4;
  - the fault injector;
  - CI.

## Design (approved by the human 2026-10-06)

- **Metrics:**
  - Prometheus scrapes `/actuator/prometheus` on each service.
  - Each service adds `io.micrometer:micrometer-registry-prometheus`, whose version is managed by Boot 4.1.1 (1.17.1), and sets `management.endpoints.web.exposure.include=health,prometheus`.
  - order-service gets a new gauge for orders per status, read from the database at scrape time, with one series per `OrderStatus`. Its name is chosen in T2 and recorded in the spec.
  - gateway-mock already has `gateway.authorize.inflight`.
  - The Spring HTTP server metrics come for free.
- **Logs:** Alloy reads the Docker container logs (through the Docker socket) and ships them to Loki, with a `service` label taken from the compose service name.
- **No traces** in M1, recorded as such in ADR-008.
- **Images:**
  - The three services are built from a simple multi-stage Dockerfile: a Maven build stage, then a JRE 21 runtime image. The exact base image tags are checked and pinned in ADR-008.
  - Observability images are pinned to the tags verified on 2026-10-06: `prom/prometheus:v3.15.0`, `grafana/grafana:13.2.3`, `grafana/loki:3.7.8`, `grafana/alloy:v1.20.1`, `postgres:18.6`.
- **Compose:**
  - The file is `infra/compose.yaml`.
  - One PostgreSQL 18.6 container with an init script that creates the `orders` and `payments` databases (ADR-007: one lab instance, one database per service). Its port is not exposed on the host.
  - Service URLs use the compose service names, for example `http://payment-service:8081`.
  - Healthchecks and `depends_on` conditions.
  - Host ports are chosen away from the user's other projects: services on 18080–18082, Grafana on 13000, Prometheus on 19090. Loki and Alloy stay internal unless needed.
- **Grafana:**
  - Datasources (Prometheus, Loki) and the "Lab — F1" dashboard are provisioned from files in `infra/grafana/`.
  - Panels:
    - orders by status;
    - `gateway_authorize_inflight`;
    - payment-service request latency and 503 rate;
    - order-service WARN log lines from Loki.
  - Anonymous viewer access is allowed for the lab.
- **F1 check:** `infra/scripts/verify-f1.sh` brings the compose up (or uses a running one) and waits for health. It then:
  - places a healthy order;
  - sets the gateway latency to 10 s;
  - places an order;
  - asserts through the Prometheus HTTP API that the `PAYMENT_PENDING` series rose and the in-flight gauge was above 0;
  - asserts through the Loki API that the order-service WARN line is present;
  - resets the gateway.

  It exits non-zero on failure.

## Acceptance criteria

- `docker compose -f infra/compose.yaml up -d` starts every container healthy, and `POST /orders` on the host port ends in `PAID`.
- `/actuator/prometheus` on each service is scraped by Prometheus (targets up).
- The orders-by-status gauge reports one series per status with correct counts. Unit and integration tests cover it.
- Loki receives the logs of the three services, labelled by service.
- Grafana starts with both datasources and the "Lab — F1" dashboard provisioned.
- `infra/scripts/verify-f1.sh` passes: F1 is visible in Prometheus (pending orders rise, in-flight gauge above 0) and in Loki (the WARN line).
- `./mvnw test` passes in the three services.

## Delivery

- Strategy: `ask-on-risk`; `feature-branch-chain` is expected. Forecast: about 900 authored lines, mostly configuration, so slicing is needed.
- Chain strategy: `feature-branch-chain`. From now on it is the user's standing default for every over-budget feature, applied without asking (`auto-chain`, decided 2026-10-06). Tracker `feat/lab-observability` (feature doc `6880194`). PR 1 `feat/lab-observability-01-decisions` → tracker: ADR-008 and specs (about 205 lines). PR 2 `feat/lab-observability-02-metrics` → PR 1: service metrics (about 345 lines).
- RDD: on (global). Reviews run per range with `--base-ref`.

## Tasks

- [x] **T1 — ADR-008 and living spec.** ADR-008: the observability stack, Option A versus OTLP, pinned versions including the base images, the host port policy. Living spec `openspec/specs/lab-observability/spec.md`. Route: delegated writer.
- [x] **T2 — Service metrics.** The Prometheus registry and exposure in the three services; the orders-by-status gauge in order-service (tests first); an update to the order-service living spec. Route: delegated writer.
- [x] **T3 — Images and compose.** The Dockerfiles; `infra/compose.yaml` with PostgreSQL, its init script, and the three services; healthchecks. Verify by bringing it up and placing a `PAID` order. Route: delegated writer.
- [x] **T4 — Observability stack.** Prometheus scrape config, Loki, Alloy config, Grafana provisioning and the "Lab — F1" dashboard. Verify that targets are up and that logs reach Loki. Route: delegated writer.
- [x] **T5 — F1 check and docs.** `infra/scripts/verify-f1.sh`, run and passing; `docs/setup.md` (Compose section, ports, how to view the dashboard). Route: delegated writer.

## Checks

- `./mvnw test` in each `lab/*` service
- `docker compose -f infra/compose.yaml config` (syntax), `up -d`, and the healthchecks
- `infra/scripts/verify-f1.sh`

## Progress

- 2026-10-06: research done (versions, Boot integration, ports); design approved; branch created.
- 2026-10-06: T1 + T2 done by a delegated writer. Base images pinned: `maven:3.9.16-eclipse-temurin-21-noble` (build) and `eclipse-temurin:21.0.12.1_1-jre-noble` (runtime). Gauge `lab.orders`, exposed as `lab_orders{status}`: one series per status including 0, one shared query cached for 1 s, NaN when the database query fails. Boot 4.1.1 disables metrics export in tests; `@AutoConfigureMetrics` turns it back on. RED: 404 on `/actuator/prometheus`, and order-service tests did not compile. GREEN: order-service 103, payment-service 49, gateway-mock 38 (parent re-run). The spec requires `verify-f1.sh` to reset the gateway even when a check fails.
- 2026-10-06: review of `29dcf1a..dd0c02f` approved (lineage `review-d70f93622ae672a0`). Its WARNINGs were fixed: the database-failure path is now proven, and the integration test no longer depends on database state.
- 2026-10-06: T3 done. Compose `c858fbf` (review `review-a8e0f0609a04e8e4` approved): multi-stage Dockerfiles that run as a non-root `app` user; one PostgreSQL with two databases, no host port, and `pg_isready -h localhost`; curl healthchecks on `/actuator/health`; host ports 18080–18082. Verified by hand: a first build of 72 s, all four containers healthy, an order `PAID`, `lab_orders` scraped.
- 2026-10-06: gauge rework (found while verifying). With the database down, a scrape took 120 s, because each of the 4 series re-ran the query against Hikari's 30 s connection timeout. That would hide F4. Now a scrape never waits: reads return the current snapshot or NaN, a stale snapshot starts one background refresh on a virtual thread, and failures are cached for the TTL. RED: 5 of 9 tests failed. GREEN: 108 tests, twice (parent re-run). Compose check: with Postgres stopped, scrapes take under 0.015 s and NaN appears after the 30 s Hikari timeout. Follow-up: a shorter Hikari `connection-timeout` would surface a hung database sooner. Review follow-ups for T4: `start_period` on the postgres healthcheck; the stack smoke test lives in T5's script.
- 2026-10-06: the parent's re-run of `f155900` failed 1 test: `gaugeReadsNeverWaitForASlowQuery` raced, because it read the gauge after releasing the slow query, and the background refresh could finish first. That commit went in by mistake: the verification chain checked the wrong exit code. Fixed by reading the gauge before releasing the query. 5 targeted runs green, full suite 108 green.
- 2026-10-06: gauge review approved (lineage `review-a349df2d19ac6c51`). Fixed: `MAX_SERVED_AGE = 10 s`, so a hung refresh turns the gauge NaN; the refresh guard is released on any throwable. RED 2 of 11, GREEN; the full suite passed 3 runs with 110 tests (parent re-run checked the mvn exit code).
- 2026-10-06: T4 done.
  - Prometheus `v3.15.0` scrapes every 5 s with a `service` label, on host port 19090.
  - Loki `3.7.8` runs single-binary, internal only, with no healthcheck because the image is distroless.
  - Alloy `v1.20.1` reads Docker logs filtered to the `kintsugi-lab` project and sets a `service` label; it reads the socket fine on WSL.
  - Grafana `13.2.3` runs on 13000 with provisioned datasources and the "Lab — F1" dashboard (4 panels) as the home dashboard.
  - Verified: 7 containers healthy; 3 targets up; Loki has the logs of all three services (it needs about a minute after startup); Grafana lists the dashboard and both datasources are healthy.
- 2026-10-06: review of `0edc7f5..bb0ae2a` (4 lenses, `review-2c30c174eaae82b2`) found security issues, fixed in `db82d86`: every host port bound to `127.0.0.1`, Grafana admin password from `GRAFANA_ADMIN_PASSWORD` (`infra/.env.example`), the Docker socket risk recorded in ADR-008, and a 3 s statement timeout scoped to the count query (`OrderStatusCounts`, not global, so F4 slow queries stay possible).
- 2026-10-06: T5 done. `infra/scripts/verify-f1.sh` (`760e8f1`) and the Compose docs (`f27a131`). Parent run: `RESULT: PASS` in 84 s, 10 checks covering healthy containers, Loki ready with logs, healthy order `PAID`, F1 order 201 `PAYMENT_PENDING`, `lab_orders` pending rose, in-flight gauge above 0, and the Loki WARN line for that order.
- 2026-10-06: final review (`review-4968cdff73666cf3`) approved; WARNINGs fixed: the Javadoc and spec now say exactly what the statement timeout bounds (a lock inside the database) and what it does not (the pool wait, an unreachable server), and the spec uses MUST NOT. Script hardening: curl `--max-time`, no `grep -q` under pipefail, compose errors shown. Tests 113 (parent re-run), script PASS in 88 s (writer run). Open design choice: a datasource socket timeout.

## Next step

Feature done. F1 is visible in Grafana. Next: faults F2–F4 (M1 check), then the fault injector.
