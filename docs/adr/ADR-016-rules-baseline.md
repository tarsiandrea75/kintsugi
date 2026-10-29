# ADR-016: Config 0, a rules-only diagnosis over the dossier

## Status

Accepted. Thresholds and rule order are fixed by this ADR before any Config 0 run. Clarified on 2026-10-29, still before any run: the Healthy test uses incident-window log counts, which are the only ones the dossier holds, and timeouts, missing baselines and entry services now have stated rules. A later change needs a revision of this ADR, and the dev result measured after it is labelled exploratory.

## Date

2026-10-29

## Context

The vision (§1, H1) asks what the LLM adds over the rules that feed it. Nothing measures that today.

- The prompt already carries the rules a diagnosis needs. `Prompts.java:60-67` says "follow the calls down to the last component that is slow or refuses" (the F1 and F2 discriminator). It also says an unreachable but healthy callee means "a CONFIGURATION of" the caller (the F3 discriminator).
- The doc comment at `Prompts.java:15-16` claims that nothing in the prompt "maps a symptom to a cause". The prompt contradicts that claim.
- The leakage principle (vision §3): a rule derived from the lab catalog may stay in the prompt only if Config 0 runs it too. A model that only re-applies its prompt's rules would then score no better than Config 0, and the paired difference A − 0 and D − 0 shows it.
- Config 0 must read the same facts as the model: the typed `Dossier` (`dossier/Dossier.java`) and the call graph (`dossier/CallGraph.java`).

## Decision

### Shape

- `dev.kintsugi.agent.rules.RulesDiagnosis.diagnose(Dossier)` returns a `RulesVerdict`. The verdict holds `service`, `category`, `rule` (the rule that fired) and `facts`. The facts name the dossier rows behind the verdict, so every answer can be checked by hand.
- It is a pure function: no Spring, no I/O, no model, no clock.
- It reads the record, never the rendered text, so the `tables` and `sheets` views cannot change its answer.
- Its answers use the agent's taxonomy (`loop/Causes.java`). The scorer compares them exactly like a model report.

### Definitions

Only the incident window is judged against the previous window. Actuator URIs (`/actuator/**`) are ignored. A dossier section that failed to gather (`Gathered.Failed`) counts as no evidence.

- **Answers of a service:** its server traffic rows in the incident window. A service with no server rows in the incident window uses its callers' client rows to it in that window instead. An answer is an error answer only when its status is numeric and ≥ 400. A non-numeric status such as `CLIENT_ERROR` never is.
- **Timeout:** an exception whose class name contains `Timeout`, such as `HttpTimeoutException`. It is checked before the connection-failure match.
- **Slow:** a service is slow when either holds:
  - one of its non-actuator URIs has an incident mean latency ≥ 2× its previous mean and ≥ 0.5 s. A URI with no previous mean, or a previous mean of 0, cannot be slow by latency. The service's latency ratio is the largest incident-to-previous ratio over its non-actuator URIs, and 0 when no URI has a baseline;
  - its callers' client rows to it carry ≥ 3 timeout exceptions in the incident window.
- **Refusing:** answers with status ≥ 400 make up ≥ 20% of the service's answers, and there are ≥ 3 of them. A timeout is not an error answer.
- **Connection failure:** a client row from caller A to callee B whose exception is a connection refusal or close, or an unknown host. Matching is by exception class name: `ConnectException`, `UnknownHostException`, `ClosedChannelException`, `NoRouteToHostException`, or a name containing `Connection`. A timeout is never a connection failure, even when its name matches.
- **Healthy:**
  - no restart in the incident window;
  - no WARN or ERROR log line in the incident window (a failed count is no evidence). The dossier gathers log counts for the incident window only, so there is no previous window to compare against. This is stricter than a rise: a callee with background WARNs is not healthy, and R-REACH then does not fire;
  - not refusing;
  - not slow.

### Rules, in order

The first rule that fires gives the verdict.

1. **R-REACH (the F3 rule).** Some caller A has ≥ 1 connection failure to a callee B, and B is healthy. The verdict is `A|CONFIGURATION`. If several callers qualify, the one with the most connection failures wins, and ties go to `CallGraph.SERVICES` order.
2. **R-DOWN (the F1 and F2 rule).**
   - Start from the entry services, the ones whose `CallGraph.calledBy` contains `CallGraph.EXTERNAL_CLIENT` (today only order-service), in `CallGraph.SERVICES` order. The first entry whose walk ends on a slow or refusing component gives the verdict. Move to a callee of the current service while that callee is slow or refusing. If several callees qualify, take the one with the larger refusing share, then the larger latency ratio, then `CallGraph.SERVICES` order.
   - The answer is the last slow or refusing component reached, including the entry service when it is the only one.
   - The category is `DEPENDENCY_REJECTING` when that component is refusing and `DEPENDENCY_LATENCY` otherwise. An error answer is the more specific fact.
   - "An answer that takes long is that component's own failure even when it ends in success" (`Prompts.java:63`), so slow needs no error.
3. **R-NONE.** No rule fired. The verdict is `UNKNOWN|UNKNOWN`, following the prompt's own instruction: "Prefer UNKNOWN to a guess."

`RESOURCE_EXHAUSTION` is defined in the prompt but has no discriminator there. Config 0 never returns it. This is a recorded limit, not a gap to fill.

### Where it runs

- Config 0 is a baseline, never the agent's answer. `AgentRoute`, `ModelRoute`, `RouteEnvironmentPostProcessor` and `LoopConfig` stay closed to it, and `AgentRoute.of("rules")` keeps throwing.
- Replay gains a `rules` route. It gathers the dossier from the recording, diagnoses it with `RulesDiagnosis`, and writes the same incident JSON and `summary.tsv` row as a model route. The row has route `rules`, model `rules-v1`, 0 tokens and cost 0.
- Config 0 runs alongside every measurement (CLAUDE.md, vision §6).

## Alternatives considered

- **Thresholds tuned on dev.** Rejected. A Config 0 fitted to the 15 dev cases would overstate rules and turn H1 into a test of the tuning.
- **Rules over the rendered text.** Rejected. Parsing text would tie Config 0 to one view, and a formatting change would move its score.
- **A production `rules` route, as a fallback when the model fails.** Rejected for now. It would make Config 0 an answer, which this ADR forbids. Escalation is config C's job (vision §5).
- **Learned baselines such as BARO.** Kept for RCAEval (follow-up #9). On the lab they would need training data the lab does not have.

## Consequences

### Positive

- H1 becomes a paired number on the same cases.
- A leaked rule is measured, not hidden: if A or D scores about the same as Config 0, the prompt is doing the diagnosis.
- Config 0 runs in seconds on any machine, with no key and no Ollama, so it can run alongside every replay.

### Negative

- The rules mirror the current lab and its prompt. On RCAEval they will be weaker by design, and that is the point of H1 there.
- The thresholds are judgement calls fixed a priori. A miss caused by a threshold is recorded, not fixed on dev.
- `RESOURCE_EXHAUSTION` cases always score as misses for Config 0.
