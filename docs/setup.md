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

Each change is logged as one `INFO` line with `key=value` pairs (`Gateway behavior changed: event=BEHAVIOR_CHANGED ...` or `Gateway behavior reset: event=BEHAVIOR_RESET ...`), and so is every `POST /authorize` (`Authorize answered: event=AUTHORIZE order=<id> latency_ms=<configured> answered=<status> duration_ms=<actual>`): the gateway has no database, so these lines are its record. The behavior is in the [living spec](../openspec/specs/gateway-mock/spec.md).

## Lab with Docker Compose

One command starts the whole M1 lab: PostgreSQL, the four services, the fault injector, Prometheus, Loki, Alloy and Grafana (ADR-008, ADR-009, [living spec](../openspec/specs/lab-observability/spec.md)). Run it from the repository root.

```bash
export KINTSUGI_REPO_DIR="$(git rev-parse --show-toplevel)" DOCKER_GID="$(stat -c %g /var/run/docker.sock)"
docker compose -f infra/compose.yaml up -d --build --wait   # start; the first build takes about a minute
docker compose -f infra/compose.yaml down                   # stop, keep the data
docker compose -f infra/compose.yaml down -v                # stop and delete the data volumes
```

Run the `export` line first in every new shell. Every `docker compose -f infra/compose.yaml` command in this document needs it, including `exec`, `logs` and the "Reproduce by hand" recipes: compose reads the whole file for any command, and without `DOCKER_GID` it stops with `required variable DOCKER_GID is missing a value: set DOCKER_GID to the gid of /var/run/docker.sock`. The verify scripts export both variables themselves.

The compose project is called `kintsugi-lab`, so these commands never touch other containers. Every port is bound to `127.0.0.1`, so the lab is reachable only from this machine:

| What | URL |
|------|-----|
| order-service | <http://localhost:18080> |
| payment-service | <http://localhost:18081> |
| gateway-mock | <http://localhost:18082> |
| inventory-service | <http://localhost:18083> |
| fault-injector | <http://localhost:18084> |
| Grafana | <http://localhost:13000> |
| Prometheus | <http://localhost:19090> |

PostgreSQL, Loki and Alloy have no host port.

### Variables

Every variable except `DOCKER_GID` has a lab default; nothing here is a secret. Put overrides in `infra/.env` (a template is `infra/.env.example`), not only in the shell: the fault injector recreates services from the same compose file and reads `infra/.env`, but it never sees your shell.

| Variable | Default | Used for |
|----------|---------|----------|
| `KINTSUGI_REPO_DIR` | `$PWD` | absolute host path of the repository; fault-injector mounts it read-only at that same path |
| `DOCKER_GID` | none, required | group id of `/var/run/docker.sock`, added to fault-injector so it can use Docker; a guessed default would start an injector that cannot reach Docker |
| `POSTGRES_BOOTSTRAP_PASSWORD` | `postgres` | the bootstrap superuser `postgres`, used only by the init script |
| `LAB_APP_DB_PASSWORD` | `lab_app` | the role `lab_app`, which the four services connect as |
| `FAULTS_DB_PASSWORD` | `faults` | the role `faults`, which the fault injector connects as |
| `GRAFANA_ADMIN_PASSWORD` | `kintsugi-lab` | Grafana's `admin` login |

`KINTSUGI_REPO_DIR` defaults to the current directory, which is right only from the repository root. From anywhere else, export it as above; the verify scripts set it themselves. With a wrong value the injector does not start, because it cannot read the compose file.

### Database roles

One PostgreSQL instance holds four databases (ADR-007, ADR-009):

- `postgres` is the bootstrap superuser. Only `infra/postgres/init/01-roles-and-databases.sh` uses it.
- `lab_app` is a non-superuser that owns `orders`, `payments` and `inventory`. The four services connect as `lab_app`.
- `faults` is a non-superuser that owns the `faults` database, where the injector records the true cause of each fault. `CONNECT` on `faults` is revoked from `PUBLIC`, so `lab_app` cannot connect to it. A superuser would ignore that revoke, which is why no service or agent role is one.

```bash
docker compose -f infra/compose.yaml exec -T postgres psql -U lab_app -d orders -c "select count(*) from orders"
docker compose -f infra/compose.yaml exec -T postgres psql -U faults -d faults -c "select fault_id, status from injections"
```

The init script runs only on an empty data volume. **Upgrading a lab created before the fault injector:** its volume has the old `lab` superuser and no `lab_app` or `faults`, so run `docker compose -f infra/compose.yaml down -v` once before the first start. The same applies after changing any database password.

### Fault injector API

The fault injector (`lab/fault-injector`, [spec](../openspec/specs/fault-injector/spec.md)) activates one catalog fault at a time, records its true cause in the `faults` database, and restores the lab. F1 and F2 change gateway-mock's behavior; F3 and F4 recreate one service with `docker compose ... up -d --no-deps --no-build --wait <service>`, never the injector itself or the whole stack. It is invisible to the agent's data sources: Alloy drops its logs and Prometheus does not scrape it. Read its logs with `docker compose -f infra/compose.yaml logs fault-injector`.

```bash
curl -s localhost:18084/faults/catalog                                   # [{"fault":"F1"}, …, {"fault":"F4"}]
curl -s -X POST localhost:18084/faults -H 'Content-Type: application/json' -d '{"fault": "F1"}'   # 201 {id, fault, injectedAt}
curl -s -i localhost:18084/faults/active                                 # 200 {id, fault, status, injectedAt}, or 204
curl -s -X POST localhost:18084/faults/<id>/restore                      # 200 {..., status RESTORED, restoredAt}
```

A second `POST /faults` while one is active answers 409; an F3 or F4 step takes as long as the recreate, up to about two minutes. No answer ever contains the true cause: read it as the `faults` role, as above.

The "Reproduce by hand" recipes below change the lab directly. They are useful to watch a fault, but they record no truth row, so scored incidents always go through the injector.

Grafana opens on the "Lab — Faults" dashboard (uid `lab-f1`) without a login (anonymous Viewer). To edit, log in as `admin`. The password comes from `GRAFANA_ADMIN_PASSWORD` and defaults to `kintsugi-lab`. To change it, set the variable in `infra/.env` before `up` (see [Variables](#variables)).

Loki needs about a minute after startup before it answers queries. Until then, the WARN-lines and gateway-rejections panels can stay empty.

### Check that F1 is visible

```bash
infra/scripts/verify-f1.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-f1.sh --down   # the same, then `down -v`
```

The script waits for Loki, restores any injection an earlier run left active, places a healthy order (`PAID`), injects F1 through the fault injector (gateway latency 10 s), and places an order that ends `PAYMENT_PENDING`. It then checks that Prometheus sees the pending count rise and the in-flight gauge above 0, and that Loki has order-service's `WARN` line for that order. It restores F1 through the injector and checks the truth: one `injections` row with the right fault and root-cause service, `RESTORED`, read as `faults`; `lab_app` is not a superuser and cannot connect to `faults`; Loki has no `fault-injector` stream although the injector logged the injection. It prints one `PASS` or `FAIL` line per check and exits non-zero on any failure. It always restores the injection on exit, even when a check fails. It needs `docker` and `curl`, and works from any directory. It takes about 1.5 minutes on a fresh stack and under a minute on a running one.

### Check that F2 is visible

```bash
infra/scripts/verify-f2.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-f2.sh --down   # the same, then `down -v`
```

The script places a healthy order (`PAID`), reads the `FAILED` payment and `PAYMENT_FAILED` order counts, injects F2 through the fault injector (the gateway answers 429), and places three orders that each end `PAYMENT_FAILED`. It then checks that Prometheus sees both counts rise by at least 3 and that Loki has payment-service's `WARN` line for one of those orders. Last, it restores F2, checks that a new order is `PAID` again, and runs the same truth checks as the F1 script. Output, exit status and the restore on exit work as in the F1 check. It takes about 1.5 minutes on a fresh stack and under a minute on a running one. The scripts share their helpers in `infra/scripts/lib.sh`.

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

### Check that F3 is visible

```bash
infra/scripts/verify-f3.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-f3.sh --down   # the same, then `down -v`
```

The script places a healthy order (`PAID`), then injects F3 through the fault injector, which recreates order-service with a wrong payment URL (`ORDER_PAYMENT_BASEURL=http://payment-service:9999`), and places an order that ends `PAYMENT_PENDING` in under 2 s. It then checks that Prometheus sees the refused-connection series (`http_client_requests_seconds_count{exception="ConnectException"}`) rise and the gateway in-flight gauge stay 0, and that Loki has order-service's `WARN` line with the order id and `ConnectException`. Last, it restores F3 (the injector recreates order-service with its default URL), checks that a new order is `PAID` again, and runs the same truth checks as the F1 script. It always restores the injection on exit, even when a check fails. Output and exit status work as in the F1 check. It needs `docker`, `curl` and `awk`.

### Reproduce F3 by hand

With the compose lab running, watch the "Lab — Faults" dashboard: under F3, "order-service → payment calls by outcome" shows `UNKNOWN ConnectException`, `PAYMENT_PENDING` rises in "Orders by status", and "Gateway in-flight authorizations" stays 0.

1. Redeploy order-service with a payment URL where nothing listens:

   ```bash
   ORDER_PAYMENT_BASEURL=http://payment-service:9999 docker compose -f infra/compose.yaml up -d --no-deps --wait order-service
   ```

2. Place an order. The response comes at once, 201 with status `PAYMENT_PENDING`, and order-service logs a `WARN` line such as `Payment of order <id> unavailable, leaving it in PAYMENT_PENDING: event=PAYMENT_UNAVAILABLE order=<id> outcome=PAYMENT_PENDING duration_ms=<ms> url="http://payment-service:9999/payments" exception=java.net.ConnectException cause="Payment of order <id> failed: ConnectException calling POST http://payment-service:9999/payments"`:

   ```bash
   curl -s -X POST localhost:18080/orders -H 'Content-Type: application/json' \
     -d '{"customerId": "customer-1", "items": [{"sku": "SKU-1", "quantity": 2}]}'
   ```

3. Restore order-service with its default URL; the next order is `PAID` again:

   ```bash
   docker compose -f infra/compose.yaml up -d --no-deps --wait order-service
   ```

Why F3 looks different from F1: both leave the order `PAYMENT_PENDING`, but under F3 no request reaches payment-service. The call fails at once with `ConnectException` instead of waiting 3 s for `HttpTimeoutException`, and the gateway in-flight gauge stays 0.

### Check that F4 is visible

```bash
infra/scripts/verify-f4.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-f4.sh --down   # the same, then `down -v`
```

The script places a healthy order (`PAID`), then injects F4 through the fault injector, which recreates inventory-service with a pool of 2 connections and a 5 s reservation query (`INVENTORY_POOL_SIZE=2 INVENTORY_RESERVATION_DELAY=5s`). It fires six concurrent orders and checks that at least one answers 503 and that every answer is either 503 or 201 `PAID`. With these settings all six usually answer 503: the 5 s query outlasts order-service's 3 s inventory read timeout, so even an order that got a connection times out. It then checks that Prometheus sees inventory's 5xx count on `/reservations` rise and the pool's pending connections reach 1 or its timeouts rise, and that Loki has order-service's `Inventory unavailable` or inventory-service's `No database connection available` `WARN` line. Before restoring inventory-service, it checks the stored evidence in PostgreSQL: every order answered 503 is stored as `REJECTED_INVENTORY_UNAVAILABLE` with an `INVENTORY_UNAVAILABLE` row in `order_events`, and at least one of them also has a `RESERVED` row in inventory's `reservation_events`, committed after order-service gave up (it polls for up to 12 s). That pair is the stock leak: stock reserved for an order that was rejected. It prints the order id and both rows. Last, it restores F4 (the injector recreates inventory-service with its defaults), checks that a new order is `PAID` again, and runs the same truth checks as the F1 script. It always restores the injection on exit, even when a check fails. Output and exit status work as in the F1 check. It takes about 2 minutes on a fresh stack.

Upgrading an existing lab: the `inventory` database is created by a PostgreSQL init script, and init scripts only run on an empty data volume. A lab volume created before inventory-service existed has no `inventory` database, so run `docker compose -f infra/compose.yaml down -v` once before the first start with inventory-service. `verify-*.sh --down` is not a substitute: it tears the lab down only after its checks have run.

### Reproduce F4 by hand

With the compose lab running, watch the "Lab — Faults" dashboard: under F4, "inventory DB pool" shows active connections at 2 with pending connections and timeouts, and "inventory 5xx rate" shows `/reservations 500`.

1. Redeploy inventory-service with a pool of 2 and a slow reservation query. The delay is a lab-only fault hook: each reservation holds its pooled connection for that long.

   ```bash
   INVENTORY_POOL_SIZE=2 INVENTORY_RESERVATION_DELAY=5s docker compose -f infra/compose.yaml up -d --no-deps --wait inventory-service
   ```

2. Place six orders at once. Every one answers 503, with the order id in the problem body, and every order is stored as `REJECTED_INVENTORY_UNAVAILABLE`. Four fail after about 2 s: inventory finds no free connection within its 2 s connection timeout, answers 500, and logs `No database connection available: event=RESERVATION_ATTEMPT order=<id> outcome=DATABASE_UNAVAILABLE ... cause="HikariPool-1 - Connection is not available, request timed out after 2001ms"`. The two that got a connection fail after 3 s, order-service's inventory read timeout, but inventory still commits their reservations 5 s after they started. order-service logs `Inventory unavailable for order <id>, order rejected: event=INVENTORY_UNAVAILABLE ...` for each:

   ```bash
   for i in 1 2 3 4 5 6; do
     curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:18080/orders -H 'Content-Type: application/json' \
       -d '{"customerId": "customer-1", "items": [{"sku": "SKU-1", "quantity": 1}]}' &
   done; wait
   ```

3. Within a few seconds, compare the two databases. order-service has the rejected orders; inventory-service has `RESERVED` rows for the two that got a connection:

   ```bash
   docker compose -f infra/compose.yaml exec -T postgres psql -U lab_app -d orders -Atc \
     "select o.id, o.status from orders o join order_events e on e.order_id = o.id where e.type = 'INVENTORY_UNAVAILABLE' order by e.occurred_at desc limit 6"
   docker compose -f infra/compose.yaml exec -T postgres psql -U lab_app -d inventory -Atc \
     "select order_id, outcome, duration_ms from reservation_events order by occurred_at desc limit 6"
   ```

4. Restore inventory-service with its defaults (pool of 10, no delay); the next order is `PAID` again:

   ```bash
   docker compose -f infra/compose.yaml up -d --no-deps --wait inventory-service
   ```

Why F4 looks different from F3: the symptom is again an error on order, but the root cause is inside inventory-service's own resources. Its pool metrics (`hikaricp_connections_pending`, `hikaricp_connections_timeout_total`) show the exhaustion, while inventory's database and its other dependencies are healthy.

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
