# Runbook: inventory-service

## 1. Purpose

inventory-service holds the available stock per SKU in PostgreSQL and reserves the items of an order. order-service calls it before storing an order. A reservation is atomic: either every line is reserved or none is.

The seed holds `SKU-1` to `SKU-5`, 1,000,000 units each. Reservations are never released, so stock only decreases and a repeated call reserves twice. Every reservation attempt is recorded in `reservation_events` with its order id (see section 8).

## 2. Dependencies

| Dependency | Use | Limits |
|------------|-----|--------|
| PostgreSQL | database `inventory` on `postgres:5432`, tables `stock` and `reservation_events` | Hikari pool of 10 connections; a request waits at most 2000 ms for a free connection |

It calls no other service. Each reservation holds one pooled connection for its whole transaction.

## 3. Endpoints

Host port 18083 (container port 8083).

| Endpoint | Status codes |
|----------|--------------|
| `POST /reservations` | 201 `{"orderId": ..., "status": "RESERVED"}`; 409 `INSUFFICIENT_STOCK` (short or unknown SKU, nothing changed); 400 invalid body; 500 `No database connection available` |
| `GET /stock/{sku}` | 200 `{"sku": ..., "available": ...}`; 404 unknown SKU |
| `GET /actuator/health` | 200 when up |
| `GET /actuator/prometheus` | 200, Prometheus text format |

## 4. Configuration

| Property | Value when unset | Set by compose |
|----------|------------------|----------------|
| `spring.datasource.hikari.maximum-pool-size` | `10` | `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: ${INVENTORY_POOL_SIZE:-10}` |
| `spring.datasource.hikari.connection-timeout` | `2000` (ms) | — |
| `kintsugi.inventory.reservation-delay` | `0s` | `KINTSUGI_INVENTORY_RESERVATIONDELAY: ${INVENTORY_RESERVATION_DELAY:-0s}` |
| `spring.datasource.url` | — | `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/inventory` |
| `server.port` | `8083` | — |

### Lab control: reservation delay

`kintsugi.inventory.reservation-delay` exists only in the lab; a production service would not have it. When above zero, every reservation runs `SELECT pg_sleep(<seconds>)` inside its transaction before updating stock, so it holds a pooled connection for the whole delay. In a healthy lab it is `0s`. Treat any other value as a configuration change to review.

## 5. Key signals

**Metrics** (Prometheus label `service="inventory-service"`):

- `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending`, `hikaricp_connections_max`: pool state.
- `hikaricp_connections_timeout_total`: requests that found no free connection within the connection timeout.
- `http_server_requests_seconds_count|_sum|_max` with `uri` (`/reservations`, `/stock/{sku}`) and `status`.

**Grafana panels:** "inventory DB pool", "inventory 5xx rate".

**Log lines** (Loki stream `{service="inventory-service"}`):

| Level | Message |
|-------|---------|
| WARN | `No database connection available: event=RESERVATION_ATTEMPT order=<id> outcome=DATABASE_UNAVAILABLE duration_ms=<ms> detail="<lines JSON>" cause="<pool message>"`, for a reservation that found no connection |
| WARN | `No database connection available: path=<path> outcome=DATABASE_UNAVAILABLE cause="<pool message>"`, for any other request that found no connection |
| WARN | `Reservation event not stored: event=RESERVATION_ATTEMPT order=<id> outcome=<outcome> duration_ms=<ms> detail="<lines JSON>" record_failure=<class> cause="<database message>"` |

The pool message reads, for example, `... Connection is not available, request timed out after 2000ms`. The values are `key=value` pairs, readable by Loki's `logfmt` parser. A reservation attempt that could not reach the database exists only in these lines.

## 6. Symptoms

Run PromQL in Grafana Explore or with `curl -s localhost:19090/api/v1/query --data-urlencode 'query=<expr>'`. Run LogQL in Grafana Explore.

### 500 on `POST /reservations`

Check `sum by (uri, status) (rate(http_server_requests_seconds_count{service="inventory-service",status=~"5.."}[1m]))` and LogQL `{service="inventory-service"} |= "No database connection available"`.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| Pool too small for the load | `hikaricp_connections_max` and `hikaricp_connections_active`; `docker compose -f infra/compose.yaml config inventory-service` | active equals max, pending above 0, a pool size lower than expected |
| Slow queries holding connections | `pg_stat_activity` query below; `rate(http_server_requests_seconds_sum{service="inventory-service",uri="/reservations"}[1m]) / rate(http_server_requests_seconds_count{service="inventory-service",uri="/reservations"}[1m])` | long-running `active` sessions; successful reservations take seconds |
| Locks on `stock` rows | `pg_stat_activity` with `wait_event_type = 'Lock'` | sessions waiting on locks |
| Traffic spike | `sum(rate(http_server_requests_seconds_count{service="inventory-service",uri="/reservations"}[1m]))` | request rate well above its usual level |
| Database down or unreachable | `docker compose -f infra/compose.yaml exec postgres pg_isready -U lab -d inventory` | not accepting connections; the WARN message names a connection error rather than a timeout on a busy pool |
| Other server error | LogQL `{service="inventory-service"} \|= "ERROR"` | stack traces without the WARN line above |

### Slow `POST /reservations`

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| Waiting for a pool connection | `hikaricp_connections_pending` | above 0; latency near 2s |
| Slow statements in the transaction | `pg_stat_activity` | `active` sessions with a large age |
| Database host saturated | `docker stats --no-stream` | the postgres container near its limits |

Callers see a slow inventory as 503 on `POST /orders` once order-service's 3s read timeout fires (see the order-service runbook).

### 409 on `POST /reservations`

Not an error of this service. Check `curl -s localhost:18083/stock/<sku>`: `available` lower than the requested quantity, or 404 for an unknown SKU.

## 7. Safe actions

- **Check status:** `docker compose -f infra/compose.yaml ps inventory-service` and `curl -s localhost:18083/actuator/health`.
- **Check configuration:** `docker compose -f infra/compose.yaml config inventory-service`. Expect `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: "10"` and `KINTSUGI_INVENTORY_RESERVATIONDELAY: 0s`.
- **Roll back a configuration change:** `unset INVENTORY_POOL_SIZE INVENTORY_RESERVATION_DELAY`, then `docker compose -f infra/compose.yaml up -d --no-deps --wait inventory-service`.
- **Restart:** `docker compose -f infra/compose.yaml restart inventory-service`. In-flight reservations fail; stock already reserved stays reserved.
- **Inspect database sessions:** `docker compose -f infra/compose.yaml exec postgres psql -U lab -d inventory -c "SELECT pid, state, wait_event_type, now() - query_start AS age, query FROM pg_stat_activity WHERE datname = 'inventory'"`.
- Do not raise the pool size as a first response: it hides slow queries and moves load onto the database.

## 8. Where the evidence is

Database `inventory` on the `postgres` container. Run SQL with `docker compose -f infra/compose.yaml exec postgres psql -U lab -d inventory -c "<sql>"`.

| Table | Columns | Content |
|-------|---------|---------|
| `stock` | `sku`, `available` | current units per SKU |
| `reservation_events` | `id`, `order_id`, `occurred_at`, `type`, `outcome`, `detail`, `duration_ms` | append-only: one row per reservation attempt that reached the database, never updated or deleted |

`reservation_events.type` is `RESERVATION_ATTEMPT`. `outcome` is `RESERVED` (committed with the stock decrement, so it exists only if the decrement does) or `INSUFFICIENT_STOCK` (nothing changed). `detail` holds the requested lines as compact JSON. `duration_ms` is how long the reservation took, any wait for its statements included. `occurred_at` is when the outcome was recorded, near the end of the transaction.

- **One order's reservations:** `SELECT occurred_at, outcome, duration_ms, detail FROM reservation_events WHERE order_id = '<id>' ORDER BY occurred_at`.
- **Slow reservations:** `SELECT occurred_at, order_id, outcome, duration_ms FROM reservation_events WHERE duration_ms > 1000 ORDER BY occurred_at DESC LIMIT 20`.
- **Reservations for orders the caller rejected:** order ids are shared with order-service's `orders` table, in the `orders` database. List the `RESERVED` order ids here, then look them up there: `SELECT order_id FROM reservation_events WHERE outcome = 'RESERVED' AND occurred_at > now() - interval '15 minutes'`. A `RESERVED` row for an order stored as `REJECTED_INVENTORY_UNAVAILABLE` is stock taken for an order that does not proceed.
