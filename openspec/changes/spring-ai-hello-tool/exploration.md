# Exploration: spring-ai-hello-tool

> Mirror of Engram topic `sdd/spring-ai-hello-tool/explore` (obs 70). Date: 2026-10-05.

## Goal

Minimal Spring Boot app under `agent/` that asks the local Ollama model a question it can only answer by calling one tool (`currentTime(zone)`), plus a ~10-prompt tool-calling smoke test to confirm the model choice. First code change: bootstraps JUnit and enables Strict TDD.

## Current state

- `agent/` holds only `.gitkeep`. No `pom.xml`, no tests.
- `openspec/config.yaml`: `strict_tdd: false`, no runner.
- `.gitignore` covers `target/` and keeps `.mvn/wrapper/maven-wrapper.jar`.
- `.gitattributes` already enforces LF (`* text=auto eol=lf`, which covers `mvnw`) and CRLF for `*.cmd`. The `mvnw` executable bit still needs `git update-index --chmod=+x agent/mvnw`.
- Environment: Corretto Java 21.0.9, Maven 3.8.9, Ollama 0.34.4, Python 3.14.0, no Docker (not needed yet).

## Verified facts (2026-10-05)

| Topic | Fact |
|---|---|
| Spring Boot | 4.1.1 latest GA (4.2.0-M2 milestone). Requires Java 17+ and Maven 3.6.3+. |
| Spring AI | 2.0.1 latest stable (2.1.0-M1 preview). 2.0.x supports Boot 4.0.x/4.1.x. |
| Initializr | Generates Boot 4.1.1, Java 21, `spring-ai.version` 2.0.1 BOM, `spring-ai-starter-model-ollama`, `spring-boot-starter-test`. |
| Ollama config (2.0) | `spring.ai.ollama.base-url`, `spring.ai.ollama.chat.model` (default `mistral`), `.temperature` (0.8), `.num-ctx` (2048), `.keep-alive`, `.think`; `spring.ai.ollama.init.pull-model-strategy` default `never`. The 1.x `chat.options.*` form is deprecated. |
| Tool API | `@Tool` / `@ToolParam` (`org.springframework.ai.tool.annotation`); `ChatClient.builder(model).defaultTools(obj)`; `ToolCallbacks.from(obj)`. |
| Thinking | qwen3 thinks by default on Ollama 0.12+; disable with `spring.ai.ollama.chat.think=false`. |
| Granite | granite4.1:8b: tool calling, BFCL V3 68.3, Apache 2.0, 128K context (secondary source; confirm by smoke test). |

To verify at design time: JUnit version shipped by Boot 4 (reported JUnit 6, `org.junit.jupiter.api` unchanged); `@MockBean` replaced by `@MockitoBean`; Ollama client timeout property; tool exception handling in 2.x.

## Proposed shape (package `dev.kintsugi.agent`)

- `AgentApplication`
- `tools/TimeTools`: `@Component`, takes a `java.time.Clock`; `@Tool String currentTime(@ToolParam String zone)` returns ISO-8601 with offset; invalid zone yields a clear error.
- `config/ClockConfig`: `Clock.systemUTC()` bean.
- `config/ChatClientConfig`: `ChatClient.builder(model).defaultTools(timeTools).build()`.
- `application.yaml`: `chat.model: granite4.1:8b`, `temperature: 0`, `num-ctx: 8192`.

## Testing strategy (Strict TDD)

1. Tool unit tests, no Spring, fixed `Clock`: Buenos Aires, Rome, invalid zone, blank zone.
2. Registration test without a live model: `ToolCallbacks.from(...)` name/description/schema; stub `ChatModel` returning a tool call then text to exercise the `ChatClient` tool loop (fallback: context test).
3. Live integration test: `@Tag("live")`, asks "what time is it in Buenos Aires?" with a fixed `Clock`, asserts the expected `HH:mm`.

Surefire excludes group `live` by default; Maven profile `live` runs it. Default: `./mvnw test`. Live: `./mvnw test -Plive`.

## Smoke test (change B)

Parameterized `@Tag("live")` JUnit test, model chosen by `-Dkintsugi.model`. A recording `ToolCallback` wrapper logs arguments, validity and latency. 10 prompts: direct questions (3), Spanish and Italian phrasing (2), ambiguous city, two zones, time delta, two negative controls (no tool call expected). Report per model: valid rate (plan-B trigger < 50%), first-attempt argument validity, abstention on negatives, median/max latency (risk trigger > 1 min per step).

## Forks

1. **Versions ADR number**: ADR-006 "Stack versions" (recommended; no change to vision), vs renumbering vision, vs merging into ADR-003. Model decision goes into ADR-003 after change B.
2. **Split**: one change (~530 hand-written lines, over budget) vs A (skeleton, tool, tests, TDD flip, versions ADR; ~300–345 lines) then B (smoke test, model decision; ~250 lines). Recommended: split. Generated wrapper files (~450 lines) go in a separate generated-only commit, excluded from review counting.
3. **Own interfaces isolating Spring AI**: YAGNI now; introduce `ModelRouter` in M2. Keep Spring AI types in `config/` and `tools/`.

## Open questions

1. Confirm ADR-006 for versions.
2. Demo entry point: small `ApplicationRunner` (no web starter) vs live test only.
3. Split A/B, and the valid-call adoption bar for B (e.g. 80%).

## Risks

- Model still downloading (affects live test and B only).
- CPU inference may exceed HTTP read timeout; property not yet verified.
- `num-ctx` 2048 default too small with tool schemas.
- `think=false` on non-thinking models unverified: set only for qwen.
- Secondary sources for JUnit 6 and granite BFCL.
- `mvnw` executable bit on Windows.
