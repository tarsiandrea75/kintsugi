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
- [ ] T5b A at a 2100 s deadline, under `evals/plans/2026-11-02-a-deadline-re2ob-dev.md` (the human's choice, 2026-11-02). Route: inline commands.
- [ ] T6 ADR for BARO: pinned package or port, after checking its current version. Route: inline (docs).
- [ ] T7 BARO in `evals/`, tests first, on synthetic fixtures; `openspec/specs/evals/spec.md` updated. Route: delegated writer.
- [ ] T8 BARO's plan, run and result. Route: inline commands.

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

## Next step

T5b's plan is committed. Its run takes about 12 to 17.5 h of CPU and waits for the human's go-ahead. BARO (T6–T8) does not depend on it.
