# ADR-023: Config A's report deadline on RCAEval is 2100 s

## Status

Accepted by the human on 2026-11-02, under the rule of `evals/plans/2026-11-02-a-deadline-re2ob-dev.md`. Amends `docs/vision.md` §5 (Config A). The default in `application.yaml` and the lab's 600 s are unchanged.

## Date

2026-11-02

## Context

- **At 600 s, A cannot answer on RE2-OB.** All 30 dev samples timed out, so its 0/30 measures the deadline (`evals/results/2026-11-02-baselines-re2ob-dev/`).
- **At 2100 s, it can.** All 30 finished: 19/30, 63% [46%, 78%], median 877 s, maximum 1,051 s (`evals/results/2026-11-02-a-deadline-re2ob-dev/`). The prompt is larger than the lab's, about 5,420 tokens against 3,240.
- **The rule was fixed before that run:** at least 15 of 30 finishing makes 2100 s the proposal, on whether A answers and not on whether it is right.

## Decision

- A's report deadline on RCAEval is **2100 s**: `--report-timeout 2100` on every RCAEval run of A. No other setting changes.
- 2100 s is the value the plan named, and H's and HR's deadline. It is not tightened towards the observed maximum.
- A timeout at this deadline remains A's answer, recorded, not rerun.

## Alternatives considered

- **Keep 600 s.** No CPU cost, but every RCAEval case would time out on this evidence, and H1's secondary test and H2 would be decided by the deadline.
- **A tighter value, such as 1200 s.** It bounds the worst case, but it would be fitted to these 30 cases. Holdout B is a different system with a different dossier size, so a margin is what makes the measurement hold there.

## Consequences

- H2's time limit stays as written: A's median report time at most 15 minutes. On dev it is 14.6 minutes, so that condition may fail on the holdouts, and the deadline does not relax it.
- M4's budget: at the dev median, the 200 holdout cases its check requires take about 49 h of CPU, out of the roughly 19 nights vision §7 gives all recordings and local runs.
- Dev's 19/30 is exploratory, because the deadline changed after A's first result on that split. No holdout has been read, so the holdouts are unaffected.
- The two tracks differ in A's deadline, and `docs/benchmark.md` must say so wherever it compares them.
