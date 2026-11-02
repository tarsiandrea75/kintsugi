# rcaeval-baro-reproduction

## Objective

Reproduce BARO's published numbers on RE2-TT with our port, to check the port against a number someone else published.

## Problem

The port matches the pinned original on 30 RE2-OB cases, but no published number on RE2-OB was found, so its 76% Avg@5 rests on our own pipeline reading. A conformance check proves the port equals the code we ran, not that we ran it the way the authors did.

## Why

A baseline is only worth comparing against if it is the baseline. Reproducing a published table is the check that catches what conformance cannot: the wrong window, the wrong preprocessing, the wrong aggregation. If it matches, BARO's column can be trusted, including `redis` first on RE2-OB. If it does not, every comparison with BARO is suspect until explained.

## Scope

- `baro-rcaeval` on RE2-TT, every repetition, with the guards on RE2-OB and RE2-SS unchanged.
- `compare_dev.py` on RE2-TT, against the pinned original.
- The living spec, ADR-024's and `docs/setup.md`'s data notes, a plan and a result.

Out of scope: Config 0 and D on TT (vision §2 lists them for later), a `redis` filter, any holdout, any other published baseline.

## Constraints

- RE2-TT is in no split of vision §2, so reading it spends no holdout. It is not `dev` either: its rows carry the split `reference`.
- RE2-OB repetitions 2–3 and RE2-SS stay refused, with their tests.
- The plan is committed before the run. Published values are fixed in it.
- Standard library only in `kintsugi_evals`.

## Design

Target: Table 6 of the RCAEval paper (`arxiv.org/html/2412.17015`), metric-based BARO on RE2-TT. 90 cases, so 15 per fault:

| Fault | AC@1 | AC@3 | Avg@5 |
|---|---|---|---|
| CPU | 0.47 | 0.8 | 0.72 |
| MEM | 0.93 | 1 | 0.99 |
| DISK | 1 | 1 | 1 |
| SOCKET | 0.6 | 0.87 | 0.83 |
| DELAY | 0.47 | 0.67 | 0.63 |
| LOSS | 0.53 | 0.6 | 0.64 |
| AVERAGE | 0.67 | 0.82 | 0.8 |

The upstream README's sentence gives "0.64, and 0.8" for delay and loss, one number short: its own sample output and the table agree on DELAY 0.63, LOSS 0.64, and 0.8 is the AVERAGE column.

- **`--rep all`, on `re2-tt` only.** Upstream pools every repetition, so one run writes all 90 rows into one `summary.tsv`, and the scorer's per-fault lines give the table's rows. `re2-ob` and `re2-ss` refuse it.
- **`main.py`'s dataset branches are checked, not assumed.** The port was written for RE2-OB. Upstream picks an SLI by dataset path and passes `dataset` to `preprocess`. T1 reads `main.py`, `RCAEval.e2e.baro` and `preprocess` at the pinned commit, and states which branches reach RE2-TT.
- **Chance.** `--topology` needs a file with a `services` list. The run writes `services.json` next to `summary.tsv`, the union of the services it ranked. Upstream's `n` is per case, so this is an upper bound on `n`, and the chance printed a lower floor; the plan says so.

## Tasks

- [ ] T1 The port on RE2-TT, `compare_dev.py`, spec and docs, tests first. Route: delegated writer (several non-trivial files).
- [ ] T2 The plan, with the published values and the decision rule. Route: inline (docs).
- [ ] T3 The run, conformance first, and the result. Route: inline commands.

## Acceptance criteria

- AC1: a synthetic RE2-TT case gives the ranking the pinned original gives (goldens), and `--rep all` writes every repetition's rows.
- AC2: `re2-ob` repetitions 2–3, `re2-ss` and `--rep all` off `re2-tt` are refused, with tests.
- AC3: conformance on all 90 cases: same service rankings as the pinned original.
- AC4: the result sets our AC@1, AC@3 and Avg@5 per fault next to Table 6, with Wilson CIs on the proportions.
- AC5: whatever the outcome, it is recorded and explained; no setting is changed to fit the table.
- AC6: no holdout is converted or opened.

## Checks

- T1: `cd evals && .venv/bin/python -m pytest -q`, red first.
- T2: structural readback.
- T3: the plan's decision rule.

## Delivery

Forecast: about 300 authored lines for T1, about 100 for T2–T3, so about budget. Strategy `feature-branch-chain` if it goes over. Branch `feat/rcaeval-baro-reproduction`, from `feat/rcaeval-ranked-metrics`. Nothing is pushed.

## Progress

- 2026-11-02: the human chose this feature. RE2-TT.zip, 2.8 GB, Zenodo record 14590730, md5 `a7fbcd1ada406067dcc50771ae398408`.

## Next step

T1.
