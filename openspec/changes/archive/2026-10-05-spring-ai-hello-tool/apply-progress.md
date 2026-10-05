# Apply Progress: spring-ai-hello-tool

> Mirror of Engram topic `sdd/spring-ai-hello-tool/apply-progress` (obs 75). Date: 2026-10-05.
> The TDD Cycle Evidence table was reconstructed after verify from the Phase 2 and Phase 3 apply reports; the RED/GREEN outcomes below were observed and reported during apply, not re-run.

## Commits

| Work unit | Commit |
|---|---|
| Phase 1: generated skeleton | `6f2e1f1` |
| Phase 2: TimeTools and test tooling | `baba2f2` |
| Phase 3: wiring, demo, live test | `a3d1c27` |
| Phase 4: ADR-006, setup, planning artifacts | `f69fa2e` |

## TDD Cycle Evidence

| Task | Test | RED (observed) | GREEN | Triangulation | Refactor |
|---|---|---|---|---|---|
| 2.1/2.2 | `TimeToolsTest.buenosAiresIsThreeHoursBehindUtc` | Compile error: `cannot find symbol class TimeTools` | Pass with real logic | Rome (2.3) uses a different offset | — |
| 2.3 | `romeIsTwoHoursAheadOfUtcInOctober` | Written in the same cycle as 2.1 (no separate RED) | Pass; parsed instant equals the fixed clock | Two zones, two offsets | — |
| 2.4 | `invalidZoneReturnsErrorInsteadOfThrowing` (4 cases) | 4/6 errored: NPE on null, `ZoneRulesException`, `DateTimeException` for blank and whitespace | 6/6 after null/blank guard and `DateTimeException` catch | Unknown, empty, whitespace, null | `invalidZone()` helper extracted (2.6), tests green |
| 2.5 | `exposesCurrentTimeToolWithZoneParameterInSchema` | Compile error: wrong package for `ToolCallbacks` | 7/7; schema contains `zone`; callback returns Rome time | Name, schema and invocation checked | — |
| 2.7 | Temporary `@Tag("live")` probe | Ran under default build (9 tests) | Skipped by default (8 tests); only probe under `-Plive` | Both profiles checked | Probe deleted |
| 3.1/3.2 | `AgentApplicationTests.ollamaDefaultsAreBoundFromConfiguration` | Model was `mistral`, expected `granite4.1:8b` | Pass after `application.yaml` | Model, temperature, num-ctx | — |
| 3.3/3.4 | `ChatClientWiringTest` | No `ChatClient` bean; then NPE (`getOptions()` not mocked) | 2/2 after `ChatClientConfig` and mocking `getOptions()` | Tool attached + context `Clock` used + no `DemoRunner` without profile | — |
| 3.5/3.6 | `DemoRunnerTest` (3 cases) | Compile error: `DemoRunner` not found | 3/3 | Answer printed; unreachable → message, exit 1, no stack trace; other errors rethrown | 3.7: none needed |
| 3.8 | `LiveTimeToolTest` (`@Tag("live")`) | **Not observed** (deviation): production code already existed | Pass, answer contains `09:00`, 32.85 s | Fixed 12:00Z clock; fails when Ollama is down (exit 1) | Debug `println` removed after verify |

## Safety net

Each phase ended with the full offline suite green: Phase 2 (8 tests), Phase 3 (14 tests), after verify corrections (14 tests).

## Deviations

See `design.md`, section "Deviations Found During Apply".
