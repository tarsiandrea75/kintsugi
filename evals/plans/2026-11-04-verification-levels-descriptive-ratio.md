# Plan: verification-levels-descriptive-ratio

An addendum to `evals/plans/2026-11-04-verification-levels-descriptive.md` (T6), which it follows in everything not stated here. Feature: `odd/tasks/verification-levels.md`, T7. Levels: ADR-028, Decision 5 as amended on 2026-11-04.

## Status

Descriptive. No model call, no cloud call, no lab, no Docker: the answers T6 re-checked are re-checked again with today's `ReportCheck`. Committed before the re-check is run.

What is already known: T6's levels on the same inputs (`evals/results/2026-11-04-verification-levels-descriptive/`): DS L0/L1/L2/L3 0/4/8/18, AS 1/10/9/10; 4 of DS's 5 correct `delay` answers at L2 under the latency rule's 0.5 s floor. The rule change was decided after seeing those numbers, so this report is post hoc on these 30 dev cases by construction.

## Date and commit

- Written: 2026-11-04
- Run on: the commit that removes the floor from `WindowChange.latency` (`fix(dossier): judge a latency change by its ratio alone`), or a later one that changes nothing under `agent/src/main/` or `evals/kintsugi_evals/`.

## The one change measured

A latency line fact is `changed` = `YES` when the incident mean is at least 2 × the previous mean, whatever its seconds; before, it also needed at least 0.5 s (R-DOWN's `SLOW_SECONDS`). The `NOT_COMPARED` cases are unchanged. Nothing else differs from T6: the same driver (`LevelRecheck`), script, inputs, recordings, topology, scorer and flags.

## Hypothesis

None (descriptive). Questions:

1. Per config and fault, how many answers move between levels, and between which?
2. What are Verified-correct and the L1 rate now, with Wilson 95% CIs, against T6's?
3. Is any answer that moved to L3 incorrect? Verified-correct must stay a subset of correct; an incorrect L3 answer would be reported, not hidden.

## Cases and split

T6's inputs exactly, dev only, no holdout: DS (`infra/scripts/out/replay-20261103T150905Z-cloud`, `summary.tsv` sha256 `4118f181...`), AS (`replay-20261103T151645Z-local`, `5d84b32f...`), both on `evals/out/rcaeval/re2-ob-rep1-resources`, and D-M0 (`replay-20261029T010641Z-cloud`, `908f6db0...`), which T6's guard excluded and is re-run only so that its exclusion is recorded again. Each local `summary.tsv` is matched by sha256 before the re-check.

## Configs

As T6: Config 0 and 0R alongside from `evals/results/2026-11-03-abstain-suspects-re2ob-dev/inputs/` (level `-` by construction; their verdicts do not depend on `WindowChange`, and `agent/src/main/java/dev/kintsugi/agent/rules` is not edited); DS and AS the re-checks.

## Method and guard

T6's method and both guards, unchanged: the re-rendered dossier message must equal the stored `DOSSIER` step byte for byte, and the re-check must verify exactly the stored evidence. An input with any refused incident is excluded whole. A latency rule change touches only `changed`, so the guards are expected to pass as in T6; if they do not, the run stops and is reported.

## Metric

T6's metrics, plus the comparison with T6: per config the level counts, Verified-correct and the L1 rate with Wilson CIs; the moves per sample (from T6's `levels-detail.tsv` level to this run's), counted per fault; the answers that moved to L3 and whether each is correct on the service.

## Decision rule

None: the result is recorded whatever it shows.

## Command

From the repository root, then `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob \
  infra/scripts/out/replay-20261103T150905Z-cloud
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob \
  infra/scripts/out/replay-20261103T151645Z-local
infra/scripts/recheck-levels.sh --dataset lab infra/scripts/out/replay-20261029T010641Z-cloud

cd evals
R=results/2026-11-03-abstain-suspects-re2ob-dev/inputs
.venv/bin/python -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv \
  --label DS ../infra/scripts/out/<DS re-check> --label AS ../infra/scripts/out/<AS re-check> \
  --out results/2026-11-04-verification-levels-ratio
```

## Results

`evals/results/2026-11-04-verification-levels-ratio/`, in T6's layout: the scorer's files, the re-checks' `levels-detail.tsv` and `recheck.txt`, and `notes.md` comparing with T6. T6's result directory is not touched.
