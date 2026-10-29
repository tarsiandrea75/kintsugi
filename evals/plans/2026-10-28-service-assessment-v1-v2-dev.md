# Plan: service-assessment-v1-v2-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/service-assessment.md`, T4.

## Status

Pre-registered: committed before any of the runs below.

## Date and commit

- Written: 2026-10-28
- Run on commit: `ca2e555`, which fixes the assessed-report check in decision mode after this plan was written and before any run, or a later one on `feat/service-assessment-close` that changes nothing under `agent/src/main/`, `agent/src/test/java/dev/kintsugi/agent/loop/Replay*.java` or `infra/scripts/replay-agent.sh`. Each run records `git rev-parse HEAD` next to its output directory in the feature document.

## Hypothesis

Arranging the dossier per service, leaf first (V1), and also asking for each service's role checked against the call graph (V2), raises decision-mode AC@1 on `dev` over today's tables and plain report. The local route is the one expected to gain: M0 local was 5/15, cloud 14/15.

## Cases and split

- Split: `dev`
- Cases: the 15 decision recordings `verify-agent-20261024T162555Z-r{1,3,5,7,9}-F{1,2,3}`, the same as M0. One sample each (`--repeats 1`), so one outcome per case and config.
- First read of this holdout: no holdout is read. `holdout` is read once in follow-up feature 4, next to Config 0.

## Configs

All in decision mode, at the routes' committed settings (temperature 0 on the local route).

| Label | Config (vision §5) | Route, model | View | Report |
|---|---|---|---|---|
| A-M0 | A, the incumbent | local, `granite4.1:8b` | `tables` | `plain` |
| A-V1 | A | local, `granite4.1:8b` | `sheets` | `plain` |
| A-V2 | A | local, `granite4.1:8b` | `sheets` | `assessed` |
| D-M0 | D, the incumbent | cloud, `claude-haiku-5-5` | `tables` | `plain` |
| D-V1 | D | cloud, `claude-haiku-5-5` | `sheets` | `plain` |
| D-V2 | D | cloud, `claude-haiku-5-5` | `sheets` | `assessed` |

The incumbents are re-run on the same commit as the variants, so the three configs of a route share code and day. The committed M0 result (`evals/results/2026-10-25-m0-dev/`) is not a config here. Its agreement with the re-run is reported as a descriptive rerun check only.

Cost: about 2 h per local run (6 h in all) and about $0.05 per cloud run (15 samples each, well under the 60-sample cap). The cloud runs need the human's go-ahead.

## Metric

- AC@1 per fault (F1, F2, F3) and pooled over the 15 cases, each with a Wilson 95% CI.
- Abstentions (`UNKNOWN|UNKNOWN`) and coverage, reported, not decided on.
- Paired against the incumbent of the same route: the discordant cases each way, the exact two-sided McNemar test, and the per-fault net (cases the variant gets right and the incumbent misses, minus the reverse). The scorer's Holm adjustment over a route's three pairs is reported. The decision does not rest on it.

## Decision rule

Written before the numbers. The "net" of a variant is counted against the incumbent of its route, on the same 15 cases.

1. **Passes on a route**: ADR-015's dev keep rule, a pooled net gain of at least 3 cases and no fault class losing more than 1 case net.
2. **Eligible**: the variant passes on the local route, and on the cloud route no fault class loses more than 1 case net. The cloud incumbent's 14/15 leaves at most 1 case to gain, so only the keep rule's second clause applies there.
3. **Kept**: the eligible variant with the larger local pooled net gain. On a tie, V1 is kept, since it has fewer moving parts (no assessed schema, no graph checks).
4. **None eligible**: the defaults stay `tables` and `plain`. Under the stop rule (ADR-014, vision §5) the local miss is documented, not chased. The human decides which variant code is deleted.

A variant that passes on the cloud route alone is reported, and is not kept by this rule.

## Command

Replays, from the repository root, one run per config; each prints its output directory, `infra/scripts/out/replay-<UTC time>-<route>/`:

```
infra/scripts/replay-agent.sh --route local --mode decision --split dev --view tables --report plain
infra/scripts/replay-agent.sh --route local --mode decision --split dev --view sheets --report plain
infra/scripts/replay-agent.sh --route local --mode decision --split dev --view sheets --report assessed
infra/scripts/replay-agent.sh --route cloud --mode decision --split dev --view tables --report plain
infra/scripts/replay-agent.sh --route cloud --mode decision --split dev --view sheets --report plain
infra/scripts/replay-agent.sh --route cloud --mode decision --split dev --view sheets --report assessed
```

Check each `summary.tsv`: 15 lines, with the `view` and `report` columns as asked.

Scoring, from `evals/`, one result per route, the incumbent first:

```
python3 -m kintsugi_evals score \
  --label A-M0 ../infra/scripts/out/<local tables plain run> \
  --label A-V1 ../infra/scripts/out/<local sheets plain run> \
  --label A-V2 ../infra/scripts/out/<local sheets assessed run> \
  --out results/2026-10-28-service-assessment-v1-v2-dev/local

python3 -m kintsugi_evals score \
  --label D-M0 ../infra/scripts/out/<cloud tables plain run> \
  --label D-V1 ../infra/scripts/out/<cloud sheets plain run> \
  --label D-V2 ../infra/scripts/out/<cloud sheets assessed run> \
  --out results/2026-10-28-service-assessment-v1-v2-dev/cloud
```

The rerun check against M0, descriptive:

```
python3 -m kintsugi_evals score \
  --label A-M0-2026-10-25 results/2026-10-25-m0-dev/inputs/A-summary.tsv \
  --label A-M0 results/2026-10-28-service-assessment-v1-v2-dev/local/inputs/A-M0-summary.tsv
```

The per-fault nets of the decision rule are computed from the two results' `inputs/*-summary.tsv` case by case, and recorded in the feature document with the result's commit.
