# Feature: latency in the dossier

## Objective

The dossier shows how long each service takes to answer, so the root-cause rule "follow the calls down to the last component that is slow" has a fact to follow, and dossier F1 stops naming the caller of the slow component.

## Problem

In dossier mode F1 (gateway-mock answers in about 10 s, with HTTP 200) is diagnosed as `payment-service|DEPENDENCY_LATENCY` in 5 of 5 replay samples (`odd/tasks/replay-harness.md` T5) and 0 of 3 recent lab rounds. A read-only analysis of the five samples found that the dossier holds no latency at all: gateway-mock's edge shows only `200 SUCCESS`, its `duration_ms=10000` line is INFO (the dossier reads WARN and ERROR only, `DossierGatherer.java:52-53`), and the only timing beyond the caller is payment-service's own 10 s `PAYMENT_ATTEMPT` event, so payment-service looks like the last slow component. The model's report says so: "gateway-mock answered all authorize calls with 200, so the slowness starts at payment-service". The one dossier F1 match ever recorded came from a model that chose to read gateway-mock's logs at every level.

## Why

The prompt rule (`Prompts.java`, F3 T5) needs per-hop latency; the gap is in the evidence, not the wording. A deterministic fact is the project's way ("the LLM proposes, deterministic code verifies") and holds for every edge and fault, unlike a sentence in the prompt.

## Design

- The dossier gathers `query_metric latency_avg` (server side, by service and uri, seconds; `mcp-tools` `MetricCatalog.LATENCY_AVG`) for the incident window and the previous window, through the same `LabTools.call` path as its other calls, and renders a "Latency" section: one row per service and uri with the average answer time in each window (and, if cheap, `latency_max`). Facts only, no interpretation (agent spec, dossier "facts only").
- Exact arguments, rendering format and where the section sits are the writer's to propose after reading `DossierGatherer`, `Dossier`, `DossierRenderer` and the `query_metric` tool contract; any change to mcp-tools is out of scope unless the existing tool cannot answer.

## Constraints

- No API key read, printed or committed; each cloud batch needs the human's go-ahead and a recorded cost (granted for this feature: one lab dossier round and one replay of 5 samples per fault).
- Tests first; living spec in the agent spec (dossier requirement and its call-count scenarios).

## Tasks

- [x] **T1 — This document** and its Engram mirror; route: inline.
- [x] **T2 — Latency in the dossier.** Gather and render it; tests first (`DossierGatherer`/renderer tests, recorded-lab fixtures); spec updated. Route: delegated writer (2+ files).
- [x] **T3 — Record a fresh dossier round** on the cloud route (`verify-agent.sh --mode dossier --route cloud`, F1-F3, about 27 minutes and $0.02) and replace the old dossier recordings in `agent/src/test/resources/replay/` with it (the old ones lack the latency calls, and the driver serves the first same-mode recording). Route: parent (runs).
- [x] **T4 — Measure on the replay:** `replay-agent.sh --samples 5` (dossier), about $0.08; compare with F1 0/5, F2 5/5, F3 4/5. Route: parent (runs).

## Acceptance criteria

- T2: the rendered dossier has a latency row for every service and uri the metric returns, in both windows; `./mvnw test` passes.
- T3: the new recordings' dossier steps hold the latency calls; `dossierCovered` stays full in replay.
- T4: F1 better than 0/5; F2 and F3 not worse than 5/5 and 4/5 beyond one sample.

## Checks

- `cd agent && ./mvnw test`; `bash infra/scripts/test-lib.sh` if scripts change.
- Per-commit native review assessment.

## Delivery

Forecast about 250 authored lines, one slice. Branch `feat/dossier-latency`, from `feat/replay-harness` (`df273c9`). Strategy `feature-branch-chain` if it grows.

## Progress

- 2026-10-19: T1, after the read-only analysis of the five replay samples.
- 2026-10-19: T2, route delegated writer (2+ non-trivial files: gatherer, renderer, model, tests, spec). Arguments: `query_metric` `{"metric":"latency_avg","service":<s>,"sinceMinutes":2N}`, one call per service (4 calls, 29 → 33 dossier calls). Why: the tool takes no `untilMinutesAgo`, so one 2N call per service covers both windows and its points are split at the incident window's start; an all-services call would exceed the tool's 10-series / 8 KB cap (about 1.8 KB per 60-point series, 2-3 series per service), cutting series. `latency_max` left out: it needs 4 more calls. Rendered as "## Latency (server side)" after Traffic: per service and uri, previous/incident mean and max (seconds, 3 decimals) and points with a value / all; at most 3 rows per service. RED observed (8 gatherer and 4 renderer failures with a stub), then GREEN (`Dossier*Test` 30/30). Full suite `./mvnw test`: 414 run, 0 failures, 0 errors, 0 skipped. Stress ceiling raised 4,500 → 5,500 tokens (the table adds about 2.4k characters at its caps). `ReplayDriverTest` now expects 33 dossier calls, 4 unrecorded (coverage 0.88) on the old recording until T3. Fixtures: new `dossier/f1` set (latency only, falls back to `healthy`), latency fixtures added to `f3` and `healthy` (synthetic, real tool shape, one point per minute); no real all-window latency_avg recording existed.

- 2026-10-19: T2 done in `673aa4a` (also the stale "29 calls" comment in `AgentLoop`). Native assessment high; review (1 lens, standing consent granted) approved and acknowledged. WARNINGs: the stress ceiling comment claimed "half of num-ctx" after the raise to 5,500 tokens (two thirds): the comment now says so and names the known edge; the integer-timestamp check on latency points is kept, since mcp-tools writes each point's time as an integer (`ObservabilityTools.java:229`, `asLong`), so a fractional time would be a contract break worth failing on. SUGGESTION left: `ReplayDriverTest`'s partial coverage until T3.

- 2026-10-19: T3, with the human's go-ahead: `verify-agent.sh --mode dossier --route cloud` on `f20fc74` (`infra/scripts/out/verify-agent-20261019T214629Z`), exit 0: **F1 MATCH, F2 MATCH, F3 MATCH**, the first dossier 3/3 on any route (one sample). Recorded with `record-replay.sh`; the three `173217Z` dossier recordings and their manifest lines removed (they lack the latency calls). `ReplayDriverTest` now replays the new F1 recording with full coverage (33 calls, 0 unrecorded): 8/8.

- 2026-10-19: T4, with the human's go-ahead: `replay-agent.sh --samples 5` (dossier) on `671b84c` (`infra/scripts/out/replay-20261019T221420Z-cloud`), 15 samples, exit 0; every dossier step fully recorded (`dossierCovered` 5/5 per fault).
  - **F1 4/5** (was 0/5): four `gateway-mock|DEPENDENCY_LATENCY`; the fifth is `INVALID_REPORT` ("report invalid twice"), not a wrong cause. **F2 5/5** (was 5/5). **F3 5/5** (was 4/5). Acceptance met.
  - The baseline (0/5, 5/5, 4/5) replayed the earlier recordings, the new run replays a new one, so evidence and latency both changed; the lab round on the same code (T3, 3/3) agrees.
  - Tokens: 777,136 prompt and 29,871 completion, **$0.0926**; T3's lab round about $0.03. Total cloud spend about $0.55 of the human's $5.
- 2026-10-19: feature closed.

- 2026-10-19: the F1 `INVALID_REPORT` analysed (read-only): both report answers hit the 512-token cap mid-quote, and the loop recognised a cut only by Ollama's `length`; Spring AI passes Anthropic's stop reason as it is (`max_tokens`, `AnthropicChatModel.java:992` in 2.0.1's sources), so the cloud retry got no "cut" instruction. Fixed on `fix/cloud-cut-report` (route: inline, one class, its test double and test, spec scenario): `max_tokens` is a cut too. RED: the new `aReportCutByAnthropicsMaxTokensIsRetriedWithTheReason` failed; GREEN: full suite 0 failures, 0 errors. Not measured on the replay (a cut happened in 1 of 30 samples). Open: reports run close to the cap on the cloud route (4 of 15 at 485+ tokens); a cap of 3 evidence items or a larger cloud cap are follow-ups.

## Next step

None in this feature. Open for the human: explorer mode's F1, which reads no dossier; the dossier's longer latency section on windows over 180 minutes (rejected by the tool's 360-minute limit).
