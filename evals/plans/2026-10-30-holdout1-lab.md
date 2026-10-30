# Plan: holdout1-lab

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/benchmark-v0.md`, T1. The M2 check of vision §7.

## Status

Pre-registered: committed before any holdout replay.

What is already known about these incidents: they were recorded live on the cloud route on 2026-10-24 (`verify-agent-20261024T162555Z`, even rounds). The live verdicts of that session are in `odd/tasks/decision-instrument.md`: on the holdout rounds, one F1 miss (r4, `payment-service|DEPENDENCY_LATENCY`) and every other case right. No holdout recording has been replayed by any config, and no config, prompt or rule was changed with these cases in view.

## Date and commit

- Written: 2026-10-30
- Run on commit: the commit that adds this plan on `feat/benchmark-v0`, or a later one that changes nothing under `agent/`, `infra/scripts/replay-agent.sh` or `infra/scripts/lib.sh`. The rules are frozen at `5797d16` (ADR-016).

## Hypothesis

Descriptive: the lab holdout-1 table for Configs 0, A and D. The H1–H3 claims of vision §1 are decided on RCAEval in M4, not here.

## Cases and split

- Split: `holdout` (holdout-1).
- Cases: the 15 decision recordings `verify-agent-20261024T162555Z-r{2,4,6,8,10}-F{1,2,3}`. One sample each.
- First read of this holdout: yes. Each config reads it once.
- Rerun rule: a run that stops before 15 samples, or fails before writing them, is discarded and rerun whole, on the same commit; the discarded directory is named in the feature document. A complete run is never rerun.

## Configs

All in decision mode with the `tables` view.

| Label | Config (vision §5) | Route, model | Report |
|---|---|---|---|
| 0 | 0, rules only (ADR-016) | `rules`, no model | `plain` |
| A | A | local, `granite4.1:8b`, temperature 0 | `plain` |
| D | D | cloud, `claude-haiku-5-5` | `plain` |

Cost: minutes for 0, about 1.5 h of CPU for A, about $0.05 for D.

## Metric

- AC@1 per fault (F1, F2, F3) and pooled over the 15 cases, each with a Wilson 95% CI.
- Abstentions (`UNKNOWN|UNKNOWN`) and coverage; invalid reports counted as misses.
- Paired, D − 0, A − 0 and D − A: the discordant cases each way, the exact two-sided McNemar test with the scorer's Holm adjustment, and the cluster bootstrap 95% interval of the difference.
- Descriptive: the cases where exactly one of 0 and A is right, and the oracle AC@1 of the better of 0 and A per case, an upper bound for a hybrid.

## Decision rule

None. The table is the M2 deliverable and is reported whatever it shows. No config is kept, dropped or changed by this result.

## Command

From the repository root, then from `evals/`:

```
infra/scripts/replay-agent.sh --route rules --mode decision --split holdout
infra/scripts/replay-agent.sh --route cloud --mode decision --split holdout --view tables --report plain
infra/scripts/replay-agent.sh --route local --mode decision --split holdout --view tables --report plain

python3 -m kintsugi_evals score \
  --label 0 ../infra/scripts/out/<rules run> \
  --label A ../infra/scripts/out/<local run> \
  --label D ../infra/scripts/out/<cloud run> \
  --out results/2026-10-30-holdout1-lab
```

Check each `summary.tsv` first: 15 samples, split `holdout`, view `tables`, report `plain`.
