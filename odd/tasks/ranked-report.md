# ranked-report

## Objective

Let the model's plain report rank up to five causes (vision §7 M3), without changing what AC@1, H1 or the verdict read, and measure that on RE2-OB dev.

## Problem

D and A name one service, so their AC@3 and Avg@5 equal their AC@1, and nothing compares them with BARO's ranking or with chance (`evals/results/2026-11-02-ranked-re2ob-dev/`).

## Why

A ranked list says something AC@1 cannot: when the first answer is wrong, whether the right one was close. That is the question the published baselines answer.

## Scope

ADR-025's decision: the `alternatives` field, its check, the prompt's description, the `ranked` column, the scorer's ranking; specs and pins; a plan and a D run.

Out of scope: NETWORK and CODE_DEFECT (their own feature, the human's choice on 2026-11-03), the assessed and hybrid reports, A runs, any holdout.

## Constraints

- AC@1, verdicts and Config 0's and 0R's outputs unchanged (rules write no `ranked`).
- The local cap stays 512 tokens; the plan watches cut reports.
- The prompt adds the description of the field and nothing else.

## Design

ADR-025. The prompt's `REPORT_TASK` gains, after the causes line: "alternatives: up to 4 other causes you have not ruled out, best first, each {rootCauseService, rootCauseCategory}, no service twice and none UNKNOWN; an empty list when both causes are UNKNOWN."

## Tasks

- [x] T1 ADR-025. Route: inline (docs).
- [x] T2 The agent: schema, `ReportCheck`, prompt, stored report, `ranked` column; tests first; pins; the agent's living spec. Route: delegated writer.
- [x] T3 The scorer reads `ranked` (REQ-13), tests first. Route: delegated writer, own commit.
- [x] T4 The plan. Route: inline (docs).
- [ ] T5 D's run, the rules check, the score and the result. Route: inline commands.

## Acceptance criteria

- AC1: a report with valid alternatives passes; a repeated service, an `UNKNOWN` item, five items, or items after an `UNKNOWN|UNKNOWN` cause fail the check.
- AC2: the system prompt and report schema change only by the field; the pins show it.
- AC3: rules runs are unchanged in every column the rules write.
- AC4: the scorer's ranked AC@1 equals AC@1 on every committed input, and `ranked` is read after `reported` and before `candidates`.
- AC5: D's run is scored against DW under the plan's rule, with Wilson CIs, AC@3 and Avg@5.

## Checks

- T2: `cd agent && ./mvnw -q test`, red first. T3: `cd evals && .venv/bin/python -m pytest -q`, red first. T5: the plan's checks.

## Delivery

Forecast: about 350 authored lines for T2, 80 for T3, 150 for T1, T4 and T5; over budget, so `feature-branch-chain`: `feat/ranked-report` for T1–T2, `feat/ranked-report-01-scorer` for T3–T5. Nothing is pushed.

## Progress

- 2026-11-03: the human chose the design and to keep NETWORK and CODE_DEFECT for a later feature.

- 2026-11-03: T1 done (`dd4a2a9`). T2 done by a delegated writer (`a94908b`). RED: 125 failures against stubs; GREEN `./mvnw -q test` 712 run, 0 failures. About 595 authored lines, over the heuristic, mostly AC1 tests and every scripted answer moving with the field. AC1 and AC2 hold.
  - The hybrid keeps today's schema and text byte for byte through `Schemas.hybridReport` and `HYBRID_REPORT_TASK`; a hybrid answer with `alternatives` is refused as an unexpected field. The assessed report is untouched.
  - The writer's choices: the schema's alternative enums leave out `UNKNOWN`, so the local grammar cannot write it; no alternative is dropped, every breach makes the report invalid with the existing retry.
  - Pins: the three plain report schemas gain the field before `confidence`; the plain instruction gains the Design's sentence; the summary header ends `candidates\tranked`. `system-*.txt` and the assessed schema are unchanged.

- 2026-11-03: native review of `d772623..7d48f5e`: one reliability lens, **approved** and acknowledged (lineage `review-7371ca44004e6d3e`). Three SUGGESTIONs, not fixed: no loop-level test of a bad `alternatives` answer's retry; no test that an assessed stored report has no `alternatives`; the hybrid no-candidate stored report has no `alternatives` field, while the spec says it is shaped like the plain report (the `ranked` column reads a missing array as empty).
- 2026-11-03: T3 done by a delegated writer on `feat/ranked-report-01-scorer` (`5f0d8f0`): `ranking_of` reads `reported`, `ranked`, `candidates`; a file without `ranked` scores as before, proved on the six committed inputs of the resource-warrant result. RED 5 failed; GREEN 328 passed. 99 authored lines. `audit_latency.py` and `cli.py` read columns by name, unchanged. AC4 holds.
- 2026-11-03: T4 done: `evals/plans/2026-11-03-ranked-report-re2ob-dev.md`.

## Next step

T5.
