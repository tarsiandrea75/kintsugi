# Delta Spec: test-tooling (new capability)

## Requirements

### REQ-1 Default test run is offline
`./mvnw test` (from `agent/`) MUST pass with no Ollama running and MUST exclude tests tagged `live`.

#### Scenario: Default run
- Given Ollama is not running
- When `./mvnw test` runs in `agent/`
- Then the build succeeds
- And no `@Tag("live")` test is executed

### REQ-2 Live profile
Maven profile `live` MUST run the `live`-tagged tests.

#### Scenario: Live run
- Given Ollama running with `granite4.1:8b`
- When `./mvnw test -Plive` runs
- Then the `live` tests execute and pass

#### Scenario: Live skipped when unavailable is not silent
- Given Ollama not running
- When `./mvnw test -Plive` runs
- Then the live test fails (it is not reported as passed or skipped silently)

### REQ-3 Strict TDD configuration
`openspec/config.yaml` MUST set `strict_tdd: true` and `testing.runner.command` to `./mvnw test` (run from `agent/`) with framework JUnit; `testing.layers.unit` SHOULD be true.

#### Scenario: Config readback
- When `openspec/config.yaml` is read
- Then `strict_tdd` is `true`, `testing.runner.command` is `./mvnw test`, and the framework is set (non-null)

### REQ-4 Maven Wrapper
`agent/mvnw`, `mvnw.cmd`, and `.mvn/` MUST exist; `agent/mvnw` MUST carry the executable bit in the Git index.

#### Scenario: Wrapper present
- Then `agent/mvnw` is mode 100755 in the index and `./mvnw -v` runs

### REQ-5 ADR-006 stack versions
`docs/adr/ADR-006-stack-versions.md` MUST record Boot 4.1.1, Java 21, Spring AI 2.0.1, JUnit version in use, and the Ollama starter; the model MUST be marked "pending change B". `docs/setup.md` and `docs/STATE.md` MUST link the ADR and list run commands (`./mvnw test`, `./mvnw test -Plive`, demo run with profile `demo`).

#### Scenario: ADR content
- When ADR-006 is read
- Then it lists the versions above
- And the model entry says "pending change B"

#### Scenario: Docs updated
- Then `docs/setup.md` documents default, live and demo commands, and STATE.md's versions-ADR question is marked resolved by ADR-006
- And `docs/vision.md`, `docs/concepts.md` and the planning journal are unchanged
