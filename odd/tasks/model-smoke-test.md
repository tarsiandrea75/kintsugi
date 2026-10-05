# Feature: model-smoke-test (change B)

Locator: `odd/tasks/model-smoke-test.md` · Engram mirror: `odd/model-smoke-test/tasks` · Branch: `feat/model-smoke-test`

## Objective

Confirm which local model reliably calls tools, using a 10-prompt tool-calling smoke test, and record the choice in ADR-003.

## Problem and why

`granite4.1:8b` is in use "pending change B" (`docs/adr/ADR-006-stack-versions.md:34`). The vision's plan B triggers when under half of tool calls are valid (`docs/vision.md:237`). The model must be chosen on evidence before M1 builds on it. The existing live test checks only the final text, not the tool calls.

## Scope

- In: a recording wrapper around tool callbacks, a parameterized live smoke test with 10 prompts, a per-model report, runs against the 3 candidates, ADR-003, doc updates.
- Out: `ModelRouter` (M2), Python evals (`evals/`), the follow-ups deferred from the hello-world change (`docs/STATE.md:26-30`).
- Workflow: the human chose ODD for this change instead of SDD (2026-10-05).

## Constraints

- Code and docs in English, including the test prompts.
- Offline tests never call the model. Live tests use `@Tag("live")` and run with `./mvnw test -Plive`. If Ollama is down, they fail and are not skipped (`openspec/specs/test-tooling/spec.md:22-25`).
- The harness stays in Java, because the recording wrapper lives in the JVM. ADR-003 records why this is not under the Python `evals/` code (ADR-001).
- The model is selected with `-Dkintsugi.model=<name>`. The default is the configured `granite4.1:8b`.
- The clock is fixed at `2026-10-05T12:00:00Z`, as in `LiveTimeToolTest`.

## Prompts (fixed clock 12:00Z)

| ID | Kind | Prompt | Expected |
|----|------|--------|----------|
| P1 | direct | What time is it in Buenos Aires? | call, 09:00 |
| P2 | direct | What is the current time in Tokyo? | call, 21:00 |
| P3 | direct | Tell me the current date and time in New York. | call, 2026-10-05 08:00 |
| P4 | indirect | Is it already evening in Tokyo? | call, yes (21:00) |
| P5 | indirect | Would a shop in Rome that opens at 9am be open right now? | call, yes (14:00) |
| P6 | ambiguous | What time is it in Portland? | call with any valid zone |
| P7 | two zones | What time is it in London and in Los Angeles? | 2 calls, 13:00 and 05:00 |
| P8 | delta | How many hours ahead of New York is Tokyo right now? | call(s), 13 |
| N1 | negative | What is the capital of Japan? | no call |
| N2 | negative | How many minutes are in a day? | no call |

## Acceptance criteria (adoption bar, decided 2026-10-05)

- A positive prompt is valid when the model makes at least one tool call and every call has a valid IANA zone (no `Error:` result).
- Adopt a model when at least 80% of positive prompts are valid (7 of 8 rounds up to 87.5%; 6 of 8 is 75%, which fails) **and** at least 1 of 2 negatives has no tool call.
- Under 50% valid triggers plan B (`docs/vision.md:237`). A median latency per step over 1 minute is the latency trigger (`docs/vision.md:236`).
- Informational, not gating: first-attempt argument validity, whether the answer contains the expected value, median and max latency.

## Delivery

- Strategy: `ask-on-risk`. Forecast was about 500 authored changed lines; T1+T2 actually came to 721 (355 code, 366 tests).
- Chain strategy: `feature-branch-chain` (decided 2026-10-05). The tracker branch is `feat/model-smoke-test` (draft, no-merge tracker PR). Children: PR 1 `feat/model-smoke-test-01-recorder` → tracker; PR 2 `feat/model-smoke-test-02-harness` → PR 1; PR 3 `feat/model-smoke-test-03-adr` → PR 2.
- Slices: PR 1 = T1 (221 lines). PR 2 = T2 (500 lines, accepted over budget by the human; it holds two commits, scoring/report 375 and live test 125, so it can be split if review needs it). PR 3 = T3 + T4.
- Push and PR creation are human decisions.
- RDD: on (global). Review is assessed after each work-unit commit.

## Tasks

- [x] **T1 — Recording tool wrapper.** A `ToolCallback` decorator that records the tool name, the arguments, whether the result is an error, and the latency. Offline unit tests (RED first). Route: delegated (writer, with T2).
- [x] **T2 — Smoke test harness and report.** Pure scoring and report logic, with offline tests (RED first). A parameterized `@Tag("live")` test over the 10 prompts, with the model chosen by `-Dkintsugi.model`. It writes a Markdown report to `agent/target/smoke-test/<model>.md` and asserts the adoption bar. Route: delegated (writer, 2+ non-trivial files).
- [ ] **T3 — Run the candidates.** Run against `granite4.1:8b`, `qwen3:8b` and `qwen3.5:9b`. qwen3 needs `spring.ai.ollama.chat.think=false`. Copy the reports to `docs/evidence/smoke-test/`. Route: per-action worker (live runs).
- [ ] **T4 — ADR-003 and docs.** Write ADR-003 with the ADR-001 headings. Update ADR-006:34, `docs/setup.md` (smoke-test checkbox and command) and the model line in the `repo-structure` spec. Route: inline or delegated, depending on the results.

## Checks

- Offline: `cd agent && ./mvnw test`
- Live: `cd agent && ./mvnw test -Plive -Dkintsugi.model=<model>`

## Progress

- 2026-10-05: exploration done; adoption bar and English-only indirect prompts decided; branch created.

- 2026-10-05: T1+T2 done by one delegated writer (trigger: 2+ non-trivial files). RED: compile failure, then `recordsAnErrorResultAsError` failed because `MethodToolCallback` JSON-quotes String results (`"\"Error: ...\""`); fixed by stripping the leading quote. GREEN: `./mvnw test` 32 tests, 0 failures (parent re-run with `clean`); `./mvnw test-compile -Plive` OK. Live smoke test not run yet (T3). `pom.xml` unchanged: surefire already forwards `-Dkintsugi.model`.

## Next step

T3: run the live smoke test against the three candidates (needs Ollama and the models pulled).
