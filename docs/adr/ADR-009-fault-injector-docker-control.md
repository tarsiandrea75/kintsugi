# ADR-009: Fault injector Docker control and isolation of the truth

## Status

Accepted

## Date

2026-10-07

## Context

The last M1 item is "Fault injector with F1–F4 and true cause recorded" ([vision](../vision.md), M1). It is a small REST service, `lab/fault-injector`, that activates a fault, stores its true cause where the agent cannot read it, and restores the lab ([feature document](../../odd/tasks/fault-injector.md)). Every diagnosis is scored against that record, starting with the M2 check (F1–F3 correctly diagnosed).

Today the verify scripts inject the faults themselves:

| Fault | Today's mechanism |
|-------|-------------------|
| F1, F2 | `PUT`/`DELETE /admin/behavior` on gateway-mock |
| F3 | compose variable `ORDER_PAYMENT_BASEURL`, then `docker compose up -d --no-deps --wait order-service` |
| F4 | compose variables `INVENTORY_POOL_SIZE` and `INVENTORY_RESERVATION_DELAY`, then the same recreate of inventory-service |

Nothing records what was injected, when, or why. Two questions follow: how a container can redeploy another container, and how to keep the truth away from an agent that will read logs, metrics, runbooks and HTTP APIs.

## Decision

**Option A: the injector drives Docker through the mounted socket with the Docker CLI and the compose plugin for F3 and F4, and calls the gateway-mock admin API for F1 and F2.**

### Injection mechanisms

- **F1 and F2** call gateway-mock's admin API (`PUT /admin/behavior` to inject, `DELETE /admin/behavior` to restore), as the scripts do today.
- **F3 and F4** run, inside the injector container:

  ```bash
  docker compose -p kintsugi-lab -f <compose.yaml> up -d --no-deps --no-build --wait <service>
  ```

  first with the fault variables set, then again without them to restore. The compose project files are mounted read-only.
- Why a real recreate:
  - It leaves real deploy evidence: the container is recreated at time T with a new configuration. The M2 `getDeployments` tool will read that evidence, as an SRE reads a deploy log.
  - F3 stays "wrong configuration via env", as the vision describes it. No fault hook is added to order-service's code.
- `--no-build` was checked on 2026-10-07 with the local Compose v5.1.3: a service whose `build:` context does not exist starts from its existing image (`<project>-<service>`) with exit code 0. The injector therefore needs the compose file, not the services' source trees. T4 confirms it again with the pinned plugin.

### Pinned versions (verified 2026-10-07)

| Component | Version | Source |
|-----------|---------|--------|
| Docker CLI | 29.8.2 | `docker:29.8.2-cli`, the latest stable `-cli` tag (29.9.0 is still `rc.1`) |
| Docker Compose plugin | v5.6.0 | the version bundled in `docker:29.8.2-cli` (`DOCKER_COMPOSE_VERSION 5.6.0`), also the latest GitHub release (2026-10-02) |
| Docker Engine on the development machine | 29.4.0, API 1.54 (minimum 1.40) | `docker version` on this machine |

Sources:

- <https://hub.docker.com/_/docker/tags?name=-cli> — tag list.
- <https://github.com/docker-library/docker/blob/master/versions.json> and <https://github.com/docker-library/docker/blob/master/29/cli/Dockerfile> — the CLI and compose versions inside the image, and the binary paths `/usr/local/bin/docker` and `/usr/local/libexec/docker/cli-plugins/docker-compose`.
- <https://github.com/docker/compose/releases/tag/v5.6.0> and <https://github.com/docker/cli/releases/tag/v29.8.2>.

How the image uses them:

- The injector's runtime stage stays `eclipse-temurin:21.0.12.1_1-jre-noble` ([ADR-008](ADR-008-lab-observability-stack.md)).
- It copies the two static binaries from `docker:29.8.2-cli` (`COPY --from=docker:29.8.2-cli ...`), so the JVM base image is unchanged and no package repository is added.
- API compatibility: the Docker CLI negotiates the API version down to the daemon's. A 29.8 client talks to the local 29.4 Engine (API 1.54).

### Isolation of the truth

- **Separate database and user.** The truth lives in its own `faults` database, owned by a dedicated `faults` user. `CONNECT` on it is revoked from `PUBLIC`, so the `lab` user that the services use cannot connect. The agent's future read-only `queryDb` tool must never be given the `faults` credentials.
- **Not in any HTTP answer.** No endpoint ever returns the true cause. `GET /faults/catalog` and `GET /faults/active` return fault ids and targets only. In M4 the eval harness reads the truth with the `faults` user.
- **Not in Loki.** Alloy drops the fault-injector container's logs, so they never reach Loki. The operator reads them with `docker logs`.
- **Not in Prometheus.** Prometheus does not scrape the injector. The service still exposes `/actuator/prometheus`, for consistency with the other services.
- **Not in the knowledge base.** The agent's knowledge base is `docs/runbooks/` only. `openspec/` and `odd/` name the true causes and are never indexed into the agent's RAG.

### Rules

- **Refuse to inject when the truth cannot be recorded.** If the `faults` database is unreachable, `POST /faults` fails before touching the lab. An injection without its truth would be an incident that cannot be scored.
- **One active fault at a time.** A second activation returns 409. Each incident has exactly one true cause, which keeps the scoring unambiguous.

## Alternatives considered

- **Option B: REST knobs inside each service.** Every fault becomes an admin endpoint, as on gateway-mock. No Docker socket is needed, but:
  - F3 would no longer be a configuration fault: it would be a runtime switch that no deploy history shows;
  - fault hooks would be added to order-service's code;
  - `getDeployments` would have nothing real to find.
- **A Docker Java client (for example docker-java).** It recreates the container through the Engine API, without a CLI in the image. But it must re-implement what compose already does: resolve the compose model and variables, keep the compose labels, and wait for health. Any drift from compose produces a container compose no longer recognizes. Kept as the fallback if the CLI approach fails in T4.
- **A host-side script.** The injector would ask a script on the host to run `docker compose`. No socket inside a container, but a second process to run and secure. The injector would also no longer be one service with one API.

## Consequences

### Positive

- F3 and F4 produce real redeploys, the evidence a production SRE would use.
- The scripts and the injector use the same compose commands, so behavior does not change when the scripts switch to the injector.
- The truth is recorded before anything breaks, and it is readable only by the `faults` user.

### Negative

- The mounted Docker socket is root-equivalent on the host, the same risk accepted for Alloy in ADR-008. It is accepted for a lab whose ports are bound to `127.0.0.1`. If the lab ever leaves the laptop, the mitigation is a socket proxy that allows only the endpoints compose needs.
- The injector image is larger: it adds two static binaries, the Docker CLI and the compose plugin, to the JRE image.
- The CLI and the plugin are pinned by hand and must be bumped with a new ADR or a superseding one.
- Invisibility depends on configuration in three places (Alloy, Prometheus and the database grants). The verify scripts must assert each one, or a change could quietly leak the truth.
