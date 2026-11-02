# ADR-022: H1's baseline moves from Config 0 to Config 0R

## Status

Accepted by the human on 2026-11-02. Amends `docs/vision.md` (§1 H1, §5, §7 M3 and the cut order, §10), ADR-019's "D − 0 (H1) is unchanged" and ADR-016's "that is the point of H1 there". Config 0, Config 0R and their rules are unchanged.

## Date

2026-11-02

## Context

- **H1 compared D with rules blind to a section of its dossier.** Since `resource-facts`, the dossier holds a `## Resources` section (ADR-021). D reads it; Config 0 has no rule that does. The core rule says a rules-only configuration diagnoses from the same facts.
- **The gap is large on RCAEval dev.** On RE2-OB dev, Config 0 scores 4/30 and Config 0R 20/30 (`evals/results/2026-11-01-config0r-re2ob-dev/`). A win over Config 0 could come mostly from reading resources, which 0R shows needs no model.
- **What has been read.** No RCAEval holdout has been converted or opened. D and A have no result on RCAEval. Only 0, 0R, H and HR have been measured there, all on dev.

## Decision

- **H1's primary test is D − 0R** on the RCAEval holdouts, with the same CI, the same "rules are enough" bound of +5 points and the same Holm correction.
- **A − 0R is secondary.** D − 0 and A − 0 are also reported as secondary, outside the Holm family. They keep ADR-016's question: how far the lab's rules transfer, and what the LLM adds over them.
- **Config 0 stays.** It runs alongside every measurement, and its rules stay frozen (ADR-016).
- **This amendment precedes D's and A's first RCAEval run.** It is committed before their plan, so it cannot be a reaction to their numbers.

## Alternatives considered

- **Keep D − 0 as primary.** It leaves the pre-registered text untouched, and ADR-016 meant the rules' weakness on RCAEval as the point of H1. Rejected: since the dossier gained resources, the test would no longer compare the two over the same facts, and a win would overstate what the LLM adds.
- **Two primary tests, D − 0 and D − 0R.** Rejected: a second primary test costs Holm power, and either result could then be quoted alone.
- **Amend after M4, once D's holdout scores are known.** Rejected: that would pick the baseline by its result.

## Consequences

- H1 is harder for the LLM to win. The amendment moves the claim against the hypothesis it pre-registered, so it cannot inflate the LLM's result.
- **0R was motivated by RE2-OB dev.** R-RES exists because of Config 0's empty lists on that split, though its thresholds were fixed before any resource series was read (ADR-021). Holdout A is the same system, so 0R may be favoured there. Holdout B (RE2-SS), a system never seen, is the stronger test of the rule; M4 reports both.
- The freeze (vision §7) covers rules `v2` as well as `v1`. `v1` stays the default of `--rules`, so H1's runs must name `v2`.
- M3's RCAEval dev list gains 0R, and the cut order protects it as it protects Config 0.
- TT and RE3 still run on Config 0, D and BARO (vision §2). They are outside H1, and this ADR does not change them.
