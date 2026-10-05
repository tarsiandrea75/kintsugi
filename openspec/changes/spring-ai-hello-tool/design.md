# Design: Spring AI hello-world with one tool

## Technical Approach

Generate `agent/` with Spring Initializr in a generated-only commit, then add authored code test-first. Spring AI types stay in `config/`, `tools/` and `demo/`. The tool is read-only and deterministic: the LLM proposes the call, `TimeTools` computes the answer from an injected `Clock`, and tests check it against a fixed clock. Spec: `specs/agent-time-tool/spec.md`.

Source note: this phase had no web or Context7 access. The facts below come from the Boot 4.0 and Spring AI 2.0 reference docs and release notes known up to 2026-06. Each one has an apply-time check.

## Resolved Technical Points

| Topic | Resolution | Apply-time check |
|---|---|---|
| JUnit | Boot 4.x manages JUnit Jupiter 6.x through `spring-boot-starter-test`. The `org.junit.jupiter.api` package is unchanged. Do not pin the version. | `./mvnw dependency:tree` shows `junit-jupiter` 6.x |
| Mocks | `@MockBean` was removed in Boot 4. Use `@MockitoBean`, and replace `Clock` with `@TestBean` (Framework 6.2+). | Compiles |
| Tool errors | By default, `DefaultToolExecutionExceptionProcessor` turns tool exceptions into a message for the model. We do not rely on that: `currentTime` catches `DateTimeException` and returns `Error: ...`. | REQ-2 tests |
| Read timeout | Ollama uses Boot's `RestClient.Builder`. Set `spring.http.clients.read-timeout: 5m` and `connect-timeout: 5s`. The JDK client has no default read timeout, so 5m is a cap. | Key exists in Boot 4.1 `spring-configuration-metadata.json` (unknown YAML keys are silently ignored). `spring-boot-restclient` must be in the tree; otherwise add `spring-boot-starter-restclient`. |
| Unreachable | `RestClient` throws `ResourceAccessException`, caused by `java.net.ConnectException`. Retry may wrap it, so the runner walks the cause chain looking for `ConnectException`. | Unit test. Manual demo with Ollama stopped must fail in under 15 s; otherwise set `spring.ai.retry.max-attempts: 1`. |
| Stub tool loop | Tools run inside `OllamaChatModel`, not inside `ChatClient`. A stub model that returns a tool call would not run the tool. Fallback: capture the outgoing `Prompt` and assert that its `ToolCallingChatOptions` contains `currentTime`. The live test covers the full loop. | Wiring test |
| Exit code | `DemoRunner` implements `ExitCodeGenerator`. `main` calls `System.exit(SpringApplication.exit(SpringApplication.run(...)))`. Throwing from the runner would log a stack trace. | Manual demo |

## Architecture Decisions

| Decision | Choice | Rejected | Rationale |
|---|---|---|---|
| Client wiring | `@Bean ChatClient` built from the autoconfigured `ChatClient.Builder` with `.defaultTools(timeTools)` | `ChatClient.create` at call sites | One wired bean that tests can inspect |
| Tool errors | Return an error string | Throw and rely on the processor | Approved decision 3; does not depend on framework defaults |
| Demo gating | `@Profile("demo")` on `DemoRunner` | Property flag | Approved decision 2 |
| Clock | `ClockConfig` returns `Clock.systemUTC()`; tests use `@TestBean` | `@ConditionalOnMissingBean` | The conditional depends on bean ordering outside autoconfiguration |
| Live split | Surefire `excludedGroups=live`; profile `live` sets `groups=live` | Failsafe ITs | Smallest config; one runner |
| Ollama keys | `spring.ai.ollama.chat.{model,temperature,num-ctx}` (2.0 form) | `chat.options.*` (deprecated) | REQ-7 text says `chat.options`; align the spec wording, the test asserts bound values |
| Versions ADR | ADR-006 "Stack versions" | Renumbering the vision ADRs | The vision reserves ADR-002 to ADR-005 |

## Data Flow

    DemoRunner --question--> ChatClient(defaultTools=TimeTools) --> OllamaChatModel --HTTP--> Ollama
                                                    |  <-- tool call currentTime(zone) ---|
                                    TimeTools(Clock) --> ISO-8601 | "Error: ..." --> model --> text --> stdout
    Connection failure (ConnectException in cause chain) --> stderr message, exitCode = 1

## File Changes (under `agent/` unless noted)

| File | Action | Description |
|---|---|---|
| `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `pom.xml`, `AgentApplication(Tests)`, `application.properties` | Create (generated) | Initializr output; delete root `agent/.gitkeep` |
| `pom.xml` | Modify | Surefire groups and the `live` profile |
| `src/main/resources/application.yaml` | Create (replaces `.properties`) | Ollama model, temperature 0, num-ctx 8192, timeouts |
| `src/main/java/dev/kintsugi/agent/AgentApplication.java` | Modify | Exit-code-aware `main` |
| `.../tools/TimeTools.java` | Create | `@Component` `@Tool` |
| `.../config/ClockConfig.java`, `.../config/ChatClientConfig.java` | Create | Beans |
| `.../demo/DemoRunner.java` | Create | Profile-gated runner |
| `src/test/java/dev/kintsugi/agent/...` | Create/Modify | `TimeToolsTest`, `DemoRunnerTest`, `ChatClientWiringTest`, `AgentApplicationTests` (REQ-7), `LiveTimeToolTest` |
| `openspec/config.yaml` | Modify | `strict_tdd: true`; runner `cd agent && ./mvnw test`; framework "JUnit Jupiter 6"; unit and integration true; `tasks` rule mentions JUnit, not "JUnit 5" |
| `docs/adr/ADR-006-stack-versions.md` | Create | ADR-001 headings. Pins Java 21 (Corretto 21.0.9), Boot 4.1.1, Spring AI 2.0.1, JUnit (Boot-managed), Maven Wrapper, Ollama >= 0.4.6. Model `granite4.1:8b` provisional until change B / ADR-003. Rejected: Boot 3.5 + Spring AI 1.1; milestones. |
| `docs/setup.md` | Modify | Link ADR-006; wrapper replaces system Maven; "Build and run" commands |
| `docs/STATE.md` | Modify | Close the versions-ADR question |

## Interfaces / Contracts

```java
// TimeTools: ISO_OFFSET_DATE_TIME always prints seconds; OffsetDateTime.toString() would drop ":00".
@Tool(description = "Current date-time in an IANA time zone, e.g. America/Argentina/Buenos_Aires")
public String currentTime(@ToolParam(description = "IANA zone id") String zone)
// valid -> OffsetDateTime.ofInstant(clock.instant(), id).truncatedTo(SECONDS).format(ISO_OFFSET_DATE_TIME)
// null/blank/DateTimeException -> "Error: invalid time zone '<zone>'. Use an IANA zone id such as Europe/Rome."
```

Wiring test: `@MockitoBean ChatModel` MUST return `ToolCallingChatOptions.builder().build()` from `getDefaultOptions()`, because `ChatClient` attaches tool callbacks only to tool-calling options. Use an `ArgumentCaptor<Prompt>`.

`DemoRunner(ChatClient, @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl)` writes `Ollama not reachable at <url>. Start it with: ollama serve` to stderr. It rethrows non-connection errors.

Surefire config: default `<excludedGroups>live</excludedGroups>`; profile `live`: `<groups>live</groups>` and an empty `excludedGroups`.

## Testing Strategy (Strict TDD order)

0. Baseline: the generated `contextLoads` passes with `./mvnw test` and no Ollama running. This proves the JUnit bootstrap.
1. **First RED**: `TimeToolsTest` Buenos Aires, then Rome, parseable, unknown zone, blank/null (`@ParameterizedTest`). No Spring.
2. `TimeToolsTest`: `ToolCallbacks.from(tools)` exposes `currentTime`, and its schema mentions `zone`.
3. `DemoRunnerTest` (lambda `ChatModel` with `ChatClient.create`, `OutputCaptureExtension`): prints the answer; with a connection failure it prints the message, sets exit code 1 and prints no `\tat `.
4. `ChatClientWiringTest` (`@SpringBootTest`, `@MockitoBean ChatModel`, `@TestBean Clock`): the prompt carries `currentTime`, there is no `DemoRunner` bean, and the tool uses the context `Clock`.
5. `AgentApplicationTests`: the real `OllamaChatModel` default options are `granite4.1:8b`, 0.0, 8192. Context start makes no HTTP call.
6. `LiveTimeToolTest` `@Tag("live")`: fixed clock 12:00Z; the answer contains `09:00`. Run with `./mvnw test -Plive`. Check that `./mvnw test` skips it.

## Generation

```bash
curl -fsS https://start.spring.io/starter.zip -d type=maven-project -d language=java \
  -d bootVersion=4.1.1 -d javaVersion=21 -d groupId=dev.kintsugi -d artifactId=agent \
  -d name=agent -d packageName=dev.kintsugi.agent -d dependencies=spring-ai-ollama \
  -d baseDir=agent -o "$SCRATCH/agent.zip" && rm agent/.gitkeep && tar -xf "$SCRATCH/agent.zip"
git add agent && git update-index --chmod=+x agent/mvnw
```

Commit 1 (`build: generate agent skeleton with Spring Initializr`) contains only generated files. Commit 2 onward holds the authored changes.

## Threat Matrix

N/A: no routing, shell, subprocess, VCS/PR automation, executable-file classification, or process-integration boundary in code. The `mvnw` chmod is a one-time manual index operation.

## Migration / Rollout

No migration required. Rollback is described in the proposal.

## Open Questions

- [ ] REQ-7 wording (`chat.options`) should become `spring.ai.ollama.chat.*`. Spec phase to align.
- [ ] The `config.yaml` tasks rule asks the first code change to bootstrap pytest as well. Proposed change: pytest bootstraps with the first `evals/` change. Needs human approval.
- [ ] Line forecast is about 350 to 390 authored lines, over the 300 to 345 target. If needed, `sdd-tasks` can split the docs (ADR-006, setup.md) into a follow-up slice.

## Deviations Found During Apply

Recorded on 2026-10-05; the code and ADR-006 are authoritative where they differ from the sections above.

1. `ClockConfig` was created in Phase 2, not 3.4: once `TimeTools` became a `@Component`, the context test failed for lack of a `Clock` bean.
2. `ToolCallbacks` lives in `org.springframework.ai.support`, not `org.springframework.ai.tool`.
3. In Spring AI 2.0.1, `ChatClient` reads `ChatModel.getOptions()`, not `getDefaultOptions()`; the wiring test mocks `getOptions()`.
4. `connect-timeout` is `2s`, not `5s`: on localhost a refused or slow connect means the server is down.
5. `spring-boot-restclient` was already on the dependency tree; no starter was added.
6. `spring.ai.retry.max-attempts: 0` is required, not a fallback: the default retries connection refused 10 times with backoff up to 180 s.
