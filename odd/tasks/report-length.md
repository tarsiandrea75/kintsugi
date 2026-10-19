# Feature: report length on the cloud route

## Objective

Keep cloud-route reports from being cut at their cap, measuring two levers separately on the replay: a larger report cap on the cloud route only (C) and a prompt asking for at most 3 evidence items (B).

## Problem

On the cloud route reports run close to the 512-token cap: in `replay-20261019T221420Z-cloud` 4 of 15 used 485 tokens or more, and F1-dossier-5 was cut twice and ended `INVALID_REPORT` (analysis in `odd/tasks/dossier-latency.md`; the missing "cut" retry instruction is fixed in `fa29d44`). Every valid report there cited 3 evidence items; the cut one wrote a fourth.

## Why

A cut report is a lost diagnosis even when the cause is right. The 512 cap is justified by CPU write time on the local route (agent spec, "Answer caps"), which does not apply to the cloud route; and fewer, shorter evidence items cost nothing in accuracy if the replay shows rates unchanged.

## Design

- **C:** the cloud route gets its own answer caps (`kintsugi.agent.cloud.num-predict.*`), defaulting to today's values, so the plumbing changes nothing; the measurement overrides only the report cap through the environment (`KINTSUGI_AGENT_CLOUD_NUM_PREDICT_REPORT=768`, read by the replay test's Spring context). The local route keeps its caps.
- **B:** the report instruction asks for at most 3 evidence items; `ReportCheck`'s limit stays 5, so a fourth is not an error.
- Each lever is measured alone with `replay-agent.sh --samples 5` (dossier, 15 samples, about $0.09 each), against `replay-20261019T221420Z-cloud` (F1 4/5, F2 5/5, F3 5/5; 4 of 15 reports at 485+ tokens). Metrics: matches, `INVALID_REPORT`s, report completion tokens (median, max, share at 485+), cost. The human decides what to keep.

## Constraints

- No API key read, printed or committed; the human gave the go-ahead for both measurements (about $0.19).
- Tests first; living spec ("Answer caps", the report rules).

## Tasks

- [x] **T1 — This document** and its Engram mirror; route: inline.
- [x] **T2 — Cloud caps plumbing (C).** `CloudRouteProperties` gains `numPredict`, `LoopConfig` passes it to `AnthropicRoute`, `application.yaml` sets it equal to the local caps; tests first; spec "Answer caps". Route: delegated writer.
- [x] **T3 — Measure C:** the replay with the cloud report cap at 768. Route: parent (runs).
- [x] **T4 — At most 3 evidence items (B):** `Prompts` report instruction; tests first in `PromptsTest`; spec. Route: inline or delegated by size.
- [ ] **T5 — Measure B:** the replay with the default cap. Then the human decides what to keep. Route: parent (runs).

## Acceptance criteria

- T2: with no override, the cloud route's caps equal today's; an override changes only the cloud route; `./mvnw test` passes.
- T3, T5: rates, invalid reports, report tokens and cost recorded per lever.

## Checks

- `cd agent && ./mvnw test`; per-commit native review assessment.

## Delivery

Forecast about 120 authored lines, one slice. Branch `feat/report-length`, from `fix/cloud-cut-report` (`fa29d44`).

## Progress

- 2026-10-19: T1.
- 2026-10-19: T2, route delegated writer (the change spans properties, config, yaml, tests and spec). `CloudRouteProperties` gains a required `numPredict` (fails fast when missing, like `call-timeout`; no fallback to the local caps), bound from `kintsugi.agent.cloud.num-predict` (384, 160, 512); `LoopConfig` passes it to `AnthropicRoute`. Tests first: `CloudRouteCapsTest` RED (`report=512`, expected 768), then GREEN with `CloudRoutePropertiesTest` (the env var `KINTSUGI_AGENT_CLOUD_NUM_PREDICT_REPORT` binds through a `systemEnvironment` source; a missing cap is rejected). `./mvnw test`: 419 tests, 0 failures, 0 errors, 0 skipped; `test-compile -Preplay` passes. Spec: "Answer caps" and "Routes".

- 2026-10-19: T2 done in `c1fcb9b`; review (standing consent granted) approved and acknowledged. Its WARNING (the yaml defaults were not asserted) fixed with `CloudRouteDefaultCapsTest`: no override gives 384, 160, 512 (a regression guard: green from the start, as the yaml already held them).

- 2026-10-19: T3, with the human's go-ahead: `KINTSUGI_AGENT_CLOUD_NUM_PREDICT_REPORT=768 replay-agent.sh --samples 5` on `49f32d7` (`infra/scripts/out/replay-20261019T225038Z-cloud`). The override took effect (a report used 586 tokens). **F1 5/5, F2 5/5, F3 4/5** (one `payment-service|DEPENDENCY_REJECTING`); 0 invalid runs, 0 cuts; report tokens median 393, max 586, 2 of 15 at 485+; evidence items 2:4, 3:9, 4:2. Baseline (`221420Z`, cap 512): F1 4/5, F2 5/5, F3 5/5; 1 invalid run; median 447.5, max 512, 6 of 16 at 485+. Cost **$0.0905**.
- 2026-10-19: T4 (route: inline, one prompt line, its test and the spec row): the report task asks for 1 to 3 evidence items; `ReportCheck` still takes 5. RED: `theReportAsksForAtMostThreeEvidenceItems` failed (20 tests, 1 failure); GREEN: full suite 422/0/0.

## Next step

T5: the replay with the default cap (512).
