# holdout2-guards

## Objective

Every check that guards a holdout covers holdout-2 as it covers holdout-1, before holdout-2 is recorded on 2026-12-01 to 05 (ADR-026).

## Problem

An audit on 2026-11-06 read every place that branches on a split. Most were fine, but three were not:
- **The scorer's policy check** counted only split `holdout` as holdout rows (`evals/kintsugi_evals/scoring.py:35`). The benchmark's no-risky-call assertion (ADR-027, Decision 3) would have passed over every holdout-2 row. Fixed in `1a3cf38`.
- **G1:** `check-freeze.sh` condition 4 finds a lab holdout-2 row only by a `split` column equal to `holdout-2` (`infra/scripts/check-freeze.sh:349-358`, `:380-382`). A committed table that lists holdout-2 recordings with no `split` column passes, although it read holdout-2.
- **G2:** condition 4 exempts any `split == "holdout"` row whose recording starts with `verify-agent-` (`check-freeze.sh:357`). Holdout-2 recordings have the same prefix. `record-replay.sh:201` also accepts a schedule-run file into `holdout` and drops its case. So a holdout-2-era recording imported as `holdout` by mistake, replayed and committed before M4, passes condition 4.

Neither G1 nor G2 can fire on what is committed today. No test covers either.

## Why

Condition 4 is M3's check that a holdout is read once (vision §7). It must fail closed before the data it guards exists.

## Scope

- **T1, the scorer (done):** `HOLDOUT_SPLITS = ("holdout", "holdout-2")`; REQ-15 and a scenario in `openspec/specs/evals/spec.md`.
- **T2, condition 4 (test-first):**
  - G1: a result row whose recording, or file stem, equals a holdout-2 recording of the manifest at HEAD is a holdout-2 read, whatever its columns.
  - G2: the holdout-1 exemption covers only the manifest's holdout-1 files at HEAD, not a name prefix.
- **T3, the import (test-first):** `record-replay.sh` refuses a schedule-run file (one that names a schedule case) into any split but `holdout-2` and `dev`. The `dev` import stays, as tested today.
- **T4, documentation:** `docs/setup.md` (condition 4 and the import rule), and the `ReplayRunTest` javadoc's split list.

Out of scope:
- a read-once guard in the lab replay path;
- the agent's code.

## Constraints

- No change under `agent/src/main/`: the freeze does not cover `infra/` or `evals/`.
- Condition 4's output for today's committed results is unchanged: it passes condition 4 as before.
- Conditions 1–3 are unchanged.

## Acceptance criteria

- AC1: a committed result table with no `split` column, listing a recording that is a holdout-2 row of the manifest, fails condition 4 with a line naming the file and the holdout-2 read.
- AC2: a `split == "holdout"` row on a `verify-agent-` recording that is not one of the manifest's holdout-1 files fails condition 4.
- AC3: a row on one of the manifest's holdout-1 files still passes condition 4.
- AC4: `record-replay.sh` refuses a case-named file into `holdout` (and `stress`, `probe`) with one `FAIL` line and writes nothing. It still accepts one into `dev` and `holdout-2`.
- AC5: `infra/scripts/test-check-freeze.sh` and the `record-replay.sh` tests pass, and so does today's `check-freeze.sh` condition 4 on HEAD.

## Checks

- `bash infra/scripts/test-check-freeze.sh`
- the `record-replay.sh` test suite (`infra/scripts/test-lib.sh` or its runner)
- `bash infra/scripts/check-freeze.sh` on HEAD: condition 4 passes, and condition 1 still fails on the missing tag, as today.

## Tasks

- [x] T1 2026-11-06: the scorer counts both holdout splits (`1a3cf38`). Route: inline. RED `test_both_holdout_splits_count_as_holdout_rows[holdout-2]`, GREEN, `evals` suite 418 passed. Assessed medium, `under_budget`.
- [x] T2 2026-11-06: condition 4, G1 and G2 (route: delegated writer; trigger: 2+ non-trivial files, plus the reading that prepares the write).
  - Condition 4 reads the manifest at HEAD: holdout-2 and holdout-1 recordings, keyed by file stem (a result row's `recording`, or the stem of its `file`).
  - A row is a holdout-2 read when its split is `holdout-2` or its recording is a holdout-2 one. The holdout-1 exemption is membership in the manifest's holdout-1 set. Every table with a `recording`, `file` or `split` column is read.
  - With no manifest, both sets are empty, so any `holdout` row fails (fails closed).
  - RED: AC1's case in `test-check-freeze.sh`, and AC2 on a throwaway repository (the runner stops at its first failure). GREEN: `test-check-freeze.sh` 62 cases, the parent's spot check the same.
- [x] T3 2026-11-06: the import refusal (route: the same writer). RED: `test-lib.sh` "a schedule run into holdout is refused" recorded it. GREEN: `test-lib.sh` 244 cases.
- [x] T4 2026-11-06: `docs/setup.md` (condition 4, the import rule, "Record holdout-2" step 4) and the `ReplayRunTest` javadoc (route: the same writer).
- `check-freeze.sh` on HEAD: condition 4 passes; conditions 1–3 fail on the missing tag, as before (AC5).

## Delivery

- Forecast: under 400 authored changed lines. Strategy `single-pr`, branch `fix/holdout2-policy-split` from `main`.

## Follow-ups

- The holdout-1 set is read at HEAD, so a later `holdout` import that names no schedule case (a `--rounds` run) would join it and be exempt. Reading the set at the tag closes it; it is not in this feature's scope.
- "Read once" has no code guard in the lab replay path, for `holdout` and `holdout-2` alike; only the plans and condition 4 hold it.

## Progress

- 2026-11-06: the audit (read-only explorer, 32 places) found G1 and G2; the parent re-read `check-freeze.sh:349-359` and `record-replay.sh:195-201`. The human authorized T2 to T4.
