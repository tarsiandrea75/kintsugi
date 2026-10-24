# Feature: a trustworthy, fast instrument for the diagnosis

## Objective

Measure the diagnosis on many clean, distinct incidents in hours instead of days. The feature has three parts:
- a decision mode: the dossier, then one report call;
- a recording protocol whose previous window is a healthy baseline;
- a replay over many recordings, split into `dev`, `holdout`, `stress` and `probe`.

Then measure today's prompt on it (M0) before anything else changes.

## Problem

Evidence from the 346 scored runs in `infra/scripts/out/` (analysis of 2026-10-24).

### One input per fault

- Every replay since 2026-10-19 22:14 replays the same recording per fault: 16 output directories, local and cloud.
- That recording is `verify-agent-20261019T214629Z`. The replay reaches it through `recordings.tsv` and the primary recording of `ReplayDriver.java:123-135`.
- The local route runs at temperature 0 (`application.yaml:19-21`), so its 3 samples per fault are near-copies of one observation.

### The baselines are other faults

- `space_out` waits 11 minutes after the previous restore, with no traffic in between (`lib.sh:648-659`).
- The window is the fault's own traffic time plus a 10-minute margin (`lib.sh:577,639-646`). The dossier counts it back from gathering time (`DossierGatherer.java:91-94`).
- So each fault's previous window holds the previous fault:
  - F2's previous window holds F1's timeouts and 9.5 s gateway latency.
  - F3's previous window holds F2's six 429s.
  - F1's previous window is empty, because all four services restarted during `start_lab`, 2 minutes before gathering.
- On F3 the local route never named order-service: 0 of 13 replays. In 6 of the 13 it answered F2's truth, `gateway-mock|DEPENDENCY_REJECTING`, citing `D23 | 429 | 6 | 0 | -6`.

### The loop is slow

A local replay sample takes 1,500 to 1,900 s:

| Phase | Share of the time |
|---|---|
| INVESTIGATE | 46% |
| KNOWLEDGE | 29% |
| REPORT | 25% |

- In replay the drill-down calls fail as "not recorded in this replay": every one of them in the local runs inspected, and 1 to 5 per sample on the cloud route.
- No setting goes straight to REPORT (`AgentMode.java:21`, `AgentLoop.java:194-213`).

## Why

Every keep-or-revert decision since 2026-10-19 rested on three inputs whose baselines were wrong. The numbers measured the protocol and the format, not the model. A fast, clean instrument comes before any change to what the model reads. That change is Part B, a later feature (`odd/tasks/service-assessment.md`).

## Scope

In:
- the decision mode in the agent;
- the replay over many recordings, with splits;
- the baseline protocol and the window margin in `verify-agent.sh` and `lib.sh`;
- scripts to check and import recordings;
- the spec, `docs/setup.md`, and the truth table of ADR-011 (lines 17-18 swap F1 and F2 against `Fault.java`).

Out:
- what the model reads: the prompt, the dossier rendering and the report schema;
- the behavior of the explorer and dossier modes;
- Part B.

## Design

### Decision mode

`AgentMode.DECISION` has no evidence phases. A run is:
1. the dossier, gathered first, before the local warm-up;
2. the warm-up;
3. one REPORT call, with the existing single retry;
4. the report.

The REPORT prompt, the schema and the checks are the same as in dossier mode, and the dossier is the only citable output. The mode is selected by `KINTSUGI_AGENT_MODE=decision`, `verify-agent.sh --mode decision` and `replay-agent.sh --mode decision`.

### Windows

The rule stays: the incident window is `[at−N, at]`, the previous window `[at−2N, at−N]`. In decision mode `at` is the incident's opening, because gathering comes first. So verify-agent's margin in decision mode is 2 minutes instead of 10.

### Baseline phase

The baseline phase replaces the bare wait of `space_out`. Before a fault's traffic, it waits until the previous restore (or `start_lab`) is older than 2N plus a minute. During the last N+1 minutes it places a healthy order about every 60 s.

The goal:
- the previous window holds at least 3 PAID orders and no fault;
- the incident window holds no other fault's traffic or restarts.

### Rounds and faults

`verify-agent.sh --rounds K` runs the fault list K times. `--faults` also accepts F4, as an untuned probe, if the generic traffic shows that fault.

### Recordings

Recordings are made with the cloud route in decision mode: one REPORT call, about 30 s per incident. Two scripts handle them:
- `infra/scripts/check-recordings.sh <run dir>` asserts the window properties of every `F<n>-incident.json`;
- `infra/scripts/add-recordings.sh <run dir> <split>` copies the incident files into `agent/src/test/resources/replay/F<n>/` and appends their manifest rows.

### Manifest

`recordings.tsv` gains a `split` column: `dev`, `holdout`, `stress` or `probe`. The six current rows become `stress`, kept for a later "back-to-back incidents" case.

### Replay

In decision mode the replay builds one case per recording of the chosen split. It pools no recordings, because all of the dossier's calls are in each recording.
- `--repeats M` (default 1) reruns each recording.
- `summary.tsv` names the recording.
- `aggregate.tsv` counts matches over distinct recordings.

Explorer and dossier replays keep today's behavior.

## Acceptance criteria

1. In decision mode a run makes no INVESTIGATE or KNOWLEDGE call. It gathers the dossier before the warm-up, then makes the REPORT call (at most one retry), then stores the report. The explorer and dossier modes behave as before, and their tests stay unchanged and green.
2. `replay-agent.sh --mode decision --split <name>` replays each recording of the split once, or M times with `--repeats`. It makes no unrecorded call, and the summary names each recording.
3. A recording round made with `verify-agent.sh --mode decision --route cloud` passes `check-recordings.sh`:
   - every previous window holds at least 3 PAID orders, and no failed order state or client error row;
   - every incident window shows no restart except the one the fault itself makes (F3 recreates order-service).
4. The manifest holds at least 5 `dev` and 5 `holdout` recordings per fault for F1-F3, plus the `stress` rows and any `probe` rows.
5. This document records M0: today's REPORT prompt in decision mode on `dev`, on the local route (`granite4.1:8b`) and the cloud route, as matches per fault over distinct recordings.
6. Living docs are updated: the spec (decision mode, replay splits), `docs/setup.md` (the protocol) and the truth table of ADR-011.

## Tasks

- [x] T1 Decision mode in the agent, replayable on today's recordings, with tests and the spec. Route: delegated writer (AgentMode, AgentLoop, the replay test side, the spec).
- [x] T1r The four review suggestions on T1. Route: delegated writer (loop, its tests, a constraint test).
- [ ] T2 Baseline protocol and recording check. Route: delegated writer (scripts and docs). It covers:
  - in `verify-agent.sh` and `lib.sh`: `--mode decision`, the margin, the baseline phase, `--rounds` and F4;
  - `check-recordings.sh`;
  - the docs.
- [ ] T3 Replay over many recordings. Route: delegated writer. It covers:
  - the manifest splits;
  - one case per recording;
  - `--split` and `--repeats`;
  - `add-recordings.sh`;
  - the summary and aggregate.
- [ ] T4 Recording session: K rounds on the cloud route in decision mode, checked, then imported as `dev`, `holdout` and `probe`. Route: inline. Lab time is about 6-8 h unattended; cost is about $0.10.
- [ ] T5 M0: replay decision mode on `dev`, local and cloud. Route: inline; about 2 h local and $0.05 cloud. qwen3:8b only if it is installed and its thinking can be turned off without code.

## Delivery

The forecast is about 800 authored changed lines (T1 ~250, T2 ~250, T3 ~300), over the 400-line budget. Strategy: `feature-branch-chain` on one branch, `feat/decision-instrument`, with one work-unit commit per code task.

## Progress

- 2026-10-24: branch `feat/decision-instrument`, from `feat/loopback-api-guard` (`409cf4b`, closed). This is the first feature of the plan agreed on 2026-10-24: fix the instrument, then shrink the model's job.
  - Part B is a per-service assessment checked against the call graph, with ADR-014.
  - Its gates and the M2 bar will be recorded in that feature's document.

- 2026-10-24: T1 done (route: delegated writer; trigger: about 15 files across the loop, its tests, a migration and the spec). Commit `3830488`.
  - What changed:
    - `AgentMode.DECISION` has no phases, with the predicates `gathersDossier()` and `gathersBeforeWarmUp()`. T1r later replaced the second predicate with a single value.
    - Only decision mode gathers the dossier before the warm-up.
    - `ReportCheck` and `Schemas` key on `gathersDossier()`.
    - Migration V7 widens `incidents_mode_check`; V4's check allowed only explorer and dossier, so every decision run would have ended `FAILED`.
    - In replay, a decision sample uses the fault's decision recording, else its first dossier recording.
    - `replay-agent.sh` accepts `--mode decision`.
    - Spec: a new "Decision mode" requirement with 6 scenarios, and nine requirements updated.
  - Choice 2B, the truthful header. The REPORT header shows the run's real phases and budget ("the phases run in order: CONCLUDE", "0 in the incident"). No prompt wording changed, and the system and dossier messages stay byte-identical.
    - Why not a copy of dossier mode's header: in replay, dossier-mode REPORT calls always carried the failed drill-down evidence, so that identity was never real.
    - M0 compares decision runs with each other.
    - A header claiming phases ran and 6 calls are left would be false input to the model.
  - RED, in two steps:
    - with the tests written first, the build did not compile: 18 "cannot find symbol" errors;
    - with only the bare enum constant, 11 of 98 tests failed.
  - GREEN: 98 focused tests pass, and the full suite passes with 474 tests and 0 failures in 47 s. Parent spot check: the full suite passes again.
  - About 630 authored lines, over the 400 heuristic because of the tests:
    - about 125 lines of production code;
    - about 405 lines of tests;
    - about 100 lines of spec and docs.
- T1 review (`3830488`, base `b1e9747`, four lenses, consent granted): approved and acknowledged (lineage `review-968c3c44ee675201`). It made four suggestions, applied in T1r:
  - R2-dossier-placement-branches: two booleans encode a three-way order.
  - R2-accumulated-prompt-index: a test reads the prompt at a fixed index.
  - R3-decision-prewarmup-gather-failure-untested.
  - R3-v7-constraint-name-coupling: no test checks the final constraint.

- 2026-10-24: T1r done (route: delegated writer; trigger: 5 files across the loop, its tests and the spec).
  - **A real bug.** In decision mode, the gathering's time check counted from the incident's opening, although the budget only starts when the warm-up ends. So a slow gathering, or a late run, ended `TIMED_OUT` before the budget had started.
    - Fix: `Run.budgetStart` stays null until the warm-up ends, and `elapsed()` is zero before then. Before the warm-up, only the `ACT` deadline bounds the gathering.
    - RED: `theGatheringDoesNotCountAgainstTheIncidentsTimeBudget` and `theTimeBudgetStartsWhenTheWarmUpEndsNotWhenTheIncidentOpened` both ended `TIMED_OUT`. GREEN after the fix.
  - `AgentMode.DossierGathering` takes values `NONE`, `BEFORE_WARM_UP` or `AFTER_WARM_UP`. It replaces `gathersBeforeWarmUp()`, so `investigate()` reads as one sequence. `gathersDossier()` stays, derived from it.
  - New tests:
    - a gathering that throws, and one that passes its deadline, before the warm-up; both are characterization tests and passed at once;
    - the decision report call is read with `getLast()` and an asserted prompt count;
    - `AgentStoreTest` checks that every mode is accepted, and that `triage`, `DECISION` and an empty string are rejected by `incidents_mode_check`.
  - Spec: two "Decision mode" scenarios, the budget sentence, and the mode check in "State tables".
  - Full suite: 484 tests, 0 failures. Parent spot check: green.

## Next step

Review T1r, then T2.
