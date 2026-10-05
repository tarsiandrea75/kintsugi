# Tasks: Spring AI hello-world with one tool

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 350-390 hand-written (generated wrapper/skeleton excluded) |
| 400-line budget risk | Medium |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 tool + tooling -> PR 2 wiring/demo/live -> PR 3 docs |
| Delivery strategy | ask-on-risk |
| Chain strategy | pending |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: Medium

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Skeleton, verify, `TimeTools`, surefire split, config.yaml (~130 lines) | PR 1 | `cd agent && ./mvnw test -Dtest=TimeToolsTest` | `./mvnw test` offline | `agent/` + `openspec/config.yaml` |
| 2 | Clock/ChatClient config, demo, REQ-7, live (~190 lines) | PR 2 | `./mvnw test` | `./mvnw test -Plive`; demo runs | config/, demo/, tests, yaml |
| 3 | ADR-006, setup.md, STATE.md (~70 lines) | PR 3 | N/A | N/A (docs only; read back) | `docs/` only |

## Phase 1: Skeleton and verification (generated-only commit)

- [x] 1.1 Run design Generation block into `agent/`; delete `agent/.gitkeep`.
- [x] 1.2 `git add agent && git update-index --chmod=+x agent/mvnw`; check `git ls-files -s agent/mvnw` = 100755 (REQ-4).
- [x] 1.3 `cd agent && ./mvnw test`: generated `contextLoads` green, no Ollama.
- [x] 1.4 Checkpoint (commit when human approves): `build: generate agent skeleton with Spring Initializr`; excluded from line count.
- [x] 1.5 VERIFY against resolved jars (`./mvnw dependency:tree`, `spring-configuration-metadata.json`): JUnit 6.x; `@MockitoBean`/`@TestBean`; HTTP read-timeout key honored by Ollama in Spring AI 2.0.1 / Boot 4.1.1 (`spring.http.clients.read-timeout`?; `spring-boot-restclient` present?); `spring.ai.ollama.chat.{model,temperature,num-ctx}`; connect-refused retry (`spring.ai.retry.*`). Record deviations in apply-progress and adjust design values.

## Phase 2: Tool and test tooling (PR 1)

- [x] 2.1 RED: `TimeToolsTest` Buenos Aires `2026-10-05T09:00:00-03:00` (fixed clock, no Spring) (REQ-1).
- [x] 2.2 GREEN: `tools/TimeTools.java` `@Component` `@Tool currentTime`.
- [x] 2.3 RED then GREEN: Rome `+02:00`; parseable same instant.
- [x] 2.4 RED then GREEN: `@ParameterizedTest` unknown zone, `""`, `"   "`, `null` return `Error: ...`, no throw (REQ-2).
- [x] 2.5 RED then GREEN: `ToolCallbacks.from(tools)` exposes `currentTime` with `zone` in schema.
- [x] 2.6 REFACTOR: extract error-message helper.
- [x] 2.7 `pom.xml`: surefire `excludedGroups=live`; profile `live` (`groups=live`, empty exclude) (test-tooling REQ-1/2).
- [x] 2.8 `openspec/config.yaml`: `strict_tdd: true`, runner `./mvnw test` (from `agent/`), framework JUnit Jupiter 6, unit/integration true, tasks rule says JUnit (REQ-3).
- [x] 2.9 Checkpoint (commit when human approves).

## Phase 3: Wiring, demo, live (PR 2)

- [x] 3.1 RED: `AgentApplicationTests` asserts `OllamaChatModel` defaults `granite4.1:8b`/0.0/8192, no HTTP (REQ-7).
- [x] 3.2 GREEN: `application.yaml` (replace `.properties`) with verified keys and timeouts.
- [x] 3.3 RED: `ChatClientWiringTest` (`@MockitoBean ChatModel` returning `ToolCallingChatOptions`, `@TestBean Clock`): captured `Prompt` has `currentTime`; no `DemoRunner` bean; tool uses context `Clock` (REQ-3, REQ-5).
- [x] 3.4 GREEN: `config/ClockConfig`, `config/ChatClientConfig` (`defaultTools`).
- [x] 3.5 RED: `DemoRunnerTest` prints "09:00"; connection failure prints "not reachable", base URL, `ollama serve`, exit code 1, no `\tat ` (REQ-5/6).
- [x] 3.6 GREEN: `demo/DemoRunner` (`@Profile("demo")`, `ExitCodeGenerator`, cause-chain walk); `AgentApplication.main` exit code.
- [x] 3.7 REFACTOR: tidy runner and tests.
- [x] 3.8 RED: `LiveTimeToolTest` `@Tag("live")`, fixed 12:00Z, asserts `09:00` (REQ-4). GREEN with Ollama + `granite4.1:8b`; confirm `./mvnw test` skips it.
- [x] 3.9 Checkpoint (commit when human approves).

## Phase 4: Docs (PR 3)

- [x] 4.1 `docs/adr/ADR-006-stack-versions.md` (ADR-001 headings; versions from generated pom/wrapper; model "pending change B").
- [x] 4.2 `docs/setup.md`: ADR link, wrapper, `./mvnw test`, `-Plive`, demo `-Dspring-boot.run.profiles=demo`.
- [x] 4.3 `docs/STATE.md`: versions-ADR question resolved; vision/concepts/journal untouched.
- [x] 4.4 Checkpoint (commit when human approves).

## Phase 5: Final verification

- [x] 5.1 `./mvnw test` green with Ollama stopped; no live test run.
- [x] 5.2 `./mvnw test -Plive` green with Ollama up.
- [x] 5.3 Demo under `demo` profile prints a tool-backed answer.
- [x] 5.4 Demo with wrong base-url: clear message, non-zero exit, fails fast (<15s, else `spring.ai.retry.max-attempts: 1`).
