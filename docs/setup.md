# Setup — Windows 11

Environment checklist for local development. Shell: Git Bash or PowerShell. Last verified: 2026-10-05.

Pinned versions and runtime defaults are recorded in [ADR-006](adr/ADR-006-stack-versions.md). Use the minimums below.

## Tools

- [ ] **Java 21** (LTS)
  - Verify: `java -version`
  - Install: `winget install Microsoft.OpenJDK.21` (or any JDK 21 distribution)
- [ ] **Maven** (optional)
  - The project ships the Maven Wrapper in `agent/`: use `./mvnw` (Git Bash) or `mvnw.cmd` (PowerShell), so a local Maven is not required.
  - Verify (only if installed): `mvn -v`
  - Install: `winget install Apache.Maven` (or download from maven.apache.org and add `bin` to `PATH`)
- [ ] **Docker Desktop**
  - Verify: `docker version`
  - Install: `winget install Docker.DockerDesktop` (WSL 2 backend)
- [ ] **Ollama >= 0.4.6**
  - Verify: `ollama --version`
  - Install: `winget install Ollama.Ollama`
  - Why 0.4.6: streaming responses with tool calling need it (non-streaming tool calling works from 0.2.8).
- [ ] **Python 3** (for `evals/` only)
  - Verify: `python --version`
  - Install: `winget install Python.Python.3.12` (or any current Python 3)

## Local model

Hardware: laptop with 32 GB RAM and no GPU, so quantized ~8B models.

| Role | Model | Notes |
|------|-------|-------|
| Default | `granite4.1:8b` | Built for tool calling and RAG, 128K context, Apache 2.0 |
| Alternative | `qwen3:8b` | Practically tied in the smoke test; needs thinking disabled to stay fast on CPU |
| Not chosen | `qwen3.5:9b` | Passed the smoke test but more than twice as slow |

Status: **chosen**. All three candidates passed the 10-prompt tool-calling smoke test; see [ADR-003](adr/ADR-003-local-models-default.md) and the [reports](evidence/smoke-test/).

- [ ] Pull the default model: `ollama pull granite4.1:8b`
- [ ] Pull alternatives when testing: `ollama pull qwen3:8b`, `ollama pull qwen3.5:9b`
- [x] Run the 10-prompt tool-calling smoke test and record the result

Smoke test for one model (from `agent/`):

```bash
./mvnw test -Plive -Dtest=LiveToolCallingSmokeTest -Dkintsugi.model=<model>
```

- qwen models also need `-Dspring.ai.ollama.chat.think=false`.
- The report is written to `agent/target/smoke-test/`.

## Build and run

Run from `agent/` (use `mvnw.cmd` on PowerShell):

| Goal | Command | Needs |
|------|---------|-------|
| Unit tests | `./mvnw test` | Nothing (offline, no Ollama) |
| Live tests | `./mvnw test -Plive` | Ollama running with `granite4.1:8b`; includes the smoke test, about 3–7 min per model |
| Demo | `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo` | Ollama running with `granite4.1:8b` |

If Ollama is down, the demo prints `Ollama not reachable at <url>. Start it with: ollama serve` and exits with code 1, without a stack trace.

## order-service

`lab/order-service` is a standalone Maven project with its own wrapper. Build and test it with:

```bash
cd lab/order-service && ./mvnw test
```

- Docker must be running: the persistence and end-to-end tests start `postgres:18.6` through Testcontainers.
- The stack and versions are recorded in [ADR-007](adr/ADR-007-lab-persistence-stack.md), and the behavior in the [living spec](../openspec/specs/order-service/spec.md).

## payment-service

`lab/payment-service` is a standalone Maven project with the same stack as order-service. It listens on port 8081. Build and test it with:

```bash
cd lab/payment-service && ./mvnw test
```

- Docker must be running, for the same Testcontainers reason as order-service.
- It asks gateway-mock for each payment's outcome; the tests use a fake gateway on loopback, so gateway-mock need not run.
- The behavior is in the [living spec](../openspec/specs/payment-service/spec.md).

payment-service reaches gateway-mock through these properties (set them like the datasource ones below, or as environment variables such as `KINTSUGI_GATEWAY_READ_TIMEOUT`):

| Property | Default | Meaning |
|----------|---------|---------|
| `kintsugi.gateway.base-url` | `http://localhost:8082` | gateway-mock root URL |
| `kintsugi.gateway.connect-timeout` | `1s` | longest wait to open a connection |
| `kintsugi.gateway.read-timeout` | `15s` | longest wait for the response once the request is sent |

## gateway-mock

`lab/gateway-mock` simulates the external payment gateway. It has no database, listens on port 8082, and needs no Docker. Build and test it with:

```bash
cd lab/gateway-mock && ./mvnw test
```

Its latency is the fault F1 control point, and its error status is the fault F2 control point. The admin API has no authentication, and the state is lost on restart:

| Call | Effect |
|------|--------|
| `PUT /admin/behavior` with `{"latencyMs": <0..60000>, "errorStatus": <429, 500 or 503>}` | Replaces the whole behavior: every `POST /authorize` waits `latencyMs`, then answers `errorStatus` if it is set. A missing `errorStatus` means no error |
| `GET /admin/behavior` | Returns the current behavior |
| `DELETE /admin/behavior` | Resets the latency to 0 and clears the error status |

Each change is logged at `INFO`. The behavior is in the [living spec](../openspec/specs/gateway-mock/spec.md).

## Lab with Docker Compose

One command starts the whole M1 lab: PostgreSQL, the three services, Prometheus, Loki, Alloy and Grafana (ADR-008, [living spec](../openspec/specs/lab-observability/spec.md)). Run it from the repository root.

```bash
docker compose -f infra/compose.yaml up -d --build --wait   # start; the first build takes about a minute
docker compose -f infra/compose.yaml down                   # stop, keep the data
docker compose -f infra/compose.yaml down -v                # stop and delete the data volumes
```

The compose project is called `kintsugi-lab`, so these commands never touch other containers. Every port is bound to `127.0.0.1`, so the lab is reachable only from this machine:

| What | URL |
|------|-----|
| order-service | <http://localhost:18080> |
| payment-service | <http://localhost:18081> |
| gateway-mock | <http://localhost:18082> |
| Grafana | <http://localhost:13000> |
| Prometheus | <http://localhost:19090> |

PostgreSQL, Loki and Alloy have no host port.

Grafana opens on the "Lab — Faults" dashboard (uid `lab-f1`) without a login (anonymous Viewer). To edit, log in as `admin`. The password comes from `GRAFANA_ADMIN_PASSWORD` and defaults to `kintsugi-lab`. To change it, set the variable in your shell or in `infra/.env` before `up`.

Loki needs about a minute after startup before it answers queries. Until then, the WARN-lines and gateway-rejections panels can stay empty.

### Check that F1 is visible

```bash
infra/scripts/verify-f1.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-f1.sh --down   # the same, then `down -v`
```

The script waits for Loki, places a healthy order (`PAID`), sets the gateway latency to 10 s, and places an order that ends `PAYMENT_PENDING`. It then checks that Prometheus sees the pending count rise and the in-flight gauge above 0, and that Loki has order-service's `WARN` line for that order. It prints one `PASS` or `FAIL` line per check and exits non-zero on any failure. It always resets the gateway on exit, even when a check fails. It needs `docker` and `curl`. It takes about 1.5 minutes on a fresh stack and under a minute on a running one.

### Check that F2 is visible

```bash
infra/scripts/verify-f2.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-f2.sh --down   # the same, then `down -v`
```

The script places a healthy order (`PAID`), reads the `FAILED` payment and `PAYMENT_FAILED` order counts, makes the gateway answer 429, and places three orders that each end `PAYMENT_FAILED`. It then checks that Prometheus sees both counts rise by at least 3 and that Loki has payment-service's `WARN` line for one of those orders. Last, it resets the gateway and checks that a new order is `PAID` again. Output, exit status and the reset on exit work as in the F1 check. It takes about 1.5 minutes on a fresh stack and under a minute on a running one. Both scripts share their helpers in `infra/scripts/lib.sh`.

### Reproduce F2 by hand

With the compose lab running, watch the "Lab — Faults" dashboard: under F2, `FAILED` rises in "Payments by status", `PAYMENT_FAILED` rises in "Orders by status", and "Gateway rejections" goes above 0.

1. Make the gateway reject every authorization with 429:

   ```bash
   curl -s -X PUT localhost:18082/admin/behavior -H 'Content-Type: application/json' -d '{"latencyMs": 0, "errorStatus": 429}'
   ```

2. Place an order. The response is 201 with status `PAYMENT_FAILED`, and payment-service logs a `WARN` line with the order id and status 429:

   ```bash
   curl -s -X POST localhost:18080/orders -H 'Content-Type: application/json' \
     -d '{"customerId": "customer-1", "items": [{"sku": "SKU-1", "quantity": 2}]}'
   ```

3. Restore the gateway; the next order is `PAID` again:

   ```bash
   curl -s -X DELETE localhost:18082/admin/behavior
   ```

Why F2 looks different from F1: a 429 is an answer, not a timeout. payment-service stores a `FAILED` payment and answers at once, so the order fails fast instead of staying `PAYMENT_PENDING`. The agent tells the two faults apart by that symptom.

## Run the lab services without Docker Compose

To debug one service from the IDE, run each service by hand, each with its own PostgreSQL database. The ports below are the services' defaults, not the compose ones.

1. Start one PostgreSQL 18.6 container per service:

   ```bash
   docker run -d --name orders-db   -e POSTGRES_DB=orders   -e POSTGRES_USER=lab -e POSTGRES_PASSWORD=lab -p 5432:5432 postgres:18.6
   docker run -d --name payments-db -e POSTGRES_DB=payments -e POSTGRES_USER=lab -e POSTGRES_PASSWORD=lab -p 5433:5432 postgres:18.6
   ```

2. Start gateway-mock on 8082:

   ```bash
   cd lab/gateway-mock && ./mvnw spring-boot:run
   ```

3. In another shell, start payment-service on 8081 (Flyway creates its schema on startup):

   ```bash
   cd lab/payment-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5433/payments --spring.datasource.username=lab --spring.datasource.password=lab"
   ```

4. In another shell, start order-service on 8080:

   ```bash
   cd lab/order-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5432/orders --spring.datasource.username=lab --spring.datasource.password=lab"
   ```

5. Place an order. With a healthy gateway it ends in `PAID`:

   ```bash
   curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' \
     -d '{"customerId": "customer-1", "items": [{"sku": "SKU-1", "quantity": 2}]}'
   ```

   Stop payment-service and repeat: the response is still 201, the order stays `PAYMENT_PENDING`, and order-service logs a `WARN` line with the order id. That is the symptom of fault F1.

### Reproduce F1 by hand

The quickest route is the compose lab: start it as in [Lab with Docker Compose](#lab-with-docker-compose) and use ports 18080 (orders) and 18082 (gateway) in the commands below. Watch the "Lab — Faults" dashboard while the fault is active: the `PAYMENT_PENDING` series rises, the gateway in-flight panel shows the waiting authorization, and the WARN line appears. `infra/scripts/verify-f1.sh` runs the same steps and checks them.

> Run by hand on 2026-10-06 (WSL, Docker Engine 29.4.0), on alternative ports (databases 55432/55433, services 18080/18081/18082) because other local projects held 5432 and 8080. Observed:
> - a healthy order returned `PAID` in 0.48 s;
> - with 10 s of gateway latency the order returned 201 `PAYMENT_PENDING` after 3.01 s, and order-service logged the `WARN` line;
> - after the gateway answered, payment-service had stored an `AUTHORIZED` payment for that pending order.
>
> If your ports are taken, pass `--server.port`, the datasource URL, `kintsugi.payment.base-url` and `kintsugi.gateway.base-url` the same way as the datasource arguments.

With the compose lab (ports 18080 and 18082) or the three services run by hand (ports 8080 and 8082):

1. Slow the gateway down to 10 seconds:

   ```bash
   curl -s -X PUT localhost:8082/admin/behavior -H 'Content-Type: application/json' -d '{"latencyMs": 10000}'
   ```

2. Place an order, as in step 5. After about 3 seconds (order-service's read timeout) the response is 201 with status `PAYMENT_PENDING`, and order-service logs a `WARN` line with the order id.
3. Restore the gateway:

   ```bash
   curl -s -X DELETE localhost:8082/admin/behavior
   ```

Why the order stays pending: order-service waits 3s for payment-service, while payment-service waits up to 15s for the gateway. With 10s of latency, order-service gives up first. payment-service still gets the answer after 10s and stores the payment (observed), so the order stays `PAYMENT_PENDING` even though a payment was authorized. That mismatch is part of what the agent has to diagnose.

order-service reaches payment-service through these properties (set them like the datasource ones above, or as environment variables such as `KINTSUGI_PAYMENT_READ_TIMEOUT`):

| Property | Default | Meaning |
|----------|---------|---------|
| `kintsugi.payment.base-url` | `http://localhost:8081` | payment-service root URL |
| `kintsugi.payment.connect-timeout` | `1s` | longest wait to open a connection |
| `kintsugi.payment.read-timeout` | `3s` | longest wait for the response once the request is sent |
