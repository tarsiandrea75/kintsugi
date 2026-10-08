# Feature: mcp-tools

Locator: `odd/tasks/mcp-tools.md` · Engram mirror: `odd/mcp-tools/tasks` · Branch: `feat/mcp-tools` (from `feat/fault-injector-04-wiring` at `a1ea863`)

## Objective

Build `mcp-tools/`, the MCP server exposing the agent's read-only tools on logs, metrics, recorded events and deploy history. This is the first M2 item (`docs/vision.md:155`). It implements ADR-004 as reserved in the vision: "Tools exposed via MCP instead of internal calls". Every agent action that touches the system goes through it (`docs/vision.md:61`).

## Problem and why

The agent needs evidence. Today the evidence is in Loki, Prometheus and the event tables, but nothing exposes it as tools a model can call safely. The tools must be:
- narrow enough for a small local model, with few parameters and validated inputs;
- safe against prompt injection, because log text and event `detail` are untrusted;
- physically unable to reach the ground truth in `faults`.

## Scope

- In:
  - the `mcp-tools` Spring Boot service, an MCP server over Streamable HTTP;
  - the log, metric, event and deploy-history tools, all read-only and built from predefined templates;
  - the `kintsugi_agent` PostgreSQL role;
  - compose wiring;
  - a check script that calls the tools through MCP while a fault is active;
  - ADR-004, the living spec and docs.
- Out:
  - the agent and its MCP client configuration (the next M2 feature);
  - RAG on runbooks (`searchRunbooks`, its own feature with pgvector and ADR-002);
  - write tools such as rollback and restart (M3, behind policy);
  - authentication beyond network isolation.

## Design (decided autonomously on 2026-10-08 while the user was away; **to be reviewed by the human**)

- **Stack:** Spring Boot 4.1.1 and Spring AI 2.0.1 (ADR-006), with `spring-ai-starter-mcp-server-webmvc` managed by `spring-ai-bom` 2.0.1. That brings MCP Java SDK 2.0.0 and protocol 2025-11-25.
  - Transport: Streamable HTTP in **STATELESS** mode on `/mcp`. The tools need no server-to-client notifications.
  - Tools are declared with `@McpTool` / `@McpToolParam`.
  - Why not stdio: the agent and mcp-tools are separate long-lived services (in compose), and any stray stdout line corrupts a stdio stream.
- **Network:** port 8085, published only on `127.0.0.1:18085`; the agent reaches it by compose service name. Origin validation is enabled if the server supports it; verify this in Spring AI 2.0.1 and document the result. No other auth for now; this is a known limitation written in ADR-004.
- **No free query languages.** The model never writes SQL, LogQL or PromQL. Every tool builds its query from a template, and the inputs are validated:
  - enums for services, statuses, outcomes, sources and metric names;
  - UUID for an order id;
  - durations capped at 360 minutes;
  - limits capped, with a default of 50 and a maximum of 100;
  - output size capped, a few KB, truncated with a marker.
- **Allowed services:** `order-service`, `payment-service`, `inventory-service`, `gateway-mock`. Never `fault-injector`. The enum makes that impossible.
- **Tools (first set):**

  | Tool | Parameters | Source |
  |---|---|---|
  | `search_logs` | service, contains?, level?, sinceMinutes, limit? | Loki `query_range`, backward |
  | `count_logs` | service, contains?, level?, sinceMinutes | Loki `count_over_time` |
  | `query_metric` | metric (allow-list: request rate, error rate, latency p95, `lab_orders`, `lab_payments`, `gateway_authorize_inflight`, `hikaricp_connections_*`, client request outcomes), service?, sinceMinutes | Prometheus `query_range`; step chosen so each series has at most 200 points; at most 10 series |
  | `get_order_trace` | orderId | `order_events`, `payment_events` and `reservation_events` for that order (at most 50 rows each), plus the order row |
  | `recent_events` | source (order/payment/reservation), type?, outcome?, minDurationMs?, sinceMinutes, limit? | the event tables |
  | `status_summary` | table (orders, payments, stock), sinceMinutes? | aggregates only |
  | `service_restarts` | sinceMinutes | Prometheus `process_start_time_seconds` changes per service (deploy history without the Docker socket) |

- **Database role `kintsugi_agent`:**
  - LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE;
  - CONNECT on `orders`, `payments` and `inventory` only, with USAGE on `public`;
  - SELECT only on `orders`, `order_events`, `payments`, `payment_events`, `stock` and `reservation_events`;
  - per-role settings: `default_transaction_read_only=on`, `statement_timeout=3s`, `transaction_timeout=5s`, `idle_in_transaction_session_timeout=5s`;
  - never CONNECT on `faults`;
  - mcp-tools opens its connections read-only as well.
- **Untrusted content:** every text that comes from logs or `detail` is returned as data inside a clearly labelled field. Tool descriptions tell the model that this content is evidence, not instructions. Prompt-injection hardening of the agent itself comes with the agent feature.
- **Every tool call is recorded,** following the human's "everything is recorded" rule: one structured INFO log line per call with the tool name, the validated parameters, the row/line count, the duration and the outcome. mcp-tools' own logs are excluded from the agent's `search_logs`, because `fault-injector` and `mcp-tools` are not in the service enum.
- **Check script:** `infra/scripts/verify-mcp.sh`. It brings up the lab, injects F1 through the fault-injector, then calls the tools through MCP (JSON-RPC over HTTP with curl) and asserts:
  - `search_logs` finds the order-service WARN line;
  - `query_metric` shows `PAYMENT_PENDING` rising;
  - `get_order_trace` returns the `PAYMENT_UNAVAILABLE` event;
  - `kintsugi_agent` cannot connect to `faults`, cannot write, and is not a superuser;
  - an invalid parameter is rejected with no query run.

  It then restores the lab.

## Acceptance criteria

- mcp-tools serves the 7 tools over MCP Streamable HTTP, and an MCP client can list and call them.
- Every tool rejects invalid parameters before any query, and caps rows, points and output size.
- No tool can reach `faults` or `fault-injector`, run free SQL, LogQL or PromQL, or write anything. Tests and the check script prove it.
- `verify-mcp.sh` passes under F1. `verify-f1.sh` … `verify-f4.sh` still pass.
- `./mvnw test` passes in mcp-tools.

## Delivery

- `auto-chain` with `feature-branch-chain`. Forecast: about 1,800 lines.
- Slices: tracker `feat/mcp-tools` (doc, ADR-004, spec, skeleton); PR 1 `-01-observability-tools`: transport hardening, then T3. T3 is a size exception (about 1,700 lines, of which 750 are tests): the clients reference the query templates (`LogQueries`, `MetricCatalog`) and one `ObservabilityTools` bean holds the four tools, so no cohesive split under budget exists.
- RDD: on; consent is a standing grant.

## Tasks

- [x] **T1 — ADR-004 and living spec.** Route: delegated writer.
- [x] **T2 — Skeleton.** Initializr skeleton if an MCP server dependency id exists; otherwise the same skeleton plus the starter. Route: delegated writer.
- [x] **T3 — Log, metric and restart tools,** with fake Loki and Prometheus HTTP servers, tests first. Route: delegated writer.
- [ ] **T4 — Event and summary tools and the `kintsugi_agent` role,** using Testcontainers, tests first. Route: delegated writer.
- [ ] **T5 — Compose wiring, `verify-mcp.sh` run for real, docs.** Route: delegated writer.

## Progress

- 2026-10-08: research done (Spring AI 2.0.1 MCP, SDK 2.0.0, protocol 2025-11-25, Loki and Prometheus limits, read-only role practices); design decided autonomously; branch created.
- 2026-10-08: T1 + T2 done by a delegated writer.
  - ADR-004 and the spec; the spec fixes concrete values: an 8 KB output cap with a `[truncated]` marker, the `untrustedText` field, outcomes `OK`/`INVALID_PARAMETER`/`UPSTREAM_ERROR`, `contains` of 1–200 characters matched literally, and `minDurationMs` from 0 to 600000.
  - Skeleton from Initializr (`spring-ai-mcp-server` gives the webmvc starter and `spring-ai-bom` 2.0.1; SDK 2.0.0).
  - **Origin validation is OFF by default in Spring AI 2.0.1** (no-op validator). It is now enabled by our own `webMvcStatelessServerTransport` bean using the SDK's `DefaultServerTransportSecurityValidator`: any request carrying an `Origin` header gets 403. A Host allow-list is deferred to T5.
  - Smoke test: `initialize` reports protocol 2025-11-25; `tools/list` returns empty; an evil Origin gets 403. mvn exit 0, 4 tests (parent re-run).
- 2026-10-08: skeleton review (`review-484521c2dd4feca4`) approved. The spec's negated MUSTs became MUST NOT (`d36b24b`). The transport fixes are in T3 part A: an explicit empty origin list; the bean exists only for the STATELESS protocol, and a guard stops startup otherwise; 403 for localhost and `null` origins; `GET /mcp` returns 405; the server version is filtered from the build.
- 2026-10-08: T3 done by a delegated writer. The first run stopped on the session limit before any edit and was restarted.
  - Tools: `search_logs`, `count_logs`, `query_metric` (13 allow-listed templates) and `service_restarts` (`process_start_time_seconds`, which the test confirms is exported).
  - `ToolCallRunner` enforces the 8 KB cap and logs one INFO line per call.
  - LogQL escaping is tested.
  - 60 tests, two runs (parent re-run, mvn exit 0).
  - **Open:**
    - (1) There is no p95: the lab publishes no latency histogram, so `latency_avg` and `latency_max` are used instead.
    - (2) 8 KB holds only about one 200-point series. To fix in T4: a compact point encoding and fewer points.
    - (3) The level filter depends on Spring's console log format.
    - (4) Non-integer arguments are rejected by Spring AI before our validation, so they get no call log.
    - (5) Restarts less than one step apart merge into one.

## Next step

T4 (event and summary tools, `kintsugi_agent` role) plus the compact metric encoding, via a delegated writer.
