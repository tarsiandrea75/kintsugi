# Plan: baselines-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/rcaeval-dev-baselines.md`, T3–T5. Data: ADR-020. Baseline: ADR-022.

## Status

Pre-registered: committed before D's or A's first run on RCAEval. Descriptive: dev decides nothing about H1, which is read on the RCAEval holdouts in M4.

What is already known: 0 4/30 and 0R 20/30 on these recordings (`evals/results/2026-11-01-config0r-re2ob-dev/`), including every case's verdict and candidates. D and A have seen no RCAEval case. BARO is out of this plan: it needs code first, so it gets its own plan (T8).

## Date and commit

- Written: 2026-11-02
- Run on commit: the commit that adds this plan on `feat/rcaeval-dev-baselines`, or a later one that changes nothing under `agent/src/main/`, `agent/src/test/java/dev/kintsugi/agent/loop/Replay*.java`, `agent/src/test/java/dev/kintsugi/agent/loop/Rules*.java`, `evals/kintsugi_evals/`, `infra/scripts/replay-agent.sh` or `infra/scripts/lib.sh`.
- Recordings: `evals/out/rcaeval/re2-ob-rep1-resources/`; its `recordings.tsv` must equal `evals/results/2026-11-01-config0r-re2ob-dev/inputs/recordings.tsv`.

## Hypothesis

Descriptive. Expected, not tested: D answers above 0R on cases 0R abstains on, and A loses samples to its 600 s deadline, because this dossier is larger than the lab's.

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one sample per config.
- First read of a holdout: none. Repetitions 2–3 and RE2-SS stay unconverted and unopened.
- Rerun rule: a run that stops before 30 samples, or fails before writing them, is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun, whatever its timeouts.

## Configs

Decision mode, `tables` view, `plain` report: the settings of the lab's holdout-1 table (`evals/plans/2026-10-30-holdout1-lab.md`).

| Label | Config (vision §5) | Route, model | Settings | Source |
|---|---|---|---|---|
| 0 | 0 (ADR-016) | `rules --rules v1`, no model | - | a fresh run at this plan's commit |
| 0R | 0R (ADR-021) | `rules --rules v2`, no model | - | a fresh run at this plan's commit |
| D | D | cloud, `claude-haiku-5-5` | route defaults | a fresh run |
| A | A | local, `granite4.1:8b`, temperature 0 | cap 512, deadline 600 s, `num-ctx` 8192 | a fresh run |

No calibration: A's deadline stays its default, as on the lab. A timeout or an invalid report is the config's answer as it stands, recorded, not rerun.

Cost:
- 0, 0R: minutes of CPU.
- D: about $0.03 to $0.07 at $0.10 and $0.50 per million tokens, from HR's prompts on these recordings (median 7,353 prompt tokens, at most 16,121 where a sample made two attempts). The run records the real cost. It needs the human's go-ahead, as `replay-agent.sh` requires.
- A: about 3 to 10 h of CPU. The lab's 15 samples took about 1.5 h on a smaller dossier; the upper bound is 30 samples with two attempts each at the deadline. It runs overnight, alone on the CPU.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with Wilson 95% CIs; coverage and selective accuracy; abstentions; invalid reports counted as misses.
- Paired, with the discordant cases each way, the exact McNemar test and the cluster bootstrap, as the scorer reports them: D − 0R, A − 0R, D − A, D − 0, A − 0.
- Descriptive: the cases where exactly one of 0R and D is right, and the same for 0R and A; report time per sample; D's real cost.
- Mechanics:
  - 0's and 0R's verdicts equal those in `evals/results/2026-11-01-config0r-re2ob-dev/inputs/`;
  - no sample ends `FAILED` and none holds `not recorded in this replay` (`unrecorded` 0, coverage 1.00);
  - a single A prompt at or above `num-ctx` would be cut silently. HR's median is near that limit, so any sample with one attempt and `promptTokens` of 8,192 or more is recorded. `promptTokens` adds attempts up (`docs/STATE.md`, Follow-ups), so a two-attempt sample is not read this way.

## Decision rule

None on H1: dev does not decide it, and these paired tests are exploratory. The result is committed and recorded whatever it shows. No config's settings change because of it without a new plan. A change in 0's or 0R's verdicts, a `FAILED` sample or an unrecorded call stops the feature until it is explained.

## Command

From the repository root, then from `evals/`:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain
infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision --view tables --report plain

cd evals
python3 -m kintsugi_evals score --service-only \
  --label 0 ../infra/scripts/out/<v1 run> \
  --label 0R ../infra/scripts/out/<v2 run> \
  --label D ../infra/scripts/out/<cloud run> \
  --label A ../infra/scripts/out/<local run> \
  --out results/2026-11-02-baselines-re2ob-dev
```

Check each fresh `summary.tsv` first: 30 samples, split `dev`, and for D and A view `tables`, report `plain`, and the model above. `recordings.tsv` and `converter-report.tsv` are copied into the results directory's `inputs/`; the recordings themselves stay outside the repository.
