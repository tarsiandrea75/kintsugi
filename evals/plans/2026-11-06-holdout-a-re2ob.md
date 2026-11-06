# Plan: holdout-a-re2ob

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/m4-plans.md`, T5. One of the four holdout tracks that feed the H1–H3 family plan, `evals/plans/2026-11-06-h1-h3-verdicts.md`.

## Status

Pre-registered: committed before any holdout is converted or replayed.

What is already known about these cases: nothing beyond their counts. RCAEval holdout A has never been converted, replayed or scored. The T4 dry check (`convert-rcaeval --check-only --allow-holdout A`) read only directory names, header lines and inject times, and printed counts and anomalies that name no case: 60 cases, and 3 non-component metric prefixes in 1 case each. Every config, rule, prompt and the escalation rule `k = L2, t = off` (ADR-030) were fixed on RE2-OB dev (repetition 1), the same system.

## Date and commit

- Written: 2026-11-06
- Run on commit: <filled at run>. It must descend from the tag `freeze-m3`, with `infra/scripts/check-freeze.sh` passing on it, and change nothing under `agent/src/main/` since the tag.
- Run window: M4, 2026-12-07 to 2026-12-27. `check-freeze.sh` condition 4 forbids any committed holdout row before 2026-12-07.

## Hypothesis

Descriptive on its own. This track feeds the family plan: H1 (D − 0R, service AC@1), H2a, H2c, H3a and H3b. No decision is taken on holdout A alone.

## Cases and split

- Split: RCAEval holdout A, RE2-OB (Online Boutique) repetitions 2 and 3; manifest split `holdout` as the converter writes it.
- Cases: 60 fault cases, 30 per repetition (6 faults: `cpu`, `mem`, `disk`, `delay`, `loss`, `socket`). No healthy pseudo-case: the converter produces none.
- The case is the unit. A recording id is sha256 of `RE2-OB/<case>/<rep>`, so the two repetitions never share an id; they stay correlated runs of the same system, which the case-level cluster bootstrap does not model across repetitions (stated as a limit).
- First read of this holdout: yes. Each config reads it once.
- Rerun rule: a run that stops before writing every sample is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun. A timeout of A at its deadline is A's answer (ADR-023).

## Configs

All in decision mode, `tables` view, `plain` report, `--dataset re2-ob`, topology `classpath:topology/re2-ob.json`.

| Label | Config (vision §5) | Route, model, settings | Runs |
|---|---|---|---|
| 0 | Config 0, rules `v1` (ADR-016) | `rules`, no model | one per repetition |
| 0R | Config 0R, rules `v2` (ADR-021, ADR-022) | `rules`, no model, `--rules v2` | one per repetition |
| A | Config A | `local`, `granite4.1:8b`, `--report-timeout 2100` (ADR-023), other settings default | one per repetition |
| D | Config D | `cloud`, `claude-haiku-5-5` (ADR-012), 3 repeats per case (vision §6) | three per repetition, `--repeats 1` each |
| C | Config C, `k = L2, t = off` (ADR-030) | computed from A and D, no run | none |

- **D's repeats and the cloud bound.** A cloud run may make at most 60 samples (`MAX_CLOUD_SAMPLES=60`, `replay-agent.sh`:159). D therefore runs each repetition three times with `--repeats 1` (30 samples a run): six runs, repeat r being the r-th run of each repetition. Why not `--repeats 3` split by `--faults`: one run per repeat keeps D's first repeat a whole run, which C's display needs (below), and needs no fault split.
- **C for display and C for the verdict.** `escalate` refuses a case present twice on one side (`escalation.py`:77-78), so the C table of this result uses D's first repeat only. The H3 verdict does not use that table: `verdict` computes C in-process from A and every D repeat (verdict spec, REQ-7).
- Cost and time: 0 and 0R take minutes. A takes about 11 h of CPU at the dev median (877 s a case), at most 35 h at the deadline. D costs about $0.0013 a case, about $0.25 for 60 cases × 3 repeats.

## Metric

All on the service only (RCAEval's AC@1, ADR-020), over the 60 fault cases:

- AC@1, AC@3, AC@5 and Avg@5, with their chance levels over `re2-ob.json`'s services, each rate with a Wilson 95% CI; per fault and pooled.
- Abstentions, coverage and selective accuracy; verified-correct and the L1 rate from the `level` column.
- Paired differences of every two configs: D − 0R (H1's statistic on this track), A − 0R, D − 0, A − 0, A − D, C − D. Where each config has one outcome per case (0, 0R, A, C), the exact two-sided McNemar test with the scorer's Holm; any difference with D, which has 3 repeats, gets the case-level cluster bootstrap 95% interval instead (ADR-015), and McNemar is reported as not applicable.
- C: the escalation count and rate (Wilson 95% CI) and each trigger's count, under the fixed rule, on D's first repeat.
- A's time to report: median and maximum of the `seconds` column.
- All of these are secondary or descriptive. The family's tests are run once, by the H1–H3 plan.

## Decision rule

None on this track alone. The table is reported whatever it shows and no config, rule or prompt changes because of it. The H1–H3 decisions are taken only by `evals/plans/2026-11-06-h1-h3-verdicts.md`, with this track as input `A`.

## Command

From `evals/`, convert both repetitions (the converter's default outputs, `evals/out/rcaeval/re2-ob-rep2/` and `re2-ob-rep3/`, git-ignored):

```
python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
  convert-rcaeval --system re2-ob --rep 2 --allow-holdout A
python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
  convert-rcaeval --system re2-ob --rep 3 --allow-holdout A
```

From the repository root, for each `REP` in 2 and 3:

```
export REPLAY_DIR=evals/out/rcaeval/re2-ob-rep$REP
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --split holdout --rules v1
infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --split holdout --rules v2
infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision --split holdout --report-timeout 2100
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --split holdout --repeats 1   # repeat 1
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --split holdout --repeats 1   # repeat 2
infra/scripts/replay-agent.sh --dataset re2-ob --route cloud --mode decision --split holdout --repeats 1   # repeat 3
```

Merge each config's runs into one summary per label (no merge tool exists yet; see the feature document). Every merged input must share one header line, checked first:

```
R=evals/results/<run date>-holdout-a-re2ob
mkdir -p $R/merged
merge() { out=$1; shift; [ "$(head -q -n 1 "$@" | sort -u | wc -l)" = 1 ] || { echo "headers differ" >&2; return 1; }
          { head -n 1 "$1"; tail -q -n +2 "$@"; } >"$out"; }
merge $R/merged/0.tsv  <0 rep2>/summary.tsv  <0 rep3>/summary.tsv
merge $R/merged/0R.tsv <0R rep2>/summary.tsv <0R rep3>/summary.tsv
merge $R/merged/A.tsv  <A rep2>/summary.tsv  <A rep3>/summary.tsv
merge $R/merged/D1.tsv <D rep2 r1>/summary.tsv <D rep3 r1>/summary.tsv
merge $R/merged/D.tsv  <D rep2 r1>/summary.tsv <D rep2 r2>/summary.tsv <D rep2 r3>/summary.tsv \
                       <D rep3 r1>/summary.tsv <D rep3 r2>/summary.tsv <D rep3 r3>/summary.tsv
```

Then, from `evals/`:

```
R=../evals/results/<run date>-holdout-a-re2ob
T=../agent/src/main/resources/topology/re2-ob.json
python3 -m kintsugi_evals escalate --a $R/merged/A.tsv --d $R/merged/D1.tsv --rule k=L2,t=off \
  --service-only --topology $T --seed 20261106 --out $R
python3 -m kintsugi_evals score --service-only --topology $T --seed 20261106 --iterations 10000 \
  --label 0 $R/merged/0.tsv --label 0R $R/merged/0R.tsv --label A $R/merged/A.tsv \
  --label D $R/merged/D.tsv --label C $R/C-summary.tsv --out $R
```

Check each summary first: 30 samples a run (60 for `merged/0`, `0R`, `A`, `D1`; 180 for `merged/D`), split `holdout`, view `tables`, report `plain`, `model` `rules-v1` / `rules-v2` / `granite4.1:8b` (`reportTimeout` 2100) / `claude-haiku-5-5`.

## Read once

- Holdout A is read once, by the runs above under this plan. A second read of any config is exploratory, says so in its own plan and in `docs/benchmark.md`, and never replaces the first.
- The result directory records the holdout name (`RCAEval holdout A, RE2-OB repetitions 2-3`) and this plan's path in a `notes.md` written with the result, since neither `score` nor `escalate` writes them. It also names the run directories behind each merged file.
- Nothing from this read is committed before 2026-12-07: `check-freeze.sh` condition 4 fails on any committed holdout A row dated earlier.
- M4's case counts: A runs here on 60 cases, which with holdout B (90) and lab holdout-2 (75) gives 225 ≥ 200; D runs here on 60, which with B (90), TT (90) and holdout-2 (75) gives 315 ≥ 300 (vision §7, ADR-032).
