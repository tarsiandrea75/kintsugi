# Plan: baro-pipeline-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/rcaeval-dev-baselines.md`, T10. Port: ADR-024, revised. Data: ADR-020.

## Status

Second read of RE2-OB dev for BARO, so exploratory in that sense. Committed before the run. The change is not tuned on the first result: it follows `main.py` of tag `1.11.0`, a pipeline fixed by others, and has no setting of ours.

What is already known: the function alone scored 7/30, with 22 of 23 misses naming `redis` (`evals/results/2026-11-02-baro-re2ob-dev/`); 0 4/30, 0R 20/30, D 11/30, A at 2100 s 19/30, case by case.

## Date and commit

- Written: 2026-11-02
- Run on commit: the commit that adds this plan on `feat/rcaeval-dev-baselines-01-baro`, or a later one that changes nothing under `evals/kintsugi_evals/` or `evals/tests/fixtures/baro/`.

## Hypothesis

Descriptive. Does the published preprocessing change BARO's answers on these cases? `redis` columns stay in the pipeline, so its misses may well persist. If they do, that is the published method's behaviour on this data as far as this pipeline defines it.

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one deterministic answer each.
- First read of a holdout: none. `baro-rcaeval` refuses any other repetition.

## Configs

| Label | Config (vision §5) | How | Source |
|---|---|---|---|
| 0, 0R, D, A2100 | as in the previous plans | - | `evals/results/2026-11-02-a-deadline-re2ob-dev/inputs/` |
| BAROfn | the function alone | - | `evals/results/2026-11-02-baro-re2ob-dev/inputs/BARO-summary.tsv` |
| BARO | BARO, the port of `RCAEval` 1.11.0 with its pipeline (ADR-024) | `baro-rcaeval`, no model | a fresh run |

Cost: minutes of CPU, no model calls.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with a Wilson 95% CI.
- Paired, as the scorer reports them, with BARO's differences against 0R, A2100, D, 0 and BAROfn the ones read.
- **Conformance first:** the port's rankings against the pinned original through the copied pipeline, on all 30 cases (`compare_dev.py`), committed as `inputs/conformance.tsv`.
- Descriptive: which services BARO's misses name; `NaN` scores, expected none after the fill.

## Decision rule

- **`compare_dev.py` exiting 1 stops T10:** a differing service ranking, a score beyond tolerance, a one-sided refusal or a column ranked by one side. No score is committed until it is explained. A column swap with equal services is recorded, not blocking.
- A case the port refuses or cannot rank fails the run, and the human decides then. `compare_dev.py` counts a case both sides rank as empty as equal, so it does not rule this out.
- Otherwise none: the result is committed and recorded whatever it shows, and it replaces BAROfn as M3's BARO number. It is not tuned further on dev. It is comparable with published numbers only on the same metric: the paper's are Avg@5 over all repetitions, and this is AC@1 on repetition 1.

## Command

From the repository root, with `<venv>` as `evals/tests/fixtures/baro/generate_goldens.py` describes:

```
<venv>/bin/python -I evals/tests/fixtures/baro/compare_dev.py ~/data/rcaeval/RE2-OB <conformance.tsv>

cd evals
python3 -m kintsugi_evals baro-rcaeval --system re2-ob --rep 1 --out out/rcaeval/baro-pipeline-re2-ob-rep1
R=results/2026-11-02-a-deadline-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --label 0 $R/0-summary.tsv \
  --label 0R $R/0R-summary.tsv \
  --label D $R/D-summary.tsv \
  --label A2100 $R/A2100-summary.tsv \
  --label BAROfn results/2026-11-02-baro-re2ob-dev/inputs/BARO-summary.tsv \
  --label BARO out/rcaeval/baro-pipeline-re2-ob-rep1 \
  --out results/2026-11-02-baro-pipeline-re2ob-dev
```

Check first that the case ids pair: the fresh `summary.tsv`'s `recording` column equals the one in `$R/0R-summary.tsv` as a set.
