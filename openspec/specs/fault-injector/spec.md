# Fault Injector Specification

> **Never index `openspec/` into the agent's RAG.** This spec names the true cause of every fault. The agent's knowledge base is `docs/runbooks/` only; `openspec/` and `odd/` stay out of it, or the benchmark would hand the agent its answers.

## Purpose

`lab/fault-injector` breaks the lab on purpose and records the ground truth that every diagnosis is scored against. It activates one fault from a fixed catalog, stores the fault's **true cause** where the agent cannot read it, and restores the lab. Design: [feature document](../../../odd/tasks/fault-injector.md) and [ADR-009](../../../docs/adr/ADR-009-fault-injector-docker-control.md). Verified by `./mvnw test` from `lab/fault-injector` (Docker control faked) and by `infra/scripts/verify-f1.sh` … `verify-f4.sh` (real Docker).

It listens on port 8084, published on `127.0.0.1:18084`. It stores its data in the `faults` database, owned by the `faults` user.

## Catalog

The catalog is fixed in code. Each entry holds the fault id, the target service, the injection parameters and the structured true cause.

| Fault | Target | How it is activated | How it is restored |
|-------|--------|---------------------|--------------------|
| F1 | gateway-mock | `PUT /admin/behavior` `{"latencyMs": 10000}` | `DELETE /admin/behavior` |
| F2 | gateway-mock | `PUT /admin/behavior` `{"latencyMs": 0, "errorStatus": 429}` | `DELETE /admin/behavior` |
| F3 | order-service | recreate with `ORDER_PAYMENT_BASEURL=http://payment-service:9999` | recreate without it |
| F4 | inventory-service | recreate with `INVENTORY_POOL_SIZE=2` and `INVENTORY_RESERVATION_DELAY=5s` | recreate without them |

A recreate is `docker compose -p kintsugi-lab -f <compose.yaml> up -d --no-deps --no-build --wait <service>`.

True causes:

| Fault | `root_cause_service` | `root_cause_category` | `root_cause` |
|-------|----------------------|-----------------------|--------------|
| F1 | gateway-mock | `DEPENDENCY_LATENCY` | The payment gateway answers authorizations after 10 s, so payments and the orders waiting on them stay pending. |
| F2 | gateway-mock | `DEPENDENCY_REJECTING` | The payment gateway rejects every authorization with 429 (rate limited), so payments fail. |
| F3 | order-service | `CONFIGURATION` | order-service is deployed with a wrong payment-service URL (`http://payment-service:9999`), so its payment calls fail to connect. |
| F4 | inventory-service | `RESOURCE_EXHAUSTION` | inventory-service runs with a database pool of 2 and reservations that hold a connection for 5 s, so the pool is exhausted and reservations fail. |

## Requirements

### Requirement: List the catalog without the truth

`GET /faults/catalog` MUST return 200 with one entry per catalog fault, in id order: `[{"fault": "F1"}, …, {"fault": "F4"}]`. An entry MUST NOT contain the target service, the parameters or any part of the true cause.

#### Scenario: Catalog lists fault ids only
- GIVEN the injector is running
- WHEN `GET /faults/catalog` is called
- THEN the response status is 200
- AND the body lists `F1`, `F2`, `F3` and `F4`
- AND no field of the body names a service, a parameter or a root cause

### Requirement: Activate a fault

`POST /faults` with body `{"fault": "<id>"}` MUST, in this order:

1. insert one `injections` row with status `ACTIVE`, the fault id, the target service, the structured true cause, the parameters and `injected_at`, and record an `ACTIVATION_STARTED` event;
2. apply the fault's activation steps;
3. record an `ACTIVATED` event;
4. return 201 with `{"id": <injection id>, "fault": "<id>", "injectedAt": "<ISO-8601 instant>"}`.

The response MUST NOT contain the true cause.

#### Scenario: F1 is activated
- GIVEN no active fault
- WHEN `POST /faults` is called with `{"fault": "F1"}`
- THEN the response status is 201 with `id`, `fault` `F1` and `injectedAt`
- AND gateway-mock's behavior is `{"latencyMs": 10000, "errorStatus": null}`
- AND one `injections` row exists with `fault_id` `F1`, `root_cause_service` `gateway-mock` and status `ACTIVE`

#### Scenario: F3 is activated by a redeploy
- GIVEN no active fault
- WHEN `POST /faults` is called with `{"fault": "F3"}`
- THEN order-service is recreated with `KINTSUGI_PAYMENT_BASEURL=http://payment-service:9999` and is healthy
- AND the response status is 201

#### Scenario: Unknown fault
- GIVEN a body that is `{"fault": "F9"}`, `{"fault": null}`, `{}` or malformed JSON
- WHEN `POST /faults` is called with each body
- THEN each response status is 400
- AND no `injections` row is written and the lab is not touched

### Requirement: One active fault at a time

At most one injection MAY be `ACTIVE`. `POST /faults` while one is active MUST return 409, write nothing and leave the lab untouched. Each incident then has exactly one true cause.

#### Scenario: Second activation is refused
- GIVEN F1 is active
- WHEN `POST /faults` is called with `{"fault": "F2"}`
- THEN the response status is 409
- AND gateway-mock's behavior is unchanged
- AND exactly one `injections` row is `ACTIVE`

### Requirement: Read the active fault

`GET /faults/active` MUST return 200 with `{"id": <injection id>, "fault": "<id>", "injectedAt": "<instant>"}` when an injection is `ACTIVE`, and 204 with no body when none is. It MUST NOT return the true cause.

#### Scenario: No active fault
- GIVEN every injection is `RESTORED` or `FAILED`
- WHEN `GET /faults/active` is called
- THEN the response status is 204

### Requirement: Restore is idempotent

`POST /faults/{id}/restore` MUST apply the fault's restore steps, set `restored_at`, set status `RESTORED`, record a `RESTORED` event and return 200 with `{"id", "fault", "injectedAt", "restoredAt"}`. Restoring an injection that is already `RESTORED` MUST return 200 with the same body and change nothing. An unknown id MUST return 404.

#### Scenario: Restore F4
- GIVEN F4 is active
- WHEN `POST /faults/{id}/restore` is called
- THEN inventory-service is recreated with its default pool size and no reservation delay, and is healthy
- AND the row is `RESTORED` with `restored_at` set
- AND the response status is 200

#### Scenario: Restore twice
- GIVEN injection `{id}` is `RESTORED`
- WHEN `POST /faults/{id}/restore` is called again
- THEN the response status is 200 with the same `restoredAt`
- AND no restore step runs and no event is added

### Requirement: Failed activation is restored and recorded

If an activation step fails, the injector MUST attempt the restore steps, set status `FAILED`, record an `ACTIVATION_FAILED` event with the failure cause and the outcome of the restore attempt, and return 500 with `{"error": "activation failed", "id": <injection id>}`. The 500 body MUST NOT contain the true cause. A failed restore (`POST /faults/{id}/restore`) MUST record a `RESTORE_FAILED` event, keep the status `ACTIVE` and return 500, so it can be retried.

#### Scenario: Recreate fails during F3
- GIVEN the compose recreate of order-service fails
- WHEN `POST /faults` is called with `{"fault": "F3"}`
- THEN a restore of order-service is attempted
- AND the row is `FAILED` with an `ACTIVATION_FAILED` event naming the cause
- AND the response status is 500
- AND a new `POST /faults` is accepted

### Requirement: Refuse to inject without recording the truth

The truth MUST be written before the lab is touched. If the `faults` database is unreachable, `POST /faults` MUST return 503 and MUST NOT apply any activation step. An injection without its truth is an incident that cannot be scored.

#### Scenario: Database down
- GIVEN the `faults` database is unreachable
- WHEN `POST /faults` is called with `{"fault": "F1"}`
- THEN the response status is 503
- AND gateway-mock's behavior is unchanged

### Requirement: Truth record and events

- `injections` holds one row per accepted activation: `id`, `fault_id`, `target_service`, `root_cause_service`, `root_cause_category`, `root_cause`, `parameters` (JSON), `injected_at`, `restored_at`, `status` (`ACTIVE`, `RESTORED` or `FAILED`).
- `injection_events` is append-only. It records every step of an injection's life with the injection id, the event type (`ACTIVATION_STARTED`, `ACTIVATED`, `ACTIVATION_FAILED`, `RESTORED`, `RESTORE_FAILED`), the time and a detail. Rows are never updated or deleted.
- Nothing is silently dropped: a step that runs has an event, and a step that fails has an event with its cause.

#### Scenario: Events of a clean F2 cycle
- GIVEN F2 was activated and then restored
- WHEN `injection_events` is read for that injection
- THEN it holds `ACTIVATION_STARTED`, `ACTIVATED` and `RESTORED`, in time order

### Requirement: The truth stays invisible to the agent

- No HTTP endpoint MUST ever return `root_cause_service`, `root_cause_category`, `root_cause`, the target service or the parameters.
- The `faults` database MUST be owned by the `faults` user, with `CONNECT` revoked from `PUBLIC`. The `lab` user MUST NOT be able to connect to it. The eval harness (M4) reads it with the `faults` user; the agent's read-only `queryDb` tool never gets those credentials.
- Loki MUST have no `service="fault-injector"` stream: Alloy drops the injector container's logs. They stay readable with `docker logs` for the operator.
- Prometheus MUST NOT scrape the injector. `/actuator/prometheus` stays exposed for consistency only.

#### Scenario: lab user cannot read the truth
- GIVEN the lab is running
- WHEN the `lab` user connects to the `faults` database
- THEN the connection is refused

#### Scenario: No injector logs in Loki
- GIVEN a fault was activated and restored
- WHEN Loki is queried for `{service="fault-injector"}`
- THEN no stream is returned

### Requirement: Leftover active injection at startup

On startup, if an `injections` row is `ACTIVE` (for example after a crash), the injector MUST log one `WARN` line with its injection id and fault id, MUST report it through `GET /faults/active`, and MUST NOT restore it on its own. The operator restores it with `POST /faults/{id}/restore`. Until then `POST /faults` returns 409.

#### Scenario: Crash during F1
- GIVEN an `injections` row for F1 is `ACTIVE` and the injector restarts
- WHEN the injector has started
- THEN a `WARN` line names the injection id and `F1`
- AND `GET /faults/active` returns that injection
- AND gateway-mock's behavior is unchanged

## Known limitations

- **Root-equivalent socket.** The injector mounts the Docker socket ([ADR-009](../../../docs/adr/ADR-009-fault-injector-docker-control.md)). Acceptable only because every lab port is bound to `127.0.0.1`.
- **No authentication.** The API has no auth; it is published on loopback only.
- **Fixed catalog.** F1–F4 only; F5–F8 and injection campaigns come later.
