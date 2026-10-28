# ADR-011: A deterministic evidence dossier before the model reasons

## Status

Accepted (2026-10-16), with the windows defined below.

Amended by ADR-014 (2026-10-28): the "facts only" rule allows the per-service sheets, arranged by the call graph, and the assessed report's roles, checked by code against the graph.

## Date

2026-10-16

## Context

The agent loop of ADR-010 lets the model explore: in each phase it plans, picks tools and reads their summaries. Runs 11-14 of `verify-agent.sh` with `granite4.1:8b` on CPU (`odd/tasks/agent-loop.md`, "Progress") scored:

| Fault | Truth | Matches |
|---|---|---|
| F1 | `gateway-mock\|DEPENDENCY_REJECTING` | 2 of 4 |
| F2 | `gateway-mock\|DEPENDENCY_LATENCY` | 3 of 4 |
| F3 | `order-service\|CONFIGURATION` | 0 of 4 |

F3 recreates order-service with a wrong payment URL (`http://payment-service:9999`). Its signals exist only on the order-service side:
- WARN log lines in order-service;
- `PAYMENT_UNAVAILABLE` order events;
- a `ConnectException` client metric;
- the recreate, seen as a restart.

payment-service and gateway-mock see nothing.

In all four runs the model never made one call that looked at order-service:
- the TRIAGE plan always started from gateway-mock, payment-service or postgres;
- INVESTIGATE calls targeted payment-service and gateway-mock, and their empty results were read as gateway trouble;
- several calls were wasted: invalid arguments (`source=payment type=PAYMENT_UNAVAILABLE`), a `get_order_trace` with the placeholder `<SELECT_A_RECENT_ORDER_ID>`, and true duplicates;
- every `search_runbooks` was scoped to payment-service, although `docs/runbooks/order-service.md:78` describes F3 exactly.

The prompt's category rule ("if A times out or fails because B does, name B", `Prompts.java:58-59`) also pulls the model toward the downstream service.

Diagnosis: the small model is weak at choosing where to look, that is, at exploring through tool calls. It is better at reading facts it is given. The vision anticipates this:
- "Deterministic rules doing the heavy lifting" (`docs/vision.md:116`);
- the risk "Small models fail at tool calling → fewer tools per step, more deterministic rules" (`docs/vision.md:233`).

A prompt hint aimed at order-side evidence would fix F3 by teaching the test. It would not tell us whether a small model can diagnose an incident it has not been coached for.

## Decision

### The dossier

Before the LLM phases, deterministic code builds an **evidence dossier** for the incident window.
- It calls the existing MCP tools, so every agent action still goes through the MCP server (`docs/vision.md:61`, ADR-004).
- It runs the same calls, in the same order, for every fault and every service. Nothing in it depends on the symptom or on a fault id.
- The model receives it as compact Markdown in the prompt. It forms hypotheses from it, and may still make a few targeted tool calls to confirm them (drill-down).

### Facts only

The dossier states facts and nothing else.
- No judgments, hints, anomaly markers or thresholds.
- No interpreting wording such as "anomalous", "should be" or "zero calls means...".
- Numbers, names, labels, counts and verbatim log signatures only.

Why: interpretive text would encode the answer and teach the test. The core rule stays: the LLM proposes, deterministic code gathers and verifies, policy authorizes.

### Sections

1. **Service status:** per service, restarts in the window (process start times) and uptime.
2. **Order states:** counts per status in the previous window and in the incident window, with 2-3 real order UUIDs per status for drill-down.
3. **Traffic matrix per edge:** caller-side attempts with `status`, `outcome` and `exception` labels, next to server-side received counts. The edges are:
   - client → order-service;
   - order-service → inventory-service `POST /reservations`;
   - order-service → payment-service `POST /payments`;
   - payment-service → gateway-mock `POST /authorize`.
4. **Log signatures:** WARN and ERROR signatures per service, with counts.
5. **Event counts:** order and payment events by type and outcome, with the real names (order events `ORDER_RECEIVED`, `INVENTORY_RESERVED`, `PAYMENT_AUTHORIZED`, `PAYMENT_UNAVAILABLE`, ...; payment events `PAYMENT_ATTEMPT` with its outcomes).

### Windows

- The agent is never told when the fault began; that would leak the answer.
- The **incident window** is the last N minutes before the incident opened (N is the incident's `sinceMinutes`).
- The **previous window** is the N minutes just before it, of the same length.
- Every section that compares before and during uses these two windows, the way an on-call engineer compares a dashboard with the previous period.

### Budgets

- The dossier must fit well inside the 8192-token context (`num-ctx`). Target: about 1.5k tokens.
- How many follow-up tool calls remain, and in which phases, is decided in the feature design (`odd/tasks/evidence-dossier.md`), not here.

### Two measurable modes

- **Explorer mode:** the current ADR-010 loop.
- **Dossier mode:** the dossier first, then fewer, targeted calls.

Both stay runnable and are compared on the same incidents. The benchmark's question is what works for small models, so neither result is discarded.

## Feasibility

A dry run on 2026-10-16 injected F3 in the live lab and queried the sources by hand.

- **Caller side, order-service → payment-service:** `http_client_requests_seconds_count{service="order-service", client_name="payment-service", method="POST", uri="/payments", status="CLIENT_ERROR", outcome="UNKNOWN", exception="ConnectException", error="ResourceAccessException"}` = 6. order-service → inventory-service is `uri="/reservations"`, not `/reserve`.
- **payment-service → gateway-mock has no client-side series.** `HttpPaymentGateway.java:50` builds its client with a static `RestClient.builder()`, which Spring Boot does not instrument. Only the server side (gateway-mock `/authorize`) is visible. The matrix is asymmetric until this is fixed.
- **Server side:** payment-service `POST /payments` received 0 requests during the F3 window. The counter stayed flat at 24 after the 3 healthy orders.
- **Restarts:** `process_start_time_seconds` shows the order-service recreate; `service_restarts` counts distinct start times. The restore recreates order-service again, so a window that reaches past the restore shows 2 start times.
- **Counter arithmetic:** `increase()` over short windows extrapolates and gives non-integer values (5.37, 8.47). Exact counts need raw sample deltas from `query_range`, with reset handling. After a recreate, the old process's series share labels with the new one's; only the reset shows the change.
- **Order counts:** `lab_orders` is an all-time gauge. Window counts must come from the orders table (`status_summary` with `sinceMinutes`).
- **Logs:** Loki `{service="order-service"} |= "WARN" |= "ConnectException"` returned 6 lines. The logged `exception=` field and `order_events.detail.exception` say `java.nio.channels.ClosedChannelException`; `ConnectException` appears only in the cause text. Signatures must keep the cause, not only the exception field.
- **Events:** the `order_events` time column is `occurred_at`. F3 gives `PAYMENT_UNAVAILABLE` events with outcome `PAYMENT_PENDING`; `detail` JSON holds `exception`, `url` and `message`.

Every section can be filled from sources the MCP tools already read. Some need new read tools or parameters; the feature document lists them.

## Alternatives considered

- **Keep tuning prompts toward order-side evidence:** rejected. It teaches the test, and it attacks the model's weak skill (choosing where to look) again instead of removing the need for it.
- **Record F3 as a known limit and close the M2 check at 2/3:** rejected for now. It gives up on understanding why the model fails, and the vision already names the remedy.
- **A larger or cloud model calibration run:** useful as a separate measurement of the ceiling (configs B and D in `docs/vision.md:104-109`). It is not a fix for a local-first agent on a laptop.
- **Let code diagnose with rules:** rejected. The LLM would become redundant and the benchmark meaningless. The dossier gathers facts; the model still names the cause.

## Consequences

### Positive

- The model reads facts instead of searching for them, which plays to what the small model does well.
- Every fault gets the same evidence, so a miss points at reasoning, not at a lucky or unlucky tool choice.
- Fewer model turns per incident. Each `ACT` turn costs 40-350 s on this host, so latency should drop even though the first prompt grows.
- The dossier is deterministic and testable with golden files, like the tool summaries of ADR-010.
- Instrumenting the gateway client also gives F1 and F2 a caller-side view of the gateway edge.

### Negative

- The "tool selection" eval metric (`docs/vision.md:134`) changes meaning in dossier mode: most evidence is gathered by code, so it measures only the drill-down calls. Reports must say which mode produced them.
- The first prompt grows by about 1.5k tokens. At 9-11 tokens/s of prompt eval this adds about 2-3 minutes to the first call, to be offset by fewer turns. **To check** in the first runs.
- The payment-service → gateway-mock edge has no caller-side metrics until the lab instrumentation task lands.
- F1 and F2 must be verified again in dossier mode; their 2/4 and 3/4 in explorer mode are not inherited.
- Two modes to keep working, with a switch and tests for both.
- New MCP read tools or parameters widen the tool surface (ADR-004), and each needs its own living spec.
- The facts-only rule must be enforced by a test, or interpretive wording will creep in.
- ADR-010 needs an amendment for the dossier step, the mode switch and the changed phases.
