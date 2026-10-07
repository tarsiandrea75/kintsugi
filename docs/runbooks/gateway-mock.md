# Runbook: gateway-mock

## 1. Purpose

gateway-mock stands in for the external payment gateway. payment-service calls it to authorize a payment. With its normal behavior it answers every valid request at once with `{"status": "AUTHORIZED"}`.

It has no database. Its only state is its current behavior, held in memory: a latency (`latencyMs`) and an optional error status (`errorStatus`). A restart returns it to latency 0 and no error.

## 2. Dependencies

None. It calls no service and has no database. Requests run on virtual threads (`spring.threads.virtual.enabled=true`), so many slow requests do not exhaust Tomcat's platform threads, and the admin API stays responsive.

## 3. Endpoints

Host port 18082 (container port 8082).

| Endpoint | Status codes |
|----------|--------------|
| `POST /authorize` | 200 `{"status": "AUTHORIZED"}`; 400 when `orderId` is missing, null or not a UUID; 429 `{"error": "rate limited"}`, 500 `{"error": "internal error"}` or 503 `{"error": "unavailable"}` when an error status is set; 503 when its wait is interrupted, for example at shutdown |
| `GET /admin/behavior` | 200 with the current behavior |
| `PUT /admin/behavior` | 200 with the new behavior; 400 for invalid values (behavior unchanged) |
| `DELETE /admin/behavior` | 200 with the reset behavior |
| `GET /actuator/health` | 200 when up |
| `GET /actuator/prometheus` | 200, Prometheus text format |

## 4. Configuration

| Property | Value |
|----------|-------|
| `server.port` | `8082` |
| `spring.threads.virtual.enabled` | `true` |
| `management.endpoints.web.exposure.include` | `health,prometheus` |

payment-service reaches it at `http://gateway-mock:8082` (`KINTSUGI_GATEWAY_BASEURL` in compose).

### Lab control: admin behavior API

The admin API changes how `POST /authorize` answers, at runtime and without a restart. It exists only in the lab. It has no authentication and must not be exposed outside the local machine.

| Field | Allowed values | Normal value |
|-------|----------------|--------------|
| `latencyMs` | 0 to 60000 | `0` |
| `errorStatus` | `null`, `429`, `500`, `503` | `null` |

- `PUT /admin/behavior` replaces the whole behavior. A missing `errorStatus` clears any error.
- A request reads the behavior once, when it starts. After a reset, requests already waiting still finish with the old behavior.
- Every change is logged at INFO with the old and the new values.

## 5. Key signals

**Metrics** (Prometheus label `service="gateway-mock"`):

- `gateway_authorize_inflight`: `POST /authorize` requests currently waiting before they answer. 0 when idle.
- `http_server_requests_seconds_count|_sum|_max` with `uri="/authorize"` or `/admin/behavior`, and `status`.

**Grafana panel:** "Gateway in-flight authorizations".

**Log lines** (Loki stream `{service="gateway-mock"}`):

| Level | Message |
|-------|---------|
| INFO | `Gateway behavior changed: latencyMs <old> -> <new>, errorStatus <old> -> <new>` |
| INFO | `Gateway behavior reset: latencyMs <old> -> 0, errorStatus <old> -> none` |

`none` means no error status.

## 6. Symptoms

Run PromQL in Grafana Explore or with `curl -s localhost:19090/api/v1/query --data-urlencode 'query=<expr>'`. Run LogQL in Grafana Explore.

### Slow `POST /authorize`

Check `gateway_authorize_inflight` and `sum(rate(http_server_requests_seconds_sum{service="gateway-mock",uri="/authorize"}[1m])) / sum(rate(http_server_requests_seconds_count{service="gateway-mock",uri="/authorize"}[1m]))`.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| A latency configured through the admin API | `curl -s localhost:18082/admin/behavior`; LogQL `{service="gateway-mock"} \|= "Gateway behavior changed"` | `latencyMs` above 0 and a matching change line |
| Request backlog | `gateway_authorize_inflight` | in-flight keeps growing while the request rate is high |
| Host CPU or memory pressure | `docker stats --no-stream` | the container near its limits |
| Network delay between payment-service and the gateway | payment-service latency against the gateway's own latency | payment-service waits much longer than the gateway reports |

### Non-200 answers on `POST /authorize`

Check `sum by (status) (rate(http_server_requests_seconds_count{service="gateway-mock",uri="/authorize"}[1m]))`.

| Possible cause | How to check | Points to it |
|----------------|--------------|--------------|
| An error status configured through the admin API | `curl -s localhost:18082/admin/behavior` | `errorStatus` not `null`; every request answers that status |
| Invalid requests from the caller | the query above | `status="400"` |
| Shutdown or restart in progress | `docker compose -f infra/compose.yaml ps gateway-mock` | a short burst of 503 while the container restarts |

### payment-service cannot reach the gateway

`gateway_authorize_inflight` stays 0 and `/authorize` shows no traffic while payment-service logs `Payment gateway unavailable`. Possible causes: the container is down (`docker compose -f infra/compose.yaml ps gateway-mock`), the health check fails (`curl -s localhost:18082/actuator/health`), or payment-service points at another URL (see the payment-service runbook).

## 7. Safe actions

- **Read the current behavior:** `curl -s localhost:18082/admin/behavior`. The normal answer is `{"latencyMs":0,"errorStatus":null}`.
- **Find who changed it and when:** LogQL `{service="gateway-mock"} |~ "Gateway behavior (changed|reset)"`.
- **Restore the normal behavior:** `curl -s -X DELETE localhost:18082/admin/behavior`. Requests already waiting finish with the old behavior; new ones see the reset.
- **Restart:** `docker compose -f infra/compose.yaml restart gateway-mock`. The behavior returns to its normal values.
- **Check health:** `curl -s localhost:18082/actuator/health`.
