# Plan: resource-warrant-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/resource-warrant.md`, T2 and T4. Data: ADR-020.

## Status

Exploratory: three more reads of RE2-OB dev by D. Committed before the runs and before the sentence is written into the prompt.

What is already known: D1–D4, the old table, 11 to 13 of 30; DP1 (`replay-20261103T114659Z-cloud`), the latency pairs, 16/30; 0R 20/30. The noise floor between identical runs is 6 discordant cases.

## Date and commit

- DP2 and DP3: on the commit that adds this plan on `feat/resource-warrant`, before T3.
- DW: on the commit that adds T3's sentence, or a later one that changes nothing under `agent/` or `evals/kintsugi_evals/`.

## Hypothesis

With a warrant for resource evidence, D names the service on resource cases it now dismisses, and answers more cases right.

## Cases and split

- RE2-OB dev, repetition 1, 30 cases (`evals/out/rcaeval/re2-ob-rep1-resources`). No holdout.

## Configs

| Label | Config | How | Source |
|---|---|---|---|
| 0, 0R | rules v1, v2 | - | `evals/results/2026-11-03-d-repeats-re2ob-dev/inputs/` |
| DP1 | D, latency pairs | - | `evals/results/2026-11-03-latency-pairs-re2ob-dev/inputs/DP-summary.tsv` |
| DP2, DP3 | D, latency pairs, unchanged | `replay-agent.sh` | fresh runs, before T3 |
| DW | D, latency pairs and the resource sentence | `replay-agent.sh` | a fresh run, after T3 |

All D runs: route `cloud`, mode `decision`, view `tables`, report `plain`, `claude-haiku-5-5`. Cost about $0.10.

## Metric

- AC@1 on the service, Wilson 95% CIs, coverage and selective accuracy.
- **The gain rule:** DW counts as a gain only if, against **each** of DP1–DP3 separately, it wins at least 7 more cases than it loses, and its paired test against 0R is won (exact McNemar p < 0.05, DW ahead).
- Descriptive: DP1–DP3's spread, which re-measures the noise with the pairs; on the 20 cpu, mem, disk and socket cases, DW's abstentions and right answers against DP1–DP3's.
- Descriptive, to read the risk: how many of DW's right answers on resource cases are cases 0R also gets right, and how many 0R misses.

## Pre-close check

Rules v1 and v2 on RE2-OB dev after T3, every column but `seconds` equal to `replay-20261103T114625Z-rules` and `-114630Z-`. The rules read no prompt, so a difference is a defect.

## Decision rule

- A DP2 or DP3 run, or DW, that is not 30 rows, `COMPLETED`, with the configuration above and DP1's recordings, is not scored, and the human decides.
- A rules difference stops T4.
- Otherwise the gain rule decides whether the sentence is **kept as a gain**. If it is not, the human decides whether to keep it, revert it, or neither yet. No further change to D on these 30 cases without its own plan.

## Command

From the repository root, then `evals/`:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources
# T2, before the sentence:
for i in 2 3; do infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain; done
# T4, after it:
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain

cd evals
R=results/2026-11-03-d-repeats-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv \
  --label DP1 results/2026-11-03-latency-pairs-re2ob-dev/inputs/DP-summary.tsv \
  --label DP2 ../infra/scripts/out/<DP2> --label DP3 ../infra/scripts/out/<DP3> \
  --label DW ../infra/scripts/out/<DW> \
  --out results/2026-11-03-resource-warrant-re2ob-dev
```
