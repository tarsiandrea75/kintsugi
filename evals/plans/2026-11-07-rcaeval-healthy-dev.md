# Plan: rcaeval-healthy-dev

## Status

Pre-registered. Committed before any pseudo-case is replayed. This run decides whether RCAEval healthy pseudo-cases (6 + 6 minutes before the injection) are usable for H2b. It does not test any hypothesis.

## Date and commit

- Written: 2026-11-07
- Run on commit: bfb46ff (replays), e8595a5 (scoring), 2026-11-07

## Hypothesis

None; this run is a gate. Its single output is "keep" or "drop" for the pseudo-cases, by the decision rule below.

## Cases and split

- Split: RCAEval RE2-OB dev (repetition 1).
- Cases: 30 pseudo-cases, one per dev fault case, built by `convert-rcaeval --healthy`.
  - The windows are `[inject − 12 m, inject − 6 m)` and `[inject − 6 m, inject)`.
  - The truth is `NONE|NONE`.
  - No data at or after `inject_time` is used.
- The dev fault cases were seen before, but their pre-injection minutes were never shown to any config.
- First read of a holdout: no.

## Configs

| Label | Config | Repeats |
|---|---|---|
| 0 | Config 0, rules v1 | 1 (deterministic) |
| 0R | Config 0R, rules v2 | 1 (deterministic) |
| D | `claude-haiku-5-5`, cloud route, decision mode | 3 |

A does not run here. Its CPU cost is decided after the gate.

## Metric

- The false-alarm rate per config is the share of runs naming a cause instead of `UNKNOWN|UNKNOWN`, with a Wilson 95% CI. D's rate is pooled over its 90 runs, and also reported per repeat.
- D's per-case consistency is reported: the count of cases with 0, 1, 2 or 3 false alarms.
- The cost of D's runs, from token counts at ADR-012's prices.

## Decision rule

Written before the numbers:

- **Keep** the pseudo-cases if D's pooled false-alarm rate is at most 1/3, i.e. at most 30 false alarms in 90 runs.
  - The reason: if the strongest config sees a fault in more than a third of healthy windows, the pseudo-cases measure window noise rather than a model's restraint.
  - A kept set then enters H2b, through an ADR and an amendment of `evals/plans/2026-11-06-h1-h3-verdicts.md`, before any holdout is read.
- **Drop** them otherwise. H2b then stays on lab holdout-2's 15 healthy controls, and an ADR records why vision §2's pseudo-cases are not used.
- Config 0 and 0R are reported and do not gate.

## Command

```
cd evals
python3 -m kintsugi_evals convert-rcaeval --system re2-ob --rep 1 --healthy --out out/rcaeval/re2-ob-rep1-healthy
cd ..
H=evals/out/rcaeval/re2-ob-rep1-healthy
REPLAY_DIR=$H infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1 --faults healthy
REPLAY_DIR=$H infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2 --faults healthy
REPLAY_DIR=$H infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --faults healthy --repeats 3
cd evals
python3 -m kintsugi_evals score --service-only --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 ../infra/scripts/out/<rules v1 run> --label 0R ../infra/scripts/out/<rules v2 run> \
  --label D ../infra/scripts/out/<cloud run> --seed 20261107 --out results/2026-11-07-rcaeval-healthy-dev
```

The exact flags follow the tools as built in T2. Any difference is recorded in the run's `notes.md`.
