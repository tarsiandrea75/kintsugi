# Plan: decoy-noise-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Taxonomy: ADR-029. Follows [`2026-11-05-decoy-categories-dev.md`](2026-11-05-decoy-categories-dev.md).

## Status

Confirmatory for one question. Do the decoy categories lower service AC@1 on RE2-OB dev, or was the drop from 26/30 to 21/30 run-to-run sampling?

- **The question is post hoc.** It was asked because the first DK run looked worse. The first DS and DK runs are therefore reported for context and are not pooled with this plan's runs, because a pair chosen for its gap would bias the estimate (regression to the mean).
- **Why one run is not enough.** The cloud route sends no temperature (`AnthropicRoute.java:18`), so each answer is a sample, and one run per configuration cannot size that noise.
- **The commit.** This plan is committed before the runs: its commit time precedes the start time in both run directories' names.

## Date and commit

- Written: 2026-11-05.
- **DK′ (decoys)** runs on the commit that adds this plan, on `feat/decoy-categories`.
- **DS′ (no decoys)** runs from a detached worktree at `9aded9d`, `../kintsugi-worktrees/decoy-baseline`.
  - `9aded9d` is the parent of `aa25d35`, the one commit that adds the decoys.
  - The worktree's `agent/` therefore differs from the branch's only by `aa25d35`: `Causes.java`, `Prompts.java`, tests and pins. This is checked with `git diff --stat 9aded9d aa25d35 -- agent/src/main` before the runs.
- Nothing under `agent/` changes in either checkout during the runs.

## Hypothesis

- **H0:** the decoys do not change service AC@1. DK′ − DS′ = 0.
- **H1:** they lower it. DK′ − DS′ < 0.
- **Secondary:** the abstention rate, `UNKNOWN|UNKNOWN` answers out of samples, for each config.

## Cases and split

- RE2-OB dev, repetition 1: 30 cases, `REPLAY_DIR=<absolute path>/evals/out/rcaeval/re2-ob-rep1-resources`. The path is absolute, because the worktree has no `evals/out`.
- `--repeats 3` for each config, so 90 samples each. No holdout.
- **Rerun rule:** a run that stops early is discarded and rerun whole, and the notes name the discarded directory. A complete run is never rerun.

## Configs

| Label | Checkout | Command |
|---|---|---|
| DS′ | worktree at `9aded9d` (no decoys) | `replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain --repeats 3` |
| DK′ | `feat/decoy-categories` at this plan's commit (decoys) | the same |
| 0 and 0R | - | read from `evals/results/2026-11-05-decoy-categories-re2ob-dev/inputs/`, which were checked unchanged today |

Both configs use `claude-haiku-5-5`, cap 768, deadline 90 s.

## Cost

- **Expected:** 180 cloud calls. One 30-case run cost $0.039, so expect about $0.24.
- **The bound:** $0.40.
  - DS′ runs first.
  - DK′ starts only if DS′'s `costUsd` stays under $0.20.

## Metric

- **Primary:** service AC@1 for each config, over 90 samples, with a Wilson 95% CI. DK′ − DS′ with the scorer's cluster bootstrap 95% CI, taking the case as the unit (seed 1, 10,000 resamples).
  - With repetitions the scorer gives no McNemar test (`scoring.py:498-499`).
- **Spread:** AC@1 for each config and each repeat (three numbers per config), showing the run-to-run noise.
- **Secondary:**
  - the abstention rate for each config;
  - decoy answers, as cause and as alternative;
  - coverage and selective accuracy;
  - Verified-correct and the L1 rate.
- **Context, not pooled:** the first pair, DS 26/30 and DK 21/30.

## Decision rule

The rule was fixed before the runs. It is the human's call to act on it.

- **The bootstrap CI for DK′ − DS′ lies wholly below 0:** the decoys cost AC@1 on RE2-OB. Recommendation: revert `aa25d35` before the freeze, or change the definitions under a new plan.
- **The CI includes 0:** there is no evidence of a cost at this sample size. Recommendation: keep the decoys (vision §1: "the taxonomy is wider than the catalog"), and record the CI as the limit of what was measured.
- **The CI lies wholly above 0:** this is recorded as it is, and no gain is claimed (post hoc).

**Mechanics:**

- A run with fewer than 90 rows, or a configuration other than the above, is not scored, and the human decides.
- `git diff --stat 9aded9d aa25d35 -- agent/src/main` must name only `Causes.java` and `Prompts.java`.

## Command

```
git worktree add --detach ../kintsugi-worktrees/decoy-baseline 9aded9d
git diff --stat 9aded9d aa25d35 -- agent/src/main
R=$PWD/evals/out/rcaeval/re2-ob-rep1-resources

(cd ../kintsugi-worktrees/decoy-baseline && REPLAY_DIR=$R infra/scripts/replay-agent.sh --dataset re2-ob --route cloud \
  --mode decision --view tables --report plain --repeats 3)
REPLAY_DIR=$R infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --view tables --report plain --repeats 3

cd evals
I=results/2026-11-05-decoy-categories-re2ob-dev/inputs
.venv/bin/python -m kintsugi_evals score --service-only --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $I/0-summary.tsv --label 0R $I/0R-summary.tsv \
  --label DS2 ../../kintsugi-worktrees/decoy-baseline/infra/scripts/out/<DS′ run> \
  --label DK2 ../infra/scripts/out/<DK′ run> \
  --out results/2026-11-05-decoy-noise-re2ob-dev
```

The labels are `DS2` and `DK2`: the scorer's input file names keep only ASCII letters, digits, `.`, `_` and `-` (`cli.py:70`). The worktree is removed after its summary is copied into the result's inputs.
