# Results: verification-levels-ratio

Plan: `evals/plans/2026-11-04-verification-levels-descriptive-ratio.md` (committed in `3ede962`, before the re-check), an addendum to T6's plan. Feature: `odd/tasks/verification-levels.md`, T7. Levels: ADR-028, Decision 5 as amended on 2026-11-04. Compared with T6: `evals/results/2026-11-04-verification-levels-descriptive/` (untouched).

Descriptive only: no hypothesis, no decision rule, no gain claimed. No model call, no cloud call, no lab; no holdout was read. **Post hoc**: the latency rule was changed after seeing T6's levels on these same 30 dev cases, so these numbers describe the new rule on the data that motivated it; they do not test it.

## Run

- The one change: a latency line fact is `changed` = `YES` when the incident mean is at least 2 × the previous mean, whatever its seconds (before: also at least 0.5 s, R-DOWN's floor). Commit `0788c6c` (`fix(dossier): judge a latency change by its ratio alone`); `agent/src/main/java/dev/kintsugi/agent/rules` is not edited.
- Inputs, T6's, matched by sha256 of `summary.tsv`: DS `infra/scripts/out/replay-20261103T150905Z-cloud` (`4118f181...`), re-checked into `recheck-20261104T221824Z-replay-20261103T150905Z-cloud`; AS `replay-20261103T151645Z-local` (`5d84b32f...`), into `recheck-20261104T221828Z-replay-20261103T151645Z-local`; D-M0 `replay-20261029T010641Z-cloud` (`908f6db0...`), into `recheck-20261104T221832Z-replay-20261029T010641Z-cloud`.
- Scored with Config 0 and 0R alongside, on the service (`--service-only`, RE2-OB topology), with T6's command and this directory as `--out`. Files as T6's: the scorer's, `DS-` and `AS-levels-detail.tsv`, `DS-`, `AS-` and `D-M0-recheck.txt`.

## Guard

| Input | Incidents | Re-rendered byte for byte, evidence equal | Refused |
|---|---|---|---|
| DS | 30 | 30 | 0 |
| AS | 30 | 30 | 0 |
| D-M0 (lab dev) | 15 | 0 | 15, as in T6 |

D-M0 is excluded whole again; the lab has no input. Each `summary.tsv` equals T6's in every column but `level`. `scores.tsv`, `paired.tsv` and `ranked.tsv` equal T6's byte for byte, so AC@1 and Config 0's verdicts are unchanged (DS 26/30, AS 20/30, 0 4/30, 0R 20/30); `report.txt` differs from T6's only in the two Verified-correct lines.

## Levels and rates, against T6

| Config | Rule | L0 | L1 | L2 | L3 | Verified-correct | L1 rate |
|---|---|---|---|---|---|---|---|
| DS | T6 (0.5 s floor) | 0 | 4 | 8 | 18 | 18/30, 60% [42%, 75%] | 30/30, 100% [89%, 100%] |
| DS | ratio only | 0 | 4 | 0 | 26 | 25/30, 83% [66%, 93%] | 30/30, 100% [89%, 100%] |
| AS | T6 (0.5 s floor) | 1 | 10 | 9 | 10 | 10/30, 33% [19%, 51%] | 29/30, 97% [83%, 99%] |
| AS | ratio only | 1 | 10 | 8 | 11 | 11/30, 37% [22%, 54%] | 29/30, 97% [83%, 99%] |
| 0, 0R | either | - | - | - | - | n/a | n/a |

The L1 rate cannot move: the change touches L3 only.

Per fault (L0/L1/L2/L3), ratio only, with T6 in brackets where it differs:

| Fault | DS | AS |
|---|---|---|
| cpu | 0/0/0/5 (0/0/1/4) | 0/0/3/2 (0/0/4/1) |
| delay | 0/0/0/5 (0/0/4/1) | 0/3/1/1 |
| disk | 0/2/0/3 | 0/2/1/2 |
| loss | 0/1/0/4 (0/1/1/3) | 0/2/0/3 |
| mem | 0/0/0/5 (0/0/2/3) | 0/3/1/1 |
| socket | 0/1/0/4 | 1/0/2/2 |

## Moves

Every move is L2 -> L3, and every moved answer had `cause-fact-changed` as its only failing check in T6. No answer moved down.

| Config | Moved | delay | mem | cpu | loss | of which incorrect |
|---|---|---|---|---|---|---|
| DS | 8 | 4 | 2 | 1 | 1 | 1 (mem) |
| AS | 1 | 0 | 0 | 1 | 0 | 0 |

The moved correct answers' cause latency rows, for example: delay `0.004 → 0.087`, `0.002 → 0.048`, `0.003 → 0.039` s; cpu `0.004 → 0.036` s; mem `0.003 → 0.183` s; loss `0.003 → 0.026` s.

## An incorrect answer at L3

**Yes, one.** DS, mem, recording `77586d7c`: the truth is productcatalogservice, the answer recommendationservice (`MISS`), and it cites recommendationservice's latency row `0.008 → 0.183` s (23×), now `YES`, so it reaches L3. recommendationservice calls productcatalogservice, so its latency rose with the true cause's. Every L3 answer was correct in T6 (DS 18/18, AS 10/10); now DS 25/26 [81%, 99%] and AS 11/11 [74%, 100%]. Verified-correct counts only correct answers by definition (25 of DS's 26 correct are at L3), so it stays a subset of correct; what changes is that L3 alone no longer implies a correct answer on this data. L3 never claimed that: it says the claimed cause owns a cited fact that changed, and here that fact did change.

## How discriminating the latency rule is now

Over DS's 30 stored dossiers (300 latency rows, 10 per incident): the true cause's row is `YES` in 25 of 30 incidents with the ratio alone, against 5 of 30 with the floor; another service's row is `YES` in 30 of 270, against 13 of 270. So the ratio-only rule marks the cause's latency changed far more often, and other services' latency about twice as often as before, which is how a caller of the cause, as above, can reach L3. AS reads the same recordings, so the same counts hold for its dossiers' rows.

## Correct answers still below L3

- DS: 1 of 26, `loss` (`owners-on-path` and `cause-fact-changed`, log-only), T6's log-only answer.
- AS: 9 of 20: `owners-on-path` in 6 (mem 2, disk, delay, loss 2, one of them log-only), `cause-fact-changed` alone in 2 (socket), and the socket answer at L0.

The log-only count is unchanged: DS 1/26, AS 1/20, both `loss`, both also failing `owners-on-path`.

## Reading

With the floor removed, the latency rule lifts 7 of DS's 8 correct answers below L3 and 1 of AS's 10, and moves one wrong DS answer to L3, a caller of the true cause whose latency rose with it. On this post hoc data the trade is 7 correct answers verified for 1 incorrect one verified; whether L3 should also require that no caller-side explanation fits is a separate design question, not decided here. The lab still has no admissible input.
