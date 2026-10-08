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
- **Network:** port 8085 in the container, published only on `127.0.0.1:18085`. The agent reaches it by its compose service name.

### Origin validation

Verified in the 2.0.1 sources:

- The MCP SDK 2.0.0 has a `ServerTransportSecurityValidator` hook and a `DefaultServerTransportSecurityValidator` that checks `Origin` and `Host` against allow-lists.
- **Spring AI 2.0.1 does not enable it.** `McpServerStatelessWebMvcAutoConfiguration` builds `WebMvcStatelessServerTransport` without a validator, so the builder's default `ServerTransportSecurityValidator.NOOP` applies. No `spring.ai.mcp.server.*` property changes that.
- **How mcp-tools enables it:** `McpTransportConfiguration` declares its own `webMvcStatelessServerTransport` bean, so the auto-configured one backs off (`@ConditionalOnMissingBean`), with `DefaultServerTransportSecurityValidator` and an **empty origin allow-list**.

With that configuration:

| Request | Result |
|---------|--------|
| No `Origin` header (the agent, `curl`) | accepted |
| Any `Origin` header (a browser page) | 403 |

That blocks DNS-rebinding from a page open in the developer's browser. The smoke test asserts the 403. `Host` is not restricted yet (see Consequences).

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
| `query_metric` | Prometheus `query_range`; at most 200 points per series and 10 series |
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
- mcp-tools also opens its connections read-only, so a bug in mcp-tools cannot write either.

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
- **`Host` is not validated yet.** Origin validation stops browser-based DNS rebinding, which is the main local threat. A `Host` allow-list (`mcp-tools:8085`, `localhost:18085`, `127.0.0.1:18085`) would add a second layer, but it must match the compose wiring, so it waits for that task.
- **Prompt-injection risk remains.** Log lines and event `detail` are attacker-controllable in principle, for example through a crafted order note. Labelling them as data lowers the risk but does not remove it. The tools are read-only, so the worst outcome is a wrong diagnosis, not a write; the policy layer (M3) guards writes.
- **One more service** to build, run, monitor and keep in step with the stack versions.
- **Spring AI customization point.** Origin validation depends on replacing an auto-configured bean by name. A Spring AI upgrade that renames the bean, or adds its own property, must be checked; the smoke test's 403 assertion catches a silent regression.
- **Templates limit the questions.** A question that no tool answers needs a new tool and a code change.
