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

- [ ] T1 `CallGraph` and the per-service sheets with line owners, behind `kintsugi.agent.decision.view`, with goldens. Route: delegated writer (dossier package, settings, tests).
- [ ] T2 The assessed report: schema, prompt task, code-derived evidence, consistency rules, and `kintsugi.agent.decision.report`. Route: delegated writer (loop package, tests, spec).
- [ ] T3 ADR-014, ADR-011's status, and the spec's remaining parts. Route: inline (docs).
- [ ] T4 Measurement on `dev`: V1 and V2, local (about 2 h each) and cloud (about $0.05 each). Pick one, then read `holdout` once. Route: inline.
- [ ] T5 The live local round for the M2 check. Route: inline (lab, about 70 minutes).

## Delivery

The forecast is about 900 authored changed lines (T1 ~400, T2 ~400, T3 ~100), over the 400-line budget. Strategy: `feature-branch-chain` on one branch, `feat/service-assessment`, with one work-unit commit per code task. The branch starts from `feat/decision-instrument` at `8de4a5a`. Once that feature's last tasks are committed after T4, it is rebased onto that branch's tip: local and unpushed.

## Progress

- 2026-10-24 21:45: branch `feat/service-assessment`, from `feat/decision-instrument` (`8de4a5a`), while T4 of that feature records. Its settings default to today's behavior, so the decision-instrument measurements (M0) are not affected.

## Next step

T1.
