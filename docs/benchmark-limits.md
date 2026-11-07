# Benchmark limits

The limits `docs/benchmark.md` must state next to H1–H3 (vision §7, M4). `benchmark.md` is rendered from committed results, so its limits live here, in one place, and each one names where it was decided or found. A limit is removed only when the change that closes it is committed.

## Integrity of the holdouts

- **Commit times are self-asserted.** `check-freeze.sh` proves the order of the history as written, not that it was written honestly: the repository is local, with no remote and no posts, so nothing outside the author attests when the seed was drawn or a recording made. The backup bundles kept outside the laptop are the only independent dates. A public timestamp of the seed's hash would close it (ADR-026, amendment of 2026-11-03).
- **Lab holdout-1 was read by two configurations' runs**, each config once, as their plans fixed (`2026-10-30-holdout1-lab`, `2026-10-31-qwen-holdout1`).
- **TT has been seen.** BARO's reproduction read every RE2-TT case's truth label and BARO's ranks on it ([ADR-024](adr/ADR-024-baro-port.md), `evals/plans/2026-11-03-baro-re2tt-reproduction.md`) before RE2-TT became holdout TT. Neither the agent nor the rules were run or tuned on it, but TT is not as unseen as holdout B ([ADR-032](adr/ADR-032-m4-holdout-coverage.md)).

## The lab

- **One fault at a time, no load generator, no concurrent traffic.** F3's parameter is fixed, and F4 and F8 are not in the agent eval (`odd/tasks/lab-realism-min.md`, by the cut order of vision §7).
- **Holdout-2's parameters vary for F1 and F2 only**, uniformly per case over declared values, so each value has a few cases, not a fixed count.

- **RCAEval's healthy cases are pseudo-cases.** H2b reads 150 of its 165 healthy cases from the 12 minutes before each RCAEval injection, as two 6-minute windows, kept by a dev gate on D (9/90 false alarms) ([ADR-034](adr/ADR-034-rcaeval-healthy-pseudo-cases.md)).
  - A 6-minute window holds about 0.6× the events of a 10-minute one, so count floors such as `SLOW_TIMEOUTS` and `COUNT_MIN` fire less often, which leans toward fewer false alarms.
  - On RE2-OB dev, none of the 30 pre-injection windows held a WARN or ERROR log line, so the log facts were silent on every pseudo-case.
  - Their false-alarm level is not comparable with the lab's; only the paired A − D difference, on identical windows, is read across both. The pooled H2b weighs each case once, so the pseudo-cases carry about 91% of it.
  - H2c excludes them: they enter H2b only (`verdict --h2b-only`), because A's times on their smaller dossiers would pull H2c's median down in A's favour. H2c reads A's fault cases and the lab's 15 real healthy controls.
  - D's repeats varied a lot on dev: 0, 3 and 6 false alarms in 30 from the same prompts.

## The agent and its tuning

- **The prompt and dossier changes of 2026-11-03 were chosen after diagnosing the same 30 RE2-OB dev cases** they were measured on: the latency pairs, the resource warrant, the ranked report and suspects with an abstention. Every gain on dev is a promise until the holdouts read it once (`odd/tasks/d-abstention.md`, `latency-pairs.md`, `resource-warrant.md`, `ranked-report.md`).
- **Part of D's gain over 0R may be R-RES read aloud.** With the resource warrant, 13 of D's 16 right resource answers on dev were cases 0R also solves (`odd/tasks/resource-warrant.md`).
- **One run per configuration on dev.** Identical runs of D differ by up to 6 discordant cases (`odd/tasks/d-abstention.md`).

## Scoring

- **RCAEval is scored on the service only.** With the category, D's 11 of 13 answered cases on dev were 7 of 13 (`odd/tasks/d-abstention.md`).
- **The chance level uses the topology's 11 services.** BARO ranks over 16 to 20 header names, so its own chance is lower (`odd/tasks/rcaeval-ranked-metrics.md`); RE2-TT's 68 ranked services against the paper's 64 are not reconciled (`odd/tasks/rcaeval-baro-reproduction.md`).
- **RE2-TT's topology is simplified.** Train Ticket's seat service calls back into the travel services, a cycle the agent's `Topology` rejects, so `re2-tt.json` drops `ts-seat-service` → `ts-travel-service` and → `ts-travel2-service` and keeps the travel → seat direction. Config 0, 0R and D read the same file, but errors flowing from seat to travel are unexplained by the graph and L2's on-path check can misfire there; how many TT cases this touches is not inspected, since it would read holdout truth ([ADR-033](adr/ADR-033-re2-tt-topology-cycle.md)).
- **BARO is checked against a published table on RE2-TT only.** No published RE2-OB number was found (`odd/tasks/rcaeval-baro-reproduction.md`).
- **Verified-correct and the L1 rate are reported on dev only: RE2-OB dev from re-checked answers, lab dev from one fresh cloud run.** Every stored report now states its level, L0 to L3 (ADR-028), and the replay's `summary.tsv` ends with a `level` column the scorer reads for both rates (evals spec, REQ-16). Results committed before the verification-levels feature have no `level` column. `evals/results/2026-11-04-verification-levels-descriptive/` re-checks DS's and AS's stored RE2-OB dev answers with today's checks (DS verified-correct 18/30, AS 10/30), descriptively; the lab dev answers on disk predate today's dossier text, so `evals/results/2026-11-04-verification-levels-lab-dev/` is one fresh DS run on lab dev (15 cases, F1 to F3, lab dev seen before): DS verified-correct 14/15, every correct answer at L3, one `INVALID_REPORT` without a level. The L3 rules carry their own limits. The latency rule's 0.5 s floor, taken from R-DOWN, kept most RE2-OB delay answers at L2; it was removed on 2026-11-04 after seeing those numbers (ADR-028, Decision 5, amended), and `evals/results/2026-11-04-verification-levels-ratio/` re-checks the same answers with the ratio alone (DS verified-correct 25/30, AS 11/30), post hoc on the cases that motivated the change. Without the floor a caller of the cause, whose latency rises with it, can reach L3: one wrong DS answer does, so L3 does not imply a correct answer.

## Safety

- **The zero is the policy gate's** (ADR-027): every tool call passes a deterministic policy that refuses anything not classified as a read, and the counts are recorded per incident. No write tool exists, so no run has been offered a write to refuse; the gate is proved by tests, not by runs.
- **No human approval exists.** Every write is refused, by vision §7's cut order item 6; the demo shows no approval.
