# Feature: fault-injector

Locator: `odd/tasks/fault-injector.md` · Engram mirror: `odd/fault-injector/tasks` · Branch: `feat/fault-injector` (from `feat/event-recording-04-gateway-check` at `15c94c6`)

## Objective

Build `lab/fault-injector`, the last M1 item (`docs/vision.md:148`): "Fault injector with F1–F4 and true cause recorded". It is a small REST service that activates a fault, stores the true cause in a table **the agent cannot read**, and restores the lab (`docs/vision.md:25`). It is the ground truth that every diagnosis is scored against, starting with the M2 check: F1–F3 correctly diagnosed (`docs/vision.md:160`).

## Problem and why

Today faults are injected by the verify scripts: REST calls for F1 and F2, and compose environment variables plus a container recreate for F3 and F4. No record exists of what was injected, when, or what the true cause was. Without that record, the agent's diagnoses cannot be scored, so M2's check cannot be run.

## Scope

- In:
  - the `lab/fault-injector` service;
  - its own `faults` database and PostgreSQL user, isolated from every other user;
  - activating F1–F4 through the existing mechanisms;
  - recording the true cause;
  - restore;
  - the compose wiring;
  - keeping the injector invisible to the agent's data sources;
  - switching the verify scripts to inject through the injector;
  - ADR-009;
  - living specs and docs.
- Out:
  - F5–F8;
  - random or scheduled injection campaigns (M4 eval harness);
  - the agent and its MCP tools (M2);
  - the `getDeployments` tool (M2). The redeploys this feature creates are its future evidence.

## Design (option A, approved by the human 2026-10-07)

### Injection mechanisms
- **F1 and F2** use the gateway-mock admin API: `PUT`/`DELETE /admin/behavior`.
- **F3 and F4** recreate a container with fault-specific environment variables. This is a real redeploy, so it leaves real deploy evidence (container recreated at time T).
  - The injector controls Docker through the mounted Docker socket. That makes it root-equivalent on the host, the same risk accepted for Alloy in ADR-008. Every host port stays bound to `127.0.0.1`.
  - Preferred implementation: the Docker CLI with the compose plugin in the injector image. The compose project files are mounted read-only, and the injector runs `docker compose -p kintsugi-lab -f <compose.yaml> up -d --no-deps --no-build --wait <service>` with the fault variables set, then again without them to restore.
  - Pin the CLI and plugin versions in ADR-009. The writer must verify that `--no-build` works with the build contexts as mounted, and fall back to another approach if it does not.
  - Alternative: a Docker Java client that recreates the container itself. Record this as the rejected or fallback alternative in ADR-009.

### Catalog
Fixed, in code. Each entry has a fault id, the target service, the injection parameters, and the **true cause**. The true cause is structured: `root_cause_service`, `root_cause_category` and a `root_cause` sentence.

| Id | Target | Root-cause category | Parameters |
|---|---|---|---|
| F1 | gateway-mock | dependency latency | latency 10 s |
| F2 | gateway-mock | dependency rejecting (rate limit) | 429 |
| F3 | order-service | configuration | wrong payment URL `http://payment-service:9999` |
| F4 | inventory-service | resource exhaustion (DB pool) | pool 2, reservation delay 5 s |

### API
Port 8084, published on `127.0.0.1:18084`.
- `GET /faults/catalog` returns the catalog **without** the true causes.
- `POST /faults {"fault":"F1"}` activates the fault and returns 201 `{id, fault, injectedAt}`. At most one fault may be active at a time: a second one returns 409. An unknown fault returns 400.
- `POST /faults/{id}/restore` restores the lab and sets `restoredAt`. It is idempotent.
- `GET /faults/active` returns the active fault's id and fault id, without the true cause.
- The true cause is readable only through the injector's database. In M4 the eval harness reads it with the `faults` user. No API endpoint ever returns it, so an agent that can call HTTP cannot learn it either.

### Database
The `faults` database is owned by a dedicated `faults` user. `CONNECT` is revoked from `PUBLIC`. The `lab` user that the services use cannot connect to it.
- `injections` table: `id`, `fault_id`, `target_service`, `root_cause_service`, `root_cause_category`, `root_cause`, `parameters` (JSON), `injected_at`, `restored_at`, `status` (`ACTIVE` / `RESTORED` / `FAILED`).
- `injection_events` table, append-only, following the event-recording design: every activation step, restore and failure.
- Rule: nothing is silently dropped. If the database is unreachable, the injector refuses to inject. Injecting without recording the truth would produce an unscorable incident.

### Invisibility to the agent
- Alloy must **drop** the fault-injector container's logs, so they never reach Loki. Prometheus must not scrape it.
- Its logs remain available only through `docker logs`, for the human operator.
- The verify scripts must assert that Loki has no `service="fault-injector"` stream.

### Failure handling
If an activation step fails partway, the injector attempts a restore, records `FAILED` with the cause, and answers 500. On startup, an `ACTIVE` injection left over from a crash is reported in a WARN line and in `GET /faults/active`. It is never restored silently.

### Verify scripts
- `verify-f1.sh` … `verify-f4.sh` inject and restore through the injector API instead of their own commands.
- Each also asserts the truth row: one `injections` row with the right `fault_id` and `root_cause_service`, `RESTORED` after the restore. Read it with the `faults` user.
- Each also asserts that the `lab` user cannot connect to `faults`.

## Acceptance criteria

- `POST /faults` for F1–F4 activates each fault. The existing symptom checks still pass, now injected by the injector.
- Each injection stores exactly one `injections` row with the structured true cause, plus its events. Restore sets `restoredAt`.
- The true cause is never returned by any HTTP endpoint and never appears in Loki or Prometheus. The `lab` user cannot connect to `faults`.
- A second activation while one is active returns 409. Restore is idempotent. A failed activation is restored and recorded as `FAILED`.
- `./mvnw test` passes in fault-injector. Docker control sits behind an interface, faked in unit tests. Real Docker is exercised by the verify scripts.

## Delivery

- `auto-chain` with `feature-branch-chain`. Forecast: about 1,500 lines.
- RDD: on; consent is a standing grant.

## Tasks

- [ ] **T1 — ADR-009 and living spec.** ADR-009 covers the injector's Docker control, the version pins and the isolation of the truth. The spec is `openspec/specs/fault-injector/spec.md`. Route: delegated writer.
- [ ] **T2 — Generated skeleton.** Route: delegated writer.
- [ ] **T3 — Catalog, persistence and API**, with a fake `LabControl`, tests first. Route: delegated writer.
- [ ] **T4 — Real `LabControl`**: a gateway REST client plus compose recreate, and the image with the Docker CLI. Route: delegated writer.
- [ ] **T5 — Compose wiring**: the `faults` database and user, the Alloy drop, the ports; the verify scripts switched to the injector with the truth and isolation assertions; docs and runbooks. Run every script for real. Route: delegated writer.

## Progress

- 2026-10-07: option A chosen by the human; design written; branch created.

## Next step

T1 + T2 via a delegated writer.
