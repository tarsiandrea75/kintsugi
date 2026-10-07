# Runbook: order-service

## 1. Purpose

order-service is the entry point of the lab. It accepts orders, reserves their stock in inventory-service, stores them in PostgreSQL, takes them through payment in payment-service, and serves them back by id.

Order lifecycle: `CREATED` → `PAYMENT_PENDING` → `PAID` or `PAYMENT_FAILED`, or `CREATED` → `REJECTED_OUT_OF_STOCK` or `REJECTED_INVENTORY_UNAVAILABLE` when the stock could not be reserved. `PAID`, `PAYMENT_FAILED` and both `REJECTED_*` statuses are terminal. Nothing moves a `PAYMENT_PENDING` order forward later: there is no reconciliation. Every order that reaches the database is kept, rejected ones included, and every step of it is recorded in `order_events` (see section 8).

## 2. Dependencies

| Dependency | Call | Connect timeout | Read timeout | On failure |
|------------|------|-----------------|--------------|------------|
| inventory-service | `POST /reservations` | 1s | 3s | `POST /orders` returns 503; the order is stored as `REJECTED_INVENTORY_UNAVAILABLE` |
| payment-service | `POST /payments` | 1s | 3s | order stays `PAYMENT_PENDING`, `POST /orders` still 201 |
| PostgreSQL | database `orders` on `postgres:5432` | Hikari pool, 10 connections | — | 500 on writes (before inventory-service is called), NaN on `lab_orders` |

Order of calls in `POST /orders`: save as `CREATED`, then reserve stock, then save as `PAYMENT_PENDING` (or a `REJECTED_*` status), then call payment, then save the outcome. Each status change is saved together with its `order_events` row in one transaction. No database transaction spans either remote call.

## 3. Endpoints

Host port 18080 (container port 8080).

| Endpoint | Status codes |
|----------|--------------|
| `POST /orders` | 201 with `Location: /orders/{id}` and the order in its final status; 400 invalid body; 409 `Insufficient stock` and 503 `Inventory unavailable`, both with the stored order's `orderId` in the problem body; 500 when saving the order, a status change or its event fails |
| `GET /orders/{id}` | 200; 400 when the id is not a UUID; 404 unknown id |
| `GET /actuator/health` | 200 when up |
| `GET /actuator/prometheus` | 200, Prometheus text format |

## 4. Configuration

| Property | Value when unset | Set by compose |
|----------|------------------|----------------|
| `kintsugi.payment.base-url` | `http://localhost:8081` | `KINTSUGI_PAYMENT_BASEURL: ${ORDER_PAYMENT_BASEURL:-http://payment-service:8081}` |
| `kintsugi.payment.connect-timeout` | `1s` | — |
| `kintsugi.payment.read-timeout` | `3s` | — |
| `kintsugi.inventory.base-url` | `http://localhost:8083` | `KINTSUGI_INVENTORY_BASEURL: http://inventory-service:8083` |
| `kintsugi.inventory.connect-timeout` | `1s` | — |
| `kintsugi.inventory.read-timeout` | `3s` | — |
| `spring.datasource.hikari.maximum-pool-size` | `10` | — |
| `spring.datasource.url` | — | `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orders` |

Any property can also be set through its Spring environment-variable form (upper case, dots as underscores, dashes removed), as compose does for the base URLs.

## 5. Key signals

**Metrics** (Prometheus label `service="order-service"`):

- `lab_orders{status}`: stored orders per status (`CREATED`, `PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`, `REJECTED_OUT_OF_STOCK`, `REJECTED_INVENTORY_UNAVAILABLE`). Refreshed at most once per second; NaN when the count query fails or the snapshot is older than 10 s.
- `http_client_requests_seconds_count|_sum|_max` with `client_name` (`payment-service`, `inventory-service`), `uri` (`/payments`, `/reservations`), `status` (HTTP code or `CLIENT_ERROR`), `outcome` (`SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR`, `UNKNOWN`), `exception` (`none` or the I/O exception, such as `ConnectException` or `HttpTimeoutException`).
- `http_server_requests_seconds_*` with `uri` and `status`.

**Grafana panels:** "Orders by status", "order-service WARN lines", "order-service → payment calls by outcome".

**Log lines** (Loki stream `{service="order-service"}`):

| Level | Message |
|-------|---------|
| WARN | `Payment of order <id> unavailable, leaving it in PAYMENT_PENDING: event=PAYMENT_UNAVAILABLE order=<id> outcome=PAYMENT_PENDING duration_ms=<ms> url="<url>" exception=<class> cause="<cause>"` |
| WARN | `Inventory unavailable for order <id>, order rejected: event=INVENTORY_UNAVAILABLE order=<id> outcome=REJECTED_INVENTORY_UNAVAILABLE duration_ms=<ms> url="<url>" exception=<class> cause="<cause>"` |
| WARN | `Order event not stored: event=<type> order=<id> outcome=<status> duration_ms=<ms> detail="<detail>" record_failure=<class> cause="<database message>"` |
| WARN | `Cannot count orders per status; lab.orders reports NaN until the query succeeds` |
| INFO | `Order counts are readable again` |

The values are `key=value` pairs, readable by Loki's `logfmt` parser (`| logfmt`). `exception` is the most specific cause's class, for example `java.net.ConnectException` or `java.net.http.HttpTimeoutException`. For an I/O failure, `<cause>` names the exception and the URL, for example `ConnectException calling POST http://payment-service:8081/payments`. Other causes read `... answered <code> with status <status> from POST <url>` or `... returned no status`.

## 6. Symptoms

Run PromQL in Grafana Explore or with `curl -s localhost:19090/api/v1/query --data-urlencode 'query=<expr>'`. Run LogQL in Grafana Explore (Loki has no host port).

### Orders stuck in `PAYMENT_PENDING`

Check first: `sum by (outcome, exception, status) (rate(http_client_requests_seconds_count{service="order-service",client_name="payment-service"}[5m]))`.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| payment-service slow (its own upstream or database slow) | the query above; `http_client_requests_seconds_max{client_name="payment-service"}` | `exception="HttpTimeoutException"`, max near the 3s read timeout |
| payment-service down or wrong base URL or port | the query above; `docker compose -f infra/compose.yaml config order-service` | `exception="ConnectException"`, calls fail in milliseconds; URL in the WARN line differs from the payment-service address |
| Host unreachable (network path, firewall) | the query above; WARN cause text | `HttpConnectTimeoutException`, calls end near the 1s connect timeout |
| payment-service answering 5xx | the query above | `outcome="SERVER_ERROR"`, `status="503"` or `"500"`; see the payment-service runbook |
| Unrecognized reply | LogQL `{service="order-service"} \|= "returned no status"` | lines present |
| Saving the outcome failed after payment | LogQL `{service="order-service"} \|= "ERROR"`; `http_server_requests_seconds_count{service="order-service",uri="/orders",status="500"}` | 500s on `/orders` with database or optimistic-locking errors |

### 503 on `POST /orders`

The body says `Inventory unavailable` and carries the `orderId`; the order is stored as `REJECTED_INVENTORY_UNAVAILABLE`. Read the WARN line: `{service="order-service"} |= "Inventory unavailable for order"`, or that order's `order_events` (section 8). Then look the same order id up in inventory-service's `reservation_events`: a read timeout leaves the reservation's outcome unknown to this service, and inventory-service may still have reserved the stock.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| inventory-service returning 5xx | `sum by (status) (rate(http_client_requests_seconds_count{service="order-service",client_name="inventory-service"}[5m]))` | `status="500"`, `outcome="SERVER_ERROR"`; continue in the inventory-service runbook |
| inventory-service slow | same query, by `exception` | `exception="HttpTimeoutException"` |
| inventory-service down or unreachable | same query; `docker compose -f infra/compose.yaml ps inventory-service` | `exception="ConnectException"`, container not healthy |
| Unexpected reply | WARN cause `answered <code> with status <status>` | a code or status other than 201 `RESERVED` or 409 `INSUFFICIENT_STOCK` |

### 409 on `POST /orders`

The body says `Insufficient stock` and carries the `orderId`; the order is stored as `REJECTED_OUT_OF_STOCK`. Check `curl -s localhost:18083/stock/<sku>`: low `available`, or 404 for an unknown SKU. Reservations are never released, so stock only goes down.

### Slow `POST /orders`

Compare `rate(http_server_requests_seconds_sum{service="order-service",uri="/orders"}[5m]) / rate(http_server_requests_seconds_count{service="order-service",uri="/orders"}[5m])` with the same ratio of `http_client_requests_seconds_*` per `client_name`. If one dependency accounts for the time, follow it. If neither does, suspect the database: check the `hikaricp_connections_pending{service="order-service"}` series and `pg_stat_activity` (see Safe actions).

### `lab_orders` reports NaN

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| Database down or unreachable | `docker compose -f infra/compose.yaml exec postgres pg_isready -U lab_app -d orders` | not accepting connections |
| `orders` table locked or count query slow | `pg_stat_activity` query below | sessions waiting on a lock for `orders` |
| Pool exhausted in order-service | `hikaricp_connections_pending{service="order-service"}` | above 0 for a sustained period |

The WARN `Cannot count orders per status` carries the exception; `Order counts are readable again` marks recovery.

## 7. Safe actions

- **Check status:** `docker compose -f infra/compose.yaml ps` and `curl -s localhost:18080/actuator/health`.
- **Check configuration:** `docker compose -f infra/compose.yaml config order-service` shows the resolved environment. Confirm both base URLs point at `payment-service:8081` and `inventory-service:8083`.
- **Roll back a configuration change:** unset the overriding shell variable (for example `unset ORDER_PAYMENT_BASEURL`), then recreate: `docker compose -f infra/compose.yaml up -d --no-deps --wait order-service`.
- **Restart:** `docker compose -f infra/compose.yaml restart order-service`. Counters restart from zero; stored orders are kept.
- **Inspect database sessions:** `docker compose -f infra/compose.yaml exec postgres psql -U lab_app -d orders -c "SELECT pid, state, wait_event_type, now() - query_start AS age, query FROM pg_stat_activity WHERE datname = 'orders'"`.
- Do not edit order statuses by hand: a `PAYMENT_PENDING` order may already have a payment stored in payment-service.

## 8. Where the evidence is

Database `orders` on the `postgres` container. Run SQL with `docker compose -f infra/compose.yaml exec postgres psql -U lab_app -d orders -c "<sql>"`.

| Table | Columns | Content |
|-------|---------|---------|
| `orders` | `id`, `customer_id`, `status`, `version`, `created_at`, `updated_at` | every order that reached the database, in its current status, rejected ones included |
| `order_events` | `id`, `order_id`, `occurred_at`, `type`, `outcome`, `detail`, `duration_ms` | append-only: one row per step of an order, never updated or deleted |

`order_events.type` is `ORDER_RECEIVED`, `INVENTORY_RESERVED`, `INVENTORY_REJECTED`, `INVENTORY_UNAVAILABLE`, `PAYMENT_AUTHORIZED`, `PAYMENT_DECLINED`, `PAYMENT_FAILED` or `PAYMENT_UNAVAILABLE`. `outcome` is the order status after the event. `detail` is compact JSON: the customer and items for `ORDER_RECEIVED`, or the exception class, URL and message of an unavailable dependency. `duration_ms` is how long the call to the other service took. When an event cannot be stored, the `Order event not stored` WARN line carries it instead.

- **One order's history:** `SELECT occurred_at, type, outcome, duration_ms, detail FROM order_events WHERE order_id = '<id>' ORDER BY occurred_at`.
- **Recent dependency failures:** `SELECT occurred_at, order_id, type, duration_ms, detail FROM order_events WHERE type IN ('INVENTORY_UNAVAILABLE', 'PAYMENT_UNAVAILABLE') ORDER BY occurred_at DESC LIMIT 20`.
- **Orders per status:** `SELECT status, count(*) FROM orders GROUP BY status`.

The same order id is the key in inventory-service's `reservation_events` and payment-service's `payment_events` (see their runbooks), so one order can be followed across the three databases.
