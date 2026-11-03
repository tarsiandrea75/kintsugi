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
- [x] T5 D's run, the rules check, the score and the result. Route: inline commands.

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

- 2026-11-03: T5 done on `6c62c1d`. Results in `evals/results/2026-11-03-ranked-report-re2ob-dev/`.
  - Pre-close check passed: rules v1 and v2 (`replay-20261103T145625Z-rules`, `-145633Z-`) equal `-133807Z-` and `-133811Z-` in every column but `seconds`, and `ranked` is empty on every rules row. AC3 holds.
  - DR `replay-20261103T145641Z-cloud`: 30 rows, all `COMPLETED`, the plan's configuration, DW's recordings; about $0.03. AC5 holds.

| Config | AC@1 [Wilson 95%] | AC@3 | Avg@5 | Mean list |
|---|---|---|---|---|
| DR | 26/30, 87% [70%, 95%] | 87% | 87% | 2.60 |
| DW | 25/30, 83% [66%, 93%] | 83% | 83% | 0.90 |
| 0R | 20/30, 67% [49%, 81%] | 80% | 76% | 1.27 |
| BARO | 4/30, 13% [5%, 30%] | 87% | 76% | 5.00 |
| chance, n = 11 | 9% | 27% | 27% | |

  - **The plan's rule is met, so the ranked report is kept:** against DW, DR wins 2 and loses 1 (losses minus wins −1, at most 2 allowed), and has no `INVALID_REPORT`.
  - DR − 0R: +20 points, 10 against 4, p = 0.18. Not a criterion here; still not a paired win.
  - **The alternatives cannot be read on this run:** DR answers 26 cases and gets all 26 right, with a mean of 2.0 alternatives each, and abstains on 4. With no wrong first answer, AC@3 and Avg@5 equal AC@1, so the descriptive question (is the truth among the alternatives?) has no case to answer it.
  - **A cost the plan did not count: 7 of 30 reports needed the retry**, and all 7 passed on it. 4 wrote alternatives with both causes `UNKNOWN`, 2 named a service twice, and 1 was cut at the 768-token cap. Their completion tokens reach 1,355, over the cap, because both attempts count. On the cloud that costs cents. On the local route a retry is a second report of about 15 minutes, so A's next run may run longer and cut more under its 512-token cap.
  - The 4 "alternatives with `UNKNOWN`" retries show the model wanting to abstain while still naming suspects. ADR-025 forbids it, so that the list never ranks below an abstention. Whether to allow it is a design question for the human, not a fix to fit these cases.

- 2026-11-03: the human chose to allow suspects with an abstention, if a measurement shows no harm: ADR-025's amendment and `evals/plans/2026-11-03-abstain-suspects-re2ob-dev.md`, branch `feat/ranked-report-02-abstain-suspects`. Tasks:
- [x] T6 Drop the rule from `ReportCheck` and the prompt, tests first; pins; spec. Route: delegated writer.
- [x] T7 DS's run, the rules check, the score and the result. Route: inline commands.
- 2026-11-03: T6 done by a delegated writer (`4d8ea5c`). RED 3 failed; GREEN `./mvnw -q test` 714 run, 0 failures. 82 authored lines. The hybrid's text and schema pins pass unchanged; the plain instruction pin changes only by the sentence.
- 2026-11-03: T7 done on `4d8ea5c`. Results in `evals/results/2026-11-03-abstain-suspects-re2ob-dev/`. Rules v1 and v2 (`replay-20261103T150856Z-rules`, `-150901Z-`) equal `-145625Z-` and `-145633Z-` in every column but `seconds`. DS `replay-20261103T150905Z-cloud`: 30 rows, all `COMPLETED`, DR's configuration and recordings; about $0.03.

| Config | AC@1 [Wilson 95%] | AC@3 [Wilson 95%] | Avg@5 | Abstentions | Retries |
|---|---|---|---|---|---|
| DR | 26/30, 87% [70%, 95%] | 26/30, 87% [70%, 95%] | 87% | 4 | 7 |
| **DS** | **26/30, 87% [70%, 95%]** | **27/30, 90% [74%, 97%]** | 89% | 3 | 3 |

  - **The plan's rule holds, so the amendment is kept:** against DR, 2 won and 2 lost, so losses minus wins 0, at most 2 allowed; 3 abstentions, at most 6; no `INVALID_REPORT`.
  - **Retries fell from 7 to 3,** all 3 for a service named twice; none for a cut.
  - **All 3 abstentions list suspects, and the truth is among them in all 3.** The scorer gives those rows an empty ranking, so this changes no score. It is what Config C's escalation could use.
  - **The first readable alternative:** DS's one wrong first answer has the truth second, so AC@3 is 27/30.
  - DS − 0R: +20 points, 9 against 3, p = 0.15, not a paired win.

- 2026-11-03: native review of `2188964..` the close: one reliability lens, **approved** and acknowledged (lineage `review-1191ba92423bf0d9`). SUGGESTION R3-scorer-abstain-ranking-unpinned: already covered outside that candidate, by T3's `test_a_row_that_abstains_has_an_empty_ranking_even_with_ranked` (`evals/tests/test_scoring.py`:440).

## Next step

The feature is complete. A's next run must check the retry and cut rates under 512 tokens.
