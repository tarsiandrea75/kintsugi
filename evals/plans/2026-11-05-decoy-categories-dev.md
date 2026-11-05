# Plan: decoy-categories-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Taxonomy: ADR-029. Config D: ADR-012 (the cloud route). Config 0: ADR-016.

## Status

Descriptive. The question is what the two decoy categories cost; this is not a gain claim.

- **The cost by construction.** No dev case has NETWORK or CODE_DEFECT as its truth, so any decoy answer is a miss by construction.
- **The comparison.** Each set is compared with the committed run of the same configuration made before the decoys: DS, with `claude-haiku-5-5`, report `plain`, cap 768.
- **The commit.** This plan is committed before the runs: its commit time precedes the start time in each run directory's name.
- **The 4 commits before ADR-029.** Since the committed DS runs, four commits have reached the agent:
  - the verification levels and the latency ratio (2026-11-04);
  - the context-full message;
  - `no-changed-callee`.

  None of them changes the prompt or the schema. Only ADR-029 does, in `aa25d35`: the system prompt and the report schema gain the two categories. That is the only difference these runs measure.

## Date and commit

- Written: 2026-11-05.
- Run on: the commit that adds this plan on `feat/decoy-categories`. Nothing under `agent/` changes during the runs.

## Hypothesis

Descriptive. Four questions:

1. **Lab dev (15 cases, scored on the full pair):**
   - How many answers name a decoy?
   - What is DK's AC@1 against DS's 14/15 (`evals/results/2026-11-04-verification-levels-lab-dev/`), paired, with the exact McNemar test?
   - The F2 and F3 cases are the overlaps ADR-029 names.
2. **RE2-OB dev (30 cases, scored on the service):**
   - How many answers name a decoy?
   - How many of them are in the loss cases?
   - What is DK's service AC@1 against DS's 26/30 (`infra/scripts/out/replay-20261103T150905Z-cloud`, the input of `evals/results/2026-11-04-verification-levels-ratio/`)?
   - The category is reported beside it, with category accuracy on the full pair.
3. **Levels:** what are Verified-correct and the L1 rate on both sets?
4. **Config 0:** are its verdicts unchanged on both sets (AC3)?

## Cases and split

- Lab dev: 15 cases, `agent/src/test/resources/replay`.
- RE2-OB dev: repetition 1, 30 cases, `REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources`.
- One sample each, and no holdout.
- **Rerun rule:** a run that stops early is discarded and rerun whole, and the notes name the discarded directory. A complete run is never rerun.

## Configs

| Label | Config | Source |
|---|---|---|
| 0 | rules v1 | fresh rules replays on both sets; their verdicts must equal the committed `0-summary.tsv` of each result |
| 0R | rules v2 (RE2-OB only) | a fresh rules replay; it must equal the committed `0R-summary.tsv` |
| DS | cloud, `claude-haiku-5-5`, report `plain`, view `tables`, cap 768, deadline 90 s, before the decoys | the committed inputs: lab `evals/results/2026-11-04-verification-levels-lab-dev/inputs/DS-summary.tsv`; RE2-OB `evals/results/2026-11-04-verification-levels-ratio/inputs/DS-summary.tsv` |
| DK | DS with the decoy taxonomy | fresh cloud runs |

**Cost:** 45 cloud calls. The committed DS runs cost $0.0165 (lab) and $0.0398 (RE2-OB), so expect about $0.06. The bound is $0.20. The script cannot stop a run mid-way, so the lab run goes first, and the RE2-OB run starts only if the lab run's `costUsd` stays under $0.05. There is no local CPU load beyond the agent.

## Metric

- AC@1 with Wilson 95% CIs:
  - lab on the full pair;
  - RE2-OB on the service (`--service-only`).
- Coverage and selective accuracy.
- Paired DK − DS and DK − 0: the discordant cases each way and the exact McNemar test.
- **Decoy answers:** how many name `NETWORK` or `CODE_DEFECT` as the cause, and how many as an alternative, per fault type.
- **RE2-OB category accuracy on the full pair:** a second score without `--service-only`, for DS and DK.
- Verified-correct and the L1 rate.
- The statuses: `INVALID_REPORT` and `TIMED_OUT`.

## Decision rule

- **None on the result.** It is recorded whatever it shows, and ADR-029 stays as accepted.
- **What the human decides:** if the decoys cost lab AC@1, the human decides whether to change the definitions before the freeze. Any change needs a new plan.
- **Mechanics:**
  - A run with fewer rows than its set, or with a configuration other than the above, is not scored, and the human decides.
  - Config 0 must be unchanged, or the feature is reopened.

## Command

From the repository root:

```
infra/scripts/replay-agent.sh --route rules --mode decision --split dev
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2

infra/scripts/replay-agent.sh --route cloud --mode decision --split dev --view tables --report plain
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain
```

Then, from `evals/`:

```
.venv/bin/python -m kintsugi_evals score \
  --label 0 ../infra/scripts/out/<lab 0> \
  --label DS results/2026-11-04-verification-levels-lab-dev/inputs/DS-summary.tsv \
  --label DK ../infra/scripts/out/<lab DK> \
  --out results/2026-11-05-decoy-categories-lab-dev

R=results/2026-11-04-verification-levels-ratio/inputs
.venv/bin/python -m kintsugi_evals score --service-only --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 ../infra/scripts/out/<re2 0> --label 0R ../infra/scripts/out/<re2 0R> \
  --label DS $R/DS-summary.tsv --label DK ../infra/scripts/out/<re2 DK> \
  --out results/2026-11-05-decoy-categories-re2ob-dev
.venv/bin/python -m kintsugi_evals score \
  --label DS $R/DS-summary.tsv --label DK ../infra/scripts/out/<re2 DK> \
  --out results/2026-11-05-decoy-categories-re2ob-dev-category
```

Before scoring, check each DK `summary.tsv`:

- its rows: 15 on the lab, 30 on RE2-OB;
- split `dev`, view `tables`, report `plain`;
- model `claude-haiku-5-5`, cap 768.
