# Plan: latency-pairs-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/latency-pairs.md`, T4. Data: ADR-020.

## Status

Exploratory: the fifth read of RE2-OB dev by D, the first with the changed dossier. Committed before the runs. The change was chosen by the rule in `evals/plans/2026-11-03-d-repeats-re2ob-dev.md`, and the gain rule below was fixed in `odd/tasks/d-abstention.md` before this plan.

What is already known: D1–D4 give 11, 12, 12, 13 of 30, with the old table; 0R 20/30; the noise floor is 6 discordant cases.

## Date and commit

- Written: 2026-11-03
- Run on commit: the commit that adds this plan on `feat/latency-pairs`, or a later one that changes nothing under `agent/` or `evals/kintsugi_evals/`.

## Hypothesis

With each latency value next to its pair, D cites only previous values less often and abstains less, so it answers more cases right.

## Cases and split

- RE2-OB dev, repetition 1, 30 cases (`evals/out/rcaeval/re2-ob-rep1-resources`).
- The lab's dev and holdout recordings for the rules check only. They are read by rules, no model, as `resource-facts` T6 did.

## Configs

| Label | Config | How | Source |
|---|---|---|---|
| 0, 0R | rules v1, v2 | - | `evals/results/2026-11-03-d-repeats-re2ob-dev/inputs/` |
| D1–D4 | D with the old table | - | same directory |
| DP | D with the latency pairs: route `cloud`, mode `decision`, view `tables`, report `plain`, `claude-haiku-5-5` | `replay-agent.sh` | a fresh run |

Cost: about $0.03.

## Pre-close check (vision §6)

Rules v1 and v2, fresh, on RE2-OB dev, lab dev and lab holdout. Every column but `seconds` must equal these runs: `replay-20261102T003836Z-rules` (v1) and `-003846Z-` (v2) for RE2-OB, `replay-20261101T154902Z-` and `-154909Z-` for lab dev, `-154916Z-` and `-154923Z-` for lab holdout, all under `infra/scripts/out/`. The rules read the typed dossier, never its text (`RulesDiagnosis.java`:30), so a difference would be a defect. The 30-case D run stands in for the 10-case replay.

## Metric

- AC@1 on the service, Wilson 95% CIs, coverage and selective accuracy.
- **The gain rule:** DP counts as a gain only if, against **each** of D1–D4 separately, it wins at least 7 more cases than it loses, and its paired test against 0R is won (exact McNemar p < 0.05, DP ahead).
- REQ-14's audit on DP, against D1–D4: the share of abstaining and of answering samples with at least one `previous_only` item, counted per sample from `audit.tsv`.
- Descriptive: how many of the 13 cases that abstained in all of D1–D4 DP answers, and how many it gets right.

## Decision rule

- A rules difference stops T4 before any D run.
- A DP run that is not 30 rows, `COMPLETED`, with the configuration above and D1's recordings, is not scored, and the human decides.
- Otherwise: the gain rule decides whether the layout is **kept as a gain**. If not, the result is recorded as it is, and the human decides whether to keep the layout anyway: it changes no rule and costs nothing at run time. In every case, no further change to D is tried on these 30 cases before its own plan.

## Command

From the repository root, then `evals/`:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2
unset REPLAY_DIR
for s in dev holdout; do for v in v1 v2; do
  infra/scripts/replay-agent.sh --route rules --mode decision --split $s --rules $v
done; done
# compare with the runs above, then:
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain

cd evals
R=results/2026-11-03-d-repeats-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv \
  --label D1 $R/D1-summary.tsv --label D2 $R/D2-summary.tsv --label D3 $R/D3-summary.tsv --label D4 $R/D4-summary.tsv \
  --label DP ../infra/scripts/out/<DP run> \
  --out results/2026-11-03-latency-pairs-re2ob-dev
python3 -m kintsugi_evals audit-latency --label DP ../infra/scripts/out/<DP run> \
  --out results/2026-11-03-latency-pairs-re2ob-dev/audit
```
