# Plan: l3-callee-recheck

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Levels: ADR-028, Decision 5 as amended on 2026-11-05 (`no-changed-callee`). Feature: `odd/tasks/l3-callee-check.md`, T4.

## Status

Descriptive only. There is no hypothesis, no decision rule and no claimed gain. Nothing calls a model or the cloud, nothing runs in the lab, and no holdout is read.

**Post hoc.** The check was chosen after seeing the wrong L3 answers on these same dev cases. These numbers describe the new rule on the data that motivated it; they do not test it. Committed before the re-check: this plan's commit time precedes the time in every re-check directory's name.

What the parent's estimate expects, from line facts re-derived from the dossier text and rounded to 3 decimals:

- **Wrong answers:** the 5 that reached L3 leave L3 (HC 3, HT 1, DS 1).
- **Correct answers:** 4 leave L3. All 4 are `delay/0ed36a9e`, once in each of HC, HT, DS and AS.
- **Lab dev:** no change expected; one lab answer is uncertain under rounding.

The re-check computes the exact counts.

## Inputs

All are dev runs that already stored their answers. Each is re-checked with `infra/scripts/recheck-levels.sh` on this plan's commit.

| Label | Run | Dataset | Committed result it belongs to |
|---|---|---|---|
| DS | `infra/scripts/out/replay-20261103T150905Z-cloud` | re2-ob | `evals/results/2026-11-04-verification-levels-ratio/` |
| AS | `infra/scripts/out/replay-20261103T151645Z-local` | re2-ob | the same |
| HT | `infra/scripts/out/replay-20261105T042537Z-local` | re2-ob | `evals/results/2026-11-05-hybrid-today-re2ob-dev/` |
| HC | `infra/scripts/out/replay-20261105T115842Z-local` | re2-ob | `evals/results/2026-11-05-hybrid-ctx12k-re2ob-dev/` |
| DL | `infra/scripts/out/replay-20261104T222411Z-cloud` (lab dev DS) | lab | `evals/results/2026-11-04-verification-levels-lab-dev/` |

The re2-ob runs use `REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources` and `--dataset re2-ob`. Before the re-check, each input `summary.tsv` must equal its committed copy in the result above in every column. HT, HC and DL are checked against their committed copies; DS and AS are checked against the ratio result's inputs, ignoring `level`.

## Guard

The guard comes from the script:

- each re-rendered dossier equals the stored one byte for byte;
- the re-check verifies exactly the stored evidence.

An input with any refused incident is reported as refused and is not scored. The human then decides.

## Metric

For each input:

- the level counts L0–L3, before (the committed `level`) and after;
- Verified-correct and the L1 rate, with Wilson 95% CIs, from the scorer;
- every answer whose level changes: its truth, its answer, and the failed check's detail;
- the four counts the estimate gave: correct staying at L3, correct leaving it, wrong staying at L3, wrong leaving it.

AC@1, coverage and Config 0's verdicts must not change. `scores.tsv` and `paired.tsv` must equal the committed results' byte for byte.

## Command

From the repository root, then from `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob infra/scripts/out/replay-20261103T150905Z-cloud
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob infra/scripts/out/replay-20261103T151645Z-local
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob infra/scripts/out/replay-20261105T042537Z-local
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob infra/scripts/out/replay-20261105T115842Z-local
infra/scripts/recheck-levels.sh infra/scripts/out/replay-20261104T222411Z-cloud

cd evals
R=results/2026-11-04-verification-levels-ratio/inputs
.venv/bin/python -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv \
  --label DS ../infra/scripts/out/<DS re-check> --label AS ../infra/scripts/out/<AS re-check> \
  --label HT ../infra/scripts/out/<HT re-check> --label HC ../infra/scripts/out/<HC re-check> \
  --out results/2026-11-05-l3-callee-recheck-re2ob
.venv/bin/python -m kintsugi_evals score \
  --label 0 results/2026-11-04-verification-levels-lab-dev/inputs/0-summary.tsv \
  --label DS ../infra/scripts/out/<DL re-check> \
  --out results/2026-11-05-l3-callee-recheck-lab-dev
```

The lab command is copied from the scoring options of `evals/results/2026-11-04-verification-levels-lab-dev/report.txt`; it is that result's command with only the inputs changed. Each result keeps the `*-recheck.txt` and `*-levels-detail.tsv` files, as the ratio result does, and a `notes.md`.
