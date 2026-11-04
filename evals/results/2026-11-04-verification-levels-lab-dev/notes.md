# Results: verification-levels-lab-dev

Plan: `evals/plans/2026-11-04-verification-levels-lab-dev.md` (committed in `857b827`, before either run). Feature: `odd/tasks/verification-levels.md`, T8. Levels: ADR-028, Decision 5 as amended on 2026-11-04.

Descriptive only: no hypothesis, no decision rule, no gain claimed. No holdout was read.

## Run

- Config 0: `infra/scripts/replay-agent.sh --route rules --mode decision --split dev`, run `replay-20261104T222358Z-rules`, exit 0, 15 rows, 15 `COMPLETED`, rules v1, no cost. Its verdicts equal `evals/results/2026-10-30-config0-dev/inputs/0-summary.tsv` in every compared column.
- DS: `infra/scripts/replay-agent.sh --route cloud --mode decision --split dev --view tables --report plain`, run `replay-20261104T222411Z-cloud`, exit 0, 15 rows, 14 `COMPLETED` and 1 `INVALID_REPORT`; model `claude-haiku-5-5`, think `off`, report cap 768, deadline 90 s. The one cloud run of the plan; no retry.
- Tokens: 110,874 prompt and 10,844 completion (F1 43,369 / 4,373; F2 37,058 / 3,459; F3 30,447 / 3,012). Estimated cost $0.0165 (`aggregate.tsv`, at the script's $0.10 and $0.50 per million prompt and completion tokens), under the plan's $0.10 bound.
- Re-check: `infra/scripts/recheck-levels.sh infra/scripts/out/replay-20261104T222411Z-cloud`, out `recheck-20261104T222535Z-replay-20261104T222411Z-cloud`, exit 0. Guard 15 of 15: each re-rendered dossier equals the stored one byte for byte and the re-check verifies exactly the stored evidence. Its `summary.tsv` equals DS's in every column, `level` included. Its `levels-detail.tsv` and `recheck.txt` are here as `DS-levels-detail.tsv` and `DS-recheck.txt`.
- Scored with the plan's command (the default lab scoring, full truth).

## Accuracy

| Config | F1 | F2 | F3 | Pooled AC@1 |
|---|---|---|---|---|
| 0 | 5/5 | 0/5 | 5/5 | 10/15, 67% [42%, 85%] |
| DS | 5/5 | 4/5 | 5/5 | 14/15, 93% [70%, 99%] |

DS - 0: DS only 4, 0 only 0, exact McNemar p = 0.12. DS's one miss is the `INVALID_REPORT` (F2, `r7`): both attempts named a service twice, once in `alternatives` and once as the cause or another alternative ("name each service once"), so the report was invalid twice and no report was stored.

## Levels and rates

| Config | L0 | L1 | L2 | L3 | No level | Verified-correct | L1 rate |
|---|---|---|---|---|---|---|---|
| DS | 0 | 0 | 0 | 14 | 1 (`INVALID_REPORT`) | 14/14, 100% [78%, 100%] | 14/14, 100% [78%, 100%] |
| 0 | - | - | - | - | 15 | n/a | n/a |

The scorer leaves the `-` row out of both denominators (evals spec REQ-16); over all 15 fault rows, 14 are verified-correct, 93% [70%, 99%].

Per fault, DS (L0/L1/L2/L3, no level): F1 0/0/0/5, 0; F2 0/0/0/4, 1; F3 0/0/0/5, 0. Every stored answer is correct and at L3; no incorrect answer reaches L3 (there is none).

## Correct answers below L3, and the log-only count

| Fault | Correct | Below L3 | Failed check | Log-only |
|---|---|---|---|---|
| F1 | 5 | 0 | - | 0 |
| F2 | 4 | 0 | - | 0 |
| F3 | 5 | 0 | - | 0 |
| All | 14 | 0, 0% [0%, 22%] | - | 0/14, 0% [0%, 22%] |

No correct answer is below L3, so none rests only on `NOT_COMPARED` log lines and none fails `cause-fact-changed` with a cause-owned log line.

What carried L3, per fault (the cause-owned cited lines with `changed` = `YES`):

- F1 (gateway-mock latency): gateway-mock's latency row in 5 of 5.
- F2 (gateway-mock rejecting): gateway-mock's traffic row in 4 of 4.
- F3 (order-service configuration): order-service's status line (a restart in the incident window) in 5 of 5, and its traffic row in 5 of 5.

The model also cited cause-owned log lines (`NOT_COMPARED`) beside those facts: F1 in 3 of 5 answers, F2 in 2 of 4, F3 in 4 of 5. On the lab's dev cases, then, the log lines were never the only cause-owned evidence: F3's restart and traffic, and F2's traffic, were always cited too. Every cited line also passed `owners-on-path` (no answer cited a line off the cause's call path).

## Reading

On lab dev, the log rule costs nothing: 0 of 14 correct answers below L3, 0 log-only. With RE2-OB dev (T7: DS 1 of 26 log-only, `loss`, which also failed `owners-on-path`), the two dev sets give no case where a previous log window would lift a correct answer to L3. This answers the human's question for these cases only; it does not show that logs are never the only evidence.

## Caveats

- One run of D on 15 cases; the largest net difference between identical runs of D seen earlier (on RE2-OB dev) is 2 cases.
- Lab dev has been read many times before; this describes today's agent on seen cases.
- The latency rule (ratio only, T7) was changed after seeing RE2-OB dev; whether F1's latency rows would also pass the former 0.5 s floor was not checked.
- The cost is the script's estimate at its configured prices, not a billed amount.
- `policy.tsv` is the scorer's output for inputs that record the policy columns; this is the first committed result whose inputs do.
