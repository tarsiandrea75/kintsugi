# rcaeval-ranked-metrics

## Objective

Add AC@3 and Avg@5 to the scorer, on the RCAEval track only, with the published evaluator's semantics and the chance level next to them (vision §6), and score the existing RE2-OB dev inputs with them.

## Problem

BARO's AC@1 of 13% cannot be set against the published baselines, which report Avg@5. The truth is second in 22 of BARO's 26 misses, and AC@1 cannot see that.

## Why

AC@1 punishes a ranking for its first entry alone. Avg@5 rewards the truth anywhere in the top 5, so a method that lists every service scores above zero by construction. That is why the chance level has to sit next to the number: without it, a high Avg@5 says nothing.

## Scope

- `scoring.py`, `cli.py`, tests, `openspec/specs/evals/spec.md`.
- One plan and one result that rescore committed inputs. No model runs.

Out of scope: any holdout, repetitions 2 and 3, a `redis` filter, a ranked report from the LLM, the metric-level (fine-grained) evaluator numbers.

## Constraints

- Standard library only in the scorer (REQ-1).
- Output without the new flag stays byte-identical, so `benchmark.py` and every committed result are untouched.
- The plan is committed before the rescoring.

## Design

Semantics, read from `RCAEval/benchmark/evaluation.py`, `scoring.py` and `main.py` at commit `dc9f48786db5` (tag `1.11.0`):

- AC@k on the service: the mean over cases of "the truth's service is in `ranks[:k]`". An empty ranking counts in the denominator with no hit.
- Avg@k: the mean of AC@1 to AC@k.
- Duplicates: the service ranking keeps each service's first occurrence, before the cut at k.
- Chance: per case `min(k, n) / n` with `n` the candidate services; chance Avg@k is the mean of chance AC@1 to AC@k. For n = 11: AC@1 9.1%, AC@3 27.3%, Avg@5 27.3%.
- Aggregation: upstream prints Avg@5 per fault type (`overall_<fault>`), and each case's repetitions count as cases.

Choices here:

- **One rule for every route.** A row's ranking is its `reported` service followed by its `candidates` services, first occurrence kept, `UNKNOWN` dropped; a row that is not `COMPLETED` has an empty ranking. On rules and BARO rows `reported` is the first candidate, so the rule gives their list unchanged.
- **D and A rank one service.** Their summaries hold one answer, so their AC@3 and Avg@5 equal their AC@1. The table prints the mean list length so nobody reads that as a ranking's result.
- **Wilson CIs on AC@1, AC@3 and AC@5**, which are proportions. Avg@5 is a point value: it is a mean of nested proportions on the same cases, so a Wilson interval does not apply.
- **A new flag, `--topology PATH`, with `--service-only`.** It turns the metrics on and gives `n`. Without it nothing changes. The lab never passes it (vision §6: RCAEval only).
- **`ranked.tsv`**, a new file next to `scores.tsv`, so `scores.tsv`'s columns and `benchmark.py` stay as they are.

## Tasks

- [x] T1 The scorer's ranked metrics, tests first, and the living spec. Route: delegated writer (three non-trivial files).
- [x] T1b The review's two advisories, tests first. Route: inline (small, understood).
- [x] T2 The plan, with the published BARO numbers if a primary source gives them. Route: inline (docs).
- [x] T3 The rescoring and its result. Route: inline commands.

## Acceptance criteria

- AC1: on a synthetic case, AC@k, Avg@5 and chance match the values computed by hand from the semantics above, including an empty ranking and a repeated service.
- AC2: without `--topology`, the report and the output files are unchanged, and a test proves it.
- AC3: `--topology` without `--service-only` is refused with a message.
- AC4: `openspec/specs/evals/spec.md` holds the requirement with GIVEN/WHEN/THEN scenarios.
- AC5: the rescoring uses the committed inputs of 0, 0R, D, A2100 and BARO; the input copies' checksums match their source; AC@1 equals the committed numbers.
- AC6: no holdout is converted or opened.

## Checks

- T1: `cd evals && .venv/bin/python -m pytest -q`, red first.
- T2: structural readback.
- T3: AC5.

## Delivery

Forecast: about 300 authored lines for T1, about 100 for T2–T3, so about budget. Strategy `feature-branch-chain` if it goes over. Branch `feat/rcaeval-ranked-metrics`, from `feat/rcaeval-dev-baselines-01-baro`. Nothing is pushed.

## Progress

- 2026-11-02: the human chose this feature. Semantics read from the pinned source, not from a summary.
- 2026-11-02: T1 done by a delegated writer (`1674293`): REQ-13, `--topology`, `ranked.tsv`. RED 32 failed against stubs; GREEN 257 passed; parent spot check `pytest -q` 257 passed. 537 authored lines, over the forecast, mostly hand-computed test cases. Without the flag, the committed `baro-pipeline` result regenerates byte for byte. AC1–AC4 hold.
  - The writer's choices: `none` and empty services are dropped from a ranking, like `UNKNOWN`; the topology is not in `inputs.tsv`, because `benchmark.py` renders every row there as an input, so its path sits in the report's chance line; a topology with duplicate services is refused.
  - Gap found: a `COMPLETED` row reporting `UNKNOWN` with a non-empty candidate list abstains under REQ-7, but its first candidate counted as rank 1. Closed in T1b.
- 2026-11-02: native review of `3f25788..b468288`: one reliability lens, **approved** and acknowledged (lineage `review-204160b5b1ea3b3a`), with two WARNINGs: R3-abstention-ranked-ac1-divergence and R3-empty-topology-bypass.
- 2026-11-02: T1b done (`937a0de`): an abstaining row has an empty ranking, even with candidates; an empty `--topology` value is refused instead of silently dropping the metrics. RED 2 failed; GREEN 259 passed.
- 2026-11-02: T2 done (`0ff028c`). No published RE2-OB number found: the RCAEval paper's Table 6 and the upstream README give BARO's Avg@5 on Train Ticket only. The ASE and FSE papers were not read. The dev cases' headers hold 16 to 20 names, so the table's chance at n = 11 is the stricter floor for BARO.
- 2026-11-02: T3 done on `0ff028c`. Results in `evals/results/2026-11-02-ranked-re2ob-dev/`. The plan's stop rule did not fire: all five input checksums match, and ranked AC@1 equals the committed AC@1 on all 35 rows. AC5 and AC6 hold. Pooled, service-only, 30 cases, hits at k = 1 to 5, chance at n = 11 (AC@1 9%, AC@3 27%, AC@5 45%, Avg@5 27%):

| Config | Hits at k = 1..5 | AC@3 | AC@5 | Avg@5 |
|---|---|---|---|---|
| 0 | 4, 5, 5, 5, 5 | 17% [7%, 34%] | 17% [7%, 34%] | 16% |
| 0R | 20, 22, 24, 24, 24 | 80% [63%, 90%] | 80% [63%, 90%] | 76% |
| D (one answer) | 11, 11, 11, 11, 11 | 37% [22%, 54%] | 37% [22%, 54%] | 37% |
| A2100 (one answer) | 19, 19, 19, 19, 19 | 63% [46%, 78%] | 63% [46%, 78%] | 63% |
| BARO (5 services) | 4, 26, 26, 29, 29 | 87% [70%, 95%] | 97% [83%, 99%] | 76% |

  - **BARO and 0R tie on Avg@5 at 76%, by different routes:** 0R puts the truth first in 20 cases and has it nowhere in 6, while BARO puts it first in 4 and has it in the top 5 in 29. So BARO's AC@1 understated its ranking, and Avg@5 alone hides the difference.
  - 0's Avg@5 of 16% is under the 27% chance level because 19 of its lists are empty and an abstention counts as a miss, so it does not mean its answers are worse than random. D's and A's numbers measure one answer, not a ranking.
  - Not tested: no paired test on AC@3 or Avg@5. The tie is a description on 30 cases, not a result.

## Next step

The feature is complete. The human chooses the next one. Reproducing BARO's published Avg@5 on RE2-TT (`odd/tasks/vision-revision.md`) is the only way found to check the port against a published number.
