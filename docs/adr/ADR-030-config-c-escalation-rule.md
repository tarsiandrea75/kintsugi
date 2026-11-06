# ADR-030: Config C's escalation threshold is a verification level set on dev

## Status

Accepted (2026-11-06). The human chose the level threshold set on dev over the literal "L2/L3 failure" reading. The chosen `(k, t)` is recorded in the amendment below after the dev run (T5). Feature: [`odd/tasks/config-c.md`](../../odd/tasks/config-c.md).

## Date

2026-11-06

## Context

Vision §5 defines Config C as "local first, escalating to D on abstention, an L2/L3 failure or low confidence (threshold set on dev)", computed from the paired A and D outputs. H3 (vision §1) reads: "C stays within 5 points of D while escalating at most 50% of cases." Nothing computes C yet.

Two facts from RE2-OB dev (`evals/results/2026-11-05-l3-callee-recheck-re2ob/inputs/`) make the literal wording untestable:

- **Too many cases fail L2/L3.** A (AS) reaches L0 on 1 case, L1 on 10, L2 on 9 and L3 on 10 (ADR-028 levels). "An L2/L3 failure" read as "level below L3" escalates 20/30 cases (67%). H3's 50% limit would be lost by construction, before any holdout is read.
- **The confidence signal is weak.** A's top-1 `report.confidence` takes only 0.85, 0.90, 0.92 and 0.95, and 0.95 also appears on wrong answers. It is stored only in the gitignored replay JSON, not in `summary.tsv`.

The vision already says that a threshold is "set on dev". The open question is which threshold, and by what procedure, so that the choice is pre-registered before a holdout is read.

## Decision

1. **Triggers.** C escalates a case to D when any of these holds for A's row:
   - A did not complete, i.e. any status other than `COMPLETED` (no answer, treated like an abstention);
   - A abstained (`UNKNOWN|UNKNOWN`);
   - A's verification level is below `k`, or A's row has no level;
   - A's top-1 confidence is below `t`.

   Otherwise C keeps A's answer. A case with no A row or no D row is not part of C.
2. **The dev-set threshold is `(k, t)`.**
   - `k ∈ {L2, L3}`.
   - `t ∈ {off}`, plus the midpoints between consecutive distinct A confidences seen on dev. `off` means no confidence trigger.
   - A row without a confidence never triggers on `t`.
3. **Selection rule, on RE2-OB dev only.**
   - Among the rules that escalate at most 50% of the paired dev cases, pick the one with the highest C AC@1 (service level, as H1).
   - Break ties by the fewer escalations, then the higher `k`, then `t = off`.
   - If no rule escalates at most 50%, fix `k = L3, t = off`, the literal vision rule, and report that H3's cost limit already fails on dev.
4. **Pre-registration.** The plan `evals/plans/2026-11-06-config-c-dev.md` and this ADR are committed before the dev run. The chosen `(k, t)` is recorded here before any M4 holdout is read, and is not changed afterwards.
5. **Vision §5 wording.** C's row now reads "escalating to D on no answer, abstention, a verification level below `k`, or a confidence below `t` (`(k, t)` set on dev, ADR-030)". H3 is unchanged.
6. **Data.** `ReplayDriver` writes a `confidence` column in `summary.tsv`. The RE2-OB dev AS and DS inputs are backfilled from the local replay JSON, and the backfill script is committed. The scorer treats the column as optional.

## Alternatives considered

- **Literal rule (level below L3), with no tuning.** It is faithful to the wording, but it escalates 67% on dev, so H3 would be decided by the rule's wording and not by the data. Rejected by the human.
- **Escalate only on L2 failure (level below L2), fixed.** It escalates 11/30 on dev, but it picks one point by hand instead of applying the "threshold set on dev" the vision already names. The grid includes it as `k = L2, t = off`.
- **A confidence threshold alone.** A's confidence barely discriminates between right and wrong answers, so it is kept only as an optional grid axis.
- **A learned escalation model** (e.g. logistic regression over level, confidence and the ranked length). With 30 dev cases it would overfit, and it would not be explainable as a deterministic rule.

## Consequences

### Positive

- H3 stays testable and pre-registered: the rule is fixed by a stated procedure on dev and committed before any holdout.
- C is deterministic and explainable. Every escalation names its trigger.
- The grid is small (2 × a few), so the selection rule leaves little room for fitting noise.

### Negative

- C's dev AC@1 is optimistic, because the rule was chosen on the same 30 cases. Only the holdout read is an honest estimate.
- The 50% cap is applied on dev, so the holdout escalation rate can still exceed it. H3 then fails on cost, and that result is reported.
- C exists only on RE2-OB until a lab A run with levels exists (lab C is out of scope here).
- The vision text changes after its pre-registration commit. This is the same pattern as ADR-022: the change lands before any affected holdout is read.

## Amendment (2026-11-06): the chosen rule

The dev run (`evals/results/2026-11-06-config-c-dev/`, plan `evals/plans/2026-11-06-config-c-dev.md`) selected **`k = L2, t = off`**, which is fixed for every holdout read.

- It escalates 11/30 (37%), and C AC@1 is 22/30 against D's 26/30 (C − D −13 points, McNemar p = 0.29).
- It was the only grid rule under the 50% cap.
- No grid rule met both of H3's conditions on dev. The human kept the procedure as pre-registered.

