# Feature: evidence-dossier

Locator: `odd/tasks/evidence-dossier.md` · Engram mirror: `odd/evidence-dossier/tasks` · First branch: `feat/evidence-dossier-01` (from `feat/agent-loop-04-check` at `7204423`)

## Objective

Give the model a deterministic evidence dossier of the incident window before it reasons, built by code through the MCP tools, as decided in ADR-011 (Proposed). Keep the current explorer mode, and measure both modes on the same incidents.

## Problem and why

- Runs 11-14 of `verify-agent.sh` with `granite4.1:8b` on CPU: F1 2/4, F2 3/4, F3 0/4.
- F3 (order-service recreated with a wrong payment URL) is never diagnosed. Its signals exist only on the order-service side, and the model never made one call that looked at order-service. Details: ADR-011, "Context".
- The small model is weak at choosing where to look and better at reading facts. The vision anticipates this (`docs/vision.md:116`, `:233`).
- A prompt hint toward order-side evidence would teach the test. A dossier of facts, the same for every fault, does not.
- agent-loop T5c3 (the M2 check "F1-F3 correctly diagnosed with a local model", `docs/vision.md:157`) stays open and depends on this feature.

## Scope

- In:
  - client-side metrics for payment-service's gateway client (lab instrumentation);
  - the MCP read tools or parameters the dossier needs;
  - the dossier builder in `agent/`;
  - a mode switch (explorer vs dossier) in the loop, with adjusted phases and budgets;
  - `verify-agent.sh` runs in both modes, results recorded.
- Out:
  - code that diagnoses: rules that name a service or category;
  - any F3-specific code, prompt or wording;
  - larger or cloud model runs (a separate calibration, if the human wants one);
  - the Python eval harness (M4).

## Design

See ADR-011 for the decision, the facts-only principle, the sections and the feasibility facts. This section adds the constraints and the open design points.

- **Sections:** service status, order states (before vs during, with 2-3 real order UUIDs per status), traffic matrix per edge (caller side next to server side), WARN/ERROR log signatures with counts, order and payment event counts by type and outcome.
- **Constraints from the 2026-10-16 dry run:**
  - edge labels are real: order-service → payment-service `POST /payments`, order-service → inventory-service `POST /reservations` (not `/reserve`), payment-service → gateway-mock `POST /authorize`;
  - payment-service → gateway-mock has no client-side series (`HttpPaymentGateway.java:50`, static `RestClient.builder()`) until T2;
  - exact counts come from raw sample deltas of `query_range` with reset handling, never from `increase()` (extrapolated: 5.37, 8.47); after a recreate, old and new series share labels and only the reset shows;
  - a window that reaches past the restore shows 2 order-service start times under F3;
  - `lab_orders` is an all-time gauge; window counts come from the orders table (`status_summary` with `sinceMinutes`);
  - log signatures keep the cause text: the `exception=` field says `ClosedChannelException`, and `ConnectException` appears only in the cause;
  - `order_events` uses `occurred_at`; F3 gives `PAYMENT_UNAVAILABLE` with outcome `PAYMENT_PENDING`, and `detail` holds `exception`, `url`, `message`.
- **Section to tool mapping (T3 confirms):**

  | Section | Existing source | Gap |
  |---|---|---|
  | Service status | `service_restarts` | none known; uptime derived from the newest start time (**to check**) |
  | Order states | `status_summary` (`sinceMinutes`); `recent_events source=order outcome=...` for UUIDs | the previous window needs a bounded range `[now-2N, now-N)`, not only "last N minutes" (known gap) |
  | Traffic matrix | `query_metric client_request_outcomes`, `request_rate` | known gap: exact per-edge counts with `uri` and `status` labels (current queries are `rate()` without them) |
  | Log signatures | `search_logs`, `count_logs` | grouping by signature over more than 100 lines (**to check**) |
  | Event counts | `recent_events` (rows, at most 100) | known gap: window counts by type and outcome |

- **Open design points (decided in T4/T5, recorded here):**
  - the dossier's Markdown layout and token target (about 1.5k tokens inside `num-ctx` 8192);
  - the window boundaries: decided in ADR-011 "Windows" (the incident window is the last N minutes, the previous window the N minutes just before it; the fault start is never given);
  - which phases remain in dossier mode, and how many follow-up tool calls (today 8 per incident);
  - how reports record the mode, so the eval can split results.

## Acceptance criteria

- The dossier contains only facts: a test fails on interpretive vocabulary ("anomalous", "should", "unusual", "suspicious", "likely", "because", ...).
- The dossier is identical in shape for every fault: same sections, same order, same edges and services, whether a value is zero or not.
- The dossier fits the token target on recorded F1-F3 outputs.
- It is built only through MCP tools; the agent still reaches the lab only through mcp-tools.
- Explorer mode still works and is selectable; reports state their mode.
- Over the same number of runs, dossier mode is not worse than explorer mode on F1 and F2.
- F3 is measured honestly: no F3-specific code or wording anywhere, and its result is recorded whatever it is.
- `./mvnw test` passes in every touched module; the existing verify scripts still pass.

## Delivery

- Forecast, authored lines: T1 about 330 (docs), T2 about 150, T3 about 500, T4 about 600, T5 about 350, T6 about 80. Total about 2,000, so it is sliced by task.
- Strategy: `feature-branch-chain` (the project's standing default). Branches `feat/evidence-dossier-NN`, each from the previous tip; T3 and T4 may split further if a slice exceeds the 400-line review budget.
- RDD: on; consent is a standing grant. Review per work-unit commit.

## Tasks

- [x] **T1 — ADR-011 and this document.** Written and accepted by the human on 2026-10-16, with the windows "last N minutes" vs "the N minutes just before". Branch `-01`. Route: delegated writer.
- [x] **T2 — Lab instrumentation:** build payment-service's gateway client from Spring's auto-configured `RestClient.Builder`, so the payment-service → gateway-mock edge gets `http_client_requests_seconds_count`. Tests first (a metric is recorded for a call to `/authorize`); update `openspec/specs/payment-service/spec.md`. Branch `-02`. Route: delegated writer.
- [ ] **T3 — MCP read tools or parameters for the dossier:** map each section to existing tools first, then add only what is missing.
  - Known gaps: window counts of events by type and outcome; exact per-edge counter deltas (raw samples, reset handling) with `uri`, `status`, `outcome`, `exception`.
  - Known gap: a bounded previous window `[now-2N, now-N)` for order states and event counts.
  - To check: log signatures beyond 100 lines; uptime from `service_restarts`.
  - Tests first; living spec `openspec/specs/mcp-tools/spec.md`. Branch `-03`. Route: delegated writer.
- [ ] **T4 — The dossier builder in `agent/`:** deterministic, facts only, same shape for every fault. Golden-file tests on recorded tool outputs (F1-F3 and a healthy window); a test that no interpretive vocabulary appears; a token-size test. Living spec `openspec/specs/agent/spec.md`. Branch `-04`. Route: delegated writer.
- [ ] **T5 — Wire the dossier into the loop behind a mode switch** (explorer vs dossier): adjust phases and budgets for dossier mode, record the mode on the incident and report. Tests first with the scripted fake chat model; spec; ADR-010 "Amended in T5" (or the next free label). Branch `-05`. Route: delegated writer.
- [ ] **T6 — Verify:** `verify-agent.sh` runs in dossier mode on F1-F3, and in explorer mode for comparison, the same number of runs each; results recorded here and in the agent-loop document (T5c3). Branch `-06`. Route: parent (runs), delegated writer (script changes, if any).

## Progress

- 2026-10-16: dry run of F3 in the live lab, queried by hand. Caller-side `ConnectException` series on order-service → payment-service `/payments` = 6; no client-side series for payment-service → gateway-mock (`HttpPaymentGateway.java:50`); payment-service `POST /payments` flat at 24 during the window; the recreate visible in `process_start_time_seconds`; `increase()` gives non-integers, so exact counts need raw deltas; `lab_orders` is all-time; 6 WARN lines in order-service with `ConnectException` only in the cause text; `PAYMENT_UNAVAILABLE` events with outcome `PAYMENT_PENDING`. Full list: ADR-011, "Feasibility".
- 2026-10-16: branch `feat/evidence-dossier-01` starts from `feat/agent-loop-04-check` at `7204423`. T1 written by a delegated writer: ADR-011 (Proposed) and this document, committed as `3971024`.
- 2026-10-16: the human accepted ADR-011, defining the windows as the last N minutes vs the N minutes just before (no fault start given to the agent). T1 done.
- 2026-10-16: T2 done on `feat/evidence-dossier-02`. Route: delegated writer. `HttpPaymentGateway` now takes the `ObservationRegistry` and observes its calls with a fixed `client.name=gateway-mock` and the I/O exception in `exception`, mirroring order-service's `ServiceRestClients` (order-service injects the registry, not a `RestClient.Builder`, so the same pattern is used here); timeouts, error mapping and URL config unchanged. RED: `HttpPaymentGatewayMetricsTest` did not compile (`constructor HttpPaymentGateway ... cannot be applied to given types; required: GatewayProperties`). GREEN: `HttpPaymentGatewayMetricsTest` 4/4 (success 200, 429, `ConnectException`, `HttpTimeoutException`), `HttpPaymentGatewayTest` 15/15; full `./mvnw test` in `lab/payment-service`: 90 tests, 0 failures, 0 errors, 0 skipped. Files: `HttpPaymentGateway.java`, `HttpPaymentGatewayTest.java` (construction only), new `HttpPaymentGatewayMetricsTest.java`, `openspec/specs/payment-service/spec.md` (requirement "Gateway client metrics"), `docs/runbooks/payment-service.md` (key signals). Not yet checked in the live lab.
- 2026-10-16: T2 committed as `d121d00`. Spot check: `HttpPaymentGatewayMetricsTest` 4 tests green. Assess from `7b8efd7` (committed only; the commits since the last review base `ae61560` are docs only): medium, `under_budget`, so no review yet; the range stays pending until a later commit reaches the budget.

## Next step

T3: MCP read tools or parameters for the dossier.
