# Kintsugi — Project Vision and Plan

> Source of truth for *what* we are building and *why*. Written on 2026-10-03 during the planning sessions (ChatGPT for the conceptual guide, Claude for the project design). Specs in `openspec/specs/` refine this document; they do not replace it. If a spec contradicts this file, raise it with the human before proceeding.

## 1. Vision and positioning

Kintsugi is a **local-first AI agent that diagnoses incidents in a microservices system**. A fault injector breaks the system on purpose and records the true root cause, so every diagnosis is scored against ground truth. The benchmark shows where small open-weight models are enough and where paying for a frontier API is worth it. (Kintsugi: the Japanese art of repairing broken things. Provisional name.)

**The question the project answers with numbers:** can a small company run a reliable incident-response agent without an API budget?

The portfolio does not say "I built an agent". It says "I measured, on ~200 injected incidents, what works, what it costs, and where local models are enough".

**Positioning of the author:** not "a developer learning AI", but a **senior backend engineer with 20+ years in production who brings engineering discipline to AI systems**: reliability, integration, safe actions, measurement.

Pitch (CV / LinkedIn):
> Kintsugi is a local-first AI agent that diagnoses incidents in a microservices system. A fault injector breaks the system on purpose, so every diagnosis is scored against ground truth. The benchmark shows where small open-weight models are enough and where paying for a frontier API is worth it.

## 2. The scenario: a lab built to break

The agent does not read invented logs. It investigates a small but real e-commerce system that a fault injector breaks on purpose. This solves the main weakness of demo projects (fake data) and gives the evaluation dataset for free: whoever injects the fault knows the true cause.

- Three Spring Boot microservices: `order-service`, `payment-service`, `inventory-service`.
- `gateway-mock`: simulated payment gateway with configurable latency and errors.
- PostgreSQL.
- `fault-injector`: small REST service. Activates a fault, stores the true cause in a table **the agent cannot read**, then restores.

Fault catalog (start with F1, add about one per week):

| Id | Fault | How it is injected | Visible symptom | Difficulty |
|----|-------|--------------------|-----------------|-----------|
| F1 | Slow payment gateway | 10 s latency on gateway-mock | orders stuck in PAYMENT_PENDING | easy |
| F2 | Gateway rejecting | 429 responses from gateway | burst of failed payments | easy |
| F3 | Wrong configuration | wrong service URL via env | connection errors (`ConnectException`) in logs and client metrics, failing fast | easy |
| F4 | DB pool exhausted | pool of 2 + slow query | 500 errors on inventory | medium |
| F5 | Bad deploy | new version with a bug behind a feature flag | errors starting right after deploy | medium |
| F6 | Corrupted data | negative price on a product | validation fails only for some orders | medium |
| F7 | Memory leak | unbounded cache | growing latency, then OOM restart | hard |
| F8 | Cascade failure | inventory slow | timeouts appear on order, root cause is inventory | hard |

F8 is the most instructive: the loudest symptom is not the cause. It tests whether the agent reasons over dependencies instead of stopping at the first error.

## 3. Architecture

Core rule: **the LLM proposes, deterministic code verifies, the policy authorizes.**

```
 Python eval harness ──inject──> Fault injector ──breaks──> Lab (3 services + gateway-mock)
        │                                                        │ logs & metrics
        │ compares report vs true cause                          v
        v                                                Observability (OTel, Prometheus, Loki, Grafana)
 ┌──────────── Agent (Spring Boot) ────────────┐   alert         ^
 │ Perception -> Plan -> Reasoning              │ <───────────────┘ reads
 │      ^                     │                 │                  │
 │ Observation <- Action <- Policy (approval)   │ <──tools──> MCP server (logs, metrics, deploys, rollback)
 └──────────────────────────────────────────────┘
        │                 │                    │
   Models (ModelRouter:  PostgreSQL + pgvector  Live page (SVG diagram
   Ollama or API)        (state, memory, RAG)   lighting up + approvals)
```

Every agent action that touches the system goes through the MCP server; write actions only after the policy.

Mapping from the 25 circles of the original "AI Agent Architecture" image to code modules:

| Circles in the image | Module | Implementation |
|---|---|---|
| Perception, Input Processing | `perception` | Alertmanager webhook + REST endpoint; everything becomes a normalized `IncidentSignal` |
| Context Understanding, State Tracking | `state` | `IncidentState` persisted in PostgreSQL, one row per loop step |
| Short-term, Long-term Memory | `memory` | short: messages of the current run; long: past resolved incidents, similarity search |
| Knowledge Base | `knowledge` | runbooks and service docs in Markdown, RAG on pgvector |
| Goal Setting, Planning, Strategy | `planner` | LLM produces a JSON plan, validated against a schema before execution |
| Reasoning, Logical Inference, Heuristics | `reasoning` | LLM + Java rules (e.g. errors started within 5 min of a deploy → deploy hypothesis) |
| Action Selection, Tool Usage, API Integration | `tools` | MCP server: getLogs, getMetrics, getDeployments, read-only queryDb, searchRunbooks, rollback, restart |
| Execution (authorized) | `policy` | every tool has a risk level; write actions wait for human approval |
| Monitoring | `observability` | OpenTelemetry trace of every step: tokens, cost, latency, tools used |
| Output Generation, Communication, Interaction | `report` + UI | JSON report with cited evidence, readable text, live page |
| Learning, Adaptation, Optimization | `evals` (Python) | eval harness, failure analysis, model router tuning |

## 4. Tech stack

Java/Spring for the system, Python only to measure. This is the split a real company would use, and it leverages the author's experience.

| Component | Choice | Why |
|---|---|---|
| Language/framework | Java 21, Spring Boot | author's strength; most enterprise systems to integrate live here |
| LLM abstraction | Spring AI | same interface for Ollama and commercial APIs; tool calling, MCP, vector store |
| Local models | Ollama, open-weight 7–14B | free to run, data never leaves the company |
| API models | one commercial provider, as baseline and fallback | needed as benchmark reference |
| Database | PostgreSQL + pgvector | state, memory and KB in one DB: one less component for an SME |
| Observability | OpenTelemetry, Prometheus, Loki, Grafana | industry standard; the agent reads lab logs/metrics from here |
| Tools | MCP server | standard protocol, reusable from other MCP clients |
| Evals | Python, pandas, matplotlib | CLI scripts, no web service until needed |
| UI | one HTML page with SVG, updated via Server-Sent Events | no frontend framework: the value is in the backend |
| Infra | Docker Compose, GitHub Actions | clone and run with one command |

Exact versions of Spring AI and models: verify on day one and record in an ADR. They change every few months.

Hardware constraint: laptop, 32 GB RAM, **no GPU**. ~8B quantized models for development; ~14B only for overnight benchmarks.

## 5. Model strategy: local first, API when it pays

The agent never calls a model directly: it goes through a `ModelRouter` interface that picks the model per task type. The same code runs in four configurations, compared on the same incidents.

| Config | Uses | Question it answers |
|---|---|---|
| A. All local small | ~8B for every step | is laptop hardware enough? |
| B. All local medium | ~14B | how much does more RAM / a modest GPU buy? |
| C. Hybrid | local for extraction/classification, API only for final diagnosis | near-API quality at a fraction of the cost? |
| D. All API | a frontier commercial model | the quality ceiling, and its price |

Hypothesis to verify (not assume): C gets close to D at much lower cost. Any result is publishable; "small models fail here, and here is why" is valuable to an SME too.

The real engineering is making small models work:
- **Structured output** validated by JSON schema, retry on validation failure.
- **Few tools per step**: the planner picks the phase; in each phase the model sees only 3–4 tools.
- **Deterministic rules** doing the heavy lifting: time correlation with deploys, metric thresholds.
- **Short context**: logs filtered and summarized by code before reaching the model.

## 6. Evals and metrics

Every change is judged by a number, not an impression.

Harness loop:
1. Pick a fault from the catalog with varied parameters (service, intensity, timing).
2. Inject it and wait for the alert.
3. Let the agent work until the final report.
4. Compare the report with the true cause recorded by the fault injector.
5. Restore the lab, next case.

| Metric | How | Purpose |
|---|---|---|
| Correct diagnosis | root-cause category equals the true one | primary metric |
| Real evidence | every log line / metric cited in the report actually exists | measures hallucination |
| Tool selection | tools used vs tools expected for that fault | where the planner goes wrong |
| Steps and model calls | count from trace | efficiency |
| Cost per incident | tokens × price; for local, machine time | config comparison |
| Latency | alert to report | operational usefulness |
| Safety | risky actions executed without approval | must always be zero |

CI runs a reduced eval (10 cases, recorded model responses) on every PR. The full benchmark (~200 cases per config) runs locally overnight.

## 7. Roadmap

Four 3-week milestones, Oct 5 – Dec 27, 2026. Each ends with something working, a passing check, and a public LinkedIn post, so even an interrupted project stays showable. Move to the next milestone only after the check passes; if one slips, shorten the next one, never the M4 evals.

**M1 — The lab (Oct 5–25)**
- Three services, gateway-mock, PostgreSQL in Docker Compose
- Fault injector with F1–F4 and true cause recorded
- OpenTelemetry, Prometheus, Loki, Grafana
- Service runbooks in Markdown
- Post: "I built a system designed to break"
- **Check: every fault F1–F4 is visible in Grafana**

**M2 — First agent (Oct 26 – Nov 15)**
- MCP server with read-only tools on logs, metrics, deploys
- RAG on runbooks with pgvector
- Loop perception → plan → action → observation, with IncidentState
- JSON report with cited evidence
- Post: the first correct diagnosis, with its step-by-step trace
- **Check: F1–F3 correctly diagnosed with a local model**

**M3 — Safe and observable agent (Nov 16 – Dec 6)**
- Policy: risk levels, human approval for rollback and restart
- Long-term memory of resolved incidents
- Deterministic rules and faults F5–F8
- Live page with the architecture diagram lighting up
- Post: video of the agent asking for approval before acting
- **Check: zero risky actions without approval**

**M4 — Benchmark and launch (Dec 7–27)**
- Python harness, ~200 cases per configuration
- Configs A–D compared via ModelRouter
- Reduced eval in CI, README with numbers, 3-minute demo video
- Technical article: local models vs APIs for SMEs
- Post: benchmark results
- **Goal: public portfolio and first applications**

Out of scope unless a benchmark justifies it: multi-agent architectures (supervisor + specialist agents). If attempted, only as a final measured experiment.

## 8. Repository and documentation

```
kintsugi/
├── README.md            # pitch, demo gif, benchmark table (lead with the headline number)
├── CLAUDE.md            # context + session protocol for the coding agent
├── docs/
│   ├── vision.md        # this file
│   ├── concepts.md      # conceptual guide to agent architecture
│   ├── STATE.md         # where we are, next action (overwritten each session)
│   ├── journal/         # one entry per session, append-only
│   ├── architecture.md
│   ├── adr/             # one decision per file
│   ├── runbooks/        # the knowledge base the agent reads
│   └── benchmark.md     # results and failure analysis
├── lab/ (order-service, payment-service, inventory-service, gateway-mock, fault-injector)
├── agent/               # Spring Boot: architecture modules
├── mcp-tools/           # MCP server
├── evals/               # Python
├── ui/
├── infra/               # compose, Prometheus, Grafana, Loki
└── .github/workflows/
```

First ADRs (about one per week):
- ADR-001: Java/Spring for the system, Python only for evals
- ADR-002: PostgreSQL + pgvector instead of a dedicated vector DB
- ADR-003: Local models as default, APIs as baseline
- ADR-004: Tools exposed via MCP instead of internal calls
- ADR-005: No multi-agent until a benchmark justifies it

## 9. Career strategy

| Role | What they look for | What to show |
|---|---|---|
| AI Engineer | LLM systems working in production, evals | benchmark, ModelRouter |
| Senior Backend Engineer (AI/LLM) | solid backend integrating models | microservices, policy, observability |
| Forward Deployed Engineer | bringing AI into a client's systems, explaining and adapting it | SME thesis, demo, explaining trade-offs |

Market: remote from Argentina; time-zone overlap with US companies is a real advantage. Languages: English, Spanish, Italian.

Visibility:
- One LinkedIn post in English per milestone, with an image/gif and a number. First post in week 1.
- One final technical article on local vs API benchmark.
- 3-minute demo video: fault injected, diagram lighting up, report with evidence, human approval.
- One small open-source contribution to Spring AI or the MCP Java SDK.
- A talk at a Buenos Aires meetup once benchmark numbers exist.
- On the CV: the project at the top, one line plus link.

## 10. Risks and plan B

Rule: if time runs short, cut from the end of M3, never from the evals. A simple measured agent beats a rich unmeasured one.

| Risk | Signal | Plan B |
|---|---|---|
| Not enough time | two weeks in a row under 8 h | drop long-term memory and write actions; keep M4 whole |
| Hardware too slow for local models | more than a minute per agent step | develop with a cheap API, local only for benchmark, or hourly cloud GPU |
| Small models fail at tool calling | under half of calls valid | fewer tools per step, more deterministic rules; document the failure as a post |
| Scope creep | urge for multi-agent or fancy UI | ADR-005 says no until a number justifies it |
| Spring AI API changes | upgrades breaking the build | own interfaces isolate the framework; pinned version |
| Motivation drop | a week without commits | public milestone posts create commitment; restart from the smallest task |

## 11. Week 1 (Oct 5–11)

Goal: fault F1 visible on a Grafana dashboard, and the first post published. ~2 h/day.

- [ ] Mon: verify environment, install Docker and Ollama, pull a ~8B model, make it call one tool from a minimal Spring AI app
- [ ] Tue: public GitHub repo, README with vision, folder structure; `order-service` skeleton with PostgreSQL
- [ ] Wed: `payment-service` and order → payment call
- [ ] Thu: `gateway-mock` with latency configurable via REST (fault F1)
- [ ] Fri: Docker Compose with OpenTelemetry, Prometheus, Grafana; dashboard showing F1 when active
- [ ] Weekend: ADR-001, first LinkedIn post with the dashboard screenshot
