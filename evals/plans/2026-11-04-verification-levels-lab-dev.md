# Plan: verification-levels-lab-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/verification-levels.md`, T8. Levels: ADR-028, Decision 5 as amended on 2026-11-04. It is the fallback named in T6's plan (`evals/plans/2026-11-04-verification-levels-descriptive.md`): T6 and T7 found no admissible lab input, since the latest lab dev run of D (`replay-20261029T010641Z-cloud`) predates today's dossier text.

## Status

Descriptive. No hypothesis, no decision rule, no gain claimed. Approved by the human on 2026-11-04: one cloud decision replay of lab dev, about $0.02, plus Config 0 alongside. Committed before either run.

What is already known: on RE2-OB dev (T7, ratio-only latency rule) DS's levels L0/L1/L2/L3 are 0/4/0/26, verified-correct 25/30, and one correct answer rests only on `NOT_COMPARED` log lines. Lab dev has been read many times before (D, A, Config 0, M0 and its successors), so this run describes today's agent on seen cases; it is not a test of anything. The latency rule was changed (T7) after seeing RE2-OB dev, not the lab, but is still a post hoc rule.

## Date and commit

- Written: 2026-11-04
- Run on: the commit that adds this plan, or a later one that changes nothing under `agent/` or `evals/kintsugi_evals/`.

## Question

The human's question about the log rule (ADR-028, Decision 5: log and header lines are `NOT_COMPARED` and never satisfy L3): on the lab, where F3's evidence (`ConnectException`) and part of F2's rest on logs, how many correct answers stay below L3, why, and how many of them have cause-owned citations that are all `NOT_COMPARED` log lines? That count bounds what a previous log window, added to the agent before the 2026-12-01 freeze, could lift.

## Cases and split

Lab dev, 15 cases (`agent/src/test/resources/replay/recordings.tsv`, split `dev`, faults F1, F2, F3; the lab has no F4 recording in dev). No holdout.

## Configs

| Label | Config | How | Source |
|---|---|---|---|
| 0 | rules v1, Config 0 | `replay-agent.sh --route rules` | a fresh run, no cost |
| DS | D with today's defaults, as in `evals/plans/2026-11-03-abstain-suspects-re2ob-dev.md`: route `cloud`, mode `decision`, view `tables`, report `plain`, `claude-haiku-5-5`; the ranked report and suspects with an abstention are today's agent | `replay-agent.sh --route cloud` | a fresh run |

DS's flags are DS's exactly; only the dataset (lab, the default, so no `--dataset` and no `REPLAY_DIR`) and the split (`dev`) differ.

## Cost

About $0.02 (DS on 30 RE2-OB cases cost about $0.03). Bound: $0.10. The script prints a cost warning and writes the estimated cost in `aggregate.tsv`; if the estimate or the observed tokens suggest more than $0.10, the run is stopped or not scored and the human decides. Exactly one cloud run; a failed run is reported, not repeated.

## Metric

- Per config: AC@1 with Wilson 95% CIs (the scorer's default lab scoring, full truth, as earlier lab plans).
- DS's level counts L0/L1/L2/L3, overall and per fault; Verified-correct (fault rows only) and the L1 rate (rows with a report only), both with Wilson 95% CIs.
- Per fault, the correct answers below L3 and the checks they fail (`citations`, `owners-on-path`, `cause-fact-changed`).
- Per fault, the correct answers below L3 whose cause-owned citations are all `NOT_COMPARED` log lines (the log-only count), and the correct answers failing only `cause-fact-changed` with a cause-owned log line.
- Any incorrect answer at L3, reported.
- Run ids, model id, token totals and cost.

The per-item detail comes from `infra/scripts/recheck-levels.sh` on DS's run directory (re-check with today's code, no model call). Its guard must pass 15/15 (re-rendered dossier byte for byte, evidence equal); its `summary.tsv` must equal DS's own in every column, `level` included, since the code is the same. If the guard fails, the counts are taken from the stored incidents' `verification.checks` instead, and that is stated.

## Decision rule

None: the result is recorded whatever it shows. Whether a previous log window goes into the agent before the freeze is the human's decision.

## Checks before scoring

Each `summary.tsv`: 15 rows, split `dev`, view `tables`, report `plain`; DS's status column `COMPLETED` or `INVALID_REPORT`. A DS run with fewer rows or another status is reported and the human decides.

## Command

From the repository root, then `evals/`:

```
infra/scripts/replay-agent.sh --route rules --mode decision --split dev
infra/scripts/replay-agent.sh --route cloud --mode decision --split dev --view tables --report plain
infra/scripts/recheck-levels.sh infra/scripts/out/<DS run>

cd evals
.venv/bin/python -m kintsugi_evals score \
  --label 0 ../infra/scripts/out/<rules run> \
  --label DS ../infra/scripts/out/<DS run> \
  --out results/2026-11-04-verification-levels-lab-dev
```

## Results

`evals/results/2026-11-04-verification-levels-lab-dev/`: the scorer's files, the re-check's `DS-levels-detail.tsv` and `DS-recheck.txt`, and `notes.md` (levels per fault, Verified-correct and the L1 rate with CIs, AC@1 for DS and Config 0, the per-fault reasons below L3, the log-only count, run ids, cost, caveats: one run, a post hoc latency rule, lab dev seen before).
