# Concepts — AI Agent Architecture, explained

> Learning companion for Kintsugi. Condensed from a conceptual guide written with ChatGPT on 2026-10-03, based on the "AI Agent Architecture" image (25 circles around a central "AI Agent"). That image is a **conceptual map of capabilities**, not a standard architecture. This file explains each concept and points to where Kintsugi implements it (see `vision.md`, section 3).

## The core idea

A chatbot mainly *answers*. An agent can **perceive → understand → reason → plan → act → observe the result → correct its behavior**, in a loop, until a goal is reached.

```
input -> understand -> decide -> plan -> act -> observe -> decide again -> ...
```

**An agent is not an LLM.** The LLM is one component the agent uses (to understand language, extract, classify, reason, choose tools, write plans). The agent is the system that coordinates:

```
LLM + memory + state + tools + knowledge + planning + policies + execution + observation + feedback
```

One-sentence definition to remember:
> An AI agent is a software system that perceives an environment, keeps state and context, uses knowledge and AI models to reason and plan, selects and executes actions through tools, observes the results and continues the loop until the goal is reached, within defined policies and constraints.

Mental pipeline:
```
GOAL -> PERCEIVE -> CONTEXT -> REASON (<- knowledge, memory) -> PLAN -> POLICY -> ACT (-> tools/APIs) -> OBSERVE -> EVALUATE -> repeat
```

## The concepts, one by one

**Perception** — receiving signals from the environment: API calls, events, messages, files, logs, user input. The agent does not need to understand the signal yet. *Kintsugi: Alertmanager webhook + REST endpoint.*

**Input Processing** — turning raw input into something usable: free text → structured JSON (`{"event":"PAYMENT_FAILED","paymentId":"12345"}`), PDF → text → chunks → metadata. A normalization layer. *Kintsugi: `IncidentSignal`.*

**Context Understanding** — "what is really happening?" Enriching the input with data from conversation, database, memory, docs, APIs, system state. The LLM helps but must not be the only source of truth.

**State Tracking** — what the agent knows about the current operation: task, status, attempt, last action, last result, next step. Without it, a multi-step agent "forgets what it already did". *Kintsugi: `IncidentState` persisted per step.*

**Short-term memory** — context of the current task or conversation.
**Long-term memory** — information that survives a single run: past incidents, known solutions, learned patterns. Stored in relational DB, document store, vector DB or graph.

**Memory ≠ Knowledge Base.** Knowledge is generally valid ("when error X appears, check Y"). Memory is about experience ("customer 123 already had error X on 12/09").

**Knowledge Base** — documentation, runbooks, API docs, business rules, incident reports. Typically accessed via **RAG** (query → retriever → vector DB → relevant docs → LLM). In an agent, RAG is a *tool the agent uses*, not the agent itself. *Kintsugi: runbooks on pgvector, `searchRunbooks` tool.*

**Reasoning** — using available information to reach a conclusion or decide the next step. In serious systems: LLM reasoning **+** deterministic business logic **+** rules **+** queries **+** external evidence. Never accept "the model thought that…" as automatically true.

**Logical Inference** — deterministic reasoning: `IF payment FAILED AND gateway DOWN THEN cause = GATEWAY`. Implemented in Java, rules engines, SQL, decision tables, policy engines. Very important in enterprise AI. *Kintsugi: Java rules in `reasoning`.*

**Heuristics** — practical rules that help decide without guaranteeing optimality: "if > 50 customers affected → high severity", "if retrieval confidence < threshold → ask for clarification".

**Goal Setting** — what the agent is trying to achieve, ideally with constraints: "restore payment service *without losing transactions and without modifying production data*".

**Planning** — which steps reach the goal. An agent can build the plan dynamically; that is what distinguishes it from a static workflow. *Kintsugi: JSON plan validated by schema.*

**Strategy** — a level above planning: which *approach* to take (database first? external API first? knowledge base first?). The planner decides steps; the strategy decides how to attack the problem.

**Action Selection** — which action now, among available tools. In industrial systems this decision is subject to policy and authorization.

**Tool Usage** — the agent calls functions (`get_customer`, `search_documents`, `create_ticket`…). The LLM chooses the tool and its parameters: *tool calling / function calling*. *Kintsugi: tools exposed through an MCP server.*

**Execution** — key distinction: *the agent decides "restart service"* ≠ *the service actually restarts*. The first is a decision; the second is a side effect. In enterprise software this separation is essential. *Kintsugi: decision → policy → authorization → tool → execution.*

**Monitoring** — after acting, observe what happened (service up? error rate down?). Success → continue; failure → revise the plan and loop.

**Learning** — a vague word in the image. It does **not** mean the LLM retrains itself after each conversation. Realistically: observe → evaluate → collect feedback → improve prompts, retrieval, tool descriptions, rules, policies. Fine-tuning or RL only much later, if ever. *Kintsugi: the eval harness and failure analysis.*

**Adaptation** — changing behavior based on results: strategy A failed → try B; low retrieval confidence → switch to hybrid search.

**Communication** — with users, systems, other agents (multi-agent). Order of learning: single agent → agent + tools → agent + RAG → only then multi-agent.

**Interaction** — the channel (chat, API, CLI, web UI, events). Not to be confused with the intelligence: the UI is interaction, Spring orchestrates, the agent reasons.

**API Integration** — where the agent leaves the "LLM world" and touches real systems: REST, databases, Kafka, Git, Jira, Slack, cloud services. This is where a project becomes serious software engineering.

**Output Generation** — turning internal results into something usable. Best practice: a structured result first, then human-friendly text.
```json
{"status":"RESOLVED","rootCause":"Gateway timeout","confidence":0.91,
 "evidence":["log-123","gateway-status-456"],"actions":["retry_payment"]}
```

**Optimization** — improving cost, latency, quality, number of model calls, tool calls, tokens. Example: an agent going from 6 LLM calls to 2 with the same result. *This is AI engineering, not prompt engineering.* *Kintsugi: ModelRouter and the A–D benchmark.*

## Two cross-cutting ideas the image does not show

**Policies** — what turns a toy into an enterprise system. The LLM may *propose* "restart production"; a policy engine checks role, environment and approval before any tool runs.

**Observability and evaluation** — record every run (LLM calls, tokens, latency, tool calls, errors, retrieval results, decisions, final answer), then measure against a dataset: correct diagnosis, tool-selection accuracy, retrieval precision, citation correctness, hallucination rate, latency, cost. Without this, you cannot tell whether a change made the agent better or worse.

## Measuring an agent honestly

An eval only helps if its number can say "no". These habits keep it able to. *Kintsugi: ADR-015 and the scorer in `evals/`.*

**Baseline** — a score means little without something to beat. If plain rules over the same facts already get 90%, an agent at 92% adds almost nothing. *Kintsugi: Config 0, rules only, runs over the same dossier as the models.*

**Confidence intervals** — 4 right out of 5 is not "80%"; it is "somewhere between 38% and 96%". The interval says how much the next run could move the number, and on small samples that is a lot. *Kintsugi: a Wilson 95% interval on every rate.*

**Paired comparison** — when two configs run the same cases, compare them case by case. Only the cases where they disagree carry information; the easy and the impossible cases cancel out. *Kintsugi: exact McNemar on the discordant cases, and a cluster bootstrap when a case is repeated, so repeats do not pose as new evidence.*

**Pre-registration and holdout** — decide the hypothesis, the cases and the decision rule before seeing the numbers. Tuning on a set and then reporting that set measures the tuning, not the agent. A holdout is read once, at the end; a second look makes it exploratory. *Kintsugi: a plan committed before every run; H1–H3 written before any holdout is read.*

**Leakage** — the answer reaching the agent through a side door: a prompt that names the discriminating symptom, a taxonomy that is exactly the test catalog, a test case the designer looked at while tuning. The score then measures recognition, not diagnosis. *Kintsugi: RCAEval cases on systems nobody tuned for, and decoy categories in the lab.*

**Calibration and abstention** — an agent that says "I don't know" when it does not know is more useful than one that always guesses. So abstaining is scored apart from being wrong, healthy cases test whether it cries wolf, and its stated confidence is checked against how often it is right. *Kintsugi: UNKNOWN on a fault is an abstention, any cause on a healthy case is a false alarm, plus coverage, selective accuracy and the Brier score.*

## Suggested learning chain

```
LLM -> prompting -> structured output -> embeddings -> RAG -> tool calling -> state -> memory
    -> planning -> agent loop -> policies -> observability -> evaluation -> production
    -> (advanced RAG: hybrid, reranking, corrective) -> (multi-agent, only if justified)
```

Study principle: do not study "AI agents" as an isolated technology. Study **how to build software systems that use AI models as decision-making components**.
