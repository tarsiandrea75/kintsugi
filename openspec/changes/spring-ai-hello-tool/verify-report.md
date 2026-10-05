# Verify Report: spring-ai-hello-tool

> Mirror of Engram topic `sdd/spring-ai-hello-tool/verify-report` (obs 76). Date: 2026-10-05. Mode: Strict TDD, hybrid.

**Verdict: FAIL (strict-TDD evidence rule)** — 1 CRITICAL, 5 WARNING, 4 SUGGESTION. Runtime behavior fully verified: 14/14 offline tests green, 18/19 scenarios PASS, 1 PARTIAL.

## Execution evidence

| Command (from `agent/`) | Exit | Result |
|---|---|---|
| `./mvnw test -DargLine=-Dspring.ai.ollama.base-url=http://localhost:1` | 0 | 14 tests, 0 failures; live excluded |
| `./mvnw test -Plive` with Ollama unreachable | 1 | LiveTimeToolTest errors (not skipped) |
| `./mvnw test -Plive` (orchestrator, Ollama up) | 0 | 1 test, 32.85 s, answer contains 09:00 |
| Demo, `demo` profile | 0 | Tool-backed answer, 18 s |
| Demo, base URL `http://localhost:1` | 1 | "Ollama not reachable at http://localhost:1. Start it with: ollama serve", 5 s, no stack trace |

## Scenarios

| Spec / Req | Scenario | Evidence | Result |
|---|---|---|---|
| time REQ-1 | Buenos Aires | `TimeToolsTest.buenosAiresIsThreeHoursBehindUtc` | PASS |
| time REQ-1 | Rome | `TimeToolsTest.romeIsTwoHoursAheadOfUtcInOctober` | PASS |
| time REQ-1 | Result parseable | Rome test parses and compares the instant | PASS |
| time REQ-2 | Unknown zone | `invalidZoneReturnsErrorInsteadOfThrowing` | PASS |
| time REQ-2 | Blank, whitespace, null | Same parameterized test | PASS |
| time REQ-3 | Context wires tool and client | `ChatClientWiringTest`, `TimeToolsTest.exposesCurrentTimeTool...` | PASS |
| time REQ-3 | Clock injectable | Wiring test uses context `Clock`; `AgentApplicationTests` loads real `ClockConfig` | PASS |
| time REQ-4 | Live Buenos Aires | `LiveTimeToolTest` | PASS |
| time REQ-5 | Not active by default | `demoRunnerIsNotPresentWithoutDemoProfile` | PASS |
| time REQ-5 | Active under demo | `DemoRunnerTest.printsTheModelAnswer` + manual run | PARTIAL (no automated profile-activation test) |
| time REQ-6 | Connection failure | `reportsUnreachableOllamaWithoutStackTrace` + manual run | PASS |
| time REQ-7 | Properties bound | `ollamaDefaultsAreBoundFromConfiguration` | PASS |
| tooling REQ-1 | Default run offline | Offline run | PASS |
| tooling REQ-2 | Live run | Live run | PASS |
| tooling REQ-2 | Live fails when unavailable | `-Plive` with Ollama down, exit 1 | PASS |
| tooling REQ-3 | Config readback | `openspec/config.yaml` | PASS |
| tooling REQ-4 | Wrapper | `agent/mvnw` mode 100755, wrapper tracked | PASS |
| tooling REQ-5 | ADR content | ADR-006 (see WARNING 2) | PASS with WARNING |
| tooling REQ-5 | Docs updated | `setup.md`, `STATE.md`; source-of-truth docs unchanged | PASS |

## Design conformance

- Spring AI imports only in `tools/`, `config/`, `demo/`.
- No AI attribution in any commit.
- Recorded deviations (acceptable): `ClockConfig` moved to phase 2; `ToolCallbacks` in `org.springframework.ai.support`; tests mock `ChatModel.getOptions()`; `connect-timeout` 2s instead of 5s (recorded in ADR-006). `design.md` not updated for these.

## Strict TDD compliance

| Check | Result |
|---|---|
| TDD evidence reported | ❌ apply-progress has no per-task TDD Cycle Evidence table |
| Tests exist for tasks | ✅ 5 test files, 15 test cases |
| GREEN confirmed | ✅ |
| Triangulation | ✅ |
| Safety net | ➖ not reported |
| RED observed | ⚠️ `LiveTimeToolTest` never observed RED |

## Findings

CRITICAL
1. apply-progress lacks the TDD Cycle Evidence table.

WARNING
1. `LiveTimeToolTest` never observed RED (production code already existed). Mitigated: fixed clock makes "09:00" unguessable without a tool call; confirmed to fail when Ollama is down.
2. ADR-006 does not use the literal "pending change B" and does not name the `spring-ai-starter-model-ollama` starter (spec REQ-5).
3. `connect-timeout` 2s vs designed 5s; design doc stale.
4. No automated test for positive `@Profile("demo")` activation.
5. Blackholed host raises `HttpConnectTimeoutException`, not handled as a connection failure (out of spec; follow-up).

SUGGESTION
1. Windows console prints '?' for non-ASCII output (stdout encoding).
2. Debug `System.out.println` in `LiveTimeToolTest`.
3. No explicit test asserting zero model calls at context start.
4. Update `design.md` with the recorded deviations.

## Corrections Applied After Verify (2026-10-05)

One scoped correction, approved by the human:

| Finding | Resolution |
|---|---|
| CRITICAL 1 (no TDD Cycle Evidence table) | Resolved: table in `apply-progress.md` (reconstructed from Phase 2/3 apply reports) and Engram obs 75 |
| WARNING 1 (live test never RED) | Accepted deviation, recorded in `apply-progress.md` |
| WARNING 2 (ADR-006 wording, starter) | Resolved: ADR-006 names `spring-ai-starter-model-ollama` and says "pending change B" |
| WARNING 3 (design stale, connect-timeout) | Resolved: `design.md` section "Deviations Found During Apply" |
| WARNING 4 (no profile-activation test) | Deferred to change B (tracked in `docs/STATE.md`) |
| WARNING 5 (`HttpConnectTimeoutException`) | Deferred, out of spec (tracked in `docs/STATE.md`) |
| SUGGESTION 1 (console encoding) | Deferred to change B |
| SUGGESTION 2 (debug `println`) | Resolved: removed |
| SUGGESTION 3 (no zero-calls-at-startup test) | Accepted: implied by `AgentApplicationTests` |
| SUGGESTION 4 (update design) | Resolved, same as WARNING 3 |

After corrections: `./mvnw test` exit 0 (14 tests), live test compiles. **Final verdict: PASS with accepted deviations.**
