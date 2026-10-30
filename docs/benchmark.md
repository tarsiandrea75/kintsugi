# Benchmark

Generated from committed results by `cd evals && python3 -m kintsugi_evals benchmark --out ../docs/benchmark.md results/2026-10-30-holdout1-lab`. Do not edit by hand: change the results and regenerate.

## 2026-10-30-holdout1-lab

| Config | F1 | F2 | F3 | pooled | Coverage | Selective |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 5/5, 100% [57%, 100%] | 0/5, 0% [0%, 43%] | 5/5, 100% [57%, 100%] | 10/15, 67% [42%, 85%] | 67% | 100% |
| A | 0/5, 0% [0%, 43%] | 2/5, 40% [12%, 77%] | 0/5, 0% [0%, 43%] | 2/15, 13% [4%, 38%] | 100% | 13% |
| D | 5/5, 100% [57%, 100%] | 5/5, 100% [57%, 100%] | 5/5, 100% [57%, 100%] | 15/15, 100% [80%, 100%] | 100% | 100% |

| Pair | Cases | Left only | Right only | McNemar p | Holm p | Difference (points) | Bootstrap 95% |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A - 0 | 15 | 2 | 10 | 0.039 | 0.077 | -53 | [-87, -13] |
| D - 0 | 15 | 5 | 0 | 0.062 | 0.077 | +33 | [+13, +60] |
| D - A | 15 | 13 | 0 | 0.00024 | 0.00073 | +87 | [+67, +100] |

Cluster bootstrap: seed 1, 10000 resamples.

Sources: 0 `../infra/scripts/out/replay-20261030T141651Z-rules` sha256 `2ef73d269e13d937befd382b7e8d7f064bf96523e30fc382619445fa068fb71d`; A `../infra/scripts/out/replay-20261030T141829Z-local` sha256 `b54217a07dbeb20a4c672fcf99e139f1d3297b869c2fd7819dc8897f8c95efaa`; D `../infra/scripts/out/replay-20261030T141713Z-cloud` sha256 `016ceea6d0107776f119b3e3f866aa13eb87dbbf57f532ed2512d5f4444c10b0`.
