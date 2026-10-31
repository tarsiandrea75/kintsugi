# ADR-019: Config H, the hybrid (rules propose, the LLM picks and explains)

## Status

Accepted. Amends `docs/vision.md` (§ on Config 0 and the configs table, M3) and ADR-016's "never the agent's answer" for this one config. Config 0 itself is unchanged.

## Date

2026-10-31

## Context

Two results point the same way:

- On the lab holdout-1, the rules (Config 0) scored 10/15 and Qon (ADR-018) 12/15, with different misses. Rules miss F2: no rule fires, so they abstain. Qon misses F1: it blames `payment-service`, the caller. An oracle of the two reaches 13/15 (`evals/results/2026-10-31-qwen-holdout1/`).
- On dev, the rules and the cloud solve the same dossiers that the local model fails. The local model's weak point is choosing among abnormal services, not reading the facts.

So a config where the rules narrow the choice and the model picks among the candidates targets exactly where each one fails.

The vision forbids this as it stands. It says Config 0 "stays a baseline that measures exactly that redundancy, never the agent's answer" (ADR-011 rejected rules inside the agent because "the LLM would become redundant"). The stop rule (vision §7) also applies: after service-assessment, a change must be track-agnostic and win a paired comparison on RCAEval dev.

The human approved this amendment on 2026-10-31.

## Decision

Add a Config **H**, the hybrid:

1. **Proposals.** The rules return up to 5 ranked candidates, each a `service|category` with the facts that support it. They come from a new pure function `RulesDiagnosis.candidates(Dossier)`. The first candidate is `diagnose()`'s verdict whenever that verdict is not UNKNOWN. The function can also return abnormal services that no rule chose, so the list may be non-empty when Config 0 abstains. `diagnose()`, its thresholds and Config 0's verdicts stay frozen at `5797d16` (ADR-016); the golden tests prove it.
2. **The choice.** The local model, Qon (ADR-018), sees the same dossier and the candidate list, picks exactly one candidate or UNKNOWN, and explains the choice with cited evidence under the existing evidence check. A choice outside the list makes the report invalid, and then the existing retry rule applies.
3. **No fallback.** When the model times out or its report is invalid, H has no answer; it does not fall back to the rules' first candidate. An empty candidate list means UNKNOWN, with no model call. The model's contribution is then the clean number H − 0.
4. **Measurement.**
   - The stop-rule comparison is H − 0, paired, on RCAEval dev (service-level AC@1), once the converter and topology-as-data exist.
   - The lab dev run is a mechanics check and makes no claim.
   - Every H result also reports the candidates' recall (is the truth in the list at all), because that is H's ceiling.
5. **Scope in the agent.** The rules enter the agent only as H's proposer, as a decision-mode report variant. Production stays closed to `route=rules` (the agent spec's Config 0 requirement).
6. **Config 0 stays the baseline.** H − 0 now measures whether the LLM adds anything over the rules once it sees them. D − 0 (H1) is unchanged.

## Alternatives considered

- **Keep the vision as it is (no hybrid)**: the local route then has only Qon alone, which is not significantly above the rules (p = 0.62) and has a different failure class. The complementarity seen on two splits would stay unused.
- **Fall back to the rules' first candidate on a model failure**: a better product, but H − 0 would then mix the model's choices with the rules' answers. The fallback can be computed afterwards from the paired H and 0 rows, as Config C is computed from A and D, without a run.
- **Show the rules' verdict as a hint and let the model answer freely**: the model could leave the list. That is unchecked, and it is Qon with a hint rather than a hybrid. The closed choice is what the code can verify.
- **The LLM proposes, the rules verify**: this is today's agent plus `ReportCheck`, and it already failed F1 locally.

## Consequences

### Positive

- The local route gets a config built on the measured complementarity of rules and Qon.
- The leakage principle holds: the rules H uses are exactly those Config 0 runs, and RCAEval tests their transfer.

### Negative

- H inherits the rules' lab knowledge (the call graph, thresholds). On RCAEval it needs topology as data before it can propose anything, so its stop-rule measurement waits for that work and the converter.
- With the rules inside one config, "are the rules enough?" is now answered by H − 0 and D − 0, no longer by the agent alone.
- A Qon sample takes 13 to 20 minutes on this CPU; H runs compete for the night budget (vision §7).
