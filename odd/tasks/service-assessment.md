# Feature: the model assesses one service at a time, checked against the call graph

## Objective

Give the model a smaller job in decision mode:
- the dossier's facts are rendered per service, leaf first, next to the call graph;
- the report first assesses each service's role (ORIGIN, AFFECTED or UNAFFECTED) and only then names the cause;
- deterministic code checks those roles against the graph.

The model still decides. Code organizes the facts and verifies the claims.

## Problem

- **Local, old recordings.** On F1, `granite4.1:8b` named payment-service 9 of 15 times, one hop early, although the dossier shows `gateway-mock /authorize 9.256 s`. On F3 it never named order-service.
- **Clean recordings** (`odd/tasks/decision-instrument.md`, T4 interim, cloud decision mode): F3 4/4 and F2 5/5, but F1 3/5. Both F1 misses name `payment-service|DEPENDENCY_LATENCY`.
- **A caller's latency contains its callee's.** In F1-r1, payment-service's `/payments` mean is 1.229 s against gateway-mock's `/authorize` 1.177 s, and the maxima are 9.979 s and 9.905 s. Comparing magnitudes cannot find the origin; the call graph can: a slow leaf is the origin, and a slow caller of a slow service only suffers from it.
- **The dossier is organized by data source**, not by the decision variable. One service's facts are spread over six tables of about 55 rows, and 8 of the 12 latency rows are `/actuator/*`.
- **The prompt already says** "follow the calls down to the last component that is slow or refuses" (`Prompts.java:60-67`). One generation over tables does not do it.

## Why

Facts organized by service turn a multi-hop synthesis over tables into a few local judgments, and code can check those judgments against the graph. This is the core rule applied to the diagnosis: the LLM proposes, deterministic code verifies. The vision asks for exactly this: "short context: logs filtered and summarized by code" (`docs/vision.md:116-117`).

## Scope

In:
- the call graph as data;
- a per-service fact-sheet renderer that records which service owns each numbered line;
- the assessed report: the schema, its prompt task and code-derived evidence;
- `ReportCheck` consistency rules;
- settings that select the variant;
- tests, with goldens;
- the spec;
- ADR-014, which amends ADR-011's "facts only" rule and records the stop rule (re-scoped on 2026-10-25: it no longer records an M2 bar).

Out:
- the explorer and dossier modes, whose rendering and report stay as they are;
- judgments, thresholds or anomaly markers in the facts;
- rule-generated hypotheses. On 2026-10-25 they became Config 0, the rules-only baseline of the revised vision, built in its own follow-up feature (`odd/tasks/vision-revision.md`, follow-up 3).
- reading `holdout` (re-scoped on 2026-10-25): holdout-1 is read in follow-up feature 4, next to Config 0.

## Design

Facts from the code map (2026-10-24):
- every dossier fact can be tied to one service. Restarts, latency and logs carry `service`. A traffic row on the client side belongs to the caller (`service`), with the callee in `clientName`; a server-side row belongs to the callee.
- the state tables and event sources map by name:

  | Source | Service |
  |---|---|
  | orders table | order-service |
  | payments table | payment-service |
  | order events | order-service |
  | payment events | payment-service |
  | reservation events | inventory-service |

- the only call graph in code is `DossierRenderer.EDGES`.
- the rendered dossier reaches `ReportCheck` as a plain `String`, and `DossierRenderer.line` scans it for `D<n> `.

1. **`CallGraph`, one source.** It holds:
   - the services in sheet order, leaf first: gateway-mock, inventory-service, payment-service, order-service;
   - the edges, the same ones as `EDGES`;
   - `calls(s)` and `calledBy(s)`;
   - the owner of each table and event source.
2. **`ServiceSheets` renderer.** It renders the same `Dossier` data. It returns the text plus the owner of every numbered line, keeps the `D<n>` numbering, and states facts only (the renderer's banned words test applies). Per service, leaf first:
   - "calls: …; called by: …";
   - restarts in the incident window;
   - inbound requests by status and outcome, as "incident N (previous M)";
   - the latency of business URIs (`/actuator/*` dropped): incident mean and max, with the previous mean;
   - outbound calls per edge, with outcomes and exceptions, as "incident N (previous M)";
   - WARN and ERROR signatures (incident window);
   - the tables and events it owns, as "incident N (previous M)".

   A final section, "seen only in the previous window", holds the rows whose incident count is 0 and previous count is above 0; each line is still owned by its service.
3. **The assessed report**, decision mode only. The schema first asks for `assessments`: an object with one property per service, in sheet order, each `{role: ORIGIN | AFFECTED | UNAFFECTED, line, why}`. Then come `summary`, `rootCauseService`, `rootCauseCategory` and `confidence`.
   - Code derives `evidence` from the assessment lines of the ORIGIN and AFFECTED services.
   - A test checks that the local route's grammar-constrained decoding keeps the property order.
4. **Consistency rules** in `ReportCheck`. They are fault-agnostic: they check the model's own claims against the graph and never name a cause. A violation goes to the existing single retry with the exact error.

   | Rule | It checks |
   |---|---|
   | Coverage | every service is assessed once |
   | One origin | at most one ORIGIN, and exactly one unless every service is UNAFFECTED (the cause is then UNKNOWN) |
   | Graph | an AFFECTED service calls an ORIGIN or AFFECTED service |
   | Answer | `rootCauseService` is the ORIGIN |
   | Citation | each cited line is owned by the service it is cited for |
5. **The prompt.** A decision-mode report task explains the three roles. The category definitions stay unchanged.
6. **Variants, behind settings.** Defaults keep today's behavior, so M0 (decision-instrument T5) measures today's prompt.
   - `kintsugi.agent.decision.view`: `tables`, today's dossier and the default, or `sheets`.
   - `kintsugi.agent.decision.report`: `plain`, today's report and the default, or `assessed`.
   - V1 is `sheets` with `plain`; V2 is `sheets` with `assessed`. An optional V3 adds one worked example on a fictional topology, which teaches the method and not an answer.
   - Measured on `dev` only. Keep the best, delete the others before the feature closes, then read `holdout` once. (Re-scoped on 2026-10-25: `holdout` is not read in this feature; see "The M2 bar and its gates".)

## The M2 bar and its gates

Re-scoped on 2026-10-25 by the vision revision (`odd/tasks/vision-revision.md`). The M2 check is now the revised vision's (`docs/vision.md` §7): `docs/benchmark.md` v0 holds the lab holdout-1 table for Configs 0, A and D, with AC@1, Wilson CIs and paired differences, from committed results and a plan committed before the run. It passes whatever the local model scores.

The bar of at least 4 of 5 per fault is dropped. With 5 cases, 4/5 has a Wilson 95% CI of [38%, 96%], and a bar of at least 4 of 5 on each of three faults fails a model that is truly 80% accurate 60% of the time. The data in hand already shows the gap it was meant to judge: M0 on `dev`, local 5/15 [15%, 58%] against cloud 14/15 [70%, 99%] on the same recordings, exact McNemar p ≈ 0.004.

What this feature still delivers:
- V1 and V2 measured on `dev`, local and cloud, under a measurement plan committed before the run (T4); the best is kept;
- the stop rule (vision §5), recorded in ADR-014: local-only adaptations end with this feature. After it, a change must be track-agnostic and win a paired comparison on RCAEval dev;
- one descriptive live round (T5).

The old gate-3 options map to the revised vision:
- another local model: `qwen3:8b` runs once on `dev`, which closes decision-instrument T5;
- a 14B model overnight: Config B (follow-up 15, cuttable);
- (C) rule-generated candidate hypotheses: Config 0, a baseline that is never the agent's answer (follow-up 3);
- documenting the limit: a local miss on the lab is documented, not chased (the stop rule).

The original bar and gates, kept as history:

The bar turns the vision's check ("F1-F3 correctly diagnosed with a local model") into numbers without changing its wording. ADR-014 records it:
- on `holdout`, the local route scores at least 4 of 5 per fault on F1-F3, at temperature 0 with one sample per recording;
- the cloud route also scores at least 4 of 5 per fault;
- one live local round passes, `verify-agent.sh --mode decision --route local`, with all three faults matched.

The gates; the human decides at each one:
1. After M0, with today's prompt on clean recordings: it tells how much of each fault was the old protocol.
2. After V1 and V2 on `dev`: if the bar is met, read `holdout` once, then run the live round, then close M2.
3. If the bar is not met, the human picks, with the data in hand:
   - (C) rule-generated candidate hypotheses: the vision's `reasoning` module (`vision.md:72,116`), pulled forward from M3. It needs an ADR superseding ADR-011's ban on thresholds, plus a rules-only baseline config, and it reuses this feature's graph and sheets.
   - another local model;
   - a 14B model overnight;
   - documenting the limit.

## Acceptance criteria

1. Sheets are rendered from the same `Dossier` data and state facts only. Every numbered line has exactly one owner, a service or the header; the order is leaf first; `/actuator/*` latency rows are dropped; rows seen only in the previous window have their own section. Goldens cover the f1, f3 and healthy fixtures and a clean decision recording.
2. The assessed schema lists `assessments` first, in fixed order. The order holds in the local route's schema, and `evidence` is derived from the assessments.
3. Each consistency rule has a test that fails before the rule exists. A violation triggers the existing single retry with the exact error.
4. The settings default to today's behavior. The explorer and dossier modes are unchanged, and every existing test stays green.
5. V1 and V2 are measured on `dev`, local and cloud, against M0, under a measurement plan committed before the run. The best variant is kept and the others deleted; the results are in this document. `holdout` is not read in this feature (re-scoped on 2026-10-25; it was "read once").
6. Living docs: the spec (decision mode, rendering, report, checks), ADR-014 with the stop rule and no bar, and ADR-011's status line ("amended by ADR-014").

## Tasks

- [x] T1 `CallGraph` and the per-service sheets with line owners, behind `kintsugi.agent.decision.view`, with goldens. Route: delegated writer (dossier package, settings, tests).
- [x] T1r The T1 review findings (2 WARNINGs, 6 SUGGESTIONs), and acceptance criterion 1's golden from a clean decision recording, after decision-instrument's T4 import. Route: delegated writer.
- [x] T2 The assessed report: schema, prompt task, code-derived evidence, consistency rules, and `kintsugi.agent.decision.report`. Route: delegated writer (loop package, tests, spec).
- [x] T2r Replays ask for the assessed report: `replay-agent.sh --report`, as `--view` in T1 (b) (`ReplayDriver`, `ReplayRunTest`, `replay-agent.sh`, `test-lib.sh`). Plus the T2 review's WARNING R3-quote-orElseThrow at `ReportCheck.java:236-237`: an owned line with no match in the text must give an invalid result for the retry, not throw. Route: delegated writer.
- [x] T3 ADR-014 without the bar, plus the stop rule (vision §5); ADR-011's status; the spec's remaining parts. Route: delegated writer, with T2r (the plan had said inline).
- [ ] T4 Measurement on `dev` under a measurement plan committed before the run (`evals/plans/2026-10-28-service-assessment-v1-v2-dev.md`): V1 and V2, local (about 2 h each) and cloud (about $0.05 each), each against a re-run of the incumbent. Pick one. `holdout` is not read in this feature: holdout-1 is read in follow-up feature 4 (`odd/tasks/vision-revision.md`), next to Config 0. Route: inline.
- [ ] T5 A descriptive live local round, `verify-agent.sh --mode decision --route local`: it is reported, not a pass or fail gate. It needs `infra/compose.yaml` to pass the chosen variant's settings to the agent container. Route: inline (lab, about 70 minutes).

## Delivery

The forecast is about 900 authored changed lines (T1 ~400, T2 ~400, T3 ~100), over the 400-line budget. Strategy: `feature-branch-chain` on one branch, `feat/service-assessment`, with one work-unit commit per code task. The branch starts from `feat/decision-instrument` at `8de4a5a`. Once that feature's last tasks are committed after T4, it is rebased onto that branch's tip: local and unpushed.

T1 alone came to about 1,870 authored lines, so it is two work-unit commits, each reviewed on its own: `db5f736` and `4556024`. After the rebase of 2026-10-25 they are `58969bb` and `12757e7`.

## Progress

- 2026-10-24 21:45: branch `feat/service-assessment`, from `feat/decision-instrument` (`8de4a5a`), while T4 of that feature records. Its settings default to today's behavior, so the decision-instrument measurements (M0) are not affected.

- 2026-10-25: T1 done (route: delegated writer; trigger: about 20 files across the dossier package, the loop, the replay driver, two scripts, the spec and the docs). It came to about 1,870 authored lines, so it is two work-unit commits:
  - (a) `db5f736`: `CallGraph` holds the services leaf first, the edges, `calls`, `calledBy` and the owner of each table and event source. `ServiceSheets` renders the same `Dossier` and returns the owner of every numbered line. Goldens cover f3, f1 and healthy. `DossierRenderer` takes its edges from `CallGraph`, and its output is unchanged.
  - (b) `4556024`: `kintsugi.agent.decision.view` (`KINTSUGI_AGENT_DECISION_VIEW`) is `tables` by default, today's dossier byte for byte, or `sheets`; it is applied in decision mode only. Replays take `--view` and record it in `summary.tsv` and `aggregate.tsv`. The spec gains the sheets rendering and the view.
  - Sizes, sheets against tables, by the tests' estimate of (characters + 2) / 3:
    - F3: 1,595 against 1,817 tokens;
    - healthy: 1,300 against 1,690;
    - stress: 5,412 against 5,463.
    - The tests assert that the sheets are never larger.
  - Choices made while writing:
    - An outbound line names only the callee, not the method and uri. Restating the edge took the stress sheets to 5,512 tokens, over the 5,500 ceiling. The callee's inbound line still names the uri.
    - The dossier introduction and `Prompts.java` are unchanged. Header lines D6-D8 explain the sheets' layout, so the two views differ only in the rendering.
    - The sheets leave out the sample order ids, the latency point counts, the previous-window max and the `/actuator/*` rows.
    - An event source's total line stays in its sheet even when its incident count is 0. Only rows move to the last section.
    - The view's value is checked at start in every mode, so a typo fails the start. It is applied in decision mode only.
  - RED: the new tests stopped at `testCompile`, with 100 "cannot find symbol" errors for `CallGraph`, `ServiceSheets`, `DossierView` and `Edge`. GREEN: the hand-written F3 golden matched at the first run, and the full suite passes with 514 tests. The writer counted 515, including its temporary golden-dump test, which it then deleted.
  - Parent checks:
    - (a) alone, with (b) stashed, passes the full suite.
    - With (b), the full suite passes with 514 tests and 0 failures, and `test-lib.sh` with 141 cases. (The count first read 515, because `target/surefire-reports` still held the deleted dump test's report.)
    - After a javadoc rewrap, the two touched loop test classes pass again.
  - Still open, in T1r: acceptance criterion 1's golden from a clean decision recording, which waits for the T4 import.
  - For T5: `infra/compose.yaml` passes only `KINTSUGI_AGENT_MODE` and `KINTSUGI_AGENT_ROUTE` to the agent container, so the live agent writes the tables.
- T1 reviews (consent granted), both approved and acknowledged. All findings go to T1r.
  - (a) `db5f736`, base `0f1398f`, so it includes the two docs commits: one lens (medium risk), 11 files, 1,535 lines (lineage `review-43b85ca6617e4ab0`).
    - WARNING R3-unknown-owner-throws: `CallGraph.ownerOfTable` and `ownerOfSource` throw for a name outside their maps. So one unknown table or event source fails the whole rendering, while an unknown service's traffic goes to "Other rows". No test pins it.
    - SUGGESTION R3-generic-outbound-untested: the outbound block for a client row on no edge has no test and no golden.
  - (b) `4556024`, base `db5f736`: four lenses (high risk, because of the shell scripts), 12 files, 488 lines (lineage `review-e8a9d3f0054320be`). R1 found nothing.
    - WARNING R2-sample-adjacent-string-params: `Sample` now has three adjacent `String` components (`split`, `view`, `recording`), so a swap compiles. Their order also differs from the TSV's, where `recording` comes before `view`.
    - SUGGESTION R4-view-validated-in-every-mode: a stray or empty `KINTSUGI_AGENT_DECISION_VIEW` stops an explorer or dossier start, where the setting does nothing. Failing fast is the spec's choice.
    - SUGGESTION R2-decision-only-view-rule-duplicated: `ReplayDriver` re-derives "only decision mode reads the view" with its own ternary, as `AgentLoop` does.
    - SUGGESTION R2-default-view-literal-repeated: the default `tables` is a literal in `LoopConfig`, `ReplayRunTest`, `application.yaml` and `replay-agent.sh`.
    - SUGGESTION R3-null-view: the new `AgentLoop` constructor accepts a null view.
    - SUGGESTION R3-view-propagation: no test shows that `-Dkintsugi.agent.decision.view` reaches the forked `ReplayRunTest` JVM. `replay.split` and `replay.repeats` already rely on the same mechanism, and `summary.tsv` records the view the JVM used.

- 2026-10-25, gate 1, the human's decision after M0: Part B goes on now, and `qwen3:8b` runs later (decision-instrument T5).
  - M0 on `dev`, today's prompt, tables: cloud F1 4/5, F2 5/5, F3 5/5; local `granite4.1:8b` F1 0/5, F2 5/5, F3 0/5.
  - The local misses are hops along the call graph. On F1 it names payment-service, one hop early. On F3 it names payment-service too: one hop too far, the callee that misconfigured order-service cannot reach.
- 2026-10-25: rebased onto `feat/decision-instrument` (`2ad9c6b`), which brings T4's 30 recordings, T2r2 and M0. (a) is now `58969bb` and (b) `12757e7`.
  - The reviews of (a) and (b) were made on their old identities. The commits' own changes are unchanged.
  - One conflict, in `test-lib.sh`'s context, was resolved by keeping both sides: (b)'s two `--view` cases and T2r2's `driver_refusal`.
  - After the rebase, `test-lib.sh` passes 147 cases and the full suite 514 tests, with 0 failures.

- 2026-10-25: T1r done (route: delegated writer; trigger: 18 files across the dossier package, the loop, the replay driver, two scripts, the spec and the docs). It is two work-unit commits:
  - (1) `0c9f4d1`, the dossier.
    - `CallGraph.ownerOfTable` and `ownerOfSource` return an `Optional`. A table or event source with no owner is written under the header-owned "Other rows" instead of failing the rendering.
    - A guard test fails the build when a table or source the gatherer reads has no owner.
    - New tests cover the outbound block for a call on no edge.
    - A golden renders the sheets of F1's first `dev` decision recording (`F1/verify-agent-20261024T162555Z-r1-F1.json`), gathered as a replay gathers it. All 33 calls are recorded, and the sheets take 1,538 tokens against 2,043 for its tables. This meets acceptance criterion 1.
  - (2) `2e694e1`, the setting.
    - `AgentMode.viewFrom` is the one rule of which mode applies the view; `AgentLoop` and the replay driver both use it.
    - `Sample`'s view is an `Optional<DossierView>`, in the TSV's order: split, recording, view.
    - `DossierView.DEFAULT` serves both `@Value` bindings, and `test-lib.sh` checks it against `replay-agent.sh`'s literal.
    - A null view is refused at construction.
    - The spec, `LoopConfig` and `setup.md` state that any other value, an empty one included, stops the start in every mode. For R4 this was decided: no behavior change.
    - R3-view-propagation: no change, as decided. The view will be checked in `summary.tsv` at the first `--view sheets` measurement.
  - What the F1 golden shows, in the writer's reading:
    - payment-service's sheet has no failure: 12 inbound `201 SUCCESS`.
    - The 6 timeouts appear only in order-service's outbound lines, with a WARN naming `payment-service:8081`.
    - The payments table holds 12 AUTHORIZED, while the orders table holds 6 PAID and 6 PAYMENT_PENDING.
    - gateway-mock's only signal is its latency, 1.177 s mean against 0.002 s before. payment-service's latency is larger, because it contains gateway-mock's.
  - RED:
    - an unknown table failed the rendering (`IllegalArgumentException: no lab service owns the table "stock"`);
    - the missing golden failed with a NullPointerException on the resource;
    - the null view raised nothing.
  - GREEN after each change. The off-edge test was checked by breaking the block on purpose, and the default check by setting `DEFAULT` to `sheets`: the new case failed.
  - Results: `test-lib.sh` 148 cases; full suite 521 tests, 0 failures. Parent checks: unit 1 alone passes with 147 cases and 518 tests; both units pass with 148 and 521.
  - About 550 authored lines plus the 67-line golden, split as about 424 and 193.
- T1r reviews (consent granted), both approved and acknowledged:
  - (1) `0c9f4d1`, base `12757e7`, so with the two docs commits: one lens (medium risk), 7 files, 474 lines (lineage `review-7c8f40077c099c14`). SUGGESTION R3-unowned-failure-path-untested: two cases have no test, an unowned table or source whose window failed, and off-graph traffic mixed with unowned tables in "Other rows".
  - (2) `2e694e1`, base `0c9f4d1`: four lenses (high risk, the scripts), 13 files, 193 lines (lineage `review-c0203fd189dff840`). R1, R3 and R4 found nothing. SUGGESTION R2-null-view-message-duplicated: two tests repeat `AgentLoop.NULL_VIEW` as a literal.
  - Both suggestions are follow-ups; neither blocks T2.

- 2026-10-25: T2 done (route: delegated writer; trigger: 11 files across the loop package, its tests and the spec). A first writer had left it uncommitted and not compiling: the schema, the prompt task, the setting, the wiring and most tests were written, while `Run.sheets` was missing, Citation was an empty stub, Coverage reported no missing service, and One origin, Graph and Answer did not exist. It is two work-unit commits:
  - (1) `a1f94c7`, the check: `ReportCheck.checkAssessed` checks the schema, then Coverage, Citation, One origin, Graph and Answer, each with an exact, fault-agnostic error, and derives the evidence from the ORIGIN and AFFECTED lines as dossier items quoted by code. `Schemas.ASSESSED_REPORT` lists `assessments` first, the services leaf first; `ModelRouteTest` checks the order on both routes. About 605 authored lines.
  - (2) `8e15a90`, the wiring: `DecisionReport` and `kintsugi.agent.decision.report` (`KINTSUGI_AGENT_DECISION_REPORT`), `plain` by default, today's report call byte for byte, or `assessed`. `AgentMode.reportFrom` is the one rule of which mode applies it. The assessed task in `Prompts`, the loop's retry and storage, `AssessedReportRequestTest` (the order in the HTTP body Ollama receives), and the spec: the setting under "Decision mode" and a new requirement, "The assessed report". About 791 authored lines.
  - Choices made while writing:
    - Coverage, the schema and Citation come first. One origin, Graph and Answer read every role, so they run only once all four services are assessed within the schema; otherwise a missing callee would raise false Graph errors.
    - One origin with no ORIGIN asks for every service UNAFFECTED and both causes UNKNOWN, as the prompt says, not only `rootCauseService`.
    - Every error that holds is listed. An AFFECTED service with no ORIGIN can break One origin and Graph at once, and the retry gets both.
    - Answer means a valid assessed report never names `postgres`, which has no sheet. No fault in the catalog has postgres as its cause.
    - `assessed` with `tables` stops a decision start, since the checks read the sheets' line owners. A gathering that gave no dossier falls back to the plain report, which is valid only with both causes UNKNOWN.
    - The stored report writes `assessments` after `model`, then the plain report's fields, with the derived evidence in the evidence's place.
  - RED: before the fix the tree did not compile (`Run.sheets`). Then Coverage and Citation failed (2 tests), and the five new rule tests failed (6 failures and 1 error). GREEN after the rules. The no-origin test now expects the Graph error as well, since payment-service's only callee is UNAFFECTED there.
  - Results: unit (1) alone, with (2) stashed, passes the full suite with 535 tests. With both, `./mvnw test` passes 548 tests with 0 failures.
  - Not done, needed before T4's V2: replays cannot ask for the assessed report yet. `ReplayDriver` builds its loops with the plain report, and `replay-agent.sh` has no `--report`. This needs `ReplayDriver`, `ReplayRunTest`, `replay-agent.sh` and `test-lib.sh`, as `--view` did in T1 (b); the scripts were outside this task's edit surfaces.
- T2 reviews (consent granted), both approved and acknowledged:
  - (1) `a1f94c7`, base `ea73507`, reviewed in a detached worktree: one lens (medium risk), 5 files, 605 lines (lineage `review-9ce19c09f0f80d3c`). WARNING R3-quote-orElseThrow: evidence derivation calls `DossierRenderer.line(...).orElseThrow()` after Citation checked only `sheets.owner(line)`, so an owner with no matching line in the text would throw instead of returning an invalid result for the retry. SUGGESTION R3-line-edge-tests: a non-integral number, a numeric string and a value outside the int range have no narrow test.
  - (2) `8e15a90`, base `a1f94c7`, so with the docs commit: one lens (medium risk), 11 files, 809 lines (lineage `review-548facc4d0bc935c`). No findings.
  - Both findings are follow-ups for a T2r; neither blocks the replay `--report` work.

- 2026-10-25: re-scoped by the vision revision (`odd/tasks/vision-revision.md`).
  - The M2 bar of at least 4 of 5 per fault is dropped, and the gates are mapped to the revised vision (see "The M2 bar and its gates").
  - New T2r: the replay `--report` flag and the T2 WARNING.
  - T3: ADR-014 without the bar, plus the stop rule. T4: V1 and V2 on `dev` under a committed measurement plan, with no holdout read. T5: a descriptive live round.

- 2026-10-28: T2r and T3 done, on branch `feat/service-assessment-close` from `1d10555` (route: delegated writer; trigger: 2+ non-trivial files across the replay driver, two scripts, the scorer, `ReportCheck`, the spec and two ADRs). Three work-unit commits:
  - (a) `6542239`, the replay `--report`. About 303 authored lines (229 added, 74 removed).
    - `replay-agent.sh --report plain|assessed` always passes `-Dkintsugi.agent.decision.report` to the forked JVM, as `--view` does. Explorer and dossier mode refuse it.
    - `--report assessed` without `--view sheets` exits 2 before Maven with `--report assessed needs --view sheets, got tables: ...`. `ReplayDriver` also refuses the pair when it is built, before any sample, with the loop's own error.
    - `ReplayRunTest` binds the report as the application does, `DecisionReport.DEFAULT`, and `refuseIgnoredProperties` refuses it outside decision mode.
    - `Sample` gains `Optional<DecisionReport> report` after the view, from `AgentMode.reportFrom`. `summary.tsv` and `aggregate.tsv` gain a `report` column after `view`, and the aggregate groups by it.
    - `test-lib.sh` gains 6 cases: the dossier-mode refusal, an unknown report, assessed with the tables, the default literal against `DecisionReport.DEFAULT`, and the assessed run's count line.
    - The scorer reads the column: `Row.report`, `-` when the column is missing. With the column, the default config name ends with the report (`cloud-decision-sheets-assessed`), so V1 and V2 differ. A file without the column keeps its name, so M0's result is unchanged.
    - Spec: "Replay of recorded tool outputs" (the property, the columns, a new scenario) and "Decision mode".
  - (b) `f1bb20a`, R3-quote-orElseThrow. About 83 authored lines.
    - Citation now also needs the cited line's text in the sheets, and keeps it as the derived evidence's quote, so the derivation has no `orElseThrow`.
    - An owned line with no text gives `assessments.<service>.line <N> is a line of <service> whose text is not in the sheets; cite another line of its own sheet or of its part of "Seen only in the previous window"`, an invalid result for the one retry.
    - R3-line-edge-tests: `3.5`, `"3"` and `2147483648` each give `assessments.gateway-mock.line must be an integer of at least 1`. This is a pin test, and it passed at once.
    - Spec: the Citation row and two scenarios.
  - (c) `4d68ce0`, ADR-014 and the spec. About 91 authored lines.
    - ADR-014 amends ADR-011's "facts only" rule for the sheets' arrangement and the assessed report's roles. It sets no M2 bar and points to vision §7's check. It explains why the 4-of-5 bar was dropped: P(at least 4 of 5) = 0.737 per fault for a truly 80%-accurate model, and 0.737³ ≈ 0.40 for all three, so it fails about 60% of the time.
    - It records the stop rule of vision §5, and the open question of the Graph rule's retry against vision §3 (follow-up 13 decides).
    - ADR-011's status says "Amended by ADR-014". The spec states the sheets' facts-only rule with ADR-014's amendment, and the open question under "The assessed report".
  - RED:
    - (a): `testCompile` failed: no `ReplayDriver` constructor with a `DecisionReport`, `Sample` had no `report`, and the 8-column aggregate. `test-lib.sh` against the old script stopped at `Unknown option: --report`. The two new scorer tests failed.
    - (b): `anOwnedLineWhoseTextIsNotInTheSheetsIsAnInvalidReportNotAFailure` failed with `NoSuchElementException: No value present` at the derivation.
  - GREEN:
    - (a): `ReplayDriverTest` 22 tests; `test-lib.sh` 154 cases; scorer 57 tests; the full suite 550 tests.
    - (b): `ReportCheckTest` 61 tests; the full suite 552 tests, 0 failures.
  - Not changed: `docs/setup.md` names `--view`, not `--report`. It was outside this task's edit surfaces.
  - The T4 measurement plan is committed with this entry: `evals/plans/2026-10-28-service-assessment-v1-v2-dev.md`.
- T2r and T3 reviews (consent granted), all four approved and acknowledged:
  - (a) `6542239`, lineage `review-f94b2c0842476c7e`; (b) `f1bb20a`, lineage `review-6e662fef562afb8c`; (c) `4d68ce0`, lineage `review-a029d6f78aeaf2d4`; the docs commit `6d6984b`, lineage `review-7d60958d4939ff25`.
  - Fixed in one follow-up commit (see the next entry):
    - WARNING R4-assessed-check-ignores-mode / R3-mode-blind-checkview: `ReplayDriver` checked the assessed report against the view in every mode, at construction, so a dossier or explorer driver with `assessed` and `tables` was refused although neither mode applies the report.
    - SUGGESTION R2-spec-error-reference: the replay requirement and its scenario named the same error two ways.
    - SUGGESTION R3-001: ADR-014's status line read as if the ADR itself were re-scoped on 2026-10-25.
    - SUGGESTION R2-sample-record-long-line: the `Sample` record's parameters had one line over the width of their neighbours.
  - Recorded as follow-ups, not fixed:
    - WARNING R2-config-name-asymmetry, with the R4 and R3 scorer-naming SUGGESTIONs: the scorer's default config name gains `-<report>` only when the TSV has the column, so a tables+plain run is `cloud-decision-tables-plain` today and `cloud-decision-tables` in M0. T4 is not affected: its plan labels every input with `--label`.
    - SUGGESTION R3-quote-null-fallback: the derived evidence reads its quote from the Citation map without a fallback; Citation fills it for every valid assessment, so a missing entry cannot reach the derivation today.
- 2026-10-28: the review follow-ups (route: delegated writer; trigger: the review's four items across the replay driver, its test, the spec and ADR-014).
  - `ReplayDriver` no longer checks the pair at construction. `checkReport(mode)` applies `AgentMode.reportFrom` and `viewFrom`, the one rule of which mode applies them, before a decision run's first model call (`decisionSamples`, `decisionSample`). A dossier or explorer sample ignores the pair, as the loop does.
  - The spec names the error once, as the error of the scenario "The assessed report needs the sheets" ("Decision mode"), in the requirement and in the replay scenario, which now states the decision-only refusal.
  - ADR-014's status says the feature's plan was re-scoped on 2026-10-25 by the vision revision; the ADR's date stays 2026-10-28.
  - The `Sample` record's parameters are wrapped like their neighbours.
  - RED: `theAssessedReportWithTheTablesIsRefusedBeforeAnyDecisionSample` and `explorerAndDossierSamplesIgnoreTheAssessedReportWithTheTables` both failed with the `IllegalArgumentException` thrown when the driver was built. GREEN: `ReplayDriverTest` 23 tests; the full suite 553 tests, 0 failures; `test-lib.sh` 154 cases; the scorer 57 tests.
- 2026-10-29: T4, the cloud route (route: inline). The three cloud replays ran at `b30ae74` on 2026-10-29, 15 samples each, and are scored in `evals/results/2026-10-28-service-assessment-v1-v2-dev/cloud/`.
  - D-M0 (tables+plain) 14/15; D-V1 (sheets+plain) 15/15; D-V2 (sheets+assessed) 9/15, coverage 87%.
  - Per-fault nets against D-M0, case by case from `inputs/*-summary.tsv`:
    - D-V1: F1 +1, F2 0, F3 0, pooled +1. No fault class loses a case, so V1 meets the cloud clause of the plan's eligibility rule.
    - D-V2: F1 -2, F2 0, F3 -3, pooled -5. F1 and F3 each lose more than 1 case, so V2 is not eligible, whatever the local result.
  - D-V2's six misses: four `INVALID_REPORT` (F1 r3, r5, r9; F3 r7) and two `UNKNOWN|UNKNOWN` abstentions (F3 r1, r3).
  - Paired (descriptive): D-V1 - D-M0 McNemar p = 1, +7 points, bootstrap [+0, +20]; D-V2 - D-M0 McNemar p = 0.062 (Holm 0.12), -33 points, bootstrap [-60, -13].
  - The first local incumbent run (`replay-20261029T011031Z-local`) stopped at 14 of 15 samples with no process left, so it is discarded. The three local runs are rerun from the start.

## Next step

T4, the local route: the three local replays (tables+plain, sheets+plain, sheets+assessed, about 2 h each), then score them into `.../local`, run the rerun check against M0, and apply the plan's decision rule. Only V1 can still be kept. `holdout` is not read.
