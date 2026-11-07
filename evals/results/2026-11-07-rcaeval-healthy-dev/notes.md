# Notes: rcaeval-healthy-dev

Plan: [`evals/plans/2026-11-07-rcaeval-healthy-dev.md`](../../plans/2026-11-07-rcaeval-healthy-dev.md). Replays on commit `bfb46ff`, scored on `e8595a5`, 2026-11-07. Decision: [ADR-034](../../../docs/adr/ADR-034-rcaeval-healthy-pseudo-cases.md).

## Result

| Config | False alarms | Rate | Wilson 95% |
|---|---|---|---|
| 0 | 0/30 | 0% | [0%, 11%] |
| 0R | 0/30 | 0% | [0%, 11%] |
| D, repeat 1 | 0/30 | 0% | [0%, 11%] |
| D, repeat 2 | 3/30 | 10% | [3%, 26%] |
| D, repeat 3 | 6/30 | 20% | [10%, 37%] |
| **D pooled** | **9/90** | **10%** | **[5.4%, 17.9%]** |

- **Gate.** D's pooled rate is 9/90, at most the 30/90 the plan allows, so the pseudo-cases are **kept**.
- **D per case.** 23 cases have no false alarm, 5 have one and 2 have two. No case is flagged in all three repeats.
- **Cost of D.** $0.035 per repeat, $0.105 in all, at about 9000 prompt tokens per case.

## Observations

- **Repeats.** D's three repeats ran the same prompts and still gave 0, 3 and 6 false alarms. The variation between repeats is as large as the signal, which is why the protocol repeats cloud runs.
- **Logs.** The 6 minutes before injection hold no WARN or ERROR log line in any of the 30 cases. The log facts are silent on every pseudo-case, which is a real property of healthy data and makes the pseudo-cases somewhat easier than a busy healthy window.
- **Event floors.** A 6-minute window holds about 0.6× the events of a 10-minute one. Count floors such as `SLOW_TIMEOUTS` and `COUNT_MIN` fire less often, which leans toward fewer false alarms (`odd/tasks/rcaeval-healthy.md`, Design).

## Deviations from the plan's command

- **D's repeats.** `--repeats 3` makes 90 samples, above `MAX_CLOUD_SAMPLES=60`, so D ran as three `--repeats 1` runs. They are scored as labels D1, D2 and D3, and the gate pools them.
- **The scorer.** `score` refused configs that share no fault case. Fixed in `e8595a5`: a healthy-only run now gets no paired test, and configs with no shared case at all are still refused.
