# Plan: baro-re2tt-reproduction

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/rcaeval-baro-reproduction.md`, T3. Port: ADR-024. Metrics: REQ-13.

## Status

First read of RE2-TT by this project. RE2-TT is in no split of vision §2, so it spends no holdout, and it has never been used to tune anything here. The target below is fixed before the run. Nothing is tuned against it: a miss is explained, not fitted.

## Date and commit

- Written: 2026-11-03
- Run on commit: the commit that adds this plan on `feat/rcaeval-baro-reproduction`, or a later one that changes nothing under `evals/kintsugi_evals/` or `evals/tests/fixtures/baro/`.

## Hypothesis

The port with the published pipeline gives Table 6's row for metric-based BARO on RE2-TT (RCAEval paper, `arxiv.org/html/2412.17015`).

## Cases and split

- RE2-TT, all repetitions: 90 cases, 15 per fault. Split `reference`.
- First read of a holdout: none.

## Configs

| Label | Config | How | Source |
|---|---|---|---|
| BARO | the port of `RCAEval` 1.11.0 with its pipeline (ADR-024) | `baro-rcaeval`, no model | a fresh run |

Config 0 does not run alongside, a deliberate exception to the standing rule: this measures the port against a published table, not a config against another, and Config 0 has no RE2-TT run. Vision §2 lists TT for Config 0 later. Cost: minutes of CPU, no model calls.

## Target

| Fault | AC@1 | AC@3 | Avg@5 |
|---|---|---|---|
| cpu | 0.47 | 0.8 | 0.72 |
| mem | 0.93 | 1 | 0.99 |
| disk | 1 | 1 | 1 |
| socket | 0.6 | 0.87 | 0.83 |
| delay | 0.47 | 0.67 | 0.63 |
| loss | 0.53 | 0.6 | 0.64 |
| AVERAGE | 0.67 | 0.82 | 0.8 |

The README's sample output agrees on Avg@5, and its sentence is one number short (feature document, Design).

## Metric

AC@1, AC@3 with Wilson 95% CIs and Avg@5, on the service, per fault and pooled (REQ-13). Pooled is the table's AVERAGE, since every fault has 15 cases. Chance from the run's `services.json`, the union of the services ranked: an upper bound on upstream's per-case `n`, so the floor printed is on the low side.

## Checks before reading the numbers

These close the two review WARNINGs on T1 (R3-silent-partial-re2-tt, R3-data-csv-not-conformance-checked). Any failure stops T3, and no score is committed until it is explained:

1. **No `data.csv` under `RE2-TT/`.** Upstream's `main.py`:153-155 reads `data.csv` if any exists, and both sides of `compare_dev.py` read `simple_metrics.csv`, so conformance could not see the difference.
2. **90 rows** in `summary.tsv`, 15 per fault. `baro-rcaeval` skips a misnamed directory silently; only `compare_dev.py` counts.
3. **`compare_dev.py --system re2-tt` exits 0** on all 90 cases against the pinned original, committed as `inputs/conformance.tsv`.

## Decision rule

Over the 21 cells of the target, comparing our value rounded to two decimals:

- **Reproduced:** every cell within 0.01. The tolerance covers rounding only.
- **Not reproduced:** any cell further off. The result is committed as it is, with the cells listed, and the human decides what follows. The conformance check separates the causes: the port equals the pinned original, so a miss means the pinned code differs from the code behind the table, or the data does.
- Either way, no port setting, window or filter changes to fit the table.

Reproduced supports the BARO column of RE2-OB, including `redis` first. It does not prove it: same code, another system.

## Command

From the repository root, with `<venv>` as `evals/tests/fixtures/baro/generate_goldens.py` describes:

```
rg --files --no-ignore -g 'data.csv' ~/data/rcaeval/RE2-TT | wc -l
<venv>/bin/python -I evals/tests/fixtures/baro/compare_dev.py --system re2-tt ~/data/rcaeval/RE2-TT <conformance.tsv>

cd evals
python3 -m kintsugi_evals baro-rcaeval --system re2-tt --rep all --out out/rcaeval/baro-re2-tt
python3 -m kintsugi_evals score --service-only \
  --topology out/rcaeval/baro-re2-tt/services.json \
  --label BARO out/rcaeval/baro-re2-tt \
  --out results/2026-11-03-baro-re2tt-reproduction
```

Then copy `conformance.tsv` and `services.json` into the result's `inputs/`.
