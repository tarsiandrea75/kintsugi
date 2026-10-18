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

### Check that the agent diagnoses F1, F2 and F3

The M2 check ([vision](vision.md), [feature document](../odd/tasks/agent-loop.md)): the agent must find the root-cause service and category of F1, F2 and F3 with the local model. M2's check is the local route; the cloud route (`--route cloud`, `claude-haiku-5-5` through Anthropic's API) is the development route of [ADR-012](adr/ADR-012-development-and-verification-models.md) and cannot pass M2.

```bash
infra/scripts/verify-agent.sh                 # F1, F2 and F3 in turn, leave the lab running
infra/scripts/verify-agent.sh --only F2       # one fault, or a list: --only F1,F3
infra/scripts/verify-agent.sh --down          # the same, then `down -v`
infra/scripts/verify-agent.sh --mode dossier  # the agent in dossier mode (ADR-011); the default is explorer
infra/scripts/verify-agent.sh --route cloud   # the agent on the cloud route (ADR-012); the default is local
infra/scripts/compare-agent-modes.sh          # 3 runs per mode, alternating explorer and dossier
infra/scripts/compare-agent-modes.sh --route cloud  # the same on the cloud route
infra/scripts/test-lib.sh                     # the scoring helpers' tests, no lab needed
```

It checks that `python3` is available and, on the local route, that Ollama serves `granite4.1:8b`; it starts or reuses the lab as the other scripts do, checks on the local route that `ollama-proxy` is healthy, then starts the agent and waits for it to be healthy (`start_agent`). For each fault it places 3 healthy orders, injects the fault through the fault injector, places 6 more orders and waits 30 s for the metrics and logs to land. It then opens an incident with the same neutral symptom for every fault and a window that starts with that fault's own orders: the minutes since its first order, rounded up, plus 10 (`incident_window`), recomputed if the `POST` first has to wait for a running incident. It polls the incident every 15 s for at most 52 minutes (the 45-minute incident budget, the 5-minute warm-up and a margin), restores the fault and reads the injection's truth row as the `faults` role. A fault matches when the incident is `COMPLETED` and its report's `rootCauseService` and `rootCauseCategory` equal the truth's `root_cause_service` and `root_cause_category`; the cause is read from the report alone (`incident_fields`, with `python3`), and is `none` when there is no report; `agent_verdict` scores it, and `infra/scripts/test-lib.sh` tests both. The script passes only when every selected fault matches. Every customer id it writes into the lab is a neutral `checkout-xxxxxxxx`, and the symptom names no cause: the agent reads both through its tools.

Before each fault after the first, it waits until 11 minutes have passed since the previous fault's restore (`space_out`), so the previous fault's orders, logs and restarts are older than any window it offers, however early the previous incident ended; the tools count a window back from the time of each call, so it only moves later during a run. A score therefore does not depend on how long the previous incident ran. Traffic from before the run (another check run just before) is not spaced out: let a few minutes pass, or run it on a fresh lab.

Every run, pass or fail, is logged under `infra/scripts/out/verify-agent-<UTC time>/` (ignored by git): `run.log` (the whole output), and per fault `<fault>-incident.json` (the incident with every step, full tool outputs and model answers) and `<fault>-score.txt`; `summary.tsv` has one line per fault, with the mode and the route (`VERIFY_AGENT_RUN_DIR`, when set, names the directory instead; `compare-agent-modes.sh` uses `verify-agent-<UTC time>-<mode>`, and `verify-agent-<UTC time>-<mode>-cloud` on the cloud route). Each `<fault>-score.txt` records the mode and the route (requested, recorded), the incident's `model`, and its tokens (prompt, completion): the totals of the steps' `promptTokens` and `completionTokens`, each `none` when no step reported it. `--mode` sets `KINTSUGI_AGENT_MODE` and `--route` sets `KINTSUGI_AGENT_ROUTE` for the agent container (`start_agent` runs `up -d --build`, so the image is rebuilt from the current code and compose recreates the container when either value changes); an incident whose recorded `mode` or `route` is not the requested one is scored as a miss that keeps the outcome in its message. An incident that is still `RUNNING` after the 52 minutes is ended by restarting the agent (`end_running_incident`), scored as a miss, and the run goes on with the next fault. When the run stops early (Ctrl-C, or a failed step), the fault being diagnosed gets a `not scored` line and fails the run, and an incident it left `RUNNING` is ended the same way; a `RUNNING` incident from elsewhere is waited for before the next `POST`. The restore on exit works as in the F1 check. It needs `docker`, `curl`, `python3` and Ollama with `granite4.1:8b`, and takes 10 to 55 minutes per fault on a laptop CPU, so up to about 3 hours for all three.

The cloud route needs `ANTHROPIC_API_KEY`. The human adds it to `infra/.env` (git-ignored); it is never committed, and no placeholder is either. Compose passes it to the agent (empty by default); a value set in the shell wins over the file, and `verify-agent.sh` drops an empty shell value so it cannot hide the file's. With `--route cloud` and no key in either place, the script stops with exit status 2 before it touches the lab, naming `ANTHROPIC_API_KEY` and `infra/.env`; it never prints the key. On the cloud route it skips the local model's checks (Ollama serving `granite4.1:8b`, `ollama-proxy` healthy) with an info line; `search_runbooks` still embeds through Ollama, so `start_lab` warns as usual when `ollama-proxy` or the indexer is down. Every cloud call costs money: each cloud batch (a `verify-agent.sh --route cloud` run or a `compare-agent-modes.sh --route cloud` comparison) needs the human's go-ahead, and its cost is recorded (ADR-012).

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
