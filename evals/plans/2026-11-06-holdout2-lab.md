# Plan: holdout2-lab

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/m4-plans.md`, T5. One of the four holdout tracks that feed the H1–H3 family plan, `evals/plans/2026-11-06-h1-h3-verdicts.md`.

## Status

Pre-registered: committed before holdout-2's seed is drawn, before any holdout-2 recording, and before any replay.

What is already known about these cases: nothing. Holdout-2 does not exist yet. Its 75 cases are drawn from one seed after the freeze tag (ADR-026), so no one can choose them knowing the frozen agent.

## Date and commit

- Written: 2026-11-06
- Recording on commits: <filled at recording>, after the tag `freeze-m3`.
- Run on commit: <filled at run>. It must descend from the tag, with `infra/scripts/check-freeze.sh` passing on it (all four conditions, condition 3 with every schedule case recorded), and change nothing under `agent/src/main/` since the tag.
- Dates:
  - 2026-12-01: the human creates the tag `freeze-m3` (vision §7, M3).
  - After the tag, the same day: the seed is drawn and committed alone, then the schedule is generated and committed in its own commit (ADR-026; `docs/setup.md`, "Holdout-2 schedule").
  - 2026-12-01 to 2026-12-05: the 75 cases are recorded and imported into split `holdout-2` (`docs/setup.md`, "Record holdout-2"), and `check-freeze.sh` must pass.
  - 2026-12-07 to 2026-12-27 (M4): the replays and the scoring below. `check-freeze.sh` condition 4 forbids any committed holdout-2 row before 2026-12-07.

## Hypothesis

Descriptive on its own. This track feeds the family plan: H2a, H2b, H2c, H3a and H3b. It does not enter H1, which is read on the RCAEval holdouts.

## Cases and split

- Split: lab `holdout-2`.
- Cases: the 75 cases of `infra/holdout2/schedule.tsv`: 20 F1, 20 F2, 20 F3 and 15 healthy controls (ADR-026, `odd/tasks/lab-realism-min.md`). F1 draws `gateway_latency_ms` from 2000, 5000, 10000, 20000; F2 draws `gateway_error_status` from 429, 500, 503; F3 has `wrong_port` 9999; healthy has no parameter.
- One recording per case, recorded once. A failed recording is not imported, and its redo takes the same schedule case (condition 3 fails on a case with no row or two).
- Healthy truth is `NONE|NONE`: `UNKNOWN|UNKNOWN` is correct on it and any named cause is a false alarm (ADR-015, amendment of 2026-11-03).
- First read of this holdout: yes. Each config reads it once.
- Rerun rule: a replay run that stops before writing every sample is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun. A timeout of A at its deadline is A's answer.

## Configs

All in decision mode, `tables` view, `plain` report, `--dataset lab` (the default), split `holdout-2`, which replays `F1,F2,F3,healthy` by default (ADR-032 Decision 2).

| Label | Config (vision §5) | Route, model, settings | Runs |
|---|---|---|---|
| 0 | Config 0, rules `v1` (ADR-016) | `rules`, no model | one |
| 0R | Config 0R, rules `v2` (ADR-021) | `rules`, no model, `--rules v2` | one |
| A | Config A | `local`, `granite4.1:8b`, report deadline 600 s, the lab's default (ADR-023) | one |
| D | Config D | `cloud`, `claude-haiku-5-5` (ADR-012), 3 repeats per case (vision §6) | six, `--repeats 1` each |
| C | Config C, `k = L2, t = off` (ADR-030) | computed from A and D, no run | none |

- H and HR stay out: they enter none of H1–H3, and HR takes about 16 minutes a case (ADR-032). 0R enters no hypothesis on the lab; it keeps the table comparable with RCAEval.
- **D and the cloud bound.** At most 60 samples a cloud run (`MAX_CLOUD_SAMPLES=60`, `replay-agent.sh`:159). Each repeat is two runs, `--faults F1,F2` (40 samples) and `--faults F3,healthy` (35), so the 3 repeats take six runs; repeat r is the r-th pair.
- **C for display and C for the verdict.** `escalate` refuses a case twice on one side (`escalation.py`:77-78), so the C table here uses D's first repeat. The H3 verdict uses `verdict`'s in-process C with every D repeat (verdict spec, REQ-7). This is the first levelled lab A run, so C exists on the lab from it (ADR-032).
- Cost and time: A at most 12.5 h of CPU at the 600 s deadline; D about $0.25 for 75 × 3 at the lab's dev cost ($0.011 for 9 cases).

## Metric

- **Primary for the lab table: the full answer**, `service|category`, as the lab is scored (vision §6).
  - AC@1 per class (F1, F2, F3) and pooled over the 60 fault cases, with Wilson 95% CIs; abstentions, coverage and selective accuracy; verified-correct and the L1 rate.
  - False alarms on the 15 healthy cases, with a Wilson 95% CI, per config.
  - Paired differences of every two configs: exact two-sided McNemar where both have one outcome per case (0, 0R, A, C); the case-level cluster bootstrap 95% interval for any difference with D.
  - C: escalation count and rate (Wilson 95% CI) and each trigger's count, on D's first repeat.
  - A's time to report: median and maximum of `seconds`, healthy cases included.
- **Secondary: the service only.** The same table with `--service-only`. The pooled conditions H2a and H3a score the lab on the service only, like H1 (human decision of 2026-11-06, `odd/tasks/m4-plans.md`), so this is the scope in which the lab enters the family; the full answer stays this table's primary.
- All of these are descriptive; the family's tests run once, in the H1–H3 plan.

## Decision rule

None on this track alone. The table is reported whatever it shows. The H1–H3 decisions are taken only by `evals/plans/2026-11-06-h1-h3-verdicts.md`, with this track as input `holdout-2`, scored `--service-only`.

## Command

Recording (2026-12-01 to 05), from the repository root, after the tag, as `docs/setup.md` gives it:

```
head -c 32 /dev/urandom | xxd -p -c 64 >infra/holdout2/seed.txt
git add infra/holdout2/seed.txt && git commit -m "chore(holdout-2): draw the seed"
infra/scripts/holdout2-schedule.py infra/holdout2/seed.txt --out infra/holdout2/schedule.tsv
git add infra/holdout2/schedule.tsv && git commit -m "chore(holdout-2): generate the schedule"
# per session, in the schedule's order, until all 75 cases are imported:
infra/scripts/verify-agent.sh --mode decision --schedule infra/holdout2/schedule.tsv --cases <range>
infra/scripts/check-recordings.sh infra/scripts/out/verify-agent-<UTC time>
infra/scripts/record-replay.sh --split holdout-2 infra/scripts/out/verify-agent-<UTC time>
infra/scripts/check-freeze.sh     # must pass once every case is imported and committed
```

Replay (from 2026-12-07), from the repository root:

```
infra/scripts/replay-agent.sh --route rules --mode decision --split holdout-2 --rules v1
infra/scripts/replay-agent.sh --route rules --mode decision --split holdout-2 --rules v2
infra/scripts/replay-agent.sh --route local --mode decision --split holdout-2
for R in 1 2 3; do   # repeat R
  infra/scripts/replay-agent.sh --route cloud --mode decision --split holdout-2 --repeats 1 --faults F1,F2
  infra/scripts/replay-agent.sh --route cloud --mode decision --split holdout-2 --repeats 1 --faults F3,healthy
done
```

Merge D's runs, with `infra/scripts/merge-summaries.sh` as in `evals/plans/2026-11-06-holdout-a-re2ob.md`, from the repository root, into `$R/merged/` with `R=evals/results/<run date>-holdout2-lab`: `D1.tsv` (repeat 1's two runs) and `D.tsv` (all six). Then, from `evals/`:

```
R=../evals/results/<run date>-holdout2-lab
P="--holdout holdout-2 --plan plans/2026-11-06-holdout2-lab.md"
python3 -m kintsugi_evals escalate --a <A run> --d $R/merged/D1.tsv --rule k=L2,t=off --seed 20261106 --out $R $P
python3 -m kintsugi_evals score --seed 20261106 --iterations 10000 \
  --label 0 <0 run> --label 0R <0R run> --label A <A run> --label D $R/merged/D.tsv --label C $R/C-summary.tsv \
  --out $R $P
python3 -m kintsugi_evals escalate --a <A run> --d $R/merged/D1.tsv --rule k=L2,t=off --service-only \
  --seed 20261106 --out $R/service-only $P
python3 -m kintsugi_evals score --service-only --seed 20261106 --iterations 10000 \
  --label 0 <0 run> --label 0R <0R run> --label A <A run> --label D $R/merged/D.tsv \
  --label C $R/service-only/C-summary.tsv --out $R/service-only $P
```

`--topology` is not passed: the lab never takes it. Check each summary first: 75 rows for 0, 0R, A and `merged/D1`, 225 for `merged/D`, split `holdout-2`, view `tables`, report `plain`, A's `reportTimeout` 600.

## Read once

- Holdout-2 is read once, by the runs above under this plan. A second read is exploratory, labelled in its plan and in `docs/benchmark.md`, and never replaces the first.
- The result directory's `provenance.tsv` records the holdout name (`holdout-2`, the lab's), this plan's path and sha256 and the commit, written by both `escalate` and both `score` runs from `--holdout` and `--plan`; its `notes.md` records the seed commit, the run directories behind each merged file and any deviation.
- Nothing from this read is committed before 2026-12-07: `check-freeze.sh` condition 4 fails on any committed holdout-2 row dated earlier.
- M4's case counts: holdout-2 gives A and D 75 cases each, toward A 75 + 60 + 90 = 225 ≥ 200 and D 75 + 60 + 90 + 90 = 315 ≥ 300.

## Amendments

- **2026-11-07, tooling only, before any holdout read ([`odd/tasks/m4-gaps.md`](../../odd/tasks/m4-gaps.md) T1–T2).** The inline `merge` shell function is replaced by `infra/scripts/merge-summaries.sh`, which also refuses an existing output, one input given twice and a row repeated byte for byte across inputs. Both `escalate` and both `score` commands take `--holdout holdout-2 --plan` this plan, so each writes `provenance.tsv` (holdout, plan path, plan sha256, git commit, UTC time) into its result directory, and refuses holdout rows without them (ADR-015). `notes.md` no longer records the holdout name and plan path; it still records the seed commit, the run directories and any deviation. No case, config, rule, margin, seed or count changes.
