# Plan: <name>

Copy this file to `evals/plans/<date>-<name>.md` and commit it before the run (ADR-015).

## Status

Pre-registered | Retrospective: written after the run, so exploratory

## Date and commit

- Written: <YYYY-MM-DD>
- Run on commit: <sha>

## Hypothesis

<One falsifiable sentence, or "exploratory">

## Cases and split

- Split: <dev | holdout-1 | holdout-2 | RCAEval split>
- Cases: <which recordings, how many, how many repetitions each>
- First read of this holdout: <yes | no, so exploratory>

## Configs

- <label>: <config of vision §5, route, mode, view, model and settings>

## Metric

<AC@1 per fault and pooled, Wilson 95% CIs; paired difference; false alarms; ...>

## Decision rule

<Written before the numbers: e.g. the dev keep rule, or the H1-H3 rule of vision §1, with the test and its threshold>

## Command

```
python -m kintsugi_evals score --label <A> <dir> --label <D> <dir> --out results/<date>-<name>
```
