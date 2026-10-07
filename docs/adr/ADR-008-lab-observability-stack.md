# ADR-008: Lab observability stack

## Status

Accepted

## Date

2026-10-06

## Context

The M1 check says every fault F1–F4 must be visible in Grafana ([vision](../vision.md), M1). The week-1 task is a Docker Compose lab with a dashboard that shows F1 while it is active ([feature document](../../odd/tasks/lab-observability.md)). In M2 the agent reads logs and metrics through MCP tools (`getLogs`, `getMetrics`), so the query APIs chosen now (PromQL, LogQL) are also the agent's future inputs.

Today the three lab services expose only `health`. Nothing counts orders by status, and `infra/` is empty. The vision ties traces to the agent's work in M2+, not to the M1 lab.

## Decision

**Option A: Prometheus pulls metrics, Alloy ships logs to Loki, no traces in M1.**

- **Metrics.** Each service adds `io.micrometer:micrometer-registry-prometheus` (managed by Spring Boot 4.1.1 at Micrometer 1.17.1, no explicit version) and sets `management.endpoints.web.exposure.include=health,prometheus`. Prometheus scrapes `/actuator/prometheus`.
- **Logs.** Grafana Alloy reads the Docker container logs through the Docker socket and pushes them to Loki, labelled with the compose service name (`service`).
- **No traces and no OTLP in M1.** The vision ties traces to the agent in M2+. Prometheus 3 and Loki 3 both accept OTLP, so adding an OpenTelemetry pipeline later only adds to what exists; nothing built now has to be undone.
- **Alloy, not Promtail.** Promtail reached end of life on 2026-03-02; Alloy is its supported replacement.

Images (verified 2026-10-06):

| Component | Image |
|-----------|-------|
| Prometheus | `prom/prometheus:v3.15.0` |
| Grafana | `grafana/grafana:13.2.3` |
| Loki | `grafana/loki:3.7.8` |
| Alloy | `grafana/alloy:v1.20.1` |
| PostgreSQL | `postgres:18.6` (as in [ADR-007](ADR-007-lab-persistence-stack.md)) |
| Service build stage | `maven:3.9.16-eclipse-temurin-21-noble` |
| Service runtime stage | `eclipse-temurin:21.0.12.1_1-jre-noble` |

The two base images come from the Docker Hub tag lists, checked on 2026-10-06:

- <https://hub.docker.com/_/maven/tags?name=3.9.16-eclipse-temurin-21> — Maven 3.9.16 matches the Maven Wrapper version in [ADR-006](ADR-006-stack-versions.md).
- <https://hub.docker.com/_/eclipse-temurin/tags?name=21.0.12.1_1-jre> — the latest Temurin 21 JRE build, on Ubuntu Noble (24.04), the same base as the build image.

Exact tags are used instead of floating ones (`21-jre`, `3-eclipse-temurin-21`) so a rebuild produces the same lab.

Host ports, chosen away from ports other local projects already use (5432, 5433 and 8080):

| Service | Host port |
|---------|-----------|
| order-service | 18080 |
| payment-service | 18081 |
| gateway-mock | 18082 |
| inventory-service | 18083 (added with fault F4) |
| fault-injector | 18084 (added with the fault injector, [ADR-009](ADR-009-fault-injector-docker-control.md)) |
| Grafana | 13000 |
| Prometheus | 19090 |

Every published port is bound to `127.0.0.1`, so the lab is not reachable from the network. PostgreSQL, Loki and Alloy are not published on the host; containers reach each other by compose service name.

The fault injector is the one compose service kept out of this stack's signals ([ADR-009](ADR-009-fault-injector-docker-control.md)): Alloy drops its container's logs with a relabel rule, and Prometheus has no scrape target for it. Prometheus and Loki are the agent's data sources, so the injector's own logs and metrics would reveal which fault was injected and when.

## Alternatives considered

- **Option B: everything over OTLP through the OpenTelemetry Collector, with Tempo for traces.** One protocol for all signals and traces from day one, but two more components (Collector, Tempo) and more configuration before any fault is visible. Traces have no consumer until the agent exists in M2.
- **Promtail for logs.** The classic Loki shipper, but end of life since 2026-03-02.
- **`spring-boot:build-image` (Cloud Native Buildpacks) instead of a Dockerfile.** No Dockerfile to maintain, but slower first builds, a large builder image download, and less control over the base image tag. A short multi-stage Dockerfile is easier to read and pin.

## Consequences

### Positive

- Faults show up in the two query languages the agent will use later (PromQL, LogQL).
- Few moving parts: four observability containers, each with one job.
- OTLP and traces can be added later without rework.
- Pinned image tags make the lab reproducible.

### Negative

- No traces: a cross-service latency path (for example F1 from gateway-mock up to order-service) is inferred from metrics and logs, not seen as a span tree.
- Alloy mounts the Docker socket, and so does the fault injector (ADR-009). Even mounted `:ro`, the socket gives full Docker API control, which is root-equivalent on the host. This is accepted for a local lab whose ports are bound to loopback only. If the lab ever leaves the laptop, each needs its own mitigation. For Alloy, a `docker-socket-proxy` in front of the socket that allows only the container list and log endpoints. That proxy does not fit the injector, which recreates services and so needs container create, start, stop and remove: its mitigation is a proxy that also allows those container calls, scoped to the `kintsugi-lab` project's containers, which needs a proxy that filters by compose label rather than by endpoint alone (see ADR-009).
- Image tags must be bumped by hand, with a new ADR or a superseding one.
