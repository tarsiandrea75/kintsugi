# m4-gaps

## Objective

Close the tooling gaps the M4 plans rely on (`docs/STATE.md` item 22), before the first holdout read on 2026-12-07, so that every plan command runs as written and its results carry their provenance.

## Problem

- **Merging runs.** Holdout runs produce several summaries per config (repeats, fault splits), and `score` and `verdict` take one per label. The plans merge them with an inline shell function.
- **Provenance.** Nothing records the holdout name or the plan path in a result directory (ADR-015). The plans rely on a hand-written `notes.md`.
- **Verdicts.** `benchmark` renders score outputs only, not the H1–H3 verdicts.
- **Freeze check.** `check-freeze.sh` condition 4 does not cover holdout TT, and the BARO TT reproduction under `evals/results/2026-11-03-baro-re2tt-reproduction/` was committed before ADR-032.
- **Review advisories:**
  - `convert --check-only` can print an unknown prefix, which could name a root cause;
  - `score`'s healthy-only fallback catches a broad `ValueError` (`cli.py`:311-316);
  - the replay-agent missing-topology refusal has no test;
  - `openspec/specs/agent/spec.md`:693 lists only lab and re2-ob;
  - `baro-rcaeval` can read holdout TT unguarded.

## Scope

The items above. Out of scope: any holdout read or run, and any change under `agent/src/main/`.

## Constraints

- No holdout data is read. No holdout case directory name is printed.
- The plans are amended in the same change wherever a command changes, before any holdout read.
- Test-first wherever behaviour changes.

## Tasks

- [x] T1 `infra/scripts/merge-summaries.sh`: header equality, a refusal of duplicated `(split, recording, sample)` rows, refusal to overwrite, tests in `test-lib.sh`. The plans use it. Route: delegated writer.
- [x] T2 Provenance: `score`, `escalate` and `verdict` take `--holdout NAME --plan PATH`, write `provenance.tsv` (holdout, plan path, plan sha256, git commit) and refuse to run on a holdout without them. The plans are amended. Route: delegated writer.
- [x] T3 `benchmark` renders the verdict section from `verdict.tsv` when given a verdict directory. Route: delegated writer.
- [x] T4 `check-freeze.sh` condition 4 covers holdout TT, with an explicit, dated exemption for the BARO TT reproduction (ADR-032). `baro-rcaeval` needs `--allow-holdout TT` for TT. Route: delegated writer.
- [x] T5 Advisories: `--check-only` prints only the count of unknown prefixes; the narrow exception in `score`; a test-lib case for the missing-topology refusal; agent spec :693. Route: delegated writer.
- [x] T6 `docs/STATE.md`. Route: inline.

## Acceptance criteria

- Every command in `evals/plans/2026-11-0[67]-*` exists with the flags it uses.
- `pytest`, `test-lib.sh` and the agent suite stay green, and `check-freeze.sh` fails only on the missing tag.

## Delivery

The forecast is about 600 lines, so the strategy is `feature-branch-chain`. Slice 1 is T1–T3; slice 2 is T4–T6.

## Progress

- 2026-11-07: created from the STATE item 22 gap list (human go-ahead).
- 2026-11-07: T1–T3 done (delegated writer). RED: 32 failures plus an ImportError, and test-lib failing on the missing script. GREEN: pytest 603 passed, test-lib PASS 261; `docs/benchmark.md` regenerates unchanged. Deviations: merge-summaries refuses rows repeated byte for byte and inputs given twice, not `(split, recording, sample)`, because separate `--repeats 1` runs all write sample 1. `provenance.tsv` has one row per command and is appended to. The provenance guard exempts inputs read from a committed result's own `inputs/` copy (by path), so the holdout-1 results still regenerate; follow-up: require the file to be git-tracked. Twelve holdout commands in five plans carry `--holdout` and `--plan`; the plans are amended (tooling only, before any holdout read).
- 2026-11-07: slice 1 (main..b3da366) native review, four lenses, standing consent: approved, acknowledged, burned. Warnings folded into slice 2: awk reads an input name with '=' as an assignment (merge-summaries.sh:79); the committed-copy exemption matches by path only (provenance.py:67-70); score writes provenance after its outputs (cli.py:396-397); benchmark's H1 reading falls back to '-' (benchmark.py:171-173); a missing verdict seed is silent (:183-186); two vacuous test asserts (test_benchmark.py:230, :232).
- 2026-11-07: T4–T5 and the six slice-1 warnings done (delegated writer). RED observed for each; GREEN: pytest 618, test-lib PASS 265, test-check-freeze PASS 67 (test-lib does not run it). check-freeze fails only on the missing tag; condition 4 now covers TT and exempts only `2026-11-03-baro-re2tt-reproduction/` RE2-TT rows. With a bogus exemption it flags the 90 BARO rows, proving detection. The committed-copy exemption needs the file git-tracked and unchanged. `docs/benchmark.md` regenerates unchanged. T6: `docs/STATE.md` updated.

## Next step

Feature complete. Next: the freeze on 2026-12-01 (human tag), then the holdout-2 seed and recording.
