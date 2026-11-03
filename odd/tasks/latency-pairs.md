# latency-pairs

## Objective

Render the dossier's latency table as labelled previous → incident pairs, and measure whether D abstains less on RE2-OB dev.

## Problem

In 26 of 67 abstaining samples and 17 of 53 answering ones, D cites only the previous values of a latency row (`odd/tasks/d-abstention.md`). The row is `| service | uri | previous mean | incident mean | previous max | incident max | previous points | incident points |`, and the model pairs the previous mean with the previous max, two columns apart, as if one were the incident. The plan `evals/plans/2026-11-03-d-repeats-re2ob-dev.md` put this change first.

## Why

A fact the model cannot read is a fact it does not have. The same misread is about as common when D answers, so this change tests whether the misread causes the abstentions; it is not a known fix. Either result is worth having before the freeze on 2026-12-01.

## Scope

- `DossierRenderer`'s latency table only, its tests and goldens; the living spec in `openspec/specs/agent/spec.md`.
- `audit-latency` reads the new row shape too (REQ-14).
- A plan and a result: D on RE2-OB dev, and the pre-close check of vision §6 (Config 0 over every recording plus a 10-case replay).

Out of scope: the resource table, the prompt's wording, A runs (8 h; a later decision), any holdout read by a model.

## Constraints

- One variable: the layout. No new number, ratio or wording that interprets the values.
- Config 0's and 0R's verdicts must not change: check whether any rule reads the rendered text.
- The same evidence rules: a quote must still be a dossier line (`ReportCheck`).
- The gain rule, fixed in `odd/tasks/d-abstention.md`: at least 7 more cases won than lost against each of D1–D4, and a paired win over 0R.

## Design

Today:

```
| service | uri | previous mean | incident mean | previous max | incident max | previous points | incident points |
| frontend | / | 0.054 | 1.325 | 0.061 | 1.477 | 29/29 | 29/29 |
```

After:

```
| service | uri | mean, previous → incident | max, previous → incident | points, previous → incident |
| frontend | / | 0.054 → 1.325 | 0.061 → 1.477 | 29/29 → 29/29 |
```

- Each cell holds one quantity over both windows, previous first, so a value is read next to its pair.
- A missing value stays `-`, as `- → 0.231`. A failed call or no rows keeps its current wording in the first value cell.
- The table's description sentence names the arrow's direction.

## Tasks

- [x] T1 The latency table as pairs, tests first; goldens; the agent's living spec. Route: delegated writer.
- [x] T2 `audit-latency` reads both shapes, tests first; REQ-14. Route: the same writer, its own commit.
- [x] T3 The plan. Route: inline (docs).
- [ ] T4 The pre-close check, D's run, the audit, the score and the result. Route: inline commands.

## Acceptance criteria

- AC1: a rendered row equals the Design's example on the same values, a missing value renders as `-`, and the failed and empty cases keep their wording.
- AC2: Config 0's and 0R's verdicts are unchanged on lab dev, lab holdout and RE2-OB dev.
- AC3: `audit-latency` classifies both shapes, with the same classes for the same values.
- AC4: D's run is scored against D1–D4 and 0R under the gain rule, with Wilson CIs.
- AC5: no model reads a holdout.

## Checks

- T1: `cd agent && mvn -q test`, red first.
- T2: `cd evals && .venv/bin/python -m pytest -q`, red first.
- T4: the plan's mechanics checks.

## Delivery

Forecast: about 200 authored lines for T1 without goldens, about 100 for T2, about 100 for T3–T4. Goldens can be large; if they push a commit past the native review's context, they go in a commit of their own. Strategy `feature-branch-chain` if over budget. Branch `feat/latency-pairs`, from `feat/d-abstention`. Nothing is pushed.

## Progress

- 2026-11-03: the human approved the change.
- 2026-11-03: T1 and T2 done by a delegated writer. T1 `d4bb2bf`: RED 5 failed in `DossierRendererTest`; GREEN `./mvnw test` 697 run, 0 failures; about 142 authored lines, 24 golden lines. T2 `0834509`: RED 6 failed; GREEN 317 passed; parent spot check `pytest -q` 317 passed. AC1 and AC3 hold.
  - No rule reads the rendered text: `RulesDiagnosis.java`:30 and `ResourceRule.java`:48 take the typed `Dossier`. `ReportCheck` looks a quote's line up by number (`ReportCheck.java`:342, :479). `Prompts.java` names none of the old columns, unchanged.
  - The writer's choice: a failed row is `| svc | - | - | - | - |`, no `- → -`, since it has no value in either window.
  - Not touched: the recorded prompts under `agent/src/test/resources/replay/` keep the old table; no test compares them.
- 2026-11-03: T3 done: `evals/plans/2026-11-03-latency-pairs-re2ob-dev.md`.

## Next step

T4.
