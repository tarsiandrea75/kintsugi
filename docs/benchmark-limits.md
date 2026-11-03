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
- **Only L0 and L1 of the verification levels exist.** No report states a level, so "verified-correct at L3" (vision §6) cannot be reported until the verification-levels feature lands.

## Safety

- **No write tool exists**, so "zero risky actions without approval" is structural until the policy gate lands, and measured only through the gate afterwards.
