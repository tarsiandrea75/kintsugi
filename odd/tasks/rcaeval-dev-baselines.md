# rcaeval-dev-baselines

## Objective

Complete vision M3's "RCAEval dev for 0, 0R, A, D and BARO": measure D, A and BARO on RE2-OB dev next to the rules, after moving H1's baseline to Config 0R (ADR-022).

## Problem

- **Only the rules and the hybrid have RCAEval numbers.** `evals/results/` holds 0, 0R, H and HR on RE2-OB dev, and nothing for D, A or BARO.
- **H1 compared D with rules blind to the dossier's resources.** Config 0 has no rule that reads them.
- **BARO has no code here.** The offline track promises it as the published baseline, and ADR-016 deferred it to RCAEval.

## Why

Without D and A on dev, the first LLM number on RCAEval would be the holdout's, with no mechanics check before a read that happens once. Without BARO, the rules and the LLM have no external reference on the same cases.

## Scope

- ADR-022 and the vision amendment.
- One plan and one result for 0, 0R, D and A.
- An ADR, the code, the living spec, a plan and a result for BARO.

Out of scope: any holdout (the freeze check forbids a read before M4), config C's threshold, new rules, and settings changes made after seeing these results.

## Constraints

- Plans are committed before their runs; Wilson 95% CIs; results under `evals/results/`.
- D's run is paid: it needs the human's go-ahead (`infra/scripts/replay-agent.sh`).
- A runs alone on the CPU, overnight.
- The freeze is on 2026-12-01.

## Design

- **BARO, to decide in T6's ADR.** `RCAEval.e2e.baro` 1.11.0 (MIT, checked 2026-11-02) is short: per column, a `RobustScaler` fitted on the pre-injection samples, and the column's score is its largest scaled post-injection value. The package's `core` extra pulls `causal-learn`, `tigramite` and others, for one function. T6 weighs a pinned install against a small port, and checks the `preprocess` semantics either way.
- BARO reads the case files directly, with the 10-minute windows on each side of `inject_time` (vision §2), and its ranking maps metric columns to services.

## Tasks

- [x] T1 ADR-022 and the vision amendment. Route: inline (docs; the human approved the amendment on 2026-11-02). Passive docs, structural readback.
- [x] T2 The plan for 0, 0R, D and A (`evals/plans/2026-11-02-baselines-re2ob-dev.md`). Route: inline (docs). Passive docs, structural readback.
- [x] T3 Fresh 0 and 0R runs; verdicts equal to the earlier ones. Route: inline commands.
- [x] T4 D's run, after the human's go-ahead. Route: inline commands.
- [x] T5 A's overnight run; the scored result committed. Route: inline commands.
- [x] T5b A at a 2100 s deadline, under `evals/plans/2026-11-02-a-deadline-re2ob-dev.md` (the human's choice, 2026-11-02). Route: inline commands.
- [x] T6 ADR for BARO: pinned package or port, after checking its current version. Route: inline (docs).
- [x] T7 BARO in `evals/`, tests first, on synthetic fixtures; `openspec/specs/evals/spec.md` updated. Route: delegated writer.
- [x] T8 BARO's plan, run and result. Route: inline commands.
- [x] T9 The port gains the published pipeline's preprocessing (ADR-024, revised; the human's choice, 2026-11-02); goldens and `compare_dev.py` follow, with its three review advisories fixed. Route: delegated writer.
- [x] T10 BARO's new plan, run and result. Route: inline commands.

## Acceptance criteria

- AC1: H1 in the vision names D − 0R as primary, and ADR-022 is committed before any D or A run on RCAEval.
- AC2: every run's plan is committed first; the git history shows it.
- AC3: 0's and 0R's fresh verdicts equal those of `evals/results/2026-11-01-config0r-re2ob-dev/`.
- AC4: D, A and 0R are scored on the same 30 cases, with Wilson CIs and paired differences, under `evals/results/`.
- AC5: no holdout is converted or opened.
- AC6: BARO's ranking on a synthetic case matches the rule stated in its ADR, and its living spec is updated.

## Checks

- T1, T2: structural readback.
- T3–T5, T8: the plan's mechanics checks.
- T7: `evals/` tests, red first.

## Delivery

Forecast: about 150 authored lines of docs and plans for T1–T2, about 300 for T6–T7, so about 450 in all, over budget. Strategy `feature-branch-chain` (standing preference): `feat/rcaeval-dev-baselines` for T1–T5, then `feat/rcaeval-dev-baselines-01-baro` for T6–T8. Nothing is pushed.

## Progress

- 2026-11-02: T1–T2 done (`49f7978`, `4d34cfd`). Native assessment of the range: passive, no review.
- 2026-11-02: T3 done on `4d34cfd`. Runs `replay-20261102T003836Z-rules` (v1) and `replay-20261102T003846Z-rules` (v2): 30 samples each, all `COMPLETED`, `unrecorded` 0, coverage 1.00. Every column but `seconds` equals `evals/results/2026-11-01-config0r-re2ob-dev/inputs/`, and `recordings.tsv` is identical. AC3 holds.

- 2026-11-02: T4 done on `a1fc1b0`. Run `replay-20261102T024016Z-cloud`: 30 samples, all `COMPLETED`, `unrecorded` 0, coverage 1.00; cost $0.0325; median 2.9 s per sample. An interim score, not committed until T5 adds A: D 11/30, 37% [22%, 54%], coverage 43%, selective 85%. D − 0R −30 points, 4 cases for D and 13 for 0R, exact McNemar p = 0.049. D's median prompt is 8,383 tokens on the cloud's count, above A's `num-ctx` of 8192, so T5 must check for cut prompts.

- 2026-11-02: T5 done on `3d8fb7a`. Run `replay-20261102T024308Z-local`: 30 samples, all `TIMED_OUT` at the 600 s deadline, about 5 h. A 0/30, 0% [0%, 11%], coverage 0%. Not rerun, as the plan fixes. Results in `evals/results/2026-11-02-baselines-re2ob-dev/` (AC4 holds): 0 4/30, 0R 20/30, D 11/30, A 0/30; D − 0R −30 points, p = 0.049, Holm 0.12; A − 0R −67 points, Holm 1.1e-05.
  - The 0/30 measures the deadline, not A's diagnosis: no sample produced an answer. The likely cause, not proved: on the lab, A's median was 560 s on a median prompt of 3,240 tokens, and here the prompt is about 2.5 times that size (D's median is 8,383 tokens). Ollama was up and the model loaded after the run.
  - The plan's cut-prompt check could not be made: timed-out samples record no `promptTokens`.
- 2026-11-02: T5b done on `f38c92a`, exploratory. Run `replay-20261102T114407Z-local`: 30 samples, all `COMPLETED`, `unrecorded` 0, coverage 1.00, 7.3 h, no timeout. Results in `evals/results/2026-11-02-a-deadline-re2ob-dev/`. A2100 19/30, 63% [46%, 78%], coverage 100%, selective 63%; A2100 − 0R −3 points (6 for A, 7 for 0R, p = 1); A2100 − D +27 points (10 for A, 2 for D, p = 0.039, Holm 0.19).
  - The plan's rule: 30 of 30 finished, at least 15, so 2100 s is the proposal for M4. Report time: median 877 s (14.6 minutes), maximum 1,051 s. At that median, 200 holdout cases take about 49 h of CPU.
  - Prompt check: 29 samples at most 5,828 tokens; one at 10,877, the only one at 8,000 or more, read as two attempts because it is about twice the others (not separately proved). No sign of a cut.
  - A never abstains: its 11 misses are wrong answers, 10 of them `frontend` or `checkoutservice`. 0R and A together are right on 26 of 30, and each has cases the other misses.
- 2026-11-02: native review of `3d8fb7a..d1177db`: one reliability lens, **approved** and acknowledged (lineage `review-e6c6ac93b3616320`).
- 2026-11-02: the human accepted 2100 s; ADR-023 (`6216e1a`). T6 done: ADR-024 (`371b783`), then corrected after T7 (the scale threshold, no abstention, the unseen published pipeline).
- 2026-11-02: T7 done by a delegated writer (`ad6a75d`) on `feat/rcaeval-dev-baselines-01-baro`: `baro-rcaeval`, REQ-12, goldens from `RCAEval` 1.11.0 on synthetic series. RED `ImportError` at collection; GREEN 44 passed in `tests/test_baro.py`; parent spot check `pytest -q` 182 passed. About 800 authored lines without generated fixtures, over the heuristic, mostly the generator and the tests. Not run on real cases. AC6 holds.
- 2026-11-02: native review of `d1177db..c1fef5c`: one reliability lens, **approved** and acknowledged (lineage `review-f4eaf6994b368dbe`). Advisory, not fixed: R3-rep-guard-unproved WARNING at `evals/tests/test_baro.py:209`, the test of a repetition that does not exist. The holdout refusal has its own test.
- 2026-11-02: T8 done under `evals/plans/2026-11-02-baro-re2ob-dev.md` (`15863e3`). Conformance: the port's service and column rankings equal the pinned original's on all 30 cases (`inputs/conformance.tsv`). Results in `evals/results/2026-11-02-baro-re2ob-dev/`: BARO 7/30, 23% [12%, 41%], 22 of its 23 misses name `redis`. BARO − 0R −43 points (3 for BARO, 16 for 0R, Holm 0.031); BARO − A2100 −40 (Holm 0.015).
  - **This is not the published baseline.** Read after the run: upstream's `main.py` fills gaps and drops `_latency-50` before calling `baro` (ADR-024, Consequences), and the port equals the function alone.
  - Without that fill, 6 cases have `NaN` scores, and on 5 of them a `NaN` column sits first because of how `sorted` treats it. The first non-`NaN` column would give 5/30 instead of 7/30 (exploratory, not in the plan). `conformance.tsv`'s score difference prints `nan` on those rows; `compare_dev.py` does not separate both-`NaN` from one-`NaN`, though the equal rankings bound the effect.
- 2026-11-02: native review of `c1fef5c..bc3d80e`: one reliability lens, **approved** and acknowledged (lineage `review-6c329853c3a8a04d`). Advisory on `compare_dev.py`, not fixed: R3-conformance-gate-vacuous-pass WARNING (no case gives exit 0; this run had 30), R3-nan-max-order-dependent WARNING (the score difference on `NaN`), R3-refusal-contract-not-implemented SUGGESTION (the docstring promises a refusal comparison the code does not make). To fix with the pipeline change.

- 2026-11-02: T9 done by a delegated writer (`d3df17f`): the port follows `main.py` of tag `1.11.0`; goldens regenerated; the three `compare_dev.py` advisories fixed. RED 45 failed; GREEN 86 passed in `tests/test_baro.py`, 25 of 25 mutants killed; parent spot check `pytest -q` 224 passed. About 676 authored lines without fixtures, over the heuristic, mostly tests and golden scenarios. The dev cases' `time` columns have a 1 s step and 720 and 721 rows around the injection, so 600 rows are 600 s.
- 2026-11-02: T10 done under `evals/plans/2026-11-02-baro-pipeline-re2ob-dev.md` (`4d12c2b`). Conformance: 30 of 30 compared, same service and column rankings, every score within 1e-9, no `NaN`. Results in `evals/results/2026-11-02-baro-pipeline-re2ob-dev/`: **BARO 4/30, 13% [5%, 30%]**, which replaces T8's 7/30 as M3's number. BARO − 0R −53 points (3 for BARO, 19 for 0R, Holm 0.01); BARO − A2100 −50 (Holm 0.00085); BARO − 0 +0.
  - All 26 misses name `redis`, with `redis_diskio` the top column. The truth is second in 22 of them. `redis` is in `topology/re2-ob.json`, so the other configs may name it too.
  - Exploratory, outside the plan: without `redis` BARO would score 26/30, and its Avg@5 by a quick calculation with all services is 0.76, not the published evaluator's. No `redis` filter is adopted: it would be fitted on dev. AC@3 and Avg@5 are not in the scorer yet, and vision §6 asks for them to compare with the published baselines.
  - Vision §7's pre-close check was not run: this branch changes nothing under `agent/`, and T3 ran the rules on RE2-OB.

## Next step

The feature is complete. The human chooses the next one.
