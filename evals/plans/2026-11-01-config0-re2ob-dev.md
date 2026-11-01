# Plan: config0-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/rcaeval-converter.md`, T6 (AC2, AC4).

## Status

Exploratory, a mechanics check. No Config 0 verdict on RCAEval has been seen by the human or the agent before this plan: the only re2-ob replay so far ran on the synthetic fixture (`replay-20261101T003339Z-rules`). This plan makes no claim about Config 0 or any model on RCAEval. It checks that the converter, the replay and the scorer run end to end on the 30 dev cases, and records Config 0's service-level AC@1 as the first RCAEval baseline number.

## Date and commit

- Written: 2026-11-01
- Run on commit: the commit that adds this plan on `feat/rcaeval-converter-02-replay`, or a later one that changes nothing under `agent/src/main/`, `agent/src/test/java/dev/kintsugi/agent/loop/Replay*.java`, `agent/src/test/java/dev/kintsugi/agent/loop/Rules*.java`, `evals/kintsugi_evals/` or `infra/scripts/replay-agent.sh`.
- Rules: ADR-016's, frozen; Config 0 is unchanged on RCAEval.

## Hypothesis

Exploratory. Expected, not tested: Config 0 is weak on RCAEval. The dossier has no resource facts yet (ADR-020, Consequences), so cpu, mem, disk and socket faults reach the rules only through latency, traffic and logs, and many cases should end as abstentions.

## Cases and split

- Dataset: RE2-OB, Zenodo record 14590730 (`RE2-OB.zip` md5 `b9e23f8842c404b396ffd2becff15de4`), under `KINTSUGI_RCAEVAL_DIR` (`~/data/rcaeval`).
- Split: `dev`, repetition 1 only: 5 services × 6 faults = 30 cases, one sample each (Config 0 is deterministic).
- First read of a holdout: none. Repetitions 2–3 (holdout A) and RE2-SS (holdout B) are not converted and not opened.

## Configs

| Label | Config (vision §5) | Route, model | Mode | Source |
|---|---|---|---|---|
| 0 | 0, rules only (ADR-016) | `rules`, no model | decision | a fresh run at this plan's commit |

Cost: minutes of CPU, no model calls.

## Metric

- AC@1 on the root-cause service only (`score --service-only`, RCAEval's AC@1), per fault type and pooled over the 30 cases, each with a Wilson 95% CI. The scope is stated here and on the first line of each config in the committed `report.txt`; `scores.tsv` does not name it.
- Abstentions (`UNKNOWN|UNKNOWN`) and coverage.
- Mechanics (AC4): no FAILED sample, and no `not recorded in this replay` in any sample (every gatherer call is recorded, which also proves the JVM received the re2-ob topology).
- Leakage (AC2): a grep over the 30 converted recordings for the case directory names and the fault words outside `untrustedText` finds nothing (the converter's own check, repeated on the output).

## Decision rule

None: exploratory (see Status). The result is committed and recorded in the feature document whatever it shows. A FAILED sample or an unrecorded call fails T6's mechanics check and is fixed before any RCAEval measurement plan is written.

## Command

From `evals/`, then the repository root, then `evals/`:

```
cd evals
python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
  convert-rcaeval --system re2-ob --rep 1
cd ..
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1 infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision
cd evals
python3 -m kintsugi_evals score --service-only \
  --label 0 ../infra/scripts/out/<rules run> \
  --out results/2026-11-01-config0-re2ob-dev
```

`recordings.tsv` and `converter-report.tsv` are copied into the results directory's `inputs/`; the recordings themselves stay outside the repository (derived from the dataset, about the size of the dataset's windows).
