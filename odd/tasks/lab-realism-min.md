# lab-realism-min

## Objective

Make holdout-2 more than a copy of holdout-1, within the time left before the freeze on 2026-12-01: healthy controls, parameters drawn for F1 and F2, and a seeded schedule (ADR-026), so the lab can record 75 holdout-2 cases on 2026-12-01 to 05.

## Problem

Holdout-1 and dev hold 5 each of F1, F2 and F3, with fixed parameters, in a fixed order (`verify-agent.sh`). The fault injector's parameters are constants (`Fault.java`), `POST /faults` takes only an id, and no recording is healthy. A holdout-2 recorded the same way would test the agent on cases it has already seen in kind, and could not measure a false alarm.

## Why

The human chose on 2026-11-03 the minimal version of vision's lab-realism, by the cut order of vision §7: items 1–4 (Config B, D+ and the cloud repeats, L4 probes, TT and RE3) are not in progress, and item 5 cuts "lab parameters beyond F1 and F2". Healthy controls and F1/F2 parameters stay, because they are what makes holdout-2 new and what measures false alarms.

## Scope

- A schedule generator: seed in, `schedule.tsv` out (ADR-026).
- `check-freeze.sh` condition 3 checks the seed, the schedule and the recordings (ADR-026, Decision 4).
- The fault injector accepts F1's and F2's parameters, within ranges declared in its catalog.
- The harness records a schedule: a fault with its parameters, or a healthy control.
- The `holdout-2` split and a schedule-case column in the manifest.
- ADR-015's healthy truth `NONE|NONE` made final.

Out of scope, by the cut order, and to be stated in `benchmark.md`: a load generator, F3 and F4 parameters, F4 and F8 in the eval, concurrent traffic.

## Constraints

- **While A's run takes the CPU** (until about 21:30 UTC on 2026-11-03): no Maven, no docker, nothing under `agent/` or `lab/`. Only T2 and T3 can run then.
- The freeze tag covers `agent/src/main/`; the generator and the catalog's ranges must be committed before it.
- A parameter never changes a case's truth: F1 is still gateway-mock `DEPENDENCY_LATENCY`, F2 still gateway-mock `DEPENDENCY_REJECTING`. T6 checks it on dev recordings.

## Design

**Classes and counts, 75 cases:** 20 F1, 20 F2, 20 F3, 15 healthy, in an order the seed shuffles.

**Declared ranges:**

| Fault | Parameter | Values | Why |
|---|---|---|---|
| F1 | gateway latency | 2 s, 5 s, 10 s, 20 s | straddles the callers' read timeouts: below order → payment's 3 s, between it and payment → gateway's 15 s, and above both |
| F2 | gateway error status | 429, 500, 503 | vision §2's list |
| F3 | wrong port | 9999, as today | not drawn (cut order, item 5) |

A discrete set, drawn uniformly, so each value has about 5 cases and a per-value reading is possible.

**The generator:** Python standard library, `infra/scripts/holdout2-schedule.py`. Input: the seed's hex and the counts. Output: `case, class, parameter, value` rows, the order and every draw from one `random.Random` seeded with the integer of the seed's 256 bits. Same seed, same file, byte for byte.

**A healthy control:** the same harness, baseline and symptom text as a fault case, with no injection. Truth `NONE|NONE`; `UNKNOWN|UNKNOWN` is correct and any cause a false alarm (ADR-015).

## Tasks

- [x] T1 This document. Route: inline (docs).
- [x] T2 The schedule generator and its tests. Route: delegated writer (while A runs).
- [x] T3 `check-freeze.sh` condition 3 as ADR-026 Decision 4 says, and its tests. Route: the same writer, its own commit.
- [x] T4 The fault injector's parameters for F1 and F2, tests first; its spec. Route: delegated writer, after A's run.
- [ ] T5 The harness records a schedule (fault with parameters, or healthy); the `holdout-2` split and the schedule-case column in `record-replay.sh` and `ReplayCases`; ADR-015's amendment. Route: delegated writer, after T4.
- [ ] T6 A plan and a dev recording of a few scheduled cases, one per F1 value, F2 status and a healthy control, to check mechanics and truth. Route: inline commands.

## Acceptance criteria

- AC1: the generator is deterministic, gives the stated counts, and draws only declared values.
- AC2: condition 3 passes in a test repository with a seed after the tag, a matching schedule and later recordings; it fails on a seed before the tag, an edited schedule, a recording before the tag, or a recording naming no schedule case.
- AC3: `POST /faults` with a parameter outside the declared values is refused with a 400; without parameters, today's behaviour is unchanged.
- AC4: a healthy recording passes `check-recordings.sh` and holds no injection.
- AC5: T6's recordings keep their truths at every parameter value.

## Checks

- T2, T3: the scripts' tests, red first. T4, T5: the modules' tests, red first. T6: the plan's checks.

## Delivery

Forecast: about 800 authored lines in all, over budget, so `feature-branch-chain`: `feat/lab-realism-min` for T1–T3, `-01-injector` for T4, `-02-harness` for T5–T6. Nothing is pushed.

## Progress

- 2026-11-03: the human chose the minimal version and ADR-026's seed.
- 2026-11-03: T2 and T3 done by a delegated writer, with no Maven or docker while A's run took the CPU. T2 `afad141`: `holdout2-schedule.py`; RED the script missing; GREEN 22 cases; a golden of the first 5 rows for a fixed test seed, checked by a separate reimplementation. T3 `b7a0092`: condition 3 checks the seed's first commit is after the tag and never edited, the schedule equals the generator's output **as of the tag** on the committed seed, and every `holdout-2` row names a schedule case in a `case` column and starts after the tag; RED on the old script; GREEN 44 cases. Parent spot check: 22 and 44 pass. 422 authored lines. AC1 and AC2 hold. Today's output unchanged: condition 3 fails on the missing tag.
  - The writer's choices: the generator is run as of the tag, so a generator changed after the freeze cannot produce another schedule; it must therefore be committed before the tag. Draws are uniform per case, so about 5 cases per value in expectation, not exactly.
  - Not checked yet: that the seed commit holds the seed alone; that a manifest row's fault matches its schedule case's class and parameter (T5 defines the manifest's healthy rows).

- 2026-11-03: native review of `0f9f893..878622f`: four lenses, **approved** and acknowledged (lineage `review-e6bedfd6bed304aa`). Its WARNINGs: the schedule depended on `random`'s `shuffle` and `choice`, which Python does not keep stable across versions; the seed commit need not descend from the tag; condition 3 passed with 1 of 75 cases or a repeated case; a short manifest row was skipped silently; and commit times are self-asserted. ADR-026's amendment of 2026-11-03 records the response.
- 2026-11-03: fixed by a delegated writer (`78e98b1`): every draw from a SHA-256 counter stream with rejection sampling and Fisher–Yates; the seed commit must descend from the tag; every schedule case recorded exactly once, after the schedule's first commit; a short row fails; tests for the generator absent or refusing at the tag; one timestamp helper. RED observed on every item but the generator-absent test, which pins existing behaviour. GREEN 26 and 55 cases; parent spot check the same. 350 authored lines. A new golden, checked by an independent reimplementation; over 3,000 seeds the first case's class counts are near uniform.
  - **The limit that stays:** commit times are written by the committer. The repository is local, so condition 3 proves the order of the history as written, not that it was written honestly (ADR-026, amendment of 2026-11-03). A public timestamp of the seed's hash would close it: the human's decision.
  - Follow-up closed on 2026-11-03 (`dd8bdfd`, branch `chore/follow-ups-2026-11-03`): the schedule's first commit must descend from the seed's first commit. Native review of the follow-ups `2be607c..42de768`: four lenses, **approved** and acknowledged (lineage `review-2ef0038f0d332210`), SUGGESTIONs only: git's stderr dropped on a `merge-base` error; the same-commit case and the git-error branch untested; names and wrapping.

- 2026-11-03: native review of `878622f..1f71e4d`: four lenses, **approved** and acknowledged (lineage `review-62d4772ac1b693b6`). SUGGESTIONs only, not fixed beyond the amendment's name: a test comment's bound wording, a duplicated fixture setup, the ancestry check's git-error branch untested.

- 2026-11-03: T4 done by a delegated writer on `feat/lab-realism-min-01-injector` (`be929c3`). `POST /faults` takes an optional `parameters` object: F1 `gatewayLatencyMs` ∈ {2000, 5000, 10000, 20000}, F2 `gatewayErrorStatus` ∈ {429, 500, 503}, declared once in `Fault.java`; any other name, value, type, or a parameter for F3 or F4 is refused with a 400 before anything is written. Without parameters every fault behaves as before. `injections.parameters` stores the applied values; the root-cause sentence names the applied value; service and category never change. gateway-mock allows exactly 429, 500 and 503 (`GatewayBehavior.java`:25). RED at compile level; GREEN `./mvnw test` 174 run, 0 failures (125 before). About 522 authored lines. AC3 holds.
  - The writer's choices: a nested `parameters` object, since Spring Boot ignores unknown top-level fields; refusal reasons name no parameter and list no value, so the leak scan holds.
  - Gap: a parameter put outside `parameters` is ignored silently and the default applied. T5's harness must nest it, and T6 must confirm the stored value.

- 2026-11-03: native review of `cb48362..741c630` (the policy count fixes and T4): one reliability lens, **approved** and acknowledged (lineage `review-f72393b4297d1264`). SUGGESTION R3-split-truth-table fixed: the injector spec's truth table was split by a paragraph. Follow-up, not fixed: WARNING R3-fragile-pool-end-detection, the policy timeout test (`AgentLoopDossierModeTest.java`:421-426) spins on a JDK method name and fails only by its 30 s timeout if the executor changes.

## Next step

T5.
