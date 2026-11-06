# Notes: config-c-dev

Plan: [`evals/plans/2026-11-06-config-c-dev.md`](../../plans/2026-11-06-config-c-dev.md). Rule: [ADR-030](../../../docs/adr/ADR-030-config-c-escalation-rule.md). Run on commit `b56a7f5`, 2026-11-06.

## Result

- Selected rule: `k = L2, t = off`, the only grid rule escalating at most 50% of the 30 paired cases.
  - It escalates 11/30 (37%, [22%, 54%]), all on the `level` trigger.
  - C AC@1 is 22/30 (73%, [56%, 86%]), against D 26/30 (87%) and A 20/30 (67%).
  - C − D is −13 points (C only 2, D only 6, exact McNemar p = 0.29).
- Config 0 alongside: 4/30 (13%). Config 0R: 20/30 (67%).
- This is a calibration, not a test of H3. C's dev AC@1 is optimistic, because the rule was chosen on these cases.

## H3 is already in tension on dev

No grid rule meets both of H3's conditions on dev:

| Rule | Escalations | C − D |
|---|---|---|
| `k = L2, t = off` | 11/30 (37%) | −13 points |
| `k = L3, t = off` | 20/30 (67%) | +7 points |
| `k = L2, t = 0.935` | 27/30 (90%) | −3 points |

- Rules within 5 points of D escalate at least 67% of cases.
- The only rule under the 50% cap trails D by 13 points.

The procedure was kept as pre-registered (human decision, 2026-11-06). Changing it would only choose which of H3's conditions fails. The 30-case intervals are wide, so H3 is decided on the M4 holdouts.

## Deviations from the plan's command

- `score --out` copies every input to `<out>/inputs/<label>-summary.tsv`, so the backfilled AS and DS summaries were written to `backfilled/` instead of `inputs/`. Writing them to `inputs/` would raise `SameFileError`.
- The scratch smoke run of T4 showed this grid before this run. It ran on the same inputs and code, and the procedure was fixed in ADR-030 before both.

## Files

- `grid.tsv`, `escalation.tsv`, `C-summary.tsv`, `escalate.txt`: written by `escalate`.
- `report.txt`, `scores.tsv`, `paired.tsv`, `ranked.tsv`, `inputs/`, `inputs.tsv`: written by `score` over 0, 0R, AS, DS and C.
- `backfilled/`: AS and DS with `confidence`, from `infra/scripts/out/replay-20261103T151645Z-local` and `replay-20261103T150905Z-cloud`.
