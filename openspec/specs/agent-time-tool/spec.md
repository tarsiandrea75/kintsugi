# Delta Spec: agent-time-tool (new capability)

Source: proposal.md (incl. Behavior Decisions approved 2026-10-05). Fixed clock used in all scenarios: `2026-10-05T12:00:00Z`.

## Requirements

### REQ-1 currentTime tool contract
`TimeTools.currentTime(String zone)` MUST be annotated `@Tool`, use an injected `Clock`, and return the current time in the given IANA zone as ISO-8601 with offset. It MUST NOT use the system clock directly.

#### Scenario: Valid zone Buenos Aires
- Given a fixed `Clock` at 2026-10-05T12:00:00Z
- When `currentTime("America/Argentina/Buenos_Aires")` is called
- Then the result is `2026-10-05T09:00:00-03:00`

#### Scenario: Valid zone Rome
- Given the same fixed `Clock`
- When `currentTime("Europe/Rome")` is called
- Then the result is `2026-10-05T14:00:00+02:00`

#### Scenario: Result is parseable
- Given the same fixed `Clock`
- When `currentTime` is called with any valid zone
- Then the result parses with `OffsetDateTime.parse` and denotes the same instant as the clock

### REQ-2 Invalid input returns an error message
For an unknown or blank zone the tool MUST return an error message string (for the model to recover from) and MUST NOT throw. The message SHOULD mention that an IANA zone id is expected.

#### Scenario: Unknown zone
- Given a fixed `Clock`
- When `currentTime("Mars/Olympus")` is called
- Then no exception is thrown
- And the result is a non-empty error message naming the invalid zone and not parseable as a date-time

#### Scenario: Blank zone
- When `currentTime("")`, `currentTime("   ")` and `currentTime(null)` are called
- Then none throws, and each returns a non-empty error message

### REQ-3 Registration with ChatClient (no live model)
The `ChatClient` bean MUST be wired with `TimeTools` as a default tool. This MUST be verifiable without a running model.

#### Scenario: Context wires tool and client
- Given the Spring context with a mocked/stubbed chat model and no Ollama running
- When the context starts (no `demo` profile)
- Then a `ChatClient` bean and a `TimeTools` bean exist
- And the tool definition `currentTime` is discoverable from the registered tool callbacks (via `ToolCallbacks.from(TimeTools)` or the client's default options)
- And no call to the model occurs

#### Scenario: Clock is injectable
- Given the context
- Then a `Clock` bean exists and `TimeTools` uses it

### REQ-4 Live tool-call test
A test tagged `@Tag("live")` MUST ask the model for the current time in Buenos Aires with a fixed `Clock` and MUST assert the answer contains the exact `HH:mm` (`09:00`) from that clock. Other phrasings of the time count as failure.

#### Scenario: Live Buenos Aires time
- Given Ollama running with `granite4.1:8b` and a fixed `Clock` at 2026-10-05T12:00:00Z
- When the model is asked for the current time in Buenos Aires
- Then the response contains `09:00`

### REQ-5 Demo runner gated by profile
The demo `ApplicationRunner` MUST be active only under the `demo` Spring profile and print the model's tool-backed answer.

#### Scenario: Not active by default
- Given the context started without the `demo` profile
- Then no `ApplicationRunner` demo bean exists and the model is never called

#### Scenario: Active under demo
- Given the `demo` profile and a stubbed `ChatClient` answering "09:00"
- When the runner executes
- Then the answer is printed to stdout

### REQ-6 Ollama unreachable
When the model endpoint cannot be reached, the demo MUST print a clear message (Ollama not reachable at the configured base URL; start it with `ollama serve`), MUST terminate with a non-zero exit code, and MUST NOT print a raw stack trace.

#### Scenario: Connection failure
- Given the `demo` profile and a `ChatClient` that throws a connection error
- When the runner executes
- Then stderr/stdout contains "not reachable", the configured base URL and `ollama serve`
- And the exit code is non-zero
- And the output contains no stack trace lines (`\tat `, exception class dump)

### REQ-7 Configuration
`application.yaml` MUST set `spring.ai.ollama.chat.model` `granite4.1:8b`, `spring.ai.ollama.chat.temperature` 0 and `spring.ai.ollama.chat.num-ctx` 8192 (Spring AI 2.0 form; the 1.x `chat.options.*` form is deprecated).

#### Scenario: Properties bound
- When the context loads
- Then the Ollama chat options expose model `granite4.1:8b`, temperature 0.0, num-ctx 8192
