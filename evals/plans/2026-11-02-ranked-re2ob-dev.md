# Plan: ranked-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/rcaeval-ranked-metrics.md`, T3. Metrics: REQ-13. Data: ADR-020.

## Status

A rescoring of committed inputs, so exploratory: no model runs and no new read of any case. The metric follows the evaluator of `RCAEval` at commit `dc9f48786db5` (tag `1.11.0`) and has no setting of ours, so there is nothing to tune on it.

What is already known: every config's AC@1 (`evals/results/2026-11-02-baro-pipeline-re2ob-dev/`). T1's smoke check also printed BARO's ranked line before this plan: hits 4, 26, 26, 29, 29 of 30 at k = 1 to 5, Avg@5 76%.

## Date and commit

- Written: 2026-11-02
- Run on commit: the commit that adds this plan on `feat/rcaeval-ranked-metrics`, or a later one that changes nothing under `evals/kintsugi_evals/`.

## Hypothesis

Descriptive. Where does the truth sit in each config's list, and how far is that from chance?

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases.
- First read of a holdout: none.

## Configs

| Label | Config (vision §5) | List | Source |
|---|---|---|---|
| 0 | rules v1 | 0 to 3 candidates | `evals/results/2026-11-02-baro-pipeline-re2ob-dev/inputs/0-summary.tsv` |
| 0R | rules v2 | 0 to 3 candidates | same directory, `0R-summary.tsv` |
| D | cloud | one answer | same directory, `D-summary.tsv` |
| A2100 | local at 2100 s | one answer | same directory, `A2100-summary.tsv` |
| BARO | the port with its published pipeline | 5 services | same directory, `BARO-summary.tsv` |

Cost: seconds of CPU, no model calls.

## Metric

- AC@1, AC@3, AC@5 with Wilson 95% CIs, and Avg@5 as a point value, on the service, per fault type and pooled (REQ-13), with the mean list length.
- Chance at n = 11, the services of `topology/re2-ob.json`, the set the rules and the agent choose from: AC@1 9.09%, AC@3 27.27%, AC@5 45.45%, Avg@5 27.27%.

## How to read it

- **D and A2100 name one service**, so their AC@3, AC@5 and Avg@5 equal their AC@1 by construction. Avg@5 favours a long list, and that is the metric's design, not a win over them. The rules list at most 3, which caps them the same way.
- **BARO's chance is lower than the table's.** The dev cases' metric headers hold 16 to 20 names, counting `frontend-external`, `loadgenerator` and the cluster nodes, before the pipeline's cuts; the `n` upstream records was not computed here. At n = 16 to 20 a random Avg@5 is 15% to 19%. The table's 27% is the stricter floor for BARO.
- **No published number on RE2-OB to compare with.** The RCAEval paper's Table 6 (`arxiv.org/html/2412.17015`) and the upstream README give BARO's Avg@5 on Train Ticket only, so these results are not set against them. Upstream also counts all repetitions and these are repetition 1 only.

## Decision rule

- **A config's ranked AC@1 differing from its committed AC@1, or an input copy's checksum differing from its source, stops T3:** no score is committed until it is explained.
- Otherwise none: the result is committed and recorded whatever it shows. No setting follows from it.

## Command

From `evals/`:

```
R=results/2026-11-02-baro-pipeline-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $R/0-summary.tsv \
  --label 0R $R/0R-summary.tsv \
  --label D $R/D-summary.tsv \
  --label A2100 $R/A2100-summary.tsv \
  --label BARO $R/BARO-summary.tsv \
  --out results/2026-11-02-ranked-re2ob-dev
```
