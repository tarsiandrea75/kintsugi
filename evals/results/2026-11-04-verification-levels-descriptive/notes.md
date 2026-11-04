# Results: verification-levels-descriptive

Plan: `evals/plans/2026-11-04-verification-levels-descriptive.md` (committed in `b0274d2`, before any re-check). Feature: `odd/tasks/verification-levels.md`, T6. Levels: ADR-028.

Descriptive only: no hypothesis, no decision rule, no gain claimed. No model call, no cloud call, no lab; no holdout was read (every row re-checked is `dev`).

## Run

- Driver: `LevelRecheck` (`agent/src/test`), added in `c749f94`, run by `infra/scripts/recheck-levels.sh` on that commit. Nothing under `agent/src/main/` or `evals/kintsugi_evals/` changed since `85f5de7`.
- Inputs, matched to their committed copies by sha256 of `summary.tsv`:
  - DS: `infra/scripts/out/replay-20261103T150905Z-cloud` = `DS` of `2026-11-03-abstain-suspects-re2ob-dev` (`4118f181...`), re-checked into `recheck-20261104T203933Z-replay-20261103T150905Z-cloud`.
  - AS: `infra/scripts/out/replay-20261103T151645Z-local` = `AS` of `2026-11-03-a-settled-re2ob-dev` (`5d84b32f...`), re-checked into `recheck-20261104T203941Z-replay-20261103T151645Z-local`.
  - D-M0, lab dev: `infra/scripts/out/replay-20261029T010641Z-cloud` (`908f6db0...`), re-checked into `recheck-20261104T203948Z-replay-20261029T010641Z-cloud`.
- Scored with Config 0 and 0R alongside, on the service (`--service-only`, RE2-OB topology), as the plan's command says.
- Files: the scorer's (`report.txt`, `scores.tsv`, `paired.tsv`, `ranked.tsv`, `inputs.tsv`, `inputs/`); `DS-` and `AS-levels-detail.tsv` (one row per stored evidence item); `DS-`, `AS-` and `D-M0-recheck.txt` (the guards' outcome per incident).

## Guard

| Input | Incidents | Re-rendered byte for byte, evidence equal | Refused |
|---|---|---|---|
| DS | 30 | 30 | 0 |
| AS | 30 | 30 | 0 |
| D-M0 (lab dev) | 15 | 0 | 15: the dossier differs at message line 73 (10) or 75 (5) |

D-M0 is excluded whole, as the plan expected: its stored dossier predates the latency section's current heading and the resources section. So **the lab has no input** in this result, and the log-only question is answered on RE2-OB dev only. Each `summary.tsv` written equals its input in every column but `level`, added last (checked with `cmp` on the rows without their last column).

## Levels and rates

AC@1 on the service as committed: DS 26/30, AS 20/30; 0 4/30, 0R 20/30 (their level is `-`).

| Config | n | L0 | L1 | L2 | L3 | - | Verified-correct | L1 rate |
|---|---|---|---|---|---|---|---|---|
| DS | 30 | 0 | 4 | 8 | 18 | 0 | 18/30, 60% [42%, 75%] | 30/30, 100% [89%, 100%] |
| AS | 30 | 1 | 10 | 9 | 10 | 0 | 10/30, 33% [19%, 51%] | 29/30, 97% [83%, 99%] |
| 0, 0R | 30 each | - | - | - | - | 30 | n/a | n/a |

Per fault (L0/L1/L2/L3):

| Fault | DS | AS |
|---|---|---|
| cpu | 0/0/1/4 | 0/0/4/1 |
| delay | 0/0/4/1 | 0/3/1/1 |
| disk | 0/2/0/3 | 0/2/1/2 |
| loss | 0/1/1/3 | 0/2/0/3 |
| mem | 0/0/2/3 | 0/3/1/1 |
| socket | 0/1/0/4 | 1/0/2/2 |

Every L3 answer is correct on the service: DS 18/18 [82%, 100%], AS 10/10 [72%, 100%]. Wrong answers stand at L1 or L2.

## Correct answers below L3

| Config | Correct | Below L3 | of which log-only | Log-only among correct |
|---|---|---|---|---|
| DS | 26 | 8 | 1 (loss) | 1/26, 4% [1%, 19%] |
| AS | 20 | 10 | 1 (loss) | 1/20, 5% [1%, 24%] |

Log-only: the evidence items citing a line the reported cause owns are at least one and all `logs` lines (`NOT_COMPARED`). Per fault the count is 0 everywhere but `loss`, 1 for each config.

Both log-only answers also fail L2's `owners-on-path` (DS's cites the latency section's heading line, owned by `header`; AS's cites recommendationservice's traffic, off emailservice's call path), so counting their log lines as changed would not lift them to L3. The correct answers whose only failing check is `cause-fact-changed` and that cite a cause-owned log line: DS 1 (loss, `74b7f07d`, which also cites the cause's latency and resource rows, both `NO`), AS 0. That is the number of answers a compared previous log window could lift to L3, at best, on this data.

The other correct answers below L3, by what keeps them there:

- **`cause-fact-changed` alone, on latency, resource or traffic rows marked `NO`**: DS 7 (delay 4, cpu 1, mem 1, loss 1), AS 3 (cpu 1, socket 2). On delay the cause's own latency rises 10 to 25 times (for example `0.004 → 0.087` s, `0.002 → 0.048` s) but stays below R-DOWN's 0.5 s floor, which the latency rule reuses (ADR-028, Decision 5); only the case whose cause reaches `2.043` s passes.
- **`owners-on-path`** (a cited line off the cause's call path or a heading line), AS 5 (mem 2, disk 1, delay 1, loss 1); and one AS socket answer at L0, whose dropped citation fails L1.

## Reading

On RE2-OB dev the log rule keeps at most one correct answer from L3 (DS 1/26, AS 0/20 on the counterfactual above). The larger effect is the latency rule's 0.5 s floor, calibrated on the lab's R-DOWN, which keeps 4 of DS's 5 correct delay answers at L2. The lab, where F3's `ConnectException` and part of F2 rest on log lines, has no admissible input: answering the question there needs a fresh lab dev decision replay (the plan's fallback, about $0.02 on the cloud route), which is the human's decision.
