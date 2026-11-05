# l3-callee-check

## Objective

L3 no longer certifies an answer that names a caller of the true cause. A second L3 check, `no-changed-callee`, requires that no direct callee of the claimed cause has a changed latency or resources line in the dossier. The rule is post hoc ([ADR-028](../../docs/adr/ADR-028-verification-levels.md), Decision 5, amended 2026-11-05). The agent's part lands before the freeze on 2026-12-01.

## Problem

`cause-fact-changed` (`ReportCheck.java:515-525`) asks only that a cited line of the claimed cause changed. A caller's latency rises with its callee's, so a wrong answer naming the caller passes. 5 wrong dev answers reached L3 this way: HC 3, HT 1, DS 1. All 5 named a direct caller of the truth, and the truth had its own changed latency line.

## Scope

- **T2, the check:** `no-changed-callee` at level 3 in `ReportCheck` and `Verification`. It needs all line facts and `Topology.calls`. The decision report and the assessed (SHEETS) report get it too, wherever L3 is computed today. It is test-first.
- **T2, documentation:** the living spec `openspec/specs/agent/spec.md`.
- **T3, `LevelRecheck`:** it also re-checks hybrid-report rows, so HT and HC can be re-checked offline.
- **T4, the measurement:** a committed plan, then re-checked levels (no model calls) on the committed dev results:
  - RE2-OB DS and AS (the ratio result);
  - lab dev DS;
  - HT;
  - HC.

  It is descriptive: the counts before and after. No holdout.

Out of scope:
- transitive callees;
- sections other than `latency` and `resources`;
- any change to `LineFact`, `WindowChange`, the prompt or Config 0.

## Constraints

- **Freeze:** T2 and T3 touch `agent/src/main/` and `agent/src/test/`, and close before 2026-12-01.
- **Annotation only:** no retry, no dropped item, no prompt change. The dossier text stays byte-identical.
- **Config 0 unchanged:** its verdicts are identical on lab dev, lab holdout and RE2-OB dev.
- **Old results still score and regenerate:** the committed results keep their levels, and the re-check writes new result directories.
- **Measurement rules:** a committed plan before the re-check, Wilson 95% CIs, and results under `evals/results/`.

## Acceptance criteria

- AC1: a report naming a service whose direct callee has a changed latency or resources line, cited or not, fails `no-changed-callee` and stops at L2 or below. The same report stays at L3 when the callee's lines are unchanged or `NOT_COMPARED`.
- AC2: a callee's changed line of another section (traffic, states, events, status) does not fail the check.
- AC3: the existing level tests pass, or each changed expectation is explained in the commit.
- AC4: `LevelRecheck` re-checks a hybrid-report row.
- AC5: `./mvnw test` in `agent/` and `.venv/bin/python -m pytest -q` in `evals/` pass.
- AC6: the re-check follows its committed plan, and its results are committed.

## Tasks

- [ ] T1 This document and the ADR-028 amendment. Route: inline.
- [ ] T2 The `no-changed-callee` check and its spec. Route: delegated writer (2+ non-trivial files), together with T3.
- [ ] T3 `LevelRecheck` for hybrid rows.
- [ ] T4 The plan, the re-check, the results and notes; STATE.

## Delivery

Strategy `feature-branch-chain`, the standing preference. The forecast for T2 and T3 is about 300 authored lines. Branch `feat/l3-callee`, from `5c207a5`.

## Progress

- T1: created, with the ADR-028 amendment.
