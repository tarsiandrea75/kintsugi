# Plan: verification-levels-descriptive

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/verification-levels.md`, T6. Levels: ADR-028.

## Status

Descriptive. No model call, no cloud call, no lab: existing answers are re-checked with today's `ReportCheck`. Committed before any re-check is run.

What is already known: DS 26/30 and AS 20/30 AC@1 on the service (`evals/results/2026-11-03-abstain-suspects-re2ob-dev/`, `evals/results/2026-11-03-a-settled-re2ob-dev/`); no stored report states a level, since both runs predate T3-T4.

## Date and commit

- Written: 2026-11-04
- Run on: the commit that adds the re-check driver (`LevelRecheck`) on `feat/verification-levels-04-level-report`, or a later one that changes nothing under `agent/src/main/` or `evals/kintsugi_evals/`.

## Hypothesis

None (descriptive). Two questions:

1. At which levels do the existing answers stand, per config and fault, and what are Verified-correct and the L1 rate?
2. How many correct answers stay below L3 only because their cause-owned citations are all log lines, which ADR-028 never compares (`NOT_COMPARED`)?

## Cases and split

Dev only. No holdout is read: the driver refuses any row whose `split` is not `dev`.

| Label | Dataset | Local run (git-ignored) | Committed as | Recordings |
|---|---|---|---|---|
| DS | RE2-OB dev, 30 cases | `infra/scripts/out/replay-20261103T150905Z-cloud` | `DS` of `evals/results/2026-11-03-abstain-suspects-re2ob-dev/` (`summary.tsv` sha256 `4118f181...`) | `evals/out/rcaeval/re2-ob-rep1-resources` |
| AS | RE2-OB dev, 30 cases | `infra/scripts/out/replay-20261103T151645Z-local` | `AS` of `evals/results/2026-11-03-a-settled-re2ob-dev/` (sha256 `5d84b32f...`) | same |
| D-M0 | lab dev, 15 cases | `infra/scripts/out/replay-20261029T010641Z-cloud` | `D-M0` of `evals/results/2026-10-28-service-assessment-v1-v2-dev/cloud/` (sha256 `908f6db0...`) | `agent/src/test/resources/replay` |

Each local `summary.tsv` is matched to its committed input by sha256 before the re-check; a local run that does not match is not used.

D-M0 is the latest lab dev run of D with the tables and the plain report. It predates the dossier's resources section (resource-facts) and the ranked report, so the guard below is expected to exclude it; it is listed so that the exclusion is recorded rather than assumed.

Not used: `evals/results/2026-11-04-lab-realism-dev-check/`. Its incidents would replay (their `DOSSIER` steps hold every call), but its cases have no split (its plan: "No split: not imported"), so the dev-only rule refuses them; its `summary.tsv` is `verify-agent.sh`'s, with no `split` or `recording` column and no replay row to copy; and Config 0 cannot run alongside it.

## Configs

| Label | Config | Source |
|---|---|---|
| 0, 0R | Config 0, rules v1 and v2 | `evals/results/2026-11-03-abstain-suspects-re2ob-dev/inputs/` (RE2-OB); `evals/results/2026-10-30-config0-dev/inputs/0-summary.tsv` (lab, only if D-M0 passes the guard) |
| DS | D: route `cloud`, `claude-haiku-5-5`, mode `decision`, view `tables`, report `plain` | the re-check of the run above |
| AS | A: route `local`, `granite4.1:8b`, mode `decision`, view `tables`, report `plain` | the re-check of the run above |
| D-M0 | D on the lab, as committed | the re-check of the run above, if it passes the guard |

Config 0's level is `-` by construction (it writes no cited evidence); its AC@1 rows are scored alongside as the scorer prints them.

## Method

`infra/scripts/recheck-levels.sh` runs `LevelRecheck` (`agent/src/test`, not frozen) over each input. For each incident file:

1. **Rebuild.** The typed dossier is gathered again from the case's recording, as `ReplayDriver` does (`DossierGatherer` over `ReplayLabTools`, the recording's window), on a clock fixed at the stored dossier's `Gathered at` instant, with the run's topology.
2. **Guard 1, the dossier.** `DossierRenderer.indexed` re-renders it; the dossier message (`Prompts.dossier` of the text) must equal the stored `DOSSIER` step's text byte for byte.
3. **Re-check.** `ReportCheck.check` runs on the last `REPORT` step's answer with the re-rendered text, its line facts and the topology, as the loop does in decision mode with the plain report.
4. **Guard 2, the evidence.** The re-check must accept the answer if and only if the run stored a report, and its verified evidence items must equal the stored report's, so the re-check reads the lines the run read.
5. **Write.** `summary.tsv` copies the original row and replaces only `level` (the run's columns, `level` added last when the run predates it); `levels-detail.tsv` lists each stored evidence item's line, owner, section and `changed`, and the sample's log-only flag.

**If a guard fails** for any incident, that input is excluded whole, and listed with its failing incidents and the first differing line. If every input is excluded, the run stops and is reported: the fallback, a cloud decision replay of lab dev (about $0.02), costs money and needs the human's approval, so it is not run under this plan.

## Metric

Per input that passes both guards, scored by `kintsugi_evals score` with Config 0 alongside:

- The level distribution per config and fault: L0, L1, L2, L3, `-`.
- Verified-correct (fault rows correct at L3, over fault rows) and the L1 rate (rows at L1 or above, over rows with a report), each with its Wilson 95% CI, as the scorer prints them; on RE2-OB "correct" is on the service (`--service-only`), as in the committed RE2-OB results.
- **The log-only count**, per fault: the correct answers below L3 whose cause-owned citations (evidence items citing a line owned by the reported cause service) are at least one and all `logs` lines with `changed` = `NOT_COMPARED`. Also reported: the correct answers below L3 for any other reason, by failed check.
- The guard: incidents that re-rendered byte for byte, and incidents excluded.

## Decision rule

None: the result is recorded whatever it shows. Whether a previous log window is added to the agent before the freeze is the human's decision; the report states the log-only count and nothing more.

## Command

From the repository root, then `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob \
  infra/scripts/out/replay-20261103T150905Z-cloud
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/recheck-levels.sh --dataset re2-ob \
  infra/scripts/out/replay-20261103T151645Z-local
infra/scripts/recheck-levels.sh --dataset lab infra/scripts/out/replay-20261029T010641Z-cloud

cd evals
R=results/2026-11-03-abstain-suspects-re2ob-dev/inputs
.venv/bin/python -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 $R/0-summary.tsv --label 0R $R/0R-summary.tsv \
  --label DS ../infra/scripts/out/<DS re-check> --label AS ../infra/scripts/out/<AS re-check> \
  --out results/2026-11-04-verification-levels-descriptive
```

The lab score, if D-M0 passes the guard, goes to `lab/` in the same result directory, with lab Config 0.

## Results

`evals/results/2026-11-04-verification-levels-descriptive/`: the scorer's files, the re-checks' `levels-detail.tsv` and `recheck.txt` (the guard's output), and `notes.md`.
