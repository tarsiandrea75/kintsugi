# Feature: cloud-route

Locator: `odd/tasks/cloud-route.md` · Engram mirror: `odd/cloud-route/tasks` · First branch: `feat/cloud-route` (from `feat/evidence-dossier-06` at `47d4269`)

## Objective

Add the development route of ADR-012 (Accepted 2026-10-18): the agent's loop can run on Anthropic's `claude-haiku-5-5` through Spring AI, selected by configuration, so design questions get answers in minutes. The local Ollama route stays the default and the only route that can pass M2's check.

## Problem and why

- On this CPU one full `verify-agent.sh` run takes 2 to 3 hours and measures prompt-reading speed before design. T6 of `evidence-dossier`: dossier 0/9, explorer 3/9; 8 of the 9 dossier misses never reached a diagnosis (timeouts, reports cut at 512 tokens).
- The vision's plan B for "Hardware too slow for local models" is "develop with a cheap API, local only for benchmark" (`docs/vision.md`, risks table). ADR-012 chose the provider and the model.
- The steps do not record token counts, so T6 could only infer that prompt reading caused the latency. Recording the model's usage on every model step fixes that for both routes, and gives the cloud route its cost.

## Scope

- In:
  - the token usage of every model call, recorded on its step (both routes);
  - the Anthropic route: dependency, route options, selection property, timeouts, the route recorded on the incident and the report;
  - `verify-agent.sh --route local|cloud` and `compare-agent-modes.sh --route`, compose wiring for the key;
  - living spec `openspec/specs/agent/spec.md`, ADR-010 amendment, `docs/setup.md`;
  - one smoke run on the cloud route and the explorer/dossier comparison on it (each batch with the human's go-ahead and its cost recorded).
- Out:
  - per-task routing (a different model for `ACT` and `REPORT`, vision configuration C): M4;
  - the replay harness (ADR-012, a candidate next feature);
  - prompt caching on the Anthropic route (an optimization, only if the measured cost justifies it);
  - any change to the local route's behavior.

## Constraints

- The local route's requests stay byte-identical: the same options, the same warm-up.
- No API key in the repository: it comes from `infra/.env` (git-ignored) as `ANTHROPIC_API_KEY`. Tests never reach the API; they use the scripted fake chat model.
- Core rule unchanged: the model proposes, the deterministic checks (`PlanCheck`, `ReportCheck`) verify, on both routes.

## Design

- **Selecting the route.** A new property `kintsugi.agent.route` (`${KINTSUGI_AGENT_ROUTE:local}`), `local` or `cloud`; any other value stops the start with the property's name, like `kintsugi.agent.mode`. Spring AI picks the chat model with `spring.ai.model.chat` (`ollama` or `anthropic`), so only one `ChatModel` bean exists and Anthropic's auto-configuration, with its required API key, is off on the local route. Both are set from the one environment variable in `application.yaml`. To verify in T2: that `spring.ai.model.chat` disables the other provider's auto-configuration in Spring AI 2.0.1.
- **The cloud route's options (`AnthropicChatOptions`).**
  - `model` `claude-haiku-5-5`;
  - no `temperature`: Haiku 5.5 rejects a non-default sampling value with HTTP 400;
  - thinking disabled, allowed at `effort` `high` or below. Thinking tokens count against `max_tokens`, so with thinking on, the answer caps (`num-predict`: 384 first `ACT`, 256 `ACT`, 512 `REPORT`) would be consumed by reasoning, and the caps would no longer mean the same thing on both routes;
  - `maxTokens` = the same answer caps (`num-predict` in `application.yaml`: 384 first `ACT`, 160 `ACT`, 512 `REPORT`; the 256 written here before T3 was wrong);
  - `outputSchema` for the report;
  - the tool callbacks as today.
  - Risk to watch in T5: with thinking disabled, a model may write a tool call as text instead of a tool-use block. The loop already treats "no tool call" as a retry; count how often it happens.
- **Route abstraction.** `ModelRoute` builds Ollama options today. It becomes a small interface with two implementations, `OllamaRoute` (today's code, unchanged output) and `AnthropicRoute`. Each one answers `options(answerCap, tools, outputSchema)` and `warmUpOptions()`, or "no warm-up" for the cloud route, where there is no model to load. `ModelRouter` picks one at start from `kintsugi.agent.route`.
- **Deadlines and timeouts.** The cloud route has its own `call-timeout` group (proposed: `act` 60 s, `report` 90 s; first measured in T5), under `kintsugi.agent.cloud`. The loop's `RestClientCustomizer` sets the read timeout of Spring's `RestClient` only. Spring AI's Anthropic client has its own `spring.ai.anthropic.timeout` (default 60 s), which is set to the route's longest call deadline so the loop's deadline, not the HTTP client, ends a call. `max-retries` is 0: the loop owns retries and their budget. Since T3's review: each cloud call also carries `AnthropicChatOptions.timeout` = the loop's wait + 1 s, because the SDK's OkHttp call ignores the interrupt of `future.cancel(true)`; without it an abandoned `ACT` call kept its connection open up to the 90 s client timeout.
- **Recording.**
  - Every `ACT` and `REPORT` step gets `promptTokens` and `completionTokens` from `ChatResponse.getMetadata().getUsage()` (null when the provider gives none). Ollama reports `prompt_eval_count` and `eval_count` through it; this measures what T6 inferred.
  - The incident gets `route` (`local` or `cloud`) and the model name, written when the run starts like `mode`. Migrations: `V5` for the step columns (T2), `V6` for the incident's route and model (T3); shown by `GET /incidents/{id}` and in the stored report.
- **Scripts.**
  - compose passes `KINTSUGI_AGENT_ROUTE` (default `local`) and `ANTHROPIC_API_KEY` (default empty) to the agent;
  - `verify-agent.sh --route local|cloud` exports the route, and refuses `cloud` when `ANTHROPIC_API_KEY` is empty;
  - the route is recorded in the score and the summary, and a mismatch is a miss, like the mode;
  - the 11-minute spacing between faults stays: it guards the incident window, not the model;
  - `compare-agent-modes.sh --route` passes it through.

## Acceptance criteria

- With no route set, the agent behaves exactly as before: the existing tests pass unchanged, and a test proves that the local route's call and warm-up options are those of today.
- `kintsugi.agent.route=cloud` runs the whole loop on `claude-haiku-5-5`. A test with the scripted fake chat model proves the options: no temperature, thinking disabled, `maxTokens` equal to the caps, the schema on the report.
- An unknown route stops the start naming the property. The cloud route with no API key stops the start naming `ANTHROPIC_API_KEY`.
- Every model step records its prompt and completion tokens when the provider reports them; the incident and the report record the route and the model.
- `verify-agent.sh --route cloud` runs F1-F3 and records the route; a run in another route is a miss.
- `./mvnw test` passes in `agent/`; `infra/scripts/test-lib.sh` passes; the existing verify scripts still pass.
- No API key, real or placeholder, is committed.

## Delivery

- Forecast, authored lines: T1 about 140 (docs), T2 about 180, T3 about 380, T4 about 90, T5 about 40 (results). Total about 830, so sliced by task.
- Strategy: `feature-branch-chain` (the project's standing default). Branches `feat/cloud-route`, `-02`, `-03`, ..., each from the previous tip.
- RDD: on; consent is a standing grant. Review per work-unit commit.

## Tasks

- [x] **T1 — This document**, after ADR-012 was accepted. Branch `feat/cloud-route`. Route: inline (one document; the code read was within the inline budget).
- [x] **T2 — Token usage on every model step** (both routes): `promptTokens` and `completionTokens` on `ACT` and `REPORT` steps from Spring AI's `Usage`, migration `V5` (step columns), shown by the read API. Tests first with the scripted fake chat model (usage present and absent). Also verify `spring.ai.model.chat` in Spring AI 2.0.1 for T3. Living spec. Branch `-02`. Route: delegated writer.
- [x] **T3 — The cloud route:**
  - `spring-ai-starter-model-anthropic` (the Spring AI BOM's version, 2.0.1);
  - `ModelRoute` as an interface with `OllamaRoute` and `AnthropicRoute`;
  - `kintsugi.agent.route` and the `spring.ai.model.chat` selection;
  - the cloud `call-timeout` group, `spring.ai.anthropic.timeout` and `max-retries: 0`;
  - no warm-up on the cloud route;
  - `route` and `model` on the incident and the report (migration `V6`).

  Tests first: the local options unchanged, the cloud options, the start failures. Living spec; ADR-010 "Amended in cloud-route T3". Branch `-03`. Route: delegated writer.
- [ ] **T4 — Scripts and compose:** `KINTSUGI_AGENT_ROUTE` and `ANTHROPIC_API_KEY` in compose; `verify-agent.sh --route`, the key check, the route in score and summary, `agent_verdict` extended and `test-lib.sh` cases; `compare-agent-modes.sh --route`; `docs/setup.md`. Branch `-04`. Route: inline or delegated writer, by size.
- [ ] **T5 — Live runs on the cloud route,** each batch with the human's go-ahead:
  - a smoke run, explorer, F1-F3, recording latencies, tokens and cost;
  - then `compare-agent-modes.sh --route cloud` with the number of rounds the human decides.

  Results recorded here, in `odd/tasks/evidence-dossier.md` and in `odd/tasks/agent-loop.md`. Branch `-05`. Route: parent (runs).

## Progress

- 2026-10-18: T1 done. Route: inline. Read for the design: `ModelRoute`, `OllamaModelRouter`, `ModelRouter`, `ModelRouteProperties`, `LoopConfig`, the agent's `application.yaml` (`spring.ai.ollama.chat`: `temperature: 0`, `num-ctx: 8192`), and Spring AI's Anthropic documentation (context7: `AnthropicChatOptions.outputSchema`, `effort`, `thinkingDisabled`, `spring.ai.anthropic.timeout` default 60 s, `max-retries` default 2). Spring AI 2.0.1 is the latest GA (Maven Central, 2026-10-18).

- 2026-10-18: T2 done in `daa531f` on `feat/cloud-route-02`. Route: delegated writer (5 non-trivial files). ACT and REPORT steps store `promptTokens` and `completionTokens` (migration `V5`), shown by `GET /incidents/{id}` with or without `?full=true`; other step kinds store null. Absent rule: null when the response has no usage metadata or reports 0 and 0, because Spring AI 2.0.1's `EmptyUsage` and Ollama's `DefaultUsage` turn a missing count into 0, and 0 would read as a free call. RED: 25 errors in `AgentLoopTest` (column missing) and an NPE in `AgentLoopApiTest`; GREEN: `./mvnw test` 352 run, 0 failures, 0 errors (parent spot check: `AgentLoopTest` 49/0, `AgentLoopApiTest` 1/0). Review: assessed medium, `under_budget`, pending in the slice with base `3756540`.
- 2026-10-18: `spring.ai.model.chat` check for T3, read in the 2.0.1 sources: `OllamaChatAutoConfiguration` and `AnthropicChatAutoConfiguration` both carry `@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama" | "anthropic", matchIfMissing = true)`. So the property selects one provider, but with it unset and both starters present both turn on: `application.yaml` must always set it, `ollama` by default.

- 2026-10-18: T2 review (base `3756540`, 1 lens): approved, acknowledged. WARNING: `tokens` stored 0 next to null counts; SUGGESTIONs: partial usage unspecified, non-model steps' nulls unasserted. Fixed in `c68a476`.
- 2026-10-18: T3 done in `0c2832c` on `feat/cloud-route-03`. Route: delegated writer (10+ non-trivial files). About 850 authored lines against the 380 forecast, half of them tests; kept as one work unit because selection, options and recording share `AgentLoop` and the same tests, and reviewed as one commit. One input: `RouteEnvironmentPostProcessor` (registered in `META-INF/spring.factories` under Boot 4's `org.springframework.boot.EnvironmentPostProcessor`) maps `kintsugi.agent.route` to `spring.ai.model.chat` and checks the key before any bean. `ModelRoute` is a sealed interface: `OllamaRoute` (options equal to today's, asserted with concrete values), `AnthropicRoute` (`claude-haiku-5-5`, no temperature, `thinkingDisabled()`, `maxTokens` = caps, `outputSchema` on the report, no warm-up). `AnthropicChatModel` does not merge default options into a prompt's, so the route builds complete options per call. RED: test-compile failures (classes missing), then 2 failures and 2 errors with recording and warm-up skip removed. GREEN: `./mvnw test` 370/0/0.
- 2026-10-18: T3 review (base `239f121`, 1 lens): approved, acknowledged. WARNINGs: an abandoned cloud call was not aborted (OkHttp ignores the interrupt); tests read `KINTSUGI_AGENT_ROUTE` from the shell. SUGGESTION: V6 backfilled never-started incidents. All fixed in `c68a476` (per-call timeout, `AbandonedCloudCallTest` against a hanging stub; `MockEnvironment` and pinned `kintsugi.agent.route=local` in the Spring Boot tests; V6 gated on a step row, `RouteBackfillMigrationTest`). Partial usage: each count is stored only when positive, because `DefaultUsage` maps a missing count to 0. `./mvnw test` 374/0/0, and the same with `KINTSUGI_AGENT_ROUTE=cloud ANTHROPIC_API_KEY=` exported.
- 2026-10-18: fix review (base `0c2832c`, 1 lens): approved, acknowledged. WARNING: V6 edited in place breaks Flyway's checksum where it was applied. Checked: the lab's `agent` database (in `knowledge-db`) is at V4, so V5 and V6 were never applied outside test databases; no V7 needed. SUGGESTION: the abandoned-call test's 2 s deadline could be flaky on a slow runner; widened to 5 s in `b15bd8b`.
- Follow-ups, not fixed: `KINTSUGI_AGENT_MODE` can leak from the shell into the Spring Boot tests the same way the route did (predates this feature). The test classes build the Anthropic client offline with the literal `test-only-not-a-key`, a string that names itself as not a key; the human may judge it against the "no placeholder key" rule. An older incident that failed at warm-up wrote no step, so V6 leaves its route null.

## Next step

T4: scripts and compose.
