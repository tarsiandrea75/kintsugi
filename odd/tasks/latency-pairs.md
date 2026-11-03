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
- [x] T4 The pre-close check, D's run, the audit, the score and the result. Route: inline commands.

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

- 2026-11-03: T4 done on `479d99e`. Results in `evals/results/2026-11-03-latency-pairs-re2ob-dev/` (`audit/` holds REQ-14's output).
  - **Pre-close check passed:** fresh rules v1 and v2 on RE2-OB dev (`replay-20261103T114625Z-rules`, `-114630Z-`), lab dev (`-114634Z-`, `-114638Z-`) and lab holdout (`-114642Z-`, `-114647Z-`) equal the plan's earlier runs in every column but `seconds`. AC2 holds.
  - **DP's run:** `replay-20261103T114659Z-cloud`, 30 rows, all `COMPLETED`, the plan's configuration, D1's recordings; about $0.03. AC4 and AC5 hold.

| Run | AC@1 [Wilson 95%] | Coverage | Selective |
|---|---|---|---|
| D1–D4, old table | 11, 12, 12, 13 of 30 | 43% to 47% | 85% to 100% |
| **DP, pairs** | **16/30, 53% [36%, 70%]** | 60% | 89% |
| 0R | 20/30, 67% [49%, 81%] | 90% | 74% |

  - **REQ-14 detects no misread now:** 0 `previous_only` items out of 50 latency-row items (46 `incident_cited`, 4 `no_number`), against at least one in 26 of 67 abstaining samples and 17 of 53 answering ones before. The rule is a lower bound, so this is not proof that every misread is gone. At least one other kind remains: `5c748506` quotes `| emailservice | / | 0.003 → 0.012 | 0.003 → 0.067 |` and writes "max rose from 0.067 to 0.012", which crosses the incident values of the two cells. The rule counts that item as `incident_cited`.
  - **The gain rule is not met:** wins minus losses against D1–D4 are 7−2, 7−3, 8−4, 7−4, so net 5, 4, 4, 3, all under 7. DP − 0R is −13 points, 9 for DP against 13 for 0R, p = 0.52, so no paired win. **So the layout is not kept as a gain.**
  - DP − 0: +40 points, 13 against 1, p = 0.0018, Holm 0.037. This was not a criterion of the plan.
  - Of the 13 cases that abstained in all of D1–D4, DP answers 7 and gets 6 right.
  - **One run only:** a gain of 3 to 5 is inside what identical runs can differ by, which is why the rule asked for 7.

- 2026-11-03: **the human kept the layout** as a format change, not as a measured gain. The claim stays what the numbers say: DP 16/30 in one run; the gain rule not met; REQ-14 detects no previous-only citation. Every later D measurement uses the pairs, so D1–D4 are the old-table baseline, and a later change to D is compared with DP runs.

## Next step

The feature is complete. Decided: the layout stays. Before that decision, the plan left it to the human: whether to keep the layout without a measured gain. It changes no rule and costs nothing at run time, and REQ-14 no longer detects the misread it targeted, though other misreads remain possible.
- 2026-11-03: native review of `89698fb..689421d`: one reliability lens, **approved** and acknowledged (lineage `review-8a1de7e1ae34d63b`). WARNING R3-misread-gone-overclaim, fixed above: "gone" became "not detected". SUGGESTION R3-exact-arrow-spacing, not fixed: a quoted pair cell with other spacing around the arrow is dropped from the audit, not counted. Before the freeze on 2026-12-01, no further change to D on these 30 cases without its own plan.
