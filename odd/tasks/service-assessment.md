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
- ADR-014, which amends ADR-011's "facts only" rule and records the M2 bar.

Out:
- the explorer and dossier modes, whose rendering and report stay as they are;
- judgments, thresholds or anomaly markers in the facts;
- rule-generated hypotheses (Part C, only if this feature misses the bar, and only with the human's decision).

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
   - Measured on `dev` only. Keep the best, delete the others before the feature closes, then read `holdout` once.

## The M2 bar and its gates

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
5. V1 and V2 are measured on `dev`, local and cloud, against M0. The best variant is kept and the others deleted; `holdout` is read once; the results are in this document.
6. Living docs: the spec (decision mode, rendering, report, checks), ADR-014, and ADR-011's status line ("amended by ADR-014").

## Tasks

- [x] T1 `CallGraph` and the per-service sheets with line owners, behind `kintsugi.agent.decision.view`, with goldens. Route: delegated writer (dossier package, settings, tests).
- [ ] T1r The T1 review findings (2 WARNINGs, 6 SUGGESTIONs), and acceptance criterion 1's golden from a clean decision recording, after decision-instrument's T4 import. Route: delegated writer.
- [ ] T2 The assessed report: schema, prompt task, code-derived evidence, consistency rules, and `kintsugi.agent.decision.report`. Route: delegated writer (loop package, tests, spec).
- [ ] T3 ADR-014, ADR-011's status, and the spec's remaining parts. Route: inline (docs).
- [ ] T4 Measurement on `dev`: V1 and V2, local (about 2 h each) and cloud (about $0.05 each). Pick one, then read `holdout` once. Route: inline.
- [ ] T5 The live local round for the M2 check. It needs `infra/compose.yaml` to pass the chosen variant's settings to the agent container. Route: inline (lab, about 70 minutes).

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

## Next step

T1r, then T2.
