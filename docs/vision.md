# Kintsugi — Project Vision and Plan

> Source of truth for *what* we are building and *why*. Written on 2026-10-03 during the planning sessions (ChatGPT for the conceptual guide, Claude for the project design). Specs in `openspec/specs/` refine this document; they do not replace it. If a spec contradicts this file, raise it with the human before proceeding.
>
> Decided on 2026-10-25 and committed on 2026-10-28 after a critique review (journal 2026-10-28-01, `odd/tasks/vision-revision.md`). This commit pre-registers H1–H3.

## 1. Vision and positioning

Kintsugi is a **local-first AI agent that diagnoses incidents in a microservices system**. A fault injector breaks a lab on purpose and records the true root cause, and a public benchmark, RCAEval, adds incidents on systems written by others. Every diagnosis is scored against ground truth. The benchmark shows what the LLM adds over rules, where small open-weight models are enough, and where paying for an API is worth it. (Kintsugi: the Japanese art of repairing broken things. Provisional name.)

**The question the project answers with numbers:** can a small company run a reliable incident-response agent without an API budget, **and does its LLM add anything over the rules that feed it?**

The portfolio does not say "I built an agent". It says "I measured, on incidents from a live lab and a public benchmark, what the LLM adds over rules, what it costs, and where local models are enough".

**Positioning of the author:** not "a developer learning AI", but a **senior backend engineer with 20+ years in production who brings engineering discipline to AI systems**: reliability, integration, safe actions, measurement.

Pitch (CV / LinkedIn):
> Kintsugi is a local-first AI agent that diagnoses incidents in a microservices system. It is scored against ground truth on a lab broken on purpose and on a public benchmark it was never tuned for. The benchmark measures what the LLM adds over the rules that feed it, what it costs, and where small open-weight models are enough.

### Pre-registered claims

These claims are written before any holdout is read, so the numbers cannot be chosen after seeing them. Every outcome is reported in `docs/benchmark.md`, and rules beating the model is a finding.

- **H1, the LLM beats rules.** On the RCAEval holdouts, the paired 95% CI of service-level AC@1, D minus Config 0R, lies above 0. This is the primary test; A minus Config 0R is secondary, and D minus Config 0 and A minus Config 0 are reported as secondary too. If the upper bound is below +5 points, the result reads "rules are enough". The baseline moved from Config 0 to 0R before any RCAEval holdout was read and before D or A ran on RCAEval ([ADR-022](adr/ADR-022-h1-baseline-0r.md)).
- **H2, local is enough.** All three must hold, otherwise local is not enough on this hardware:
  - the paired 95% CI of AC@1(A) − AC@1(D), pooled over lab holdout-2 and the RCAEval holdouts, has its lower bound above −10 points;
  - A's false-alarm rate is at most D's plus 10 points;
  - A's median time to report on this laptop is at most 15 minutes.
- **H3, escalation pays.** C stays within 5 points of D while escalating at most 50% of cases.
- H1–H3 get a Holm correction, because three tests at once raise the chance of a false win. Everything else is labelled exploratory. The verdict procedure (one-sided bootstrap p-values against each margin, intersection-union for multi-condition hypotheses, Holm at 0.025) is fixed in [ADR-031](adr/ADR-031-h1-h3-verdict-procedure.md).

The configurations are defined in §5, the metrics and the protocol in §6.

## 2. Two tracks

The agent is scored on two tracks, both with a known root cause:
- **the lab**, a small e-commerce system broken on purpose, for the live loop: alert, investigation, report, approval;
- **RCAEval**, offline, for incidents on systems written by others, which nobody tuned the agent for.

Why two: the lab alone cannot tell "it diagnoses" apart from "it recognizes a catalog its designer tuned it for".

### The lab: the live loop

The agent does not read invented logs. It investigates a small but real e-commerce system that a fault injector breaks on purpose. This solves the main weakness of demo projects (fake data) and gives the evaluation dataset for free: whoever injects the fault knows the true cause.

- Three Spring Boot microservices: `order-service`, `payment-service`, `inventory-service`.
- `gateway-mock`: simulated payment gateway with configurable latency and errors.
- PostgreSQL.
- `fault-injector`: small REST service. Activates a fault, stores the true cause in a table **the agent cannot read**, then restores.

The lab gains:
- a load generator;
- healthy controls: the same symptom, with a "no fault" truth row;
- fault parameters drawn from declared ranges: F1 latency straddling the callers' read timeouts, F2 429/500/503, F3 wrong port or host, F4 and F8 reservation delays;
- decoy categories, so the taxonomy is wider than the catalog.

Fault catalog:

| Id | Fault | How it is injected | Visible symptom | Difficulty |
|----|-------|--------------------|-----------------|-----------|
| F1 | Slow payment gateway | 10 s latency on gateway-mock | orders stuck in PAYMENT_PENDING | easy |
| F2 | Gateway rejecting | 429 responses from gateway | burst of failed payments | easy |
| F3 | Wrong configuration | wrong service URL via env | connection errors (`ConnectException`) in logs and client metrics, failing fast | easy |
| F4 | DB pool exhausted | pool of 2 + slow query | 500 errors on inventory | medium |
| F8 | Cascade failure | inventory slow | timeouts appear on order, root cause is inventory | hard |

F5–F7 were cut on 2026-10-25: code defects and memory exhaustion are covered by RCAEval RE3 and RE2-mem. F8 keeps its id.

F8 is the most instructive: the loudest symptom is not the cause. It tests whether the agent reasons over dependencies instead of stopping at the first error. It is built from the existing reservation delay and needs the load generator to tell it from F4.

A stated limit: one fault at a time. Simultaneous faults are out of scope: they would cost about 2k lines and give up single-cause scoring.

### Offline track: RCAEval

RCAEval is MIT-licensed: 735 cases on 3 systems written by others, with 17 published non-LLM baselines.

| Split | Cases | What it tests |
|---|---|---|
| Dev | RE2-OB repetition 1, 30 cases | development and tuning |
| Holdout A | RE2-OB repetitions 2–3, 60 cases | same system, new runs |
| Holdout B | RE2-SS, 90 cases | a system never seen |
| Holdout TT | RE2-TT, 90 cases, Config 0, 0R and D only ([ADR-032](adr/ADR-032-m4-holdout-coverage.md)) | a large system (68 services), for H1 |

- TT has 68 services, beyond the local `num-ctx` of 8192. TT and RE3 run on Config 0, 0R, D and BARO (one of the published baselines) only.
- Windows: 10 minutes on each side of `inject_time`, as the published baselines get them. The lab keeps hiding the fault's start (ADR-011); the difference is documented.
- Healthy pseudo-cases come from windows before the injection.
- The truth never reaches the agent.
- A Python converter under `evals/` turns the case files into the case dossier the agent reads. [ADR-020](adr/ADR-020-rcaeval-dataset-converter.md) pins the dataset revision, amends ADR-001's boundary for the converter and fixes the category map.

## 3. Architecture

Core rule: **the LLM proposes, deterministic code verifies at a stated level, the policy authorizes. A rules-only configuration diagnoses from the same facts, so the LLM's contribution is a number.**

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
        │                 │                    ^
   Models (ModelRouter:  PostgreSQL + pgvector  case dossier <──converter── RCAEval case files
   Ollama or API)        (state, RAG)           (offline track)
```

Every agent action that touches the system goes through the MCP server; write actions only after the policy.

### Verification levels

| Level | What deterministic code checks |
|---|---|
| L0 | schema |
| L1 | the cited line exists |
| L2 | the claims fit the topology |
| L3 | the cited fact belongs to the claimed service and changed between the windows, checked on typed line facts |
| L4 | a live read-only probe confirms it (lab only) |

L2 and L3 are annotations, not retries, so a check never steers the model toward an answer. Every report states its level. The benchmark scores "verified-correct", meaning correct at L3 or above.

**Leakage principle.** A rule derived from the lab catalog may stay in the prompt only if Config 0 runs it too. Its transfer is then tested on RCAEval, and a generic-prompt ablation runs on the cloud route. Otherwise the prompt teaches the test, and the LLM gets the credit for the designer's knowledge.

Config 0 is the code diagnosing with rules that ADR-011 rejected for the agent: "the LLM would become redundant". It stays a baseline that measures exactly that redundancy; ADR-016 records it. Only Config H (ADR-019) uses the same rules inside the agent, as proposers the LLM chooses from; H − 0 then measures what the LLM adds once it sees them.

### Modules

Mapping from the 25 circles of the original "AI Agent Architecture" image to code modules:

| Circles in the image | Module | Implementation |
|---|---|---|
| Perception, Input Processing | `perception` | Alertmanager webhook + REST endpoint; everything becomes a normalized `IncidentSignal`. Offline track: RCAEval case files → case dossier |
| Context Understanding, State Tracking | `state` | `IncidentState` persisted in PostgreSQL, one row per loop step |
| Short-term, Long-term Memory | `memory` | short: messages of the current run. Long-term memory was cut on 2026-10-25 |
| Knowledge Base | `knowledge` | runbooks and service docs in Markdown, RAG on pgvector |
| Goal Setting, Planning, Strategy | `planner` | LLM produces a JSON plan, validated against a schema before execution |
| Reasoning, Logical Inference, Heuristics | `reasoning` | Config 0: rules only, in Java, over the same dossier. A baseline; in Config H (ADR-019) the same rules propose candidates |
| Action Selection, Tool Usage, API Integration | `tools` | MCP server: getLogs, getMetrics, getDeployments, read-only queryDb, searchRunbooks, rollback, restart |
| Execution (authorized) | `policy` | every tool has a risk level; write actions wait for human approval |
| Monitoring | `observability` | OpenTelemetry trace of every step: tokens, cost, latency, tools used |
| Output Generation, Communication, Interaction | `report` | JSON report with cited evidence and its verification level, readable text. The live page was cut on 2026-10-25 |
| Learning, Adaptation, Optimization | `evals` (Python) | eval harness, scorer, RCAEval converter, failure analysis, model router tuning |

## 4. Tech stack

Java/Spring for the system, Python only to measure. This is the split a real company would use, and it leverages the author's experience.

| Component | Choice | Why |
|---|---|---|
| Language/framework | Java 21, Spring Boot | author's strength; most enterprise systems to integrate live here |
| LLM abstraction | Spring AI | same interface for Ollama and commercial APIs; tool calling, MCP, vector store |
| Local models | Ollama, open-weight 7–14B | free to run, data never leaves the company |
| API models | one commercial provider, as baseline and fallback | needed as benchmark reference |
| Database | PostgreSQL + pgvector | state and KB in one DB: one less component for an SME |
| Observability | OpenTelemetry, Prometheus, Loki, Grafana | industry standard; the agent reads lab logs/metrics from here |
| Tools | MCP server | standard protocol, reusable from other MCP clients |
| Evals | Python, pandas, matplotlib | CLI scripts, no web service until needed |
| UI | none: the live page was cut on 2026-10-25 | the value is in the backend; the report is JSON plus readable text |
| Infra | Docker Compose, local check scripts | clone and run with one command |

Exact versions of Spring AI and models: verify on day one and record in an ADR. They change every few months.

Hardware constraint: laptop, 32 GB RAM, of which WSL sees 23 GB, **no GPU**. ~8B quantized models for development; ~14B only for overnight benchmarks.

## 5. Model strategy: local first, API when it pays

The agent never calls a model directly: it goes through a `ModelRouter` interface that picks the model. The same code runs in the configurations below, compared on the same cases.

| Config | What it is | Question it answers |
|---|---|---|
| 0 | Rules only, Java, over the same dossier | what do the lab's rules alone get from the same facts, on any system? |
| 0R | Config 0's rules plus R-RES, the resource rule (ADR-021) | what do the rules alone get from the same facts, resources included? H1's baseline (ADR-022) |
| A | Local 8B; report deadline 600 s on the lab, 2100 s on RCAEval (ADR-023) | is laptop hardware enough? |
| B | Local 14B, on a paired subset of about 60 cases | how much does a bigger local model buy? |
| C | Local first, escalating to D on no answer, abstention, a verification level below `k`, or a confidence below `t` (`(k, t)` set on dev by a stated rule, [ADR-030](adr/ADR-030-config-c-escalation-rule.md)). Computed from the paired A and D outputs, with no extra runs | near-D quality at a fraction of the cost? |
| D | `claude-haiku-5-5`, the cheapest API model (ADR-012) | the API reference, and its price |
| H | Hybrid (ADR-019): Config 0's rules propose up to 5 candidates, local qwen3.5:9b with thinking (ADR-018) picks one or UNKNOWN and explains, with no fallback | does the LLM add anything over the rules once it sees them? (H − 0 on RCAEval dev) |
| HR | H over 0R's candidates (ADR-021) | the same question, resources included (HR − 0R on RCAEval dev) |
| D+ | An optional frontier spot-check, about 60 cases, only with the human's go-ahead and a price check | does a frontier model change the picture? |

The hypotheses are pre-registered in §1 (H1–H3) and verified, not assumed. Any result is reported; "small models fail here, and here is why" is valuable to an SME too.

Local is measured, not gated: no milestone check requires a local score. ADR-012's "the only route that can pass M2's check" is amended accordingly.

**Stop rule.** Local-only adaptations end with service-assessment. After that, a change must be track-agnostic and win a paired comparison on RCAEval dev. A local miss on the lab is documented, not chased.

Why: nine features gave no local accuracy gain on F1 or F3. On M0, on the same `dev` recordings, local scored 5/15 [15%, 58%] against cloud 14/15 [70%, 99%] (exact McNemar p ≈ 0.004).

The real engineering is making small models work:
- **Structured output** validated by JSON schema, retry on validation failure.
- **Few tools per step**: the planner picks the phase; in each phase the model sees only 3–4 tools.
- **Deterministic rules** doing the heavy lifting: time correlation with deploys, metric thresholds. Config 0 runs these rules alone, so their share of the result is a number.
- **Short context**: logs filtered and summarized by code before reaching the model.

## 6. Evals and metrics

Every change is judged by a number, not an impression.

Harness loop on the lab:
1. Pick a fault from the catalog, with parameters drawn from its declared range, or a healthy control.
2. Inject it and wait for the alert.
3. Let the agent work until the final report.
4. Compare the report with the true cause recorded by the fault injector.
5. Restore the lab, next case.

On RCAEval there is no injection: the agent reads the converted case dossier, and the scorer compares its report with the dataset's truth.

| Metric | How | Purpose |
|---|---|---|
| AC@1 | the top answer is right: service and category on the lab, service on RCAEval | primary metric |
| AC@3, Avg@5 | RCAEval only, with the evaluator's semantics; the chance level is shown next to them (on the lab's 5 components a random top-3 already hits 60%) | comparison with the published baselines |
| Category accuracy | against a pre-registered map | the kind of fault, across both tracks |
| Coverage, selective accuracy | share of cases with an answer; accuracy on those cases | an agent that abstains instead of guessing |
| False alarms | on healthy cases: UNKNOWN is correct, any cause is a false alarm. UNKNOWN on a fault is an abstention | an agent that cries wolf |
| Brier score | of the top-1 confidence | does the stated confidence mean anything |
| Verified-correct | correct at L3 or above | right for a checked reason |
| L1 rate | every cited line exists (the old "Real evidence") | measures hallucination |
| Cost per correct diagnosis | tokens × price; for local, machine time | config comparison |
| Latency | alert to report | operational usefulness |
| Safety | risky actions executed without approval | must stay 0 |

**Protocol:**
- a measurement plan (hypothesis, cases, metric, decision rule) is committed before every run;
- Wilson 95% CIs on every rate;
- paired comparisons on the same cases, with exact McNemar;
- a cluster bootstrap on RCAEval, whose repetitions are correlated;
- a holdout is read once, and a second read makes the result exploratory;
- 3 cloud repeats per holdout case, since the route has no temperature 0, while the case stays the unit of analysis;
- on dev, a variant is kept only with a net gain of at least 3 cases and no fault class losing more than 1;
- results are committed under `evals/results/`.

Why: with 5 cases per fault, 4/5 has a Wilson 95% CI of [38%, 96%], and a bar of at least 4 of 5 on each of three faults fails a model that is truly 80% accurate 60% of the time. Small samples and repeated looks make noise look like progress.

No remote CI. Before a feature closes, a local check runs Config 0 over every recording plus a 10-case replay.

## 7. Roadmap

Four 3-week milestones, Oct 5 – Dec 27, 2026. Each ends with something working and a passing check, so even an interrupted project stays showable. Move to the next milestone only after the check passes; if one slips, shorten the next one, never the M4 evals.

**M1 — The lab (Oct 5–25)**
- Three services, gateway-mock, PostgreSQL in Docker Compose
- Fault injector with F1–F4 and true cause recorded
- Prometheus, Loki, Grafana (OpenTelemetry traces deferred to the agent in M2+, ADR-008)
- Service runbooks in Markdown
- **Check: every fault F1–F4 is visible in Grafana**

**M2 — Measured honestly (Oct 26 – Nov 15)**
- The protocol and the scorer
- service-assessment closed
- Config 0 v1
- Topology as data
- The RCAEval converter started
- **Check: `docs/benchmark.md` v0 holds the lab holdout-1 table (the 15 existing holdout recordings, read once) for Configs 0, A and D, with AC@1, Wilson CIs and paired differences. One command regenerates it from committed results, and its plan was committed before the run. It passes whatever the local model scores.**

**M3 — Two tracks, frozen (Nov 16 – Dec 6)**
- The ranked report (1–5 candidates)
- Config H, the hybrid (ADR-019)
- The case dossier with resource facts
- RCAEval dev for 0, 0R, A, D and BARO
- Lab realism
- Policy and approval
- The freeze on Dec 1; holdout-2 recorded Dec 1–5 (75 cases)
- L2 and L3; probes last
- **Check: `check-freeze.sh` passes:**
  - the tag exists;
  - the agent, rules, prompt and taxonomy are unchanged since the tag;
  - every holdout-2 recording and its seed postdate the tag;
  - no committed result read a holdout before M4.

**M4 — Benchmark (Dec 7–27)**
- The holdout runs, compared via ModelRouter
- README with numbers, 3-minute demo video
- Technical article: what the LLM adds over rules, local models vs APIs for SMEs
- **Check: `benchmark.md` gives H1–H3 verdicts by the pre-registered rules, from committed results. A covers at least 200 holdout cases and D at least 300. Zero risky actions ran without approval. Any publication is a separate human decision.**

M4 is never cut.

**Budgets.** About 19 of the ~62 nights go to recordings and local runs, which share one CPU. The D runs fit the ~$3.6 left; D+ needs a go-ahead.

**Cut order if time runs short:**
1. Config B;
2. D+ and the cloud repeats;
3. L4 probes;
4. RE3;
5. lab parameters beyond F1 and F2;
6. approved write actions (the policy then refuses every write).

Never cut the protocol, Config 0, Config 0R, the freeze, holdout-2, holdout B, holdout TT on Config 0, 0R and D, `benchmark.md` or the M4 run (ADR-032: A's 200 and D's 300 cases depend on them).

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
│   └── benchmark.md     # the headline: H1–H3 verdicts, results and failure analysis
├── lab/ (order-service, payment-service, inventory-service, gateway-mock, fault-injector)
├── agent/               # Spring Boot: architecture modules
├── mcp-tools/           # MCP server
├── evals/               # Python: the scorer, the RCAEval converter
│   └── results/         # committed results of every measurement
└── infra/               # compose, Prometheus, Grafana, Loki, check scripts
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

Repository and visibility (decided 2026-10-07): the repository stays local and private, with no pushes and no posts. Backups are full `git bundle` files kept outside the laptop. Anything below that involves publishing is a separate human decision, taken later if ever.

Visibility options, not scheduled:
- One final technical article on local vs API benchmark.
- 3-minute demo video: fault injected, report with evidence, human approval.
- One small open-source contribution to Spring AI or the MCP Java SDK.
- A talk at a Buenos Aires meetup once benchmark numbers exist.
- On the CV: the project at the top, one line plus link.

## 10. Risks and plan B

Rule: if time runs short, apply the cut order of §7, never cut the evals. A simple measured agent beats a rich unmeasured one.

| Risk | Signal | Plan B |
|---|---|---|
| Not enough time | two weeks in a row under 8 h | apply the cut order (§7); keep M4 whole |
| Hardware too slow for local models | more than a minute per agent step | develop with a cheap API, local only for benchmark, or hourly cloud GPU |
| Small models fail at tool calling | under half of calls valid | fewer tools per step, more deterministic rules; document the failure in `docs/benchmark.md` |
| Scope creep | urge for multi-agent or fancy UI | ADR-005 says no until a number justifies it |
| Spring AI API changes | upgrades breaking the build | own interfaces isolate the framework; pinned version |
| Motivation drop | a week without commits | milestone checks and the session journal keep the thread; restart from the smallest task |
| The lab becomes the product | a feature closes with no number in `benchmark.md` | write the measurement plan first |
| Rules beat the LLM | Config 0 or 0R matches or beats the LLM | report it in `benchmark.md`; the lab then measures the live loop |
| The RCAEval converter overruns | more than 2 sessions on it | metrics-only RE2-OB |
| Forking paths | a holdout is read twice | label the result exploratory; record a fresh holdout |
| Budget | over $2.5 spent, or nights lost | apply the cut order |

## 11. Week 1 (Oct 5–11)

Goal: fault F1 visible on a Grafana dashboard. ~2 h/day. (Status as of 2026-10-07: done, and F2–F4 too; see `docs/STATE.md`.)

- [x] Mon: verify environment, install Docker and Ollama, pull a ~8B model, make it call one tool from a minimal Spring AI app
- [x] Tue: local repository, README with vision, folder structure; `order-service` skeleton with PostgreSQL
- [x] Wed: `payment-service` and order → payment call
- [x] Thu: `gateway-mock` with latency configurable via REST (fault F1)
- [x] Fri: Docker Compose with Prometheus, Loki and Grafana (OpenTelemetry deferred, ADR-008); dashboard showing F1 when active
- [x] Weekend: ADR-001
