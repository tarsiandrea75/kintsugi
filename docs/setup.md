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
- [ ] **Docker Compose plugin v5.6.0**, the fault injector's version ([ADR-009](adr/ADR-009-fault-injector-docker-control.md), "Compose version skew with the host")
  - Verify: `docker compose version` prints `v5.6.0`; `apt-mark showhold` lists `docker-compose-plugin`
  - Install (WSL, Docker's apt repository): `sudo apt-get update && sudo apt-get install --only-upgrade docker-compose-plugin=5.6.0-1~ubuntu.24.04~noble`, then `sudo apt-mark hold docker-compose-plugin`
  - Why pinned and held: a different host version recreates, once, every service the injector recreated, which is noise in the deploy evidence. Upgrade the host and the injector's `docker/compose-bin` together, then `sudo apt-mark unhold docker-compose-plugin`.
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

One command starts the whole lab: PostgreSQL, the four services, the fault injector, Prometheus, Loki, Alloy, Grafana, mcp-tools, the runbook knowledge base (the `knowledge-db` store, the one-shot `knowledge-indexer` and `ollama-proxy`) and the diagnosing agent (ADR-008, ADR-009, ADR-004, ADR-002, ADR-010, [living spec](../openspec/specs/lab-observability/spec.md)). Run it from the repository root.

```bash
export KINTSUGI_REPO_DIR="$(git rev-parse --show-toplevel)" DOCKER_GID="$(stat -c %g /var/run/docker.sock)"
docker compose -f infra/compose.yaml up -d --build --wait   # start; the first build takes about a minute
docker compose -f infra/compose.yaml down                   # stop, keep the data
docker compose -f infra/compose.yaml down -v                # stop and delete the data volumes
```

The verify scripts start the same lab but the agent (`start_lab` in `infra/scripts/lib.sh`): no other check needs it, so an agent that cannot start (see [The diagnosing agent](#the-diagnosing-agent)) never fails them. `verify-agent.sh` starts it as well.

Run the `export` line first in every new shell. Every `docker compose -f infra/compose.yaml` command in this document needs it, including `exec`, `logs` and the "Reproduce by hand" recipes: compose reads the whole file for any command, and without `DOCKER_GID` it stops with `required variable DOCKER_GID is missing a value: set DOCKER_GID to the gid of /var/run/docker.sock`. The verify scripts export both variables themselves.

The compose project is called `kintsugi-lab`, so these commands never touch other containers. Every port is bound to `127.0.0.1`, so the lab is reachable only from this machine:

| What | URL |
|------|-----|
| order-service | <http://localhost:18080> |
| payment-service | <http://localhost:18081> |
| gateway-mock | <http://localhost:18082> |
| inventory-service | <http://localhost:18083> |
| fault-injector | <http://localhost:18084> |
| mcp-tools (MCP, `POST /mcp`) | <http://localhost:18085/mcp> |
| agent (incident API) | <http://localhost:18086/incidents> |
| Grafana | <http://localhost:13000> |
| Prometheus | <http://localhost:19090> |

PostgreSQL, `knowledge-db`, Loki and Alloy have no host port. `ollama-proxy` listens on the Docker bridge gateway (`172.17.0.1:11434`), not on `127.0.0.1`; see [The runbook knowledge base](#the-runbook-knowledge-base).

### Variables

Every variable except `DOCKER_GID` has a lab default; nothing here is a secret but `ANTHROPIC_API_KEY`, which only the cloud route uses and which lives in `infra/.env` alone. Put overrides in `infra/.env` (a template is `infra/.env.example`), not only in the shell: the fault injector recreates services from the same compose file and reads `infra/.env`, but it never sees your shell.

| Variable | Default | Used for |
|----------|---------|----------|
| `KINTSUGI_REPO_DIR` | `$PWD` | absolute host path of the repository; fault-injector mounts it read-only at that same path |
| `DOCKER_GID` | none, required | group id of `/var/run/docker.sock`, added to fault-injector so it can use Docker; a guessed default would start an injector that cannot reach Docker |
| `POSTGRES_BOOTSTRAP_PASSWORD` | `postgres` | the bootstrap superuser `postgres`, used only by the init script |
| `LAB_APP_DB_PASSWORD` | `lab_app` | the role `lab_app`, which the four services connect as |
| `FAULTS_DB_PASSWORD` | `faults` | the role `faults`, which the fault injector connects as |
| `KINTSUGI_AGENT_DB_PASSWORD` | `kintsugi_agent` | the read-only role `kintsugi_agent`, which mcp-tools connects as; mcp-tools does not start without it |
| `GRAFANA_ADMIN_PASSWORD` | `kintsugi-lab` | Grafana's `admin` login |
| `KNOWLEDGE_BOOTSTRAP_PASSWORD` | `postgres` | the knowledge store's bootstrap superuser, used only by its init script |
| `KNOWLEDGE_INDEXER_DB_PASSWORD` | `knowledge_indexer` | the role `knowledge_indexer`, which owns the knowledge tables; only the indexer connects as it |
| `KNOWLEDGE_AGENT_DB_PASSWORD` | `knowledge_agent` | `kintsugi_agent` on the knowledge store (a separate server, so a separate password); without it mcp-tools still starts and only `search_runbooks` fails |
| `AGENT_DB_PASSWORD` | `agent_app` | the role `agent_app`, which owns the agent's state in the `agent` database on `knowledge-db`; the agent does not start without it (outside compose, `application.yaml` has no default) |
| `OLLAMA_PROXY_BIND_IP` | `172.17.0.1` | the address `ollama-proxy` listens on: the default bridge's gateway (`docker0`), which is what `host-gateway` resolves to |
| `KINTSUGI_AGENT_ROUTE` | `local` | the agent's route: `local` (the local model) or `cloud` (`claude-haiku-5-5`, ADR-012); `verify-agent.sh --route` sets it |
| `ANTHROPIC_API_KEY` | empty | the cloud route's key, a secret: the human sets it in `infra/.env` (git-ignored) only to run the cloud route; it is never committed |

`KINTSUGI_REPO_DIR` defaults to the current directory, which is right only from the repository root. From anywhere else, export it as above; the verify scripts set it themselves. With a wrong value the injector does not start, because it cannot read the compose file.

### Database roles

One PostgreSQL instance holds four databases (ADR-007, ADR-009):

- `postgres` is the bootstrap superuser. Only `infra/postgres/init/01-roles-and-databases.sh` uses it.
- `lab_app` is a non-superuser that owns `orders`, `payments` and `inventory`. The four services connect as `lab_app`.
- `faults` is a non-superuser that owns the `faults` database, where the injector records the true cause of each fault. `CONNECT` on `faults` is revoked from `PUBLIC`, so `lab_app` cannot connect to it. A superuser would ignore that revoke, which is why no service or agent role is one.
- `kintsugi_agent` is the read-only role of mcp-tools (ADR-004): `SELECT` on the six evidence tables of `orders`, `payments` and `inventory`, nothing else, and no `CONNECT` on `faults`.

```bash
docker compose -f infra/compose.yaml exec -T postgres psql -U lab_app -d orders -c "select count(*) from orders"
docker compose -f infra/compose.yaml exec -T postgres psql -U faults -d faults -c "select fault_id, status from injections"
```

The knowledge store is a second server, `knowledge-db` (ADR-002): `knowledge_indexer` owns the `knowledge` database's tables, and `kintsugi_agent` there has `CONNECT` and `SELECT` only; it holds no `faults` database. It also holds the `agent` database, the diagnosing agent's state (ADR-010): `agent_app` is not a superuser, has only `USAGE` and `CREATE` on its `public` schema, and Flyway creates the tables as that role when the agent starts. Its init script runs only on an empty `knowledge-data` volume too.

The init script runs only on an empty data volume. **Upgrading a lab created before the fault injector:** its volume has the old `lab` superuser and no `lab_app` or `faults`, so run `docker compose -f infra/compose.yaml down -v` once before the first start. The same applies to a lab created before mcp-tools, whose volume has no `kintsugi_agent`, and after changing any database password.

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

### The agent's tools (mcp-tools)

mcp-tools ([spec](../openspec/specs/mcp-tools/spec.md), [ADR-004](adr/ADR-004-tools-via-mcp.md)) serves the agent's read-only tools over MCP: Streamable HTTP, stateless, JSON-RPC on `POST /mcp`. Compose publishes it on `127.0.0.1:18085`; the agent reaches it as `mcp-tools:8085`. It connects to PostgreSQL as the read-only role `kintsugi_agent`. A request with an `Origin` header gets 403, and a `Host` other than `mcp-tools:8085`, `localhost:18085` or `127.0.0.1:18085` gets 421. Every tool call is one `INFO` line in its log: `docker compose -f infra/compose.yaml logs mcp-tools`.

```bash
curl -s localhost:18085/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
curl -s localhost:18085/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"status_summary","arguments":{"table":"orders"}}}'
```

```bash
infra/scripts/verify-mcp.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-mcp.sh --down   # the same, then `down -v`
```

The script recreates the lab with `down -v` when its volume has no `kintsugi_agent` role. It sends `initialize` and `tools/list` (exactly the eight tools), places a healthy order, injects F1 through the fault injector and places an order that ends `PAYMENT_PENDING`. Through `tools/call` it checks that `search_logs` finds order-service's `WARN` line for that order, that `query_metric` shows `lab_orders{status="PAYMENT_PENDING"}` rising, and that `get_order_trace` returns the order with its `PAYMENT_UNAVAILABLE` event. It checks that `search_logs` on `fault-injector` answers `INVALID_PARAMETER`, that an `Origin` header gets 403 and an unknown `Host` 421, and, with `psql` as `kintsugi_agent`, that the role cannot connect to `faults`, cannot insert into `orders`, and is not a superuser. Last, it checks that mcp-tools logged the calls, restores F1 and runs the same truth checks as the F1 script. Output, exit status and the restore on exit work as in the F1 check. It needs `docker`, `curl` and `awk`, and takes about 2 minutes on a fresh stack.

### The runbook knowledge base

`search_runbooks`, the eighth tool, searches `docs/runbooks/*.md` by meaning ([knowledge-base spec](../openspec/specs/knowledge-base/spec.md), [ADR-002](adr/ADR-002-pgvector-knowledge-base.md)). It needs the local Ollama with `nomic-embed-text` (`ollama pull nomic-embed-text`), listening as usual on `127.0.0.1:11434`.

- `ollama-proxy` (socat, on the host's network) listens on the Docker bridge gateway `172.17.0.1:11434` only and forwards to `127.0.0.1:11434`. Containers reach Ollama as `http://host.docker.internal:11434` (`extra_hosts: host-gateway`). Ollama's own configuration is unchanged and it stays on loopback. If another process already listens on that address, the bind fails (`Address in use` in `docker compose -f infra/compose.yaml logs ollama-proxy`): the proxy restarts on its own until the address is free (`restart: unless-stopped`), the indexer exits 1, and every other service comes up as usual.
- `knowledge-db` is the pgvector store; it has no host port. Read it with `docker compose -f infra/compose.yaml exec -T knowledge-db psql -U kintsugi_agent -d knowledge`.
- `knowledge-indexer` runs once at every `up`, embeds the changed runbooks (about 40 s for all five on a laptop CPU; unchanged ones are skipped) and exits. mcp-tools waits for it, but only as an optional dependency: when Ollama is down the indexer exits 1, compose warns, `up --wait` exits non-zero, and mcp-tools starts anyway; `search_runbooks` then refuses with `UPSTREAM_ERROR` until the index is built. The verify scripts accept that state, and only it (the indexer exited non-zero or the proxy is down, every other service running and healthy), and print compose's errors and the reason as `WARN` lines. Re-index by hand with `docker compose -f infra/compose.yaml run --rm --no-deps knowledge-indexer`.

```bash
curl -s localhost:18085/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_runbooks","arguments":{"query":"reservations are very slow"}}}'
```

```bash
infra/scripts/verify-rag.sh          # start or reuse the lab, run the checks, leave it running
infra/scripts/verify-rag.sh --down   # the same, then `down -v`
```

The script waits for `knowledge-indexer` to exit 0, then checks with `psql` that `index_metadata` records `nomic-embed-text`, 768 dimensions and the digest the local Ollama serves now (read from `GET /api/tags`) and that every service runbook has chunks. Through MCP it asks four symptom-worded questions and expects the right runbook in the top 3 and no hit from the README's "Authoring rule", and checks the `service` filter. It checks that `kintsugi_agent` cannot insert into the knowledge store, that the store has no `faults` database, and that `kintsugi_agent` cannot connect to `faults` on the lab's postgres. It runs the indexer again and expects it to embed nothing and to name every `docs/runbooks/*.md` file as skipped, then starts a one-off mcp-tools configured with `mxbai-embed-large` and expects `search_runbooks` to refuse the index. No fault is injected. It needs `docker`, `curl` and Ollama, and takes about a minute on a running lab.

### The diagnosing agent

The agent (`agent/`, [spec](../openspec/specs/agent/spec.md), [ADR-010](adr/ADR-010-agent-loop.md)) receives a neutral symptom, investigates the lab only through mcp-tools, and writes a JSON report naming the root-cause service and category, with evidence that code checks against the recorded tool outputs. Compose publishes its incident API on `127.0.0.1:18086` only: it has no authentication, and `?full=true` returns full tool outputs. It keeps its state in the `agent` database on `knowledge-db` and reaches `granite4.1:8b` through `ollama-proxy` (`ollama pull granite4.1:8b`). One incident runs at a time; a second `POST` gets 409. Read its step log with `docker compose -f infra/compose.yaml logs agent`.

```bash
curl -s -X POST localhost:18086/incidents -H 'Content-Type: application/json' \
  -d '{"symptom":"Customers report that checkout orders are not completing as expected","sinceMinutes":10}'
curl -s localhost:18086/incidents/<id>              # status, steps (summaries) and, once COMPLETED, the report
curl -s 'localhost:18086/incidents/<id>?full=true'  # the same with every full tool output and model answer
```

A `knowledge-data` volume created before the `agent` database has no `agent` database or `agent_app` role, so the agent cannot connect and exits at startup: reset that volume once ([Reset a volume](#reset-a-volume)). Only `verify-agent.sh` and a plain `docker compose ... up --wait` notice it: the other verify scripts do not start the agent. `verify-agent.sh` then stops with the agent's last log lines and the `reset-volume.sh` command to run.

The API has no cancel. To end an incident left `RUNNING` (for example by an interrupted script), restart the agent: its restart sweep marks the incident `FAILED`, `agent restarted during the run`.

```bash
bash -c 'source infra/scripts/lib.sh && restart_agent'
```

### Run a live F1–F3 round

This script runs a live round of F1, F2 and F3 on either route. It is no longer the M2 check: since the vision revision of 2026-10-25, M2's check is `docs/benchmark.md` v0, the lab holdout-1 table for Configs 0, A and D with AC@1, Wilson CIs and paired differences, from committed results and a plan committed before the run ([vision](vision.md) §7). That check passes whatever the local model scores; this script still reports each fault as MATCH or MISS, and a live local round is descriptive. The cloud route (`--route cloud`, `claude-haiku-5-5` through Anthropic's API) is the development route of [ADR-012](adr/ADR-012-development-and-verification-models.md) and Config D of the vision.

```bash
infra/scripts/verify-agent.sh                 # F1, F2 and F3 in turn, leave the lab running
infra/scripts/verify-agent.sh --only F2       # one fault, or a list: --only F1,F3
infra/scripts/verify-agent.sh --down          # the same, then `down -v`
infra/scripts/verify-agent.sh --mode dossier  # the agent in dossier mode (ADR-011); the default is explorer
infra/scripts/verify-agent.sh --mode decision # the dossier, then one report call; a baseline phase before each fault
infra/scripts/verify-agent.sh --route cloud   # the agent on the cloud route (ADR-012); the default is local
infra/scripts/verify-agent.sh --rounds 5      # the fault list 5 times (1 to 20); each file names its round
infra/scripts/verify-agent.sh --mode decision --schedule infra/holdout2/schedule.tsv --cases 1-5  # schedule cases
infra/scripts/check-recordings.sh infra/scripts/out/verify-agent-<UTC time>  # are the recorded windows clean?
infra/scripts/compare-agent-modes.sh          # 3 runs per mode, alternating explorer and dossier
infra/scripts/compare-agent-modes.sh --route cloud  # the same on the cloud route
infra/scripts/test-lib.sh                     # the scoring helpers' tests, no lab needed
```

It checks that `python3` is available and, on the local route, that Ollama serves `granite4.1:8b`; it starts or reuses the lab as the other scripts do, checks on the local route that `ollama-proxy` is healthy, then starts the agent and waits for it to be healthy (`start_agent`). For each fault it places 3 healthy orders, injects the fault through the fault injector, places 6 more orders and waits 30 s for the metrics and logs to land. It then opens an incident with the same neutral symptom for every fault and a window that starts with that fault's own orders: the minutes since its first order, rounded up, plus 10, or plus 2 in decision mode (`incident_window`, `window_margin`), recomputed if the `POST` first has to wait for a running incident. It polls the incident every 15 s for at most 52 minutes (the 45-minute incident budget, the 5-minute warm-up and a margin), restores the fault and reads the injection's truth row as the `faults` role. A fault matches when the incident is `COMPLETED` and its report's `rootCauseService` and `rootCauseCategory` equal the truth's `root_cause_service` and `root_cause_category`; the cause is read from the report alone (`incident_fields`, with `python3`), and is `none` when there is no report; `agent_verdict` scores it, and `infra/scripts/test-lib.sh` tests both. The script passes only when every selected fault matches. Every customer id it writes into the lab is a neutral `checkout-xxxxxxxx`, and the symptom names no cause: the agent reads both through its tools.

In explorer and dossier mode, before each fault after the first, it waits until 11 minutes have passed since the previous fault's restore (`space_out`), so the previous fault's orders, logs and restarts are older than any window it offers, however early the previous incident ended; the tools count a window back from the time of each call, so it only moves later during a run. A score therefore does not depend on how long the previous incident ran. The spacing holds across runs too: every restore confirmed through `lib.sh` (`restore_injection`, so also the single-fault checks and a leftover injection restored at start) records its time in `infra/scripts/out/.last-fault-end` (ignored by git), and the first fault waits from it (`last_fault_end`); a missing or unreadable file means no wait, and deleting it skips the wait.

Decision mode (`--mode decision`; agent spec, "Decision mode") is the mode for recordings (`odd/tasks/decision-instrument.md`). The agent gathers its dossier as the incident opens, before its warm-up, over the incident window `[at−N, at]` and the previous window `[at−2N, at−N]`, where `at` is the opening and `N` the window. Nothing runs between the window's computation and the gathering, so the margin is 2 minutes instead of 10: enough to start the window before the fault's first order whatever the rounding. The bare wait of `space_out` is not enough for this mode: the previous window held the previous fault, or nothing at all after `start_lab` restarted the services, and the model then compared the fault with another fault. So every fault, the first included, starts instead with a baseline phase (`baseline_phase` in `lib.sh`): one healthy order every 50 s, each expected to be `201 PAID`, for B = 2 × N_max + 1 minutes.
- N_max is the largest window a fault can get in this flow, derived from the script's constants: the traffic and settle with every step at its time cap (9 orders at the 15 s transport cap plus their 2 s gap, the injection at the injector's 180 s cap, then the 30 s settle: 363 s), rounded up to 7 minutes, plus the 2-minute margin. So N_max is 9 minutes and B is 19.
- The previous window then starts at least a minute after the phase did, and ends about 2 minutes before the fault's first order. It holds only the phase's healthy orders: at least 3 even in the shortest, 3-minute window, hence one order every 50 s rather than every minute. The previous fault, its restore and the lab's start are older than both windows.
- A baseline order that is not `PAID` (for example the first one after F3's restore recreated `order-service`) is logged as a `WARN` line, and the phase starts over from it, so that order too ages out of both windows. After 2 restarts, a third such order fails the fault: it is not injected, it is scored a miss (`baseline failed` in `summary.tsv`), and the run goes on with the next fault, which gets its own baseline. One bad order so costs at most that fault, never the remaining rounds of an unattended session.
- A fault whose window is larger than N_max anyway (when `open_incident` first waited for another running incident) fails with that reason and is scored a miss; its run is still waited for and recorded.
- The cost is the phase: 19 minutes per fault, and up to 3 times that when the phase starts over twice. On the cloud route a decision-mode fault takes about 21 minutes (the phase, 1 to 2 minutes of fault traffic and settle, about 30 s for the incident, and the restore), so about 65 minutes per round of F1-F3.

`--schedule FILE` runs the cases of a schedule that `holdout2-schedule.py` wrote (below, "Holdout-2 schedule"), in the schedule's order; `--cases LIST` (`1-5,9`) runs only those cases, still in the schedule's order. It takes neither `--only` nor `--rounds`, and a schedule it cannot run (no file, another header, an unknown class or parameter, a case listed twice) or a `--cases` case the schedule does not hold exits 2 before the lab is touched (`read_schedule` in `lib.sh`).
- A fault case is injected with its drawn parameter nested under `parameters` in `POST /faults` (`fault_request`): `{"fault":"F1","parameters":{"gatewayLatencyMs":5000}}`, or F2's `gatewayErrorStatus`. The injector ignores a parameter at the top level and applies its default, so the nesting matters. F3's `wrong_port` 9999 is the injector's own and is not sent. The truth is the injection's truth row, as for a round.
- A healthy control injects nothing. Where a fault case injects, it checks that `GET /faults/active` answers 204 (`expect_no_injection`), and fails the run otherwise. A transport failure, with no status code, is asked again, up to `NO_INJECTION_ATTEMPTS` (3) times in all, `NO_INJECTION_PAUSE_SECONDS` (10 s) apart, so an injector slow to answer does not end the session; any status code but 204, a 200 (an active injection) among them, fails at once, and so does an injector that never answers. Everything else is a fault case's: the same baseline phase (or `space_out` wait), the same 3 + 6 orders 2 s apart, the same 30 s settle, the same symptom and window. The agent therefore cannot tell it from a fault case by its symptom, its window or the harness's timing, only by what the lab's evidence shows. Its truth is `NONE|NONE` ([ADR-015](adr/ADR-015-evaluation-protocol.md)): only a `COMPLETED` report naming `UNKNOWN|UNKNOWN` matches, and a named cause is scored a miss and called a false alarm.
- Each case's files carry its class and case number (`F1-c7-incident.json`, `healthy-c3-score.txt`). The score file adds `case:` and `parameters:` lines, and `summary.tsv` adds two last columns, `case` and `parameters` (`<name>=<value>`, or `-`). Without `--schedule` the files and columns are as before.

F4 is not in the list: its pool of 2 is exhausted only by concurrent orders (`verify-f4.sh` places 6 at once), and these orders come one at a time, so they would show slow reservations, not an exhausted pool.

Every run, pass or fail, is logged under `infra/scripts/out/verify-agent-<UTC time>/` (ignored by git): `run.log` (the whole output), and per fault `<fault>-incident.json` (the incident with every step, full tool outputs and model answers) and `<fault>-score.txt`; `summary.tsv` has one line per fault, with the mode and the route (`VERIFY_AGENT_RUN_DIR`, when set, names the directory instead; `compare-agent-modes.sh` uses `verify-agent-<UTC time>-<mode>`, and `verify-agent-<UTC time>-<mode>-cloud` on the cloud route). `--rounds K` (1 to 20) runs the fault list K times: each fault's files then carry the round (`<fault>-r<k>-incident.json`, `<fault>-r<k>-score.txt`), and each `summary.tsv` line ends with its round (1 without `--rounds`). Each `<fault>-score.txt` records the mode and the route (requested, recorded), the incident's `model`, and its tokens (prompt, completion): the totals of the steps' `promptTokens` and `completionTokens`, each `none` when no step reported it. `--mode` sets `KINTSUGI_AGENT_MODE` and `--route` sets `KINTSUGI_AGENT_ROUTE` for the agent container (`start_agent` runs `up -d --build`, so the image is rebuilt from the current code and compose recreates the container when either value changes); an incident whose recorded `mode` or `route` is not the requested one is scored as a miss that keeps the outcome in its message. An incident that is still `RUNNING` after the 52 minutes is ended by restarting the agent (`end_running_incident`), scored as a miss, and the run goes on with the next fault. When the run stops early (Ctrl-C, or a failed step), the fault being diagnosed gets a `not scored` line and fails the run, and an incident it left `RUNNING` is ended the same way; a `RUNNING` incident from elsewhere is waited for before the next `POST`. The restore on exit works as in the F1 check. It needs `docker`, `curl`, `python3` and Ollama with `granite4.1:8b`, and in explorer or dossier mode takes 10 to 55 minutes per fault on a laptop CPU, so up to about 3 hours for all three.

The cloud route needs `ANTHROPIC_API_KEY`. The human adds it to `infra/.env` (git-ignored); it is never committed, and no placeholder is either. Compose passes it to the agent (empty by default); a value set in the shell wins over the file, and `verify-agent.sh` drops an empty shell value so it cannot hide the file's. With `--route cloud` and no key in either place, the script stops with exit status 2 before it touches the lab, naming `ANTHROPIC_API_KEY` and `infra/.env`; it never prints the key. On the cloud route it skips the local model's checks (Ollama serving `granite4.1:8b`, `ollama-proxy` healthy) with an info line; `search_runbooks` still embeds through Ollama, so `start_lab` warns as usual when `ollama-proxy` or the indexer is down. Every cloud call costs money: each cloud batch (a `verify-agent.sh --route cloud` run or a `compare-agent-modes.sh --route cloud` comparison) needs the human's go-ahead, and its cost is recorded (ADR-012).

`check-recordings.sh` checks the recorded windows before `record-replay.sh` imports them; it never contacts the lab. For every `<fault>-incident.json`, `<fault>-r<k>-incident.json` and schedule case `<class>-c<case>-incident.json` of the run directories it reads the dossier's tool calls, which the incident's `DOSSIER` step records with their arguments and outputs, and checks:
- the previous window: the orders table shows at least 3 `PAID` orders and no order in any other status, and no traffic row, on any edge, has an outcome other than `SUCCESS`;
- the incident window: no service restarted, except once for the fault's own recreate (`order-service` for F3, `inventory-service` for F4; F1, F2 and a healthy control restart nothing).

It prints one `PASS` or `FAIL` line per incident, with the facts that fail it, then a `RESULT` line, and exits 1 when an incident fails (2 on a wrong argument: no run directory, a missing one, or one with no incident). The recordings of 2026-10-19 (`verify-agent-20261019T214629Z`) fail it, each on what came before it: F1's previous window is empty and its incident window holds the lab's restarts, F2's previous window holds F1's pending orders and timeouts, and F3's holds F2's failed payments and 429s. `test-lib.sh` runs it on those recordings (their committed replay copies), on a clean fixture, `infra/scripts/testdata/check-recordings/clean/`, and on a healthy control's, `infra/scripts/testdata/check-recordings/healthy/`. A healthy control passes the same checks unchanged: its previous window holds the baseline's healthy orders, and its incident window no restart.

### Record a run for replay

The replay harness (`odd/tasks/replay-harness.md`) runs the real agent loop against tool outputs recorded from a `verify-agent.sh` run, so a prompt or loop change can be scored on many recordings without the lab. `record-replay.sh` takes those recordings from run directories into a split; it never contacts the lab:

```bash
infra/scripts/record-replay.sh --split dev infra/scripts/out/verify-agent-<UTC time> [<run dir>...]
infra/scripts/record-replay.sh --split holdout --rounds 2,4,6,8,10 infra/scripts/out/verify-agent-<UTC time>
```

Every recording belongs to one split, the manifest's last column (`odd/tasks/decision-instrument.md`):
- `dev`: the recordings a change is tuned and measured on;
- `holdout`: read once, to confirm a result reached on `dev`;
- `stress`: the six recordings of 2026-10-19, whose previous windows hold other faults (`check-recordings.sh` fails them), kept for a later robustness case, and the only recordings explorer and dossier replays pool;
- `probe`: faults the prompt was never tuned on (none yet);
- `holdout-2`: holdout-2's schedule cases, recorded after the freeze ([ADR-026](adr/ADR-026-holdout2-seed.md)); see "Record holdout-2" below.

`--split` is required. For each `<fault>-incident.json`, `<fault>-r<k>-incident.json` or schedule case `<class>-c<case>-incident.json` it reads the score file of the same name. It copies the incident to `agent/src/test/resources/replay/<fault>/<run dir name>-<fault>.json`, `<run dir name>-r<k>-<fault>.json` or `<run dir name>-c<case>-<class>.json` (committed: recordings are lab output, not secrets). It then adds a line to `agent/src/test/resources/replay/recordings.tsv` with the fault (`healthy` for a healthy control), the recorded mode and route, the truth (`service|category`, `NONE|NONE` for a healthy control), the file, the incident id, the split and the case. The `case` column holds the schedule case of a `holdout-2` row, taken from the file name, and is empty in every other split; a `holdout-2` recording that names no case is refused. `--rounds` takes only some rounds of a `--rounds` run (`1-5`, or `1,3,5,7,9`), so one run directory can feed both `dev` and `holdout`.
- Except in `stress`, an incident must first pass `check-recordings.sh`, run on that incident alone.
- Re-running it on the same run with the same split replaces the copy and replaces that file's manifest line with the new values (`manifest_upsert` in `lib.sh`; unchanged when they are the same), never adding a second one.
- A fault with no score file or no truth is skipped with a warning.
- An incident is refused when it holds a key-like string (`sk-ant-`, `ANTHROPIC_API_KEY=` with a value, or `x-api-key`, checked by `holds_key_like_string` in `lib.sh`), is not a JSON object, fails `check-recordings.sh`, or is already recorded in another split. A recording stays in one split, so a second import never moves a `holdout` recording into `dev`; to move one, edit the manifest. Nothing is written for a refused incident, and the script exits 1 after the others.
- A manifest whose first line is not the header with the `split` and `case` columns stops it with exit 2.
- `REPLAY_DIR` names another destination, for a dry run in a temporary directory.

To import a recording round (`verify-agent.sh --mode decision --route cloud --rounds K`), check it, then import half of its rounds into each split. Alternating rounds spread the session's hours over both splits, so a slow drift of the lab does not separate `dev` from `holdout`:

```bash
infra/scripts/check-recordings.sh infra/scripts/out/verify-agent-<UTC time>
infra/scripts/record-replay.sh --split dev --rounds 1,3,5,7,9 infra/scripts/out/verify-agent-<UTC time>
infra/scripts/record-replay.sh --split holdout --rounds 2,4,6,8,10 infra/scripts/out/verify-agent-<UTC time>
```

An incident that fails the check is refused and named, and the others are imported.

The tool listing is not recorded per run: the replay offers the tools of `agent/src/test/resources/mcp-outputs/tools-list.json`, a recorded `tools/list` answer holding the same eight tools `verify-mcp.sh` expects.

### Replay the recorded faults

`replay-agent.sh` runs the real agent loop and the route's real model over those recordings, without the lab, and scores every sample against the recorded truth:

```bash
infra/scripts/replay-agent.sh                                     # 1 dossier sample of F1-F3, cloud route
infra/scripts/replay-agent.sh --samples 5 --faults F1,F3
infra/scripts/replay-agent.sh --route local                       # Ollama with granite4.1:8b on 127.0.0.1:11434
infra/scripts/replay-agent.sh --mode decision                     # each dev recording once: the dossier, one report call
infra/scripts/replay-agent.sh --mode decision --split holdout --repeats 3
infra/scripts/replay-agent.sh --route rules --mode decision --split holdout-2   # read once, under its plan
infra/scripts/replay-agent.sh --mode decision --view sheets       # the dossier as one fact sheet per service
infra/scripts/replay-agent.sh --mode decision --view sheets --report assessed   # each service's role first
infra/scripts/replay-agent.sh --route rules --mode decision       # Config 0: the rules alone, no model (ADR-016)
infra/scripts/replay-agent.sh --route local --mode decision --report hybrid   # Config H: the model picks a candidate
infra/scripts/replay-agent.sh --route local --mode decision --model qwen3.5:9b --think on \
  --report-cap 2048 --report-timeout 1800                         # another local model, thinking, its own cap and deadline
```

The class `healthy` (lab-realism-min T7) replays the healthy controls: recordings with no fault injected, fault `healthy` and truth `NONE|NONE` in the manifest ([ADR-015](adr/ADR-015-evaluation-protocol.md)). Each is a decision case like a fault's, and `--faults` takes `healthy` with the lab's faults in decision mode only (explorer and dossier mode pool `stress` recordings, and there is none). Only `holdout-2` records healthy controls, so a `--split holdout-2` run replays `F1,F2,F3,healthy` by default, every case of the schedule; every other split keeps `F1,F2,F3`. A healthy sample's verdict is `MATCH` only for a `COMPLETED` report naming `UNKNOWN|UNKNOWN`; a named cause is a `MISS`, which the evals scorer counts as a false alarm.

`--route rules` is Config 0 (agent spec, "Rules-only diagnosis (Config 0)"), decision mode only: it runs `RulesReplayTest`, with no Spring context, no model, no key, no Ollama and no Docker, and writes the same files with route `rules`, 0 tokens and a cost of `0.0000`, so the scorer reads it as one more config. Production has no such route.

**Cost:** on the cloud route every sample is paid model calls (the script prints how many samples it will run, at most 60 per run). Run it only with the human's go-ahead, and record the cost from `aggregate.tsv`. The key comes from the shell or `infra/.env` (`export_cloud_key` in `lib.sh`) and reaches Maven through its environment only; it is never printed or put on a command line.

It runs `ReplayRunTest` with the `replay` Maven profile (the default `./mvnw test` leaves it out) and needs Docker for the Testcontainers database. It writes `infra/scripts/out/replay-<UTC time>-<route>/`:
- `<fault>-<mode>-<recording>-<n>.json`, the incident with its steps, where `<recording>` is the file name, without `.json`, of the recording the sample opened on;
- `summary.tsv`, one line per sample with its status, verdict, coverage (calls answered from a recording out of all calls), conflicts, tokens and seconds, then its split, recording, view and report, the route settings it ran with: `model`, `think`, `reportCap` and `reportTimeout`, and last `candidates`, the rules' candidates as `service|category` pairs in order, joined by `;` (`odd/tasks/hybrid.md` T4): on `rules` lines those of the dossier the rules diagnosed (the `reported` verdict is still the rules' own), on `hybrid` lines the list the report task gave the model (empty when it had none), and empty on every other line;
- `aggregate.tsv`, per fault, mode, split, view, report and route settings: the distinct `recordings` the samples opened on, the `samples`, matches out of samples, mean coverage, `coveredSamples` (coverage at least 0.8), `matchesCovered` (matches among them, out of them), `dossierCovered` (samples whose dossier was fully recorded, out of samples), tokens and the estimated cloud cost, then the same four route-setting columns.

A sample with coverage below 1.00 made calls no recording holds, answered as `CLIENT_ERROR`: read its verdict with that in mind. A dossier-mode sample usually shows about 0.91, its model-chosen calls (free-text `search_runbooks` queries) being unrecorded, which is why the covered threshold is 0.8.

`--mode decision` (agent spec, "Decision mode") is the measurement mode: the dossier, then one report call, with no evidence phase.
- Each manifest line of a chosen fault in the split (`--split`, default `dev`) recorded in decision or dossier mode is one case.
- Each case replays its own recording alone, which holds every call the dossier makes, so its coverage is 1.00 with no conflict. It runs `--repeats` times (1 to 999, default 1).
- `recordings` in `aggregate.tsv` counts the independent observations, and `samples` is recordings × repeats. On the local route, at temperature 0, repeats of a recording are near-copies.
- Before Maven, the script lists the cases with `ReplayCases` (`agent/src/test/java/dev/kintsugi/agent/loop/ReplayCases.java`), the class `ReplayRunTest` picks them with, run from its source by the JDK in about a second, so its count and its refusals are the driver's own. An unknown split, a chosen fault with no recording in the split, or a manifest line the driver refuses stops the script there with exit 2. A recording without its start, symptom or window fails the run before any model call.
- `--samples` is an error in decision mode, which points to `--repeats`. On the cloud route the bound of 60 samples per run counts recordings × repeats.
- `--view` sets how the dossier is written for the model (`kintsugi.agent.decision.view`; agent spec, "Decision mode"; `odd/tasks/service-assessment.md`): `tables`, the default, the dossier of dossier mode, by data source; or `sheets`, one fact sheet per service, each after the services it calls, with the rows seen only in the previous window apart. The run's view is in every line of `summary.tsv` and `aggregate.tsv`, `-` for explorer and dossier samples, so a result is attributable to its variant. The agent reads the same setting from `KINTSUGI_AGENT_DECISION_VIEW` and applies it in decision mode only, but checks it in every mode: any other value, an empty one included, stops its start. `infra/compose.yaml` does not pass that variable to the agent container, so the lab's agent writes the tables.
- `--report` sets which report the model writes (`kintsugi.agent.decision.report`; agent spec, "Decision mode"; `odd/tasks/service-assessment.md` T2): `plain`, the default, the report of every mode; or `assessed`, the role of each service first (`ORIGIN`, `AFFECTED` or `UNAFFECTED`), each citing a line of its own sheet, checked by code against the call graph. `assessed` needs `--view sheets`: with the tables the script exits 2 before Maven. `hybrid`, Config H ([ADR-019](adr/ADR-019-hybrid-config.md); `odd/tasks/hybrid.md`), lists the rules' candidates and asks the model to pick one or `UNKNOWN`, in either view, on `--route local` or `--route cloud` (the cloud run is a later ablation); on `--route rules`, which calls no model, the script exits 2 before Maven. The run's report is in every line of `summary.tsv` and `aggregate.tsv`, `-` for explorer and dossier samples. The agent reads the same setting from `KINTSUGI_AGENT_DECISION_REPORT`.

`--model`, `--think on|off`, `--report-cap N` and `--report-timeout SECONDS` override the local route for one run (agent spec, "Routes", "Answer caps" and "Budgets"; ADR-017; `odd/tasks/qwen-dev.md`), and are refused with exit 2 on any other route:
- `--model` names the Ollama model, `granite4.1:8b` by default; the script checks that Ollama serves it.
- `--think on|off` sends Ollama's `think` field with that value on every call and the warm-up. Without it no `think` field is sent, as before the option existed; a thinking-capable model such as `qwen3.5:9b` then thinks by its own default. The answer is read from the message's content, never from its thinking.
- `--report-cap` sets the report's `num_predict`, 1 to 99999 (default 512). With thinking on, Ollama counts the thinking tokens against it, so take the cap from a calibration.
- `--report-timeout` sets the report call's deadline in seconds, 1 to 2700 (default 600; the incident's budget is 45 minutes). The loop's HTTP read timeout follows it.

They reach the agent as `KINTSUGI_AGENT_MODEL_NAME`, `KINTSUGI_AGENT_MODEL_THINK` (`true` or `false`), `KINTSUGI_AGENT_MODEL_NUM_PREDICT_REPORT` and `KINTSUGI_AGENT_MODEL_CALL_TIMEOUT_REPORT` (with an `s`); the script clears each one whose option is not given, so a run never takes a value the shell left. Every line of `summary.tsv` and `aggregate.tsv` records the effective `model`, `think` (`on`, `off`, or `default` when no field was sent; `off` on the cloud route, `-` for rules), `reportCap` and `reportTimeout`: the last four columns of `aggregate.tsv`, and the four before `candidates` in `summary.tsv`.

The default mode is `dossier`; `--samples` takes 1 to 999. Explorer and dossier samples pool the fault's `stress` recordings only, the ones they were measured on, its first `stress` recording in the sample's mode first: recordings imported into `dev`, `holdout` or `probe` never change them, and a `holdout` recording never reaches them. `--split`, `--repeats`, `--view` and `--report` are errors there. `--mode explorer` and `--mode both` (explorer and dossier) still run, but explorer replays are not valid measurements: under exact matching most of the explorer's model-chosen calls are unrecorded (coverage 0.00-0.38 in the T4 smoke check), so they measure the replay's gaps, not the agent. A `MISS` is a measurement; the script exits 1 only when a sample could not run. `REPLAY_DIR` names another replay directory, as for `record-replay.sh`.

### Score replay results

The scorer in `evals/` ([ADR-015](adr/ADR-015-evaluation-protocol.md), `openspec/specs/evals/spec.md`) reads one or more `summary.tsv` and prints AC@1 per fault and pooled, with Wilson 95% CIs, and the paired difference of every two configs, with exact McNemar and a seeded cluster bootstrap. It uses the Python standard library only, so a bare `python3` runs it.

The tests need pytest, installed once in a git-ignored venv (on WSL, `python3 -m venv` needs the `python3.12-venv` package):

```bash
python3 -m venv evals/.venv
evals/.venv/bin/pip install -r evals/requirements-dev.txt     # pytest==9.1.1
evals/.venv/bin/python -m pytest evals -q
```

Score from `evals/`, one `--label` per config:

```bash
cd evals
python3 -m kintsugi_evals score \
  --label A ../infra/scripts/out/replay-<time>-local \
  --label D ../infra/scripts/out/replay-<time>-cloud \
  --out results/<date>-<name>
```

- An unlabelled dir is labelled `route-mode-view` from its columns.
- `--out` writes `report.txt` (the printed report), `scores.tsv`, `paired.tsv`, `inputs.tsv` (source, sha256, rows) and `inputs/<label>-summary.tsv`, a copy of each input, so the result regenerates from committed files.
- `--seed` (default 1) and `--iterations` (default 10000) set the bootstrap.
- Commit the plan to `evals/plans/` before the run (template `evals/plans/TEMPLATE.md`) and the result to `evals/results/<date>-<name>/` after it.

### Convert RCAEval cases

The offline track reads the RCAEval RE2 dataset ([ADR-020](adr/ADR-020-rcaeval-dataset-converter.md)). The data stays outside the repository, about 11 GB extracted for RE2-OB and RE2-SS; `RE2-TT.zip`, 2.8 GB more, is read only by BARO's reproduction. Download Zenodo record [14590730](https://doi.org/10.5281/zenodo.14590730) and check the md5s before unzipping:

```bash
mkdir -p ~/data/rcaeval && cd ~/data/rcaeval
curl -LO https://zenodo.org/records/14590730/files/RE2-OB.zip
curl -LO https://zenodo.org/records/14590730/files/RE2-SS.zip
curl -LO https://zenodo.org/records/14590730/files/RE2-TT.zip
md5sum RE2-OB.zip RE2-SS.zip RE2-TT.zip
# b9e23f8842c404b396ffd2becff15de4  RE2-OB.zip
# bd747a8fc7c5be00c613e13fbf9dd74b  RE2-SS.zip
# a7fbcd1ada406067dcc50771ae398408  RE2-TT.zip
unzip -q RE2-OB.zip && unzip -q RE2-SS.zip && unzip -q RE2-TT.zip
export KINTSUGI_RCAEVAL_DIR=~/data/rcaeval     # the converter's default root
```

Convert from `evals/`. Run it with `python3 -I`, as for any code that reads downloaded data, so nothing in the current directory is imported:

```bash
cd evals
python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
  convert-rcaeval --system re2-ob --rep 1
```

- It writes one blinded `<hash>.json` per case, `recordings.tsv` (the truth is only there) and `converter-report.tsv` to `evals/out/rcaeval/re2-ob-rep1/` (git-ignored); `--out` sets another directory, `--case NAME` converts only that case (repeatable).
- Only RE2-OB repetition 1 is `dev`. Repetitions 2-3 (holdout A) and RE2-SS (holdout B) are refused unless `--allow-holdout A` or `--allow-holdout B` names them, which only a committed holdout plan may do.
- The conversion fails if a recording holds a fault word, a case name or a category outside the logs' own text; fault words in log text are counted in `converter-report.tsv`. A failed run leaves nothing in `--out`: the files are written to a staging directory beside it and moved in only after the check.
- RE2-OB traffic comes from `traces.csv`. To check that every caller -> callee pair in the dev traces is an edge of the topology file (AC3 of `odd/tasks/rcaeval-converter.md`; about 75 s, 70 MB), run from the repository root:

  ```bash
  python3 -I evals/kintsugi_evals/rcaeval/trace_edges.py \
    --topology agent/src/main/resources/topology/re2-ob.json ~/data/rcaeval/RE2-OB/*/1
  ```

  It takes the pairs from client spans (an `operationName` of `hipstershop.<Name>Service/<Method>`) and from parent links, with the same names as the converter (`frontendservice` is `frontend`), marks each pair `edge` or `NOT AN EDGE`, and exits 1 when a pair is not an edge or no pair is found.
- The agent's test copy in `agent/src/test/resources/rcaeval/` is the converter's output on the synthetic fixture. To regenerate it, run from `evals/` `.venv/bin/python -m kintsugi_evals convert-rcaeval --root tests/fixtures/rcaeval --system re2-ob --rep 1 --case checkoutservice_cpu --out ../agent/src/test/resources/rcaeval`, then delete the `converter-report.tsv` it leaves in that directory: it is not part of the fixture.

### Rank RCAEval cases with BARO

BARO is the published baseline, ported with the standard library together with the published pipeline's preprocessing ([ADR-024](adr/ADR-024-baro-port.md)). It reads the case files directly, so a run needs a committed plan first. From `evals/`, again with `python3 -I`:

```bash
python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
  baro-rcaeval --system re2-ob --rep 1
```

- It writes one `summary.tsv` to `evals/out/rcaeval/baro-re2-ob-rep1/` (git-ignored; `--out` sets another directory), which `score --service-only` reads next to the replayed configs on the same recording ids, and `services.json`, the services ranked over the run, which `score --topology` accepts.
- Only RE2-OB repetition 1 and `--system re2-tt --rep all` are accepted. Every other repetition or system is refused before any case file is read.
- `--system re2-tt --rep all` writes every repetition of RE2-TT into one `summary.tsv` of split `reference`, to `evals/out/rcaeval/baro-re2-tt-repall/`. RE2-TT is in no split of the vision, so this spends no holdout, and the converter refuses it.
- Before ranking it drops `time.1` and `_latency-50`, turns infinities into empty cells, fills every column forward over the whole file and then with 0, keeps the last 600 rows before `inject_time` and the first 600 from it, and renames `_latency-90` to `_latency`. These are row counts, so a side is 600 s only on a case without gaps. BARO has no window of its own, and a case with a non-numeric cell anywhere is refused.
- The port's goldens are in `evals/tests/fixtures/baro/`, written by `generate_goldens.py` in a throwaway virtual environment; its header holds the versions and the steps, and it holds a copy of the pipeline's lines from `main.py`, which cannot be imported.
- `compare_dev.py` in the same directory compares the port with that pipeline on the 30 dev cases, in the same environment, and exits 1 on any difference or any other number of cases. `--system re2-tt` does so on the 90 cases of RE2-TT, and refuses a directory of another name. It reads case files, so it needs the same committed plan.

### Replay RCAEval cases

Replay the converted cases with `--dataset re2-ob` ([ADR-020](adr/ADR-020-rcaeval-dataset-converter.md), `odd/tasks/rcaeval-converter.md`), then score them on the service only:

```bash
# 1. Convert (above): writes evals/out/rcaeval/re2-ob-rep1/.
# 2. Replay, here Config 0, from the repository root:
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1 infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision
# 3. Score from evals/: RCAEval's AC@1 names the root-cause service only.
cd evals
python3 -m kintsugi_evals score --service-only --label 0 ../infra/scripts/out/replay-<time>-rules
```

- `REPLAY_DIR` is required for `re2-ob`: it names the converter's output directory, which stays outside the repository. Without it the script exits 2.
- The faults are RCAEval's fault types, `cpu`, `mem`, `disk`, `delay`, `loss` and `socket` (all six by default; `--faults cpu,delay` chooses), and the truth in `recordings.tsv` is `<service>|<category>` by ADR-020's map.
- The test JVM gets `-Dkintsugi.agent.topology=classpath:topology/re2-ob.json`, so the gatherer, the rules and the loop read Online Boutique's topology. A lab run (`--dataset lab`, the default) gets the lab's file, as before.
- Decision mode only: the converter writes one decision case per RCAEval case, in `dev` (repetition 1), or in `holdout` only under a committed holdout plan.
- `score --service-only` counts a report correct when its service is the truth's, whatever its category, still with Wilson 95% CIs; the report's first line per config ends with `; AC@1 on the service only`. Without the flag the scorer compares the whole `service|category`.
- `score --service-only --topology agent/src/main/resources/topology/re2-ob.json`, with the path relative to where it runs, adds AC@1, AC@3, AC@5, Avg@5 and their chance levels over that topology's services to the report, and `--out` also writes `ranked.tsv` (REQ-13). The lab never passes it. Without `--service-only` it is refused.

### Choose the rules version

`kintsugi.agent.rules` (`KINTSUGI_AGENT_RULES`) selects the rules configuration ([ADR-021](adr/ADR-021-resource-rules-config.md), `odd/tasks/resource-facts.md`):

- `v1`, the default, is Config 0 (ADR-016), frozen.
- `v2` is Config 0R: Config 0's rules unchanged, plus R-RES, which names a service `RESOURCE_EXHAUSTION` from its resource facts only where Config 0 abstains. Its candidates are Config 0's, then the resource-abnormal services.
- Any other value stops the agent's start. The agent reads it only for the hybrid report's candidates.

On replay, `replay-agent.sh --rules v1|v2` (default `v1`) passes it as `-Dkintsugi.agent.rules`, next to the topology and the view, so a value left in the shell never applies:

```bash
# Config 0R on the RE2-OB dev cases; summary.tsv's model column reads rules-v2.
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1 infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision \
  --rules v2
# HR: the hybrid over Config 0R's candidates; each incident names the version under "rules".
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1 infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision \
  --report hybrid --rules v2
```

- `--rules` applies to decision mode on the `rules` route or with `--report hybrid`. Any other run exits 2 before Maven, since the version would change nothing there.
- The lab recordings predate the resource calls, so their resource facts are failed and `v2` equals `v1` on them.
- The output directory is named by the route only, so keep a `v1` and a `v2` run apart by their directories, and label them when scoring.

### Freeze check

M3 closes when `check-freeze.sh` passes ([vision](vision.md) §7, `odd/tasks/check-freeze.md`). It is read-only: it reads git, the recordings' manifest and the committed results, and writes nothing. It needs git and Python 3.11 or later, and exits 2 on an older `python3`.

```bash
infra/scripts/check-freeze.sh                          # tag freeze-m3, M4 from 2026-12-07
infra/scripts/check-freeze.sh --tag freeze-m3 --m4-date 2026-12-07
infra/scripts/test-check-freeze.sh                     # its tests, on throwaway repositories
```

It prints one PASS line per condition that holds and one FAIL line per failure, naming the file or commit, then a RESULT line. It exits 0 only when all four hold:

1. `tag`: the tag exists.
2. `unchanged`: nothing under `agent/src/main/` (the loop, the rules, the prompt, the taxonomy) changed between the tag and HEAD, and nothing there is uncommitted or untracked.
3. `holdout-2` ([ADR-026](adr/ADR-026-holdout2-seed.md)), all read at HEAD:
   - the seed `infra/holdout2/seed.txt` was first committed in a commit that descends from the tag (`git merge-base --is-ancestor`) and is later than the tag's commit time, and never changed since;
   - the generator as of the tag, `infra/scripts/holdout2-schedule.py`, run on that seed gives exactly the committed `infra/holdout2/schedule.tsv`;
   - the schedule's first commit descends from the seed's first commit, or is it: a schedule committed before its seed means the seed was known earlier;
   - every schedule case is named in the `case` column of exactly one `holdout-2` manifest row: a missing case fails, naming the first five, and a second row for one case fails, naming both files;
   - every `holdout-2` recording's incident `startedAt` is later than the tag's commit time and than the schedule's first commit.

   With no `holdout-2` recording yet, or a manifest with no `case` column (reported once), it fails closed and says so. A row with no value for `case`, empty or shorter than the header, fails as naming no schedule case.

   The limit: git commit times are written by the committer and can be set to anything, and the repository has no remote. So condition 3 proves the order of the history as written, not that it was written honestly; the backup bundles kept outside the laptop are what a reader can compare it against (ADR-026, amendment).
4. `results`: no `.tsv` under `evals/results/`, in any commit before midnight UTC of the M4 date (a version deleted later included), holds a row of lab `holdout-2` or of an RCAEval holdout: A (RE2-OB repetitions 2-3) or B (RE2-SS). RCAEval rows are found by their recording id, recomputed from the row's fault and truth as the converter blinds it in every table with a fault, a truth and a recording or file column (a split column or not), and by split `holdout` on a recording that is not a lab `verify-agent-*` one. Lab holdout-1 rows are allowed: they were read once in M2 by plan.

The tag name, the M4 date, the frozen paths, the split name and the holdout-2 paths are set once at the top of the script.

### Holdout-2 schedule

Holdout-2's 75 cases (20 F1, 20 F2, 20 F3, 15 healthy controls) and their F1 and F2 parameters come from one seed drawn after the freeze tag ([ADR-026](adr/ADR-026-holdout2-seed.md), `odd/tasks/lab-realism-min.md`). The generator is deterministic: the same seed gives the same file, byte for byte, and condition 3 above reruns it as of the tag.

Every draw comes from SHA-256, not from Python's `random`, whose `shuffle` and `choice` may change between Python versions. Block i (from 0) is SHA-256 of the seed's 32 bytes followed by i as 8 bytes big-endian, read as four 8-byte unsigned big-endian words in order. An integer in [0, n) takes the next word, discards it when it is at or above the largest multiple of n not over 2^64, and otherwise is the word mod n. The class list (the F1 cases, then F2, F3, healthy) is shuffled by Fisher–Yates from the last position down, swapping position k with a draw in [0, k + 1); then each F1 and F2 case, in the shuffled order, draws its value's index the same way. The generator's docstring states the same, and its tests check it against an independent reimplementation.

```bash
# 1. Only after the tag freeze-m3 exists: 256 bits from /dev/urandom, as 64 hex characters, committed alone.
mkdir -p infra/holdout2
head -c 32 /dev/urandom | xxd -p -c 64 >infra/holdout2/seed.txt
# without xxd: { od -An -tx1 -N32 /dev/urandom | tr -d ' \n'; echo; } >infra/holdout2/seed.txt
git add infra/holdout2/seed.txt && git commit -m "chore(holdout-2): draw the seed"
# 2. The schedule, from the committed seed, in its own commit before the first recording.
infra/scripts/holdout2-schedule.py infra/holdout2/seed.txt --out infra/holdout2/schedule.tsv
git add infra/holdout2/schedule.tsv && git commit -m "chore(holdout-2): generate the schedule"
infra/scripts/test-holdout2-schedule.sh                           # the generator's tests
```

The schedule is a TSV with the columns `case`, `class`, `parameter` and `value`: F1 rows draw `gateway_latency_ms` from 2000, 5000, 10000, 20000; F2 rows draw `gateway_error_status` from 429, 500, 503; F3 rows have `wrong_port` 9999; healthy rows have `-`. Never edit the seed after its commit: condition 3 fails on a seed changed since. Commit the schedule before the first recording, and record each case once: a failed recording is not imported, and its redo takes the same case. A malformed seed or a missing file exits 2.

### Record holdout-2

After the seed and the schedule are committed (above), record the schedule in decision mode, in as many sessions as it takes, check each run, and import it into `holdout-2`:

```bash
# 3. Record: all 75 cases, or a session's share of them, in the schedule's order.
infra/scripts/verify-agent.sh --mode decision --schedule infra/holdout2/schedule.tsv --cases 1-15
# 4. Check the windows, then import: each row names its schedule case in the manifest's case column.
infra/scripts/check-recordings.sh infra/scripts/out/verify-agent-<UTC time>
infra/scripts/record-replay.sh --split holdout-2 infra/scripts/out/verify-agent-<UTC time>
# 5. Once every case is imported and committed:
infra/scripts/check-freeze.sh
```

A case whose recording fails (a refused import, a `not scored` or `baseline failed` line) is recorded again with `--cases <its case>` in a later run, and only the redo is imported: condition 3 fails on a case with no row or with two. Each decision-mode case costs about 21 minutes on the cloud route (the baseline phase dominates), so the 75 cases take about 26 hours of lab time.

### Reset a volume

Init scripts (`infra/postgres/init/`, `infra/knowledge-db/init/`) run only when their volume is empty. When one changes after the volume was created (for example when the `agent` database was added to `knowledge-db`), or when a volume's state is broken, delete that one volume and start the lab again:

```bash
infra/scripts/reset-volume.sh knowledge-data         # asks for confirmation
infra/scripts/reset-volume.sh knowledge-data --yes   # no prompt
docker compose -f infra/compose.yaml up -d           # recreates the volume and runs its init scripts
```

The script accepts only the volume keys of `infra/compose.yaml` (it lists them on a wrong name), removes the containers that use the volume and leaves the rest of the lab running. `knowledge-data` loses nothing that cannot be rebuilt: `knowledge-indexer` re-embeds the runbooks at the next start. `postgres-data`, `prometheus-data` and `loki-data` lose their recorded orders, metrics and logs.

### Back up the repository

The repository has no remote, so a full `git bundle` kept outside WSL is its only backup. Run this at the end of every session and when a milestone or feature closes:

```bash
infra/scripts/backup-bundle.sh                                  # writes to C:\Users\andrea.tarsi\kintsugi-backups
KINTSUGI_BACKUP_DIR=/mnt/d/backups infra/scripts/backup-bundle.sh   # another folder
```

The script bundles every branch and tag into `kintsugi-YYYY-MM-DD-full.bundle`, checks that every local branch tip and every tag is in it, and writes its sha256 next to it. A bundle with no `.sha256` comes from an interrupted run: check it with `git bundle verify <bundle>` before you recompute the checksum with `sha256sum`, and keep an older bundle if the check fails. A second run on the same day replaces that day's bundle. Uncommitted changes are not included (the script warns). To restore: `git clone <bundle> kintsugi`, after `sha256sum -c <bundle>.sha256`.

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
