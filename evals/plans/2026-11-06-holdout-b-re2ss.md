# Plan: holdout-b-re2ss

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/m4-plans.md`, T5. One of the four holdout tracks that feed the H1–H3 family plan, `evals/plans/2026-11-06-h1-h3-verdicts.md`.

## Status

Pre-registered: committed before any holdout is converted or replayed.

What is already known about these cases: nothing beyond their counts. RE2-SS (Sock Shop) is a system no config, rule or prompt has run on or been tuned for. The T4 dry check (`convert-rcaeval --check-only --allow-holdout B`) read only directory names, header lines and inject times and printed counts that name no case: 90 cases, every header clean. The topology file `re2-ss.json` was written from upstream sources and header prefixes, with no case read (ADR-020).

## Date and commit

- Written: 2026-11-06
- Run on commit: <filled at run>. It must descend from the tag `freeze-m3`, with `infra/scripts/check-freeze.sh` passing on it, and change nothing under `agent/src/main/` since the tag.
- Run window: M4, 2026-12-07 to 2026-12-27. `check-freeze.sh` condition 4 forbids any committed holdout row before 2026-12-07.

## Hypothesis

Descriptive on its own. This track feeds the family plan: H1 (D − 0R, service AC@1), H2a, H2c, H3a and H3b. No decision is taken on holdout B alone. ADR-022 names it the stronger test of 0R's resource rule, since R-RES was motivated by RE2-OB dev.

## Cases and split

- Split: RCAEval holdout B, RE2-SS repetitions 1, 2 and 3; manifest split `holdout` as the converter writes it.
- Cases: 90 fault cases over the three repetitions (6 faults: `cpu`, `mem`, `disk`, `delay`, `loss`, `socket`), each repetition's count as `--check-only` prints it. No healthy pseudo-case: the converter produces none.
- Traffic is empty: Sock Shop has no traces, so the converter writes an empty traffic answer and reads no `traces.csv` (`convert.py` docstring). Every config reads the same empty section.
- The case is the unit. Recording ids are sha256 of `RE2-SS/<case>/<rep>`, distinct across repetitions.
- First read of this holdout: yes. Each config reads it once.
- Rerun rule: a run that stops before writing every sample is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun. A timeout of A at its deadline is A's answer (ADR-023).

## Configs

All in decision mode, `tables` view, `plain` report, `--dataset re2-ss`, topology `classpath:topology/re2-ss.json`.

| Label | Config (vision §5) | Route, model, settings | Runs |
|---|---|---|---|
| 0 | Config 0, rules `v1` (ADR-016) | `rules`, no model | one per repetition |
| 0R | Config 0R, rules `v2` (ADR-021, ADR-022) | `rules`, no model, `--rules v2` | one per repetition |
| A | Config A | `local`, `granite4.1:8b`, `--report-timeout 2100` (ADR-023), other settings default | one per repetition |
| D | Config D | `cloud`, `claude-haiku-5-5` (ADR-012), 3 repeats per case (vision §6) | three per repetition, `--repeats 1` each |
| C | Config C, `k = L2, t = off` (ADR-030) | computed from A and D, no run | none |

- **D's repeats and the cloud bound.** A cloud run may make at most 60 samples (`MAX_CLOUD_SAMPLES=60`, `replay-agent.sh`:159). D runs each repetition three times with `--repeats 1`, one repetition's cases a run (30 at the expected count): nine runs, repeat r being the r-th run of each repetition. Should a repetition hold more than 60 cases, its run is split by `--faults cpu,mem,disk` and `--faults delay,loss,socket`, and both halves form that repeat.
- **C for display and C for the verdict.** `escalate` refuses a case present twice on one side (`escalation.py`:77-78), so the C table here uses D's first repeat only. The H3 verdict uses `verdict`'s in-process C, with every D repeat (verdict spec, REQ-7).
- Cost and time: A takes about 16 h of CPU at RE2-OB dev's median, at most 53 h at the deadline; Sock Shop's dossier size differs, which is why ADR-023 kept a margin. D costs about $0.35 for 90 cases × 3 repeats.

## Metric

All on the service only (ADR-020), over the 90 fault cases:

- AC@1, AC@3, AC@5 and Avg@5, with their chance levels over `re2-ss.json`'s services, each rate with a Wilson 95% CI; per fault and pooled.
- Abstentions, coverage and selective accuracy; verified-correct and the L1 rate from the `level` column.
- Paired differences: D − 0R (H1's statistic on this track), A − 0R, D − 0, A − 0, A − D, C − D. Exact two-sided McNemar where both configs have one outcome per case (0, 0R, A, C); the case-level cluster bootstrap 95% interval for any difference with D (ADR-015).
- C: escalation count and rate (Wilson 95% CI) and each trigger's count, on D's first repeat.
- A's time to report: median and maximum of `seconds`.
- All secondary or descriptive; the family's tests run once, in the H1–H3 plan.

## Decision rule

None on this track alone. The table is reported whatever it shows. The H1–H3 decisions are taken only by `evals/plans/2026-11-06-h1-h3-verdicts.md`, with this track as input `B`.

## Command

From `evals/`, convert the three repetitions (outputs `evals/out/rcaeval/re2-ss-rep{1,2,3}/`, git-ignored):

```
for REP in 1 2 3; do
  python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
    convert-rcaeval --system re2-ss --rep $REP --allow-holdout B
done
```

From the repository root, for each `REP` in 1, 2 and 3:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ss-rep$REP
infra/scripts/replay-agent.sh --dataset re2-ss --route rules --mode decision --split holdout --rules v1
infra/scripts/replay-agent.sh --dataset re2-ss --route rules --mode decision --split holdout --rules v2
infra/scripts/replay-agent.sh --dataset re2-ss --route local --mode decision --split holdout --report-timeout 2100
infra/scripts/replay-agent.sh --dataset re2-ss --route cloud --mode decision --split holdout --repeats 1   # repeat 1
infra/scripts/replay-agent.sh --dataset re2-ss --route cloud --mode decision --split holdout --repeats 1   # repeat 2
infra/scripts/replay-agent.sh --dataset re2-ss --route cloud --mode decision --split holdout --repeats 1   # repeat 3
```

Merge each config's runs into one summary per label, with the same `merge` shell function and header check as `evals/plans/2026-11-06-holdout-a-re2ob.md`, into `$R/merged/` with `R=evals/results/<run date>-holdout-b-re2ss`: `0.tsv`, `0R.tsv`, `A.tsv` (three runs each), `D1.tsv` (the three repeat-1 runs) and `D.tsv` (all nine D runs). Then, from `evals/`:

```
R=../evals/results/<run date>-holdout-b-re2ss
T=../agent/src/main/resources/topology/re2-ss.json
python3 -m kintsugi_evals escalate --a $R/merged/A.tsv --d $R/merged/D1.tsv --rule k=L2,t=off \
  --service-only --topology $T --seed 20261106 --out $R
python3 -m kintsugi_evals score --service-only --topology $T --seed 20261106 --iterations 10000 \
  --label 0 $R/merged/0.tsv --label 0R $R/merged/0R.tsv --label A $R/merged/A.tsv \
  --label D $R/merged/D.tsv --label C $R/C-summary.tsv --out $R
```

Check each summary first: one repetition's cases a run (90 rows in `merged/0`, `0R`, `A`, `D1`; 270 in `merged/D`), split `holdout`, view `tables`, report `plain`, and the expected `model` and `reportTimeout`.

## Read once

- Holdout B is read once, by the runs above under this plan. A second read is exploratory, labelled in its plan and in `docs/benchmark.md`, and never replaces the first.
- The result directory's `notes.md` records the holdout name (`RCAEval holdout B, RE2-SS`), this plan's path and the run directories behind each merged file; `score` and `escalate` do not write them.
- Nothing from this read is committed before 2026-12-07: `check-freeze.sh` condition 4 fails on any committed holdout B row dated earlier.
- Holdout B is never cut (ADR-032). M4's case counts: A 75 + 60 + 90 = 225 ≥ 200; D 75 + 60 + 90 + 90 = 315 ≥ 300.
