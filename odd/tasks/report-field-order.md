# Feature: the report's reasoning comes before its answer

## Objective

Put `summary` and `evidence` before `rootCauseService`, `rootCauseCategory` and `confidence` in the report schema and the report task, so the model writes its reasoning before it commits to a cause.

## Problem

In 3 of the 5 cloud F3 misses with line citations, the summary names the right cause (order-service's own payment URL) while the fields name payment-service (`odd/tasks/evidence-by-reference.md`, 2026-10-23). The quote-based misses of 2026-10-20 show the same mode. The fields come first today (`rootCauseService, rootCauseCategory, summary, confidence, evidence`), and a model writes left to right: it chooses the cause before it has written the reasoning that leads to it.

## Why

The order of a structured answer is part of the prompt: fields written earlier condition the later ones, never the reverse. Reasoning first, answer last, is a structural change, not a wording change, and it is measured on many samples on the replay.

## Scope

In: `Schemas.report` (property and `required` order), `Prompts.REPORT_TASK` (the field order it lists), `ReportCheck.MODEL_FIELDS` (the order the report is stored in), their tests, the spec's "The report". Out: the checks themselves, the fields' rules, the evidence contract.

## Acceptance criteria

1. The report schema lists its properties, and its `required` array, in the order `summary, evidence, rootCauseService, rootCauseCategory, confidence`.
2. The report task describes the fields in that order.
3. The spec's "The report" table and a scenario state the order and why.
4. Measured on the cloud replay, F3 15 samples (about $0.09, the human's go-ahead given on 2026-10-23): F3 at least as good as 10/15 with line citations, aimed at the 13-14/15 of the quote baselines. F1 and F2 on the cloud need their own go-ahead. Then the local replay.

## Tasks

- [x] T1 Order in the schema, the report task and the stored report, with tests and the spec (route: inline; mechanical edits in known places).
- [ ] T2 Measurement: the cloud replay, then the local replay.

## Progress

- 2026-10-23: branch `feat/report-field-order`, from `feat/evidence-by-reference` (closed at `1811640`).

- 2026-10-23: T1 done (route: inline). `ReportCheck.MODEL_FIELDS` is `summary, evidence, rootCauseService, rootCauseCategory, confidence` and `Schemas.report` builds its properties and `required` from it; the report task lists the fields in that order, the cause "as your summary concludes"; spec "The report" table, paragraph and scenario. RED: `theSchemaPutsTheReasoningBeforeTheCause` and `theReportTaskAsksForTheReasoningBeforeTheCause` failed; GREEN: full suite passed, 0 failures.

- T1 review (`4585c0c`, base `1811640`, consent granted): approved; WARNING R3-stored-order-unproved: the scenario said the stored report keeps the order, but `incidents.report` is jsonb, which does not keep key order. The line is removed from the scenario.

- 2026-10-23: T2, first cloud run, F3 15 samples (`infra/scripts/out/replay-20261023T125404Z-cloud`, on `d396ec4`, **cost $0.095**; cloud spend total about $1.16 of $5). **F3 8/15, and every valid report matched (8/8)**; the 7 misses are `INVALID_REPORT`. 20 of 28 report attempts failed one rule, every time the same: `summary must be a string of 1 to 500 characters`. With the reasoning first and "your reasoning" in the task, summaries ran past 500 characters (the cloud schema cannot carry `maxLength`), and the retry error did not say by how much.
- T1b (route: inline): the task asks for "your reasoning in brief, at most 500 characters (about 80 words)", and the length error gives the summary's length (`…, not 612`). RED: `aSummaryTooLongIsRejectedWithItsLength` and the prompt test failed; GREEN: full suite passed.

- T1b review (`2f255ca`, base `d396ec4`, consent granted): approved; WARNING R3-length-unit-mismatch checked: the reported length and the rule both count code points (`Causes.string`), so they agree; SUGGESTION R3-suffix-branches-untested (a non-string summary gets no length) left as a follow-up.

- 2026-10-23: T2, second cloud run, F3 15 samples, with the human's go-ahead (`infra/scripts/out/replay-20261023T132036Z-cloud`, on `eadd1d4`, **cost $0.099**; cloud spend total about $1.26 of $5). **F3 10/15; every valid report matched again (10/10, 18/18 over both runs).** The 5 misses are `INVALID_REPORT`: 16 of 26 attempts had a summary over 500 characters (501-624), also on the retry that was told the length. The valid summaries were 363-496 characters. The model cannot count characters well enough to land under a hard limit it overshoots by 1-25%.

- 2026-10-23: T1c (route: inline), the human's choice: code cuts a summary over 500 characters to its first 499 and `…` instead of rejecting the report; `ReportCheck.Result` carries the stored summary, the verdict lists `summary cut from <n> to 500 characters`, and `AgentLoop.stored` stores the cut summary. An empty or non-string summary is still an error. The length suffix of T1b is gone (no longer reachable). RED: three `ReportCheckTest` tests and `aLongSummaryIsStoredCutAt500Characters` did not compile; GREEN: full suite passed.

## Next step

T2 again: the cloud replay, F3 15 samples (about $0.10), with the human's go-ahead; then F1 and F2 on the cloud, and the local replay.
