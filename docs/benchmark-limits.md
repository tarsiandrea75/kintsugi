# Benchmark limits

The limits `docs/benchmark.md` must state next to H1–H3 (vision §7, M4). `benchmark.md` is rendered from committed results, so its limits live here, in one place, and each one names where it was decided or found. A limit is removed only when the change that closes it is committed.

## Integrity of the holdouts

- **Commit times are self-asserted.** `check-freeze.sh` proves the order of the history as written, not that it was written honestly: the repository is local, with no remote and no posts, so nothing outside the author attests when the seed was drawn or a recording made. The backup bundles kept outside the laptop are the only independent dates. A public timestamp of the seed's hash would close it (ADR-026, amendment of 2026-11-03).
- **Lab holdout-1 was read by two configurations' runs**, each config once, as their plans fixed (`2026-10-30-holdout1-lab`, `2026-10-31-qwen-holdout1`).

## The lab

- **One fault at a time, no load generator, no concurrent traffic.** F3's parameter is fixed, and F4 and F8 are not in the agent eval (`odd/tasks/lab-realism-min.md`, by the cut order of vision §7).
- **Holdout-2's parameters vary for F1 and F2 only**, uniformly per case over declared values, so each value has a few cases, not a fixed count.

## The agent and its tuning

- **The prompt and dossier changes of 2026-11-03 were chosen after diagnosing the same 30 RE2-OB dev cases** they were measured on: the latency pairs, the resource warrant, the ranked report and suspects with an abstention. Every gain on dev is a promise until the holdouts read it once (`odd/tasks/d-abstention.md`, `latency-pairs.md`, `resource-warrant.md`, `ranked-report.md`).
- **Part of D's gain over 0R may be R-RES read aloud.** With the resource warrant, 13 of D's 16 right resource answers on dev were cases 0R also solves (`odd/tasks/resource-warrant.md`).
- **One run per configuration on dev.** Identical runs of D differ by up to 6 discordant cases (`odd/tasks/d-abstention.md`).

## Scoring

- **RCAEval is scored on the service only.** With the category, D's 11 of 13 answered cases on dev were 7 of 13 (`odd/tasks/d-abstention.md`).
- **The chance level uses the topology's 11 services.** BARO ranks over 16 to 20 header names, so its own chance is lower (`odd/tasks/rcaeval-ranked-metrics.md`); RE2-TT's 68 ranked services against the paper's 64 are not reconciled (`odd/tasks/rcaeval-baro-reproduction.md`).
- **BARO is checked against a published table on RE2-TT only.** No published RE2-OB number was found (`odd/tasks/rcaeval-baro-reproduction.md`).
- **Verified-correct and the L1 rate are reported on RE2-OB dev only, from re-checked answers.** Every stored report now states its level, L0 to L3 (ADR-028), and the replay's `summary.tsv` ends with a `level` column the scorer reads for both rates (evals spec, REQ-16). Results committed before the verification-levels feature have no `level` column. `evals/results/2026-11-04-verification-levels-descriptive/` re-checks DS's and AS's stored RE2-OB dev answers with today's checks (DS verified-correct 18/30, AS 10/30), descriptively; no lab result reports either rate, since the lab dev answers on disk predate today's dossier text, and a new lab run is needed to measure them. The L3 rules carry their own limits. The latency rule's 0.5 s floor, taken from R-DOWN, kept most RE2-OB delay answers at L2; it was removed on 2026-11-04 after seeing those numbers (ADR-028, Decision 5, amended), and `evals/results/2026-11-04-verification-levels-ratio/` re-checks the same answers with the ratio alone (DS verified-correct 25/30, AS 11/30), post hoc on the cases that motivated the change. Without the floor a caller of the cause, whose latency rises with it, can reach L3: one wrong DS answer does, so L3 does not imply a correct answer.

## Safety

- **The zero is the policy gate's** (ADR-027): every tool call passes a deterministic policy that refuses anything not classified as a read, and the counts are recorded per incident. No write tool exists, so no run has been offered a write to refuse; the gate is proved by tests, not by runs.
- **No human approval exists.** Every write is refused, by vision §7's cut order item 6; the demo shows no approval.
