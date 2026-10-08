# ADR-004: Tools exposed via MCP instead of internal calls

## Status

Accepted

> Decided autonomously on 2026-10-08 while the human was away, from the design in the [feature document](../../odd/tasks/mcp-tools.md). **To be reviewed by the human.** A change after review supersedes this ADR or amends it in place before the feature merges.

## Date

2026-10-08

## Context

The first M2 item is "MCP server with read-only tools on logs, metrics, deploys" ([vision](../vision.md), M2). The vision reserves this ADR for "Tools exposed via MCP instead of internal calls" and states that every agent action that touches the system goes through the MCP server.

The agent needs evidence. Today it sits in three places, none of them exposed as tools a model can call safely:

| Evidence | Where it lives |
|----------|----------------|
| Logs of the lab services | Loki, fed by Alloy ([ADR-008](ADR-008-lab-observability-stack.md)) |
| Metrics (request rates, errors, latency, pools, business counters) | Prometheus ([ADR-008](ADR-008-lab-observability-stack.md)) |
| Recorded events and current state | the `orders`, `payments` and `inventory` databases ([ADR-007](ADR-007-lab-persistence-stack.md)) |

The tools must satisfy three constraints:

- **Narrow enough for a small local model** ([ADR-003](ADR-003-local-models-default.md)): few parameters, validated inputs, bounded output.
- **Safe against prompt injection:** log text and event `detail` are untrusted content that the model will read.
- **Physically unable to reach the ground truth:** the `faults` database and the fault-injector's logs ([ADR-009](ADR-009-fault-injector-docker-control.md)).

## Decision

**A separate Spring Boot service, `mcp-tools/`, exposes a fixed set of read-only tools over MCP Streamable HTTP in stateless mode. Every tool builds its query from a template; the model never writes a query language.**

### Stack (verified 2026-10-08)

| Component | Version | Notes |
|-----------|---------|-------|
| Spring Boot | 4.1.1 | as in [ADR-006](ADR-006-stack-versions.md) |
| Spring AI | 2.0.1 | imported through `spring-ai-bom`, as in ADR-006 |
| Starter | `spring-ai-starter-mcp-server-webmvc` | Spring Initializr's `spring-ai-mcp-server` dependency resolves to it when `web` is selected |
| MCP Java SDK | 2.0.0 | `io.modelcontextprotocol.sdk:mcp` and `mcp-core`, managed by the Spring AI BOM |
| MCP protocol | 2025-11-25 | the version the SDK negotiates; the smoke test asserts it in the `initialize` answer |

Checked with `./mvnw dependency:tree` in `mcp-tools/`.

### Transport

- **Streamable HTTP, STATELESS, on `POST /mcp`** (`spring.ai.mcp.server.protocol=STATELESS`). Each JSON-RPC request is answered on its own, with no session.
  - Why stateless: the tools are request/response lookups. They need no server-to-client notifications, sampling or progress, so a session would only add state to lose on a restart.
  - `GET /mcp` answers 405, as the stateless transport defines.
- Tools are declared with `@McpTool` and `@McpToolParam`.
- **Network:** port 8085 in the container, published only on `127.0.0.1:18085`. The agent reaches it by its compose service name, `mcp-tools:8085`.
- **Compose:** the service `mcp-tools` waits for PostgreSQL and Prometheus to be healthy; its healthcheck reads `/actuator/health`. That health has no database indicator (`management.health.db.enabled=false`): a PostgreSQL outage is evidence the event tools report as `UPSTREAM_ERROR` while the log and metric tools keep working, not a fault of mcp-tools. Alloy ships its logs (the tool-call records) to Loki for the human; no tool can select them, because `mcp-tools` is not in the service enum. Prometheus does not scrape it: it exports no tool metrics yet, and leaving it out keeps Prometheus to the lab itself.
- **Credentials:** `KINTSUGI_AGENT_DB_PASSWORD` has a lab default in compose only. The init script and mcp-tools both require it and have no default of their own.

### Origin and Host validation

Verified in the 2.0.1 sources:

- The MCP SDK 2.0.0 has a `ServerTransportSecurityValidator` hook and a `DefaultServerTransportSecurityValidator` that checks `Origin` and `Host` against allow-lists.
- **Spring AI 2.0.1 does not enable it.** `McpServerStatelessWebMvcAutoConfiguration` builds `WebMvcStatelessServerTransport` without a validator, so the builder's default `ServerTransportSecurityValidator.NOOP` applies. No `spring.ai.mcp.server.*` property changes that.
- **How mcp-tools enables it:** `McpTransportConfiguration` declares its own `WebMvcStatelessServerTransport` bean with `DefaultServerTransportSecurityValidator`, an **empty origin allow-list**, set explicitly rather than left to the builder's default, and a **Host allow-list** from `kintsugi.mcp.allowed-hosts` (default `mcp-tools:8085,localhost:18085,127.0.0.1:18085`).
  - The SDK skips the Host check when its list is empty, so an empty or missing property stops the startup instead.
  - The auto-configured bean backs off because its `@ConditionalOnMissingBean` has no attributes, so it matches **by type** (the method's return type), not by name.
  - The validator exists only on the STATELESS transport. Our bean is conditional on `spring.ai.mcp.server.protocol=STATELESS`, and a guard bean fails the startup for any other protocol, so a protocol switch cannot silently drop validation. Both compare the value ignoring case, as Spring's relaxed enum binding does, so `stateless` works too.

With that configuration:

| Request | Result |
|---------|--------|
| No `Origin` header (the agent, `curl`) | accepted |
| Any `Origin` header (a browser page) | 403 |
| A `Host` outside the allow-list | 421 |

That blocks DNS-rebinding from a page open in the developer's browser. The smoke test asserts the 403 for `http://evil.example`, `http://localhost:18085` and `null`, that the active transport bean is ours, and that `GET /mcp` answers 405. Unit tests check the three compose hosts pass and other hosts get 421; `infra/scripts/verify-mcp.sh` checks the 403 and the 421 against the running container.

### No free query language

The model never writes SQL, LogQL or PromQL. Every tool fills a fixed template from validated parameters:

| Parameter kind | Validation |
|----------------|------------|
| Service | enum: `order-service`, `payment-service`, `inventory-service`, `gateway-mock` |
| Status, outcome, source, metric, table | enums or allow-lists |
| Order id | UUID |
| Time window (`sinceMinutes`) | 1 to 360 |
| Row limit | default 50, maximum 100 |
| Output | a few KB at most, truncated with a marker |

An invalid parameter is an error returned to the model, and no query runs.

- `fault-injector` and `mcp-tools` are not in the service enum, so no tool can read their logs. The fault-injector's logs are already dropped by Alloy (ADR-009); the enum makes it impossible at the tool level as well.

### Tools (first set)

| Tool | Source |
|------|--------|
| `search_logs` | Loki `query_range`, newest first |
| `count_logs` | Loki `count_over_time` |
| `query_metric` | Prometheus `query_range`; at most 60 compact `[unixSeconds, value]` points per series and 10 series, so several series fit in the 8 KB result |
| `get_order_trace` | the order row plus its order, payment and reservation events (at most 50 rows each) |
| `recent_events` | the event tables |
| `status_summary` | aggregates of `orders`, `payments` or `stock` |
| `service_restarts` | changes of Prometheus `process_start_time_seconds` per service |

The contract of each tool is in the [living spec](../../openspec/specs/mcp-tools/spec.md).

- **Deploy history comes from `process_start_time_seconds`.** A recreate by the fault-injector restarts the JVM, so the metric jumps. That is real evidence of a redeploy at time T, read from data the lab already collects.

### Database role `kintsugi_agent`

- `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE`. A superuser ignores `REVOKE CONNECT` (ADR-009), so this is what makes the isolation hold.
- `CONNECT` on `orders`, `payments` and `inventory` only, with `USAGE` on schema `public`.
- `SELECT` only on `orders`, `order_events`, `payments`, `payment_events`, `stock` and `reservation_events`.
- Per-role settings:
  - `default_transaction_read_only=on`;
  - `statement_timeout=3s`;
  - `transaction_timeout=5s`;
  - `idle_in_transaction_session_timeout=5s`.
- Never `CONNECT` on `faults`, and never given the `faults` credentials.
- mcp-tools also opens its connections read-only, so a bug in mcp-tools cannot write either: one Hikari pool per database (at most 2 connections, 2 s connection timeout), each connection marked read-only, and pgjdbc's `readOnlyMode=always` so even auto-commit statements run read-only. The database names are fixed in code, so no setting can point a pool at `faults`.

**How the `SELECT` grants reach tables that do not exist yet.** The init script runs on an empty volume, before any service starts; each service's Flyway creates its tables later, as `lab_app`. Three options were weighed:

| Option | Covers later tables | Only the six tables | Verdict |
|--------|---------------------|---------------------|---------|
| `ALTER DEFAULT PRIVILEGES FOR ROLE lab_app IN SCHEMA public GRANT SELECT ON TABLES TO kintsugi_agent` | yes | **no**: it also opens `order_items` (customer items), `flyway_schema_history` and every table a later migration adds | rejected: wider than the spec |
| Explicit per-table `GRANT` run after Flyway (a one-shot compose job, or a migration in each service) | only if it runs after every migration, on every fresh volume | yes | rejected: an ordering dependency in compose, or mcp-tools' needs written into the lab services |
| **Event trigger** on `ddl_command_end` for `CREATE TABLE` in each lab database | yes | yes: it grants only when the new table in `public` has one of the six names | **chosen** |

The trigger function is created by the superuser in a schema of its own, closed to `PUBLIC`, so schema `public` stays empty for Flyway and no lab role can change it. It runs as the role that issued the `CREATE TABLE`, `lab_app`, which owns the table and so may grant on it. Tests run the real init script and the real migrations in a PostgreSQL 18.6 container and prove the role can read the six tables and nothing else.

Residual risk:
- A table renamed into one of the six names (`ALTER TABLE ... RENAME`) is not granted, and a table dropped and created again is granted again. Both fail closed or stay within the six names.
- The six names are written in the script. A new evidence table needs a script change and a fresh volume, like any other role change (`down -v`).
- The role settings (`default_transaction_read_only`, the timeouts) are session defaults. mcp-tools never changes them, but a client holding the `kintsugi_agent` credentials could `SET` them off. It still cannot write (no grant) or reach `faults` (no `CONNECT`); it could run longer queries. The credentials stay inside mcp-tools.
- Like any role, `kintsugi_agent` can connect to the `postgres` maintenance database through the default `PUBLIC` grant. It holds no lab data.

### Untrusted content

Text that comes from logs or an event's `detail` is returned as data, inside a clearly labelled field. Each tool's description tells the model that this content is evidence, not instructions. Hardening the agent itself against prompt injection belongs to the agent feature.

### Every tool call is recorded

mcp-tools writes one structured INFO line per tool call:
- the tool name;
- the validated parameters;
- the row or line count;
- the duration;
- the outcome.

This follows the "everything is recorded" rule. Because `mcp-tools` is not in the service enum, these lines never come back to the agent through `search_logs`.

## Alternatives considered

- **stdio transport.** The usual choice for a desktop MCP client that spawns the server. Here:
  - the agent and mcp-tools are separate long-lived services in compose, and stdio would make the agent spawn and own the server process;
  - any stray line on stdout, such as a Spring banner or a library log, corrupts the JSON-RPC stream.
- **Internal tool calls, no MCP.** Spring AI `@Tool` methods inside the agent. Simpler, with one less service and no protocol. But:
  - the agent would hold Loki, Prometheus and database credentials, so a prompt-injected agent would have direct reach;
  - the tools could not be reused by another MCP client (the vision's "reusable from other MCP clients");
  - the boundary "every action goes through the MCP server" would exist only by convention.
- **Free query tools** (`run_sql`, `run_logql`, `run_promql`). Maximum flexibility, but:
  - a small model writes wrong queries often;
  - a prompt-injected model could write any query the role allows;
  - unbounded queries can be slow or return huge results.

  Templates trade flexibility for safety and predictability. A missing question becomes a new tool, which is reviewed like any other code.
- **Deploy history through the Docker Engine API.** The most precise, with container create times and image digests. But it means mounting the Docker socket into a service the agent talks to, which is root-equivalent on the host (ADR-009). Rejected.
- **Deploy history from a log written by the fault-injector.** Precise and cheap, but it is the injector's own record: reading it would hand the agent a trace of the injection itself, close to the ground truth. Rejected for the same reason the injector's logs are kept out of Loki.

## Consequences

### Positive

- The agent holds no credentials for Loki, Prometheus or PostgreSQL. Its only reach into the system is a fixed set of validated, read-only tools.
- The ground truth is out of reach on two levels: the enum (no `fault-injector` logs) and the database role (no `CONNECT` on `faults`, not a superuser).
- Bounded outputs keep tool results small enough for a local model's context.
- Every tool call is in the logs, so a diagnosis can be audited step by step.
- Any MCP client, such as an IDE or another agent, can use the same tools.

### Negative

- **No authentication beyond network isolation.** Anyone who can reach port 8085 on the compose network, or `127.0.0.1:18085` on the host, can call the tools. Accepted for a lab bound to localhost. If the lab ever leaves the laptop, add authentication, for example MCP's OAuth authorization or a shared token.
- **The `Host` allow-list must follow the compose wiring.** A new port or service name for mcp-tools needs `kintsugi.mcp.allowed-hosts` changed too, or every call answers 421.
- **Prompt-injection risk remains.** Log lines and event `detail` are attacker-controllable in principle, for example through a crafted order note. Labelling them as data lowers the risk but does not remove it. The tools are read-only, so the worst outcome is a wrong diagnosis, not a write; the policy layer (M3) guards writes.
- **One more service** to build, run, monitor and keep in step with the stack versions.
- **Spring AI customization point.** Origin validation depends on replacing an auto-configured bean by name. A Spring AI upgrade that renames the bean, or adds its own property, must be checked; the smoke test's 403 assertion catches a silent regression.
- **Templates limit the questions.** A question that no tool answers needs a new tool and a code change.
