# Archive Report: spring-ai-hello-tool

**Date**: 2026-10-05  
**Status**: COMPLETE  
**Artifact Observation IDs**: 71 (proposal), 72 (spec), 73 (design), 74 (tasks), 76 (verify-report)

## Summary

The spring-ai-hello-tool change has been fully implemented, verified, and archived. This change bootstrapped the first Spring AI agent code, implementing tool calling with a local Ollama model using Strict TDD practices.

## Verification Status

**Verdict**: PASS (with accepted deviations)

**Final State Authority**: Commits after verify-report show resolution of all blockers:
- **Commit fd20282** ("TDD evidence + verify corrections"): Appended TDD Cycle Evidence table to apply-progress, resolving the CRITICAL process-evidence gap originally reported in verify-report
- **Live test outcome**: Never observed RED, but live test with fixed Clock makes 09:00 unguessable without tool execution; test failure confirmed during live run with Ollama down
- **Deferred items** (per launch prompt):
  - HttpConnectTimeoutException handling (change B follow-up)
  - Demo profile-activation test (automatic verification deferred)
  - Windows console UTF-8 encoding (out of scope)

**Per verify-report (obs 76, state at verification time)**:
- Runtime behavior: fully verified
- Tasks: 30/30 checked (all implementation work complete)
- Scenarios: 19 total (agent-time-tool 12 + test-tooling 7); 18 PASS, 1 PARTIAL (REQ-5 @Profile gating verified manually)
- Tests: ./mvnw test exit 0, 14 tests green, live excluded; ./mvnw test -Plive with Ollama down exits 1 (LiveTimeToolTest fails, not silently skipped)
- Conformance: no changes to docs/vision.md, docs/concepts.md, or docs/journal/2026-10-03-00-planning.md; no AI attribution; Spring AI imports confined to tools/, config/, demo/

## Specs Synced to Main Repository

| Capability | Action | File |
|------------|--------|------|
| agent-time-tool | Created | openspec/specs/agent-time-tool/spec.md |
| test-tooling | Created | openspec/specs/test-tooling/spec.md |

**Spec Contents** (as persisted at merge time):
- **agent-time-tool**: 7 requirements covering tool contract with fixed Clock (2026-10-05T12:00:00Z), zone handling (BA 09:00-03:00, Rome 14:00+02:00), error messages (no throw), ChatClient registration testable without model, live test exact HH:mm 09:00, and @Profile("demo") gating
- **test-tooling**: Requirements covering default offline behavior, -Plive profile for live execution, Strict TDD runner configuration, mvnw executable bit, and ADR-006 version pinning with model "pending change B" (smoke test in change B)

**Note**: Spec REQ-7 already uses `spring.ai.ollama.chat.*` property naming (retained as written in merge).

## Archive Contents

```
openspec/changes/archive/2026-10-05-spring-ai-hello-tool/
├── proposal.md
├── design.md
├── exploration.md
├── apply-progress.md (with TDD Cycle Evidence table added in fd20282)
├── tasks.md (30/30 complete)
├── verify-report.md
├── specs/
│   ├── agent-time-tool/
│   │   └── spec.md (7 reqs)
│   └── test-tooling/
│       └── spec.md (requirements for test configuration and tooling)
└── archive-report.md (this file)
```

## Implementation Details

**Commits** (final state per launch prompt):
- 6f2e1f1: agent: generated skeleton from Initializr (Boot 4.1.1, Java 21, Spring AI 2.0.1, spring-ai-ollama)
- baba2f2: agent: TimeTools tool implementation with injected Clock and zone handling
- a3d1c27: agent: ChatClient wiring, DemoRunner, live test, and application.yaml configuration
- f69fa2e: docs: ADR-006 stack versions and planning documents
- fd20282: verify: TDD evidence table + corrections (resolved CRITICAL process-evidence gap)

**Code Scope**:
- Package dev.kintsugi.agent with subpackages: tools/, config/, demo/
- TimeTools.currentTime(zone): @Tool returning ISO-8601 offset date-time or error message
- ClockConfig: Clock.systemUTC dependency injection
- ChatClientConfig: @Bean ChatClient with defaultTools(timeTools)
- DemoRunner: ApplicationRunner with ExitCodeGenerator, handles ConnectException gracefully
- SurefirePlugin: excludedGroups=live by default; profile live sets groups=live
- application.yaml: spring.ai.ollama.chat.* configuration with granite4.1:8b, temperature 0, num-ctx 8192

**Test Implementation** (Strict TDD order):
1. TimeToolsTest: zone parsing (Buenos Aires, Rome), valid ISO output, invalid zone error, null/blank handling
2. ToolCallbacks.from metadata resolution
3. DemoRunnerTest: lambda ChatModel, OutputCaptureExtension, tool callback invocation
4. ChatClientWiringTest: @SpringBootTest, @MockitoBean ChatModel, ArgumentCaptor, tool invocation assertions
5. AgentApplicationTests: OllamaChatOptions value assertions
6. LiveTimeToolTest: @Tag("live"), asserts 09:00 with fixed Clock, run via ./mvnw test -Plive

## Deferred Items (Change B)

Per design doc and launch prompt, these are deferred to the model-smoke-test (change B):
- HttpConnectTimeoutException handling in DemoRunner (currently not caught, would show stack trace on blackholed host)
- Demo profile-activation automatic test (currently verified manually via DemoRunnerTest lambda setup)
- Windows console UTF-8 encoding for non-ASCII output (out of scope)

## Final State

All 30 implementation tasks are complete and marked in tasks.md. The CRITICAL process-evidence gap (missing TDD Cycle Evidence table) was resolved in commit fd20282. The specs are merged into openspec/specs/ and serve as the source of truth for these capabilities. Runtime behavior is fully verified and the code integrates with the local Ollama model as specified. The change folder has been moved to the archive with full integrity verification.

## Handoff

The project now has a working Spring AI agent using local Ollama model calls. Change B (model-smoke-test) will confirm model choice and handle remaining edge cases.
