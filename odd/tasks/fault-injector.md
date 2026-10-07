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
- `GET /faults/catalog` returns the fault ids **only**. Each fault's target service is also its root-cause service, so exposing targets or parameters would leak part of the answer (decided 2026-10-07, stricter than the first draft).
- `POST /faults {"fault":"F1"}` activates the fault and returns 201 `{id, fault, injectedAt}`. At most one fault may be active at a time: a second one returns 409. An unknown fault returns 400.
- `POST /faults/{id}/restore` restores the lab and sets `restoredAt`. It is idempotent.
- `GET /faults/active` returns the active injection id and fault id without the true cause, or 204 when nothing is active. A `POST /faults` while the `faults` database is down answers 503. A failed restore keeps the injection `ACTIVE` and answers 500, so the restore can be retried.
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
- Slices: tracker `feat/fault-injector` (doc, ADR-009, spec, skeleton); PR 1 `-01-core`: design fixes, catalog, domain, persistence; PR 2 `-02-api`: control port, service, API; PR 3 `-03-lab-control`: restore-race fix plus T4. T4 is a size exception (about 940 lines): `RoutingLabControl` and `DockerLabControlConfiguration` wire both controls together, so no clean split exists. About a third is tests. Then T5.
- RDD: on; consent is a standing grant.

## Tasks

- [x] **T1 — ADR-009 and living spec.** ADR-009 covers the injector's Docker control, the version pins and the isolation of the truth. The spec is `openspec/specs/fault-injector/spec.md`. Route: delegated writer.
- [x] **T2 — Generated skeleton.** Route: delegated writer.
- [x] **T3 — Catalog, persistence and API**, with a fake `LabControl`, tests first. Route: delegated writer.
- [x] **T4 — Real `LabControl`**: a gateway REST client plus compose recreate, and the image with the Docker CLI. Route: delegated writer.
- [ ] **T5 — Compose wiring**: the `faults` database and user, the Alloy drop, the ports; the verify scripts switched to the injector with the truth and isolation assertions; docs and runbooks. Run every script for real. Route: delegated writer.

## Progress

- 2026-10-07: option A chosen by the human; design written; branch created.
- 2026-10-07: T1 + T2 done by a delegated writer.
  - ADR-009 pins the Docker CLI at 29.8.2 and Compose at v5.6.0, taken from the `docker:29.8.2-cli` image; the local Engine is 29.4.0 (API 1.54).
  - `--no-build` recreates a service from its existing image without the build context (checked on v5.1.3; to recheck on v5.6.0 in T4).
  - Living spec `openspec/specs/fault-injector/spec.md`, with a note never to index `openspec/` into the agent's RAG; the runbooks README says the knowledge base is `docs/runbooks/` only.
  - Accepted decisions: the catalog returns ids only; `GET /faults/active` answers 204 when nothing is active; 503 when the `faults` database is down; categories `DEPENDENCY_LATENCY`, `DEPENDENCY_REJECTING`, `CONFIGURATION`, `RESOURCE_EXHAUSTION`.
  - Skeleton: mvn exit 0 (parent re-run).
- 2026-10-07: review of T1+T2 (`review-028bf5d4411d2ff7`) approved, but it found real design flaws, fixed in the ADR and spec before any code:
  - **Superuser bypass:** the `lab` role is the image's superuser, which ignores `REVOKE CONNECT`. Services move to a non-superuser `lab_app` role (in T5), `faults` gets its own non-superuser owner, and the future agent role must be non-superuser too.
  - **New status `RESTORE_PENDING`:** a failed activation whose restore also fails stays blocking.
  - **One active injection:** enforced by a unique index on the constant `((true)) WHERE status IN ('ACTIVE','RESTORE_PENDING')`. An index on the status column would allow one row per status.
  - **Bounded recreate:** `--wait-timeout`, set by the property `kintsugi.lab-control.recreate-wait-timeout`, default 120 s.
  - **Naming:** a table maps each compose variable to its container variable.
  - **Catalog:** returns ids only.
- 2026-10-07: T3 done by a delegated writer (tests first).
  - Catalog, domain, the V1/V2 migrations, append-only events, `FaultInjectionService`, the API, and the `unsupported` `LabControl` mode until T4.
  - Tests: concurrency (5×, real HTTP, a barrier), database down → 503, and a test that no response body contains truth values.
  - A false positive (parameter "2" matching "F2") fixed the leak test.
  - mvn exit 0, 48 tests, two runs (parent re-run).
  - Accepted: if the `ACTIVATED` event cannot be written, the injector answers 201 and logs an ERROR; the row stays ACTIVE and blocking.
- 2026-10-07: reviews of `-01-core` (`review-2b23fbe83be51ac9`; its WARNING came from the slicing) and `-02-api` (`review-88ac9cd2e949f559`) approved. The `-02-api` WARNING (a failed restore could overwrite RESTORED) is fixed with a conditional update. The guard already existed: a concurrent-restore test proves it, and a mutation check (removing the guard turns the test RED) shows the test catches it. `compensate()` now catches every RuntimeException.
- 2026-10-07: T4 done by a delegated writer.
  - `HttpGatewayControl` (F1/F2), `ComposeRecreateControl` (F3/F4, argv only, process timeout) and `RoutingLabControl`; mode `docker` vs `unsupported`.
  - Image: Docker CLI 29.8.2, plus Compose v5.6.0 from `docker/compose-bin:v5.6.0`. The `docker:29.8.2-cli` image actually bundles v5.5.1; ADR-009 is corrected.
  - The container runs as uid 999; with `--group-add <socket gid>` it reaches the Engine (client 29.8.2, server 29.4.0, API 1.54). `--no-build --wait` was verified on v5.6.0 in a throwaway project.
  - 79 tests, two runs (parent re-run, mvn exit 0).
  - T5 needs: `group_add` with the socket gid, the socket mount, the repo mounted read-only at its host path, `KINTSUGI_LABCONTROL_MODE=docker` and `KINTSUGI_LABCONTROL_COMPOSEFILE`.

## Next step

T5 (compose wiring, non-superuser roles, Alloy drop, verify scripts switched to the injector with truth and isolation assertions, docs) via a delegated writer.
