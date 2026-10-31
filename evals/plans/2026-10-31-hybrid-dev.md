# Plan: hybrid-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/hybrid.md`, T7. Config: ADR-019. Model: ADR-018.

## Status

Pre-registered: committed before any H run on dev.

What is already known:
- Dev has been read many times (M0, V1/V2, Config 0, `qwen-dev`), so this is a dev result and makes no claim.
- The rules' candidates on these 15 cases are known from the T4 smoke (`replay-20261031T131039Z-rules`): the truth is in every list (recall 15/15). On F2 and F3 the list has one entry; on F1 the truth is first, followed by `payment-service` and once also `order-service`.
- The only H runs before this plan are the `stress` calibration (T6), whose verdicts were not read.

## Date and commit

- Written: 2026-10-31
- Run on commit: the commit that adds this plan on `feat/hybrid`, or a later one that changes nothing under `agent/src/main/`, `infra/scripts/replay-agent.sh` or `infra/scripts/lib.sh`. It must be the same agent code as the calibration run (`518c3d9`).

## Calibration (stress split, before this plan)

`replay-20261031T131815Z-local`: H with Qon, cap 4096, deadline 2700 s, 3 recordings. Each needed one report call: 732 s, 749 s and 1561 s; completion 304-365 tokens as Ollama reports them. The settings this fixes:

- Report cap 4096: it does not bind.
- Report deadline 2100 s: 1561 s × 1.35 for a slower day (the factor of `evals/plans/2026-10-30-qwen-dev.md`), rounded up. A second attempt fits the 45 min incident budget only after a first attempt under 600 s.

## Hypothesis

Mechanics check, descriptive. On the dev cases, does H run end to end, does Qon pick from the list rather than answering UNKNOWN or an invalid report, and on F1 does it keep the rules' first candidate rather than the caller?

## Cases and split

- Split: `dev`.
- Cases: the 15 decision recordings `verify-agent-20261024T162555Z-r{1,3,5,7,9}-F{1,2,3}`, the same as M0, Config 0 and `qwen-dev`. One sample each.
- First read of this holdout: no holdout is read.
- Rerun rule: a run that stops before 15 samples, or fails before writing them, is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun, whatever its timeouts.

## Configs

All in decision mode, `tables` view.

| Label | Model | Thinking | Report | Report cap | Report deadline | Source |
|---|---|---|---|---|---|---|
| 0 | rules (ADR-016) | - | plain | - | - | `evals/results/2026-10-30-config0-dev/inputs/0-summary.tsv` |
| Qon | `qwen3.5:9b` | on | plain | 4096 | 1200 s | `evals/results/2026-10-30-qwen-dev/inputs/Qon-summary.tsv` |
| H | `qwen3.5:9b` | on | hybrid | 4096 | 2100 s | a fresh run |

H's deadline is longer than Qon's. Qon's dev samples had no timeout (longest sample 1252 s), so the difference cannot have cost Qon a case, but it is stated, not hidden. Config 0's input has no `candidates` column, so the plan reads the rules' recall from H's own rows.

Cost: about 4 to 8 h of CPU; no API calls.

## Metric

- AC@1 per fault and pooled with Wilson 95% CIs; coverage and selective accuracy; H's candidate recall.
- Paired, every pair (the scorer's Holm adjustment over all of them): discordant cases, exact McNemar, cluster bootstrap.
- Descriptive:
  - per-fault net of H against 0 and against Qon;
  - how often H answered UNKNOWN or `INVALID_REPORT`;
  - on F1, how often H kept the first candidate;
  - report time per sample and the share of samples with a second report attempt.

## Decision rule

None on the configs: the stop rule (vision §7) names RCAEval dev, and this run cannot meet it. The run passes as a mechanics check when it writes 15 samples and no sample ends `FAILED`. Any other outcome is recorded in the feature document and decides nothing about H.

## Command

From the repository root, then from `evals/`:

```
infra/scripts/replay-agent.sh --route local --mode decision --split dev --view tables --report hybrid \
  --model qwen3.5:9b --think on --report-cap 4096 --report-timeout 2100

python3 -m kintsugi_evals score \
  --label 0 results/2026-10-30-config0-dev/inputs/0-summary.tsv \
  --label Qon results/2026-10-30-qwen-dev/inputs/Qon-summary.tsv \
  --label H ../infra/scripts/out/<H run> \
  --out results/2026-10-31-hybrid-dev
```

Check the fresh `summary.tsv` first: 15 samples, split `dev`, view `tables`, report `hybrid`, model `qwen3.5:9b`, think `on`, cap 4096, deadline 2100 s.
