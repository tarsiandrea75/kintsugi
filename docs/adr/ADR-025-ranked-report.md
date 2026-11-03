# ADR-025: The ranked report keeps its first cause and adds up to four alternatives

## Status

Accepted

## Date

2026-11-03

## Context

Vision §7 M3 asks for "the ranked report (1–5 candidates)". Today the model's plain report names one cause, `rootCauseService` and `rootCauseCategory`, so D's and A's AC@3 and Avg@5 equal their AC@1 by construction (`odd/tasks/rcaeval-ranked-metrics.md`). Those metrics exist only to compare with the published baselines, which rank five services (vision §6).

Everything that scores a diagnosis reads that one cause: `reported`, the verdict, AC@1, H1 (service-level AC@1, D − 0R) and the Brier score of the top-1 confidence. A ranked report must not change what any of them reads.

Two constraints from the routes: the local report cap is 512 tokens, sized for one cause with two or three evidence items, and the cloud schema drops `maxItems` and `minItems` above 1 (`AnthropicSchema`), so only `ReportCheck` can enforce a count.

`odd/tasks/vision-revision.md` row 7 bundled two new categories, NETWORK and CODE_DEFECT, with this feature. They change the taxonomy, its enums, the goldens and the prompt, so a change in AC@1 could no longer be attributed to one cause. The human chose on 2026-11-03 to give them their own feature, after this one.

## Decision

1. **The first cause stays where it is.** `rootCauseService` and `rootCauseCategory` keep their meaning: the model's best answer. AC@1, the verdict, H1 and the Brier score read them as before.
2. **A new field, `alternatives`:** 0 to 4 items `{rootCauseService, rootCauseCategory}`, best first, after `rootCauseCategory` and before `confidence` in the field order, so the reasoning still comes before the causes.
3. **Rules `ReportCheck` enforces:** values from the same enums; no item `UNKNOWN`; no service twice, counting the first cause; at most 4 items; an empty list when the first cause is `UNKNOWN|UNKNOWN`. A breach is an invalid report, with the existing single retry.
4. **No per-item reasoning, no per-item confidence.** The summary and the shared evidence carry the reasoning; `confidence` stays the top-1's. This keeps the answer near today's length under the local 512-token cap.
5. **Where it applies:** the plain report, in every mode. The assessed and hybrid reports are unchanged: the hybrid picks one of the rules' candidates, and ADR-019's closed choice stays closed.
6. **Output:** the replay driver writes the model's list to a new last `summary.tsv` column, `ranked`, as `service|category` joined by `;`. The `candidates` column keeps the rules' list. The scorer's ranking (REQ-13) becomes `reported`, then `ranked`, then `candidates`, each service once, so ranked AC@1 still equals AC@1. REQ-7a's candidate recall is unchanged.

### Amendment, 2026-11-03: suspects with an abstention

**Status:** accepted, pending its measurement (`evals/plans/2026-11-03-abstain-suspects-re2ob-dev.md`). If the measurement fails, the amendment is reverted and Decision 3 stands as written.

**Context:** on the first ranked run, 7 of 30 reports needed the retry, and 4 of those because the model abstained while listing suspects (`odd/tasks/ranked-report.md`, T5). On the local route a retry is a second report of about 15 minutes.

**Decision:** Decision 3's last rule is dropped. When both causes are `UNKNOWN`, `alternatives` may list up to 4 suspects with the same rules: enums, no `UNKNOWN` item, no service twice. They are stored and written to `ranked`. The scorer still gives an abstaining row an empty ranking (REQ-13), so they change no score. The prompt says: "when both causes are UNKNOWN, the causes you suspect but cannot support, or an empty list".

**Risk:** with suspects allowed, abstaining may become the easier answer, and AC@1 fall. The plan's rule tests it.

## Alternatives considered

- **An array of candidates replacing the two cause fields.** Rejected: every consumer of `reported` would change, and AC@1 would move from a field the model fills to an index we choose.
- **A reason per alternative.** Rejected for now: about 40 tokens an item would push the local answer past 512 tokens, and the reason for an alternative is already in the summary.
- **Bundling NETWORK and CODE_DEFECT.** Rejected by the human for this feature (Context).

## Consequences

### Positive

- AC@3 and Avg@5 become readable for D and A, against BARO and chance.
- No score that exists today changes its input.

### Negative

- Every whole-prompt pin and the report schema pins change, and the lab goldens with them.
- Five ranked causes still give one confidence; a calibration per rank is not possible.
- The local answer grows by about 10 to 15 tokens an alternative, so A's cut rate must be watched on its next run.
