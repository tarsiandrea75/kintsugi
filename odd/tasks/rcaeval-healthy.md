# rcaeval-healthy

## Objective

Build RCAEval healthy pseudo-cases (vision §2: "Healthy pseudo-cases come from windows before the injection") and decide, on dev, whether they are usable for H2b. A decision rule is committed before the run.

## Problem

- **H2b's sample is tiny.** H2b (A's false-alarm rate at most D's plus 10 points) rests only on lab holdout-2's 15 healthy controls. With 15 cases, one extra false alarm by A gives p ≈ 0.26, so H2b fails, and with it H2 (ADR-031, intersection-union).
- **The converter writes no pseudo-cases** (`evals/kintsugi_evals/rcaeval/convert.py`).
- **The data holds only 12 minutes before the fault.** Every RCAEval case (RE2-OB, RE2-SS, RE2-TT, all repetitions) records exactly 12 minutes before `inject_time`, by an aggregate timestamp check (2026-11-06). Fault cases use 10 + 10 minutes, so a pseudo-case with two windows before the injection must use 6 + 6 minutes.

## Why

The human chose (2026-11-06) to build the pseudo-cases rather than leave H2b on 15 cases. Cost is not a constraint: D costs about a tenth of a cent a case; A's CPU is the constraint. A dev check on D gates the choice, so that a useless window shape is caught before any holdout.

## Scope

- **Converter:** `convert-rcaeval --healthy` writes one pseudo-case per case.
  - The windows are `[inject − 12 m, inject − 6 m)` (previous) and `[inject − 6 m, inject)` (incident), with `sinceMinutes = 6`.
  - The truth is `NONE|NONE` and the fault is `healthy`.
  - The blinding salt is distinct from the fault case's.
  - The output goes to a separate out directory.
- **Replay:** `replay-agent.sh` accepts `--faults healthy` for re2-* datasets.
- **The dev check:** D (3 repeats), Config 0 and Config 0R on the 30 RE2-OB rep-1 pseudo-cases, under `evals/plans/2026-11-07-rcaeval-healthy-dev.md`.
- **Decision record:** an ADR that keeps or drops the pseudo-cases by the plan's rule, and amends the H1–H3 plan's H2b tracks if they are kept.

Out of scope: any holdout read; A on pseudo-cases (decided after the gate, with its CPU cost); any change under `agent/src/main/`.

## Constraints

- No change under `agent/src/main/` is needed. The gatherer takes every window from the incident's `sinceMinutes` (`DossierGatherer.java`:125-133), and the rules use ratios or small minimums.
- No holdout is read. No case directory name of a holdout is printed.
- The measurement rules apply: a plan before the run, Wilson CIs, results under `evals/results/`, Config 0 alongside.

## Design

- `SINCE_MINUTES` becomes a parameter of `convert_case`: 10 for fault cases, 6 for pseudo-cases. For a pseudo-case, `end = inject`, and the logs, traffic, metric window and step derive from it, so the 21 s step becomes 13 s.
- **Known bias:** a 6-minute window holds about 0.6× the events of a 10-minute one. The count floors (`SLOW_TIMEOUTS`, `REFUSING_ANSWERS`, `COUNT_MIN` = 3) then fire slightly less, which leans toward fewer false alarms.
- A − D stays a paired comparison on identical windows. The absolute false-alarm level is not comparable with the lab's.

## Tasks

- [x] T1 This document and the dev plan with its decision rule, committed before any run. Route: inline (docs only).
- [x] T2 Converter `--healthy` and `replay-agent.sh --faults healthy`, with tests first and docs. Route: delegated writer.
- [x] T3 The dev run: convert RE2-OB rep 1 healthy, run D ×3, 0 and 0R, score, and commit the results. Route: inline.
- [ ] T4 An ADR recording the gate's outcome. If the pseudo-cases are kept: the H2b tracks in `evals/plans/2026-11-06-h1-h3-verdicts.md`, the sample size and A's CPU cost (a human decision). Route: inline.

## Acceptance criteria

- The plan and its decision rule are committed before the first pseudo-case run.
- A pseudo-case's dossier windows end at `inject_time`, and no fault-period data enters it (tested on the fixture).
- The gate's outcome follows the plan's rule mechanically.

## Checks

- `evals/.venv/bin/python -m pytest evals -q`
- `infra/scripts/test-lib.sh`
- `cd agent && ./mvnw -q test`, which stays green with no `agent/src/main` change.

## Progress

- 2026-11-07: explored. 12 minutes of pre-injection data in every case; no agent change needed; the human chose the pseudo-cases with a dev gate on D.
- 2026-11-07: T1 done. The plan `evals/plans/2026-11-07-rcaeval-healthy-dev.md` gates on D's pooled false-alarm rate of at most 1/3, committed before any run.
- 2026-11-07: T2 done (delegated writer). RED: 6 failures and 6 errors; GREEN: pytest 547, test-lib PASS 248. A mutation check moved the end 30 s later and failed 5 tests. The fault-case output is pinned byte for byte. Dev smoke: 30 pseudo-cases, all `healthy` `NONE|NONE` `dev`, output timestamps from inject−720 s to inject−5 s. Finding: in all 30 dev pseudo-cases the 6 minutes before injection hold no WARN or ERROR log line, so the log facts are silent. That is a real property of healthy data, and it leans toward fewer false alarms (recorded for T4).
- 2026-11-07: slice (5a8c701..bfb46ff) native review, four lenses, standing consent: approved, acknowledged, burned.
- 2026-11-07: T3 done. The gate is KEPT: D pooled 9/90 false alarms (10%, Wilson [5.4%, 17.9%]); per repeat 0, 3 and 6; 0 and 0R gave 0/30 each; D cost $0.105. Deviations: D ran as 3 runs because of `MAX_CLOUD_SAMPLES`; `score` failed on a healthy-only run and was fixed in e8595a5 (test-first). Results in `evals/results/2026-11-07-rcaeval-healthy-dev/`, d69cf65.

## Next step

T4: the sample size of pseudo-cases for A (CPU), a human decision; then ADR-034 and the H1–H3 plan amendment.
