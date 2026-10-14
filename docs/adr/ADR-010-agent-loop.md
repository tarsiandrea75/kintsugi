# ADR-010: An explicit phased agent loop driven by our code

## Status

Accepted

> Decided on 2026-10-08 from the design in the [feature document](../../odd/tasks/agent-loop.md). The human chose the report taxonomy, the context strategy (summaries built in code) and the trigger (the check script). The rest of the design was proposed by Claude after read-only research and **is to be reviewed by the human**. A change after review supersedes this ADR or amends it in place before the feature merges.

## Date

2026-10-08

## Context

The last two M2 items are the loop "perception → plan → action → observation, with IncidentState" and "a JSON report with cited evidence" ([vision](../vision.md), M2). They feed the M2 check: "F1–F3 correctly diagnosed with a local model".

The pieces the agent reasons over already exist:

- mcp-tools serves seven read-only evidence tools and `search_runbooks` over MCP ([ADR-004](ADR-004-tools-via-mcp.md), [ADR-002](ADR-002-pgvector-knowledge-base.md));
- the fault-injector records the truth as `root_cause_service` and `root_cause_category` in the `faults` database, which the agent must never read ([ADR-009](ADR-009-fault-injector-docker-control.md));
- the local default model is `granite4.1:8b` on a laptop CPU, with `num-ctx` 8192 ([ADR-003](ADR-003-local-models-default.md)).

Constraints:

- **A script must score the diagnosis.** The report must name the cause in a vocabulary that a mechanical equality can compare with the truth, without an LLM judge.
- **Citations must be checkable.** The vision's "real evidence" metric needs every cited log line or metric to exist; a model that invents evidence must be caught by code.
- **Small model, small context.** A raw `search_logs` result can reach 8 KB; a few of them overflow 8192 tokens. The vision's tactics apply: few tools per step, structured output validated by schema, logs summarized by code (`docs/vision.md`, section 5).
- **Every step is recorded**, so a run can be replayed, audited and measured by the M4 evals.
- **The agent never touches the truth or the lab directly.** Its only reach into the lab is mcp-tools.
- **Core rule:** the LLM proposes, deterministic code verifies, policy authorizes. In M2 every tool is read-only, so the policy layer is a no-op; it arrives in M3.

## Decision

**The agent is a Spring Boot service whose own code drives a fixed sequence of phases. In each phase the model sees only a few tools, writes a plan that code checks (in the text of its first answer, since T5), and requests tool calls that our code checks, executes through the MCP client, records in full and summarizes before the model sees them. A final structured-output call writes a schema-validated report whose evidence quotes code checks against the recorded tool outputs.**

### Stack (verified 2026-10-08 against the Spring AI 2.0.1 reference)

| Component | Choice | Source |
|-----------|--------|--------|
| Spring Boot / Spring AI | 4.1.1 / 2.0.1, as in [ADR-006](ADR-006-stack-versions.md) | `agent/pom.xml` |
| Chat model | `spring-ai-starter-model-ollama`, already in `agent/` | [Ollama chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) |
| MCP client | `spring-ai-starter-mcp-client` (JDK `HttpClient` transport, MCP Java SDK 2.0.0), `spring.ai.mcp.client.type=SYNC` (the default), `spring.ai.mcp.client.initialized=false`, one connection under `spring.ai.mcp.client.streamable-http.connections.kintsugi` with `url` `${KINTSUGI_MCP_URL:http://127.0.0.1:18085}` (compose sets `http://mcp-tools:8085`) and `endpoint` `/mcp`. Tool definitions arrive as `ToolCallback`s from `SyncMcpToolCallbackProvider`; calls go through the `McpSyncClient` (settled in T3, below) | [MCP client starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) |
| Tool execution | the model is called through `ChatModel`, which in 2.0.1 never executes tools; our loop executes each checked call | [Tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html) |
| Structured output | `OllamaChatOptions.outputSchema(<JSON schema>)`, Ollama's schema-constrained `format`, then validation by our own code | [Ollama chat, structured outputs](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html), [provider-native structured output](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html) |
| State store | a new `agent` database on the `knowledge-db` instance (pgvector image, PostgreSQL 18), schema by Flyway in `agent/` | [ADR-002](ADR-002-pgvector-knowledge-base.md) |

Facts checked in the 2.0.1 docs, which correct the feature document's wording:

- **There is no `internalToolExecutionEnabled` option in 2.0.1.** "The per-`ChatModel` internal tool execution loop of Spring AI 1.x has been removed. Calling `ChatModel` with tools sends the tool definitions to the model and returns the model's response — tool calls in that response are not executed automatically." The automatic loop now lives in `ChatClient`'s auto-registered `ToolCallingAdvisor`, which can be turned off per request (`AdvisorParams.toolCallingAdvisorAutoRegister(false)`) or globally (`spring.ai.chat.client.tool-calling.enabled=false`). Calling `ChatModel` directly is therefore the user-controlled path, with no flag to set.
- **`ToolCallingManager.executeToolCalls(prompt, response)` executes every tool call of a response before returning.** The loop needs a check before each call (phase, budget), and the full raw output of each call. So it looks up the matching MCP `ToolCallback` by name and calls it itself, one checked call at a time. `ToolCallingManager` is not needed.
- **Tool names are kept as served.** With a single MCP connection the default `DefaultMcpToolNamePrefixGenerator` adds no prefix; it only replaces non-alphanumeric characters with underscores, and the mcp-tools names have none.
- **The starter initializes its clients eagerly by default (settled in T3).** `McpClientAutoConfiguration` calls `initialize()` on each client while the context starts when `spring.ai.mcp.client.initialized` is true, its default; with mcp-tools down, the start fails ("Client failed to initialize by explicit API call"), which a test reproduces. With `initialized=false` the SDK client initializes on its first request, and after a failed attempt it tries again on the next one. `SyncMcpToolCallbackProvider` lists the tools only when first asked, and `ToolCallingAutoConfiguration` skips MCP providers when it builds its resolver, so nothing else connects at startup.
- **The MCP `ToolCallback` cannot run our calls (settled in T3).** `SyncMcpToolCallback.call` throws a `ToolExecutionException` when the result has `isError`, and returns the content list re-serialized, not the text. mcp-tools reports `INVALID_PARAMETER` and `UPSTREAM_ERROR` as `isError` results whose text is evidence. So the loop's `LabTools` port offers the model the callbacks' definitions only (wrapped so they cannot execute), and runs each call with `McpSyncClient.callTool`, keeping the text exactly as mcp-tools wrote it.
- **The MCP client's request timeout defaults to 20 s** (`spring.ai.mcp.client.request-timeout`), above mcp-tools' own 5 s backend timeouts. It is kept.
- **Provider-native structured output is model-specific on Ollama**: the docs warn that not every model honours it, and that reasoning models such as `qwen3:8b` may answer with plain text. Our own validation and one retry cover that, and the loop counts every attempt. Spring's `validateSchema()` self-correction (up to 3 attempts by default) is not used: it lives on `ChatClient` and its retries would be invisible to the step budget.

### The loop

Four phases, always in this order. Each phase exposes only its own tools, as the vision's "few tools per step" asks.

| Phase | Tools offered | Tool calls in the phase |
|-------|---------------|-------------------------|
| `TRIAGE` | `status_summary`, `query_metric`, `service_restarts` | at most 2 |
| `INVESTIGATE` | `search_logs`, `count_logs`, `recent_events`, `get_order_trace` | at most 4 |
| `KNOWLEDGE` | `search_runbooks` | at most 2 |
| `CONCLUDE` | none | 0; one structured-output call writes the report |

- **Plan first, in the same answer (amended in T5).** The first answer of each of the first three phases writes a plan `{hypotheses[], nextTools[]}` in its text and requests its tool calls. Code reads the JSON object from the text and checks it (the report's enums; the phase's tool names). The plan steers the model and is recorded for the evals; it does not authorize anything, so a missing or invalid plan is recorded on the `ACT` step and the run goes on. T1 to T4 had a separate schema-constrained `PLAN` call with one retry; see "Budgets amended in T5".
- **Act, one checked call at a time.** The model is called with the phase's tools. Code checks every requested call in order: a call past the phase's or the incident's budget is refused (`REFUSED_BUDGET`) and consumes nothing, since nothing is left; any other call consumes one unit of the 8-call tool budget and one of its phase's allowance, and a tool outside the phase is then refused (`REFUSED_PHASE`), not run. Every refusal is recorded with its reason. So a model that keeps asking for the wrong tool cannot loop, and no tool call ever consumes the model-call budget. An allowed call runs through mcp-tools, and its full output is stored.
- **Observe through summaries.** The model never receives raw tool output. Code builds a deterministic summary of each output (counts, the first distinct log messages, a compressed metric series, the top runbook hits), capped at 1,500 characters; every "top N" breaks ties by first occurrence time, then lexicographically, so the same output always gives the same summary, and each prompt is rebuilt from the state: symptom, phase, remaining budget, latest plan and the summaries so far. That keeps a run inside `num-ctx` 8192 with room to spare: eight summaries are at most 12,000 characters, about 3,000 tokens.
- **A phase ends** when the model answers without a tool call or the phase's cap is reached.
- **What one call sees (settled in T4, reordered in T5c).** Each call is a system message (role, symptom, window, rules, the two enums), a user message with the evidence of earlier calls, and, within a phase, the previous answer's tool calls and one tool-response message with their summaries; the instruction (budgets left, latest plan, task) comes last. See "Amended in T5c". The model thus gets each result as a tool result, as it was trained to, while the prompt never grows into a chat history. Every model answer is a step: `ACT` (an answer in an evidence phase, with or without tool calls; migration V2 adds the kind) or `REPORT`, so the step table counts every model call. Until T5 a separate `PLAN` call was a step too; the kind stays allowed but is no longer written.
- **Conclude.** The model writes the report with no tools offered. Code validates the schema, then checks every evidence quote against the recorded output of the cited tool call. On failure the model gets one retry with the list of errors; a second failure ends the run as `INVALID_REPORT`.

Why the phase caps sum to the incident's 8-call budget: the model always gets to triage, investigate and read the runbooks, and the last phase can never be starved by the first. Reaching the 8th call closes the evidence phases explicitly (a recorded step and a log line) and moves to `CONCLUDE`; a run that uses its whole tool budget can still produce a scored report.

### Budgets and statuses

| Budget | Value | When it is hit |
|--------|-------|----------------|
| Tool calls | 8 per incident (2 + 4 + 2 per phase) | further calls are refused and recorded; the loop moves to the next phase |
| Model calls | 12 per incident (16 before T5) | the run ends `BUDGET_EXCEEDED`. A valid run needs at most 8 `ACT` answers (each phase's answers are at most its allowance) + 2 report attempts = 10, so this is a guard against a loop bug, never a normal end |
| One model call | per task since T5c4: 300 s for `ACT`, 420 s for `REPORT` since T5c5 (240 s and 300 s in T5c4, 180 s for both in T5, 90 s before) | the run ends `TIMED_OUT` |
| One incident | 45 minutes since T5c5 (30 in T5, 10 before), wall clock from the end of the warm-up, tool calls included | the run ends `TIMED_OUT` |
| Warm-up | one request before the run, at most 300 s, counted against no budget | the run ends `FAILED` |

Statuses: `RUNNING`, `COMPLETED`, `INVALID_REPORT`, `BUDGET_EXCEEDED`, `TIMED_OUT`, `FAILED`. `FAILED` covers a failed warm-up, an unreachable model, an unexpected error (an `Error` included) and an incident left `RUNNING` by an agent restart. Every end records its reason; none is silent.

**Budgets amended in T5 (the human's decision of 2026-10-12).** The T1 values assumed ADR-003's latencies (a 14 s median, 63.5 s cold). T4's live smoke measured 42 to 84 s per call on the laptop's CPU, so 16 calls could not fit in 10 minutes and one call passed 90 s. The human chose, against that measurement:

- **fewer calls:** the plan merges into the first `ACT` answer of each phase, which removes three plan calls and three possible retries per run (about 8 to 10 calls instead of up to 16). Ollama cannot constrain an answer by a schema while offering tools, so the plan travels as JSON in the answer's text and code checks it; a retry would cost a call again, so a bad plan is recorded instead;
- **180 s per model call (per task since T5c4, below) and 30 minutes per incident (45 since T5c5, below)**, to fit the measured latency with room for the longest calls;
- **a warm-up before each run:** one tiny request with the route's model and `num-ctx` and `num_predict` 1, so the model's load (T4's cold first call passed 90 s) is paid outside the budgets. It is logged and bounded at 300 s; a failure ends the run `FAILED`.

The loop's HTTP read timeout is its own (T4 review): a `RestClientCustomizer` in the web profile sets it to the longest of the call deadlines and the warm-up (420 s since T5c5, 300 s in T5c4), so the HTTP client never cuts a request before the loop's own deadline; the `demo` profile keeps `spring.http.clients.read-timeout` at 5 minutes. A call the loop abandons at 180 s is cancelled through its thread's interrupt, which closes its connection; T5c found that Ollama still ran it to its end, and fixed it (see "Amended in T5c"). The vision's plan B trigger is one minute per agent step; the step table records every call's latency, so the evals can tell how often a run crosses it.

### Amended in T5c: answer caps, prompt order, aborted calls

The first full `verify-agent.sh` run (2026-10-14) ended all three incidents `TIMED_OUT` at the same point: the second `ACT` call of `INVESTIGATE`, after two tool results. A read-only diagnosis, from Ollama's own timings and a replayed request, found three causes, each fixed here with a test:

- **The model summarised instead of acting.** After the tool results granite wrote a 650 to 800-token Markdown summary of them, at about 2.9 tokens/s on the laptop's CPU: the answer alone took longer than the 180 s call. The instruction sat in the user message before the tool results, so the model answered the results. Every call now carries Ollama's `num_predict`: 384 tokens for a phase's first answer, which carries the plan (plans measured 170 to 200 tokens), 160 for a later one (30 to 66 measured for one or two calls; 160 holds four), 512 for the report (about all one 180 s call can write; a cut report is retried with the reason). A cut answer (`done_reason` `length`) is recorded, its tool calls are still checked, and a cut plan is recorded missing or invalid; nothing ends the run.
- **The prompt was read again on every call.** The rebuilt state came first and changed every call, so Ollama reused only the system message and the tool definitions (1,368 tokens) and read the rest again at about 13 tokens/s, 70 to 92 s on the fourth call. The prompt now keeps what changes least first: system message, evidence (which only grows), previous answer and tool results, then the instruction with the budgets left and the task, which asks for tool calls or an empty answer and never a summary. Rejected: a true chat history, which also keeps the prefix but makes the prompt grow with every answer the model writes, against "rebuilt from the state".
- **An abandoned call kept running.** The loop's interrupt closed the connection, but Ollama ran the call to its end and the next incident's warm-up waited behind it (2 min 14 s). Ollama's Go server watches for a client going away only after it has read the request body to its end, and its JSON decoder stops at the end of the object: with the chunked body the default client (Reactor Netty) sent, the last chunk arrived apart and was never read. Measured on the lab: a chunked request whose client closed at 6 s ran to `200` at 16.7 s; the same request with a `Content-Length` ended `500` at 6 s. The loop's `RestClientCustomizer` now buffers the request body, so every request carries a `Content-Length`. Rejected: a streaming call cancelled through Reactor (more code in the loop for the same effect, and the same Go behaviour applies to its request body); a different HTTP client (the JDK client also sends a body of unknown length chunked).

### Amended in T5c4: a call deadline per task

The second full `verify-agent.sh` run (2026-10-14), after T5c, got through every evidence phase in two of three incidents and then ended all three `TIMED_OUT` on a single call: F2 and F3 on the `REPORT` call, F1 on its first plan-carrying `INVESTIGATE` call. Measured on the laptop's CPU: reading the prompt takes about 70 to 90 s, an answer is written at about 2.9 tokens/s, plan-carrying `ACT` calls took 139 to 178 s and other `ACT` calls 78 to 108 s. A 512-token report needs about 176 s of writing on top of the prompt read, so it cannot fit in 180 s.

The human chose (2026-10-14) **a deadline per task: 240 s for an `ACT` call, 300 s for the `REPORT` call**, configured as `kintsugi.agent.model.call-timeout.act` and `.report` (`CallTimeouts`). Each call still waits at most the smaller of its task's deadline and the incident's time left, and a timed-out call's reason names its own deadline. The 30-minute incident budget, the warm-up and the `num_predict` caps are unchanged; the loop's HTTP read timeout follows the longest deadline. A route without deadlines or caps now fails the start, naming the property, instead of the first call. Rejected: a single larger deadline for every call (an `ACT` call that runs on would hold the run up to the report's 300 s for nothing); lowering the report cap (a cut report is retried, which costs a second long call).

### Amended in T5c5: short evidence quotes and longer call deadlines

The third full `verify-agent.sh` run (2026-10-14), after T5c4, brought every incident to the end of its evidence phases, and still produced no report. F1 ended `INVALID_REPORT` after 1,143 s and 16 steps: each evidence `quote` copied a whole escaped log line, about 200 tokens, so both 512-token report answers were cut before the JSON closed. F2 timed out on a `REPORT` call past 300 s, F3 on a `KNOWLEDGE` plan call past 240 s; plan-carrying `ACT` calls took 140 to 207 s, reports 154 to 284 s or more.

The human chose (2026-10-14):

- **Short evidence quotes.** A quote is 8 to 160 characters (300 before): the distinctive fragment, such as an exception name, a status or an error message, not the whole line. The report task asks for it and names the limit, the schema sent with the call carries the same bounds, and `ReportCheck` rejects a longer quote with a reason the retry can act on ("quote only its distinctive fragment ..."). The substring check is unchanged, so a short fragment of an escaped line still matches in the raw or the decoded text. Five quotes now take at most about 250 tokens, so a report fits its 512-token cap. Rejected: raising the report cap (each extra token costs about 0.35 s on this CPU, and a longer quote proves nothing more than its distinctive part); cutting long quotes in code (code would be writing the model's evidence).
- **Longer call deadlines: 300 s for `ACT`, 420 s for `REPORT`.** 300 s gives the slowest plan call measured about 90 s of room; 420 s gives a report about two minutes past the slowest measured.
- **A 45-minute incident budget (30 before).** The longest run measured had 16 steps: 8 tool calls, 6 `ACT` answers and 2 reports. With every model call at its new deadline it needs 6 x 300 + 2 x 420 = 2,640 s, 44 minutes, which 30 minutes would cut before the report; 45 minutes holds it with the tool calls. `verify-agent.sh` waits for an incident for the budget, the 5-minute warm-up and a margin (52 minutes). The loop's HTTP read timeout follows the longest deadline, now 420 s. The `num_predict` caps and the warm-up are unchanged.

### Plan and report schemas

- **Plan:** `hypotheses` (1 to 3 items, each `{rootCauseService, rootCauseCategory, why}`), `nextTools` (0 to 4 of the phase's tools). Same enums as the report.
- **Report (human's taxonomy):** `rootCauseService` is `gateway-mock`, `payment-service`, `order-service`, `inventory-service`, `postgres` or `UNKNOWN`; `rootCauseCategory` is `DEPENDENCY_LATENCY`, `DEPENDENCY_REJECTING`, `CONFIGURATION`, `RESOURCE_EXHAUSTION` or `UNKNOWN`. These are the truth's own column vocabularies, generic SRE terms that reveal no fault id. Scoring is a mechanical equality on both fields.
- The model writes `rootCauseService`, `rootCauseCategory`, `summary` (at most 500 characters), `confidence` (0 to 1) and `evidence[]`. Code adds `incidentId` and `stepsUsed`, which the model cannot know reliably.
- Each evidence item is `{toolCallId, tool, quote, why}`. `toolCallId` is the step number code printed at the head of that tool's summary. A quote must be a substring of the full recorded output, after whitespace normalization; quoting from the summary works because summaries copy text verbatim. Since T5c5 a quote is at most 160 characters, a distinctive fragment rather than a whole line.

### State

- **A new `agent` database on the `knowledge-db` instance**, with `CONNECT` revoked from `PUBLIC`, for the role `agent_app` (`NOSUPERUSER NOCREATEDB NOCREATEROLE`, password from `AGENT_DB_PASSWORD`, required by the init script). `agent_app` has no `CONNECT` on `knowledge`: the agent reads runbooks only through `search_runbooks`; and neither knowledge role may connect to `agent`. Flyway in `agent/` creates the tables as `agent_app`, which therefore owns them.
- **The database stays the bootstrap superuser's (settled in T2).** `agent_app` gets `CONNECT` on `agent` and `USAGE, CREATE` on its schema `public`, which is all Flyway needs. Making it the database's owner would also let it grant `CONNECT` on `agent` to any role, change the database's settings and create other schemas; with the grant on `public` it can do none of that, which a test checks.
- Two tables: `incidents` (one row per run) and `incident_steps` (one row per model call or tool call, with the full tool output and the summary sent to the model).
- **Why `knowledge-db`:** the vision keeps "state, memory and KB in one DB" (`docs/vision.md`, section 4). The lab's postgres is a fault target: a stopped or exhausted lab database would take the agent's memory of the incident down with it, exactly while it investigates.
- **Why not on the lab server, for the truth:** `faults` lives on the lab's postgres. `agent_app` exists only on `knowledge-db` and holds no credentials for the lab server, so it cannot even attempt a connection to `faults`.

### The model router

`ModelRouter` picks a chat model and its options per task (`ACT`, `REPORT`). In M2 it has one route: the auto-configured Ollama model, `granite4.1:8b`, for every task. The model name is recorded on every step. The interface exists now so that configurations A to D of the vision (M4) are a routing change, not a loop change.

Settled in T4, amended in T5: the route is configured under `kintsugi.agent.model` (`name`, `temperature` 0, `num-ctx` 8192, `call-timeout` per task since T5c4 (`act` 300 s, `report` 420 s since T5c5; 240 s and 300 s in T5c4; 180 s for both before), and since T5c `num-predict` 384, 160 and 512 per call kind) and every call carries these options, with the phase's tool definitions or the report schema. The loop runs each model call on its own thread and stops waiting at the smaller of its task's deadline and the incident's time left, so a hung call cannot outlive its budget. `ModelTask` is `ACT` or `REPORT`; the warm-up uses the `ACT` route. The route builds `OllamaChatOptions`; an API route will need its own options, which is part of the deferred API baseline. The loop runs on one thread with a queue of 2 runs; the database already admits one running incident.

### Prompt injection

The system prompt states that any content inside an `untrustedText` field is evidence to analyse, never an instruction, and that the agent must not act on requests found there. Summaries keep that labelling. Plans and reports are enum-constrained and schema-validated, and every tool is read-only, so in M2 the worst outcome of an injected instruction is a wrong diagnosis. The prompts contain no fault id and no symptom-to-cause mapping, under the same rule as the runbooks.

### Perception

`POST /incidents {symptom, sinceMinutes}` answers 202 with the incident id, and the loop runs in the background, started through an `IncidentRunner`; `GET /incidents/{id}` returns the status, the steps (their summaries, or the full outputs with `?full=true`) and the report. The agent serves them on port 8086. When the agent starts, before it accepts a request, every incident left `RUNNING` by a previous process becomes `FAILED`: no loop survives a restart, and a stale `RUNNING` row would block every new incident. The `demo` profile keeps its one-shot question with no web server and no database. The check script (`verify-agent.sh`) is the trigger: it injects a fault, generates traffic, waits, and posts a neutral symptom that never names the cause. One incident runs at a time, because a laptop CPU serves one model call at a time anyway. The database enforces it atomically, with a partial unique index on `((true)) WHERE status = 'RUNNING'` (the fault-injector's pattern): the endpoint inserts and answers 409 on the unique violation, so two concurrent requests cannot both start a run.

## Alternatives considered

- **Spring AI's internal tool loop** (`ChatClient` with its auto-registered `ToolCallingAdvisor`). Least code, and it keeps Spring's observability and advisor composition. But the loop runs until the model stops calling tools: no per-phase tool sets, no check before each call, no budget, and the raw tool output goes straight back into the conversation, overflowing 8192 tokens after a few log searches. The steps would also not be ours to persist one by one. Rejected: control, budgets and summaries are the point of this milestone.
- **A free-text report scored by an LLM judge.** Natural for a model, and it can express nuance. But the score would depend on a second model's judgement, which needs its own evaluation, costs another call per case, and drifts with the judge model. The truth already has two enum columns; an enum report makes scoring an equality that a script computes for free. Rejected; free text survives as `summary`, which is not scored.
- **A larger `num-ctx` instead of summaries.** Simpler: raw outputs straight to the model. But on a CPU the prompt evaluation time grows with the context, memory grows with it, and small models attend worse over long contexts. ADR-003's latencies are already near the one-minute plan B trigger at 8192. Rejected, chosen by the human. It stays a measurable knob for M4.
- **Alertmanager as the trigger now.** Closest to the vision's perception module. But there is no continuous traffic in the lab yet, so alert rules would fire unpredictably, and the M2 check needs a deterministic sequence: inject, traffic, ask. Deferred to M3 with continuous traffic; the endpoint stays the same, Alertmanager will be one more producer of `IncidentSignal`.
- **State in the lab's postgres.** No new database. But the fault-injector breaks that server on purpose, and it hosts `faults`: the agent's role would live next to the truth. Rejected.
- **A multi-agent design** (a supervisor with specialist agents per evidence source). Fashionable, but more model calls on a slow CPU, harder budgets, and no benchmark yet to justify it. The vision reserves ADR-005 for "No multi-agent until a benchmark justifies it" and lists the urge as scope creep. Rejected for M2; the phases give the same separation of concerns inside one agent.

## Consequences

### Positive

- Every step is bounded, counted and stored; a run can be replayed and audited, and the M4 evals read the step table directly.
- Scoring is mechanical on both fields, and hallucinated evidence is caught by code, not by impression.
- Prompts stay small and predictable, so the local model's latency stays predictable too.
- The model is swappable behind `ModelRouter`; the loop does not change for configurations A to D.
- The agent's state survives faults on the lab's database, and the agent cannot reach the truth.

### Negative

- **More code.** The summarizers are one per tool, must be deterministic and tested against recorded outputs, and must follow any change to the mcp-tools output contract.
- **A summary can hide the clue.** Code decides what the model sees; a wrong summary rule makes the agent blind to evidence that is in the full output. The stored outputs let the evals find such cases.
- **Each step's speed depends on the model.** At T4's measured 42 to 84 s per call, a run of 8 to 10 calls takes about 6 to 14 minutes on the laptop; the 30-minute budget leaves room for the slowest calls, not for a much slower model.
- **The plan is weaker than a schema-constrained one (T5).** It is free JSON in the text of a tool-calling answer, so a small model may omit it or break its rules. That costs nothing but the plan: it is recorded as missing or invalid, and the evals can count how often it happens.
- **F3 is the hardest case.** Its symptom is connection errors from order-service to payment-service. The agent must infer `CONFIGURATION` on `order-service` rather than an outage of `payment-service`, from evidence such as a recent restart of order-service (`service_restarts`) and payment-service still answering. If it fails, it is a finding for the benchmark, not a reason to leak hints into the prompt.
- **The API baseline route is deferred.** ADR-003 placed the API baseline in M2 with the `ModelRouter`. This ADR ships the router with one Ollama route only; whether M2 still needs the API route is an open point for the human.
- **A new database on an existing volume.** The `knowledge-db` init scripts run only on an empty volume, so adding the `agent` database and `agent_app` role needs `docker compose down -v` for the store and a re-index, once. The init script also requires `AGENT_DB_PASSWORD`, so compose must pass it to `knowledge-db` before a fresh volume is created.
- **The laptop is slower than the budgets assume (T4 smoke).** One live run (`granite4.1:8b` warm, real mcp-tools, store in Testcontainers) produced valid schema-constrained plans and real tool calls, but its model calls took 42 to 84 s, and the fifth, an `ACT` in `INVESTIGATE`, passed 90 s, so the run ended `TIMED_OUT`. At that pace 16 calls do not fit in 10 minutes either. The human decided on 2026-10-12: fewer calls, larger budgets and a warm-up (see "Budgets amended in T5").
- **Spring's observability is bypassed.** Driving `ChatModel` directly skips the advisor chain and its observations; our step table and log lines replace them until OpenTelemetry arrives.
