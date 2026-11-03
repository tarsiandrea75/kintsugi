# Plan: d-repeats-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/d-abstention.md`, T3. Data: ADR-020.

## Status

Exploratory. Three more reads of RE2-OB dev by D, with nothing changed, to measure how much D's answers move between runs of the same setup. Committed before the runs.

What is already known: D's first run, `replay-20261102T024016Z-cloud`, gave 11/30 with 17 abstentions. The diagnosis of 2026-11-03 read its reports (`odd/tasks/d-abstention.md`, Problem).

## Date and commit

- Written: 2026-11-03
- Run on commit: the commit that adds this plan on `feat/d-abstention`, or a later one that changes nothing under `agent/` or `evals/kintsugi_evals/`.

## Hypothesis

Descriptive, two questions:

1. **Noise:** how many cases change between two runs of D with nothing changed? The cloud route sends no temperature, so the API's default applies.
2. **Misread:** how often does D cite only previous values of a latency row (REQ-14), in abstaining and in answering reports?

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, the converted recordings in `evals/out/rcaeval/re2-ob-rep1-resources`.
- First read of a holdout: none.

## Configs

| Label | Config (vision §5) | How | Source |
|---|---|---|---|
| 0 | rules v1 | - | `evals/results/2026-11-02-baro-pipeline-re2ob-dev/inputs/0-summary.tsv` |
| 0R | rules v2 | - | same directory, `0R-summary.tsv` |
| D1 | D, the first run | - | same directory, `D-summary.tsv` |
| D2, D3, D4 | D, unchanged: route `cloud`, mode `decision`, view `tables`, report `plain`, `claude-haiku-5-5` | `replay-agent.sh` | three fresh runs |

Cost: about $0.10 in all, approved by the human on 2026-11-03. Minutes per run.

## Metric

- AC@1 on the service, per run, with a Wilson 95% CI; coverage and selective accuracy, as the scorer prints them.
- **Discordant cases** between each pair of D runs, right against not right, with the exact McNemar p the scorer prints. The largest discordant count over the six D pairs is **the noise floor**.
- Per case over D1–D4: how many runs abstain (0 to 4), and how many are right.
- REQ-14's audit on D1–D4: items per class, abstaining and answering samples apart, and every `previous_only` item listed.

## Decision rule

Mechanics: each fresh `summary.tsv` has 30 rows, split `dev`, the configuration above, and the same `recording` set as D1. A run that fails these is not scored, and the human decides.

Otherwise descriptive; no setting changes here. The result fixes two rules for the next feature, written now so they are not chosen after seeing it:

- **A change to D counts only if its gain over the D runs here exceeds the noise floor**, case for case, on top of the paired test against 0R that the stop rule requires.
- **The table change goes first if `previous_only` items appear in at least a third of D's abstaining samples over D1–D4.** Otherwise the prompt change goes first.

## Command

From the repository root, then from `evals/`:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources
for i in 1 2 3; do
  infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain
done

cd evals
R=results/2026-11-02-baro-pipeline-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv --label D1 $R/D-summary.tsv \
  --label D2 ../infra/scripts/out/<run 1> --label D3 ../infra/scripts/out/<run 2> --label D4 ../infra/scripts/out/<run 3> \
  --out results/2026-11-03-d-repeats-re2ob-dev
python3 -m kintsugi_evals audit-latency \
  --label D1 ../infra/scripts/out/replay-20261102T024016Z-cloud \
  --label D2 ../infra/scripts/out/<run 1> --label D3 ../infra/scripts/out/<run 2> --label D4 ../infra/scripts/out/<run 3> \
  --out results/2026-11-03-d-repeats-re2ob-dev/audit
```
