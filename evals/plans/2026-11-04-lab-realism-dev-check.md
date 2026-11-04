# Plan: lab-realism-dev-check

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/lab-realism-min.md`, T6 (AC4, AC5). Schedule: `evals/plans/2026-11-04-lab-realism-dev-check.schedule.tsv`.

## Status

A mechanics check, not a measurement: it records a hand-written dev schedule live, to show that the harness and the injector do what T4 and T5 say before holdout-2 is recorded on 2026-12-01. Its D scores are descriptive and decide nothing. The recordings are not imported into any split, so the manifest, lab dev and every committed result stay as they are, and Config 0 is not replayed on them: there is no measurement for it to run alongside.

## Date and commit

On the commit that adds this plan on `feat/lab-realism-min-02-harness`.

## Hypothesis

A scheduled case's parameter reaches the injector nested, is stored as applied, and changes neither the truth's service nor its category; a healthy control injects nothing and its windows pass `check-recordings.sh`.

## Cases and split

Nine hand-written cases, not drawn from a seed: every declared F1 value (2000, 5000, 10000, 20000 ms), every declared F2 status (429, 500, 503), one F3 and one healthy control, in the schedule's order. No split: not imported. No holdout is touched.

## Configs

| Label | Config | How |
|---|---|---|
| D | `claude-haiku-5-5`, route `cloud`, mode `decision` | `verify-agent.sh --mode decision --route cloud --schedule <the schedule>` |

One report call per case, about $0.05 in all. About 21 minutes per case, so about 3 h 15 min.

## Checks

1. **Run.** 9 lines in `summary.tsv`, each with its case and parameters; no `baseline failed`, no window larger than N_max, no `not scored`.
2. **Stored parameters.** For each fault case, the `injections` row (read as the `faults` role) has `parameters` `{"latencyMs": L}` for F1, `{"latencyMs": 0, "errorStatus": S}` for F2, the scheduled value, and a `root_cause` naming it.
3. **Truths (AC5).** Every F1 row's truth is `gateway-mock|DEPENDENCY_LATENCY`, every F2 row's `gateway-mock|DEPENDENCY_REJECTING`, F3's as before; the healthy case has no `injections` row and truth `NONE|NONE`.
4. **Windows (AC4).** `check-recordings.sh` on the run directory passes for all 9 incidents, the healthy one included.
5. **Evidence, descriptive.** In each F1 dossier, the gateway's latency in the incident window grows with L; in each F2 dossier, the status S appears. Recorded, not a pass condition.

## Metric

None is scored. D's 9 answers are listed with their truths, descriptively: 9 cases give no useful interval.

## Decision rule

- Checks 1–4 all pass: T6 is done, and holdout-2 may be recorded as `docs/setup.md` says.
- Check 1 fails on a lab or harness defect: fix it with a test, then record only the failed cases again with `--cases`.
- Check 2 or 3 fails: holdout-2 is not recorded until the injector is fixed and this check passes again.
- Check 4 fails on the healthy case: fix the check or the harness before holdout-2; a fault case's failure is read as for any recording.

## Results

`evals/results/2026-11-04-lab-realism-dev-check/`: `summary.tsv`, the score files, the `injections` rows of the run, the `check-recordings.sh` output and a `report.md` with the checks' outcomes and the run's cost. The incidents stay in `infra/scripts/out/` (git-ignored), since they are not imported.
