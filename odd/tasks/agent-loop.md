# Feature: agent-loop

Locator: `odd/tasks/agent-loop.md` · Engram mirror: `odd/agent-loop/tasks` · Tracker branch: `feat/agent-loop` (from `feat/runbook-rag` at `aecc90c`)

## Objective

Build the first diagnosing agent. These are the last two M2 items (`docs/vision.md:155-156`):
- the loop perception → plan → action → observation, with `IncidentState`;
- a JSON report with cited evidence.

They feed the M2 check: "F1–F3 correctly diagnosed with a local model" (`docs/vision.md:157`).

## Problem and why

mcp-tools gives the evidence, and `search_runbooks` gives the knowledge, but nothing reasons over them yet. The agent must:
- turn a neutral symptom into a diagnosis a script can score against the truth in `faults`;
- cite evidence that a script can check really exists;
- run within tight budgets on a small local model;
- record every step, so each run can be replayed and measured.

Core rule: the LLM proposes, deterministic code verifies, policy authorizes.

## Scope

- In:
  - the perception REST endpoint;
  - the agent's state tables;
  - the MCP client;
  - code-built summaries of tool outputs;
  - the phased loop with a schema-validated plan;
  - the schema-validated report with evidence checks;
  - a minimal `ModelRouter` (one Ollama route);
  - compose wiring;
  - a check script running F1–F3 against the local model.
- Out:
  - Alertmanager and continuous traffic (M3);
  - policy and approvals (M3; every tool is read-only, so policy is a no-op);
  - long-term memory and Java rules (M3);
  - the Python eval harness and the A–D benchmark (M4; the check script is M2's scorer).

## Design

The human decided the taxonomy, the context strategy and the trigger on 2026-10-08. The rest was proposed by Claude after read-only research and is to be reviewed.

- **Report taxonomy (human):**
  - `rootCauseService`: `gateway-mock`, `payment-service`, `order-service`, `inventory-service`, `postgres` or `UNKNOWN`;
  - `rootCauseCategory`: `DEPENDENCY_LATENCY`, `DEPENDENCY_REJECTING`, `CONFIGURATION`, `RESOURCE_EXHAUSTION` or `UNKNOWN`.

  These are the truth's own column vocabularies (`root_cause_service`, `root_cause_category`). They are generic SRE terms, so they reveal no fault id. Scoring is a mechanical equality on both fields.
- **Report shape:** `incidentId`, `rootCauseService`, `rootCauseCategory`, `summary` (at most 500 characters), `confidence` (0–1), `evidence[]` and `stepsUsed`.
  - Each evidence item is `{toolCallId, tool, quote, why}`.
  - Code checks every `quote` against the recorded output of that `toolCallId`. A citation that does not exist is rejected.
  - Two invalid reports end the run with status `INVALID_REPORT`.
- **Context strategy (human): summaries built in code.**
  - The full tool output is stored in the step table, for citations and evals.
  - The model receives a deterministic summary: counts, the first distinct lines, compressed series, and the top runbook hits. Tools are called with small limits.
  - This is the vision's tactic, "logs summarized by code" (`docs/vision.md:117`).
  - `num-ctx` stays 8192.
- **Trigger (human): the check script.** The script injects a fault through the fault-injector, places orders the way `verify-f1.sh` does, waits, then calls `POST /incidents` with a neutral symptom.
  - The symptom never names the cause or the fault.
  - `POST /incidents {symptom, sinceMinutes}` answers 202 with an id.
  - `GET /incidents/{id}` returns the status, steps and report.
- **Loop:** driven by our own code, not by Spring AI's tool loop: `ChatModel` is called directly and each MCP `ToolCallback` is invoked by our code, one call at a time (corrected in T1; see ADR-010). Every step is counted, bounded and persisted. There are four phases, each exposing only a few tools:
  1. TRIAGE: `status_summary`, `query_metric`, `service_restarts`;
  2. INVESTIGATE: `search_logs`, `count_logs`, `recent_events`, `get_order_trace`;
  3. KNOWLEDGE: `search_runbooks`;
  4. CONCLUDE: no tools; one structured-output call produces the report.

  At the start of each phase the model writes a small schema-validated plan, `{hypotheses[], nextTools[]}`.
- **Budgets:**
  - at most 8 tool calls per incident;
  - 90 s per model call;
  - 10 minutes per incident;
  - each budget ends the run with an explicit status, never silently. Exception (amended in T1): using up the 8 tool calls moves the run to CONCLUDE, so it still produces a report that can be scored. The exhausted budget is recorded on the steps.
- **State:** an `agent` database with an owner role `agent_app`, not a superuser and with no CONNECT on `faults`. It holds:
  - `incidents`: id, signal, status, report, started_at, ended_at;
  - `incident_steps`: incident_id, step_no, phase, model, tokens, tool_name, tool_args, tool_output, tool_outcome, latency_ms, created_at.

  The database lives on the `knowledge-db` instance, matching the vision's "state, memory and KB in one DB" (`docs/vision.md:89`). The lab's postgres, which faults can break, stays out of the agent's path. **To review.**
- **Model:** `granite4.1:8b`, the ADR-003 default, behind a `ModelRouter` interface with one Ollama route. The API baseline route is deferred: ADR-003 lists it for M2, so this is **to review** with the human.
- **MCP client:** `spring-ai-starter-mcp-client`, Streamable HTTP to `mcp-tools:8085` (on the Host allow-list), SYNC.
- **Prompt injection:** the system prompt states that `untrustedText` is data. Plans and reports are schema-validated enums. Every tool is read-only, so the worst case in M2 is a wrong diagnosis.

## Acceptance criteria

- `POST /incidents` runs the loop within its budgets and stores every step, with the full tool output.
- The report validates against its schema, and every evidence quote exists in its cited tool output.
- `verify-agent.sh` injects F1, F2 and F3 in turn, generates traffic, triggers the agent and restores each fault. It records each report and scores it against the truth (service and category). It passes when all three match. Every run is logged, pass or fail.
- The agent's roles cannot reach `faults`. The agent calls the lab only through mcp-tools.
- `./mvnw test` passes in `agent`, and the existing verify scripts still pass.

## Delivery

- `feature-branch-chain` (standing preference). Tracker `feat/agent-loop`; children `feat/agent-loop-NN-<slice>`, each starting from the previous tip. The forecast is about 2,500 lines, so it is sliced by task.
- RDD: on; consent is a standing grant.

## Tasks

- [x] **T1 — ADR-010 (agent loop design) and the living spec `openspec/specs/agent/spec.md`:** endpoint, loop, budgets, state, report schema and evidence check. Branch: tracker. Route: delegated writer.
- [x] **T2 — The state store:** the `agent` database and `agent_app` role on `knowledge-db`, the schema through Flyway in `agent/`, and the incident API skeleton (`POST` and `GET`). Testcontainers. Branch `-01-state`. Route: delegated writer.
- [x] **T3 — The MCP client and tool-output summarizers,** tested against recorded tool outputs. Branch `-02-tools`. Route: delegated writer.
- [x] **T4 — The loop, planner and report:** phases, plan schema, budgets, report schema, evidence check and `ModelRouter`, tested with a scripted fake chat model. Branch `-03-loop`. Route: delegated writer.
- [ ] **T5 — Compose wiring and `verify-agent.sh` (F1–F3) run for real with `granite4.1:8b`;** record the results and docs. Branch `-04-check`. Route: delegated writer.
  - [x] **T5a** — budgets amended, warm-up, T4 review fixes (`206bf8e`, review `review-73eb7c52d4d33d38`).
  - [x] **T5b** — compose wiring, neutral traffic ids, `verify-agent.sh` (`1662569`, review `review-754658aa32761c24`).
  - [x] **T5c1 — model-call fixes (agent code, spec, ADR):** cap `num_predict` on `ACT` calls, with room for a phase's first answer, which carries the plan; put the instruction after the tool results and keep the parts that change at the end, so the model does not summarise and the cached prefix grows; abort the HTTP request when a call times out, so Ollama does not finish an abandoned call. Tests first. Route: delegated writer.
  - [x] **T5c2 — `verify-agent.sh` fixes:** read the reported cause only from `.report`; restart the agent when a wait times out; a per-fault window that excludes the previous fault's traffic; a fresh window after a 409 wait; the lab starts even when the agent cannot. Route: delegated writer (same worker as T5c1, separate commit).
  - [ ] **T5c3 — full run** of `verify-agent.sh`, results recorded. Route: parent.
  - [x] **T5c4 — a call deadline per task (the human's decision of 2026-10-14):** 240 s for `ACT` calls, 300 s for the `REPORT` call (`kintsugi.agent.model.call-timeout.act`/`.report`), each still bounded by the incident's time left and named in the timeout reason; the loop's read timeout follows the longest deadline. Tests first; spec and ADR-010 "Amended in T5c4". Route: delegated writer.
  - [x] **T5c5 — short evidence quotes and longer call deadlines (the human's decision of 2026-10-14, after the third run):** the report prompt asks for a short verbatim fragment per evidence `quote` (at most 160 characters) and `ReportCheck` rejects a longer quote with a reason the retry can act on; the substring check stays. Call deadlines become 300 s for `ACT` and 420 s for `REPORT`; check that the incident's time budget and `verify-agent.sh`'s wait still fit them. Tests first; spec and ADR-010 "Amended in T5c5". Route: delegated writer (2+ non-trivial files).
  - [x] **T5c6 — evidence check against what the model saw (the human's decision of 2026-10-14, after the fourth run):** `ReportCheck` also accepts a quote found verbatim in the cited step's summary, the text shown to the model, which is derived from the tool output deterministically. It compares with whitespace and line breaks collapsed, and decodes escapes such as `\n` first. The substring rule stays. Tests first, from the fourth run's real quotes; update the spec (the acceptance wording "exists in its cited tool output") and ADR-010 "Amended in T5c6". Route: delegated writer.
  - [x] **T5c7 — why every report blames `payment-service`:** a read-only investigation of the fourth run (prompts, summaries, tools, runbooks), returning the cause and a fix proposal for the human. Route: read-only explorer.
  - [x] **T5c8 — diagnosis-quality fixes for T5c7 causes 1–4 (the human's decision of 2026-10-14):**
    - (1) a prompt rule that for `DEPENDENCY_*` the root-cause service is the slow or refusing dependency, not its caller;
    - (2) TRIAGE hypotheses cover at least two different services on the call path, and each phase starts from a fresh plan whose kept hypotheses cite a toolCallId, instead of re-showing the old plan verbatim;
    - (3) tool and prompt text saying the lab logs failures at WARN/INFO; guidance to trace a non-terminal order found through `recent_events source=order`; `error_rate` described as 5xx only;
    - (4) the runbook summary cap raised to about 900 characters;
    - the two T5c6 review WARNINGs: a quote must not match code-authored summary text (header, aggregates, `(cut)` markers, outcome sentences), and an `AgentLoop`-level test proves the shown summary is stored.

    Tests first where deterministic; spec, ADR-010 "Amended in T5c8". Route: delegated writer.
  - [x] **T5c9 — stop wasting the tool budget (the human's decision of 2026-10-14, after the fifth run):**
    - a tool call whose arguments hold a placeholder (`<…>`) is refused without spending budget, with a reason the model can act on;
    - a call identical to an earlier one in the incident (same tool, same arguments) is not run and not counted; the model is told "already called, see toolCallId N";
    - the log tools' text says gateway-mock logs its decisions at INFO, so search it without a level filter;
    - the prompt says stuck orders are `PAYMENT_PENDING`, with `PAYMENT_UNAVAILABLE` events, not `PAYMENT_FAILED`, unless the status summary shows them.

    Tests first; spec, ADR-010 "Amended in T5c9". Route: delegated writer.
  - [x] **T5c10 — deterministic fixes from the sixth run (the human's decision of 2026-10-15):**
    - log results group lines that differ only in numbers and ids into one message template with a count, and the decisive lines are not crowded out by startup noise;
    - refused steps are not offered as citable evidence to the report;
    - the log tools' text says postgres has no logs to search;
    - `hypotheses[].why` is allowed up to 300 characters.

    Tests first; the specs, and ADR-010 "Amended in T5c10". Route: delegated writer.
  - [x] **T5c11 — deterministic fixes from the seventh run (the human's decision of 2026-10-15):**
    - an evidence item whose quote is not in its step is dropped and recorded as dropped. The report stays valid while at least one verified evidence item remains; all other report checks still invalidate it.
    - the ACT task states how many tool calls this phase still allows.

    Tests first; spec, ADR-010 "Amended in T5c11". Route: delegated writer.
  - [x] **T5c12 — no lost phases (the human's decision of 2026-10-15, after the eighth run):**
    - an `ACT` answer with no tool call, while the phase still allows calls, gets one short retry that asks for one tool call now and no plan; a second answer with no tool call ends the phase as today. The retry is one model call per phase at most.
    - the plan check drops `nextTools` entries outside the phase (and records them as dropped) instead of rejecting the plan; the plan stays valid when its other fields are valid.

    Tests first; spec, ADR-010 "Amended in T5c12". Route: delegated writer (2+ non-trivial files: `AgentLoop`, `PlanCheck`, `Prompts`, their tests, spec, ADR).

## Progress

- 2026-10-08: read-only research done.
  - Facts: the truth enum `RootCauseCategory`; there is no Alertmanager and no traffic generator; the Spring AI MCP client exposes `SyncMcpToolCallbackProvider`; `num-ctx` 8192 is too small for raw tool outputs; ADR-003's smoke test does not rank the models.
  - The human chose: the truth enum plus `UNKNOWN`, code-built summaries, and the check script as the trigger.
  - Tracker branch created.

- 2026-10-08: T1 done by a delegated writer (docs only).
  - Created ADR-010 (140 lines) and `openspec/specs/agent/spec.md` (325 lines).
  - **Corrections from the Spring AI 2.0.1 docs:**
    - `internalToolExecutionEnabled` no longer exists. Calling `ChatModel` directly never runs tools; the automatic loop lives in `ChatClient`'s `ToolCallingAdvisor`.
    - `ToolCallingManager.executeToolCalls` runs every call of a response at once, so the loop invokes each MCP `ToolCallback` itself, to check each call before it runs.
    - Structured output uses `OllamaChatOptions.outputSchema`. `validateSchema()` is not used, because its hidden retries escape our budgets.
    - ADR-005 was never written, so ADR-010 cites the vision instead.
  - **Values chosen by the writer, marked "(chosen)" in the spec, to review:**
    - tool calls per phase: 2 in TRIAGE, 4 in INVESTIGATE, 2 in KNOWLEDGE;
    - at most 16 model calls per incident;
    - a plan holds 1–3 hypotheses and 0–4 next tools;
    - a report holds 1–5 evidence items; each quote is 8–300 characters and matches the raw output or a decoded JSON string in it;
    - code caps `limit` at 20, and at 3 for `search_runbooks`;
    - one incident runs at a time, and a second `POST` gets 409;
    - on restart, an incident left `RUNNING` becomes `FAILED`.
  - **Accepted by the parent:** an exhausted tool budget leads to CONCLUDE rather than ending the run. This keeps the run scorable and records the budget explicitly.
  - Leakage grep: clean.

- 2026-10-08: T1 range reviewed (medium risk, reliability lens), approved and acknowledged (`review-9b4856cc2faa5b3f`).
  - The stale loop mechanism in this document is fixed here.
  - These spec points fold into T2 part A:
    - which budget a refused call consumes;
    - the 409 rule enforced atomically, for example by a partial unique index on `RUNNING`;
    - deterministic tie-breaks in the summaries.

- 2026-10-08: T2 done by a delegated writer on `feat/agent-loop-01-state`.
  - **Part A** (spec only): refused calls and budgets pinned down; the 409 rule enforced by a partial unique index; summary tie-breaks.
  - **Part B:**
    - the `agent` database and the `agent_app` role on `knowledge-db`. The database stays owned by the bootstrap superuser; `agent_app` gets only USAGE and CREATE on `public`, so it cannot grant CONNECT or change settings, and a test proves it;
    - a Flyway V1 with `incidents` and `incident_steps`, and CHECK constraints that tie `ended_at`, `failure_reason` and `report` to the status;
    - `POST` and `GET /incidents` on port 8086, with hand-written validation;
    - a no-op `IncidentRunner`, and the restart rule run before the web server accepts requests;
    - the demo profile runs without a web server or database.
  - **Parent:** added `AGENT_DB_PASSWORD` to the `knowledge-db` environment in compose. Without it the init stops on a fresh volume, and compose was outside the writer's surface.
  - Results:
    - `agent`: 105 tests (XML), exit 0, three runs by the writer and one by the parent;
    - mcp-tools store tests: 74, and `knowledge`: 31, exit 0 (writer).
  - RED observed: a compile failure, then config failures, then GREEN. A concurrency test runs 10 rounds of two parallel POSTs; exactly one wins each round.
  - Choices to review: port 8086; the stricter CHECK constraints; the restart reason keeps the spec's wording.
  - **The human must add** `AGENT_DB_PASSWORD=agent_app` to `infra/.env.example`. An existing store volume needs `down -v` and a re-index.

- 2026-10-08: T2 range reviewed (high risk, 4 lenses), approved and acknowledged (`review-6b85d9eefa655835`). Folded into T3 part A:
  - WARNING (R3/R4): if `runner.start(id)` throws after the `RUNNING` row is committed, the incident stays `RUNNING` and blocks every later POST. Mark it `FAILED` on a runner failure;
  - a 409 may carry no id when the running incident ends between the two queries;
  - the repository uses status string literals instead of the enum;
  - the concurrency test's barrier has no timeout.

  For T5: publish the incident API only on `127.0.0.1`, because it has no authentication and `?full=true` returns full tool outputs.

- 2026-10-08: T3 done by a delegated writer on `feat/agent-loop-02-tools`.
  - **Part A** (the T2 review fixes) passes alone: 110 tests, exit 0, run by the parent.
    - A runner failure marks the incident `FAILED` and answers 500.
    - A 409 retries the insert once; a 409 with no id is documented.
    - The repository uses the status enum.
    - The concurrency barrier has a timeout.
  - **Part B:**
    - the MCP client starts lazily (`spring.ai.mcp.client.initialized=false`), so the agent starts while mcp-tools is down. With the default it fails, reproduced;
    - calls go through `McpSyncClient.callTool`, because the Spring AI `ToolCallback` throws on error results and would lose `INVALID_PARAMETER` and `UPSTREAM_ERROR` evidence;
    - the limit caps are applied by rewriting the arguments;
    - one deterministic summarizer per tool. Copied text is written as escaped JSON string literals, so lab text cannot fake a header and quotes still match the raw output;
    - 14 fixtures recorded read-only from the live mcp-tools.
  - Results: `agent` has 172 tests (XML), exit 0, two runs by the writer and one by the parent.
  - **Process deviation:** Part B's code was written before its tests. The only real RED was the eager-initialization experiment.
  - **Leak found (fix in T5):** the verify scripts put `customerId "verify-fN"` into lab events, and the agent can read it through `recent_events` and `get_order_trace`. That names the fault. The fixtures were neutralised to `verify-run`; T5 must make every traffic generator use a neutral customer id.
  - Spec changes:
    - `service_restarts` shows the 6 newest start times plus a count of earlier ones;
    - `get_order_trace` shows each event's time;
    - the connection is named `kintsugi`, and T5 sets `KINTSUGI_MCP_URL=http://mcp-tools:8085`.
  - Size: Part B is about 1,900 lines plus 312 of fixtures. It is cohesive: eight summarizers with their tests.

- 2026-10-08: T3 range reviewed (medium risk, reliability lens), approved and acknowledged (`review-e658b633b537867f`). Folded into T4 part A:
  - WARNING: a non-object argument gives a plain-text `INVALID_PARAMETER` output, which the summarizer cannot parse;
  - suggestion: if `incidents.fail` throws inside the runner-failure catch, the `RUNNING` row stays.

- 2026-10-08: T4 done by a delegated writer on `feat/agent-loop-03-loop`.
  - **Part A** (the T3 review fixes) passes alone: 174 tests, exit 0, run by the parent.
    - Non-object arguments get mcp-tools' `INVALID_PARAMETER` envelope.
    - A failure inside the fail-in-catch is logged, and the restart sweep is the backstop.
  - **Part B** adds the `loop/` package:
    - `AgentLoop` on a single-thread executor with a queue of 2;
    - `ModelRouter` with one Ollama route, `granite4.1:8b`;
    - plan and report schemas through `outputSchema`, checked in code with one retry;
    - per-call tool gating;
    - a new step kind `ACT` (migration V2), so every model call has a row;
    - each model call has a deadline of `min(90 s, time left)`.
  - Results: `agent` has 209 tests (XML), exit 0: two runs by the writer, one by the parent. The leakage regex is clean on the prompts and schemas.
  - **RED was only a compile failure.** No behavioural RED was observed against working code. The tests were written first.
  - **Live smoke** (real `granite4.1:8b` and mcp-tools; the store on Testcontainers, since the live `knowledge-db` has no `agent` database yet):
    - **run 1, cold:** the first plan took more than 90 s, so `TIMED_OUT`;
    - **run 2, warm:** the plans were valid and two `query_metric` calls returned OK, but model calls took 42–84 s each, and an `ACT` call passed 90 s, so `TIMED_OUT`.
    - **The wiring works; the budgets do not fit this laptop's CPU.** That needs the human's decision before T5.
  - **Size exception:** Part B is about 2,470 lines (1,310 main, 1,015 tests). The writer proposed a split (B1: router, schemas, checks, prompts; B2: loop, repository and wiring). The parent committed it whole, as one cohesive behaviour; the review covers the range.

- 2026-10-08: T4 range reviewed (medium risk, reliability lens), approved and acknowledged (`review-d81f547c033875bb`). Folded into T5 part A:
  - WARNING: the global 90 s read timeout also hits the demo profile's cold first call;
  - an `Error` in `run()` skips `finish()` and leaves the incident `RUNNING`;
  - the full-queue rejection is untested;
  - a null tool name can throw in `contains`.
- 2026-10-12: **the human decided the budgets** against the measured CPU latency (42–84 s per call):
  - fewer calls: the plan merges into the first `ACT` call of each phase, so there are no separate `PLAN` calls and about 8–10 calls per run;
  - 180 s per model call;
  - 30 minutes per incident;
  - a model warm-up before each run.

  The spec and ADR-010 change in T5 part A.

- 2026-10-14: T5 part A committed on `feat/agent-loop-04-check` (`206bf8e`). Route: delegated writer (earlier session), parent commit.
  - The plan is read from the first `ACT` answer of each phase; a missing or invalid plan is recorded and the run goes on. `ModelTask` is `ACT` or `REPORT`.
  - Budgets: 180 s per model call, 30 min per incident, at most 12 model calls; a warm-up on the `ACT` route (300 s limit, no step) runs before the time budget starts.
  - T4 review fixes: the loop sets its own read timeout (web profile), the demo keeps 5 min; an `Error` in `run()` ends the incident `FAILED`; the full-queue rejection and a nameless tool call are tested.
  - ADR-010 lines that still named a separate `PLAN` call were corrected before the commit.
  - Checks: `agent` has 223 tests (surefire XML), 0 failures, exit 0 (run by an assessment worker). The leakage regex is clean on `Prompts.java` and `Schemas.java`.
- 2026-10-14: T5 part A reviewed (medium risk, `slice_budget_reached`, reliability lens), approved and acknowledged (`review-73eb7c52d4d33d38`). Non-blocking suggestions:
  - `Answers.embedded` returns null when the whole text parses as a non-object JSON value, without trying the fence or brace search;
  - the warm-up's HTTP-level timeout branch ("took longer than") has no test.
- 2026-10-14: T5 part B is started but uncommitted: the `agent` compose service (127.0.0.1:18086), `agent/Dockerfile`, neutral `checkout-xxxxxxxx` customer ids in `lib.sh` (all `verify-*.sh` follow the new signatures), and a `.gitignore` entry for `infra/scripts/out/`. Open points:
  - `verify-agent.sh` does not exist yet;
  - the live `knowledge-db` volume predates the `agent` database and role, which `01-knowledge.sh` creates only on a fresh volume;
  - compose defaults `AGENT_DB_PASSWORD` to `agent_app`, while `application.yaml` has no default. **Decided by the human (2026-10-14): keep the compose default**, like every other lab role. compose is the local lab's configuration, `application.yaml` is the app's contract and fails fast outside compose. The human adds `AGENT_DB_PASSWORD=agent_app` to the protected `infra/.env.example`.
- 2026-10-14: added `infra/scripts/reset-volume.sh` (documented in `docs/setup.md`, "Reset a volume") and used it, with the human's ok, to delete `kintsugi-lab_knowledge-data`, so the next start creates the `agent` database and role. The human asked that every repeatable fix-up operation become a script in the repo.

- 2026-10-14: T5 part B wiring done by a delegated writer (uncommitted; the real F1–F3 run is still pending, so T5 stays open). Route: delegated writer.
  - Kept the earlier writer's work: the `agent` compose service, `agent/Dockerfile`, neutral `checkout-xxxxxxxx` customer ids (`place_order` takes no argument), the `.gitignore` entry. Only a comment in compose was rewrapped.
  - `infra/scripts/verify-agent.sh`: checks Ollama serves `granite4.1:8b` and `ollama-proxy` is healthy, then per fault places 3 healthy orders, injects it, places 6 orders, waits 30 s, opens an incident with one neutral symptom ("Customers report that checkout orders are not completing as expected", 10-minute window), polls every 15 s for at most 37 min, saves the incident with `?full=true`, restores the fault and scores the report against the truth row (`COMPLETED` and service and category equal). `--only F1[,F3]` reruns a subset; `--down` as usual. Every run is logged under `infra/scripts/out/verify-agent-<UTC>/` (`run.log`, `<fault>-incident.json`, `<fault>-score.txt`, `summary.tsv`). A run that stops early writes a `not scored` line, fails, and restarts the agent to end its `RUNNING` incident.
  - New generic `lib.sh` helpers: `agent_call`, `incident_status`, `wait_incident`, `open_incident` (on a 409 it waits for the running incident and retries once), `restart_agent` (the API has no cancel; the restart sweep ends a `RUNNING` incident `FAILED`), `truth_cause`. Documented in `docs/setup.md` ("The diagnosing agent", "Check that the agent diagnoses F1, F2 and F3"), which also lists the agent's port, `AGENT_DB_PASSWORD` and the `agent` database.
  - Validated on the live lab: the fresh `knowledge-data` volume created the `agent` database and the non-superuser `agent_app`; Flyway applied V1 and V2; the agent is healthy and reaches Ollama through `ollama-proxy`; `POST /incidents` answered 202 with an id, `GET` showed `RUNNING`, a second `POST` 409 with that id; a restart ended it `FAILED`. Two interrupted `verify-agent.sh` runs (F2, F1) injected, placed neutral orders, opened the incident (warm-up 769 ms, first `ACT` call started), then restored the fault and recorded `not scored`. `verify-mcp.sh` and `verify-f1.sh` pass; new orders carry `checkout-` ids. `bash -n` and shellcheck 0.10.0 (Docker image) are clean on the changed scripts.
  - Observed, not fixed (agent code is out of this task's surface): on shutdown the loop logs `run_end_not_stored` (the store is closed before the interrupted run can record its end); the restart sweep then marks the incident `FAILED`, so nothing stays `RUNNING`.
- 2026-10-14: T5 part B wiring committed (`1662569`) and reviewed with `reset-volume.sh` (`39fb342`): high risk, four lenses, approved and acknowledged (`review-754658aa32761c24`). WARNINGs to fix in T5 part C:
  - a `wait_incident` timeout clears `current_fault` before `exit 1`, so the trap never runs `restart_agent` and the incident stays `RUNNING` (three lenses);
  - the fixed 10-minute window can include the previous fault's orders and logs when an incident ends early, so scores depend on timing;
  - after a 409 wait in `open_incident`, the retried POST keeps a window that may no longer cover the fault;
  - the agent is now part of every `start_lab`; on a pre-agent `knowledge-data` volume it exits, and every verify script fails until `reset-volume.sh` runs.
  Suggestions: a duration mismatch in the usage text, the hard-coded env list in `restart_agent`, an unreadable timeout message, an untracked window on `open_incident`, and EOF handling in `reset-volume.sh`'s prompt.
- 2026-10-14: the first full `verify-agent.sh` run started on the committed script (before the part C fixes), so its F2 and F3 scores may suffer from the window issue.
- 2026-10-14: first full `verify-agent.sh` run (`infra/scripts/out/verify-agent-20261014T133638Z/`, logs not in git): **F1, F2 and F3 all `TIMED_OUT`, no report, 0/3.**
  - Each run ended at the same point: the 4th model call, the second `ACT` in `INVESTIGATE` (after two `recent_events` results), passed 180 s. Earlier calls took 27–117 s. Each incident lasted 6–7.5 min of its 30.
  - The model put its plan inside the tool arguments (F1 step 2: `query_metric` got `{"plan": …}`, `CLIENT_ERROR`), and the plan was recorded missing.
  - **Script bug:** `verify-agent.sh:147` reads `rootCauseService`/`rootCauseCategory` with `json_field` from the whole incident body, so it picked up a hypothesis from that plan in the tool arguments and printed `reported 'payment-service|DEPENDENCY_REJECTING'` for all three, although `report` is null. The verdicts (MISS) are right; the logged "reported" values are false. Fix in part C: read only `.report`.
- 2026-10-14: diagnosis of the 4th-call timeout (read-only worker; evidence from `journalctl -u ollama` timings and one replayed request):
  - **Cause:** after two tool results, granite writes a 650–800-token Markdown summary ("**Investigation Summary** …") instead of tool calls or an empty answer. `ModelRoute.options()` sets no `num_predict`, and this CPU generates about 2.9 tokens/s, so the answer alone takes 228–287 s. Earlier `ACT` answers were 30–66 tokens. Replaying F2's 4th request with `num_predict` 512 stopped at the cap with prose and no tool calls. Likely trigger: the instruction sits in the user message before the assistant and tool messages, so the model "answers" the results.
  - **Aggravating:** the prompt cache is reused only for the first 1368 tokens (system prompt and tool definitions), because the rebuilt user state comes first and changes every call; the rest is re-read at about 13 tokens/s (70–92 s on the 4th call).
  - **Knock-on:** Ollama finishes an abandoned call after the agent's 180 s timeout (one request at a time), so the next incident's warm-up waited behind it (F2 2m14s, F3 58 s).
  - **Ruled out:** context overflow (at most 3354 of 8192 tokens, `truncated = 0`), model eviction (no loads or embedding calls in the window), large tool outputs (only summaries are sent).
  - Fix options: cap `num_predict` on `ACT` calls; put the instruction after the tool results and keep the changing parts at the end so the prefix stays cached; abort the HTTP request on timeout.

- 2026-10-14: T5c1 and T5c2 done by a delegated writer (uncommitted, two units). Route: delegated writer.
  - **T5c1 (agent, spec, ADR-010 "Amended in T5c"):**
    - answer caps `kintsugi.agent.model.num-predict`: 384 for a phase's first `ACT` answer (plan measured 170–200 tokens), 160 for later ones (30–66 measured; holds 4 calls), 512 for the report (about all one 180 s call can write). A cut answer (`done_reason` `length`) is noted `answer cut at <cap> tokens` on its step, its tool calls are still checked; a cut report is retried with the reason;
    - prompt order: system, evidence (append-only), previous answer and tool results, then the instruction (budgets left, plan, task) last; the task asks for tool calls or an empty answer, never a summary, and keeps the plan out of tool arguments;
    - **aborted calls, root cause corrected:** the loop's interrupt already closed the connection; Ollama ran on because the request body was chunked (Reactor Netty), and Ollama's Go server watches for a client going away only after reading the body to its end. Measured: chunked request, client gone at 6 s, ran to `200` at 16.7 s; `Content-Length`, `500` at 6 s. Fix: `BufferingClientHttpRequestFactory` in the loop's `RestClientCustomizer`. Live check on the rebuilt image (8 s call timeout): the abandoned call ended in Ollama at 8.0 s (`500`), before the fix it ran 2 min 5 s to `200`.
    - RED observed for each change (behavioural), then GREEN: `agent` 229 tests (surefire XML), 0 failures. Leakage regex clean on `Prompts.java`, `Schemas.java`.
  - **T5c2 (scripts, docs):** the cause is read from `.report` only (`incident_fields`, python3; `none` without a report); a wait timeout ends the incident (`end_running_incident`) and the run goes on; per-fault window `incident_window` (minutes since the fault's first order, rounded up, plus 10, recomputed after a 409 wait) and `space_out` (11 min after the previous restore), so no window reaches the previous fault; `start_lab` starts everything but the agent, `start_agent` (verify-agent only) waits for it and points at `reset-volume.sh knowledge-data`; `compose_up` replaces the hard-coded fault-variable list; usage durations and the timeout message fixed; `reset-volume.sh` treats EOF as "no". `verify-f1.sh` passes; shellcheck 0.10.0 clean on the changed scripts.
  - Probe incidents ("Probe of the call abort, please ignore", 3, `TIMED_OUT`) remain in the live `agent` database.
- 2026-10-14: second full `verify-agent.sh` run after T5c1/T5c2 (`infra/scripts/out/verify-agent-20261014T151220Z/`): **0/3, all `TIMED_OUT`, no report**, but the runs now get much further.
  - F2 and F3 went through every evidence phase with valid plans and real tool calls, used the 8-call tool budget, and then timed out on the **`REPORT` call** (more than 180 s). They lasted 13.5 min of 30.
  - Plan-carrying `ACT` calls took 139–178 s, right at the edge; other `ACT` calls took 78–108 s.
  - F1 timed out on the first `INVESTIGATE` call (plan-carrying) after a 164 s `TRIAGE` call.
  - The abort works: Ollama logged the abandoned call as `500` at 3m0s.
  - Estimate, not measured: about 90 s to read the prompt plus up to 512 report tokens at about 2.9 tokens/s (about 176 s) cannot fit in 180 s, so the per-call budget does not fit this CPU for the report and barely fits for plan calls. This needs the human's decision.
- 2026-10-14: T5c4 done by a delegated writer (uncommitted, one unit). Route: delegated writer (2+ non-trivial files: loop code, tests, spec, ADR).
  - `CallTimeouts(act, report)` replaces the single `call-timeout`: `kintsugi.agent.model.call-timeout.act` 240 s and `.report` 300 s. A call waits at most `min(its task's deadline, incident time left)`; the reason names the task's deadline (`model call REPORT in CONCLUDE took longer than 300 s`). `LoopConfig.readTimeout` is the longest of both deadlines and the warm-up (300 s with the defaults).
  - T5c review suggestions: the `ModelRouteProperties` javadoc no longer repeats the caps (it points at the spec); a missing `call-timeout` or `num-predict` now fails binding with a message naming the property, instead of a `NullPointerException` at the first call.
  - RED observed (behavioural, with a stub `CallTimeouts` that used the `ACT` deadline for every task): a report past the `ACT` but within the `REPORT` deadline ended `TIMED_OUT` instead of `COMPLETED`; a slow report named `3 s` instead of its own `300 ms`; `of(REPORT)` and the read timeout returned the `ACT` deadline. Then GREEN: `agent` 236 tests, 0 failures.
  - Durations in tests are scaled down (200 ms to 3 s) through the route's `CallTimeouts`.
- 2026-10-14: third full `verify-agent.sh` run after T5c4 (`infra/scripts/out/verify-agent-20261014T162127Z/`, 74 min): **0/3, no report**, but every incident now reaches the end of the evidence phases.
  - F1 `INVALID_REPORT` (1143 s, 16 steps): used the 8-call budget, then both `REPORT` answers (284 s and 154 s) were cut at 512 tokens. Each evidence `quote` copied a whole escaped log line (about 200 tokens per item), so the JSON never closed. Its draft named `payment-service|DEPENDENCY_REJECTING`; the truth is `gateway-mock|DEPENDENCY_LATENCY`, so it would have missed anyway.
  - F2 `TIMED_OUT` (1023 s, 14 steps): used the budget, then the `REPORT` call took longer than 300 s.
  - F3 `TIMED_OUT` (692 s, 9 steps): the first `KNOWLEDGE` `ACT` call took longer than 240 s.
  - Call latencies: plan-carrying `ACT` 140–207 s, other `ACT` 74–103 s, `REPORT` 154–284 s or more. The F1/F2 `KNOWLEDGE` plan calls took 189 s and 207 s, close to the 240 s deadline.
  - Reading: the loop, the tools, the evidence check and the abort all work end to end; on this CPU the run is limited by model speed (about 13 tokens/s prompt, 2.9 tokens/s answer) and by long evidence quotes in the report. The F1 draft also shows a wrong diagnosis (payment-service instead of gateway-mock). This needs the human's decision.
- 2026-10-14: T5c5 done by a delegated writer (uncommitted, one unit). Route: delegated writer (2+ non-trivial files: loop code, tests, spec, ADR, script docs).
  - Quotes: `ReportCheck.MIN_QUOTE_LENGTH` 8 and `MAX_QUOTE_LENGTH` 160 (300 before) drive the check, the report schema (`Schemas`) and the report task (`Prompts`), which asks for "a short fragment ... only its distinctive part, such as an exception name, a status or an error message, not the whole line". A longer quote is rejected with `evidence[i].quote is longer than 160 characters; quote only its distinctive fragment, such as an exception name, a status or an error message, not the whole line`, which the retry receives; the substring check is unchanged, and a test shows a short fragment of an escaped event detail matching in both the raw (escaped) and the decoded text.
  - Deadlines: `call-timeout.act` 300 s and `.report` 420 s; the loop's read timeout follows (420 s). **The incident budget no longer fitted:** the longest measured run (16 steps: 8 tool calls, 6 `ACT` answers, 2 reports) needs 6 x 300 + 2 x 420 = 2,640 s (44 min) with every model call at its deadline, so `incident-timeout` is 45 min (30 before). `verify-agent.sh`'s wait becomes 52 min (budget, 5 min warm-up, 2 min margin); its usage text and `docs/setup.md` say so. Compose sets none of these values.
  - RED observed (behavioural, 7 failures): the schema's quote `maxLength` was 300; a 230-character quote present in the output passed; the report task did not name the limit; the bounds message said 8 to 300; the packaged defaults were 240/300 s, 30 min and a 300 s read timeout (`DefaultBudgetsTest` reads `application.yaml`). Then GREEN: `agent` 244 tests (surefire XML), 0 failures, 0 errors.
  - `infra/scripts/lib.sh` (outside the writer's surface, done by the parent): `INCIDENT_WAIT_SECONDS=$((45 * 60 + 300 + 120))` (52 min), so the script waits until the agent's own 45 min budget ends.
- 2026-10-14: T5c5 committed as `c81d03f`. The parent made the `lib.sh` wait edit. Spot check: `ReportCheckTest` 21 and `DefaultBudgetsTest` 3, 0 failures. Shellcheck clean from `infra/scripts`. Review assess from `3b1bd4c`: `high_risk`. Four-lens review `review-e18daff939075595` approved and acknowledged, with non-blocking findings:
  - WARNING (readability): ADR-010 still said a call is abandoned "at 180 s". Fixed in the next docs commit ("at its deadline").
  - WARNING (reliability), follow-up: the 45 min budget covers the longest measured run (6 `ACT` + 2 `REPORT` = 2,640 s at the deadlines) but not tool-call time (up to 20 s each) nor a valid worst case of 8 `ACT` + 2 `REPORT` (3,240 s). `DefaultBudgetsTest` checks only the measured run. Revisit after the fourth run's latencies.
  - Suggestions: the singular `call-timeout` name in `ModelRouteProperties:18`, a test-name typo at `AgentLoopTest:525`, a tight `ACT` deadline test, and the incident budget's worst valid run in `application.yaml`.
- 2026-10-14: fourth full `verify-agent.sh` run after T5c5 (`infra/scripts/out/verify-agent-20261014T174310Z/`, 84 min): **0/3, all `INVALID_REPORT`**. Every incident ran the full loop (16 steps), and no call hit a deadline: `ACT` 77–233 s, `REPORT` 88–341 s. Every report closed its JSON, and no quote was over 160 characters.
  - **Cause (deterministic, in our check):** the model writes its quotes from the tool **summaries** it is shown (`status="PAYMENT_FAILED" count=6`), but `ReportCheck` looks them up only in the raw `toolOutput` JSON (`{"status":"PAYMENT_FAILED","count":6}`) and its decoded strings. Of 30 quotes, 18 are verbatim in the summary of the cited step and 0 in the raw output. The rest are runbook quotes that turn the `\n\n` escapes into single newlines, plus one with an invented `(cut)`.
  - **Diagnosis quality:** all six drafts name `payment-service|DEPENDENCY_REJECTING`, whatever the fault. With a fixed check the run would still score at most 0/3 on service; F2's category is right.
- 2026-10-14: T5c6 done by a delegated writer (one unit). Route: delegated writer (2+ non-trivial files: check, loop wiring, tests, spec, ADR).
  - `ToolStep` now keeps the summary the model was shown (`AgentLoop.runTool` stores it), and `ReportCheck` also looks for the quote there. Quote and haystacks (raw output, decoded JSON strings, summary) are normalized alike: the escapes `\n`, `\t`, `\"` and `\\` decoded, whitespace runs, line breaks included, collapsed to one space, trimmed. The substring rule, the 8–160 bounds and the error text are unchanged.
  - Fixture: `agent/src/test/resources/live-runs/verify-agent-20261014T174310Z.json`, the fourth run's tool steps (output and summary) and report answers, unchanged. `LiveRunReportCheckTest` replays its six reports.
  - RED observed (behavioural, 5 failures): the summary-form quote `status="PAYMENT_FAILED" count=6` (F2 step 3), a runbook quote of the summary with its `\n\n` escapes written as one real line break (F2 step 11), a summary quote in a unit test, the escape decoding in `normalized`, and the replay. The rejection test (invented `Gatewa...(cut)`, `| **g`, `durationMs=10008 of 9 events`) passed before and after. Then GREEN: `agent` 250 tests (surefire XML), 0 failures, 0 errors.
  - Replay: the run has 26 quotes, not 30 (F1's retry holds one item); 16 are verbatim in their step's summary and none in the raw output. With the new check **20 of 26 pass** and **one report is valid: F1's retry (step 16)**. Still rejected, correctly, because they are not verbatim: F1#15 `durationMs=10008 of 9 events` (the summary has `min=... max=10008 of 9 events`); F2#15 and F2#16 `## 5. Key signals\n- \`lab_payments{status}\`...` (drops the `**Metrics** ...` line between); F3#15 `Gatewa...(cut)` (the summary has `Gatewa"(cut)`) and `| **g` (the runbook has `| gateway-mock`); F3#16 the same `| **g`. Every report would still name `payment-service|DEPENDENCY_REJECTING` (T5c7).
- 2026-10-14: T5c6 committed as `80dd02f`. Spot check: `ReportCheckTest` and `LiveRunReportCheckTest` green. Assess from `c81d03f`: medium, `slice_budget_reached`. Review `review-02490cbeba753389` (one lens, reliability) approved and acknowledged, with follow-up WARNINGs:
  - the summary fallback also matches text the code writes (the header, computed aggregates, the `(cut)` markers and the outcome sentences), so a quote of those passes;
  - no `AgentLoop`-level test proves the loop stores the summary it showed the model on the `ToolStep`.
- 2026-10-14: T5c7, why every report blames `payment-service` (read-only explorer). Verified from the fourth run and the code; ranked:
  1. **Caller named instead of callee (prompt):** `Prompts.java:39-49` never says that for `DEPENDENCY_*` the root cause is the slow or refusing dependency. The F1/F3 summaries name gateway-mock and still report payment-service.
  2. **Evidence-free TRIAGE plan, re-fed verbatim (prompt + loop):** step 0 hypothesizes payment-service, inventory-service and postgres before any tool call in all three runs. "Latest plan" (`Prompts.java:141`) repeats it, and the tool arguments follow it. gateway-mock and order-service are never targeted.
  3. **Queries miss every decisive signal (tool text + prompt):**
     - the lab logs failures at WARN/INFO, never ERROR, and every log query used `level=ERROR`, so they returned 0;
     - `error_rate` counts 5xx only, so it is blind to F1–F3;
     - F3 traced a pre-fault PAID order taken from `source=payment`;
     - `service_restarts` is never called.
  4. **The runbook summary cuts the exonerating text (summarizer):** `Summarizers.RUNBOOK_TEXT_CHARS=300` cuts before "FAILED means the gateway answered 429" and before the gateway rows of "Burst of PAYMENT_FAILED orders".
  5. **KNOWLEDGE comes after INVESTIGATE and is filtered to payment-service (loop, large).**

  Decisive signals: F1 gateway-mock INFO `latency_ms=10000` (not queried); F2 payment-service WARN `Gateway rejected … 429` (ERROR-only queries); F3 order event `PAYMENT_UNAVAILABLE` `ConnectException` to `:9999` (`recent_events source=order`, never queried). The existing tools can surface all three.

- 2026-10-14: T5c8 done by a delegated writer, in two units. Route: delegated writer (2+ non-trivial files in two modules: prompts, loop, summarizers, check, tool descriptions, tests, two specs, ADR).
  - `059141c` fix(mcp-tools): `search_logs`/`count_logs` descriptions say the lab logs failures at WARN and gateway decisions at INFO, "not ERROR"; `query_metric` says `error_rate` counts 5xx server responses only; the `search_runbooks` example is `orders stay pending after checkout`. mcp-tools spec: new requirement "Descriptions say where the lab's signals are". RED: `McpToolsEndpointTest` 1 failure; GREEN: mcp-tools 213 tests (surefire XML), 0 failures, 0 errors.
  - fix(agent), the next commit: (1) the system message gives the call path and says that for `DEPENDENCY_*` the root cause is the slow or refusing component, not its caller; (2) a plan is shown only within its phase (`Your plan for this phase:`), never in the next phase or the report; `TRIAGE`'s plan task asks for at least two services along the call path, later phases for a fresh plan whose `why` starts with a `toolCallId` (asked, not validated: an invalid plan has no retry, so a check buys nothing); (3) the system message says to trace a `PAYMENT_PENDING`/`PAYMENT_FAILED` order found with `recent_events source=order`; (4) `RUNBOOK_TEXT_CHARS` 900 (300 before), and runbook chunks are shown while they fit the unchanged 1,500 cap, with an honest `shown` (usually one whole chunk); WARNING A: `ToolSummaries.quotableLines` keeps the summary lines that hold a JSON literal, minus the header and the code's `(cut)` markers, and `ReportCheck` matches a summary quote within one of them; WARNING B: an `AgentLoopTest` run quotes the summary-only `status="PAYMENT_PENDING" count=7` and completes at the first report.
  - RED observed (behavioural, 13 failures, with the old code and a stub `quotableLines` returning every line): `PromptsTest` 6, `AgentLoopTest` 2 (plan shown per phase), `ToolSummariesTest` 4 (900 cap, fitting, quotable lines), `ReportCheckTest` 1 (code-written text). The WARNING B loop test passed before and after: it guards T5c6's existing wiring. GREEN: `agent` 262 tests (surefire XML), 0 failures, 0 errors.
  - Replay (`LiveRunReportCheckTest`): unchanged, **20 of 26 quotes pass**, F1's retry valid; no quote of the run relied on text only the summary's code wrote.
  - Cost: about 110 tokens added to the system message (read once per incident, then reused), about 60 to each plan task, about 100 to the log tools' definitions. Authored lines: about 500 with the new `PromptsTest`, over the 400 heuristic because of tests and two specs.
- 2026-10-14: T5c8 committed as `059141c` (mcp-tools) and `e20dcc2` (agent). Spot check: `PromptsTest` and `ReportCheckTest` green. Assess from `80dd02f`: medium, `slice_budget_reached`. Review (reliability lens) approved and acknowledged. Follow-ups:
  - WARNING: a 900-character runbook chunk full of `\n`/`\"` can escape past the 1,500-character summary cap, so the line is dropped while the summary still says `shown=1` (`Summarizers.java:233-239`);
  - SUGGESTION: computed figures stay quotable (`ToolSummaries.java:66-74`);
  - SUGGESTION: no test covers how ties between runbook chunks are ordered;
  - the recorded `tools-list.json` still has the old descriptions, and no test compares against it.
- 2026-10-14: fifth full `verify-agent.sh` run after T5c8 (`infra/scripts/out/verify-agent-20261014T221806Z/`, 66 min): **1/3, the first match.** All three ended `COMPLETED` with a valid report, and no call hit a deadline. Incidents took 587–917 s.
  - **F2 MATCH** `gateway-mock|DEPENDENCY_REJECTING`, citing `status="FAILED" count=6` and the six `FAILED` payment attempts.
  - **F1 and F3 `UNKNOWN|UNKNOWN`:** honest reports that say the evidence is insufficient. Waste in the 8-call tool budget, seen in all three runs:
    - the INVESTIGATE plan sends `get_order_trace` with the literal placeholder `"<SELECT_A_PENDING_ORDER_ID>"` (`INVALID_PARAMETER`, budget spent);
    - the next `ACT` repeats an identical call (`recent_events source=payment …`, F1/F2 steps 5 and 9);
    - `search_logs gateway-mock level=WARN` returns 0, because gateway-mock logs its decisions (`latency_ms=`, `answered=429`) at INFO;
    - F3 filtered `recent_events source=order` by `outcome=PAYMENT_FAILED`, but its orders are `PAYMENT_PENDING` with `PAYMENT_UNAVAILABLE` events, so it saw nothing;
    - F3's `count_logs service=postgres` was invalid; F1's KNOWLEDGE plan named a tool not allowed in that phase.
  - The budget runs out in INVESTIGATE before the decisive call (F1: the trace of a real order and gateway-mock logs were refused).
- 2026-10-14: T5c9 done by a delegated writer, in two units. Route: delegated writer (2+ non-trivial files in two modules: loop, argument checks, migration, prompt, tool descriptions, tests, two specs, ADR).
  - mcp-tools (`be241fd`): the `search_logs`/`count_logs` descriptions say gateway-mock logs its decisions at INFO, so search it with no level filter. RED: `McpToolsEndpointTest` 1 failure; GREEN: `mcp-tools` 213 tests (surefire XML), 0 failures, 0 errors.
  - agent: an offered call whose argument is a placeholder (`<name>`) is refused `REFUSED_PLACEHOLDER`; a call with the same tool and arguments (parsed JSON, key order ignored) as one that ran is refused `REFUSED_DUPLICATE`, "already called as toolCallId N; read that result". Neither runs. The first 2 per incident consume nothing; later ones consume a unit, so a repeating model still ends its phase. With the cap a valid run makes at most 8 + 2 `ACT` answers and 2 reports, the 12-call model budget (`DefaultBudgetsTest`). Migration V3 widens the `tool_outcome` check. The system message says an order never paid stays `PAYMENT_PENDING` with `PAYMENT_UNAVAILABLE` events, filter by those, `PAYMENT_FAILED` only when the status summary shows it, and copy a real `orderId`.
  - RED observed against stubs (9 failures): 3 `AgentLoopTest` (placeholder, duplicate, cap), 3 `ToolArgsTest`, 2 `PromptsTest`, 1 `DefaultBudgetsTest`. GREEN: `agent` 272 tests (surefire XML), 0 failures, 0 errors; the `AgentLoopTest` leak check still passes.
  - Open: the 45 min incident budget does not hold the new worst case (10 `ACT` + 2 `REPORT` at their deadlines), as it already did not for 8 + 2.
- 2026-10-14: T5c9 committed as `be241fd` (mcp-tools) and `f9d2b25` (agent; Flyway `V3` widens the `tool_outcome` CHECK). Spot check: `ToolArgsTest` and `DefaultBudgetsTest` green. Assess from `e20dcc2`: medium, `slice_budget_reached`. Review (reliability lens) approved and acknowledged. Follow-ups:
  - WARNING: a call that failed transiently (`UPSTREAM_ERROR`/`CLIENT_ERROR`) is also remembered, so retrying it is refused as a duplicate (`AgentLoop.java:338-342`);
  - SUGGESTION: duplicates count across phases, although time-relative queries can return new data later;
  - SUGGESTION: `<init>`-like search terms would count as placeholders;
  - SUGGESTION: `DefaultBudgetsTest` only adds up constants; no loop-level worst-case test.
- 2026-10-15: sixth full `verify-agent.sh` run after T5c9 (`infra/scripts/out/verify-agent-20261014T234745Z/`, 70 min): **0/3** (run 5 was 1/3). With one sample per fault, the result is noisy.
  - **F1 `UNKNOWN`:** `search_logs gateway-mock` with no level returned 20 lines, all Spring/Tomcat startup lines. Every line is distinct and the cap is 20, so the per-request `Authorize answered … latency_ms=10000` lines were crowded out. Tool-side cause: the lines are grouped by exact message, so lines that differ only in their numbers do not group.
  - **F2 `INVALID_REPORT`:** right path, with 6 `FAILED` attempts and a trace of a `PAYMENT_FAILED` order. Both reports cited a refused step (`REFUSED_DUPLICATE`, then `REFUSED_PLACEHOLDER`) in one evidence item; the retry fixed one and broke another.
  - **F3 `gateway-mock|DEPENDENCY_REJECTING`:** it traced a `PAID` order again and over-applied the callee rule without evidence.
  - Recurring, all runs:
    - `count_logs service=postgres` is invalid, because postgres is not a log service;
    - plans fail on `hypotheses[].why` over 200 characters;
    - the KNOWLEDGE plan names a tool outside the phase.
  - The new refusals worked: `REFUSED_PLACEHOLDER` and `REFUSED_DUPLICATE` cost no budget, and F2 then traced a real order.

- 2026-10-15: T5c10 done by a delegated writer, in two units. Route: delegated writer (2+ non-trivial files in two modules: summarizer, prompts, plan check, tool descriptions, tests, two specs, ADR).
  - Cause of the crowded-out log lines, found in code and in F1's stored step 6: mcp-tools asks Loki for the newest lines (`direction=backward`, `limit` 20), and its output held all 9 `Authorize answered … latency_ms=…` lines. The agent's `Summarizers.searchLogs` lost them: it grouped by exact message text, so each request line (its own order id) was seen once, and the tie on count went to the earliest lines, the startup ones. Loki was not queried: the stored output already shows the real line shapes.
  - mcp-tools (`c0950b7`): the `search_logs`/`count_logs` descriptions say logs exist only for order-service, payment-service, inventory-service and gateway-mock, and postgres has no logs to search (`ToolInputs.SERVICES` accepts only those four). RED: `McpToolsEndpointTest` 1 failure; GREEN: `mcp-tools` 213 tests (surefire XML), 0 failures, 0 errors.
  - agent: log messages group by a template (UUIDs and hex ids of 8+ characters with a digit and a letter become `<id>`, numbers `#`), each group shown by its newest message, ranked by count then newest time; the header reads `lines=<n> groups=<m> (same text but for numbers and ids), most frequent then newest first`. On F1's recorded output (new fixture `search_logs-gateway-latency.json`) the first group is `Authorize answered` with count 9 and `latency_ms=10000`. The report task ends `Citable toolCallIds: …` and `Refused calls did not run and are not citable: …`; `ReportCheck` stays the guard, and its retry already names every invalid item at once (`aWrongToolAMissingStepOrARefusedCallIsRejected`). `PlanCheck.MAX_WHY_LENGTH` 300 (200 before), and the plan task says so; the report's `evidence[].why` stays 200.
  - RED observed (9 failures, with a stub `Prompts.report` overload that ignored the steps): `PromptsTest` 3, `ToolSummariesTest` 6. Two existing tests built "distinct" lines that differ only by a number, which now group; they use letters instead. GREEN: `agent` 277 tests (surefire XML), 0 failures, 0 errors; the `AgentLoopTest` leak check still passes.
  - Cost: about 25 tokens on the report task, about 25 on the log tools' definitions. Authored lines: about 300 (agent about 280, mcp-tools about 20), plus this entry.
- 2026-10-15: T5c10 committed as `c0950b7` (mcp-tools) and `46da2c4` (agent). Spot check: `ToolSummariesTest` and `PromptsTest` green. Assess from `f9d2b25`: medium, `under_budget`; the review is deferred to the next commit that reaches the budget. The seventh full run started.
- 2026-10-15: seventh full `verify-agent.sh` run after T5c10 (`infra/scripts/out/verify-agent-20261015T010641Z/`, 84 min): **0/3**, but F1 and F2 named the right root cause and lost it to the quote check.
  - **F1 `INVALID_REPORT`:** both reports said `gateway-mock|DEPENDENCY_LATENCY`. Each time, one evidence item out of 5 was invalid. The model shortened a JSON quote from `recent_events` and closed it with a `}` that is not in the output. It also quoted `(no logs found)` for a search that returned no lines. The other items were verbatim.
  - **F2 `INVALID_REPORT`:** both reports said `gateway-mock|DEPENDENCY_REJECTING`, with the `Authorize answered … answered=429` line as evidence. One item dropped the middle tokens of the `Gateway behavior changed … error_status=429` line; the same line, quoted in full in another item, passed.
  - **F3 `gateway-mock|DEPENDENCY_REJECTING`:** it traced a `PAID` order and never asked for `recent_events source=order`, the decisive signal.
  - Recurring, all three:
    - TRIAGE plans 3 calls against a phase budget of 2, so `status_summary` is refused every time;
    - the KNOWLEDGE plan names `search_logs`, the plan is invalid, and the model writes its calls as Markdown, so no runbook was searched.
  - T5c10 worked: the grouped log lines put `Authorize answered` first, and no report cited a refused step.
  - Proposed T5c11:
    - `ReportCheck` drops an evidence item whose quote is not in its step, and records it as dropped. The report stays valid while at least one verified item remains. Every remaining item is still checked by code.
    - The ACT task states the phase's tool-call limit.
- 2026-10-15: T5c11 done by a delegated writer, in one unit (agent only). Route: delegated writer (2+ non-trivial files: `ReportCheck`, `AgentLoop`, `Prompts`, their tests, the spec, ADR-010).
  - `ReportCheck.check` returns a `Result(errors, dropped, evidence)`; `errors(...)` stays as its shortcut. An item that passes every rule but the substring test is dropped. The report stays valid while one verified item is left, or both causes are `UNKNOWN`. Every other item error is fatal: a missing, refused or other-tool step, the quote bounds, the schema, the enums, or no verified item left. A rejected report's errors name the unverified quotes too, in item order, so the retry fixes them all. The matching rules are unchanged.
  - Recording: the `REPORT` step's summary reads `valid; dropped: evidence[i].quote is not in the output of step N; ...`, and its `model_output` keeps the raw answer. `incidents.report` keeps only the verified items, so what is stored and scored is verified. No migration: no column or constraint changes.
  - The `ACT` task, with or without the plan, ends `You may make at most N tool calls in this phase; more are refused.` N is the lesser of the phase's and the incident's calls left, and the sentence says `1 tool call` when N is 1. It adds about 15 tokens per `ACT` call, and the `AgentLoopTest` leak check still passes.
  - RED observed (4 failures, with a stub `check` that only wrapped `errors`): `ReportCheckTest` 2 (one bad quote among verified ones; an `UNKNOWN` report whose only quote is dropped), `PromptsTest` 1 (the call limit), and `AgentLoopTest` 1 (the run ended `FAILED`, because the script had no answer for the retry). `LiveRunReportCheckTest`'s replay was rewritten for the new rule: the fourth run's six reports are all valid now, with the same non-verbatim items dropped.
  - GREEN: `agent` 283 tests (surefire XML), 0 failures, 0 errors. Specs: `openspec/specs/agent/spec.md` ("Budgets", "The report", "Evidence is checked", with four new scenarios). ADR-010 has "Amended in T5c11".
  - Authored lines: about 255 (code about 105, tests about 100, spec and ADR about 50), plus this entry.
- 2026-10-15: T5c11 committed as `bffdb9c`. Spot check: `ReportCheckTest` and `PromptsTest` green. Assess from `f9d2b25` (covering T5c10 and T5c11): medium, `slice_budget_reached`. Review `review-aba6cf1194eeab4b` (reliability lens) approved and acknowledged. Follow-up, WARNING:
  - the T5c10 log template masks every number, so `answered=429` and `answered=200` (or `latency_ms=0` and `latency_ms=10000`) fall into one group, and the group shows only its newest line (`Summarizers.java:165-168`);
  - when the gateway's answers change within the window, the older decisive variant can vanish from the summary;
  - no test covers a 429 group followed by newer 200 lines.

  Candidate fix: keep `key=value` numbers in the template, or show the newest line of each distinct value.
- 2026-10-15: the eighth full run started on `bffdb9c`.
- 2026-10-15: eighth full `verify-agent.sh` run on `bffdb9c` (`infra/scripts/out/verify-agent-20261015T114730Z/`, 60 min): **1/3**. F2 MATCH; F1 and F3 `UNKNOWN|UNKNOWN`, both valid reports with no evidence.
  - **F1:** TRIAGE kept to 2 calls (the stated limit worked), but `status_summary payments` showed only `AUTHORIZED`. The INVESTIGATE answer was cut at 384 tokens: the model wrote its plan and then described its calls in Markdown, made no tool call, and the phase ended. The investigation never ran.
  - **F3:** asked `recent_events source=payment` twice and `search_logs gateway-mock`; all showed authorized payments. It never asked `source=order`. `outcome=PAYMENT_FAILED` for payment was rejected as `INVALID_PARAMETER`.
  - **KNOWLEDGE, third run in a row:** the plan names `search_logs`, so it is invalid. The model writes its calls as Markdown, and no runbook is ever searched.
  - Pattern: an ACT answer with no tool call ends the phase, even with calls left. This lost all of F1.
  - Proposed T5c12:
    - an ACT answer with no tool call, while the phase has calls left, gets one short retry that asks for a tool call now and no plan;
    - the plan check drops `nextTools` entries outside the phase instead of rejecting the plan.
- 2026-10-15: T5c12 done by a delegated writer, in one unit (agent only). Route: delegated writer (2+ non-trivial files: `AgentLoop`, `PlanCheck`, `Prompts`, their tests, the spec, ADR-010).
  - The retry: an `ACT` answer with text but no tool call, while the phase and the incident still allow a call, gets one more `ACT` call per phase, with the same messages and `Prompts.actRetry` last (`Your previous answer made no tool call, so nothing ran. Make exactly one tool call now, ... Write no plan and no text`). It goes through `call()`, so the model-call budget, the `ACT` deadline and the incident's time left apply; it asks for no plan, so it gets the `act` cap (160), not `first-act`. The answer before it is recorded `no tool call; one retry asks for a tool call`, and the retry's `ACT` step starts `retry: `. A second answer without a tool call ends the phase, and a later one in the same phase gets no retry.
  - Accepted design choice: an answer with no text and no tool call ends the phase at once, with no retry. It is the `ACT` task's documented exit ("answer with no text and no tool call, and the run moves on"); retrying it would force a call in every phase.
  - The plan check: `PlanCheck.check` returns `Result(errors, dropped, plan)`; a string `nextTools` entry outside the phase is dropped and named in the verdict (`plan valid; dropped: nextTools[0] "search_logs" is not a tool of KNOWLEDGE, which offers search_runbooks`), and the plan shown later is the one without it. A non-string entry, more than 4 entries, and every other rule still invalidate the plan.
  - Budget: `max-model-calls` 12 → 15 (`application.yaml`). Each phase's retry can add one answer, so a valid run makes at most 8 + 2 (uncounted refusals) + 3 (retries) `ACT` answers and 2 report attempts; at 12 a run using retries could end `BUDGET_EXCEEDED` without a report. The incident's time budget is unchanged: it holds the longest run measured, not the worst case.
  - RED observed (10 failures, with compile-only stubs for `PlanCheck.check` and `Prompts.actRetry`): `AgentLoopTest` 6, `PlanCheckTest` 3 (new class), `PromptsTest` 1; then `DefaultBudgetsTest` 1 (expected 15) until the yaml changed. About 25 existing `AgentLoopTest` scripts ended phases with a non-empty answer; the quiet-phase helpers now answer with no text (`ScriptedChatModel.quiet()`), and `aCutAnswer...` and `aMissingOrInvalidPlan...` were rewritten for the new rules.
  - GREEN: `agent` 295 tests (surefire XML), 0 failures, 0 errors. Specs: `openspec/specs/agent/spec.md` ("Phases and their tools", "The plan", "Budgets", "Answer caps", with seven new or rewritten scenarios). ADR-010 has "Amended in T5c12".
  - Follow-up: the comment above `max-model-calls` in `application.yaml` still says a valid run makes at most 10 model calls and 12 is a guard; only the value was in scope.
  - Authored lines: about 450 (code about 90, tests about 250, spec and ADR about 110), plus this entry.
- 2026-10-15: T5c12 committed as `3c77f6f`.
- 2026-10-15: T5c12 commits `3c77f6f`, `4695993`, config comment `3e6edc2`. Spot check: `PlanCheckTest`, `DefaultBudgetsTest` green. Assess from `807ae72`: medium, `slice_budget_reached`; review approved and acknowledged. The ninth full run started on `3e6edc2`.

## Next step

The ninth full run (T5c3) on the T5c12 commit.
