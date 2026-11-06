# repeated-service-first

## Objective

A plain report whose alternatives repeat a service, the first cause's included, is no longer invalid. `ReportCheck` keeps the first occurrence of each service and drops the later ones, as the scorer's `ranking_of` already does. The agent's part lands before the freeze on 2026-12-01.

## Problem

`ReportCheck.alternatives` (`agent/src/main/java/dev/kintsugi/agent/loop/ReportCheck.java:654-679`) rejects a report that names a service twice. It compares the service only and ignores the category. Three lab F2 runs ended `INVALID_REPORT` this way:
- DS `r7` on 2026-11-04 (`infra/scripts/out/replay-20261104T222411Z-cloud/`);
- DK `r3` and `r9` on 2026-11-05 (`infra/scripts/out/replay-20261105T211956Z-cloud/`).

In all six attempts the cause was the truth, `gateway-mock|DEPENDENCY_REJECTING`:
- Attempt 1 added the cause's service again, `gateway-mock|RESOURCE_EXHAUSTION`.
- The retry does not show the model its previous report (`AgentLoop.java:709`). So attempt 2 moved RESOURCE_EXHAUSTION to `payment-service`, which already held CONFIGURATION, and made a new repeat.

The model ranks (service, category) hypotheses, while the rule counts services.

## Why

- Keeping the first occurrence is the model's own order, reduced to services. That is the ranking `ranking_of` (`evals/kintsugi_evals/scoring.py:163-177`) computes, so no score changes.
- It is deterministic. A better retry message would be probabilistic, and the model already worked around the current one.
- It is post hoc: it was written after seeing these three dev cases. The human chose it on 2026-11-06.

## Scope

- **T1, the rule (test-first):**
  - `ReportCheck` drops each alternative whose service is already named, the first cause's included, and keeps the first occurrence.
  - The stored report holds the deduplicated list. The raw `modelOutput` stays in the step row.
  - The step summary names every dropped item, so the drop is observable.
  - It applies to the ranked report and to an abstention's suspects (ADR-025 amendment 2026-11-03).
  - The other rules are unchanged: enums, no UNKNOWN item, at most 4 items on the deduplicated list.
  - The prompt is unchanged: it still asks for no service twice.
- **T2, documentation:** ADR-025 amended (Decision 3), the living spec `openspec/specs/agent/spec.md`, and the `ReportCheck` javadoc.

Out of scope:
- a new model run (the next D run will show it);
- the retry message;
- the hybrid and assessed reports, which have no alternatives;
- the scorer, which already deduplicates.

## Constraints

- **Freeze:** this touches `agent/src/main/` and `agent/src/test/`, and closes before 2026-12-01.
- **Config 0 unchanged:** the rules do not write alternatives.
- **Committed results still score and regenerate:** they keep their stored reports.

## Acceptance criteria

- AC1: given a report whose cause is `gateway-mock|DEPENDENCY_REJECTING` and whose alternatives are `payment-service|CONFIGURATION`, `gateway-mock|RESOURCE_EXHAUSTION` (r7's attempt 1, as recorded), the report is valid. Its stored alternatives are `payment-service|CONFIGURATION` only.
- AC2: a repeat within the alternatives, for example `payment-service|CONFIGURATION`, `payment-service|RESOURCE_EXHAUSTION`, keeps the first item only.
- AC3: an UNKNOWN alternative is still invalid. A list of 5 distinct services is still invalid. A list of 5 that holds 4 distinct services after the drop is valid.
- AC4: an abstention (UNKNOWN|UNKNOWN) keeps every listed suspect except the repeats.
- AC5: the step summary names each dropped alternative, by its index and service.
- AC6: the full `agent` test suite passes.

## Checks

- `cd agent && ./mvnw -q test -Dtest=ReportCheckTest` (RED before the change, GREEN after).
- `cd agent && ./mvnw -q test` (full suite).

## Tasks

- [x] T1 2026-11-06: the first-occurrence rule, test-first (route: delegated writer; trigger: 2+ non-trivial files, plus the reading that prepares the write).
  - The drop is in `ReportCheck.alternatives`; `Result.alternatives` carries the kept list, and `AgentLoop.stored` writes it into the stored report.
  - A repeat is dropped only when the item breaks no other rule. The bound of 4 counts the kept items.
  - Each drop is named in the step summary (`valid; dropped: alternatives[<i>]: <service> is already named ...`). It is appended after the L1 `citations` check, which counts only dropped evidence.
  - RED: 4 `ReportCheckTest` failures and 1 `AgentLoopDecisionModeTest` failure. GREEN: `./mvnw test -Dtest='ReportCheckTest,AgentLoopDecisionModeTest'` 140 tests, 0 failures. The full suite: 798 tests, 0 failures.
  - The parent's spot check gave the same: the targeted run, 140 tests, 0 failures.
- [x] T2 2026-11-06: ADR-025 amendment, living spec, javadoc (route: the same writer, in the same work unit).

## Delivery

- Forecast: under 400 authored changed lines, so `single-pr`, one branch `fix/f2-repeated-service` from `main`.

## Progress

- 2026-11-06: the explorer mapped the rule, the three incidents and the scorer; the human chose the first-occurrence rule.
- 2026-11-06: T1 and T2 in `2690e4f`. Assessed medium (`executable_change`), `under_budget`; the feature closes here, so it was reviewed now, consent granted. One lens (reliability), approved and acknowledged.

## Follow-ups

- R3-001 (SUGGESTION): no test shows that an alternative with an unexpected field is neither kept nor dropped as a repeat (`ReportCheck.java:686-689`). Only the UNKNOWN case is tested.
- No model run shows the rule yet: the next lab D run will.
