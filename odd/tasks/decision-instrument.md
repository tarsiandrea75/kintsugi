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

The baseline phase replaces the bare wait of `space_out`, in decision mode only. Before each fault's traffic, including the first after `start_lab`, it places a healthy order every 50 s for B = 2·N_max + 1 minutes.
- N_max is the largest window the fault flow can get, derived from the script's constants: ceil((9 orders × (15 s max + 2 s gap) + 180 s injector cap + 30 s settle) / 60) + 2-minute margin = 9 minutes. So B is 19 minutes.
- The cadence is 50 s, not 60 s, because the shortest previous window is 3 minutes and 60 s spacing can put only 2 orders in it.
- An incident whose window is larger than N_max fails that fault, with a FAIL line and a MISS, because the baseline guarantee no longer holds.
- A baseline order that is not PAID restarts the baseline (T2 adjustment, see Progress).

The goal:
- the previous window holds at least 3 PAID orders and no fault;
- the incident window holds no other fault's traffic or restarts.

### Rounds and faults

`verify-agent.sh --rounds K` (1 to 20) runs the fault list K times. With `--rounds`, files carry the round (`F<n>-r<k>-incident.json`), and `summary.tsv` has a `round` column in every mode. Faults are chosen with `--only`.

F4 is left out (follow-up). Its pool exhaustion shows only under concurrent orders: `verify-f4.sh` places 6 at once. One order at a time shows only slow reservations and order-service 503s, with no exhausted pool, so it would teach the wrong symptom.

### Recordings

Recordings are made with the cloud route in decision mode: one REPORT call, about 30 s per incident. Two scripts handle them:
- `infra/scripts/check-recordings.sh <run dir>` asserts the window properties of every `F<n>-incident.json`;
- the existing `infra/scripts/record-replay.sh` imports run directories into the manifest. It is idempotent and refuses key-like strings. It gains `--split <name>`, and it learns the round-numbered files `F<n>-r<k>-incident.json`.

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
   - every previous window holds at least 3 PAID orders, no order in any other state, and no traffic row other than SUCCESS (either side);
   - every incident window shows no restart except the one the fault itself makes (F3 recreates order-service).
4. The manifest holds at least 5 `dev` and 5 `holdout` recordings per fault for F1-F3, plus the `stress` rows and any `probe` rows.
5. This document records M0: today's REPORT prompt in decision mode on `dev`, on the local route (`granite4.1:8b`) and the cloud route, as matches per fault over distinct recordings.
6. Living docs are updated: the spec (decision mode, replay splits), `docs/setup.md` (the protocol) and the truth table of ADR-011.

## Tasks

- [x] T1 Decision mode in the agent, replayable on today's recordings, with tests and the spec. Route: delegated writer (AgentMode, AgentLoop, the replay test side, the spec).
- [x] T1r The four review suggestions on T1. Route: delegated writer (loop, its tests, a constraint test).
- [x] T2 Baseline protocol and recording check. Route: delegated writer (scripts and docs). It covers:
  - in `verify-agent.sh` and `lib.sh`: `--mode decision`, the margin, the baseline phase and `--rounds` (F4 left out, see Design);
  - `check-recordings.sh`;
  - the docs.
- [x] T2r The slice review's findings (the label and the flaky test; the `diagnose()` tests are T2r2, after T4). Route: inline, for the label and the flaky test. The `diagnose()` tests wait for T4 to end, because they touch `verify-agent.sh`, which T4 is running.
- [ ] T2r2 After T4: tests for decision mode's two `diagnose()` branches, the ACT deadline headroom of the gathering test (R3-gathering-act-headroom) and the four T3r suggestions. Route: delegated writer (scripts, their tests, one loop test).
- [x] T3 Replay over many recordings. Route: delegated writer. It covers:
  - the manifest splits;
  - one case per recording;
  - `--split` and `--repeats`;
  - `record-replay.sh --split` and round-numbered files;
  - the summary and aggregate.
- [ ] T4 Recording session: K rounds on the cloud route in decision mode, checked, then imported as `dev`, `holdout` and `probe`. Route: inline. A decision-mode fault takes about 21 minutes on the cloud route (19 of them baseline), so a round of F1-F3 takes about 65 minutes and 10 rounds about 11 h, unattended. Cost is about $0.10.
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

- 2026-10-24: T2 done (route: delegated writer; trigger: 5 files across the scripts, their tests and the docs).
  - `verify-agent.sh --mode decision`:
    - the window margin is 2 minutes in decision mode and still 10 in the other modes;
    - a 19-minute baseline of healthy orders runs before each fault (see Design);
    - a window larger than N_max fails the fault;
    - `--rounds K` repeats the fault list, with round-numbered files and a `round` column.
  - `check-recordings.sh` checks both windows of every recording. A clean decision-mode F3 fixture lives in `infra/scripts/testdata/check-recordings/clean/`.
  - RED, in steps: `window_margin: command not found`, then `--rounds 0` answered "Unknown option", then the check script did not exist (exit 127). GREEN: 108 cases.
  - **Evidence for the Problem section.** `check-recordings.sh` fails all three 2026-10-19 recordings:
    - F1: "0 PAID orders" in the previous window, and restarts of all four services in the incident window;
    - F2: "PAYMENT_PENDING 6" and F1's `HttpTimeoutException x6` in the previous window;
    - F3: "PAYMENT_FAILED 6" and F2's `429 TooManyRequests x6` in the previous window.
  - Adjustment, requested by the parent before commit: a baseline order that is not PAID restarts the baseline, so the order ages out of both windows. After 2 restarts, the third failure fails only that fault (a MISS with status `baseline failed`), and the run goes on. Without this, one refused order (for example right after F3's restore recreates order-service) would have ended an 11-hour unattended session.
    - RED: the run stopped at the first refused order.
    - GREEN: `test-lib.sh` passes 112 cases.
  - Parent spot check: `test-lib.sh` passes 112 cases; the old run exits 1; the clean fixture passes.
  - About 650 authored lines, over the 400 heuristic: the check script is 190 lines and the tests about 175.
  - Not covered by a test: the `diagnose` branch of `verify-agent.sh`. It needs the lab; its loop shape is tested against the real `baseline_phase`.

- Slice review, T1r and T2 (`cf005a0`, base `3830488`, four lenses, 13 files, 988 lines, consent granted): approved and acknowledged (lineage `review-bfba21c58f6dd791`). Findings:
  - WARNING R2-check-recordings-client-error-label: the previous-window check flags every non-SUCCESS row, server side included, but labels them all "client errors". The check is right; the label is wrong.
  - SUGGESTION R3-wallclock-budget-test-timing: `theGatheringDoesNotCountAgainstTheIncidentsTimeBudget` uses the real clock with a 400 ms budget, so it can fail on a loaded host.
  - SUGGESTION R3-decision-diagnose-branches-unproved: no test covers decision mode's two branches in `diagnose()`, the `baseline failed` row and the window-too-large MISS.
- 2026-10-24 16:26 UTC: T4 started, `verify-agent.sh --mode decision --route cloud --rounds 10` on `cf005a0`, about 11 h. Until it ends, nothing that it runs may change: `verify-agent.sh`, `lib.sh` and the lab's sources.

- 2026-10-24: T2r done (route: inline; mechanical edits in known places).
  - The label: `check-recordings.sh` now says "traffic not SUCCESS" (either side), and its PASS line says "all traffic SUCCESS". The check itself is unchanged.
    - RED: the `test-lib.sh` expectations were updated first, and "a clean decision-mode recording passes" failed.
    - GREEN: 112 cases.
  - The flaky test: `theGatheringDoesNotCountAgainstTheIncidentsTimeBudget` now uses a 3 s gathering and a 2 s budget. `theTimeBudgetStartsWhenTheWarmUpEndsNotWhenTheIncidentOpened`, which had the same 400 ms real-time window, now uses a 10 s clock offset and a 2 s budget.
    - Each test still proves its property: the gathering outlasts the whole budget, and the offset exceeds it.
    - Mutation check: with the old `elapsed()` restored, exactly these two tests failed (12 run, 2 failures). With the fix, they are green.
  - The full agent suite is green.
  - T2r2, after T4 ends, will test decision mode's two `diagnose()` branches: the `baseline failed` row and the window-too-large MISS.

- T2r review (`f3cd4c3`, base `cf005a0`, four lenses, consent granted): approved and acknowledged (lineage `review-f51735ec76bd71be`). It made one suggestion, R3-gathering-act-headroom: the 3 s gathering leaves about 2 s of real time against the 5 s ACT deadline. It goes to T2r2.
- 2026-10-24: T3 started (delegated writer). Its surfaces exclude `verify-agent.sh` and `lib.sh`, which T4 is running.

- 2026-10-24: T3 done (route: delegated writer; trigger: 9 files across the replay driver, its tests, two scripts, the docs and the spec).
  - The manifest gains a `split` column, and the six old rows are `stress`.
  - Decision mode replays each recording of a split as its own case, with no pooling. `--repeats M` reruns each case; `summary.tsv` gains `split` and `recording`; `aggregate.tsv` counts distinct recordings per split.
  - `replay-agent.sh` gains `--split` and `--repeats`. `--samples` is refused in decision mode, and the cloud limit of 60 counts recordings × repeats.
  - `record-replay.sh`:
    - `--split` is required;
    - it reads round files;
    - it runs `check-recordings.sh` on each incident except in `stress`;
    - it refuses to move a recording between splits;
    - `--rounds LIST` lets one run feed both `dev` and `holdout`.
  - RED and GREEN:
    - Java RED: the new tests did not compile (40 "cannot find symbol"). Java GREEN: 14 tests.
    - Mutation check: pooling a decision case made exactly `aDecisionCaseIsServedItsOwnRecordingAlone` fail.
    - Shell RED, in two steps: decision mode accepted `--samples`, then ran without `--split`. Shell GREEN: 135 cases.
    - Full suite: 488 tests.
  - Parent spot check: `test-lib.sh` passes 135 cases; the full suite is green.
  - About 1,090 changed lines: two scripts partly rewritten, about 350 lines of tests, about 100 of docs and spec.
  - Follow-up: explorer and dossier replays pool every recording of a fault, so after the T4 import they will also pool the decision recordings, and their conflicts will rise.
  - Import plan for T4: odd rounds go to `dev`, even rounds to `holdout`, so any drift over the 11-hour session spreads over both:
    - `record-replay.sh --split dev --rounds 1,3,5,7,9 <run>`
    - `record-replay.sh --split holdout --rounds 2,4,6,8,10 <run>`
- 2026-10-24 16:47: the first T4 recording (`F1-r1`) passes `check-recordings.sh`. Its previous window holds 5 PAID orders, and all traffic is SUCCESS; its incident window has no restart; the window is 4 minutes. The cloud decision run took 15 s (steps DOSSIER and REPORT) and matched.

- T3 review (`4b26044`, base `f3cd4c3`, four lenses, 10 files, 1,114 lines, consent granted): approved and acknowledged (lineage `review-cdc06bb0f905dcbb`). All findings go to T3r, which is running:
  - WARNING R2-awk-case-selection-duplicate: `replay-agent.sh` repeats `decisionCases`' rule in awk, with no test that they agree.
  - WARNING R3-dossier-pools-holdout: explorer and dossier replays pool every split, holdout included, so imports would change them and holdout would leak into non-decision replays.
  - SUGGESTION: the split names are hard-coded.
  - SUGGESTION: the comment of `recorded_split` is garbled.
  - SUGGESTION: the `ReplayRunTest` property refusals are untested.
- 2026-10-24 21:15, interim T4 results. The cloud route in decision mode on clean incidents (live, rounds 1-5): F1 3/5, F2 5/5, F3 4/4.
  - **F3 is right every time** once its previous window holds no other fault: the contamination was the F3 problem.
  - **F1 is now the hard case, on the cloud too.** Both misses name `payment-service|DEPENDENCY_LATENCY`, one hop early. With a healthy baseline a caller's latency includes its callee's: in F1-r1, payment-service's `/payments` mean is 1.229 s against gateway-mock's `/authorize` 1.177 s (maxima 9.979 and 9.905). Comparing magnitudes cannot find the origin. On the old recording F1 looked easy, because its empty baseline left gateway-mock alone at 9.256 s.
  - This is the case Part B's per-service assessment is designed for: a slow leaf is ORIGIN, and a slow caller of a slow service is AFFECTED.

- 2026-10-24: T3r done (route: delegated writer; trigger: 8 files across the replay driver, two scripts, their tests, the docs and the spec).
  - **One source for case selection.** A new class `ReplayCases` (JDK only) owns `recordings.tsv`: the splits, the pooled split, the decision cases and `replay.faults`.
    - `ReplayDriver` uses it.
    - `replay-agent.sh` runs it straight from its source file through the Java 21 launcher, in 0.6-0.8 s with no Maven and no Docker. Its awk copy is gone.
    - `ReplayRunTest` was not used for this, because it is a `@SpringBootTest` with a Testcontainers Postgres.
    - RED: on a manifest with an unknown split, the old script counted 2 recordings while the driver refused it.
  - **Explorer and dossier replays pool only `stress`**, so no import into `dev`, `holdout` or `probe` can change them, and holdout never reaches a non-decision replay.
    - RED: `explorerAndDossierSamplesPoolOnlyTheStressRecordings` failed on HEAD (`expected "stress" but was "dev"`).
  - Split names come from one constant in Java, and from one array in `record-replay.sh`.
  - The `recorded_split` comment is rewritten.
  - `ReplayDriver.refuseIgnoredProperties` is now unit-tested.
  - Results: full suite 492 tests and `test-lib.sh` 137 cases. Parent spot check: both green, and the listing prints the three stress cases.
  - About 645 lines, about 120 of them moved from `ReplayDriver` to `ReplayCases`.

- T3r review (`0f1398f`, base `4b26044`, four lenses, 10 files, 672 lines, consent granted): approved and acknowledged (lineage `review-5129e067991ad6b0`). Four suggestions, all going to T2r2:
  - a misleading helper comment in `test-lib.sh`;
  - two wordings of the unknown-split refusal;
  - `record-replay.sh`'s split array as a second list next to `ReplayCases.SPLITS`;
  - an empty case listing counted as 1, probably unreachable because `--faults` with no fault is refused earlier.
- 2026-10-24 21:40: Part B (`odd/tasks/service-assessment.md`) starts on its own branch while T4 records. It is safe because `verify-agent.sh` builds the agent once, before the fault loop, so later source edits never reach T4's container. Part B's settings default to today's behavior, so M0 still measures today's prompt.

- 2026-10-25 02:35 UTC: the T4 recording session ended after 10 h 10 min (`verify-agent-20261024T162555Z`, on `cf005a0`).
  - All 30 incidents are COMPLETED, each in two steps (DOSSIER and REPORT) of about 15 s. The windows are 4 minutes for F1 and 3 for F2 and F3.
  - `check-recordings.sh` passes all 30:
    - every previous window holds at least 3 PAID orders, no other state, and only SUCCESS traffic;
    - no incident window has a restart, except F3's own recreate of order-service.
  - Live verdicts on the cloud route: F1 8/10, F2 10/10, F3 10/10. Both F1 misses, in rounds 3 and 4, name `payment-service|DEPENDENCY_LATENCY`, one hop early. With the planned import, one miss falls in `dev` (r3) and one in `holdout` (r4).
  - The script exits 1, because each MISS is a `fail` line.
  - Cost: 159,390 prompt and 16,466 completion tokens, about $0.02 at the replay's prices.

## Next step

On this branch:
- T2r2: the `diagnose()` branch tests, the ACT deadline headroom and the four T3r suggestions;
- the import, with odd rounds in `dev` and even rounds in `holdout`, which closes T4;
- T5 (M0), with the machine otherwise idle, because a concurrent build would slow the local route.

Then rebase `feat/service-assessment` onto this branch's tip.
