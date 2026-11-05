# HR with today's agent on RE2-OB dev (HT)

Plan: [`evals/plans/2026-11-05-hybrid-today-re2ob-dev.md`](../../plans/2026-11-05-hybrid-today-re2ob-dev.md), committed as `3269598` at 04:24:48Z. The HT run started at 04:25:37Z (`replay-20261105T042537Z-local`). Exploratory, dev only, one run.

## Mechanics

- 30 rows, every hybrid incident's `rules` is `v2`, 0 unrecorded calls.
- The 3 empty candidate lists end `UNKNOWN|UNKNOWN` with no model call.
- Status: 27 `COMPLETED`, 3 `INVALID_REPORT` (both report attempts invalid), 0 `TIMED_OUT`.
- Report time per sample: median 875 s, maximum 1,553 s. This is under the 1,800 s threshold, so the deadline rule does not fire.
- Cut at 4096 tokens: 5 report attempts in 3 incidents:
  - 2 attempts each on `loss/cd8184b1`, which ended `INVALID_REPORT`;
  - 2 attempts each on `loss/d4000574`, which also ended `INVALID_REPORT`;
  - 1 attempt on `mem/77586d7c`, which completed on its retry.
- **That is more than 3, so the plan's cap rule fires: the human revisits the cap.** The result is recorded unchanged.
- The fresh rules replays (`replay-20261105T042451Z-rules`, `replay-20261105T042456Z-rules`) match the committed 2026-11-03 summaries for 0 and 0R, with 0 mismatches over 30 rows.

## Numbers (AC@1 on the service, Wilson 95%)

| Config | AC@1 | Coverage | Selective |
|---|---|---|---|
| 0 | 4/30, 13% [5%, 30%] | 37% | 36% |
| 0R | 20/30, 67% [49%, 81%] | 90% | 74% |
| HR (2026-11-01) | 23/30, 77% [59%, 88%] | 83% | 92% |
| HT | 23/30, 77% [59%, 88%] | 80% | 96% |

- HT − 0R: HT only 3, 0R only 0, exact McNemar p = 0.25.
- HT − HR: 0 discordant cases each way, p = 1. Correctness is identical case by case.
  - The only change in an answer: `delay/880fd526`. HR answered `frontend` (wrong). HT ended `INVALID_REPORT` with no answer. Both are misses. This is why coverage drops by one and selective accuracy rises.
- HT is still below the 24/30 ceiling that 0R's candidate lists set.

## Kept first candidate

| Config | All 27 non-empty lists | 6 lists with more than one entry |
|---|---|---|
| HT | 21/27 | 1/6 |
| HR | 22/27 | 1/6 |

The difference is the `delay/880fd526` case.

## Verification levels (HT)

- Verified-correct: 21/27, 78% [59%, 89%]. 3 fault rows have no level; they are the `INVALID_REPORT` rows.
- L1 rate: 27/27.
- Of the 23 correct answers:
  - 21 are at L3;
  - `mem/4643c898` is at L2. The service is right but the kind is wrong (`DEPENDENCY_LATENCY`);
  - `mem/f16c5adc` is at L1.
- Wrong answers at L3: `delay/1644ae3e` names `checkoutservice`, a caller of the cause `currencyservice`. This is the same pattern as the open question on L3 (ADR-028).

## Caveats

- One run of a sampled local model. The dev cases have been seen before.
- The latency line fact's ratio-only rule was chosen post hoc on these 30 cases (ADR-028, Decision 5).
- The cap rule fired. Whether it is moved is the human's decision, and any rerun needs a new plan.
