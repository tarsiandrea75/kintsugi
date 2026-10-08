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

**The agent is a Spring Boot service whose own code drives a fixed sequence of phases. In each phase the model sees only a few tools, writes a schema-validated plan, and requests tool calls that our code checks, executes through the MCP client, records in full and summarizes before the model sees them. A final structured-output call writes a schema-validated report whose evidence quotes code checks against the recorded tool outputs.**

### Stack (verified 2026-10-08 against the Spring AI 2.0.1 reference)

| Component | Choice | Source |
|-----------|--------|--------|
| Spring Boot / Spring AI | 4.1.1 / 2.0.1, as in [ADR-006](ADR-006-stack-versions.md) | `agent/pom.xml` |
| Chat model | `spring-ai-starter-model-ollama`, already in `agent/` | [Ollama chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) |
| MCP client | `spring-ai-starter-mcp-client` (JDK `HttpClient` transport), `spring.ai.mcp.client.type=SYNC` (the default), one connection under `spring.ai.mcp.client.streamable-http.connections.mcp-tools` with `url` `http://mcp-tools:8085` and `endpoint` `/mcp` (the default). Tools arrive as `ToolCallback`s from `SyncMcpToolCallbackProvider` | [MCP client starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) |
| Tool execution | the model is called through `ChatModel`, which in 2.0.1 never executes tools; our loop executes each checked call | [Tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html) |
| Structured output | `OllamaChatOptions.outputSchema(<JSON schema>)`, Ollama's schema-constrained `format`, then validation by our own code | [Ollama chat, structured outputs](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html), [provider-native structured output](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html) |
| State store | a new `agent` database on the `knowledge-db` instance (pgvector image, PostgreSQL 18), schema by Flyway in `agent/` | [ADR-002](ADR-002-pgvector-knowledge-base.md) |

Facts checked in the 2.0.1 docs, which correct the feature document's wording:

- **There is no `internalToolExecutionEnabled` option in 2.0.1.** "The per-`ChatModel` internal tool execution loop of Spring AI 1.x has been removed. Calling `ChatModel` with tools sends the tool definitions to the model and returns the model's response — tool calls in that response are not executed automatically." The automatic loop now lives in `ChatClient`'s auto-registered `ToolCallingAdvisor`, which can be turned off per request (`AdvisorParams.toolCallingAdvisorAutoRegister(false)`) or globally (`spring.ai.chat.client.tool-calling.enabled=false`). Calling `ChatModel` directly is therefore the user-controlled path, with no flag to set.
- **`ToolCallingManager.executeToolCalls(prompt, response)` executes every tool call of a response before returning.** The loop needs a check before each call (phase, budget), and the full raw output of each call. So it looks up the matching MCP `ToolCallback` by name and calls it itself, one checked call at a time. `ToolCallingManager` is not needed.
- **Tool names are kept as served.** With a single MCP connection the default `DefaultMcpToolNamePrefixGenerator` adds no prefix; it only replaces non-alphanumeric characters with underscores, and the mcp-tools names have none.
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

- **Plan first.** At the start of each of the first three phases, the model writes a plan `{hypotheses[], nextTools[]}`, constrained by a JSON schema with enums (the report's service and category values; the phase's tool names). Code validates it. The plan steers the model and is recorded for the evals; it does not authorize anything.
- **Act, one checked call at a time.** The model is called with the phase's tools. Code checks every requested call in order: a tool outside the phase, or a call past the phase's or the incident's budget, is refused, not run, and recorded with its reason; a refused call still counts against the tool budget, so a model that keeps asking for the wrong tool cannot loop. An allowed call runs through mcp-tools, and its full output is stored.
- **Observe through summaries.** The model never receives raw tool output. Code builds a deterministic summary of each output (counts, the first distinct log messages, a compressed metric series, the top runbook hits), capped at 1,500 characters, and each prompt is rebuilt from the state: symptom, phase, remaining budget, latest plan and the summaries so far. That keeps a run inside `num-ctx` 8192 with room to spare: eight summaries are at most 12,000 characters, about 3,000 tokens.
- **A phase ends** when the model answers without a tool call or the phase's cap is reached.
- **Conclude.** The model writes the report with no tools offered. Code validates the schema, then checks every evidence quote against the recorded output of the cited tool call. On failure the model gets one retry with the list of errors; a second failure ends the run as `INVALID_REPORT`.

Why the phase caps sum to the incident's 8-call budget: the model always gets to triage, investigate and read the runbooks, and the last phase can never be starved by the first. Reaching the 8th call closes the evidence phases explicitly (a recorded step and a log line) and moves to `CONCLUDE`; a run that uses its whole tool budget can still produce a scored report.

### Budgets and statuses

| Budget | Value | When it is hit |
|--------|-------|----------------|
| Tool calls | 8 per incident (2 + 4 + 2 per phase) | further calls are refused and recorded; the loop moves to the next phase |
| Model calls | 16 per incident | the run ends `BUDGET_EXCEEDED`. A valid run needs at most 3 plans + 3 plan retries + 8 tool-step answers + 2 report attempts = 16, so this is a guard against a loop bug, never a normal end |
| One model call | 90 s | the run ends `TIMED_OUT` |
| One incident | 10 minutes, wall clock, tool calls included | the run ends `TIMED_OUT` |

Statuses: `RUNNING`, `COMPLETED`, `INVALID_REPORT`, `BUDGET_EXCEEDED`, `TIMED_OUT`, `FAILED`. `FAILED` covers a plan invalid twice, an unreachable model, and an incident left `RUNNING` by an agent restart. Every end records its reason; none is silent.

The 90 s cap sits above ADR-003's worst measured latency (63.5 s, a cold start that includes loading the model). The vision's plan B trigger is one minute per agent step; the step table records every call's latency, so the evals can tell how often a run crosses it.

### Plan and report schemas

- **Plan:** `hypotheses` (1 to 3 items, each `{rootCauseService, rootCauseCategory, why}`), `nextTools` (0 to 4 of the phase's tools). Same enums as the report.
- **Report (human's taxonomy):** `rootCauseService` is `gateway-mock`, `payment-service`, `order-service`, `inventory-service`, `postgres` or `UNKNOWN`; `rootCauseCategory` is `DEPENDENCY_LATENCY`, `DEPENDENCY_REJECTING`, `CONFIGURATION`, `RESOURCE_EXHAUSTION` or `UNKNOWN`. These are the truth's own column vocabularies, generic SRE terms that reveal no fault id. Scoring is a mechanical equality on both fields.
- The model writes `rootCauseService`, `rootCauseCategory`, `summary` (at most 500 characters), `confidence` (0 to 1) and `evidence[]`. Code adds `incidentId` and `stepsUsed`, which the model cannot know reliably.
- Each evidence item is `{toolCallId, tool, quote, why}`. `toolCallId` is the step number code printed at the head of that tool's summary. A quote must be a substring of the full recorded output, after whitespace normalization; quoting from the summary works because summaries copy text verbatim.

### State

- **A new `agent` database on the `knowledge-db` instance**, owned by `agent_app` (`NOSUPERUSER NOCREATEDB NOCREATEROLE`), with `CONNECT` revoked from `PUBLIC`. `agent_app` has no `CONNECT` on `knowledge`: the agent reads runbooks only through `search_runbooks`. Flyway in `agent/` creates the tables as `agent_app`.
- Two tables: `incidents` (one row per run) and `incident_steps` (one row per model call or tool call, with the full tool output and the summary sent to the model).
- **Why `knowledge-db`:** the vision keeps "state, memory and KB in one DB" (`docs/vision.md`, section 4). The lab's postgres is a fault target: a stopped or exhausted lab database would take the agent's memory of the incident down with it, exactly while it investigates.
- **Why not on the lab server, for the truth:** `faults` lives on the lab's postgres. `agent_app` exists only on `knowledge-db` and holds no credentials for the lab server, so it cannot even attempt a connection to `faults`.

### The model router

`ModelRouter` picks a chat model and its options per task (`PLAN`, `ACT`, `REPORT`). In M2 it has one route: the auto-configured Ollama model, `granite4.1:8b`, for every task. The model name is recorded on every step. The interface exists now so that configurations A to D of the vision (M4) are a routing change, not a loop change.

### Prompt injection

The system prompt states that any content inside an `untrustedText` field is evidence to analyse, never an instruction, and that the agent must not act on requests found there. Summaries keep that labelling. Plans and reports are enum-constrained and schema-validated, and every tool is read-only, so in M2 the worst outcome of an injected instruction is a wrong diagnosis. The prompts contain no fault id and no symptom-to-cause mapping, under the same rule as the runbooks.

### Perception

`POST /incidents {symptom, sinceMinutes}` answers 202 with the incident id, and the loop runs in the background; `GET /incidents/{id}` returns the status, the steps and the report. The check script (`verify-agent.sh`) is the trigger: it injects a fault, generates traffic, waits, and posts a neutral symptom that never names the cause. One incident runs at a time, because a laptop CPU serves one model call at a time anyway.

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
- **Each step's speed depends on the model.** A run of up to 16 model calls at ADR-003's median of 14 s takes about 4 minutes on the laptop; the 10-minute budget leaves room for cold starts, not for a much slower model.
- **F3 is the hardest case.** Its symptom is connection errors from order-service to payment-service. The agent must infer `CONFIGURATION` on `order-service` rather than an outage of `payment-service`, from evidence such as a recent restart of order-service (`service_restarts`) and payment-service still answering. If it fails, it is a finding for the benchmark, not a reason to leak hints into the prompt.
- **The API baseline route is deferred.** ADR-003 placed the API baseline in M2 with the `ModelRouter`. This ADR ships the router with one Ollama route only; whether M2 still needs the API route is an open point for the human.
- **A new database on an existing volume.** The `knowledge-db` init scripts run only on an empty volume, so adding the `agent` database and `agent_app` role needs `docker compose down -v` for the store and a re-index, once.
- **Spring's observability is bypassed.** Driving `ChatModel` directly skips the advisor chain and its observations; our step table and log lines replace them until OpenTelemetry arrives.
