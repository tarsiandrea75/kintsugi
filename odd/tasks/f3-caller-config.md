# F3: the caller's own configuration as a cause

## Objective

Let the agent diagnose F3 (`order-service|CONFIGURATION`: order-service calls payment-service on the wrong port) by fixing two defects found in the recorded F3 steps, without overfitting the agent to F3.

## Problem

The agent never diagnosed F3: 0/7 on the cloud route (`odd/tasks/cloud-route.md` T5), 0/3 per mode on the local route (`odd/tasks/evidence-dossier.md` T6b). An analysis of the 13 recorded F3 incidents (2026-10-19, read-only, on Fable 5.1) found:

1. **The prompt sends a caller's failure to the callee.** `agent/src/main/java/dev/kintsugi/agent/loop/Prompts.java:59-60`: "if A times out or fails because B does, name B". A `ConnectException` calling `payment-service:9999` becomes `payment-service|DEPENDENCY_REJECTING`, although `DEPENDENCY_REJECTING` is defined as "reachable but refuses" and a connect failure means the callee was never reached. In 9 of 13 runs the wrong URL was in front of the model; in run `20261018T212739Z-explorer-cloud` its own summary says "pointing to a wrong port" and it still names payment-service.
2. **A tool call with a missing required argument loses its reason.** mcp-tools' input validation answers in plain text; `McpLabTools.outcome` reads it as `CLIENT_ERROR` "the result is not JSON", so the model is not told which argument was missing. In two cloud explorer runs this hid every order-side line, the 9999 URL among them.

## Why

F3 is one of the three faults M2 needs (`docs/vision.md`). Both defects are general: the first misleads any caller-side configuration fault, the second wastes the budget of any incident on any route.

## Scope

- T2: a tool call that mcp-tools rejects before its handler runs is recorded as `INVALID_PARAMETER` with mcp-tools' own message as the reason, not `CLIENT_ERROR`.
- T3: the cause categories and the root-cause service rule in the system prompt: an error answer or a slow answer from B names B; a failure to connect to B while B shows no failure of its own points at the caller's setting for B (`CONFIGURATION` on A).
- T4: a cloud run, with the human's go-ahead and its cost recorded.

Out of scope (follow-ups of the same analysis, not authorized): a read-only `service_config` tool, deterministic dossier facts (the failing URL in the traffic table, the window split at a restart), runbook retrieval changes, `service_restarts` always on in explorer `TRIAGE`, the lab settling between faults.

## Constraints

- The LLM proposes, deterministic code verifies, policy authorizes. The prompt describes categories; it does not map a symptom to a fault.
- No change to `ReportCheck`, the report schema or the tools offered.
- A cloud result does not decide M2 (ADR-012): an accepted change is re-run on the local route, at the human's decision.

## Tasks

- [x] **T1 — This document.** Branch `feat/f3-caller-config` (from `feat/cloud-route-05`). Route: inline (one document).
- [ ] **T2 — mcp-tools rejections keep their reason** (item 4 of the analysis). When the MCP result is an error (`isError`) whose text is not mcp-tools' JSON envelope, record `INVALID_PARAMETER` with that text as the reason, in mcp-tools' rejection envelope, so the summarizer and the dossier show it like any rejection. A non-error result that is not JSON stays `CLIENT_ERROR`. Tests first in `McpLabToolsTest` (a missing required argument against `FakeMcpServer` or the real validator). Living spec: agent spec, "Tool calls run small and recorded". Route: delegated writer (2+ files).
- [ ] **T3 — The caller's setting as a cause** (item 1). Reword `DEPENDENCY_REJECTING`, `CONFIGURATION` and the root-cause service rule in `Prompts.java`. Tests first in `PromptsTest`. Living spec: agent spec, the report's cause categories. Route: delegated writer (2+ files).
- [ ] **T4 — A cloud run,** with the human's go-ahead: `verify-agent.sh --route cloud` in explorer mode, then dossier, recording F1-F3 and the cost. F1 and F2 must not regress. Route: parent (runs).

## Acceptance criteria

- T2: a call missing a required argument is recorded as `INVALID_PARAMETER` and its reason names the argument as mcp-tools wrote it; existing outcomes are unchanged; `./mvnw test` in `agent/` passes.
- T3: the prompt no longer tells the model to name the callee when the caller cannot connect to it; F1 (latency) and F2 (rejecting) wording still names the callee; `./mvnw test` passes.
- T4: F3 diagnosed in at least one run on the cloud route, F1 and F2 not worse than their T5 rates (explorer F1 2/3, F2 3/3).

## Checks

- `cd agent && ./mvnw test` (Spring Boot tests need Docker for Testcontainers).
- Per-commit native review assessment (`gentle-ai review assess --base-ref <last reviewed> --committed-only`).

## Delivery

Forecast about 200 authored lines (code, tests, spec, this document): under the 400-line budget, one slice. Strategy `feature-branch-chain` if it grows.

## Progress

- 2026-10-19: T1, this document, created after the human chose items 1 and 4 of the F3 analysis.

## Next step

T2.
