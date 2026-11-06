# Plan: config-c-dev

## Status

Pre-registered. Committed with ADR-030 before the run. This run fixes Config C's escalation rule `(k, t)` for every later holdout read. It does not test H3: H3 is read on the M4 holdouts.

## Date and commit

- Written: 2026-11-06
- Run on commit: <sha, filled at T5>

## Hypothesis

None; this run is a calibration. Its single output is the escalation rule that ADR-030's selection rule picks. Any C AC@1 it reports is optimistic, because the rule is chosen on these cases.

## Cases and split

- Split: RCAEval RE2-OB dev, the same 30 cases as `evals/results/2026-11-05-l3-callee-recheck-re2ob`.
- No new agent run. C is computed from the existing A (AS) and D (DS) outputs, joined on `(split, recording)`.
- First read of a holdout: no holdout is read.

## Configs

| Label | Config | Source |
|---|---|---|
| 0 | Config 0, rules only | inputs of `2026-11-05-l3-callee-recheck-re2ob` |
| 0R | Config 0R, H1's baseline | inputs of `2026-11-05-l3-callee-recheck-re2ob` |
| AS | Config A: local granite4.1:8b, 2100 s report deadline, levels after ADR-028's `no-changed-callee` recheck | AS summary, with `confidence` backfilled from `infra/scripts/out/replay-20261103T151645Z-local` |
| DS | Config D: `claude-haiku-5-5` | DS summary, with `confidence` backfilled from `infra/scripts/out/replay-20261103T150905Z-cloud` |
| C | Config C under the chosen `(k, t)` | computed by `escalate` from AS and DS |

The backfilled summaries are written to this run's `inputs/` by `infra/scripts/backfill-confidence.sh`. The committed files of the 2026-11-05 run are not modified.

## Metric

For each rule of ADR-030's grid (`k ∈ {L2, L3}`, `t ∈ {off} ∪ {midpoints between consecutive distinct AS confidences}`):

- escalations, as a count and a rate of the paired cases, with a Wilson 95% CI;
- C service-level AC@1, with a Wilson 95% CI;
- C − D, the paired difference with exact McNemar;
- the count of each trigger: no answer, abstention, level, confidence.

For the chosen rule, the standard `score` report over 0, 0R, AS, DS and C: AC@1/3/5 at service level, with Wilson CIs and paired differences.

## Decision rule

ADR-030 Decision 3, written before the numbers:

- Among the rules escalating at most 50% of the paired cases, pick the highest C AC@1.
- Break ties by the fewer escalations, then the higher `k`, then `t = off`.
- If no rule escalates at most 50%, fix `k = L3, t = off` and state that H3's cost limit already fails on dev.

The chosen `(k, t)` is recorded in ADR-030's amendment and is not changed after any holdout read.

## Command

```
R=evals/results/2026-11-06-config-c-dev
S=evals/results/2026-11-05-l3-callee-recheck-re2ob/inputs
infra/scripts/backfill-confidence.sh $S/AS-summary.tsv infra/scripts/out/replay-20261103T151645Z-local $R/inputs/AS-summary.tsv
infra/scripts/backfill-confidence.sh $S/DS-summary.tsv infra/scripts/out/replay-20261103T150905Z-cloud $R/inputs/DS-summary.tsv
cd evals
python -m kintsugi_evals escalate --a ../$R/inputs/AS-summary.tsv --d ../$R/inputs/DS-summary.tsv \
  --service-only --topology ../agent/src/main/resources/topology/re2-ob.json --out ../$R
python -m kintsugi_evals score --service-only --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 ../$S/0-summary.tsv --label 0R ../$S/0R-summary.tsv \
  --label AS ../$R/inputs/AS-summary.tsv --label DS ../$R/inputs/DS-summary.tsv \
  --label C ../$R/C-summary.tsv --out ../$R
```

The exact flags follow the CLI as built in T4. Any difference is recorded in the run's `notes.md`.
