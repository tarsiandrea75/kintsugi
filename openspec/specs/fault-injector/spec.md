# Fault Injector Specification

> **Never index `openspec/` into the agent's RAG.** This spec names the true cause of every fault. The agent's knowledge base is `docs/runbooks/` only; `openspec/` and `odd/` stay out of it, or the benchmark would hand the agent its answers.

## Purpose

`lab/fault-injector` breaks the lab on purpose and records the ground truth that every diagnosis is scored against. It activates one fault from a fixed catalog, stores the fault's **true cause** where the agent cannot read it, and restores the lab. Design: [feature document](../../../odd/tasks/fault-injector.md) and [ADR-009](../../../docs/adr/ADR-009-fault-injector-docker-control.md). Verified by `./mvnw test` from `lab/fault-injector` (Docker control faked) and by `infra/scripts/verify-f1.sh` … `verify-f4.sh` (real Docker).

It listens on port 8084, published on `127.0.0.1:18084`. It stores its data in the `faults` database, owned by the non-superuser `faults` role.

## Catalog

The catalog is fixed in code. Each entry holds the fault id, the target service, the injection parameters and the structured true cause.

| Fault | Target | How it is activated | How it is restored |
|-------|--------|---------------------|--------------------|
| F1 | gateway-mock | `PUT /admin/behavior` `{"latencyMs": 10000}` | `DELETE /admin/behavior` |
| F2 | gateway-mock | `PUT /admin/behavior` `{"latencyMs": 0, "errorStatus": 429}` | `DELETE /admin/behavior` |
| F3 | order-service | recreate with compose variable `ORDER_PAYMENT_BASEURL=http://payment-service:9999` | recreate without it |
| F4 | inventory-service | recreate with compose variables `INVENTORY_POOL_SIZE=2` and `INVENTORY_RESERVATION_DELAY=5s` | recreate without them |

A recreate is `docker compose -p kintsugi-lab -f <compose.yaml> up -d --no-deps --no-build --wait --wait-timeout <N> <service>`, where `<N>` is the property `kintsugi.lab-control.recreate-wait-timeout` (default `120s`). A non-zero exit, including a wait timeout, is a failed step.

The parameters use the **compose variable** names, which the injector sets. `infra/compose.yaml` maps each one to the **container variable** the service reads:

| Compose variable | Container variable | Service |
|------------------|--------------------|---------|
| `ORDER_PAYMENT_BASEURL` | `KINTSUGI_PAYMENT_BASEURL` | order-service |
| `INVENTORY_POOL_SIZE` | `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` | inventory-service |
| `INVENTORY_RESERVATION_DELAY` | `KINTSUGI_INVENTORY_RESERVATIONDELAY` | inventory-service |

True causes:

| Fault | `root_cause_service` | `root_cause_category` | `root_cause` |
|-------|----------------------|-----------------------|--------------|
| F1 | gateway-mock | `DEPENDENCY_LATENCY` | The payment gateway answers authorizations after 10 s, so payments and the orders waiting on them stay pending. |
| F2 | gateway-mock | `DEPENDENCY_REJECTING` | The payment gateway rejects every authorization with 429 (rate limited), so payments fail. |
| F3 | order-service | `CONFIGURATION` | order-service is deployed with a wrong payment-service URL (`http://payment-service:9999`), so its payment calls fail to connect. |
| F4 | inventory-service | `RESOURCE_EXHAUSTION` | inventory-service runs with a database pool of 2 and reservations that hold a connection for 5 s, so the pool is exhausted and reservations fail. |

## Lab control

`kintsugi.lab-control.mode` selects how the injector acts on the lab:

| Mode | Used by | Behavior |
|------|---------|----------|
| `docker` | the compose deployment | F1 and F2 call gateway-mock's admin API; F3 and F4 run a compose recreate. Each fault goes to the control of its catalog mechanism (`GATEWAY_BEHAVIOR` or `COMPOSE_RECREATE`). |
| `unsupported` (default) | tests, a bare `java -jar` | every activation fails before touching the lab and is recorded `FAILED`; restore does nothing |

Properties of the `docker` mode:

| Property | Default | Meaning |
|----------|---------|---------|
| `kintsugi.lab-control.gateway-base-url` | `http://gateway-mock:8082` | gateway-mock's base URL |
| `kintsugi.lab-control.gateway-connect-timeout` | `2s` | connect timeout of each gateway-mock call |
| `kintsugi.lab-control.gateway-read-timeout` | `5s` | read timeout of each gateway-mock call |
| `kintsugi.lab-control.compose-file` | none, required | the lab's `infra/compose.yaml`, mounted at its host path |
| `kintsugi.lab-control.compose-project` | `kintsugi-lab` | the `-p` project name |
| `kintsugi.lab-control.recreate-wait-timeout` | `120s` | `--wait-timeout`, in whole seconds, at least 1 |

- **Gateway faults.** Activation is `PUT /admin/behavior` with the catalog parameters as the JSON body (F1 `{"latencyMs": 10000}`, F2 `{"latencyMs": 0, "errorStatus": 429}`). Restore is `DELETE /admin/behavior`. An error status, a timeout or a connection failure is a failed step.
- **Recreate faults.** The injector runs exactly this argv, with no shell: `docker`, `compose`, `-p`, `<compose-project>`, `-f`, `<compose-file>`, `up`, `-d`, `--no-deps`, `--no-build`, `--wait`, `--wait-timeout`, `<N>`, `<service>`. Activation adds the fault's compose variables to the inherited environment. Restore runs the same argv with those variables removed, so compose falls back to its defaults. The process is killed if it runs longer than `<N>` plus 30 s. A non-zero exit, or a kill, is a failed step whose recorded cause ends with the tail of stderr.
- **Docker access.** The image carries the Docker CLI 29.8.2 and the compose plugin v5.6.0 ([ADR-009](../../../docs/adr/ADR-009-fault-injector-docker-control.md)). It runs as an unprivileged user; the compose deployment mounts the Docker socket and adds the socket's group with `group_add`.

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
- THEN order-service is recreated from compose variable `ORDER_PAYMENT_BASEURL=http://payment-service:9999`
- AND its container has `KINTSUGI_PAYMENT_BASEURL=http://payment-service:9999` and is healthy
- AND the response status is 201

#### Scenario: Recreate does not become healthy in time
- GIVEN order-service does not become healthy within `kintsugi.lab-control.recreate-wait-timeout`
- WHEN `POST /faults` is called with `{"fault": "F3"}`
- THEN the activation step counts as failed
- AND the failed-activation rule below applies

#### Scenario: Unknown fault
- GIVEN a body that is `{"fault": "F9"}`, `{"fault": null}`, `{}` or malformed JSON
- WHEN `POST /faults` is called with each body
- THEN each response status is 400
- AND no `injections` row is written and the lab is not touched

### Requirement: Real lab control

With `kintsugi.lab-control.mode=docker` the injector MUST apply and undo faults as described in [Lab control](#lab-control): the gateway-mock admin API for F1 and F2, the recreate argv for F3 and F4. It MUST NOT start without `kintsugi.lab-control.compose-file`, and MUST NOT run any command through a shell.

#### Scenario: Missing compose file
- GIVEN `kintsugi.lab-control.mode=docker` and no `kintsugi.lab-control.compose-file`
- WHEN the injector starts
- THEN startup fails with an error naming `kintsugi.lab-control.compose-file`

#### Scenario: F4 restore removes the fault variables
- GIVEN F4 is active
- WHEN its restore runs
- THEN the recreate argv is the same as the activation's
- AND `INVENTORY_POOL_SIZE` and `INVENTORY_RESERVATION_DELAY` are absent from the process environment

### Requirement: One blocking injection at a time

An injection is **blocking** while its status is `ACTIVE` or `RESTORE_PENDING`: the lab is, or may still be, broken. At most one injection MAY be blocking. `POST /faults` while one is blocking MUST return 409, write nothing and leave the lab untouched. Each incident then has exactly one true cause.

The database MUST enforce it with a partial unique index that admits at most one row with status `ACTIVE` or `RESTORE_PENDING` (an index on a constant expression, so one `ACTIVE` and one `RESTORE_PENDING` row cannot coexist either). A unique violation on insert MUST answer 409, like the check before it.

#### Scenario: Second activation is refused
- GIVEN F1 is active
- WHEN `POST /faults` is called with `{"fault": "F2"}`
- THEN the response status is 409
- AND gateway-mock's behavior is unchanged
- AND exactly one `injections` row is blocking

#### Scenario: Concurrent activations
- GIVEN no blocking injection
- WHEN two `POST /faults` requests are sent at the same time
- THEN exactly one answers 201 and the other answers 409
- AND exactly one `injections` row exists, and it is `ACTIVE`
- AND the lab is activated once

#### Scenario: A pending restore blocks new faults
- GIVEN an injection is `RESTORE_PENDING`
- WHEN `POST /faults` is called with `{"fault": "F1"}`
- THEN the response status is 409
- AND the lab is not touched

### Requirement: Read the active fault

`GET /faults/active` MUST return 200 with `{"id": <injection id>, "fault": "<id>", "status": "ACTIVE" | "RESTORE_PENDING", "injectedAt": "<instant>"}` when an injection is blocking, and 204 with no body when none is. It MUST NOT return the true cause, the target service or the parameters.

#### Scenario: No active fault
- GIVEN every injection is `RESTORED` or `FAILED`
- WHEN `GET /faults/active` is called
- THEN the response status is 204

#### Scenario: Pending restore is reported
- GIVEN an injection is `RESTORE_PENDING`
- WHEN `GET /faults/active` is called
- THEN the response status is 200 with that injection and status `RESTORE_PENDING`

### Requirement: Restore is idempotent

`POST /faults/{id}/restore` on a blocking injection (`ACTIVE` or `RESTORE_PENDING`) MUST apply the fault's restore steps, set `restored_at`, set status `RESTORED`, record a `RESTORED` event and return 200 with `{"id", "fault", "status", "injectedAt", "restoredAt"}`. Restoring an injection that is no longer blocking (`RESTORED`, or `FAILED` after a successful compensating restore) MUST return 200 with the same body and change nothing: no restore step runs and no event is added. An unknown id, or one that is not a UUID, MUST return 404.

If the restore steps fail, the injector MUST record a `RESTORE_FAILED` event with the cause, set status `RESTORE_PENDING` (still blocking) and return 500 with `{"error": "restore failed", "id": <injection id>}`, so the restore can be retried. The move to `RESTORE_PENDING` MUST be conditional on the row still being blocking (`UPDATE ... WHERE id = ? AND status IN ('ACTIVE', 'RESTORE_PENDING')`): a failure that ends after a concurrent restore already set `RESTORED` is recorded as a `RESTORE_FAILED` event only, and the row stays `RESTORED`.

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

#### Scenario: Restore fails
- GIVEN F3 is active and the recreate of order-service fails
- WHEN `POST /faults/{id}/restore` is called
- THEN the response status is 500
- AND the row is `RESTORE_PENDING` with a `RESTORE_FAILED` event naming the cause
- AND a new `POST /faults` answers 409 until a restore succeeds

#### Scenario: A late restore failure does not undo a successful restore
- GIVEN F1 is active and two restores of it run at the same time
- WHEN one restore succeeds and sets `RESTORED`, and the other fails afterwards
- THEN the failing call answers 500
- AND the row stays `RESTORED` with `restored_at` set, with a `RESTORE_FAILED` event after the `RESTORED` one
- AND no injection is blocking

### Requirement: Failed activation is restored and recorded

If an activation step fails, the injector MUST attempt the restore steps (the compensating restore) and record an `ACTIVATION_FAILED` event with the failure cause. Then:

- if the compensating restore succeeds, it MUST set status `FAILED` and `restored_at`; the injection no longer blocks;
- if the compensating restore also fails, it MUST also record a `RESTORE_FAILED` event with that cause and set status `RESTORE_PENDING`; the injection keeps blocking until a `POST /faults/{id}/restore` succeeds.

Either way it returns 500 with `{"error": "activation failed", "id": <injection id>}`. The 500 body MUST NOT contain the true cause or a failure message. If recording the outcome itself fails, for any reason (database, transaction, serialization of the event detail), the injector MUST log that error and still answer the same 500; the row then stays `ACTIVE`, which keeps blocking.

#### Scenario: Recreate fails during F3, restore succeeds
- GIVEN the compose recreate of order-service fails, and the compensating restore succeeds
- WHEN `POST /faults` is called with `{"fault": "F3"}`
- THEN a restore of order-service is attempted
- AND the row is `FAILED` with an `ACTIVATION_FAILED` event naming the cause
- AND the response status is 500
- AND a new `POST /faults` is accepted

#### Scenario: Recreate fails during F3, restore fails too
- GIVEN the compose recreate of order-service fails, and the compensating restore fails as well
- WHEN `POST /faults` is called with `{"fault": "F3"}`
- THEN the response status is 500
- AND the row is `RESTORE_PENDING` with an `ACTIVATION_FAILED` and a `RESTORE_FAILED` event, each naming its cause
- AND a new `POST /faults` answers 409 until `POST /faults/{id}/restore` succeeds

### Requirement: Refuse to inject without recording the truth

The truth MUST be written before the lab is touched. If the `faults` database is unreachable, `POST /faults` MUST return 503 and MUST NOT apply any activation step. An injection without its truth is an incident that cannot be scored.

#### Scenario: Database down
- GIVEN the `faults` database is unreachable
- WHEN `POST /faults` is called with `{"fault": "F1"}`
- THEN the response status is 503
- AND gateway-mock's behavior is unchanged

### Requirement: Truth record and events

- `injections` holds one row per accepted activation: `id`, `fault_id`, `target_service`, `root_cause_service`, `root_cause_category`, `root_cause`, `parameters` (JSON), `injected_at`, `restored_at`, `status` (`ACTIVE`, `RESTORE_PENDING`, `RESTORED` or `FAILED`). Only the status and `restored_at` are ever updated.
- `injection_events` is append-only. It records every step of an injection's life with the injection id, the event type (`ACTIVATION_STARTED`, `ACTIVATED`, `ACTIVATION_FAILED`, `RESTORED`, `RESTORE_FAILED`), the time and a detail. Rows are never updated or deleted.
- Nothing is silently dropped: a step that runs has an event, and a step that fails has an event with its cause.

#### Scenario: Events of a clean F2 cycle
- GIVEN F2 was activated and then restored
- WHEN `injection_events` is read for that injection
- THEN it holds `ACTIVATION_STARTED`, `ACTIVATED` and `RESTORED`, in time order

### Requirement: The truth stays invisible to the agent

- No HTTP endpoint MUST ever return `root_cause_service`, `root_cause_category`, `root_cause`, the target service or the parameters.
- A superuser ignores `REVOKE CONNECT`, so the isolation holds only for roles that are not superusers. The compose `postgres` keeps a bootstrap superuser (`POSTGRES_USER`) used only by the init scripts. The services MUST connect with the non-superuser application role `lab_app`, which owns only `orders`, `payments` and `inventory`.
- The `faults` database MUST be owned by the non-superuser `faults` role, with `CONNECT` revoked from `PUBLIC`. `lab_app` MUST NOT be able to connect to it. The eval harness (M4) reads it with the `faults` role.
- The agent's future read-only role (M2, behind `queryDb`) MUST be a non-superuser and MUST NOT have `CONNECT` on `faults`; it never gets the `faults` credentials.
- Loki MUST have no `service="fault-injector"` stream: Alloy drops the injector container's logs. They stay readable with `docker logs` for the operator.
- Prometheus MUST NOT scrape the injector. `/actuator/prometheus` stays exposed for consistency only.

#### Scenario: The service role is not a superuser
- GIVEN the lab is running
- WHEN `pg_roles` is read for `lab_app`
- THEN `rolsuper` is false

#### Scenario: The service role cannot read the truth
- GIVEN the lab is running
- WHEN `lab_app` connects to the `faults` database
- THEN the connection is refused

#### Scenario: No injector logs in Loki
- GIVEN a fault was activated and restored
- WHEN Loki is queried for `{service="fault-injector"}`
- THEN no stream is returned

### Requirement: Leftover active injection at startup

On startup, if an `injections` row is blocking (`ACTIVE` after a crash, or `RESTORE_PENDING`), the injector MUST log one structured `WARN` line with its injection id, fault id and status, MUST report it through `GET /faults/active`, and MUST NOT restore it on its own. The operator restores it with `POST /faults/{id}/restore`. Until then `POST /faults` returns 409.

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
