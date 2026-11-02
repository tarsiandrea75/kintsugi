# Plan: a-deadline-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/rcaeval-dev-baselines.md`, T5b. Data: ADR-020. Baseline: ADR-022.

## Status

Exploratory: A's deadline is changed after seeing A's result on the same split. Committed before the run.

What is already known (`evals/results/2026-11-02-baselines-re2ob-dev/`): at the 600 s deadline, all 30 A samples timed out, so A has no answer on any case. Its 0/30 measures the deadline, not the diagnosis. 0 4/30, 0R 20/30 and D 11/30 are known case by case.

## Date and commit

- Written: 2026-11-02
- Run on commit: the commit that adds this plan on `feat/rcaeval-dev-baselines`, or a later one that changes nothing under `agent/src/main/`, `agent/src/test/java/dev/kintsugi/agent/loop/Replay*.java`, `evals/kintsugi_evals/`, `infra/scripts/replay-agent.sh` or `infra/scripts/lib.sh`.
- Recordings: `evals/out/rcaeval/re2-ob-rep1-resources/`; its `recordings.tsv` must equal `evals/results/2026-11-02-baselines-re2ob-dev/inputs/recordings.tsv`.

## Hypothesis

Descriptive. With a 2100 s deadline, does A answer at all on RE2-OB, and how accurate is it when it does? 2100 s is H's and HR's deadline on these cases (`evals/plans/2026-11-01-hybrid-re2ob-dev.md`), chosen so A's time limit does not differ from the other local config's. No other setting changes.

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one sample each.
- First read of a holdout: none.
- Rerun rule: a run that stops before 30 samples, or fails before writing them, is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun, whatever its timeouts.

## Configs

Decision mode, `tables` view, `plain` report.

| Label | Config (vision §5) | Route, model | Settings | Source |
|---|---|---|---|---|
| 0 | 0 | `rules --rules v1` | - | `evals/results/2026-11-02-baselines-re2ob-dev/inputs/0-summary.tsv` |
| 0R | 0R | `rules --rules v2` | - | `.../inputs/0R-summary.tsv` |
| D | D | cloud, `claude-haiku-5-5` | route defaults | `.../inputs/D-summary.tsv` |
| A600 | A | local, `granite4.1:8b`, temperature 0 | cap 512, deadline 600 s, `num-ctx` 8192 | `.../inputs/A-summary.tsv` |
| A2100 | A | local, `granite4.1:8b`, temperature 0 | cap 512, deadline 2100 s, `num-ctx` 8192 | a fresh run |

Cost: about 12 h of CPU if A's time grows with its prompt (about 25 minutes a sample, extrapolated from the lab), and 17.5 h if every sample reaches the deadline. No API calls. It runs alone on the CPU.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with Wilson 95% CIs; coverage and selective accuracy; timeouts, abstentions and invalid reports.
- Paired, as the scorer reports them, with A2100's differences against 0R, D and 0 the ones read.
- Report time per sample: median and maximum, against H2's limit of 15 minutes (vision §1). H2 stays as written.
- Prompt size: `promptTokens` per completed sample. Ollama reports what it evaluated, so a prompt it cut shows up at or just below `num-ctx`, not above it. Any one-attempt sample at 8,000 tokens or more is recorded as possibly cut, and the Ollama server log is checked for truncation if it is readable.
- Mechanics: no `FAILED` sample; `unrecorded` 0, coverage 1.00.

## Decision rule

Fixed before the run, on whether A answers and not on whether it is right:

- **At least 15 of 30 samples finish before the deadline:** 2100 s becomes the proposal for A's deadline on RCAEval, recorded in an ADR before any holdout is read.
- **Fewer than 15:** A cannot be measured on RE2-OB at this deadline on this hardware. That is recorded as a finding, and the human decides what M4 does with A.

Either way, the human checks the proposal against M4's budget: at about 25 minutes a sample, the 200 holdout cases M4's check requires take about 83 h of CPU, against the roughly 19 nights vision §7 gives all recordings and local runs. No other setting changes because of this result without a new plan. H1 and H2 are not decided here.

## Command

From the repository root, then from `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision \
  --view tables --report plain --report-timeout 2100

cd evals
R=results/2026-11-02-baselines-re2ob-dev/inputs
python3 -m kintsugi_evals score --service-only \
  --label 0 $R/0-summary.tsv \
  --label 0R $R/0R-summary.tsv \
  --label D $R/D-summary.tsv \
  --label A600 $R/A-summary.tsv \
  --label A2100 ../infra/scripts/out/<local run> \
  --out results/2026-11-02-a-deadline-re2ob-dev
```

Check the fresh `summary.tsv` first: 30 samples, split `dev`, view `tables`, report `plain`, model `granite4.1:8b`, cap 512, deadline 2100 s.
