# Plan: holdout-tt-re2tt

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/m4-plans.md`, T5. One of the four holdout tracks that feed the H1–H3 family plan, `evals/plans/2026-11-06-h1-h3-verdicts.md`; TT enters H1 only (ADR-032).

## Status

Pre-registered: committed before holdout TT is converted or replayed.

What is already known about these cases:

- **TT has been seen.** BARO's reproduction (`evals/plans/2026-11-03-baro-re2tt-reproduction.md`, ADR-024) read every RE2-TT case's truth label and BARO's ranks on it. Neither the agent nor the rules were tuned on it: no config of vision §5 has run on TT, and no rule, prompt or dossier choice was made with it in view (ADR-032). `docs/benchmark-limits.md` states this.
- The T4 dry check (`convert-rcaeval --check-only --allow-holdout TT`) read only directory names, header lines and inject times: 90 cases, 68 services.
- The topology `re2-tt.json` (68 services, 153 edges) was written from Train Ticket `v0.1.0` sources and header prefixes, with no case read, and drops the back edges `ts-seat-service` → `ts-travel-service` and → `ts-travel2-service` to break a call cycle (ADR-033). How many cases that touches is not inspected, since it would read truth.

## Date and commit

- Written: 2026-11-06
- Run on commit: <filled at run>. It must descend from the tag `freeze-m3`, with `infra/scripts/check-freeze.sh` passing on it, and change nothing under `agent/src/main/` since the tag.
- Run window: M4, 2026-12-07 to 2026-12-27. Holdout TT is read only from 2026-12-07 (ADR-032), though `check-freeze.sh` condition 4 does not yet detect TT rows (see Read once).

## Hypothesis

Descriptive on its own. This track feeds H1 only (D − 0R, service AC@1), pooled with holdouts A and B. A does not run on TT (68 services, beyond the local `num-ctx` of 8192), so TT enters neither H2 nor H3.

## Cases and split

- Split: RCAEval holdout TT, RE2-TT (Train Ticket) repetitions 1, 2 and 3, the whole of RE2-TT; manifest split `holdout` as the converter writes it.
- Cases: 90 fault cases over the three repetitions (6 faults), each repetition's count as `--check-only` prints it. No healthy pseudo-case.
- Traffic is empty: TT's spans are HTTP, and `traffic.py` reads Online Boutique's gRPC names only, so the converter writes an empty traffic answer, as for Sock Shop (`convert.py` docstring).
- **Case names carry truth.** An RE2-TT case directory is named `<service>_<fault>`. No command of this plan names a case: no `--case`, and no tooling output that prints a directory name. Conversion globs the directories, and recordings are blinded (sha256 of `RE2-TT/<case>/<rep>`). Any log or note that would show a directory name is not committed.
- First read of this holdout by any agent config: yes. Each config reads it once.
- Rerun rule: a run that stops before writing every sample is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun.

## Configs

All in decision mode, `tables` view, `plain` report, `--dataset re2-tt`, topology `classpath:topology/re2-tt.json` (ADR-033).

| Label | Config (vision §5) | Route, model, settings | Runs |
|---|---|---|---|
| 0 | Config 0, rules `v1` (ADR-016) | `rules`, no model | one per repetition |
| 0R | Config 0R, rules `v2` (ADR-021, ADR-022) | `rules`, no model, `--rules v2` | one per repetition |
| D | Config D | `cloud`, `claude-haiku-5-5` (ADR-012), 3 repeats per case (vision §6) | three per repetition, `--repeats 1` each |

- No A, so no C (ADR-032 Decision 1).
- **D and the cloud bound.** At most 60 samples a cloud run (`MAX_CLOUD_SAMPLES=60`, `replay-agent.sh`:159): D runs each repetition three times with `--repeats 1`, one repetition's cases a run, nine runs. A repetition over 60 cases is split by `--faults cpu,mem,disk` and `--faults delay,loss,socket`.
- The three configs read the same simplified topology, so ADR-033's choice affects them alike and D − 0R stays fair.
- Cost: D about $0.35 for 90 × 3 at RE2-OB dev's per-case cost; TT's 68-service dossier is larger, so up to a few times that, still inside the budget (ADR-032). If the first run's `aggregate.tsv` shows a per-case cost that would take TT's D above $1.5, the human is asked before the remaining runs.

## Metric

All on the service only (ADR-020), over the 90 fault cases:

- AC@1, AC@3, AC@5 and Avg@5, with their chance levels over `re2-tt.json`'s 68 services, each rate with a Wilson 95% CI; per fault and pooled.
- Abstentions, coverage and selective accuracy; D's verified-correct and L1 rate from the `level` column.
- Paired differences: D − 0R (H1's statistic on this track) and D − 0 by the case-level cluster bootstrap 95% interval (D has 3 repeats); 0R − 0 with exact two-sided McNemar.
- BARO's TT result from the reproduction is not part of this table; it ranked every repetition under split `reference`, and any comparison with it is exploratory.

## Decision rule

None on this track alone. The table is reported whatever it shows. H1 is decided only by `evals/plans/2026-11-06-h1-h3-verdicts.md`, with this track as input `TT`.

## Command

From `evals/`, convert the three repetitions (outputs `evals/out/rcaeval/re2-tt-rep{1,2,3}/`, git-ignored):

```
for REP in 1 2 3; do
  python3 -I -c 'import sys; sys.path.insert(0, "."); from kintsugi_evals.cli import main; sys.exit(main(sys.argv[1:]))' \
    convert-rcaeval --system re2-tt --rep $REP --allow-holdout TT
done
```

From the repository root, for each `REP` in 1, 2 and 3:

```
export REPLAY_DIR=evals/out/rcaeval/re2-tt-rep$REP
infra/scripts/replay-agent.sh --dataset re2-tt --route rules --mode decision --split holdout --rules v1
infra/scripts/replay-agent.sh --dataset re2-tt --route rules --mode decision --split holdout --rules v2
infra/scripts/replay-agent.sh --dataset re2-tt --route cloud --mode decision --split holdout --repeats 1   # repeat 1
infra/scripts/replay-agent.sh --dataset re2-tt --route cloud --mode decision --split holdout --repeats 1   # repeat 2
infra/scripts/replay-agent.sh --dataset re2-tt --route cloud --mode decision --split holdout --repeats 1   # repeat 3
```

Merge each config's runs into one summary per label, with `infra/scripts/merge-summaries.sh` as in `evals/plans/2026-11-06-holdout-a-re2ob.md`, from the repository root, into `$R/merged/` with `R=evals/results/<run date>-holdout-tt-re2tt`: `0.tsv`, `0R.tsv` (three runs each) and `D.tsv` (all nine D runs). Then, from `evals/`:

```
R=../evals/results/<run date>-holdout-tt-re2tt
python3 -m kintsugi_evals score --service-only --topology ../agent/src/main/resources/topology/re2-tt.json \
  --seed 20261106 --iterations 10000 \
  --label 0 $R/merged/0.tsv --label 0R $R/merged/0R.tsv --label D $R/merged/D.tsv --out $R \
  --holdout TT --plan plans/2026-11-06-holdout-tt-re2tt.md
```

Check each summary first: 90 rows in `merged/0` and `0R`, 270 in `merged/D`, split `holdout`, view `tables`, report `plain`, `model` `rules-v1` / `rules-v2` / `claude-haiku-5-5`.

## Read once

- Holdout TT is read once by agent configs, by the runs above under this plan. A second read is exploratory, labelled in its plan and in `docs/benchmark.md`, and never replaces the first.
- The result directory's `provenance.tsv` records the holdout name (`TT`, RCAEval holdout TT, RE2-TT), this plan's path and sha256 and the commit, written by `score` from `--holdout` and `--plan`; its `notes.md` names the run directories behind each merged file and any deviation, and names no case directory.
- Nothing from this read is committed before 2026-12-07. `check-freeze.sh` condition 4 checks holdouts A and B and lab holdout-2 rows, not TT's, so for TT this date is held by this plan, not by the check (an open follow-up of `odd/tasks/m4-plans.md`).
- Holdout TT on Config 0, 0R and D is never cut (ADR-032). D's 90 TT cases bring D to 75 + 60 + 90 + 90 = 315 ≥ 300.

## Amendments

- **2026-11-07, tooling only, before any holdout read ([`odd/tasks/m4-gaps.md`](../../odd/tasks/m4-gaps.md) T1–T2).** The inline `merge` shell function is replaced by `infra/scripts/merge-summaries.sh`, which also refuses an existing output, one input given twice and a row repeated byte for byte across inputs. The `score` command takes `--holdout TT --plan` this plan, so each writes `provenance.tsv` (holdout, plan path, plan sha256, git commit, UTC time) into its result directory, and refuses holdout rows without them (ADR-015). `notes.md` no longer records the holdout name and plan path; it still names the run directories and any deviation. No case, config, rule, margin, seed or count changes.
