# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-05

## Milestone

M1 — The lab (Oct 5–25)

## Last action

Spring AI hello-world done (SDD change `spring-ai-hello-tool`, archived): `granite4.1:8b` called the `currentTime` tool and answered from a fixed clock, verified by a live test. Versions pinned in ADR-006.

## Next action

Change B: tool-calling smoke test (~10 prompts) to confirm the local model, then record the choice in ADR-003.

## Open questions

- Adoption bar for the smoke test (proposed: at least 80% valid tool calls; the vision's plan B triggers below 50%).
- When to make the GitHub repository public (private for now; the vision assumes public from week 1).

## Follow-ups (for change B or later)

- Automated test for the `demo` profile activation (verified manually only).
- `DemoRunner` does not treat `HttpConnectTimeoutException` (blackholed host) as a connection failure.
- Windows console prints `?` for non-ASCII model output; try `-Dstdout.encoding=UTF-8`.
- Docker Desktop is not installed; needed for the M1 lab (Docker Compose).

## Blockers

None.
