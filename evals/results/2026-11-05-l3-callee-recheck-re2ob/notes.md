# Results: l3-callee-recheck (RE2-OB dev, and lab dev beside it)

Plan: [`evals/plans/2026-11-05-l3-callee-recheck.md`](../../plans/2026-11-05-l3-callee-recheck.md), committed as `ee00580` at 20:59:19Z, before every re-check (20:59:30Z to 20:59:56Z). Feature: `odd/tasks/l3-callee-check.md`, T4. Levels: ADR-028 Decision 5, amended 2026-11-05 (`no-changed-callee`).

Descriptive only. **Post hoc:** the check was chosen after seeing the wrong L3 answers on these same dev cases. No model, cloud or lab call was made, and no holdout was read. The lab dev result is in `../2026-11-05-l3-callee-recheck-lab-dev/` and is described here.

## Run

| Label | Input run | Re-check directory | Guard |
|---|---|---|---|
| DS | `replay-20261103T150905Z-cloud` | `recheck-20261105T205930Z-…` | PASS, 30 of 30 |
| AS | `replay-20261103T151645Z-local` | `recheck-20261105T205935Z-…` | PASS, 30 of 30 |
| HT | `replay-20261105T042537Z-local` | `recheck-20261105T205942Z-…` | PASS, 30 of 30 |
| HC | `replay-20261105T115842Z-local` | `recheck-20261105T205949Z-…` | PASS, 30 of 30 |
| DS (lab dev) | `replay-20261104T222411Z-cloud` | `recheck-20261105T205956Z-…` | PASS, 15 of 15 |

- Each input `summary.tsv` equals its committed copy in every column but `level`, with 0 mismatches.
- AC@1, coverage and Config 0 are unchanged.
  - Every `scores.tsv` row of 0, 0R, DS, AS, HT and HC equals its row in the committed result.
  - The lab dev result's `scores.tsv` and `paired.tsv` equal those of `2026-11-04-verification-levels-lab-dev` byte for byte.

## Levels

| Config | Verified-correct before | After | Wrong answers leaving L3 | Correct answers leaving L3 |
|---|---|---|---|---|
| DS | 25/30, 83% [66%, 93%] | 24/30, 80% [63%, 90%] | 1 | 1 |
| AS | 11/30, 37% [22%, 54%] | 10/30, 33% [19%, 51%] | 0 | 1 |
| HT | 21/27, 78% [59%, 89%] | 20/27, 74% [55%, 87%] | 1 | 1 |
| HC | 21/30, 70% [52%, 83%] | 20/30, 67% [49%, 81%] | 3 | 1 |
| DS (lab dev) | 14/14 | 14/14, 100% [78%, 100%] | 0 | 0 |

The L1 rates are unchanged. Across all inputs, the 5 wrong L3 answers leave L3 and 4 correct ones do, exactly as the plan's estimate said. Every change is L3 → L2.

- **Wrong answers that left L3.** Each names a direct caller of the truth:
  - DS `mem/77586d7c`: recommendationservice, truth productcatalogservice;
  - HT and HC `delay/1644ae3e`: checkoutservice, truth currencyservice;
  - HC `delay/880fd526`: frontend, truth productcatalogservice;
  - HC `loss/d4000574`: recommendationservice, truth productcatalogservice.
- **The correct answers that left L3** are one recording, `delay/0ed36a9e`, in DS, AS, HT and HC. Its truth, checkoutservice, calls paymentservice and emailservice, and both slowed about 10×. This is the check's stated cost (ADR-028).
- **No wrong answer is left at L3** in any input.

## Caveats

- Post hoc, on 30 dev cases already seen; a measurement on unseen cases is what tests the check.
- The one cost is a real pattern: a cause whose callees also slowed. A lab or holdout fault of that shape could not reach L3.
- Only direct callees count; a changed line two calls down does not fail the check.
