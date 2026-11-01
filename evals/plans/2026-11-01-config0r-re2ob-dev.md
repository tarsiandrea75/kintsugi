# Plan: config0r-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/resource-facts.md`, T6 (AC1, AC2, AC5, AC6).

## Status

Exploratory (ADR-016:5, ADR-021, Status). R-RES's thresholds were fixed in ADR-021 before any converted resource series was read, and this plan is committed before the first conversion that writes resource series. No resource value of any RE2-OB case has been seen by the human or the agent: the converter was developed on synthetic fixtures only, and only one dev header line was read (`checkoutservice_cpu/1`). The run measures what the resource facts and R-RES add to the rules on RE2-OB dev, and it is the base of the stop-rule comparison HR − 0R (T7, its own plan).

## Date and commit

- Written: 2026-11-01
- Run on commit: the commit that adds this plan on `feat/resource-facts-03-rules`, or a later one that changes nothing under `agent/src/main/`, `agent/src/test/java/dev/kintsugi/agent/loop/Replay*.java`, `agent/src/test/java/dev/kintsugi/agent/loop/Rules*.java`, `evals/kintsugi_evals/` or `infra/scripts/replay-agent.sh`.
- Rules: `v1` is ADR-016's, frozen; `v2` is ADR-021's (Config 0R).

## Hypothesis

Exploratory. Expected, not tested: 0R recovers part of the cpu, mem and socket cases that Config 0 abstains on, and changes nothing where Config 0 answers (R-RES runs after R-REACH and R-DOWN). Disk, delay and loss cases should not move. Config 0's verdicts on the new conversion equal those of `evals/results/2026-11-01-config0-re2ob-dev/` (the new calls do not reach `diagnose()` under v1).

## Cases and split

- Dataset: RE2-OB, Zenodo record 14590730 (`RE2-OB.zip` md5 `b9e23f8842c404b396ffd2becff15de4`), under `KINTSUGI_RCAEVAL_DIR` (`~/data/rcaeval`).
- Split: `dev`, repetition 1 only: 5 services × 6 faults = 30 cases, one sample each per config (both are deterministic).
- First read of a holdout: none. Repetitions 2–3 (holdout A) and RE2-SS (holdout B) are not converted and not opened.
- The conversion goes to a new directory, `evals/out/rcaeval/re2-ob-rep1-resources`, so the recordings behind the earlier RE2-OB results stay as they were.

## Configs

| Label | Config (vision §5) | Route, model | Mode | Source |
|---|---|---|---|---|
| 0 | 0, rules only (ADR-016) | `rules --rules v1`, no model | decision | a fresh run at this plan's commit |
| 0R | 0R, rules plus R-RES (ADR-021) | `rules --rules v2`, no model | decision | a fresh run at this plan's commit |

Cost: minutes of CPU, no model calls.

## Metric

- AC@1 on the root-cause service only (`score --service-only`), per fault type and pooled over the 30 cases, each with a Wilson 95% CI; the paired difference 0R − 0 with its exact McNemar test.
- Abstentions (`UNKNOWN|UNKNOWN`), the number of R-RES verdicts, and how many are right.
- Category accuracy under ADR-020's map, reported, not used.
- Mechanics: no FAILED sample; no `not recorded in this replay` in any sample (`unrecorded` 0, coverage 1.00).
- AC1 on RE2-OB: Config 0's 30 verdicts equal those in `evals/results/2026-11-01-config0-re2ob-dev/inputs/`.
- AC5: the converter's leakage guard passes, and a grep of the 30 converted recordings for the case directory names and `<service>_cpu|_mem|_socket|_diskio` finds nothing.

## Decision rule

None: exploratory (see Status). The result is committed and recorded in the feature document whatever it shows. A threshold miss is recorded, not fixed on dev (ADR-021). A FAILED sample, an unrecorded call, a change in Config 0's verdicts or a leak stops T6 and is fixed before T7's plan is written.

## Command

From `evals/`, then the repository root, then `evals/`:

```
cd evals
python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
  convert-rcaeval --system re2-ob --rep 1 --out out/rcaeval/re2-ob-rep1-resources
cd ..
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2
cd evals
python3 -m kintsugi_evals score --service-only \
  --label 0 ../infra/scripts/out/<v1 run> \
  --label 0R ../infra/scripts/out/<v2 run> \
  --out results/2026-11-01-config0r-re2ob-dev
```

`recordings.tsv` and `converter-report.tsv` are copied into the results directory's `inputs/`; the recordings themselves stay outside the repository.
