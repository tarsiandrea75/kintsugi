# ADR-031: The H1–H3 verdict procedure

## Status

Accepted (2026-11-06). The human chose this procedure before any holdout was read. Feature: [`odd/tasks/m4-plans.md`](../../odd/tasks/m4-plans.md).

## Date

2026-11-06

## Context

Vision §1 pre-registers three hypotheses as bounds on paired 95% CIs.

- **H1:** D − 0R service-level AC@1 on the RCAEval holdouts lies above 0.
- **H2:** three conditions:
  - the pooled A − D lower bound is above −10 points;
  - A's false-alarm rate is at most D's plus 10 points;
  - A's median time to report is at most 15 minutes.
- **H3:** C stays within 5 points of D while escalating at most 50% of cases.

It then says that "H1–H3 get a Holm correction". Holm adjusts p-values, not interval bounds.

Several points are not written down:

- **The test itself:** the alpha level, the sidedness and the CI method.
- **Multiple conditions:** how H2's three conditions and H3's two make one decision.
- **The adjustment:** how Holm changes "lies above".

The scorer currently applies Holm to two-sided McNemar p-values across every pair in one `score` run (`evals/kintsugi_evals/cli.py`:317-321), which is not the H1–H3 family. ADR-015 already fixes the case as the unit, and the cluster bootstrap when cases repeat.

## Decision

1. **One one-sided test per condition, against its stated margin.** Each condition is a null hypothesis that the margin is not met:

   | Condition | Statistic | Null hypothesis | Unit |
   |---|---|---|---|
   | H1 | D − 0R, service AC@1, RCAEval holdouts A and B | ≤ 0 | fault case |
   | H2a | A − D, AC@1, pooled over holdout-2, holdout A and holdout B | ≤ −10 points | fault case |
   | H2b | false-alarm rate A − D, healthy cases | ≥ +10 points | healthy case |
   | H2c | median of A's time to report, all A cases | ≥ 15 minutes | case |
   | H3a | C − D, AC@1, the A∩D holdout cases | ≤ −5 points | fault case |
   | H3b | C's escalation rate, the same cases | ≥ 50% | case |

2. **The p-value.** It is the share of case-level bootstrap resamples whose statistic falls on the null side of its margin. Ties count on the null side.
   - There are 10000 resamples.
   - The seed is recorded in the plan.
   - The resampling unit is the case, and repeats of a case move together (ADR-015).
   - Pooling over tracks resamples within each track and keeps the track sizes.
   - C's rule is ADR-030's fixed `k = L2, t = off`.
3. **Alpha is 0.025, one-sided.** It is the one-sided reading of the vision's two-sided 95% CI, so an unadjusted condition passes exactly when its 95% CI clears the margin.
4. **Intersection-union.** A hypothesis with several conditions holds only if every condition holds. Its p-value is the maximum of its conditions' p-values.
5. **Holm over {H1, H2, H3}.** Sort the three p-values in ascending order and compare them with 0.025/3, 0.025/2 and 0.025 in turn. Stop at the first p-value that fails: it and every larger one fail. The test is step-down, at familywise alpha 0.025 one-sided.
6. **Reading.**
   - A hypothesis "holds" when Holm rejects its null. Otherwise it "is not supported", and that result is reported.
   - Every condition also reports its point estimate, its two-sided 95% bootstrap percentile CI, and its unadjusted and Holm-adjusted p-values.
   - H1's "rules are enough" reading applies when the 95% CI's upper bound is below +5 points, as the vision states.
7. **Secondary results stay outside the family.** These are A − 0R, D − 0, A − 0, per-track splits and the exact McNemar tests. They are reported without Holm and labelled secondary or exploratory (ADR-022).

## Alternatives considered

- **Bonferroni-widened CIs at 1 − 0.05/3.** Simpler, but it is more conservative than Holm and departs from the vision's "95% CI".
- **Two-sided tests.** Each hypothesis states a direction and a margin, so a two-sided test would ask a question that was never posed.
- **McNemar for H1 and H2a.** It tests "no difference", not a margin such as −10 points, and it cannot pool across tracks or carry repeats.
- **Combining the conditions with a weighted score.** It would change what the vision pre-registered.

## Consequences

### Positive

- Every verdict is a mechanical consequence of committed results and this ADR.
- The rules keep the vision's wording: an unadjusted 95% CI that clears its margin is exactly a pass at 0.025 one-sided.

### Negative

- Holm and intersection-union both make the test stricter. A condition whose CI barely clears its margin can still be "not supported" after Holm.
- The p-values of the bootstrap percentile method are approximate for small tracks.
- H2c's median-time test is not a paired comparison: it bootstraps A's own times.
- The code needs new verdict logic in the scorer (`odd/tasks/m4-plans.md` T3).
