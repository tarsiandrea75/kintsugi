# Plan: qwen-holdout1

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/qwen-dev.md`, T7. Model choice: ADR-017.

## Status

Pre-registered: committed before Qon reads any holdout recording. Qon earned this read under the decision rule of `evals/plans/2026-10-30-qwen-dev.md` (dev 8/15 against A's 5/15, no fault class below −1 net).

What is already known: the holdout-1 verdicts of Configs 0, A and D (`evals/results/2026-10-30-holdout1-lab/`, `docs/benchmark.md`), and the live cloud verdicts of the recording session. Qon's settings (model, thinking, cap 4096, deadline 1200 s) were fixed by the `stress` calibration and the dev plan before any of this; nothing about Qon is changed after seeing the holdout results of other configs.

## Date and commit

- Written: 2026-10-31
- Run on commit: the commit that adds this plan on `feat/qwen-dev`, or a later one that changes nothing under `agent/src/main/`, `infra/scripts/replay-agent.sh` or `infra/scripts/lib.sh`. It must be the same agent code as the dev run (`5a5f9ca`).

## Hypothesis

Descriptive: Qon's lab holdout-1 row beside 0, A and D. No H1–H3 verdict (those need RCAEval, M4).

## Cases and split

- Split: `holdout` (holdout-1).
- Cases: the 15 decision recordings `verify-agent-20261024T162555Z-r{2,4,6,8,10}-F{1,2,3}`. One sample each.
- First read of this holdout by Qon: yes, once.
- Rerun rule: a run that stops before 15 samples, or fails before writing them, is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun, whatever its timeouts.

## Configs

All in decision mode, `tables` view, `plain` report.

| Label | Model | Thinking | Report cap | Report deadline | Source |
|---|---|---|---|---|---|
| 0 | rules (ADR-016) | - | - | - | `evals/results/2026-10-30-holdout1-lab/inputs/0-summary.tsv` |
| A | `granite4.1:8b` | default | 512 | 600 s | `evals/results/2026-10-30-holdout1-lab/inputs/A-summary.tsv` |
| D | `claude-haiku-5-5` | off | 768 | 90 s | `evals/results/2026-10-30-holdout1-lab/inputs/D-summary.tsv` |
| Qon | `qwen3.5:9b` | on | 4096 | 1200 s | a fresh run |

Cost: about 4.5 h of CPU; no API calls.

## Metric

- AC@1 per fault and pooled with Wilson 95% CIs; coverage and selective accuracy.
- Paired, every pair (the scorer's Holm adjustment over all of them): discordant cases, exact McNemar, cluster bootstrap.
- Descriptive: the per-fault net of Qon against A and against 0; the oracle AC@1 of 0 and Qon per case.

## Decision rule

None. The row is added to `docs/benchmark.md` whatever it shows, by regenerating it from `results/2026-10-30-holdout1-lab` and `results/2026-10-31-qwen-holdout1`.

## Command

From the repository root, then from `evals/`:

```
infra/scripts/replay-agent.sh --route local --mode decision --split holdout --view tables --report plain \
  --model qwen3.5:9b --think on --report-cap 4096 --report-timeout 1200

python3 -m kintsugi_evals score \
  --label 0 results/2026-10-30-holdout1-lab/inputs/0-summary.tsv \
  --label A results/2026-10-30-holdout1-lab/inputs/A-summary.tsv \
  --label D results/2026-10-30-holdout1-lab/inputs/D-summary.tsv \
  --label Qon ../infra/scripts/out/<Qon run> \
  --out results/2026-10-31-qwen-holdout1

python3 -m kintsugi_evals benchmark --out ../docs/benchmark.md \
  results/2026-10-30-holdout1-lab results/2026-10-31-qwen-holdout1
```

Check the fresh `summary.tsv` first: 15 samples, split `holdout`, view `tables`, report `plain`, model `qwen3.5:9b`, think `on`, cap 4096, deadline 1200 s.
