# Plan: baro-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/rcaeval-dev-baselines.md`, T8. Port: ADR-024. Data: ADR-020.

## Status

Pre-registered: committed before BARO's first run on any real RCAEval case. Descriptive: dev decides nothing about H1.

What is already known: 0 4/30, 0R 20/30, D 11/30, A at 2100 s 19/30 on these cases, case by case (`evals/results/2026-11-02-a-deadline-re2ob-dev/`). The port has seen only synthetic series. BARO has no tunable setting here, so this result can change nothing in it.

## Date and commit

- Written: 2026-11-02
- Run on commit: the commit that adds this plan on `feat/rcaeval-dev-baselines-01-baro`, or a later one that changes nothing under `evals/kintsugi_evals/` or `evals/tests/fixtures/baro/`.

## Hypothesis

Descriptive. Expected, not tested: BARO does well on faults that move a service's own metric (cpu, mem), and worse where a fault shows mainly in callers' latency, since it has no topology.

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one deterministic answer each.
- First read of a holdout: none. `baro-rcaeval` refuses any other repetition.

## Configs

| Label | Config (vision §5) | How | Source |
|---|---|---|---|
| 0, 0R, D, A2100 | as in the previous plans | - | `evals/results/2026-11-02-a-deadline-re2ob-dev/inputs/` |
| BARO | BARO, the port of `RCAEval` 1.11.0 (ADR-024) | `baro-rcaeval`, ±600 s window, no model | a fresh run |

Cost: minutes of CPU, no model calls.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with a Wilson 95% CI.
- Paired, as the scorer reports them, with BARO's differences against 0R, A2100, D and 0 the ones read.
- **Conformance first:** the port's and the pinned original's rankings on all 30 cases (`evals/tests/fixtures/baro/compare_dev.py`), committed as `inputs/conformance.tsv`.
- Descriptive: which services BARO's misses name, including `frontend-external` and `redis`, which are not lab-topology services and count as wrong answers.

## Decision rule

- **A service ranking that differs on any case stops T8:** no score is committed until the difference is explained, and the port or ADR-024 is corrected under a new commit of this plan. A column-order difference with equal service rankings is recorded, not blocking.
- A case the port refuses fails the run. The human decides then, as ADR-024 says.
- Otherwise none: the result is committed and recorded whatever it shows. It is not comparable with RCAEval's published numbers, because the published pipeline's feeding of `baro` was not seen (ADR-024, Consequences).

## Command

From the repository root, with `<venv>` as `evals/tests/fixtures/baro/generate_goldens.py` describes:

```
<venv>/bin/python -I evals/tests/fixtures/baro/compare_dev.py ~/data/rcaeval/RE2-OB <conformance.tsv>

cd evals
python3 -m kintsugi_evals baro-rcaeval --system re2-ob --rep 1 --out out/rcaeval/baro-re2-ob-rep1
R=results/2026-11-02-a-deadline-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --label 0 $R/0-summary.tsv \
  --label 0R $R/0R-summary.tsv \
  --label D $R/D-summary.tsv \
  --label A2100 $R/A2100-summary.tsv \
  --label BARO out/rcaeval/baro-re2-ob-rep1 \
  --out results/2026-11-02-baro-re2ob-dev
```

Check first that the case ids pair: the fresh `summary.tsv`'s `recording` column equals the one in `$R/0R-summary.tsv` as a set.
