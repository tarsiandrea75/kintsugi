# ADR-003: Local models as default, APIs as baseline

## Status

Accepted

## Date

2026-10-05

## Context

The vision makes local models the default and keeps a commercial API as a baseline and fallback (`docs/vision.md`, sections 4 and 5). The development hardware is a laptop with 32 GB RAM and no GPU, so the local model is a quantized ~8B model. ADR-006 put `granite4.1:8b` in use provisionally, pending a tool-calling smoke test.

The vision's plan B triggers when fewer than half of tool calls are valid, or when an agent step takes more than a minute (`docs/vision.md:236-237`). The model had to be chosen on evidence before M1 builds on it.

The smoke test ([feature document](../../odd/tasks/model-smoke-test.md)) sent 10 fixed prompts to each candidate through the Spring AI tool-calling path, with one time-zone tool:

- 8 positive prompts that need the tool (direct, indirect, ambiguous, two zones, time delta).
- 2 negative prompts that must not call it.

Adoption bar: at least 80% valid positives (7 of 8) and at least 1 of 2 negatives without a call. A positive is valid when the model makes at least one tool call and every call uses a valid IANA zone without an error result.

Setup: Ollama, CPU only, 32 GB RAM, temperature 0, `think=false` for the qwen models, clock fixed at 2026-10-05T12:00:00Z.

Results ([granite4.1:8b](../evidence/smoke-test/granite4.1_8b.md), [qwen3:8b](../evidence/smoke-test/qwen3_8b.md), [qwen3.5:9b](../evidence/smoke-test/qwen3.5_9b.md)):

| Model | Verdict | Valid positives | Negatives without a call | First-attempt validity | Median latency | Max latency |
|---|---|---|---|---|---|---|
| `granite4.1:8b` | ADOPT | 8 of 8 | 2 of 2 | 8 of 8 | 14.3 s | 63.5 s |
| `qwen3:8b` | ADOPT | 8 of 8 | 2 of 2 | 8 of 8 | 14.6 s | 89.6 s |
| `qwen3.5:9b` | ADOPT | 8 of 8 | 2 of 2 | 8 of 8 | 34.9 s | 76.5 s |

No prompt threw. Each max latency is P1, the first prompt, which includes loading the model (cold start).

## Decision

- Local models are the default. `granite4.1:8b` is the local default model.
- `qwen3:8b` is the documented alternative. It is practically tied with `granite4.1:8b` and needs `spring.ai.ollama.chat.think=false`.
- `qwen3.5:9b` is not chosen: it is more than twice as slow, with no gain in validity.
- The API baseline is deferred until a second provider exists (M2, with the `ModelRouter`). No API provider or model is chosen here. ADR-012 (2026-10-18) chose Anthropic's `claude-haiku-5-5` as the development route; the local model stays the verification route.
- Revisit the choice when the M1/M2 evals can discriminate between models.

Scope of the evidence: this is a smoke test of the Spring AI tool-calling path, not a system evaluation. It checks that a model calls tools reliably enough to build on.

Why the harness is in Java and not under `evals/`: ADR-001 puts evaluation code in Python under `evals/`. This harness needs to observe each tool call as the model makes it, so it wraps the tool in a recording `ToolCallback` that runs inside the JVM. It is a `@Tag("live")` JUnit test (`LiveToolCallingSmokeTest`), run with `./mvnw test -Plive`. The Python evals keep their role for system-level benchmarks.

Limitations:

1. The test is saturated. With one tool and simple prompts it confirms the adoption bar but does not rank the models.
2. The validity rule checks that a call used a valid IANA zone and did not error, not that it used the zone the prompt asked for. A manual check of the reports shows that all recorded zones were correct.
3. Each prompt ran once.
4. The informational "expected in answer" column misses 12-hour formats such as "8:00 AM".

## Alternatives considered

- **`qwen3:8b` as default**: same validity and median latency. Not chosen because there is no measured difference, and its thinking mode must be disabled to stay fast on CPU. It stays the documented alternative.
- **`qwen3.5:9b` as default**: same validity, but a median latency of 34.9 s against 14.3 s, which uses more than half of the 60 s plan B budget on a single step.
- **API model as default**: contradicts the local-first goal of the vision and adds cost and data exposure. The API remains a baseline, deferred to M2.
- **Run the harness under Python `evals/`**: cannot record tool calls inside the Spring AI tool-calling loop without a JVM component.

## Consequences

### Positive

- The default model is backed by recorded evidence, and the smoke test is repeatable with one command per model.
- A practically tied alternative is documented, so switching model is a configuration change.
- Both chosen models stay far from the plan B triggers.

### Negative

- The test cannot rank the candidates, so the choice between `granite4.1:8b` and `qwen3:8b` is provisional until harder evals exist.
- One run per prompt does not measure variance.
- The API baseline is not yet defined, so configurations C and D of the vision cannot be compared until M2.
