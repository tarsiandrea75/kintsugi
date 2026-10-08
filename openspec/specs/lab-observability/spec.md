# Lab Observability Specification

## Purpose

The lab observability stack makes injected faults visible. One Docker Compose command starts the M1 lab (PostgreSQL, order-service, payment-service, gateway-mock, inventory-service) with Prometheus, Loki, Alloy and Grafana. A provisioned dashboard shows faults F1, F2, F3 and F4 while they are active. Prometheus (PromQL) and Loki (LogQL) are also the query APIs the agent will use in M2. Design: [feature document](../../../odd/tasks/lab-observability.md) and, for the faults, [fault F2](../../../odd/tasks/fault-f2.md) and [fault F3](../../../odd/tasks/fault-f3.md) and [fault F4](../../../odd/tasks/fault-f4.md). Stack and versions: [ADR-008](../../../docs/adr/ADR-008-lab-observability-stack.md). Verified by `./mvnw test` in each `lab/*` service and by `infra/scripts/verify-f1.sh`, `infra/scripts/verify-f2.sh`, `infra/scripts/verify-f3.sh` and `infra/scripts/verify-f4.sh`.

The stack also runs the fault injector (`fault-injector`, [its spec](../fault-injector/spec.md), [ADR-009](../../../docs/adr/ADR-009-fault-injector-docker-control.md)). The verify scripts inject and restore every fault through it. It is deliberately **excluded** from the agent's data sources: Prometheus does not scrape it and Alloy drops its logs, because both would reveal when and which fault was injected.

The stack also runs `mcp-tools`, the agent's tools over MCP ([its spec](../mcp-tools/spec.md), [ADR-004](../../../docs/adr/ADR-004-tools-via-mcp.md)). Alloy ships its logs like a lab service's, so its tool-call records are in Loki for the human; no tool can select them, because `mcp-tools` is not in the tools' service enum. Prometheus does not scrape it.

## Requirements

### Requirement: Every service exposes Prometheus metrics

order-service, payment-service, gateway-mock and inventory-service MUST expose `/actuator/prometheus` in the Prometheus text format, with the actuator exposure set to `health,prometheus`. Each MUST export the Spring HTTP server timer `http_server_requests_seconds`. gateway-mock MUST also export `gateway_authorize_inflight`, order-service MUST export `lab_orders` (see "Orders-by-status gauge"), payment-service MUST export `lab_payments` (see "Payments-by-status gauge"), and inventory-service MUST export the Hikari pool metrics `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending`, `hikaricp_connections_max` and `hikaricp_connections_timeout_total`.

#### Scenario: Scrape a service
- GIVEN a running service that has served at least one HTTP request
- WHEN `GET /actuator/prometheus` is called
- THEN the response status is 200
- AND the body contains `http_server_requests_seconds_count`

#### Scenario: Service-specific metrics
- GIVEN gateway-mock, order-service, payment-service and inventory-service are running
- WHEN each is scraped
- THEN gateway-mock's body contains `gateway_authorize_inflight`
- AND order-service's body contains `lab_orders`
- AND payment-service's body contains `lab_payments`
- AND inventory-service's body contains `hikaricp_connections_pending`

#### Scenario: Prometheus scrapes every service
- GIVEN the compose stack is up
- WHEN Prometheus' targets are listed
- THEN the four services are targets with health `up`, each with the label `service` set to its compose service name
- AND `fault-injector` and `mcp-tools` are not targets

### Requirement: Orders-by-status gauge

order-service MUST export the gauge `lab_orders` (Micrometer name `lab.orders`) with one series per order status, tag `status` in `CREATED`, `PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`, `REJECTED_OUT_OF_STOCK`, `REJECTED_INVENTORY_UNAVAILABLE`. Each series MUST hold the number of stored orders in that status, read from the database at scrape time; a status with no orders MUST report 0. The exact contract is in the [order-service spec](../order-service/spec.md).

#### Scenario: F1 raises the pending series
- GIVEN the compose stack is up and the gateway latency is above payment-service's read timeout
- WHEN an order is placed
- THEN `lab_orders{status="PAYMENT_PENDING"}` rises by 1 at the next scrape

#### Scenario: F2 raises the payment-failed series
- GIVEN the compose stack is up and the gateway answers 429
- WHEN an order is placed
- THEN `lab_orders{status="PAYMENT_FAILED"}` rises by 1 within a few scrapes

### Requirement: Payments-by-status gauge

payment-service MUST export the gauge `lab_payments` (Micrometer name `lab.payments`) with one series per payment status, tag `status` in `AUTHORIZED`, `DECLINED`, `FAILED`. Each series MUST hold the number of stored payments in that status; a status with no payments MUST report 0. The exact contract is in the [payment-service spec](../payment-service/spec.md).

#### Scenario: F2 raises the failed series
- GIVEN the compose stack is up and the gateway answers 429
- WHEN an order is placed
- THEN `lab_payments{status="FAILED"}` rises by 1 within a few scrapes

### Requirement: Logs reach Loki labelled by service

Alloy MUST read the Docker logs of the four services and push them to Loki with the label `service` set to the compose service name (`order-service`, `payment-service`, `gateway-mock`, `inventory-service`). It also ships the logs of `mcp-tools` (label `service="mcp-tools"`) and of the observability containers, as it does for every container of the compose project. Alloy MUST drop the logs of the `fault-injector` container, so Loki never has a `service="fault-injector"` stream; the operator reads them with `docker logs`.

#### Scenario: Query a service's logs
- GIVEN the compose stack is up and order-service has logged a line
- WHEN Loki is queried with `{service="order-service"}`
- THEN the line is returned

#### Scenario: The fault injector's logs never reach Loki
- GIVEN the fault injector has logged lines for an injection (visible with `docker logs`)
- WHEN Loki's `service` label values and `{service="fault-injector"}` series are queried
- THEN `fault-injector` is not a label value and no series is returned

### Requirement: Injection, truth and isolation checks

Each verify script MUST inject its fault with `POST /faults {"fault": "<id>"}` on the fault injector (`127.0.0.1:18084`) and restore it with `POST /faults/{id}/restore`, also in its exit trap, so the lab is restored whatever the outcome. Before injecting, a script MUST restore any injection an earlier run left blocking (`GET /faults/active`). After the restore it MUST assert:

1. the truth row, read as the `faults` role: exactly one `injections` row for its injection id, with its fault id, the fault's `root_cause_service` and status `RESTORED`;
2. that `lab_app` and `faults` are not superusers (`rolsuper` is false in `pg_roles`);
3. that `lab_app` cannot connect to the `faults` database (permission denied);
4. that Loki has no `service="fault-injector"` label value or stream, while `docker logs` of the injector has lines for that injection.

#### Scenario: The truth is recorded and isolated
- GIVEN a verify script has injected and restored its fault through the injector
- WHEN it checks the truth
- THEN the `injections` row has the right `fault_id`, `root_cause_service` and status `RESTORED`
- AND `lab_app` is not a superuser and is refused by the `faults` database
- AND Loki has no `fault-injector` stream

### Requirement: Grafana is provisioned

Grafana MUST start with the Prometheus and Loki datasources and the "Lab — Faults" dashboard (uid `lab-f1`, kept so existing links work) provisioned from files in `infra/grafana/`, with anonymous viewer access; the admin password MUST come from `GRAFANA_ADMIN_PASSWORD`, with a lab default. The dashboard MUST have these panels:

| Panel | Source |
|-------|--------|
| Orders by status | `lab_orders` by `status` |
| Gateway in-flight authorizations | `gateway_authorize_inflight` |
| payment-service /payments latency and 503 rate | `http_server_requests_seconds` of payment-service |
| order-service WARN lines | Loki, `{service="order-service"}` filtered to `WARN` |
| Payments by status | `lab_payments` by `status` |
| Gateway rejections | Loki, per-minute count of payment-service's "Gateway rejected the payment" `WARN` lines |
| order-service → payment calls by outcome | rate of `http_client_requests_seconds_count` of order-service with `client_name="payment-service"`, by `outcome` and `exception` |
| inventory DB pool | `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending` and the rate of `hikaricp_connections_timeout_total`, all with `service="inventory-service"` |
| inventory 5xx rate | rate of `http_server_requests_seconds_count{service="inventory-service",status=~"5.."}`, by `uri` and `status` |

"Gateway rejections" counts log lines rather than reusing `lab_payments`: it names the cause (the gateway's 429) from a second, independent source, while "Payments by status" shows the effect.

"order-service → payment calls by outcome" tells F3 from F1, which both leave orders in `PAYMENT_PENDING`: under F3 the calls fail at once with `exception="ConnectException"`, under F1 they end after the read timeout with `exception="HttpTimeoutException"`.

"inventory DB pool" shows F4's root cause inside inventory-service: active connections at the pool size, pending connections above 0 and timeouts rising. "inventory 5xx rate" shows its first symptom, the 500s on `/reservations`.

#### Scenario: Open the dashboard
- GIVEN the compose stack is up
- WHEN `http://localhost:13000` is opened without logging in
- THEN the "Lab — Faults" dashboard is listed and shows the nine panels
- AND both datasources are configured

### Requirement: F1 visibility check

`infra/scripts/verify-f1.sh` MUST bring the compose stack up, or use a running one, and wait until it is healthy. It then MUST:

1. place a healthy order;
2. inject F1 through the fault injector (gateway latency 10 s);
3. place an order;
4. assert through the Prometheus HTTP API that `lab_orders{status="PAYMENT_PENDING"}` rose and that `gateway_authorize_inflight` was above 0;
5. assert through the Loki API that order-service logged the payment `WARN` line;
6. restore F1 through the fault injector and run the truth and isolation checks.

It MUST exit non-zero when any assertion fails, and MUST restore the injection whatever the outcome.

#### Scenario: F1 is visible
- GIVEN Docker is running
- WHEN `infra/scripts/verify-f1.sh` runs
- THEN it exits 0
- AND the gateway latency is 0 afterwards

### Requirement: F2 visibility check

`infra/scripts/verify-f2.sh` MUST bring the compose stack up, or use a running one, and wait until it is healthy. It then MUST:

1. place a healthy order and assert it is `PAID`;
2. read the baselines of `lab_payments{status="FAILED"}` and `lab_orders{status="PAYMENT_FAILED"}`;
3. inject F2 through the fault injector, which makes the gateway answer 429 (`PUT /admin/behavior {"latencyMs":0,"errorStatus":429}`);
4. place three orders and assert each answers 201 `PAYMENT_FAILED`;
5. assert through the Prometheus HTTP API that both series rose by at least 3;
6. assert through the Loki API that payment-service logged the `WARN` line for one of those orders;
7. restore F2 through the fault injector and assert that a new order is `PAID` again;
8. run the truth and isolation checks.

It MUST exit non-zero when any assertion fails, and MUST restore the injection whatever the outcome. The check scripts share their helpers through `infra/scripts/lib.sh`.

#### Scenario: F2 is visible and the lab recovers
- GIVEN Docker is running
- WHEN `infra/scripts/verify-f2.sh` runs
- THEN it exits 0
- AND the gateway has no error status afterwards

### Requirement: F3 visibility check

Fault F3 is a wrong payment URL in order-service. `infra/compose.yaml` MUST set order-service's `KINTSUGI_PAYMENT_BASEURL` from `ORDER_PAYMENT_BASEURL`, defaulting to `http://payment-service:8081`; F3 recreates order-service with `ORDER_PAYMENT_BASEURL=http://payment-service:9999`, a port where nothing listens.

`infra/scripts/verify-f3.sh` MUST bring the compose stack up, or use a running one, and wait until it is healthy. It then MUST:

1. place a healthy order and assert it is `PAID`;
2. inject F3 through the fault injector, which recreates order-service with the wrong URL, and assert that the new container has `KINTSUGI_PAYMENT_BASEURL=http://payment-service:9999`;
3. read the baseline of the refused-connection series, `http_client_requests_seconds_count{service="order-service",client_name="payment-service",exception="ConnectException"}`, after the recreate, because the new process starts its counters from zero;
4. place an order and assert it answers 201 `PAYMENT_PENDING` in under 2 s;
5. assert through the Prometheus HTTP API that the refused-connection series rose by at least 1 and that `gateway_authorize_inflight` stayed 0 since the injection;
6. assert through the Loki API that order-service logged a `WARN` line with the order id and `ConnectException`;
7. restore F3 through the fault injector, which recreates order-service without the variable, and assert that a new order is `PAID` again;
8. run the truth and isolation checks.

It MUST exit non-zero when any assertion fails, and MUST restore the injection whatever the outcome.

#### Scenario: F3 is visible and the lab recovers
- GIVEN Docker is running
- WHEN `infra/scripts/verify-f3.sh` runs
- THEN it exits 0
- AND order-service runs with `KINTSUGI_PAYMENT_BASEURL=http://payment-service:8081` afterwards

### Requirement: F4 visibility check

Fault F4 is an exhausted database connection pool in inventory-service. `infra/compose.yaml` MUST set inventory-service's `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` from `INVENTORY_POOL_SIZE`, defaulting to 10, and its `KINTSUGI_INVENTORY_RESERVATIONDELAY` from `INVENTORY_RESERVATION_DELAY`, defaulting to `0s`. F4 recreates inventory-service with `INVENTORY_POOL_SIZE=2` and `INVENTORY_RESERVATION_DELAY=5s`: every reservation holds one of two connections for 5 s, and a request that finds no free connection within the 2 s connection timeout gets a 500.

`infra/scripts/verify-f4.sh` MUST bring the compose stack up, or use a running one, and wait until it is healthy. It then MUST:

1. place a healthy order and assert it is `PAID`;
2. inject F4 through the fault injector, which recreates inventory-service with both variables, and assert that the new container has a pool of 2 and a 5 s reservation delay;
3. read the baselines of inventory's 5xx count on `/reservations` and of `hikaricp_connections_timeout_total` after the recreate, because the new process starts its counters from zero;
4. place six orders concurrently;
5. assert that at least one answers 503, that every answer is either 503 or 201 `PAID`, and that every 503 body carries the stored order's `orderId`;
6. assert through the Prometheus HTTP API that inventory's 5xx count rose by at least 1, and that `hikaricp_connections_pending` reached at least 1 since the injection or the timeout count rose;
7. assert through the Loki API that, since the injection, order-service logged its `Inventory unavailable` `WARN` line or inventory-service logged its `No database connection available` `WARN` line;
8. before restoring inventory-service, assert in PostgreSQL (through `docker compose exec -T postgres psql -U lab_app`) that every order answered 503 is stored in `orders` as `REJECTED_INVENTORY_UNAVAILABLE` with an `INVENTORY_UNAVAILABLE` row in `order_events`, and that at least one of them also has a `RESERVED` row in inventory's `reservation_events`, polling for up to 12 s because inventory commits that row after its 5 s delay; print that order id and both rows. Not every 503 order has a `RESERVED` row: an order whose reservation found no connection reserved nothing. The check runs before the restore because recreating inventory-service kills a reservation still sleeping on its connection;
9. restore F4 through the fault injector, which recreates inventory-service without the variables, and assert that a new order is `PAID` again;
10. run the truth and isolation checks.

It MUST exit non-zero when any assertion fails, and MUST restore the injection whatever the outcome.

#### Scenario: F4 is visible and the lab recovers
- GIVEN Docker is running
- WHEN `infra/scripts/verify-f4.sh` runs
- THEN it exits 0
- AND inventory-service runs with a pool of 10 connections and no reservation delay afterwards

#### Scenario: F4 leaves cross-service evidence of the stock leak
- GIVEN F4 is injected and six orders are placed concurrently
- WHEN `infra/scripts/verify-f4.sh` checks PostgreSQL
- THEN at least one order id answered 503 has an `order_events` row of type `INVENTORY_UNAVAILABLE`, status `REJECTED_INVENTORY_UNAVAILABLE` in `orders`, and a `reservation_events` row with outcome `RESERVED`

### Requirement: Compose startup and host ports

`docker compose -f infra/compose.yaml up -d` MUST start every container healthy, with healthchecks and `depends_on` conditions. One PostgreSQL 18.6 container MUST hold the `orders`, `payments` and `inventory` databases, owned by the non-superuser role `lab_app` that the four services connect as, and the `faults` database, owned by the non-superuser role `faults` with `CONNECT` revoked from `PUBLIC`. The read-only role `kintsugi_agent`, which mcp-tools connects as, may read only the six evidence tables (mcp-tools spec). Only the init script uses the bootstrap superuser. Services MUST reach each other by compose service name. Only these host ports MUST be published, each bound to `127.0.0.1` so the lab is not reachable from the network:

| Container | Host port |
|-----------|-----------|
| order-service | 18080 |
| payment-service | 18081 |
| gateway-mock | 18082 |
| inventory-service | 18083 |
| fault-injector | 18084 |
| mcp-tools | 18085 |
| Grafana | 13000 |
| Prometheus | 19090 |

PostgreSQL, Loki and Alloy MUST NOT be published on the host.

#### Scenario: A healthy lab pays an order
- GIVEN the stack was started with `docker compose -f infra/compose.yaml up -d`
- WHEN every container reports healthy and `POST /orders` is called on `localhost:18080`
- THEN the order ends in `PAID`

## Known limitations

- **No traces.** Cross-service latency is inferred from metrics and logs; OTLP and Tempo come with the agent in M2+ (ADR-008).
- **No alerting.** Nothing fires when a fault is active; alerting comes with the agent's perception module.
- **Only F1 to F4 are visible.** The later faults and their panels are not built yet.
- **Manual recipes bypass the injector.** The "Reproduce by hand" recipes in `docs/setup.md` change the lab directly, so they record no truth row. Scored incidents go through the fault injector only.
