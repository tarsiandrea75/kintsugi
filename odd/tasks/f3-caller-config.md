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

Out of scope (follow-ups of the same analysis, not authorized): a read-only `service_config` tool, deterministic dossier facts (the failing URL in the traffic table, the window split at a restart), runbook retrieval changes, `service_restarts` always on in explorer `TRIAGE`, the lab settling between faults. From the F1 analysis of T5: F1 windows contain order-service port-9999 lines left over from the previous run's F3; `docs/runbooks/order-service.md:77` ("payment-service slow") is cited to name payment-service; the dossier's traffic table has no latency column.

## Constraints

- The LLM proposes, deterministic code verifies, policy authorizes. The prompt describes categories; it does not map a symptom to a fault.
- No change to `ReportCheck`, the report schema or the tools offered.
- A cloud result does not decide M2 (ADR-012): an accepted change is re-run on the local route, at the human's decision.

## Tasks

- [x] **T1 — This document.** Branch `feat/f3-caller-config` (from `feat/cloud-route-05`). Route: inline (one document).
- [x] **T2 — mcp-tools rejections keep their reason** (item 4 of the analysis). When the MCP result is an error (`isError`) whose text is not mcp-tools' JSON envelope, record `INVALID_PARAMETER` with that text as the reason, in mcp-tools' rejection envelope, so the summarizer and the dossier show it like any rejection. A non-error result that is not JSON stays `CLIENT_ERROR`. Tests first in `McpLabToolsTest` (a missing required argument against `FakeMcpServer` or the real validator). Living spec: agent spec, "Tool calls run small and recorded". Route: delegated writer (2+ files). Done in `4388912`.
- [x] **T3 — The caller's setting as a cause** (item 1). Reword `DEPENDENCY_REJECTING`, `CONFIGURATION` and the root-cause service rule in `Prompts.java`. Tests first in `PromptsTest`. Living spec: agent spec, the report's cause categories. Route: delegated writer (2+ files).
- [x] **T4 — A cloud run,** with the human's go-ahead: `verify-agent.sh --route cloud` in explorer mode, then dossier, recording F1-F3 and the cost. F1 and F2 must not regress. Route: parent (runs).
- [x] **T5 — Follow the chain to the slow component** (fixes T3's F1 regression). The root-cause service rule in `Prompts.SYSTEM` follows the calls down to the last slow or refusing component, counts a slow answer that ends in success as that component's failure, and excludes a timeout from "cannot reach"; tests first in `PromptsTest`, living spec in the agent spec. Route: delegated writer (2+ files: prompt, test, spec, this document).

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
- 2026-10-19: T2 implemented (route: delegated writer, 2+ files). Evidence: mcp-tools runs MCP Java SDK 2.0.0, whose stateless server validates arguments against the tool's input schema before the handler (`ToolInputValidator`, on by default, Spring AI 2.0.1 does not turn it off); run against the recorded `search_logs` schema it answers `isError` true with the plain text `Tool (search_logs) input validation failed: Validation failed: JSON schema validation errors: [: required property 'service' not found, : required property 'sinceMinutes' not found]`. `McpLabTools.call` now records an error result whose text is not JSON as `INVALID_PARAMETER`, in mcp-tools' rejection envelope with that text (whitespace normalized) as `reason`; the summarizer and the dossier already read that `reason`. `FakeMcpServer.answer(tool, text, isError)` sets `isError` explicitly. RED: `McpLabToolsTest` 27 tests, 1 failure (`aPlainTextErrorIsARejectionWithItsReason`, expected `INVALID_PARAMETER`); GREEN: 27 tests, 0 failures. Full suite (`./mvnw test` in `agent/`): 378 tests, 0 failures, 0 errors, BUILD SUCCESS. Living spec: agent spec, "Tool calls run small and recorded", rule and scenario "Missing required argument".
- 2026-10-19: T2 review: native assessment medium, `under_budget`: pending in the slice.
- 2026-10-19: T3 implemented (route: delegated writer, 2+ files: prompt, test, spec, this document). `Prompts.SYSTEM` now defines `DEPENDENCY_REJECTING` as a component that is reached and answers with an error, gives a wrong address or port of a called component as a `CONFIGURATION` example, and says: name B when A waits for B or gets an error answer from B; when A cannot reach B at all (connection refused or closed, unknown host) while B shows no failure of its own, B is not the cause, suspect A's own setting for B, a `CONFIGURATION` of A. RED: `PromptsTest` 17 tests, 2 failures (`aDependencyCauseNamesTheDependencyNotItsCaller`, `aCallerThatCannotReachAHealthyDependencyPointsAtTheCallersSetting`); GREEN: 17 tests, 0 failures. Full suite (`./mvnw test` in `agent/`): 379 tests, 0 failures, 0 errors, BUILD SUCCESS. Living spec: agent spec, "What one call sees", rule, why and scenario "A caller that cannot reach a healthy dependency points at its own setting".
- 2026-10-19: T3 done in `51136ad`. Native assessment of `5b56ade..51136ad`: medium, `under_budget` (no review due); the slice stays pending until a later commit reaches the budget.

- 2026-10-19: T4, with the human's go-ahead: `compare-agent-modes.sh --route cloud --runs 1` (`infra/scripts/out/verify-agent-20261019T010327Z-explorer-cloud`, `-013033Z-dossier-cloud`), on `51136ad`.
  - **Explorer 2/3:** F1 MISS (`payment-service|DEPENDENCY_LATENCY`: the right category, the wrong service), F2 MATCH, **F3 MATCH** (`order-service|CONFIGURATION`, the first F3 match on any route).
  - **Dossier 1/3:** F1 MISS (`payment-service|DEPENDENCY_LATENCY` again), F2 MATCH, F3 MISS as `payment-service|CONFIGURATION`: the right category now, the wrong component.
  - Acceptance: F3 diagnosed at least once, met. "F1 and F2 not worse than T5": F2 1/1 in both modes; F1 0/2, against explorer 2/3 and dossier 1/3 in T5. The same wrong-service miss already happened in T5 (3 of 6), so one sample per mode cannot tell a regression from noise; open.
  - Tokens: 271,273 prompt and 14,029 completion, **$0.0341**. Total cloud spend about $0.15 of the human's $5.

- 2026-10-19: T4b, with the human's go-ahead: `compare-agent-modes.sh --route cloud --runs 2` (`infra/scripts/out/verify-agent-20261019T015826Z-explorer-cloud`, `-022538Z-dossier-cloud`, `-025252Z-explorer-cloud`, `-032023Z-dossier-cloud`), on `51136ad`.
  - Explorer: F1 0/2, F2 2/2, F3 1/2 (one MATCH, one `UNKNOWN|UNKNOWN`). Dossier: F1 0/2, F2 2/2, F3 1/2 (one MATCH, one `payment-service|CONFIGURATION`).
  - **With T4, three rounds after T3:** explorer 5/9 (F1 0/3, F2 3/3, F3 2/3), dossier 4/9 (F1 0/3, F2 3/3, F3 1/3). Before T3 (cloud-route T5, three rounds): explorer 5/9 (F1 2/3, F2 3/3, F3 0/3), dossier 3/9 (F1 1/3, F2 2/3, F3 0/3).
  - **F3 went from 0/6 to 3/6; F1 went from 3/6 to 0/6.** Every F1 miss names `payment-service|DEPENDENCY_LATENCY`, the caller of the slow gateway-mock: the right category, the wrong component. F1 is now a likely regression of the T3 wording, not noise; acceptance "F1 not worse than T5" fails. The cause is not analysed yet.
  - Tokens: 516,752 prompt and 27,256 completion, **$0.0653**. Total cloud spend about $0.22 of the human's $5.

- 2026-10-19: T5 implemented (route: delegated writer, 2+ files: prompt, test, spec, this document). An analysis of the post-T3 F1 steps found that the model applies "name B when A waits for B" one hop only and stops at payment-service; that gateway-mock's slow answers, which end in HTTP 200 with INFO logs only, look healthy; and that order-service's `HttpTimeoutException` must not be read as "cannot reach". `Prompts.SYSTEM` now names the component where the slowness or the error answer starts, follows the calls to C when B in turn waits for C or gets an error answer from C, counts a long answer as that component's failure even when it ends in success and logs no error, and says a timeout is not the "cannot reach" case. RED: `PromptsTest` 19 tests, 2 failures (`aDependencyCauseFollowsTheChainToTheLastSlowOrRefusingComponent`, `aTimeoutIsNotACallerThatCannotReachItsDependency`); GREEN: 19 tests, 0 failures. Full suite (`./mvnw test` in `agent/`): 381 tests, 0 failures, 0 errors, BUILD SUCCESS. Living spec: agent spec, "What one call sees", rule, why and scenario "A slow dependency behind a dependency is the root cause (F3 T5)".

- 2026-10-19: T5 done in `c3c573d` (native assessment `5b56ade..c3c573d`: medium, `under_budget`). Two cloud rounds with the human's go-ahead (`infra/scripts/out/verify-agent-20261019T114057Z-explorer-cloud`, `-120802Z-dossier-cloud`, `-123439Z-explorer-cloud`, `-130127Z-dossier-cloud`):
  - **Explorer 5/6:** F1 2/2, F2 2/2, F3 1/2 (one `payment-service|CONFIGURATION`). Round 1 explorer was the first 3/3 on the cloud route.
  - **Dossier 4/6:** F1 0/2 (`gateway-mock|DEPENDENCY_REJECTING`: the right component, the wrong category; and `order-service|CONFIGURATION`, likely drawn by the port-9999 lines the previous run's F3 leaves in F1's window), F2 2/2, F3 2/2.
  - Against cloud-route T5 (before T3, rates over three rounds): F1 explorer 2/3 → 2/2, dossier 1/3 → 0/2; F2 5/6 → 4/4; F3 0/6 → 3/4. Acceptance: F3 diagnosed, met; F1 and F2 not worse, met in explorer mode; dossier F1 stays open, with a lab cause (the leftover F3 lines) to rule out first.
  - Tokens: 511,127 prompt and 26,764 completion, **$0.0645**. Total cloud spend about $0.28 of the human's $5.

## Next step

Review the slice `5b56ade..c3c573d` (T2, T3, T5) before closing the feature. Then the human decides: the dossier's F1 (first the leftover F3 lines in the next fault's window, a lab/script follow-up), or close the feature and confirm on the local route (M2's check).
