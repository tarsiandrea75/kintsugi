# Results: lab-realism-dev-check

Plan: `evals/plans/2026-11-04-lab-realism-dev-check.md` (committed in `12a772f`, before the run). Feature: `odd/tasks/lab-realism-min.md`, T6.

## Run

- `verify-agent.sh --mode decision --route cloud --schedule evals/plans/2026-11-04-lab-realism-dev-check.schedule.tsv`, on `6d05227` (the plan's commit plus a task-list line; nothing under `agent/`, `lab/` or `infra/scripts/` changed). Run directory `verify-agent-20261104T112337Z` (git-ignored; its incidents are not imported). 2026-11-04, 11:23 to 14:27 UTC.
- `claude-haiku-5-5`, 73,733 prompt and 6,128 completion tokens over 9 report calls: about $0.011 at ADR-012's prices ($0.10 / $0.50 per million). The plan estimated $0.05.
- Files: `summary.tsv`, `scores/` (one score file per case), `injections.txt` (the run's `injections` rows, read as the `faults` role), `check-recordings.txt`.

## Checks

| # | Check | Outcome |
|---|---|---|
| 1 | Run: 9 lines with case and parameters; no `baseline failed`, oversized window or `not scored` | **Pass.** 9 `COMPLETED` lines, windows 3 to 4 min, exit 0. |
| 2 | Stored parameters | **Pass.** Each F1 row stores `{"latencyMs": L}` and each F2 row `{"latencyMs": 0, "errorStatus": S}` with the scheduled value, and its `root_cause` names it (`after 2 s` … `after 20 s`; `with 429 (rate limited)`, `500 (internal server error)`, `503 (service unavailable)`). |
| 3 | Truths (AC5) | **Pass.** Every F1 row `gateway-mock|DEPENDENCY_LATENCY`, every F2 row `gateway-mock|DEPENDENCY_REJECTING`, F3 `order-service|CONFIGURATION`, all `RESTORED`. Exactly 8 `injections` rows were written during the run, one per fault case: the healthy case injected nothing; its truth is `NONE|NONE`. |
| 4 | Windows (AC4) | **Pass.** `check-recordings.sh`: `RESULT: PASS (9 recording(s) clean)`, the healthy one included (previous window 4 `PAID` orders, incident window no restart). |
| 5 | Evidence, descriptive | See below. |

## Evidence (descriptive)

Payment-service's `/payments` latency in the dossier, previous → incident window, mean and max in seconds:

| Case | L (ms) | mean | max |
|---|---|---|---|
| F1-c1 | 2000 | 0.012 → 0.331 | 0.013 → 1.347 |
| F1-c4 | 5000 | 0.012 → 0.661 | 0.018 → 4.298 |
| F1-c7 | 10000 | 0.011 → 1.182 | 0.016 → 8.582 |
| F1-c9 | 20000 | 0.013 → 1.642 | 0.016 → 14.150 |

The latency grows with L. At 20000 ms gateway-mock's own `/authorize` max is 20.004 s, while payment-service's `/payments` max stops at about 14 s, below L: the caller gives up first. Each F2 incident shows its scheduled status: at 429 in payment-service's logs ("Gateway rejected the payment of order … with status 429"), at 500 and 503 in the client calls from payment-service to gateway-mock `/authorize` ("6 client calls end in 500"; D's summary at 503 cites D21 and D23). Their latencies do not move. The healthy and F3 cases show no latency change at `/payments`.

## D's answers (descriptive)

9 of 9 `MATCH`: every F1 and F2 case named `gateway-mock` with the right category, F3 `order-service|CONFIGURATION`, and the healthy control `UNKNOWN|UNKNOWN`. Nine cases give no useful interval, and this is not a measurement (the plan's Status): it says nothing about holdout-2.

## Decision

Checks 1–4 pass, so T6 is done by the plan's decision rule, and holdout-2 may be recorded as `docs/setup.md` says, after the freeze tag and the seed.
