# decoy-categories

## Objective

The report's taxonomy gains NETWORK and CODE_DEFECT as decoy categories, each defined in the prompt, so the taxonomy is wider than the catalog ([ADR-029](../../docs/adr/ADR-029-decoy-categories.md); `docs/vision.md:55`). It lands before the freeze on 2026-12-01 and is measured on dev.

## Problem

The taxonomy is exactly the four catalog categories (`Causes.java:17-18`), which is structural leakage (`odd/tasks/vision-revision.md`, row 4). The two categories were split out of the ranked report by the human on 2026-11-03 (ADR-025).

## Scope

- **T2, the taxonomy:**
  - `Causes.CATEGORIES` gains the two values, and the prompt gains their definitions (`Prompts.java:63-81`).
  - The schema and prompt pins are regenerated: `agent/src/test/resources/topology-pins/*`, `LabTopologyPinsTest`, `ReportCheckTest`'s enum strings, `PromptsTest`.
  - The living spec is updated: the field table in `openspec/specs/agent/spec.md`.
  - The work is test-first.
- **T3, the measurement:** a committed plan, then fresh runs on dev with Config 0 alongside, compared with the committed runs of the same configuration.

Out of scope:
- any truth change: ADR-020's map, the fault-injector enum, `leakage.py`;
- the rules (ADR-016);
- any holdout.

## Constraints

- **Freeze:** T2 touches `agent/src/main/`, so it closes before 2026-12-01.
- **Config 0 unchanged:** its verdicts on lab dev, lab holdout and RE2-OB dev stay identical.
- **Committed results regenerate byte for byte.**
- **Measurement rules:** a committed plan before any run, Wilson 95% CIs, Config 0 alongside, no holdout. A cloud run needs the human's approval of its cost.

## Acceptance criteria

- AC1: a report with `NETWORK` or `CODE_DEFECT` as its category passes `ReportCheck` and is stored. An unknown category is still refused, with the six values plus `UNKNOWN` in the message.
- AC2: the prompt states both definitions. The pin files change only by the two categories, and the diff shows that.
- AC3: Config 0's verdicts are unchanged (the rules replays on lab dev and RE2-OB dev).
- AC4: `./mvnw test` in `agent/` and `.venv/bin/python -m pytest -q` in `evals/` pass.
- AC5: the measurement follows its committed plan, and its results are committed.

## Tasks

- [x] T1 This document and ADR-029. Route: inline.
- [x] T2 The taxonomy, the prompt, the pins and the spec. Route: delegated writer (2+ non-trivial files).
- [x] T3 The plan, the runs, the results and notes; STATE.

## Delivery

Strategy `feature-branch-chain`, the standing preference. The forecast for T2 is about 150 authored lines plus the regenerated pins. Branch `feat/decoy-categories`, from `903cbb6`.

## Progress

- T1: `aac223f`. ADR-029 accepted. The human confirmed both definitions on 2026-11-05.
- T2: `aa25d35`. Route: delegated writer (code, tests, six pin files and the spec).
  - RED: 9 failures before the code change, in `ReportCheckTest` (4: the new decoy test, both enum error strings, the alternatives' schema enum), `AgentLoopDecisionModeTest` (3: the new stored-decoy test, the plain and hybrid byte-for-byte schemas) and `PromptsTest` (2: the new definitions test, the warrant now followed by `NETWORK`).
  - GREEN: `./mvnw test` in `agent/`: 794 tests, 0 failures, 0 errors; `pytest -q` in `evals/`: 412 passed.
  - AC1: `theDecoyCategoriesAreValidCausesAndAlternatives` and `aReportNamingTheDecoyCategoriesIsStored`; the enum errors list the six values plus `UNKNOWN`.
  - AC2: each schema pin changes by `"NETWORK","CODE_DEFECT"` in its category enums only; each system pin gains the two definition lines before `UNKNOWN`.
  - The rules are untouched. AC3 (the Config 0 replays) is left to T3.
- Review of `aa25d35`: medium risk, one lens (reliability), consent granted by standing preference. Approved and acknowledged (`review-d5e282973f7fef38`). Advisories:
  - R3: the new spec scenarios had split the alternatives scenario from its last AND line. Fixed in the next commit.
  - R3: no test accepts an assessed report naming a decoy. That path is covered only by the enum error string and the schema pin. Follow-up.
    - Closed 2026-11-06: `anAssessedReportNamingADecoyCategoryIsValid` in `ReportCheckTest`. It pins behavior that already existed, so it had no RED; a mutation to an unknown category made it fail, and the mutation was reverted.
- T3: plan `57340ed`; runs `replay-20261105T211956Z-cloud` (lab) and `replay-20261105T212143Z-cloud` (RE2-OB), $0.060 in all. Config 0 is unchanged, so AC3 holds.
  - No answer names a decoy as its cause.
  - Lab: 13/15 against DS's 14/15, p = 1.
  - RE2-OB service: 21/30 against 26/30, p = 0.062, mostly through more abstentions (6 against 3).
  - Results: `evals/results/2026-11-05-decoy-categories-*`. AC5 met.
- Open, the human's call: whether the RE2-OB drop is the taxonomy or sampling noise. One run per configuration cannot tell.
- Noise check, plan `4e8cda9` (amended `9849aa7`): 3 runs each, $0.242.
  - DS′ 71/90 against DK′ 63/90; DK′ − DS′ is −8.9 points [−17.8, 0.0].
  - The rule's outcome: the CI includes 0, so keep. It is borderline, and the decision is the human's.
- Closed: the human kept the decoys on 2026-11-05, and ADR-029 records the probable cost.
