# Feature: rules-baseline (Config 0)

- Branch: `feat/rules-baseline`, from `b30ae74` (chained on `feat/service-assessment-close`)
- Worktree: `../kintsugi-worktrees/rules-baseline`. The main checkout runs the service-assessment T4 local replays, which rebuild `agent/` on each run, so no file there changes until they end.
- Milestone: M2 (vision §7). Follow-up #3 of `odd/tasks/vision-revision.md`.
- Delivery: `feature-branch-chain`. The forecast is about 550 authored lines, so there are two slices: PR 1 covers T1 and T2, PR 2 covers T3 to T5.

## Objective

Build a deterministic Config 0. It diagnoses from the same typed `Dossier` and call graph the LLM sees, using the rules the prompt already gives the model, and replay scores it like the `local` and `cloud` routes. The LLM's contribution then becomes a number: the paired difference A − 0 and D − 0 (vision §1, H1).

## Problem and why

- Nothing measures whether the model adds anything over its own prompt. `Prompts.java:60-67` encodes the F1/F2 rule ("follow the calls down to the last slow or refusing component") and the F3 rule ("unreachable but healthy callee means a CONFIGURATION of the caller").
- The doc comment at `Prompts.java:15-16` claims the prompt "maps no symptom to a cause". That claim is false and must be corrected.
- Leakage principle (vision §3): a rule derived from the lab catalog stays in the prompt only if Config 0 runs it too.

## Scope

In scope:
- ADR-016.
- `RulesDiagnosis`: a pure function from `Dossier` to diagnosis.
- A replay-only `rules` route.
- The corrected Prompts doc comment.
- Living-spec updates.
- One Config 0 measurement on `dev` under a committed plan.

Out of scope:
- The holdout read: follow-up #4, m2-measurement.
- Topology as data: follow-up #5. This feature uses `CallGraph` as it is.
- The ranked report.
- RCAEval.
- Any production route. Config 0 is a baseline, never the agent's answer.

## Design

**Package and shape.**
- New package `dev.kintsugi.agent.rules`, holding `RulesDiagnosis.diagnose(Dossier) -> RulesVerdict`.
- `RulesVerdict` holds `service`, `category`, `rule` (the id of the rule that fired, or `NONE`) and `facts`. The facts are short strings naming the dossier rows that fired the rule, so every verdict can be explained.
- The class has no Spring, no I/O and no model.
- It reads the typed `Dossier`, not the rendered text, so the `tables` and `sheets` views cannot change its answer.

**Rules.** They are the prompt's rules in their order, nothing more:
- **R-REACH**, the F3 rule. A caller's client traffic to a callee carries a connection failure (connection refused or closed, unknown host; a timeout does not count). The callee shows no failure of its own: no WARN or ERROR rise, no restarts, no error answers in its server rows. The verdict is `<caller>|CONFIGURATION`.
- **R-DOWN**, the F1/F2 rule. Start from the entry service and follow `CallGraph.calls` down while the next callee is abnormal.
  - Abnormal means slow, which gives `DEPENDENCY_LATENCY`, or refusing, which gives `DEPENDENCY_REJECTING`.
  - The answer is the last abnormal component on the path, with its category.
  - "An answer that takes long is that component's own failure even when it ends in success", so slow is judged on latency and does not need errors.
- **R-NONE.** No rule fires: `UNKNOWN|UNKNOWN`. The prompt says "prefer UNKNOWN to a guess".
- `RESOURCE_EXHAUSTION` has a definition in the prompt but no discriminator, so Config 0 never returns it. That is a recorded limit, not a gap to fill.

**Thresholds.** These are the only free parameters. ADR-016 fixes them before any dev run, and they are not tuned on dev results:
- Slow: incident-window mean latency ≥ 2× the previous window's mean, and ≥ 0.5 s.
- Refusing: answers with a numeric status ≥ 400 make up ≥ 20% of the service's incident-window answers, and there are ≥ 3 of them (ADR-016 holds the full definitions).
- Any later change gets a new ADR revision, and its dev result is labelled exploratory.

**Topology.** Config 0 reuses `CallGraph` (`calls`, `calledBy`). The package-private `Edge.callerRow` and `serverRow` matchers get a public read-only accessor, or the rules live behind a small public helper in `dossier`. The writer picks the smaller change; neither adds a topology copy.

**Replay route.**
- `rules` is a branch in `ReplayDriver.run()`. It gathers the dossier with `new DossierGatherer(tools, clock).gather(...)` over the same `ReplayLabTools` and diagnoses with `RulesDiagnosis`.
- It writes the same incident JSON shape and `summary.tsv` row, with route `rules`, model `rules-v1`, tokens 0 and cost 0.
- `AgentRoute`, `ModelRoute`, `RouteEnvironmentPostProcessor` and `LoopConfig` stay closed: production cannot select Config 0.
- `infra/scripts/replay-agent.sh` accepts `--route rules`, with no key, no Ollama and no cost warning.
- The Spring context for a rules replay comes from a dedicated test entry or the local profile, without a model call. The writer picks the smaller change and documents it.

## Acceptance criteria

- AC1: ADR-016 exists, with the headings of ADR-001. It states the rules, the thresholds, the leakage principle, the RESOURCE_EXHAUSTION limit, and that Config 0 is never the agent's answer.
- AC2: `RulesDiagnosis` gives:
  - `gateway-mock|DEPENDENCY_LATENCY` on the F1 golden dossier;
  - `order-service|CONFIGURATION` on the F3 golden dossier;
  - `UNKNOWN|UNKNOWN` on the healthy golden dossier.

  Every verdict names its rule and at least one fact. Unit tests prove all of this.
- AC3: the same verdict comes back for one dossier whatever its rendered view, because the rules read only the record.
- AC4: `infra/scripts/replay-agent.sh --route rules --mode decision --split dev` writes 15 `summary.tsv` rows with route `rules`, 0 tokens and cost 0. It uses no network and no Ollama, and the scorer reads it as its own config.
- AC5: no production class can select the `rules` route. A test proves `AgentRoute.of("rules")` still throws.
- AC6: the `Prompts.java:15-16` doc comment says which rules the prompt encodes and points at ADR-016. The prompt text sent to the model is byte-identical, which the `PromptsTest` golden proves.
- AC7: `openspec/specs/agent/spec.md` gets a "Rules-only diagnosis (Config 0)" requirement and the `rules` replay route, both with GIVEN/WHEN/THEN scenarios.
- AC8: a measurement plan is committed before the Config 0 dev run. The result, under `evals/results/`, has Wilson 95% CIs and paired comparisons against the M0 A and D dev inputs. No holdout recording is read.

## Tasks

- [x] T1: ADR-016 plus the design above. Docs. Route: inline (one file, design already settled here).
- [x] T2: `RulesDiagnosis` and `RulesVerdict`, with unit tests over the `dossier/` golden fixtures, RED first (AC2, AC3). Route: delegated writer (2+ non-trivial files).
- [x] T3: the `rules` replay route: the `ReplayDriver` branch, `replay-agent.sh`, the cost-0 aggregate, tests (AC4, AC5), and the agent spec requirement (AC7). Route: delegated writer.
- [ ] T4: correct the `Prompts.java:15-16` comment, with a byte-identical prompt golden (AC6). Route: inline (mechanical).
- [ ] T5: the measurement plan, the Config 0 run on dev, the score against M0 A and D, and the committed result (AC8). Route: inline commands. The run starts only after the T4 local replays of service-assessment finish, because both rebuild under `nice` and share one CPU.

## Checks

- `cd agent && nice -n 19 ./mvnw -q test` runs the default suite. It holds 553 tests at the base.
- `infra/scripts/test-lib.sh` runs the bash lib. It holds 154 cases at the base.
- `cd evals && .venv/bin/python -m pytest -q` runs 57 tests at the base.
- Native review runs per work-unit commit (standing consent).

## Progress

- 2026-10-29: worktree and branch created. Exploration mapped Prompts rules, Dossier, CallGraph, routes and the replay seam (`ReplayDriver.run()` :266-307). This document was written.

- 2026-10-29: T1 committed as `dca9d2f`; assessment medium, under budget, so it stays pending in slice 1. The first T2 writer stopped before writing code: ADR-016 compared log counts against the previous window, but the dossier holds incident-window counts only (`DossierGatherer.java:116-128`). ADR-016 was clarified before any run: incident-window logs, a timeout matcher, missing baselines, entry services, non-numeric statuses.

- 2026-10-29: T2 implemented against ADR-016 at `4323f3c`: `RulesDiagnosis` and `RulesVerdict` in `dev.kintsugi.agent.rules`. RED: `RulesDiagnosisTest` failed to compile (no `RulesDiagnosis`, `RulesVerdict`). GREEN: `RulesDiagnosisTest` 19/19 and `CallGraphTest` 5/5; the full default suite holds 572 tests (553 + 19), 0 failures. The golden dossiers give F1 `gateway-mock|DEPENDENCY_LATENCY` (R-DOWN), F3 `order-service|CONFIGURATION` (R-REACH) and healthy `UNKNOWN|UNKNOWN` (R-NONE), with no threshold changed. `CallGraph.Edge.callerRow`/`serverRow` and the test `RecordedLab` became public; no topology copy.

- 2026-10-29: slice 1 (T1, T2; `dca9d2f`, `4323f3c`, `8647359`) reviewed: approved, lineage review-adf5f4937bf15329, acknowledged. Its WARNING (connection-failure rows counted as fallback answers) and SUGGESTION (a non-refusing callee's share in the walk tie-break) were fixed RED-first in the next commit, with ADR-016 aligned before any run; 574 tests green.

- 2026-10-29: T3 implemented (uncommitted). Spring-context choice: a dedicated `RulesReplayTest` (tagged `replay`) with no Spring context at all, over `ReplayDriver.rulesOnly(...)`, which needs no router, settings, loop or database; `replay-agent.sh --route rules` runs it instead of `ReplayRunTest`, so no context ever sees `rules` and `AgentRoute`, `ModelRoute`, `RouteEnvironmentPostProcessor` and `LoopConfig` are unchanged. The rules sample writes an incident JSON built in code (`id`, mode, route `rules`, model `rules-v1`, `report`, `rule`, `facts`, `view`, `decisionReport`, no steps); `aggregate.tsv` cost is `0.0000`. RED: `ReplayDriverTest` failed to compile (no `rulesOnly`, `rulesSample`, `rulesSamples`, `RULES_ROUTE`, `RULES_MODEL`); the base `replay-agent.sh` answered `Unknown route 'rules'; choose cloud or local` to the two new `test-lib.sh` cases. `RouteSelectionTest`'s `AgentRoute.of("rules")` guard was green from the start, as expected for a closed route. GREEN: default suite 579 tests (574 + 5), 0 failures; `test-lib.sh` 156 cases; F1 r1 dev on the rules route is `MATCH` (`R-DOWN`), as ADR-016 predicts; the dev smoke run wrote 15 rows, all route `rules`, 0 tokens, exit 0 (not scored, not committed).

## Next step

T4: correct the `Prompts.java:15-16` doc comment, prompt byte-identical.
