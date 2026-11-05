# Results: decoy-noise-re2ob-dev

Plan: [`evals/plans/2026-11-05-decoy-noise-re2ob-dev.md`](../../plans/2026-11-05-decoy-noise-re2ob-dev.md).

- Committed as `4e8cda9` at 21:27:40Z, and amended in `9849aa7` at 21:34:35Z, before any sample was drawn.
- The first sampling run started at 21:34:44Z.
- Before that, two launches of DS′ stopped before any call:
  - the cloud key was missing in the worktree;
  - 90 samples are over the cloud cap.

## Runs

| Config | Repeat | Run | Service AC@1 | Abstentions | Decoy as cause or alternative | Status | Cost |
|---|---|---|---|---|---|---|---|
| DS′ (no decoys, worktree at `9aded9d`) | 1 | `replay-20261105T213444Z-cloud` | 23/30 | 5 | 0 or 0 | 30 `COMPLETED` | $0.0399 |
| DK′ (decoys) | 1 | `replay-20261105T213702Z-cloud` | 23/30 | 6 | 0 or 2 | 30 `COMPLETED` | $0.0390 |
| DS′ | 2 | `replay-20261105T213913Z-cloud` | 24/30 | 4 | 0 or 0 | 29 `COMPLETED`, 1 `INVALID_REPORT` | $0.0435 |
| DK′ | 2 | `replay-20261105T214134Z-cloud` | 19/30 | 9 | 0 or 1 | 30 `COMPLETED` | $0.0411 |
| DS′ | 3 | `replay-20261105T214402Z-cloud` | 24/30 | 6 | 0 or 0 | 30 `COMPLETED` | $0.0370 |
| DK′ | 3 | `replay-20261105T214612Z-cloud` | 21/30 | 7 | 1 or 1 | 30 `COMPLETED` | $0.0413 |

- **Cost:** $0.242 in all, within the approved $0.24 estimate and the $0.40 bound. The first pair cost $0.079, under the $0.13 check.
- **Configuration:** every run is `claude-haiku-5-5`, report `plain`, view `tables`, cap 768.
- **Inputs:**
  - `inputs/DS2-summary.tsv` and `inputs/DK2-summary.tsv` are the three runs of each config, concatenated in run order, 90 rows each.
  - 0 and 0R are the inputs of `2026-11-05-decoy-categories-re2ob-dev`, checked unchanged that day.
- **The baseline worktree** differed from the branch only by `aa25d35` in `agent/src/main` (`Causes.java`, `Prompts.java`). It was removed after the runs.

## Numbers (service AC@1, 90 samples, Wilson 95%)

| Config | AC@1 | Coverage | Selective | Abstentions | Verified-correct |
|---|---|---|---|---|---|
| DS′ | 71/90, 79% [69%, 86%] | 82% | 96% | 15/90 | 68/89, 76% [67%, 84%] |
| DK′ | 63/90, 70% [60%, 78%] | 76% | 93% | 22/90 | 60/90, 67% [56%, 76%] |

- **DK′ − DS′:** −8.9 points, cluster bootstrap 95% [−17.8, 0.0] (seed 1, 10,000 resamples, the case as the unit).
  - The upper bound is 0 to within floating-point error: the computed value is −1.9 × 10⁻¹⁸, and the bootstrap's values are multiples of 1/90.
- **The spread across repeats:** DS′ 23, 24, 24; DK′ 23, 19, 21. In each pair, DK′ is equal to or below DS′.
- **The L1 rate** is 100% for both configs.
- **Decoys:** DK′ names a decoy as its cause once in 90 samples, and as an alternative in 4.

## The decision rule

The plan, fixed before the runs, says that a CI wholly below 0 means the decoys cost AC@1, and that a CI including 0 means no evidence of a cost at this sample size.

- **The CI's upper bound is exactly 0.** It includes 0, so the rule's outcome is **no evidence of a cost**, and the recommendation is to keep the decoys.
- **What the data lean to.** The result sits on the boundary, and everything points the same way:
  - the point estimate is −9 points;
  - DK′ is at or below DS′ in every pair;
  - DK′ abstains 22 times against 15.

  The likely mechanism is that a wider taxonomy makes the model abstain more. It almost never picks a decoy.
- **The first pair, for context, not pooled:** DS 26/30 and DK 21/30. DS′ at 23–24 shows that 26 was a high draw.

## Caveats

- These are dev cases, seen before, and the question is post hoc: it was asked after the first pair.
- With 30 cases × 3 repeats, a cost of a few points cannot be told from zero.
- The cost would fall on any measurement scored with the decoys, holdout-2 included, so the decision is the human's before the freeze.
