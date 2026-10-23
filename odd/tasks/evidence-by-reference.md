# Feature: evidence cited by reference

## Objective

Make local reports valid: the model cites a line of the evidence by its number, and code fills in the verbatim quote, so verification stays exact without the model copying text.

## Problem

On the local replay (`odd/tasks/local-dossier-timing.md`, 2026-10-23, `infra/scripts/out/replay-20261022T220256Z-local`), 5 of 9 runs ended `INVALID_REPORT`. In almost every report, each `evidence[].quote` failed the substring check: `granite4.1:8b` rebuilds a dossier row in its own format or shortens a log line instead of copying it. Every valid F1 and F2 report named the right cause, so the misses come from copying, not from reasoning. 4 report attempts were also cut at the 512-token cap, and the quotes are a large share of the answer.

## Why

- "The LLM proposes, deterministic code verifies": today the model must both choose the evidence and reproduce it exactly. Copying is a weakness of small models that verification does not need. A line number is an exact, checkable choice; the text then comes from code, so it is verbatim by construction.
- Looser matching (fuzzy or token overlap) was rejected: it would admit evidence the outputs do not hold.

## Design

### What the model sees

- **Dossier.** Every citable line of the rendered dossier (bullet facts, table rows, log-signature lines; not headings or blank lines) starts with its number, `D1`, `D2`, … in order. Table header and separator rows are not numbered.
- **Tool summaries.** Every line of a step's summary after its header is numbered from 1 within that step (`1:`, `2:`, …). The header (`toolCallId=N tool=X …`) and the cut note are not numbered.
- The numbers are written by code at render time, so recordings and the replay need no change.

### What the model writes

An evidence item becomes `{toolCallId, tool, line, why}`:

- `toolCallId` and `tool` as today (an integer step, or `"dossier"`); `tool` stays as a cross-check that catches a wrong step.
- `line`: an integer ≥ 1, the line's number in that source (`D7` is written as `line: 7` with `toolCallId: "dossier"`).
- `quote` leaves the model's schema.

### What code checks and stores

- The existing rules for the step stay: a step that is not of this incident or was refused is fatal; a wrong tool is droppable.
- New droppable rule: the line does not exist in that source ("line N is not in the output of step S" / "line N is not in the dossier").
- Code adds `quote`, the cited line's text without its number, capped at 160 characters as today, to each verified item. `incidents.report` keeps its shape: `{toolCallId, tool, line, quote, why}`.
- Valid/invalid, the retry and "report invalid twice" are unchanged.

### Cost

- Prompt: about 150 dossier lines times about 2 tokens is about 300 tokens more, about 27 s more on the laptop's CPU when the dossier is read uncached (the slowest call measured is 510 s, under the 600 s deadline).
- Answer: five quotes of up to 160 characters leave the report, about 150-200 tokens fewer, about 50-60 s less writing at 3 tokens/s, and fewer cuts at 512.

### Risks

- The model may cite a line that does not support its `why`. Today the exact quote makes it look at the text; a number does not. The replay measures this through the match rate; the stored quote keeps the citation readable for a human check.
- The cloud route changes too (one contract): its match rate is measured on the replay again (cents, the human's go-ahead).

## Scope

In: `DossierRenderer`, `ToolSummaries`, `Schemas`, `ReportCheck`, `AgentLoop` (storing the filled quote), `Prompts`, their tests, the agent spec, an ADR. Out: the fault catalog, mcp-tools, the replay recordings, explorer-mode heuristics.

## Constraints

- The 400-line review budget per work unit; feature-branch-chain if the feature goes over it.
- No cloud batch without the human's go-ahead and a recorded cost.
- Tests first for every behavior change.

## Acceptance criteria

1. The rendered dossier numbers every citable line `D1…Dn`; headings, blank lines, table headers and separators are not numbered.
2. A tool summary numbers every line after its header from 1; the header and the cut note are not numbered.
3. The report schema's evidence item is `{toolCallId, tool, line, why}`, with `line` an integer ≥ 1 and no `quote`.
4. An item citing an existing line of a valid source is verified, and its stored item carries `quote` equal to that line's text without its number (capped at 160 characters).
5. An item citing a line that does not exist is dropped with "line N is not in the dossier" or "line N is not in the output of step S"; the other rules are unchanged.
6. The spec ("The report", "Evidence is checked", "Evidence dossier rendering") and its scenarios describe the new contract; an ADR records the decision.
7. Measured on the local replay (3 samples per fault): fewer `INVALID_REPORT` runs than 5/9. On the cloud replay: a match rate not worse than the last baseline (F1 4/5, F2 5/5, F3 5/5), within noise.

## Tasks

- [x] T1 ADR-013 and the spec: the contract, requirements and GIVEN/WHEN/THEN scenarios (route: inline, docs).
- [x] T2 Numbered lines: `DossierRenderer` and `ToolSummaries`, with tests (route: to decide at the task; two non-trivial files, so likely delegated).
- [x] T3 Citation by line: `Schemas`, `ReportCheck`, `AgentLoop` storing the filled quote, `Prompts`, with tests.
- [ ] T4 Measurement: the local replay, 3 samples per fault (free, about 5 hours), then the cloud replay, 5 samples per fault (cents, the human's go-ahead).

## Progress

- 2026-10-23: design written, from a read-only map of `ReportCheck`, `Schemas`, `DossierRenderer`, `ToolSummaries`, `Prompts` and the spec. Branch `feat/evidence-by-reference`, from `feat/local-deadlines`.

- 2026-10-23: the human approved the design and left the work to run unattended until 08:00 (no cloud batch). T1 done (route: delegated, two non-trivial docs): `docs/adr/ADR-013-evidence-cited-by-line.md` and the spec ("Evidence dossier rendering", "Summaries built in code", "The report", "Evidence is checked" and their scenarios). Check: structural readback of the new requirements and the remaining `quote` mentions. Decisions taken on the writer's open points:
  - Every dossier line that is not a heading, a blank line, a table header or a separator is numbered (simpler than a list of kinds, and the window and `not shown:` lines are facts too).
  - The numbers count toward the summary's 1,500-character cap.
  - Every summary line after the header is citable, also lines code computed (`events=20`): code computes them from the tool's output, as it does the dossier, which is already citable.

- 2026-10-23: T2 done (route: delegated, two non-trivial source files). `DossierRenderer` numbers lines in its `Out` helper and exposes `line(dossier, n)`; `ToolSummaries.capped` numbers summary lines before the cap and exposes `line` and `lines`; the `search_runbooks` budget counts the numbers; a refused call's summary is `1: the call was refused…`, and `AgentLoop.reasonLine` reads numbered lines. Until T3, `quotableLines` falls back to unnumbered summaries (recorded live run). RED: 4 new tests failed on stubs; GREEN: `DossierRendererTest`, `ToolSummariesTest` 57/0/0 (parent re-ran: 12 + 45, 0 failures), full suite 424/0/0.

- T2 review (`19afa42`, base `2154ecd`, consent granted): approved; WARNING R3-quotable-strips-visible-number (the transitional quote matching, removed in T3), SUGGESTION R3-runbook-budget-untested (follow-up).
- 2026-10-23: T3 done (route: delegated). The evidence item is `{toolCallId, tool, line, why}`; `ReportCheck` resolves the line with `ToolSummaries.line` / `DossierRenderer.line` and rebuilds each verified item as `{toolCallId, tool, line, quote, why}`, the quote being the line's first 160 code points without its number. Quote matching (`quoted`, `normalized`, `inSummary`, `quotableLines`) is removed. `LiveRunReportCheckTest` and its fixture are retired: they tested the removed quote matcher on unnumbered summaries. `AnthropicSchema` needs no change (it already drops `minimum`). RED: 45 failures and 1 error in six focused classes; GREEN: 180/0/0; full suite 422/0/0; parent re-ran `ReportCheckTest` 40/0/0 and `AgentLoopDossierModeTest` 13/0/0.

- T3 review (`c2c09bf`, base `19afa42`, consent granted): approved, no findings.
- 2026-10-23: T4, local half: replay with line citations, 3 samples per fault (`infra/scripts/out/replay-20261023T032154Z-local`, on `c2c09bf`, no cloud cost).
  - **Validity solved: 1 of 9 runs `INVALID_REPORT`** (5 of 9 before). 17 of 18 report attempts were valid at the first try; the one invalid run cited a refused step twice (fatal, as before). No report was cut at 512 (4 attempts before); report answers 347-501 tokens.
  - No timeout (1,627-1,920 s per run).
  - Matches: **F1 0/3, F2 3/3, F3 0/3** (before: 2/3, 1/3, 0/3). Every F1 and F3 report is now valid and wrong: F1 names payment-service (DEPENDENCY_LATENCY, the type right), F3 gateway-mock or payment-service, DEPENDENCY_REJECTING.
  - Reading: the copying failure is gone; what is left on the local route is reasoning on F1 and F3. Whether line citations cost F1 (the design's main risk) is not settled: F1 was 2/3 before, but one of the earlier local F1 runs at 600 s already named payment-service, so local F1 is about 3 of 7 overall. Acceptance criterion 7, local half, is met (fewer invalid runs); the cloud half is pending.

## Next step

The human decides the cloud replay (5 samples per fault, cents) to check that line citations keep the cloud match rate; then local F1 and F3 reasoning, a separate problem.
