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
- [ ] **T3 — Images and compose.** The Dockerfiles; `infra/compose.yaml` with PostgreSQL, its init script, and the three services; healthchecks. Verify by bringing it up and placing a `PAID` order. Route: delegated writer.
- [ ] **T4 — Observability stack.** Prometheus scrape config, Loki, Alloy config, Grafana provisioning and the "Lab — F1" dashboard. Verify that targets are up and that logs reach Loki. Route: delegated writer.
- [ ] **T5 — F1 check and docs.** `infra/scripts/verify-f1.sh`, run and passing; `docs/setup.md` (Compose section, ports, how to view the dashboard). Route: delegated writer.

## Checks

- `./mvnw test` in each `lab/*` service
- `docker compose -f infra/compose.yaml config` (syntax), `up -d`, and the healthchecks
- `infra/scripts/verify-f1.sh`

## Progress

- 2026-10-06: research done (versions, Boot integration, ports); design approved; branch created.
- 2026-10-06: T1 + T2 done by a delegated writer. Base images pinned: `maven:3.9.16-eclipse-temurin-21-noble` (build) and `eclipse-temurin:21.0.12.1_1-jre-noble` (runtime). Gauge `lab.orders`, exposed as `lab_orders{status}`: one series per status including 0, one shared query cached for 1 s, NaN when the database query fails. Boot 4.1.1 disables metrics export in tests; `@AutoConfigureMetrics` turns it back on. RED: 404 on `/actuator/prometheus`, and order-service tests did not compile. GREEN: order-service 103, payment-service 49, gateway-mock 38 (parent re-run). The spec requires `verify-f1.sh` to reset the gateway even when a check fails.

## Next step

T3 (images and compose) via a delegated writer.
