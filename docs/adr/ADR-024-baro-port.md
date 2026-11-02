# ADR-024: BARO is a standard-library port, checked against the pinned package

## Status

Accepted by the agent on 2026-11-02, as a reversible implementation choice inside the human's approved scope (`odd/tasks/rcaeval-dev-baselines.md`, T6). The package version was checked on PyPI that day.

## Date

2026-11-02

## Context

- **Vision §2 and M3 name BARO** as the published baseline on RCAEval. ADR-016 deferred it there. The published implementation is `RCAEval.e2e.baro` in `RCAEval` 1.11.0 (MIT).
- **The function is short.** It is about 30 lines plus a preprocessing helper, and it needs only numpy, pandas and scikit-learn.
- **The package is not.** Its `core` extra pulls `causal-learn`, `tigramite`, `pingouin`, `statsmodels` and others; `default` adds `torch`.
- **`evals/` has no runtime dependencies.** The scorer uses the standard library only (ADR-015; `evals/requirements-dev.txt` holds just pytest).

## Decision

Port the function to `evals/kintsugi_evals/rcaeval/baro.py` with the standard library, and prove it equal to the pinned original on synthetic data.

The port's semantics, each from `RCAEval.e2e.baro.baro` and `RCAEval.io.time_series.preprocess` with a non-null dataset and `dk_select_useful` false:

1. Read `simple_metrics.csv`, keep samples within 600 s on each side of `inject_time` (vision §2). Samples with `time < inject_time` are normal, the rest anomalous.
2. In each segment separately, drop `time` and drop every column constant in that segment. A later `NaN` counts as different from the first value, as pandas' `!=` does.
3. Divide columns ending in `_mem` by 1e6.
4. Keep the columns present in both segments, in the normal segment's order.
5. Per column, `RobustScaler` as fitted on the normal segment: centre at the median, scale by the difference of the 75th and 25th percentiles with linear interpolation, ignoring `NaN`; a scale below 10 machine epsilons becomes 1 (`sklearn.preprocessing._data._handle_zeros_in_scale`), not only an exact 0.
6. A column's score is Python's `max` over its scaled anomalous values, in order, which is order-dependent on `NaN`. Sort descending and stably with Python's `sorted`.
7. A column's service is the text before its first `_`, with `-db` removed (`RCAEval.benchmark.scoring.to_service`). The service ranking keeps each service's first occurrence. `frontend-external` and `redis` remain their own names, as published.

**Proof of equality.** A generator run once, in a throwaway virtual environment with `RCAEval==1.11.0` installed without dependencies plus numpy, pandas and scikit-learn, writes goldens of the original's ranking and scores on synthetic series. They are committed as a fixture with the generator and its versions, and the port's test compares against them. The goldens never touch RCAEval case data.

**Output.** One `summary.tsv` the scorer reads as is: route `baro`, `reported` as `<service>|UNKNOWN` for the top service, `candidates` holding the top 5. BARO has no abstention. A case with no column left to rank, or with input the port refuses, fails the whole run and writes nothing, so no `UNKNOWN` is invented. The truth, taken from the case directory for the `truth` column, never reaches the ranking.

## Alternatives considered

- **A pinned install of the package in `evals/`.** The original code runs, so no port can drift. Rejected: dozens of transitive packages for one function, against ADR-015's standard-library scorer, and 1.11.0's `baro` still needs pandas and scikit-learn at every run.
- **Pinned install in a separate virtual environment for the measurement only.** Exact and isolated, but it makes `kintsugi_evals` depend on an environment nobody else has, and M4 would inherit it. The goldens give the same assurance on what they cover.
- **Port with no goldens.** Rejected: the `NaN`, zero-scale and constant-column branches are where a port silently differs.

## Consequences

- The port can differ from the original on inputs the goldens do not cover, and float parsing can differ by an ulp, which could swap two nearly tied columns. T8's plan adds a check of exact rankings on the 30 dev cases in the throwaway environment.
- **The port equals the function, not necessarily the published pipeline.** The wheel holds no benchmark entry point, so how the published evaluation windows and feeds `baro` was not seen. Published numbers are not comparable to this one until that is checked. Checked after T8 on the repository's `main.py` (`main` branch, 2026-11-02, not necessarily 1.11.0): before calling `baro` it drops `_latency-50` columns, turns infinities into `NaN`, then applies `ffill` and `fillna(0)`, and renames `_latency-90` to `_latency`. Its default `--length 20` gives the same 600 samples on each side. The port does none of these, so it is not yet the published baseline.
- The window is inclusive at both ends, while the converter's are half-open, so a sample exactly at +600 s reaches BARO and not the dossier.
- A new `RCAEval` release does not move this baseline. The pinned version is the one reported.
- BARO reads raw case files, so it gets the guard the converter has: only repetitions a committed plan names, and no holdout before M4.
- AC@3 and Avg@5 for comparison with the published numbers are outside this ADR.
