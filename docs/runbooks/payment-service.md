# Runbook: payment-service

## 1. Purpose

payment-service records the payment of an order. For each request it asks the payment gateway (gateway-mock) for the outcome, stores a payment with that outcome in PostgreSQL, and returns it. It never decides the outcome on its own.

Payment statuses: `AUTHORIZED` and `DECLINED` come from the gateway; `FAILED` means the gateway answered 429 and the payment was recorded without a decision. Every attempt, stored or not, is recorded in `payment_events` (see section 8). There is no retry, no backoff and no idempotency: two calls for one order create two payments.

## 2. Dependencies

| Dependency | Call | Connect timeout | Read timeout | On failure |
|------------|------|-----------------|--------------|------------|
| gateway-mock | `POST /authorize` | 1s | 15s | 503 `Payment gateway unavailable`, no payment stored but the attempt recorded as `GATEWAY_UNAVAILABLE` (429 is stored as `FAILED`) |
| PostgreSQL | database `payments` on `postgres:5432` | Hikari pool, 10 connections | — | 500 on writes, NaN on `lab_payments` |

The gateway read timeout (15s) is longer than order-service's read timeout on this service (3s). With a slow gateway, order-service gives up first while this service keeps waiting.

## 3. Endpoints

Host port 18081 (container port 8081).

| Endpoint | Status codes |
|----------|--------------|
| `POST /payments` | 201 with `Location: /payments/{id}` and the payment; 400 when `orderId` is missing, null or not a UUID, or the JSON is malformed; 503 `Payment gateway unavailable` |
| `GET /payments/{id}` | 200; 400 when the id is not a UUID; 404 unknown id |
| `GET /actuator/health` | 200 when up |
| `GET /actuator/prometheus` | 200, Prometheus text format |

## 4. Configuration

| Property | Value when unset | Set by compose |
|----------|------------------|----------------|
| `kintsugi.gateway.base-url` | `http://localhost:8082` | `KINTSUGI_GATEWAY_BASEURL: http://gateway-mock:8082` |
| `kintsugi.gateway.connect-timeout` | `1s` | — |
| `kintsugi.gateway.read-timeout` | `15s` | — |
| `spring.datasource.hikari.maximum-pool-size` | `10` | — |
| `spring.datasource.url` | — | `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/payments` |
| `server.port` | `8081` | — |

## 5. Key signals

**Metrics** (Prometheus label `service="payment-service"`):

- `lab_payments{status}`: stored payments per status (`AUTHORIZED`, `DECLINED`, `FAILED`). Refreshed at most once per second; NaN when the count query fails or the snapshot is older than 10 s.
- `http_server_requests_seconds_count|_sum|_max` with `uri="/payments"` and `status` (`201`, `400`, `503`, ...).

**Grafana panels:** "Payments by status", "payment-service /payments latency and 503 rate", "Gateway rejections".

**Log lines** (Loki stream `{service="payment-service"}`):

| Level | Message |
|-------|---------|
| WARN | `Gateway rejected the payment of order <id> with status 429; recording it as FAILED` |
| WARN | `Payment gateway unavailable: event=PAYMENT_ATTEMPT order=<id> outcome=GATEWAY_UNAVAILABLE duration_ms=<ms> url="<gateway URL>" exception=<class> cause="<cause>"` |
| WARN | `Payment event not stored: event=PAYMENT_ATTEMPT order=<id> outcome=<outcome> duration_ms=<ms> detail="<detail JSON>" record_failure=<class> cause="<database message>"` |
| WARN | `Payment commit outcome unknown, the payment may or may not be stored: event=PAYMENT_ATTEMPT order=<id> outcome=COMMIT_UNKNOWN duration_ms=<ms> payment=<payment id> gateway_outcome=<outcome> exception=<class> cause="<message>"` |
| WARN | `Cannot count payments per status; lab.payments reports NaN until the query succeeds` |
| INFO | `Payment counts are readable again` |

The values are `key=value` pairs, readable by Loki's `logfmt` parser. `exception` is the most specific cause's class, for example `java.net.http.HttpTimeoutException`. `<cause>` reads `Authorization of order <id> failed: <client error>`, `Authorization of order <id> returned no status`, or `Authorization of order <id> returned an unknown status: <status>`.

## 6. Symptoms

Run PromQL in Grafana Explore or with `curl -s localhost:19090/api/v1/query --data-urlencode 'query=<expr>'`. Run LogQL in Grafana Explore.

### Burst of `PAYMENT_FAILED` orders

An order ends `PAYMENT_FAILED` when this service answers `DECLINED` or `FAILED`. Split first: `sum by (status) (lab_payments{service="payment-service"})`.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| Gateway rate limiting | LogQL `sum(count_over_time({service="payment-service"} \|= "Gateway rejected the payment" [1m]))` | rejection lines rising with `lab_payments{status="FAILED"}` |
| Gateway declining payments | `lab_payments{status="DECLINED"}` | `DECLINED` rising, no rejection lines |
| Client retries or duplicate calls | `rate(http_server_requests_seconds_count{service="payment-service",uri="/payments"}[5m])` against order-service's order rate | more payments than orders |

When the gateway is the source, check gateway-mock's own state and logs (see its runbook) before acting here.

### 503 on `POST /payments`

Check `sum(rate(http_server_requests_seconds_count{service="payment-service",uri="/payments",status="503"}[1m]))` and LogQL `{service="payment-service"} |= "Payment gateway unavailable"`.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| Gateway slow | average latency below; gateway-mock's `gateway_authorize_inflight` | 503s arrive about 15s after the request; in-flight above 0 |
| Gateway down or wrong base URL | `docker compose -f infra/compose.yaml ps gateway-mock`; `docker compose -f infra/compose.yaml config payment-service` | cause names a connection error and the URL; container not healthy |
| Gateway answering 500 or 503 | gateway-mock's `http_server_requests_seconds_count{service="gateway-mock",uri="/authorize",status=~"5.."}` | 5xx rising there |
| Gateway reply without a known status | LogQL `{service="payment-service"} \|= "returned no status"` or `\|= "unknown status"` | lines present |

### Slow `POST /payments`

`sum(rate(http_server_requests_seconds_sum{service="payment-service",uri="/payments"}[1m])) / sum(rate(http_server_requests_seconds_count{service="payment-service",uri="/payments"}[1m]))`.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| Gateway slow | gateway-mock's `gateway_authorize_inflight` and its `/authorize` latency | gateway latency close to this service's latency |
| Database slow or pool saturated | `hikaricp_connections_pending{service="payment-service"}`; `pg_stat_activity` on `payments` | pending above 0, long-running or waiting sessions |
| CPU or memory pressure on the host | `docker stats --no-stream` | the container near its limits |

Callers see this as orders stuck in `PAYMENT_PENDING` (see the order-service runbook).

### `lab_payments` reports NaN

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| Database down or unreachable | `docker compose -f infra/compose.yaml exec postgres pg_isready -U lab -d payments` | not accepting connections |
| `payments` table locked or count query slow | `pg_stat_activity` on `payments` | sessions waiting on a lock |
| Pool exhausted | `hikaricp_connections_pending{service="payment-service"}` | above 0 for a sustained period |

## 7. Safe actions

- **Check status:** `docker compose -f infra/compose.yaml ps payment-service` and `curl -s localhost:18081/actuator/health`.
- **Check configuration:** `docker compose -f infra/compose.yaml config payment-service`; the gateway URL must be `http://gateway-mock:8082`.
- **Roll back a configuration change:** remove the override from the environment or `infra/compose.yaml`, then `docker compose -f infra/compose.yaml up -d --no-deps --wait payment-service`.
- **Restart:** `docker compose -f infra/compose.yaml restart payment-service`. Stored payments are kept; counters restart from zero.
- **Look up a payment:** `curl -s localhost:18081/payments/<id>`.
- Do not replay `POST /payments` for an order: there is no idempotency, so a replay creates a second payment.

## 8. Where the evidence is

Database `payments` on the `postgres` container. Run SQL with `docker compose -f infra/compose.yaml exec postgres psql -U lab -d payments -c "<sql>"`.

| Table | Columns | Content |
|-------|---------|---------|
| `payments` | `id`, `order_id`, `status`, `version`, `created_at` | payments with a gateway outcome (`AUTHORIZED`, `DECLINED`, `FAILED`) |
| `payment_events` | `id`, `order_id`, `occurred_at`, `type`, `outcome`, `detail`, `duration_ms` | append-only: one row per payment attempt that reached the database, never updated or deleted |

`payment_events.type` is `PAYMENT_ATTEMPT`. `outcome` is `AUTHORIZED`, `DECLINED` or `FAILED` (committed with the `payments` row), `GATEWAY_UNAVAILABLE` (no payment stored, 503), `ERROR` (an unexpected failure, 500) or `COMMIT_UNKNOWN` (the commit itself failed, so the payment may or may not be stored). `detail` is compact JSON: the payment id, or the exception class, URL and message. `duration_ms` is how long the attempt took, gateway call included.

- **One order's attempts:** `SELECT occurred_at, outcome, duration_ms, detail FROM payment_events WHERE order_id = '<id>' ORDER BY occurred_at`.
- **Recent unavailable gateway:** `SELECT occurred_at, order_id, duration_ms, detail FROM payment_events WHERE outcome = 'GATEWAY_UNAVAILABLE' ORDER BY occurred_at DESC LIMIT 20`.
- **Attempts per outcome in the last 15 minutes:** `SELECT outcome, count(*), max(duration_ms) FROM payment_events WHERE occurred_at > now() - interval '15 minutes' GROUP BY outcome`.

An order stuck in `PAYMENT_PENDING` in order-service may still have a payment here: payment-service keeps waiting for the gateway after order-service gave up.
