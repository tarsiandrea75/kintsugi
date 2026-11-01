# hybrid

## Objective

Build Config H (ADR-019): the rules propose up to 5 ranked candidates, the local model Qon (ADR-018) picks one or UNKNOWN and explains it. Check its mechanics on lab dev under a committed plan. The stop-rule measurement, H − 0 on RCAEval dev, waits for the converter and topology-as-data.

## Problem

On holdout-1 the rules (10/15) and Qon (12/15) fail on different faults: the rules abstain on F2, while Qon blames the caller on F1. An oracle of the two reaches 13/15. Today no config combines them. The rules return one verdict and keep their other candidates private (`RulesDiagnosis.java`: the R-REACH `failures` map, the R-DOWN abnormal callees, the per-service `Assessment`). Decision mode has no report that limits the model to a list.

## Why

If the model chooses well among the rules' candidates, the local route gets a number above the rules alone on the faults where each one fails. If it does not, H − 0 says so, and the LLM's contribution on the local route is measured as zero.

## Scope

- In:
  - ADR-019 and the vision amendment;
  - `RulesDiagnosis.candidates(Dossier)`;
  - a `hybrid` decision report;
  - the replay option `--report hybrid` and a `candidates` column in `summary.tsv`;
  - candidate recall in the scorer;
  - the specs;
  - a `stress` calibration, the lab dev plan and the dev run.
- Out:
  - the RCAEval converter and topology-as-data (separate features, still open under M2's dates);
  - the H − 0 measurement on RCAEval;
  - any holdout read;
  - any change to `diagnose()`, its thresholds or Config 0.

## Constraints

- Config 0 is frozen at `5797d16` (ADR-016). `diagnose()` must give the same verdict on every golden dossier and every recording, and the existing tests prove it.
- Production stays closed to `route=rules`. The rules enter the agent only through the `hybrid` report.
- No fallback: a timed-out or invalid H report has no answer (ADR-019).
- Local runs take hours and never share the CPU.

## Design

- **Candidates (rules).** `RulesDiagnosis.candidates(Dossier)` is a pure function returning `List<RulesCandidate>`, 0 to 5 items, each `(service, category, rule, facts)`, with no duplicate service. Order:
  1. `diagnose()`'s verdict, when it is not UNKNOWN;
  2. the other R-REACH callers, by failure count;
  3. the other abnormal services found by R-DOWN's assessment, by refusing share, then latency ratio, then service order.

  Services that are unhealthy but neither slow nor refusing (restarts, WARN/ERROR lines only) are not proposed. The rules have no category for them: ADR-016 rules out RESOURCE_EXHAUSTION, and a pair with category UNKNOWN cannot be picked under T3's membership check. Decided at T2 (2026-10-31).

  Categories follow the rule that would name each service: a refusing service gets DEPENDENCY_REJECTING, a slow one DEPENDENCY_LATENCY, and R-REACH keeps its own category. A healthy dossier gives an empty list. `diagnose()` stays as it is; candidates reuse its private helpers, and nothing it returns changes.
- **The hybrid report (agent).** `DecisionReport.HYBRID` sits beside `PLAIN` and `ASSESSED`, selected by `kintsugi.agent.decision.report=hybrid`.
  - `AgentLoop` computes the candidates from the gathered typed `Dossier`.
  - An empty list ends the run with `UNKNOWN|UNKNOWN` and no model call.
  - Otherwise the report prompt adds a numbered candidate block (service, category, the rule's facts) to the plain report task. The prompt says to pick exactly one candidate or UNKNOWN.
  - The schema stays the plain report's. `ReportCheck` adds one rule: `rootCause` must be a listed pair or `UNKNOWN|UNKNOWN`. A violation is an invalid report, which gets the existing single retry, then `INVALID_REPORT`.
- **Replay and scoring.**
  - `--report hybrid` is accepted only for `--route local`, and for `--route cloud` as a later ablation.
  - `summary.tsv` gains a last column, `candidates`: the pairs in order, `;`-separated, written by the `rules` and `hybrid` rows and empty otherwise.
  - The scorer reports candidate recall per fault (the truth is in the list) and the AC@1 that recall bounds.
- **Measurement on lab dev.** H (Qon, settings from the calibration) beside the committed 0 and Qon dev inputs. It is descriptive only, because dev has been read many times and the stop rule names RCAEval.

## Tasks

- [x] T1 ADR-019, the vision amendment, and this document. Route: inline (docs). Commit `8ba6a38`; passive docs, structural readback only.
- [x] T2 `RulesDiagnosis.candidates`, tests first: golden dossiers, ordering, cap 5, no duplicates, healthy is empty, and `candidates[0]` equals `diagnose()` when it is not UNKNOWN. The agent spec's Config 0 requirement gets a candidates scenario. Route: delegated writer.
- [x] T3 The `hybrid` decision report, tests first: the prompt block, the membership check, retry then `INVALID_REPORT`, the empty list giving UNKNOWN with no call, production still closed to `route=rules`. The agent spec's "Decision mode" and "The report" requirements. Route: delegated writer.
- [x] T4 Replay `--report hybrid` and the `candidates` column, tests first. Rules rows write their candidates. Docs in `docs/setup.md`. Route: delegated writer.
- [x] T5 Scorer candidate recall, tests first, and the evals spec. Route: inline or delegated, decided at the task.
- [x] T6 Calibration on `stress`: one H run with Qon to fix the report cap and deadline. Route: inline commands.
- [x] T7 The lab dev plan, committed before the run. Route: inline.
- [x] T8 The H dev run, the score with 0 and Qon, and the reading in STATE. Route: inline commands.
- [x] T9 (added 2026-11-01, after the RCAEval converter) The RE2-OB dev plan `evals/plans/2026-11-01-hybrid-re2ob-dev.md`, committed before the run. It fixes the stop-rule outcome in advance: the rules' candidates cap H at 5/30 against Config 0's 4/30. Route: inline.
- [ ] T10 The H run on RE2-OB dev, the score with 0, and the reading in STATE. Route: inline commands.

## Acceptance criteria

- AC1: `diagnose()` gives the same verdict as before on every golden dossier and on the dev and holdout recordings (Config 0 replay unchanged).
- AC2: `candidates` has at most 5 entries, no duplicate service, the rules' verdict first, and an empty list for a healthy dossier (tests).
- AC3: a hybrid report naming a pair outside the list is invalid and retried once (test).
- AC4: an empty list gives `UNKNOWN|UNKNOWN` with zero model calls (test).
- AC5: `summary.tsv` carries the candidates, and the scorer reports candidate recall (tests).
- AC6: the dev plan is committed before the H run. The result has Wilson CIs, paired H − 0 and H − Qon, and candidate recall.

## Checks

- `cd agent && ./mvnw -q test` (excludes the `live` and `replay` tags)
- `cd evals && .venv/bin/python -m pytest -q`
- The Config 0 replay over dev gives the committed `0-summary.tsv` verdicts.

## Delivery

Forecast about 750 authored lines (docs 150, candidates with tests 200, hybrid report with tests 250, replay 80, scorer 70). That is over the 400-line budget, so `feature-branch-chain` (standing preference). Branch `feat/hybrid` from `feat/qwen-dev` (`ccb7a40`).

## Progress

- 2026-10-31: feature document created; branch `feat/hybrid` at `ccb7a40`. Exploration (read-only explorer):
  - the rules return one verdict, with their candidate sets private;
  - decision mode's report variants are the natural plug-in;
  - there is no AC@k and no RCAEval converter;
  - the rules route is replay-only by spec.

  The vision forbade rules in the agent's answer; the human approved the amendment (ADR-019).

- 2026-10-31: T2 done. Route: delegated writer (rules code, tests, spec). `RulesCandidate` is new. `RulesDiagnosis.candidates(Dossier)` implements order steps 1–3. `diagnose()` moved into a private `verdict()` with its body unchanged; `reach()` takes the first of `reachCallers()` under the same comparator; the walk's comparator became `BY_ABNORMALITY`. Agent spec: new requirement "Rules candidates (Config H)" with 4 scenarios.
  - RED: `RulesDiagnosisTest` failed to compile (46 missing symbols). GREEN: 27 tests, 0 failures; full `./mvnw -q test` exit 0, 599 tests, 0 failures.
  - AC1 on recordings: the Config 0 replays over dev and holdout (`replay-20261031T123404Z-rules`, `replay-20261031T123411Z-rules`) give the same verdict per case as the committed `0-summary.tsv` inputs.
  - Commit `d96320b`. Review assess (base `ccb7a40`): medium, `review_due` false, `under_budget`; the slice stays pending.
  - Design change: step 4 was dropped (see Design). The cap of 5 cannot bind in the lab, which has 4 services in `CallGraph.SERVICES`; it binds on RCAEval.

- 2026-10-31: T3 done. Route: delegated writer (loop code, tests, spec). `DecisionReport.HYBRID` (`kintsugi.agent.decision.report=hybrid`).
  - `AgentLoop.dossier()` keeps `RulesDiagnosis.candidates` in `Run.candidates`.
  - An empty list ends the run COMPLETED as `UNKNOWN|UNKNOWN`, with no warm-up and no model call: decision mode gathers before the warm-up.
  - `Prompts.hybridReport` adds the numbered candidate block to the plain task.
  - `ReportCheck.checkHybrid` rejects a pair outside the list; the existing single retry and `INVALID_REPORT` apply.
  - Agent spec: "Decision mode" updated, and a new requirement "The hybrid report (Config H)" with 5 scenarios.
  - RED: 12 missing symbols. GREEN: AgentLoopDecisionModeTest 35, ReportCheckTest 65, 0 failures; full suite 607 tests, 0 failures. The T2 baseline was 597, not 599 (the writer measured HEAD `959a23a` in a scratch worktree), so T3 adds 10 tests. Parent spot check: both classes green.
  - Accepted choices:
    - When the gathering gives no dossier there is no list. The run then asks for the plain report, which can only be valid as UNKNOWN, mirroring ASSESSED.
    - The stored report does not hold the candidates; T4 adds them to the replay output.
    - The loop tests use F3 with healthy restarts (one candidate). Two candidates are covered in ReportCheckTest.

- 2026-10-31: native review of the slice `ccb7a40..5cff0b2` (T2 and T3; medium, `slice_budget_reached`): consent granted, one lens (reliability), approved and acknowledged (lineage `review-108c1c23b85d453e`).
  - Fixed: R3-spec-contradicts-no-candidate-behavior (the spec said a decision run makes 1 or 2 model calls; it now adds "or none with the hybrid report and no candidate") and R3-state-next-action-stale.
  - Follow-ups, informational:
    - WARNING R3-empty-candidates-unproved-on-partial-dossier (`AgentLoop.java:259-262`): when the gathering yields a partial dossier, the rules may propose nothing, and H answers UNKNOWN with no call. The empty-list test does not cover a partial dossier.
    - SUGGESTIONs: R3-candidate-cap-unproved (it cannot bind with the lab's 4 services), R3-either-view-test-proves-construction-only, R3-no-candidate-report-fields-unasserted.

- 2026-10-31: T4 done. Route: delegated writer (script, driver, loop, docs, spec).
  - `replay-agent.sh --report hybrid` works for local and cloud. With `--route rules` it exits 2, and outside decision mode the existing check refuses it.
  - `summary.tsv` gains a last column, `candidates`. Rules rows compute it from the same `Dossier` they diagnose. Hybrid rows take the list the run used: `AgentLoop.runToOutcome(UUID)` returns the status and `Run.candidates`, and `run(UUID)` delegates to it. `aggregate.tsv` is unchanged.
  - The scorer tolerates the extra column (`csv.DictReader`), so `scoring.py` is unchanged.
  - RED: ReplayDriverTest failed to compile, and `test-lib.sh` failed on the new message. GREEN: ReplayDriverTest 31, full suite 609 tests, 0 failures; `test-lib.sh` 160 PASS (parent re-ran it); evals 66 passed.
  - Rules smoke on dev (`replay-20261031T131039Z-rules`): verdicts identical to `replay-20261031T123404Z-rules`.
  - Candidates on dev: F1 `gateway-mock|DEPENDENCY_LATENCY` first, then `payment-service`, and in one case also `order-service`. F2 `gateway-mock|DEPENDENCY_REJECTING` alone. F3 `order-service|CONFIGURATION` alone. The truth is in every list (recall 15/15), and on F2 and F3 the list has a single entry. On dev, H can only lose to the rules by answering UNKNOWN or an invalid report, or by leaving the first candidate on F1.
  - Follow-ups: `ReplayDriver.rulesOnly(..., HYBRID)` is guarded only by the script; there is no script test for `--route cloud --report hybrid`, because it could start a paid run.

- 2026-10-31: native review of `5cff0b2..4715600` (T4, high risk): consent granted, four lenses, approved and acknowledged (lineage `review-a49d8aeb95880378`).
  - Fixed: R2-state-next-action-names-finished-task and R3-state-next-action-stale-after-t4 (STATE's next action).
  - Follow-ups (SUGGESTIONs on `test-lib.sh:606-607`):
    - R2-either-view-test-name-overstates-coverage: the test's name claims more coverage than it has.
    - R3-hybrid-script-acceptance-partly-unproved: the script test proves only the local route's acceptance.

- 2026-10-31: T5 done. Route: delegated writer (scorer, cli, tests, spec: 2+ non-trivial files), not inline as first planned.
  - `Row.candidates` is read only for `route` rules or `report` hybrid, and only when the column exists.
  - `FaultScore.recalled` and `candidate_recall`; `report.txt` shows `recall x%`, and `scores.tsv` gains a last column `candidate_recall`. Evals spec REQ-7a "Candidate recall".
  - A label that mixes rows with and without candidates gets no recall (accepted: a config has one route and one report).
  - RED: 5 failed, 66 passed. GREEN: 72 passed (parent re-ran it).
  - Backward compatibility: `docs/benchmark.md` regenerated from the committed results is byte-identical (parent check with `--out ../docs/benchmark.md`, since the header prints that path). The committed `inputs/*-summary.tsv` re-score to their committed `report.txt`.
  - Smoke on the T4 rules dev run: recall 100% on F1, F2 and F3; AC@1 10/15.
  - Commit `86b822d`. Review assess (base `4715600`): medium, `under_budget`; the slice stays pending.

- 2026-10-31: T6 done. Route: inline commands. Run `replay-20261031T131815Z-local` on `518c3d9`: H with Qon, cap 4096, deadline 2700 s so neither binds, 3 `stress` recordings. All samples COMPLETED with one report call each and one candidate each.
  - Report call: 732 s, 749 s and 1561 s; prompt about 4,440 tokens; completion 304-365 tokens as Ollama reports them.
  - The 1561 s call is longer than Qon's 1200 s deadline from `qwen-dev`, which was set from a longest call of 801 s. Thinking time varies more than that calibration showed.
  - Only timing and token columns were read for the calibration. Printing step metadata also showed the first 40 characters of each report summary; no verdict column was read. These are `stress` recordings, not dev or holdout.
  - Settings for the dev plan: cap 4096 (it does not bind). Deadline 2100 s: 1561 s × 1.35 for a slower day (the factor `qwen-dev` used), rounded up. A second attempt then fits the 45 min incident budget only when the first took under 600 s. Every calibration sample needed one call, so a slow single call is the larger risk.

- 2026-10-31: T7 done. The plan `evals/plans/2026-10-31-hybrid-dev.md` is committed with this entry before the dev run (human go-ahead for T7 and T8). It sets H against the committed 0 and Qon dev inputs. It is a mechanics check with no decision rule on the configs.

- 2026-10-31: T8 done. Route: inline commands. Run `replay-20261031T144625Z-local` on `eafa834`, scored into `evals/results/2026-10-31-hybrid-dev/`.
  - Mechanics check: passed. 15 samples, split `dev`, view `tables`, report `hybrid`, `qwen3.5:9b`, think on, cap 4096, deadline 2100 s; every sample COMPLETED. No sample was discarded or rerun.
  - AC@1: H 13/15, 87% [62%, 96%]; F1 3/5, F2 5/5, F3 5/5. Coverage 100%, candidate recall 100% on every fault. Against the committed inputs: 0 10/15 (F2 0/5), Qon 8/15 (F1 1/5).
  - Paired: H − 0 +20 points (H only 5, 0 only 2; McNemar p = 0.45, Holm 0.91; bootstrap [−13, +53]). H − Qon +33 (H only 5, Qon only 0; p = 0.062, Holm 0.19; bootstrap [+13, +60]). Qon − 0 −13 (Holm 0.91).
  - Per-fault net: against 0, F1 −2, F2 +5, F3 0. Against Qon, F1 +2, F2 +1, F3 +2.
  - UNKNOWN 0, `INVALID_REPORT` 0. Every sample needed one report call; no second attempt.
  - F1: H kept the rules' first candidate (the truth) 3 times out of 5. In r1 and r7 it picked `payment-service`, the second candidate, the caller: the same miss Qon makes alone.
  - Report time per sample 675-1660 s, median 850 s, total about 4.1 h.
  - Reading. On F2 and F3 every list had one candidate, the truth, so H's gain there comes from the rules' proposals, not from the model's choice. F1 is the only fault with a real choice, and there the model lost 2 of 5 that the rules' first candidate had right. On these 15 cases, "take the first candidate" (the fallback ADR-019 rejected for measurement) would score 15/15. Dev has been read many times, so this is no claim; it says where to look on RCAEval: whether the model adds anything when the list has more than one entry.
  - Commit `5fc7224`. Review of `4715600..5fc7224` (T5-T8): medium, `slice_budget_reached`; one lens (reliability), approved with no findings and acknowledged (`review-ee0ba94db473e384`).

## Next step

T10 on `feat/hybrid-rcaeval`: the H run on RE2-OB dev under `evals/plans/2026-11-01-hybrid-re2ob-dev.md`.
