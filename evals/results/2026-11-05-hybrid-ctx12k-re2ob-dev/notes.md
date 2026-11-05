# HT with a 12288-token context window on RE2-OB dev (HC)

Plan: [`evals/plans/2026-11-05-hybrid-ctx12k-re2ob-dev.md`](../../plans/2026-11-05-hybrid-ctx12k-re2ob-dev.md), committed as `f8b598b` at 11:58:17Z. The HC run started at 11:58:42Z (`replay-20261105T115842Z-local`). Exploratory, dev only, one run.

## Mechanics

- **Rows:** 30, all with `numCtx` 12288. Every hybrid incident's `rules` is `v2`. There are 0 unrecorded calls, and the configuration is the plan's.
- **Empty lists:** the 3 empty candidate lists end `UNKNOWN|UNKNOWN` with no model call.
- **Status:** 30 `COMPLETED`. There are 0 `INVALID_REPORT` and 0 `TIMED_OUT`, against HT's 3 `INVALID_REPORT`.
- **Cut report attempts:** 0, for either cause (HT had 5).
- **Prompts:** the longest was 9,949 tokens. 3 report prompts passed 8192 tokens, and so did 5 calls counting prompt plus completion. Retry prompts are the long ones: the retry appends the rejection.
- **Time per sample:** median 961 s (HT 875 s), maximum 2,540 s (HT 1,553 s).
  - The maximum is `delay/880fd526`: two report calls, of 1,056 s and 1,479 s. No single call passed the 2,100 s deadline.
  - **The sample time passes 1,800 s, so the plan's deadline rule fires: the human revisits the deadline (ADR-023).**
  - Two samples passed 1,800 s: `delay/880fd526` at 2,540 s and `loss/d4000574` at 1,979 s.
- **Rules replays:** the fresh runs (`replay-20261105T115822Z-rules`, `replay-20261105T115827Z-rules`) match HT's committed 0 and 0R summaries, with 0 mismatches over 30 rows.

## Numbers (AC@1 on the service, Wilson 95%)

| Config | AC@1 | Coverage | Selective |
|---|---|---|---|
| 0 | 4/30, 13% [5%, 30%] | 37% | 36% |
| 0R | 20/30, 67% [49%, 81%] | 90% | 74% |
| HR | 23/30, 77% [59%, 88%] | 83% | 92% |
| HT | 23/30, 77% [59%, 88%] | 80% | 96% |
| HC | 24/30, 80% [63%, 90%] | 90% | 89% |

- HC reaches the ceiling of 24/30 set by 0R's candidate lists.
- **HC − HT:** HC only 1, HT only 0, p = 1.
  - The gain is `loss/cd8184b1`. HT ended `INVALID_REPORT` there, because its context window filled. HC answered `recommendationservice`, which is right.
  - In the other two `INVALID_REPORT` cases, HC answered wrongly:
    - `loss/d4000574`: HC answered `recommendationservice`, the truth is `productcatalogservice`;
    - `delay/880fd526`: HC answered `frontend`, the same wrong answer as HR.
  - Coverage rises from 80% to 90%, and selective accuracy falls from 96% to 89%.
- **HC − 0R:** HC only 4, 0R only 0, p = 0.125. This is the best p the plan allowed.
- **HC − HR:** HC only 1, HR only 0, p = 1.

## Kept first candidate

| Config | All 27 non-empty lists | 6 lists with more than one entry |
|---|---|---|
| HC | 23/27 | 2/6 |
| HT | 21/27 | 1/6 |

## Verification levels (HC)

- **Verified-correct:** 21/30, 70% [52%, 83%]. HT had 21/27, with 3 rows lacking a level.
- **L1 rate:** 30/30.
- **Of the 24 correct answers,** 21 are at L3 and 3 at L1: `cpu/dd26c6f8`, `mem/77586d7c` and `socket/6cf8e040`.
- **Wrong answers at L3:** 3.
  - `delay/1644ae3e` names a caller of the cause, as in HT.
  - `delay/880fd526` names `frontend`, a caller.
  - `loss/d4000574` names `recommendationservice`.
  - This is the open question on L3 (ADR-028).
- **Levels move between runs on the same answer.** HT and HC give the same answer in 4 cases where the level differs: `cpu/dd26c6f8`, `mem/77586d7c` and `socket/6cf8e040` fall from L3 to L1, and `mem/f16c5adc` rises from L1 to L3. The level depends on which lines the model cites, and that is sampled.

## Caveats

- These are single runs of a sampled local model, so the gain of 1 case could be noise. The dev cases have been seen before.
- The latency line fact's ratio-only rule was chosen post hoc on these 30 cases (ADR-028, Decision 5).
- The default window stays 8192 (ADR-010). Changing it would need an amendment, and the cost is the deadline rule firing above.
