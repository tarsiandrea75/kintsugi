# Plan: abstain-suspects-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/ranked-report.md`, T6 and T7. Report: ADR-025, amendment of 2026-11-03.

## Status

Exploratory: the tenth read of RE2-OB dev by D. Committed before the change and the run.

What is already known: DR, the ranked report, 26/30, 4 abstentions, 7 retries of which 4 for alternatives with an abstention (`evals/results/2026-11-03-ranked-report-re2ob-dev/`). DW 25/30. The largest net difference between identical runs of D is 2 cases.

## Date and commit

- Run on: the commit that adds T6's change on `feat/ranked-report-02-abstain-suspects`, or a later one that changes nothing under `agent/` or `evals/kintsugi_evals/`.

## Hypothesis

Allowing suspects with an abstention removes those retries and does not make D abstain more or answer worse.

## Cases and split

RE2-OB dev, repetition 1, 30 cases (`evals/out/rcaeval/re2-ob-rep1-resources`). No holdout.

## Configs

| Label | Config | How | Source |
|---|---|---|---|
| 0, 0R | rules v1, v2 | - | `evals/results/2026-11-03-ranked-report-re2ob-dev/inputs/` |
| DR | D, the ranked report | - | same directory, `DR-summary.tsv` |
| DS | D, suspects allowed with an abstention: route `cloud`, mode `decision`, view `tables`, report `plain`, `claude-haiku-5-5` | `replay-agent.sh` | a fresh run |

Cost: about $0.03.

## Metric

- AC@1 on the service with Wilson 95% CIs, coverage and selective accuracy; AC@3 and Avg@5 with chance at n = 11.
- Abstentions; reports that needed the retry, by message; `INVALID_REPORT`.
- Descriptive: on DS's abstentions, how many list suspects, and whether the truth is among them.

## Pre-close check

Rules v1 and v2 on RE2-OB dev equal `replay-20261103T145625Z-rules` and `-145633Z-` in every column but `seconds`.

## Decision rule

- A DS run that is not 30 rows, `COMPLETED` or `INVALID_REPORT`, with the configuration above and DR's recordings, is not scored, and the human decides. A rules difference stops T7.
- **The amendment is kept** if all hold:
  - DS's AC@1 against DR: losses minus wins at most 2.
  - DS's abstentions at most DR's plus 2, so at most 6.
  - At most 1 `INVALID_REPORT`.
- Otherwise the amendment is reverted, as ADR-025 says, and the result is recorded.

## Command

From the repository root, then `evals/`:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain

cd evals
R=results/2026-11-03-ranked-report-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv --label DR $R/DR-summary.tsv \
  --label DS ../infra/scripts/out/<DS run> \
  --out results/2026-11-03-abstain-suspects-re2ob-dev
```
