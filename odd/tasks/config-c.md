# config-c

## Objective

Config C (vision §5) is computed from the paired A and D outputs, with no new runs. Its escalation rule is fixed on dev before any holdout is read, so H3 ("C stays within 5 points of D while escalating at most 50% of cases") stays pre-registered.

## Problem

- C is computed nowhere (`docs/STATE.md`, journal 2026-11-06-01), so H3 cannot be tested.
- Vision §5 escalates on "abstention, an L2/L3 failure or low confidence (threshold set on dev)". Read literally, "an L2/L3 failure" means level below L3. On RE2-OB dev, A (AS, `evals/results/2026-11-05-l3-callee-recheck-re2ob/inputs/AS-summary.tsv`) is L0 1, L1 10, L2 9, L3 10. The literal rule escalates 20/30 (67%), so it fails H3's 50% limit by construction.
- A's top-1 `report.confidence` takes only 0.85, 0.90, 0.92 and 0.95 on RE2-OB dev, and 0.95 also appears on wrong answers. It lives only in the gitignored replay JSON (`infra/scripts/out/replay-*/`), not in `summary.tsv`.

## Why

H3 is one of the three pre-registered hypotheses, and C is the only configuration that prices escalation. If the rule were chosen after a holdout is read, H3 would no longer be pre-registered. Human decision (2026-11-06): the minimum level becomes the threshold set on dev, and an ADR amends the vision wording.

## Scope

- An ADR amending vision §5: C's escalation threshold is a minimum verification level (and optionally a confidence cut), chosen on dev by a stated procedure under the 50% cap.
- A `confidence` column in `summary.tsv` (top-1 `report.confidence`), plus a backfill of the RE2-OB dev AS and DS inputs from the local replay JSON.
- A living spec `openspec/specs/config-c/spec.md` and a committed dev plan.
- A pure function and a CLI subcommand that compute C from two summaries and a rule, with AC@1, escalation rate, Wilson CIs and the paired difference C − D.
- The dev run that fixes the rule, committed under `evals/results/`.

Out of scope: any holdout read; any change to A, D or Config 0's behaviour; H3's verdict (M4, `benchmark.md`); lab C until a levelled lab A run exists.

## Constraints

- **No holdout is read.** The rule is chosen on RE2-OB dev only, and is committed before any M4 run.
- **No new runs.** C uses existing A and D outputs, joined on `(split, recording)`.
- **Freeze.** Nothing under `agent/src/main/` changes (`infra/scripts/check-freeze.sh`). `ReplayDriver` lives under `agent/src/test/`.
- **Old results still score.** `confidence` is an optional column, as `level` was.
- **Measurement rules.** Plan committed before the run, Wilson 95% CIs, results under `evals/results/`, Config 0 alongside.

## Design

- **Escalation triggers** for one case, given A's row: A did not complete (any non-COMPLETED status), A abstained (`UNKNOWN|UNKNOWN`), A's level is below `k`, or A's confidence is below `t`. When a trigger fires, C takes D's row; otherwise it takes A's.
- **Grid.** `k ∈ {L2, L3}` and `t ∈ {off} ∪ {midpoints between consecutive distinct A confidences on dev}`.
- **Selection rule.** Among rules escalating at most 50% of dev cases, pick the highest C AC@1. Ties go to the fewer escalations, then to the higher `k`, then to `t = off`. If no rule fits under 50%, report that and fix `k = L3`, `t = off` (the literal vision rule).
- **Code.** `evals/kintsugi_evals/escalation.py` holds the pure function over `scoring.Row`s. `cli.py` gets an `escalate` subcommand; the statistics reuse `stats.wilson`, `stats.mcnemar_exact` and `scoring.paired`.

## Tasks

- [x] T1 ADR-030 C escalation rule, vision §5 amended, and this document's design confirmed. Route: inline (docs only).
- [x] T2 `confidence` column in `ReplayDriver`'s `summary.tsv`, a backfill script for the RE2-OB dev AS/DS inputs, and the scorer reading it optionally. Tests first. Route: delegated writer (Java test-side + Python + script).
- [x] T3 Living spec `openspec/specs/config-c/spec.md` and the plan `evals/plans/2026-11-06-config-c-dev.md`. Route: inline (docs only).
- [x] T4 `escalation.py`, the `escalate` CLI subcommand and the tests (RED first). Route: delegated writer.
- [x] T5 Dev run per the plan, results under `evals/results/2026-11-06-config-c-dev/`, chosen `(k, t)` recorded in ADR-030. Route: inline.
- [ ] T6 `docs/STATE.md` and the M4 plan follow-up updated. Route: inline.

## Acceptance criteria

- ADR-030 states the grid, the selection rule and the fallback, and is committed before T5's run.
- `python -m kintsugi_evals escalate` on the RE2-OB dev AS/DS inputs prints, for each grid rule, the escalation count, C AC@1 with Wilson 95% CI, and C − D paired.
- The chosen rule is recorded and is the one the selection rule picks. No holdout file is read.
- The old summaries without `confidence` still score unchanged (`pytest` green).

## Checks

- `evals/.venv/bin/python -m pytest evals -q`
- `cd agent && ./mvnw -q test -Dtest=ReplayDriverTest` (T2)
- `infra/scripts/check-freeze.sh` stays as it is today (no frozen path touched)

## Delivery

- Forecast is about 740 authored lines, which is over the 400-line budget, so the strategy is `feature-branch-chain` (standing choice).
- Slice 1 holds T1-T3 and slice 2 holds T4-T6.

## Progress

- 2026-11-06: explored. The facts are in Problem; the human chose the level threshold set on dev with an amending ADR.
- 2026-11-06: T1 done. ADR-030 written; vision §5 C row amended (structural readback, docs only). Commit bd73bf7; review assess: medium, under_budget (pending in slice 1).
- 2026-11-06: T2 done (delegated writer). RED observed (Python 7 failed + missing module; Java compile then 13 failures), GREEN: pytest 431 passed, ReplayDriverTest 43/0/0, `agent/src/main` untouched (check-freeze fails only on the missing `freeze-m3` tag, baseline). Backfill dry run: AS and DS 30/30 rows; AS confidences {0.85, 0.9, 0.92, 0.95}, DS {0.2..0.7}. DS JSON is in `replay-20261103T150905Z-cloud`.
- 2026-11-06: T3 done. Living spec `openspec/specs/config-c/spec.md` (REQ-1..4) and plan `evals/plans/2026-11-06-config-c-dev.md`, committed before any run (structural readback, docs only).
- 2026-11-06: slice 1 (main..7bacdc9) native review, 4 lenses, consent granted (standing): approved, acknowledged, authority burned (lineage review-94af93752127156d). Advisory, non-blocking, folded into T4: the spec escalates on a missing level but ADR-030 does not say so (R2); the backfill writes an empty confidence on a COMPLETED row whose JSON has no number, and a short row raises KeyError (R3/R4); `withoutLevel` helper name is stale (R2); Java non-numeric confidence untested (R3).
- 2026-11-06: T4 done (delegated writer). RED: missing module, 7 CLI failures, 3 backfill failures; GREEN: pytest 462 passed, ReplayDriverTest 44/0/0, `agent/src/main` untouched. Slice-1 advisories fixed (ADR-030 D1 'or has no level', strict backfill, renamed helper, Java test). Scratch smoke run on RE2-OB dev (not a result): only `k = L2, t = off` fits the cap, 11/30 escalated, C 22/30 vs D 26/30 (−13, McNemar p 0.29); `k = L3, t = off` gives 28/30 but escalates 20/30. Plan fix for T5: `score` copies inputs to `<out>/inputs/`, so the backfilled summaries go to `<out>/backfilled/` (SameFileError otherwise).
- 2026-11-06: slice 2a (7bacdc9..b56a7f5) native review, reliability lens, consent granted (standing): approved, acknowledged, burned (review-59a6c9b772e62776). Advisory: `escalate` can leave partial outputs if a later write fails (cli.py:499-505); no end-to-end test of a confidence cut (test_cli.py:406-427).
- 2026-11-06: T5 done on b56a7f5 with the human's go-ahead. Selected `k = L2, t = off`: 11/30 escalated, C 22/30 (73%) vs D 26/30, A 20/30, 0R 20/30, 0 4/30. No grid rule meets both H3 conditions on dev (notes.md). Recorded in ADR-030's amendment.

## Next step

T6.
