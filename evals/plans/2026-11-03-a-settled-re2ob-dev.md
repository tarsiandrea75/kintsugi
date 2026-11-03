# Plan: a-settled-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Deadline: ADR-023. Report: ADR-025 with its amendment.

## Status

Exploratory: the third read of RE2-OB dev by A, the first with today's agent: the latency pairs, the resource sentence, the ranked report and suspects with an abstention. Committed before the run.

What is already known: A at 2100 s with the old agent, 19/30, no abstention, median report 877 s, maximum 1,051 s (`evals/results/2026-11-02-a-deadline-re2ob-dev/`). D with today's agent, DS, 26/30.

## Date and commit

- Run on: the commit that adds this plan on `feat/a-settled-re2ob-dev`. Nothing under `agent/` changes during the run, and no other CPU-heavy job runs beside it.

## Hypothesis

Descriptive, three questions:

1. Does today's agent fit the local route: no timeouts, few cuts and retries under the 512-token cap?
2. What does A score with it, against A2100 and DS?
3. Does the ranked report give A lists that AC@3 and Avg@5 can read?

## Cases and split

RE2-OB dev, repetition 1, 30 cases (`evals/out/rcaeval/re2-ob-rep1-resources`). No holdout.

## Configs

| Label | Config | How | Source |
|---|---|---|---|
| 0, 0R, DS | as committed | - | `evals/results/2026-11-03-abstain-suspects-re2ob-dev/inputs/` |
| A2100 | A, the old agent | - | `evals/results/2026-11-02-a-deadline-re2ob-dev/inputs/A2100-summary.tsv` |
| AS | A with today's agent: route `local`, `granite4.1:8b`, mode `decision`, view `tables`, report `plain`, report timeout 2100 s, cap 512 | `replay-agent.sh` | a fresh run |

Cost: about 8 h of CPU, no model fees.

## Metric

- AC@1 on the service with Wilson 95% CIs, coverage and selective accuracy; AC@3 and Avg@5 with chance at n = 11.
- `TIMED_OUT`, `INVALID_REPORT`, reports that needed the retry by message, reports cut at 512 tokens.
- Report time per sample: median and maximum, counting both attempts when there is a retry.

## Decision rule

- A run that is not 30 rows with the configuration above and DS's recordings is not scored, and the human decides.
- **The deadline is revisited by the human** (ADR-023) if any sample ends `TIMED_OUT`, or the maximum report time passes 1,800 s, which leaves less than 300 s of margin.
- **The cap is revisited by the human** if more than 3 reports are cut at 512 tokens.
- Otherwise none: the result is recorded whatever it shows, and no setting changes on it.

## Command

From the repository root, then `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision \
  --view tables --report plain --report-timeout 2100

cd evals
R=results/2026-11-03-abstain-suspects-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv --label DS $R/DS-summary.tsv \
  --label A2100 results/2026-11-02-a-deadline-re2ob-dev/inputs/A2100-summary.tsv \
  --label AS ../infra/scripts/out/<AS run> \
  --out results/2026-11-03-a-settled-re2ob-dev
```
