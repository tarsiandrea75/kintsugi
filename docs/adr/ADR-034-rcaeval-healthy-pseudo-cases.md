# ADR-034: RCAEval healthy pseudo-cases enter H2b

## Status

Accepted (2026-11-07). The human made the decisions below on 2026-11-06 and 2026-11-07, before any holdout was read. The dev gate they depend on was pre-registered and run on RE2-OB dev only. Feature: [`odd/tasks/rcaeval-healthy.md`](../../odd/tasks/rcaeval-healthy.md).

## Date

2026-11-07

## Context

- **H2b rests on 15 cases.** H2b tests A's false-alarm rate against D's plus 10 points ([ADR-031](ADR-031-h1-h3-verdict-procedure.md)). Its only healthy cases were lab holdout-2's 15 healthy controls (`evals/plans/2026-11-06-h1-h3-verdicts.md`, before this ADR).
  - With 15 cases, one extra false alarm by A gives p ≈ 0.26, so H2b fails.
  - H2 is an intersection-union of H2a, H2b and H2c, so H2b alone would then sink H2.
- **Vision §2 promises healthy pseudo-cases** for RCAEval, from windows before the injection. The converter wrote none until this feature.
- **Every RCAEval case records exactly 12 minutes before `inject_time`.** This holds for RE2-OB, RE2-SS and RE2-TT, all repetitions, by an aggregate timestamp check of 2026-11-06. Fault cases use 10 + 10 minutes, so a pseudo-case with a previous and an incident window must use 6 + 6 minutes.
- **A's CPU is the constraint, not money.** D costs about $0.035 per 30-case repeat. A takes about 11 minutes a case on RE2-OB dev.
- **A dev gate decided whether the pseudo-cases are usable.** The plan [`evals/plans/2026-11-07-rcaeval-healthy-dev.md`](../../evals/plans/2026-11-07-rcaeval-healthy-dev.md) was committed before any run. It keeps the pseudo-cases if D's pooled false-alarm rate on the 30 RE2-OB dev pseudo-cases is at most 30/90.

## Decision

1. **Pseudo-cases are built from the 12 pre-injection minutes.**
   - `convert-rcaeval --healthy` writes one pseudo-case per case.
   - The previous window is `[inject − 12 m, inject − 6 m)` and the incident window `[inject − 6 m, inject)`, with `sinceMinutes` 6.
   - The truth is `NONE|NONE` and the fault `healthy`. No row at or after `inject_time` enters a pseudo-case.
   - The blinded name is salted apart from the fault case's, and the output goes to its own directory.
2. **The dev gate keeps them.** D's pooled false-alarm rate was 9/90 (10%, Wilson 95% [5.4%, 17.9%]), within the plan's 30/90.
   - Per repeat it was 0, 3 and 6 of 30.
   - Config 0 and Config 0R gave 0/30 each.
   - Results: [`evals/results/2026-11-07-rcaeval-healthy-dev/`](../../evals/results/2026-11-07-rcaeval-healthy-dev/notes.md).
3. **The sample is every pseudo-case of holdouts A and B.**
   - Holdout A (RE2-OB repetitions 2–3) gives 60 pseudo-cases, and holdout B (RE2-SS) 90.
   - With lab holdout-2's 15 healthy controls, H2b reads 165 healthy cases.
   - TT gets none, because A does not run on TT ([ADR-032](ADR-032-m4-holdout-coverage.md)) and H2b needs A and D.
4. **Configs 0, 0R, A and D run on the pseudo-cases.**
   - D runs 3 repeats, each a separate `--repeats 1` run of one repetition's 30 pseudo-cases, because a cloud run takes at most 60 samples (`MAX_CLOUD_SAMPLES`).
   - Each config's pseudo-case rows are merged into separate per-config healthy summaries, never into the fault-case summaries.
   - `verdict` reads them as their own tracks, `A-healthy` and `B-healthy`, each named by the new repeatable flag `--h2b-only` (verdict spec REQ-9).
   - H2b's margin (+10 points), seed (20261106) and procedure (ADR-031) do not change.
5. **The pseudo-cases enter H2b only (human decision of 2026-11-07).**
   - An `--h2b-only` track enters H2b and no other condition: not H1, H2a, H2c, H3a or H3b. H2b pools it with the other tracks as ADR-031 pools tracks, resampling within it and keeping its size.
   - `verdict` refuses an `--h2b-only` track that holds a fault row, lacks A or D, or is also an `--h1-track`, and `verdict.txt` names the H2b-only tracks.
   - H2c reads A's fault cases on every track plus lab holdout-2's 15 real healthy controls, 225 cases. Why: a pseudo-case's 6-minute dossier is lighter than a fault case's, so A's times on it would pull H2c's median down in A's favour.
   - Why a flag and separate tracks: it follows `--h1-track`'s style, keeps the inclusion explicit in the command and in `verdict.txt`, and leaves the fault-case summaries exactly as before.
6. **The cost is accepted.**
   - A takes about 27 more CPU hours (150 cases × ~11 minutes), so A's M4 commitment rises from ~40 to ≈ 67 CPU hours. At the 2100 s deadline the 150 pseudo-cases would take at most ~88 hours.
   - D costs about $0.035 per 30-case repeat, about $0.53 for the 15 runs.

The holdout plans are amended in the same change: `evals/plans/2026-11-06-h1-h3-verdicts.md`, `2026-11-06-holdout-a-re2ob.md` and `2026-11-06-holdout-b-re2ss.md`.

## Alternatives considered

- **Keep H2b on the 15 lab cases.** It costs nothing, but one extra false alarm by A fails H2b and so H2. The test would mostly measure the sample's size.
- **A fixed sample of 60 pseudo-cases.** It halves A's extra CPU time, but needs a sampling rule over the holdouts, and a smaller H2b. Every pseudo-case already exists once the holdout is converted, so taking all of them leaves no choice to defend.
- **Merge the pseudo-case rows into each track's fault-case summaries.** It needs no new flag, since `verdict` finds healthy rows by their truth, but H2c then reads A's time on every A row: its median would cover 375 cases, 150 of them on lighter 6-minute dossiers, biased in A's favour.
- **Change H2b's margin or its test.** Rejected as post hoc: the margin is pre-registered in vision §1 and ADR-031, and changing it after seeing how small the sample is would move the bar instead of measuring against it.

## Consequences

### Positive

- H2c is unchanged by the pseudo-cases: it keeps its 225 cases, so A's lighter pseudo-case dossiers cannot speed up its median.
- H2b reads 165 healthy cases instead of 15, so its CI narrows and one false alarm no longer decides H2.
- Vision §2's promise of RCAEval pseudo-cases is kept.
- A − D stays a paired comparison: both configs read identical windows.
- No agent code changes: the gatherer takes every window from the incident's `sinceMinutes`.

### Negative

- **The windows are 6 minutes, not 10.** A 6-minute window holds about 0.6× the events, so count floors such as `SLOW_TIMEOUTS`, `REFUSING_ANSWERS` and `COUNT_MIN` fire less often. That leans toward fewer false alarms.
- **The pre-injection logs are silent.** None of the 30 dev pseudo-cases holds a WARN or ERROR log line, so the log facts say nothing. The pseudo-cases are easier than a busy healthy window.
- **The false-alarm level is not comparable with the lab's.** Only the paired A − D difference is read across both.
- **The pooled H2b is mostly RCAEval.** `verdict` pools per case, so 150 of the 165 cases, about 91% of the weight, are pseudo-cases.
- **D's repeats vary a lot.** The same prompts gave 0, 3 and 6 false alarms on dev, which the case-level bootstrap carries but does not remove.
- **CPU time.** A's M4 commitment rises to ≈ 67 CPU hours between 2026-12-07 and 2026-12-27.
- `docs/benchmark-limits.md` states these limits.
