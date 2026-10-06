# Lab Observability Specification

## Purpose

The lab observability stack makes injected faults visible. One Docker Compose command starts the M1 lab (PostgreSQL, order-service, payment-service, gateway-mock) with Prometheus, Loki, Alloy and Grafana. A provisioned dashboard shows fault F1 while it is active. Prometheus (PromQL) and Loki (LogQL) are also the query APIs the agent will use in M2. Design: [feature document](../../../odd/tasks/lab-observability.md). Stack and versions: [ADR-008](../../../docs/adr/ADR-008-lab-observability-stack.md). Verified by `./mvnw test` in each `lab/*` service and by `infra/scripts/verify-f1.sh`.

## Requirements

### Requirement: Every service exposes Prometheus metrics

order-service, payment-service and gateway-mock MUST expose `/actuator/prometheus` in the Prometheus text format, with the actuator exposure set to `health,prometheus`. Each MUST export the Spring HTTP server timer `http_server_requests_seconds`. gateway-mock MUST also export `gateway_authorize_inflight`, and order-service MUST export `lab_orders` (see "Orders-by-status gauge").

#### Scenario: Scrape a service
- GIVEN a running service that has served at least one HTTP request
- WHEN `GET /actuator/prometheus` is called
- THEN the response status is 200
- AND the body contains `http_server_requests_seconds_count`

#### Scenario: Service-specific metrics
- GIVEN gateway-mock and order-service are running
- WHEN each is scraped
- THEN gateway-mock's body contains `gateway_authorize_inflight`
- AND order-service's body contains `lab_orders`

#### Scenario: Prometheus scrapes every service
- GIVEN the compose stack is up
- WHEN Prometheus' targets are listed
- THEN the three services are targets with health `up`

### Requirement: Orders-by-status gauge

order-service MUST export the gauge `lab_orders` (Micrometer name `lab.orders`) with one series per order status, tag `status` in `CREATED`, `PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`. Each series MUST hold the number of stored orders in that status, read from the database at scrape time; a status with no orders MUST report 0. The exact contract is in the [order-service spec](../order-service/spec.md).

#### Scenario: F1 raises the pending series
- GIVEN the compose stack is up and the gateway latency is above payment-service's read timeout
- WHEN an order is placed
- THEN `lab_orders{status="PAYMENT_PENDING"}` rises by 1 at the next scrape

### Requirement: Logs reach Loki labelled by service

Alloy MUST read the Docker logs of the three services and push them to Loki with the label `service` set to the compose service name (`order-service`, `payment-service`, `gateway-mock`).

#### Scenario: Query a service's logs
- GIVEN the compose stack is up and order-service has logged a line
- WHEN Loki is queried with `{service="order-service"}`
- THEN the line is returned

### Requirement: Grafana is provisioned

Grafana MUST start with the Prometheus and Loki datasources and the "Lab — F1" dashboard provisioned from files in `infra/grafana/`, with anonymous viewer access; the admin password MUST come from `GRAFANA_ADMIN_PASSWORD`, with a lab default. The dashboard MUST have these panels:

| Panel | Source |
|-------|--------|
| Orders by status | `lab_orders` by `status` |
| Gateway in-flight authorizations | `gateway_authorize_inflight` |
| payment-service request latency and 503 rate | `http_server_requests_seconds` of payment-service |
| order-service WARN lines | Loki, `{service="order-service"}` filtered to `WARN` |

#### Scenario: Open the dashboard
- GIVEN the compose stack is up
- WHEN `http://localhost:13000` is opened without logging in
- THEN the "Lab — F1" dashboard is listed and shows the four panels
- AND both datasources are configured

### Requirement: F1 visibility check

`infra/scripts/verify-f1.sh` MUST bring the compose stack up, or use a running one, and wait until it is healthy. It then MUST:

1. place a healthy order;
2. set the gateway latency to 10 s;
3. place an order;
4. assert through the Prometheus HTTP API that `lab_orders{status="PAYMENT_PENDING"}` rose and that `gateway_authorize_inflight` was above 0;
5. assert through the Loki API that order-service logged the payment `WARN` line;
6. reset the gateway latency.

It MUST exit non-zero when any assertion fails, and MUST reset the gateway whatever the outcome.

#### Scenario: F1 is visible
- GIVEN Docker is running
- WHEN `infra/scripts/verify-f1.sh` runs
- THEN it exits 0
- AND the gateway latency is 0 afterwards

### Requirement: Compose startup and host ports

`docker compose -f infra/compose.yaml up -d` MUST start every container healthy, with healthchecks and `depends_on` conditions. One PostgreSQL 18.6 container MUST hold the `orders` and `payments` databases. Services MUST reach each other by compose service name. Only these host ports MUST be published, each bound to `127.0.0.1` so the lab is not reachable from the network:

| Container | Host port |
|-----------|-----------|
| order-service | 18080 |
| payment-service | 18081 |
| gateway-mock | 18082 |
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
- **Only F1 is visible.** Faults F2–F4 and their panels are not built yet.
