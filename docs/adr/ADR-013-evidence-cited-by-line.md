# ADR-013: Evidence is cited by line number, and code fills in the quote

## Status

Accepted

## Date

2026-10-23

## Context

A report backs its cause with evidence items. Until now each item carried a `quote` that the model wrote itself, and code checked that the quote, normalized, was a substring of the cited step's output or summary, or of the rendered dossier (ADR-011). The model had to do two things: choose the evidence, and copy it exactly.

The local replay of 2026-10-23 (`odd/tasks/local-dossier-timing.md`, `infra/scripts/out/replay-20261022T220256Z-local`, `granite4.1:8b`, three samples per fault) showed that copying is where the small model fails:

| Measure | Result |
|---|---|
| Runs ended `INVALID_REPORT` ("report invalid twice") | 5 of 9 |
| Cause | in almost every report, each `evidence[].quote` failed the substring check |
| How the quotes differ | granite rebuilds a dossier row in its own format (`gateway-mock \| client \| POST /authorize \| status: 429 \| exception: TooManyRequests`) or shortens a log line |
| Valid F1 and F2 reports | every one named the right cause |
| Report attempts cut at the 512-token cap | 4 |

So the F1 and F2 misses come from copying, not from reasoning. A run survived only when one item happened to be exact. The quotes are also a large share of the report's answer, which is why attempts were cut at 512 tokens.

ADR-011 put most of the evidence in a deterministic dossier the model reads. This decision keeps that dossier and its facts-only rule, and changes only how a report points at a fact in it or in a tool summary. The feature is tracked in `odd/tasks/evidence-by-reference.md`.

## Decision

### Numbered lines

- **Dossier:** every line of the rendered dossier that is not a heading, a blank line, a table header or a table separator starts with its number, `D1`, `D2`, … in order (bullet facts, table rows, log-signature lines).
- **Tool summaries:** every line of a step's summary after its header is numbered from 1 within that step (`1:`, `2:`, …). The header (`toolCallId=N tool=X …`) and the cut note are not numbered.
- Code writes the numbers when it renders, so the recordings and the replay need no change.

### What the model writes

An evidence item is `{toolCallId, tool, line, why}`:
- `toolCallId` and `tool` as before: an integer step, or `"dossier"`. `tool` stays as a cross-check that catches a wrong step.
- `line`: an integer of at least 1, the line's number in that source. `D7` is written as `line: 7` with `toolCallId: "dossier"`.
- The model writes no `quote`.

### What code checks and stores

- The step rules stay: a step that is not of this incident, or was refused, is fatal; a wrong `tool` is dropped.
- A line that does not exist in its source is dropped: `line N is not in the dossier`, or `line N is not in the output of step S`.
- Code fills `quote` on each verified item with the cited line's text without its number, capped at 160 characters. The stored item in `incidents.report` is `{toolCallId, tool, line, quote, why}`.
- Valid and invalid reports, the one retry and "report invalid twice" are unchanged.

Why: "the LLM proposes, deterministic code verifies". A line number is an exact, checkable choice, and the text then comes from code, so the stored quote is verbatim by construction. Verification stays exact without asking a small model to copy text.

## Alternatives considered

- **Looser quote matching (fuzzy or token overlap):** rejected. It would admit evidence the outputs do not hold, and a stored quote would no longer be text the tool returned.
- **Keep the quotes and prompt the model harder to copy exactly:** rejected. It is prompt-tuning measured on a few samples, the vicious circle the human raised on 2026-10-20 (`docs/journal/2026-10-20-01.md`), and it attacks the small model's weak skill instead of removing the need for it.
- **A line number and a quote together:** rejected. The quote would still have to match, so the copying failure stays, and the answer stays as long.

## Consequences

### Positive

- Every stored quote is verbatim by construction: code copies it from the line the model chose.
- The report's answer is shorter: five quotes of up to 160 characters leave it, about 150-200 tokens fewer, about 50-60 s less writing at 3 tokens/s on the laptop's CPU.
- Fewer report attempts are cut at the 512-token cap.
- The stored quote keeps each citation readable for a human check of the report.
- The check is simpler: the line exists or it does not, with no normalization or escape rules.

### Negative

- A number does not make the model read the text the way copying it does: the model may cite a line that does not support its `why`. The replay measures this through the match rate.
- The prompt grows: about 150 dossier lines at about 2 tokens per number is about 300 tokens, about 27 s more on the laptop's CPU when the dossier is read uncached (the slowest call measured is 510 s, under the 600 s deadline).
- The cloud route changes too, since there is one contract: its match rate must be measured on the replay again (cents, with the human's go-ahead).
- The stored evidence item gains `line`, so readers of `incidents.report` see one more field.
