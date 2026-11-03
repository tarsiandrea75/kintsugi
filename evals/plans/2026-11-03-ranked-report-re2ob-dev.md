# Plan: ranked-report-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/ranked-report.md`, T5. Report: ADR-025. Metrics: REQ-13.

## Status

Exploratory: the ninth read of RE2-OB dev by D, the first with the ranked report. Committed before the run.

What is already known: DW, with the pairs and the resource sentence, 25/30 in one run (`evals/results/2026-11-03-resource-warrant-re2ob-dev/`); DP1–DP3 15 to 17; 0R 20/30. Between identical runs of D, the largest net difference so far is 2 cases (D1–D4: 11 to 13; DP1–DP3: 15 to 17).

## Date and commit

- Run on: the commit that adds this plan on `feat/ranked-report-01-scorer`, or a later one that changes nothing under `agent/` or `evals/kintsugi_evals/`.

## Hypothesis

Two questions:

1. **Non-inferiority:** asking for alternatives does not cost D its first answer.
2. **Descriptive:** when D's first answer is wrong, is the truth among its alternatives?

## Cases and split

RE2-OB dev, repetition 1, 30 cases (`evals/out/rcaeval/re2-ob-rep1-resources`). No holdout.

## Configs

| Label | Config | How | Source |
|---|---|---|---|
| 0, 0R | rules v1, v2 | - | `evals/results/2026-11-03-resource-warrant-re2ob-dev/inputs/` |
| DW | D with the pairs and the resource sentence | - | same directory, `DW-summary.tsv` |
| BARO | the port with its published pipeline | - | `evals/results/2026-11-02-baro-pipeline-re2ob-dev/inputs/BARO-summary.tsv` |
| DR | D with the ranked report: route `cloud`, mode `decision`, view `tables`, report `plain`, `claude-haiku-5-5` | `replay-agent.sh` | a fresh run |

Cost: about $0.03.

## Metric

- AC@1 on the service with Wilson 95% CIs, coverage and selective accuracy.
- REQ-13's AC@3, AC@5 and Avg@5, with chance at n = 11 (`--topology`).
- `INVALID_REPORT` count and the share of DR's answering reports with at least one alternative.
- Descriptive: of DR's wrong first answers, how many have the truth among the alternatives, and at which place.

## Pre-close check

Rules v1 and v2 on RE2-OB dev, every column but `seconds` and the new `ranked` equal to `replay-20261103T133807Z-rules` and `-133811Z-`, and `ranked` empty on every rules row.

## Decision rule

- A DR run that is not 30 rows, `COMPLETED` or `INVALID_REPORT`, with the configuration above and DW's recordings, is not scored, and the human decides. A rules difference stops T5.
- **The ranked report is kept** if both hold:
  - DR's AC@1 against DW: losses minus wins at most 2, the largest net difference seen between identical runs.
  - At most 1 `INVALID_REPORT`.
- Otherwise the result is recorded as it is and the human decides.
- No AC@3 or Avg@5 threshold: they are reported, not tested.

## Command

From the repository root, then `evals/`:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain

cd evals
R=results/2026-11-03-resource-warrant-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv --label DW $R/DW-summary.tsv \
  --label BARO results/2026-11-02-baro-pipeline-re2ob-dev/inputs/BARO-summary.tsv \
  --label DR ../infra/scripts/out/<DR run> \
  --out results/2026-11-03-ranked-report-re2ob-dev
```
