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

Two layers of names are involved in F3 and F4. The injector sets the **compose variables** (interpolated by `infra/compose.yaml`); compose turns them into the **container variables** that Spring Boot reads:

| Compose variable (set by the injector) | Container variable (read by the service) | Default |
|----------------------------------------|------------------------------------------|---------|
| `ORDER_PAYMENT_BASEURL` | `KINTSUGI_PAYMENT_BASEURL` (order-service) | `http://payment-service:8081` |
| `INVENTORY_POOL_SIZE` | `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` (inventory-service) | `10` |
| `INVENTORY_RESERVATION_DELAY` | `KINTSUGI_INVENTORY_RESERVATIONDELAY` (inventory-service) | `0s` |

The catalog's parameters use the compose names, because those are what the injector sets. Assertions on a running container use the container names.

Nothing records what was injected, when, or why. Two questions follow: how a container can redeploy another container, and how to keep the truth away from an agent that will read logs, metrics, runbooks and HTTP APIs.

## Decision

**Option A: the injector drives Docker through the mounted socket with the Docker CLI and the compose plugin for F3 and F4, and calls the gateway-mock admin API for F1 and F2.**

### Injection mechanisms

- **F1 and F2** call gateway-mock's admin API (`PUT /admin/behavior` to inject, `DELETE /admin/behavior` to restore), as the scripts do today.
- **F3 and F4** run, inside the injector container:

  ```bash
  docker compose -p kintsugi-lab -f <compose.yaml> up -d --no-deps --no-build --wait --wait-timeout <N> <service>
  ```

  first with the fault variables set, then again without them to restore. The compose project files are mounted read-only.
- **Bounded recreate.** `<N>` is the property `kintsugi.lab-control.recreate-wait-timeout`, default `120s`: a Spring service in this lab becomes healthy in well under a minute, and its healthcheck allows `start_period` 60 s. The injector also kills the CLI process if it outlives the timeout plus a small margin. A non-zero exit, including a `--wait-timeout` expiry, is a failed step: activation then follows the failed-activation rule, and a restore keeps the injection blocking.
- Why a real recreate:
  - It leaves real deploy evidence: the container is recreated at time T with a new configuration. The M2 `getDeployments` tool will read that evidence, as an SRE reads a deploy log.
  - F3 stays "wrong configuration via env", as the vision describes it. No fault hook is added to order-service's code.
- `--no-build` was checked on 2026-10-07 with the local Compose v5.1.3: a service whose `build:` context does not exist starts from its existing image (`<project>-<service>`) with exit code 0. The injector therefore needs the compose file, not the services' source trees. T4 confirmed it with the pinned v5.6.0 inside the injector image: `up -d --no-deps --no-build --wait` of gateway-mock in a throwaway project, with the repository mounted read-only, exited 0 with the container healthy.

### Pinned versions (verified 2026-10-07)

| Component | Version | Source |
|-----------|---------|--------|
| Docker CLI | 29.8.2 | `docker:29.8.2-cli`, the latest stable `-cli` tag (29.9.0 is still `rc.1`) |
| Docker Compose plugin | v5.6.0 | `docker/compose-bin:v5.6.0`, Compose's own binary image; the latest GitHub release (2026-10-02). Corrected in T4: the published `docker:29.8.2-cli` image (built 2026-09-30) still bundles v5.5.1, although docker-library's `versions.json` already names 5.6.0 |
| Docker Engine on the development machine | 29.4.0, API 1.54 (minimum 1.40) | `docker version` on this machine |

Sources:

- <https://hub.docker.com/_/docker/tags?name=-cli> — tag list.
- <https://github.com/docker-library/docker/blob/master/versions.json> and <https://github.com/docker-library/docker/blob/master/29/cli/Dockerfile> — the CLI version inside the image, and the binary paths `/usr/local/bin/docker` and `/usr/local/libexec/docker/cli-plugins/docker-compose`. The compose version there runs ahead of the published image: `docker run --rm docker:29.8.2-cli docker compose version` printed v5.5.1 on 2026-10-07.
- <https://hub.docker.com/r/docker/compose-bin/tags> — `v5.6.0` holds the single static binary `/docker-compose`.
- <https://github.com/docker/compose/releases/tag/v5.6.0> and <https://github.com/docker/cli/releases/tag/v29.8.2>.

How the image uses them:

- The injector's runtime stage stays `eclipse-temurin:21.0.12.1_1-jre-noble` ([ADR-008](ADR-008-lab-observability-stack.md)).
- It copies two static binaries, so the JVM base image is unchanged and no package repository is added: the CLI from `docker:29.8.2-cli` (`/usr/local/bin/docker`), and the compose plugin from `docker/compose-bin:v5.6.0` (`/docker-compose`) into `/usr/local/libexec/docker/cli-plugins/docker-compose`, one of the CLI's default plugin directories.
- Checked in T4 inside the built image: `docker --version` is 29.8.2, `docker compose version` is v5.6.0, and through the mounted socket the client talks to the local Engine 29.4.0 (API 1.54).
- API compatibility: the Docker CLI negotiates the API version down to the daemon's. A 29.8 client talks to the local 29.4 Engine (API 1.54).

### Socket access without root

The injector runs as the image's unprivileged `app` user, not as root. The Docker socket is `root:<docker group>` with mode `660`, so `app` can use it only as a member of the socket's group. That group id belongs to the host (1001 on the development machine) and differs between hosts, so the image does not hard-code it:

- compose adds it at run time with `group_add`, from a variable such as `DOCKER_GID` set to the output of `stat -c %g /var/run/docker.sock` (task T5);
- checked in T4: without the group, `docker compose ps` fails with "permission denied while trying to connect to the docker API"; with `--group-add 1001` it succeeds.

Group membership on the socket is as root-equivalent as running as root (see Negative consequences); `group_add` only keeps the JVM process itself unprivileged inside its container.

The compose file must be mounted **at its host path** (for example `/home/<user>/kintsugi:/home/<user>/kintsugi:ro`). Compose resolves the file's relative bind mounts (`./postgres/init`, `./alloy/config.alloy`, …) against the file's own directory, and the Docker daemon reads those resolved paths on the host. Mounted elsewhere, a recreate would hand the daemon paths that do not exist on the host.

### Isolation of the truth

- **Separate database and roles, none of them a superuser except the bootstrap one.** A PostgreSQL superuser ignores `REVOKE CONNECT`, so a grant only isolates the truth from roles that are **not** superusers. Today the services connect as `lab`, the image's `POSTGRES_USER`, which is a superuser and could read `faults`. Therefore:
  - The compose `postgres` keeps a **bootstrap superuser** (`POSTGRES_USER`). Only the init scripts in `infra/postgres/init/` use it. No service, script assertion or tool connects with it.
  - The services connect with a **non-superuser application role**, `lab_app` (`NOSUPERUSER NOCREATEDB NOCREATEROLE`). It owns `orders`, `payments` and `inventory`, and nothing else.
  - `faults` is owned by a **non-superuser `faults` role**. `CONNECT` on `faults` is revoked from `PUBLIC`, so only `faults` (and the bootstrap superuser) can connect.
  - The agent's future read-only role (M2, behind `queryDb`) MUST be a non-superuser and MUST NOT have `CONNECT` on `faults`. It must never be given the `faults` credentials.
  - The verify scripts MUST assert both halves: the service role is not a superuser (`SELECT rolsuper FROM pg_roles WHERE rolname = 'lab_app'` is false), and it cannot connect to `faults`.
  - Task T5 implements this in `infra/postgres/init/` and in the services' compose credentials.
- **Not in any HTTP answer.** No endpoint ever returns the true cause. `GET /faults/catalog` returns fault ids only, and `GET /faults/active` returns the injection id, the fault id, its status and times. Neither returns a target service or a parameter, because each fault's target is also its root-cause service. In M4 the eval harness reads the truth with the `faults` role.
- **Not in Loki.** Alloy drops the fault-injector container's logs, so they never reach Loki. The operator reads them with `docker logs`.
- **Not in Prometheus.** Prometheus does not scrape the injector. The service still exposes `/actuator/prometheus`, for consistency with the other services.
- **Not in the knowledge base.** The agent's knowledge base is `docs/runbooks/` only. `openspec/` and `odd/` name the true causes and are never indexed into the agent's RAG.

### Rules

- **Refuse to inject when the truth cannot be recorded.** If the `faults` database is unreachable, `POST /faults` fails before touching the lab. An injection without its truth would be an incident that cannot be scored.
- **One blocking injection at a time.** An injection is blocking while its status is `ACTIVE` or `RESTORE_PENDING`. A second activation returns 409. Each incident has exactly one true cause, which keeps the scoring unambiguous.
  - The database enforces it, not only the code: a partial unique index on a constant, `CREATE UNIQUE INDEX ... ON injections ((true)) WHERE status IN ('ACTIVE', 'RESTORE_PENDING')`, admits at most one blocking row. An index on `status` itself would still admit one `ACTIVE` and one `RESTORE_PENDING` row. Two concurrent `POST /faults` therefore give exactly one 201 and one 409.
- **A lab that may still be broken stays blocking.** If an activation step fails and its compensating restore succeeds, the injection is `FAILED` and no longer blocks. If the compensating restore also fails, or a later `POST /faults/{id}/restore` fails, the injection is `RESTORE_PENDING`: the lab may still be broken, so no new fault may be layered on top of it. `POST /faults` answers 409 until a restore succeeds.

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
- The truth is recorded before anything breaks, and it is readable only by the `faults` role. Because no service or agent role is a superuser, the `REVOKE CONNECT` actually holds.
- A failed restore never frees the lab for a second fault, so an incident never has two overlapping causes.

### Negative

- The mounted Docker socket is root-equivalent on the host, the same risk accepted for Alloy in ADR-008. It is accepted for a lab whose ports are bound to `127.0.0.1`. If the lab ever leaves the laptop, the mitigation is a socket proxy that allows only the endpoints compose needs.
- The injector image is larger: it adds two static binaries, the Docker CLI and the compose plugin, to the JRE image.
- The CLI and the plugin are pinned by hand and must be bumped with a new ADR or a superseding one.
- Invisibility depends on configuration in three places (Alloy, Prometheus and the database roles and grants). The verify scripts must assert each one, or a change could quietly leak the truth.
- T5 must move the services from the `lab` superuser to `lab_app`, which touches every service's compose credentials and the init scripts. Existing data volumes were initialized without `lab_app`, so the lab needs `down -v` once.
- A `RESTORE_PENDING` injection blocks every new fault until an operator gets a restore through. That is deliberate: a stuck lab is visible, a silently double-faulted lab is not.
