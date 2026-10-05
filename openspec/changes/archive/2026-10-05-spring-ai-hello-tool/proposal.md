# Proposal: Spring AI hello-world with one tool

## Intent

M1 Monday goal (docs/vision.md): a minimal Spring AI app where the local Ollama model answers a question only a tool can answer. First code change: bootstraps JUnit and switches the project to Strict TDD. Model choice stays open until change B (`model-smoke-test`).

## Scope

### In Scope
- `agent/` skeleton from Initializr: Boot 4.1.1, Java 21, Spring AI 2.0.1 BOM, `spring-ai-starter-model-ollama`, no web starter, Maven Wrapper.
- `TimeTools.currentTime(zone)` `@Tool` with injected `Clock`; ISO-8601 with offset; clear error on invalid/blank zone.
- `ChatClient` bean wired with the tool; `ApplicationRunner` demo printing the answer.
- Tests first: tool unit tests, no-live-model registration/wiring test, one `@Tag("live")` test run only via Maven profile `live`.
- `application.yaml`: `spring.ai.ollama.chat.*` (model `granite4.1:8b`, temperature 0, num-ctx 8192).
- `openspec/config.yaml`: `strict_tdd: true`, runner `./mvnw test` from `agent/`.
- ADR-006 "Stack versions" (model marked pending change B); `docs/setup.md` updates; resolves the STATE.md versions-ADR question.

### Out of Scope
- 10-prompt smoke test and model decision (change B, ADR-003).
- `ModelRouter` or own interfaces isolating Spring AI (M2).
- Edits to `docs/vision.md`, `docs/concepts.md`, `docs/journal/2026-10-03-00-planning.md`.

## Capabilities

### New Capabilities
- `agent-time-tool`: `currentTime(zone)` tool contract, its registration with `ChatClient`, and the demo answer flow.
- `test-tooling`: default vs `live` test split and the Strict TDD runner.

### Modified Capabilities
- None

## Approach

Package `dev.kintsugi.agent`: `tools/TimeTools`, `config/ClockConfig`, `config/ChatClientConfig`, `AgentApplication`. Spring AI types stay in `config/` and `tools/`. Surefire excludes group `live` by default. Red then green per test.

**Vision deviation (human-approved, flagged per config rule):** vision says the agent never calls a model directly and own interfaces isolate Spring AI. This change uses `ChatClient` directly; `ModelRouter` is deferred to M2.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `agent/` | New | Maven project, sources, tests, config |
| `agent/mvnw`, `mvnw.cmd`, `.mvn/` | New | Generated-only commit, excluded from review-line count; `git update-index --chmod=+x agent/mvnw` |
| `openspec/config.yaml` | Modified | Strict TDD on, runner set |
| `docs/adr/ADR-006-stack-versions.md` | New | Pinned versions |
| `docs/setup.md`, `docs/STATE.md` | Modified | Versions ADR link, run commands |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| CPU inference exceeds HTTP read timeout | Med | Verify timeout property in design; live test isolated |
| Boot 4 / JUnit 6 / `@MockitoBean` differences | Med | Verify at design time |
| Demo runner calls Ollama during context tests | Med | Gate runner by property/profile |
| `mvnw` loses executable bit on Windows | Low | Explicit chmod in index |

## Rollback Plan

Revert the change commits (generated and authored); restore `strict_tdd: false` and null runner; delete ADR-006.

## Dependencies

- Local Ollama with `granite4.1:8b` (live test only).

## Success Criteria

- [ ] `./mvnw test` passes with no Ollama running.
- [ ] `./mvnw test -Plive` gets the correct Buenos Aires `HH:mm` via a tool call.
- [ ] Demo prints a tool-backed answer.
- [ ] Strict TDD enabled; ADR-006 records versions.

## Behavior Decisions (approved 2026-10-05)

1. **Ollama unreachable**: the demo prints a clear message (Ollama not reachable at the configured base URL; start it with `ollama serve`) and exits with a non-zero code. No raw stack trace.
2. **Demo activation**: the `ApplicationRunner` runs only under the `demo` Spring profile, so tests and future features never call the model by accident.
3. **Invalid or blank zone**: the tool returns an error message to the model (an observation it can recover from, e.g. retry with a valid IANA id) instead of throwing.
4. **Live test assertion**: strict, exact `HH:mm` derived from the fixed `Clock`; other phrasings count as a failure.
