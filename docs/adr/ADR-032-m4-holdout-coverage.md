# ADR-032: M4 holdout coverage: RE2-TT for D, holdout-2's configs, and what is never cut

## Status

Accepted (2026-11-06). The human made the three decisions below before any holdout was read. Feature: [`odd/tasks/m4-plans.md`](../../odd/tasks/m4-plans.md).

## Date

2026-11-06

## Context

The check for M4 (vision §8) requires that A covers at least 200 holdout cases and D at least 300. The planned holdouts are lab holdout-2 (75 cases, ADR-026), RCAEval holdout A (RE2-OB repetitions 2–3, 60) and holdout B (RE2-SS, 90), 225 in all.

- **A and holdout B.** A reaches 200 only with holdout B. Vision §8 lists holdout B as the last item of the cut order.
- **D's case count.** D stops at 225 with no further holdout. Vision §2 runs TT and RE3 on Config 0, D and BARO only, and the cut order lists "TT and RE3" fourth.
  - RE3 is not downloaded and has no converter support.
  - RE2-TT is downloaded (ADR-020) and was used only for BARO's reproduction (ADR-024); the converter refuses it.
  - No agent configuration and no rule of Config 0 or 0R has run on or been tuned on TT.
- **D's cost** on RE2-OB dev is about $0.0013 a case: 9850 prompt and 680 completion tokens at ADR-012's prices. 315 cases × 3 repeats cost about $1.2, inside the ~$3.6 left.
- **Holdout-2's configs.** No document states which configs run on holdout-2.

## Decision

1. **RE2-TT is the RCAEval holdout TT**, 90 cases, for Config 0, Config 0R and D only.
   - It is read once, under a committed plan, from 2026-12-07.
   - The converter is enabled for it behind `--allow-holdout TT`, as for A and B. That is `evals/` code, not frozen.
   - TT enters H1 (D − 0R, RCAEval holdouts) with holdouts A and B. It enters neither H2 nor H3, because A does not run on it (68 services, beyond the local context).
   - D then covers 75 + 60 + 90 + 90 = 315 cases.
2. **Holdout-2 runs Config 0, Config 0R, A and D.** C is computed from A and D under ADR-030's fixed rule.
   - A's holdout-2 run is the first levelled lab A run, so C exists on the lab from it.
   - 0R enters no hypothesis on the lab, but it costs nothing and keeps the table comparable with RCAEval.
   - H and HR stay out: they enter none of H1–H3, and HR takes about 16 minutes a case.
3. **Never cut, added to vision §8's list:** holdout B, and TT on Config 0, 0R and D.
   - The cut order keeps "RE3" at item 4. "Holdout B" leaves item 7, and item 7 is removed.
   - The M4 check therefore cannot fail through a cut. A's ~40 CPU hours become a fixed commitment between 2026-12-07 and 2026-12-27: 150 RCAEval cases at about 11 minutes each, plus 75 lab cases.

## Alternatives considered

- **Keep TT and holdout B cuttable, and relax the M4 check if they are cut.** It moves the pre-registered bar after the plan, which is exactly what pre-registration forbids.
- **Count D's cloud repeats as cases.** Three repeats of 225 cases make 675 runs, but the case is the unit (ADR-015). Repeats narrow nothing about new systems.
- **Download RE3 instead of using TT.** It needs a download, a new converter and new fault classes before 2026-12-07, for the same purpose.

## Consequences

### Positive

- Both M4 case counts are reachable without any new local run beyond those already planned.
- C exists on the lab for H3, at no extra cost.
- H1 is read on three RCAEval systems' worth of runs (OB repeats, SS, TT), not two.

### Negative

- **TT has been seen.** BARO's reproduction read TT's truth labels and BARO's ranks on it (ADR-024). Neither the agent nor the rules were tuned on it, but TT is not as unseen as holdout B. `docs/benchmark-limits.md` states this.
- **Converter risk.** The converter's holdout and RE2-SS paths are untested (STATE follow-up), and TT's 68 services are new to it. A dry check of headers and counts, with no scoring, runs before 2026-12-07 (`odd/tasks/m4-plans.md` T4).
- **CPU time.** About 40 CPU hours of A become fixed in M4's three weeks.

## Amendment (2026-11-07)

A's commitment rises to ≈ 67 CPU hours. [ADR-034](ADR-034-rcaeval-healthy-pseudo-cases.md) adds the 150 healthy pseudo-cases of holdouts A and B to H2b, and A runs on all of them: about 27 more hours at ~11 minutes a case. The case counts of Decision 1 and the M4 check do not count the pseudo-cases. Decided before any holdout was read.
