# topology-data

## Objective

Move the lab topology the agent knows (services, call edges, data owners, datastore) out of Java constants into one data file loaded at startup. The lab file must reproduce today's behavior byte for byte. This is vision M2's "Topology as data" (follow-up 5 in `odd/tasks/vision-revision.md`).

## Problem

The agent hard-codes the lab's topology in several places:

- **`dossier/CallGraph.java`**: a static class with `SERVICES` (leaf first), `EDGES`, `TABLE_OWNERS`, `SOURCE_OWNERS`, `calls`, `calledBy` and the owner lookups. Its callers are `ServiceSheets`, `DossierRenderer`, `ReportCheck` (Coverage and Graph), `RulesDiagnosis` (R-REACH, R-DOWN, the entry services, tie order) and `Schemas` (assessments).
- **`DossierGatherer.java:48-55`**: `SERVICES` in call-path order, `STATUS_TABLES` and `EVENT_SOURCES`.
- **`loop/Causes.java:15`**: the report's service enum (the services, `postgres`, `UNKNOWN`).
- **`loop/Prompts.java:26-29`**: the system message names the components and the call path in prose (pinned by the agent spec at `:1274`).

On RCAEval the systems differ (other services, other edges). The rules cannot propose anything there, and the sheets and checks would describe the wrong system. ADR-019 makes H − 0 on RCAEval wait for this feature.

## Why

With topology as data, the converter can write one topology file per RCAEval system, and the same agent code (rules, sheets, checks, prompt) runs on it. This keeps the leakage principle honest: Config 0 and the agent read the same topology file, so neither gets a private copy of the lab's structure.

## Scope

- In: the agent module only.
  - A topology file format and loader, plus the lab file.
  - The call sites above read the loaded topology instead of constants.
  - The derived lists (gather order, cause enum, prompt text).
  - The agent spec.
- Out:
  - `mcp-tools` (`ToolInputs.SERVICES`, tool descriptions, `LogQueries`), `knowledge` (`RunbookFiles.SERVICES`), `infra/` scripts, Prometheus, compose and the lab services. They operate the live lab, not the offline track.
  - The RCAEval topology files, which the converter feature writes.
  - The fault categories, which are a report vocabulary, not topology.

## Constraints

- **Byte-identical lab behavior.** Every dossier and sheet golden, the system message, the JSON schemas and Config 0's verdicts and candidates stay identical. Config 0 stays frozen (ADR-016).
- **No new library.** The loader uses the agent's existing Jackson.
- **Fail at startup.** A file that names an unknown service, has a cycle, or has an owner outside the services fails when it loads, never later in a run.

## Design

- **File:** `agent/src/main/resources/topology/lab.json`, read through the property `kintsugi.agent.topology` (a Spring resource location, default `classpath:topology/lab.json`).
  - `services`: the lab's names in display order (`gateway-mock`, `payment-service`, `order-service`, `inventory-service`).
  - `datastores`: `["postgres"]`.
  - `edges`: `{caller, target, method, uri}` in today's `EDGES` order. A `null` caller means the external client.
  - `tableOwners`, `sourceOwners`: today's maps.
- **`Topology`:** an immutable record in `dossier/`, built by `Topology.load(...)`. It validates the data, then exposes what the code uses today:
  - `services()` in display order;
  - `leafFirst()`, ordered by depth to a leaf, with ties broken by display order: gateway-mock 0, inventory-service 0, payment-service 1, order-service 2. That equals today's `CallGraph.SERVICES`.
  - `gatherOrder()`, the reverse of `leafFirst()`. That equals today's `DossierGatherer.SERVICES`.
  - `edges()`, `calls()`, `calledBy()`, `ownerOfTable()`, `ownerOfSource()`, `statusTables()` and `eventSources()`.
  - `causes()`: the services, then the datastores, then `UNKNOWN`. That equals today's `Causes.SERVICES`.
- **Wiring:** one `Topology` bean. Today's static call sites take it as a constructor or method argument, so `CallGraph`'s static constants go away. Tests build the lab topology from the same file.
- **Prompt:** the system message is rendered from the topology and must equal today's text byte for byte. A test pins that.
- **Why derive the orders rather than list them in the file:** an RCAEval topology file then needs only the names and edges. The derivation rule is the one documented property today's constants already follow (leaf first). The "depth, then display order" rule is tested on the lab file.

## Tasks

- [x] T1 This document and the design. Route: inline (docs). Human confirmed the design on 2026-10-31. Passive docs, structural readback only.
- [x] T2 `Topology`, the lab file and the loader, tests first: the loaded lab topology equals today's `CallGraph`, `DossierGatherer` and `Causes` constants and orders, and the validation failures (unknown name, cycle, foreign owner, missing file). No call site changes yet. Route: delegated writer.
- [x] T3 The call sites read a `Topology`: `ServiceSheets`, `DossierRenderer`, `ReportCheck`, `RulesDiagnosis`, `Schemas` and the Spring wiring. `CallGraph`'s constants are removed. All goldens stay byte-identical and the Config 0 tests stay green. Agent spec: the sheets and Coverage/Graph requirements say the graph is loaded from data. Route: delegated writer.
- [ ] T4 `DossierGatherer`'s lists, `Causes.SERVICES` and the `Prompts` system message derive from the topology, tests first (byte-identical prompt and schema). Agent spec: the system-message requirement. Route: delegated writer.
- [ ] T5 Closing check: the Config 0 replay over dev and holdout gives the same verdicts and candidates as before, then STATE, the journal and the backup. Route: inline commands.

## Acceptance criteria

- AC1: the lab dossier and sheet goldens, the system message and the JSON schemas are byte-identical (existing golden tests plus new pins).
- AC2: Config 0's verdicts and the rules' candidates are unchanged on the golden dossiers and on the dev and holdout recordings.
- AC3: no topology constant is left in `agent/src/main` outside `topology/lab.json` (an `rg` check over the service names, with the remaining hits listed and justified).
- AC4: an invalid topology file fails at load with a message naming the problem (tests).

## Checks

- `cd agent && ./mvnw -q test` at every task (it excludes the `live` and `replay` tags).
- The Config 0 replay on dev and holdout at T5.

## Delivery

- **Forecast:** about 800 authored changed lines across T2–T4, above the 400-line budget.
- **Strategy:** `feature-branch-chain` (the standing preference). Slices: `feat/topology-data` (T1–T2), `feat/topology-data-01-call-sites` (T3), `feat/topology-data-02-derived` (T4–T5).
- **Base:** `feat/hybrid` at `e8c4731`.

## Progress

- 2026-10-31: exploration (one read-only explorer) mapped every hard-coded copy, with path:line evidence; it is summarized under Problem. The branch `feat/topology-data` was created from `e8c4731`.
- 2026-10-31, T2 (route: delegated writer): added `agent/src/main/resources/topology/lab.json`, the record `dossier/Topology` with `Topology.load(Resource)` and `Topology.load(InputStream, origin)` (Jackson's `JsonMapper.shared()`), and the tests `dossier/TopologyTest` (14) and `loop/CausesTopologyTest` (1, in `loop` because `Causes.SERVICES` is package-private). `Topology` reuses `CallGraph.Edge`; `CallGraph` and every call site are unchanged.
  - Validation, in the record's constructor so every instance is checked: no services; a duplicate name across services and datastores; an edge caller or target that is not a service (a datastore target fails too); a table or source owner that is not a service; a cycle (the message names the path, for example `cycle: a -> b -> a`). `load` also fails on a missing file, malformed JSON, a missing field or a non-string name. Every failure is an `IllegalArgumentException` whose message names the file and the culprit.
  - Order rule for `statusTables()` and `eventSources()`: the key order of `tableOwners` and `sourceOwners` in the file (Jackson's object nodes keep it; the record copies into a `LinkedHashMap`). The lab file lists them as `orders, payments` and `order, payment, reservation`, today's `DossierGatherer` lists.
  - RED: `./mvnw -q test -Dtest=TopologyTest` failed to compile (`Topology` missing). GREEN: `TopologyTest` 14 run, 0 failures; `CausesTopologyTest` 1 run, 0 failures. Full suite `./mvnw -q test`: 626 run, 0 failures, 0 errors, 0 skipped. The parent re-ran both topology tests: green.
  - Commit `124123d`. Review of `e8c4731..124123d` (T1–T2): medium, `slice_budget_reached`; one lens (reliability), approved and acknowledged (`review-475cf63c9198f0e0`). Five non-blocking findings, whose capture gave only id and location: WARNINGs R3-001 (`Topology.java:210-232`, the field/names/owners readers), R3-002 (`:77-78`, the edge caller), R3-003 (`:74`, `readTree`), R3-004 (`:142`, `causes()`); SUGGESTION R3-005 (`TopologyTest.java:110-113`, the missing-field test). The parent's reading, not the reviewer's text: the readers do not check a node's JSON type, an empty file and a service named `UNKNOWN` are not rejected, and only `edges` has a missing-field test. Taken into T3 as hardening tests.

- 2026-10-31, T3 (route: delegated writer; trigger: 2+ non-trivial files, about 25 files touched), on `feat/topology-data-01-call-sites`:
  - Wiring: one `Topology` bean in `config/TopologyConfig`, loaded from `kintsugi.agent.topology` (a Spring `Resource`, environment variable `KINTSUGI_AGENT_TOPOLOGY`), default `Topology.DEFAULT_LOCATION` = `classpath:topology/lab.json`, written in `application.yaml`. `LoopConfig.agentLoop` takes the bean and passes it to `AgentLoop`, whose four constructors now take a `Topology` after the clock; the loop keeps it, plus the assessed schema written once. The static entry points take it as an argument: `DossierRenderer.render(dossier, topology)`, `ServiceSheets.render(dossier, topology)`, `DossierView.render(dossier, topology)`, `RulesDiagnosis.diagnose/candidates(dossier, topology)`, `ReportCheck.checkAssessed(report, sheets, topology)`, and `Schemas.ASSESSED_REPORT` became `Schemas.assessedReport(topology)`. `RulesDiagnosis`' tie comparator and `order` became instance members over `topology.leafFirst()`. `ReplayDriver` (test code) uses the lab topology.
  - `CallGraph` is gone: `EXTERNAL_CLIENT` and `Edge` moved into `Topology` (`Topology.EXTERNAL_CLIENT`, `Topology.Edge`), and `Topology` gained `PROPERTY` and `DEFAULT_LOCATION`. Every former `CallGraph.SERVICES` reader uses `leafFirst()` (the `others` filter of the sheets uses `services()`, the same set).
  - Tests: the fixture `dossier/LabTopology.LAB` loads `DEFAULT_LOCATION` once; tests pass it where they called the static functions. `CallGraphTest` is deleted, its assertions moved onto `Topology` in `TopologyTest` with literal expected values (edges, leaf-first order and its property, calls/calledBy, owners, the gatherer's tables and sources all owned). New `config/TopologyConfigTest` (4): the default is the lab file, the property names the file, a missing file and a cyclic file each fail the context start naming the file.
  - T2 hardening (tests first): `TopologyTest` gained a missing-field test per top-level field, a wrong-JSON-type test for each of the five fields, an edge that is not an object, an edge caller that is neither null nor a string (a left-out caller now fails too: the external client is an explicit null), an empty file, a root that is not an object, and a service or datastore named `UNKNOWN`. RED: `./mvnw -q test -Dtest=TopologyTest` 29 run, 11 failures (the five missing-field cases already passed). GREEN after the loader checks each field's JSON type and the record rejects `UNKNOWN`: 29 run, 0 failures; 33 after the moved `CallGraphTest` assertions.
  - Full suite `./mvnw -q test`: 642 run, 0 failures, 0 errors, 0 skipped. The pre-T3 baseline, re-run from `HEAD` (`023b747`) in a scratch copy, is 624, not T2's recorded 626: 624 − 5 (`CallGraphTest`) + 19 (`TopologyTest`) + 4 (`TopologyConfigTest`) = 642. No golden file changed; `DossierRendererTest`, `ServiceSheetsTest`, `ReportCheckTest`, `AgentLoopDecisionModeTest`, `AgentLoopDossierModeTest` and `RulesDiagnosisTest` pass with their expected values unchanged.
  - Agent spec: the sheets requirement says the graph comes from the `Topology` loaded from `kintsugi.agent.topology` and checked at load; Config 0, Config H, the sheets' facts-only note, the "Other rows" scenario and the assessed report's rules name the topology instead of `CallGraph`; new scenario "An invalid topology file stops the start (topology-data T3)".
  - AC3 check, `rg -n '"(gateway-mock|payment-service|order-service|inventory-service|postgres)"' agent/src/main`: `topology/lab.json` (the data itself); `DossierGatherer.java:48-49` `SERVICES` (T4 derives it from `gatherOrder()`); `loop/Causes.java:15-16` `SERVICES` (T4 derives it from `causes()`). `Prompts`' system message names the services in prose, unquoted, so the pattern misses it; it is T4's too.
  - Size: about 1,100 changed lines (tracked +521 −495, plus 102 in new files), above the 400-line heuristic. Most is mechanical: the deleted `CallGraph` and `CallGraphTest`, and a `LAB` argument at about 150 test call sites; the authored logic is the loader hardening, the bean, and the signatures.

  - Size: about 1,100 changed lines, over the 400 heuristic; mostly mechanical (deleting `CallGraph` and `CallGraphTest`, passing `LAB` at about 150 test call sites). Kept as one slice: splitting would leave a branch where half the call sites read constants that no longer exist. The pre-T3 baseline is 624 tests (the writer re-ran `023b747`); the 626 recorded for T2 was a miscount. The parent re-ran `RulesDiagnosisTest`, `DossierRendererTest`, `ServiceSheetsTest`, `TopologyTest` and `TopologyConfigTest`: green; no file under `agent/src/test/resources` changed.

  - Commit `369568d`. Review of `023b747..369568d`: medium, `slice_budget_reached`; one lens (reliability), approved and acknowledged. Six non-blocking findings; the capture gave only id and location:
    - WARNING R3-001, `application.yaml:69`;
    - WARNING R3-002, `ServiceSheets.java:91`;
    - WARNING R3-003, `ServiceSheets.java:176`;
    - WARNING R3-004, `TopologyConfig.java:20-21`;
    - WARNING R3-005, `TopologyConfigTest.java:31-33`;
    - SUGGESTION R3-006, `Topology.java:181-184`.

    The parent's reading of those lines, not the reviewer's text:
    - the default location is written twice (the yaml and the `@Value` default);
    - the sheets silently drop logs of a service missing from the topology;
    - the property test names the lab file, which is also the default, so it does not prove the property is honored;
    - the `UNKNOWN` check is case-sensitive.

    R3-005 is taken into T4: point the property at a different file. The rest are follow-ups.

## Next step

T4: the derived lists and the system message, plus R3-005.
