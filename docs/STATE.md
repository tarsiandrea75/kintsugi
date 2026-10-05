# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-05

## Milestone

M1 — The lab (Oct 5–25)

## Last action

Change B done (ODD feature `model-smoke-test`, [feature document](../odd/tasks/model-smoke-test.md)). A 10-prompt tool-calling smoke test ran against three local models. All three met the adoption bar with 8 of 8 valid positives and 2 of 2 negatives. [ADR-003](adr/ADR-003-local-models-default.md) keeps `granite4.1:8b` as the local default (median 14.3 s per prompt), with `qwen3:8b` as the alternative. All commits are local, on a feature-branch chain that has not been pushed.

## Next action

Decide on pushing the chain and opening the PRs: a draft tracker PR `feat/model-smoke-test` and the children `-01-recorder` → `-02-harness` → `-03-adr`. Then take the next M1 step from the vision: the `order-service` skeleton with PostgreSQL, as an ODD feature with a living spec.

## Decisions since the last session

- Workflow: ODD with mandatory living specs in `openspec/specs/` (`CLAUDE.md`, Working conventions). Decided 2026-10-05.
- Docker is available: Docker Engine 29.4.0 and Compose v5.1.3 inside WSL. The earlier "Docker Desktop is not installed" note was wrong.

## Open questions

- When to make the GitHub repository public (private for now; the vision assumes public from week 1).

## Follow-ups

Smoke-test harness (non-blocking review findings):
- The `@AfterAll` report is skipped on a partial run (a filtered case or a non-`RuntimeException` throwable).
- The validity rule does not check that the zone matches the prompt.
- The report header omits the `think` setting, so the qwen evidence does not show `think=false`.
- The expected-value check misses 12-hour formats such as "8:00 AM", and a backtick in the arguments breaks the report row.

Carried over:
- Automated test for the `demo` profile activation (verified manually only).
- `DemoRunner` does not treat `HttpConnectTimeoutException` (blackholed host) as a connection failure.
- Windows console prints `?` for non-ASCII model output; try `-Dstdout.encoding=UTF-8`.

## Blockers

None.
