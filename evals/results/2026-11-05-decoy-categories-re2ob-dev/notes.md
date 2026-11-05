# Results: decoy-categories-dev

Plan: [`evals/plans/2026-11-05-decoy-categories-dev.md`](../../plans/2026-11-05-decoy-categories-dev.md), committed as `57340ed` at 21:18:23Z, before every run (21:19:30Z onwards). Taxonomy: ADR-029 (`aa25d35`). This file covers all three result directories:

- `2026-11-05-decoy-categories-lab-dev/`;
- this one, RE2-OB scored on the service;
- `2026-11-05-decoy-categories-re2ob-dev-category/`, RE2-OB scored on the full pair.

Descriptive, dev only, one run each.

## Runs

| Label | Set | Run | Rows | Status | Cost |
|---|---|---|---|---|---|
| 0 | lab dev | `replay-20261105T211930Z-rules` | 15 | equal to the committed 0, 0 mismatches | - |
| 0 | RE2-OB dev | `replay-20261105T211937Z-rules` | 30 | equal to the committed 0, 0 mismatches | - |
| 0R | RE2-OB dev | `replay-20261105T211943Z-rules` | 30 | equal to the committed 0R, 0 mismatches | - |
| DK | lab dev | `replay-20261105T211956Z-cloud` | 15 | 13 `COMPLETED`, 2 `INVALID_REPORT` | $0.0216 |
| DK | RE2-OB dev | `replay-20261105T212143Z-cloud` | 30 | 30 `COMPLETED` | $0.0388 |

- Both DK runs have the plan's configuration: `claude-haiku-5-5`, report `plain`, view `tables`, cap 768.
- The total cost was $0.060, within the human's approval and the $0.20 bound.
- Config 0's verdicts are unchanged, so AC3 holds.
- Since DS ran (2026-11-03T15:09Z, after `4d8ea5c`), the only change to the prompt, schemas or categories is `aa25d35` (from the `git log` of `Prompts.java`, `Schemas.java` and `Causes.java`). The re-checks of 2026-11-05 showed that DS's dossiers re-render byte for byte.

## Decoy answers

- **No answer names a decoy as its cause**, on either set.
- **Decoys among the alternatives:** one, on the lab. An F2 report lists `order-service|CODE_DEFECT` third, behind the right cause.

## Numbers (Wilson 95%)

| Set | DS (before) | DK (decoys) | DK − DS |
|---|---|---|---|
| Lab dev, full pair | 14/15, 93% [70%, 99%] | 13/15, 87% [62%, 96%] | DK only 1, DS only 2, p = 1 |
| RE2-OB dev, service | 26/30, 87% [70%, 95%] | 21/30, 70% [52%, 83%] | DK only 0, DS only 5, p = 0.062 (Holm 0.19) |
| RE2-OB dev, full pair | 20/30, 67% [49%, 81%] | 15/30, 50% [33%, 67%] | DK only 1, DS only 6, p = 0.12 |

- **Coverage on RE2-OB** falls from 90% to 80%, and selective accuracy from 96% to 88%.
- **Verified-correct:**
  - lab: DK 13/13 against DS's 14/14;
  - RE2-OB: DK 21/30, 70% [52%, 83%], against DS's 25/30. DS's figure is from its committed level, before `no-changed-callee`; under that check DS is 24/30.
- **L1 rate:** 100% on both sets, for both configs.

## What changed

**RE2-OB.** 5 cases are right for DS and wrong for DK:

| Case | DK's answer | What happened |
|---|---|---|
| `cpu/1bc72e38` | `UNKNOWN|UNKNOWN` | abstained; DS named the cause |
| `cpu/d90da82b` | `UNKNOWN|UNKNOWN` | abstained; DS named the cause |
| `delay/880fd526` | `UNKNOWN|UNKNOWN` | abstained; DS named the cause |
| `socket/6cf8e040` | `UNKNOWN|UNKNOWN` | abstained; DS named the cause |
| `delay/0ed36a9e` | `paymentservice` | named a callee of the truth (`checkoutservice`) |

- DK abstains on 6 cases against DS's 3.
- None of the 5 losses names a decoy.
- The other category changes are:
  - `delay/1644ae3e`: RESOURCE_EXHAUSTION instead of DEPENDENCY_LATENCY;
  - `loss/23063f7a`: `checkoutservice|UNKNOWN`;
  - `delay/cca1ac01`: DEPENDENCY_LATENCY instead of UNKNOWN, a gain.

**The lab.**

- The 2 `INVALID_REPORT` are F2 runs. Both attempts of each were rejected for naming a service twice across the cause and the alternatives. That is the ranked report's rule, and it has nothing to do with the decoys.
- DS had one such F2 failure; DK completes that one.

## Reading, and its limits

- **No decoy answers.** The decoys cost nothing directly: the model never chose one.
- **The RE2-OB drop is mostly abstention, and it is not significant** (p = 0.062, Holm 0.19).
- **The cause is unknown.** One cloud run per configuration cannot separate two explanations:
  - a wider taxonomy that makes the model less willing to commit;
  - run-to-run sampling. The cloud route sends no temperature, so its answers are sampled.

  No repeated DS run on RE2-OB dev exists to size that noise.
- Dev cases have been seen before.
