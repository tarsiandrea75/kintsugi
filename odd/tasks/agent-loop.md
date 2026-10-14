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

## Next step

T5 part B on `feat/agent-loop-04-check`: run `infra/scripts/verify-agent.sh` (F1–F3, up to about 2 hours on the laptop CPU) with `granite4.1:8b`, record the results from `infra/scripts/out/`, then commit Part B.
