# Feature: evidence settling between runs

## Objective

No incident's evidence window reaches back to a fault restored by an earlier check run.

## Problem

`verify-agent.sh` spaces its faults out: `space_out` (`infra/scripts/lib.sh:591`) waits until `INCIDENT_WINDOW_MARGIN_MINUTES + 1` minutes (11) have passed since the previous restore. But the restore time lives only in the shell (`previous_fault_end`, `verify-agent.sh:96`), so a new process starts with none and its first fault starts at once. `compare-agent-modes.sh` starts one `verify-agent.sh` per mode and round, back to back (`compare-agent-modes.sh:51-57`): the previous run's F3 restore is followed within minutes by the next run's F1, whose window (about 12 minutes, anchored at "now" on each tool call) holds F3's port-9999 lines. `docs/setup.md:402` admits the gap ("let a few minutes pass, or run it on a fresh lab").

## Why

The dossier F1 misses on the cloud route include `order-service|CONFIGURATION`, likely drawn by those lines (`odd/tasks/f3-caller-config.md`, T5 rounds). A lab leak makes every later measurement noisier, prompt changes included; it has to be ruled out before more scoring.

## Scope

- The restore time of the last fault is kept on disk, so `space_out` holds across processes: `compare-agent-modes.sh` rounds, `--only` runs and the single-fault checks (`verify-f1.sh`..`verify-f4.sh`) when they restore through `lib.sh`.
- Docs: `docs/setup.md` and the usage text of `verify-agent.sh`.

Out of scope (follow-ups):
- The dossier's "previous" window `[at - 2n, at - n)` (`DossierGatherer`) still reaches about 24 minutes back, past the 11-minute spacing.
- `status_summary` without `sinceMinutes` counts all orders, so F3's `PAYMENT_PENDING` rows stay as state.
- Anchoring windows to the injection time (an agent API change).

## Constraints

- Deterministic, in `infra/scripts`; no Java, no prompt change.
- Bash, tested in `infra/scripts/test-lib.sh` (test first).
- No API key read or printed; no cloud run without the human's go-ahead and a recorded cost.

## Tasks

- [x] **T1 — This document** and its Engram mirror.
- [x] **T2 — Persist the last restore time.** `lib.sh` records the epoch second of every confirmed restore in `infra/scripts/out/.last-fault-end` (git-ignored with `out/`); `verify-agent.sh` seeds `previous_fault_end` from it, so the first fault of a run waits like the others. A missing or unreadable file means no wait, as today. Tests first in `test-lib.sh`. Docs: `docs/setup.md`, `verify-agent.sh` usage. Route: delegated writer (2+ files).
- [x] **T3 — Confirm in a run** (with the human's go-ahead if on the cloud route): one `compare-agent-modes.sh` round; check the log shows the wait before the second run's F1 and that F1's evidence holds no port-9999 lines. Route: parent (runs).

## Acceptance criteria

- T2: two `verify-agent.sh` processes back to back wait 11 minutes from the first one's last restore before the second one's first traffic; a fresh `out/` waits nothing; `test-lib.sh` passes.
- T3: the second run's F1 step outputs carry no `9999`.

## Checks

- `bash infra/scripts/test-lib.sh`
- `shellcheck` on changed scripts, when installed.
- Per-commit native review assessment.

## Delivery

Forecast about 80 authored lines, one slice. Strategy `feature-branch-chain` if it grows. Branch `feat/evidence-settling`, from `feat/f3-caller-config` (`3e1bf58`).

## Progress

- 2026-10-19: T1, mapping done (read-only explorer): the leak is across processes, not inside one run.
- 2026-10-19: T2 (route: delegated writer, 2+ non-trivial files). `restore_injection` records every confirmed restore through `record_fault_end` (so `expect_restored`, `on_exit` and `restore_leftover_injection` all do); `last_fault_end` reads it back, empty on a missing, empty or non-numeric file; `LAST_FAULT_END_FILE` overrides the path. `verify-agent.sh` seeds `previous_fault_end` from it. RED: `test-lib.sh` failed with `record_fault_end: command not found` / `FAIL  a recorded restore time, in a directory created for it`. GREEN: `PASS  43 cases` (9 new, `space_out` tested with `sleep` stubbed). `shellcheck` not installed: skipped; `bash -n` clean.

- 2026-10-19: T2 done in `8fd134d`. Native assessment `3e1bf58..8fd134d`: high, review due; 4-lens review (standing consent granted), approved and acknowledged, no correction. Its three WARNINGs fixed inline (route: inline, small and understood): `verify-agent.sh` read the file before `start_lab` restored a leftover injection, so it now reads it in `diagnose` until the run's first restore; `last_fault_end` ignores a time in the future, which would wait without bound; `restore_injection`'s recording is now tested with `injector_call` stubbed. RED: `FAIL  a restore time in the future`; GREEN: `PASS  46 cases`. SUGGESTIONs left (non-atomic write, test trap and parse style, line references in this document).
- 2026-10-19: fixes in `c31d253`; native assessment high, 4-lens review approved and acknowledged. Its one WARNING fixed (test only): the recording case read an empty time in arithmetic, so a regression would abort the test script instead of printing FAIL; it now defaults to 0. GREEN: `PASS  46 cases`. SUGGESTIONs left (untested 12-digit cap and `diagnose` fallback). Fix in `d033a14`: assessment high, 4-lens review approved with no findings, acknowledged.

- 2026-10-19: T3, with the human's go-ahead: `compare-agent-modes.sh --route cloud --runs 1` on `7643856` (`infra/scripts/out/verify-agent-20261019T170520Z-explorer-cloud`, `-173217Z-dossier-cloud`); exit 1 (misses).
  - **Spacing holds across runs:** the dossier run waited 598 s before its F1, right after the explorer run's F3 restore. No `F1-incident.json` of either run mentions `9999` (only each F3 does). Acceptance met.
  - Scores, for the record (one sample per mode, so noise): explorer 1/3 (F1 MATCH; F2 `INVALID_REPORT`; F3 `payment-service|CONFIGURATION`), dossier 1/3 (F1 `payment-service|DEPENDENCY_LATENCY`; F2 MATCH; F3 `payment-service|CONFIGURATION`). Against the T5 rounds of `f3-caller-config` (explorer 5/6, dossier 4/6) this is lower, but F3 runs after in-run spacing that did not change, so this change cannot explain the F3 misses: the rates are within run-to-run noise at this sample size, the reason for the replay harness.
  - Tokens: 268,476 prompt and 14,470 completion, **$0.0341**. Total cloud spend about $0.31 of the human's $5.
- 2026-10-19: feature closed.

## Next step

None in this feature. Open for the human: the out-of-scope follow-ups above; the replay harness, to measure prompt changes on many samples instead of one round.
