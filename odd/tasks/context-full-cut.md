# context-full-cut

## Objective

A report the model could not finish because the context window filled is named for that cause, not for the report cap, and a local replay can set the context window per run and records it. Then HT is rerun with a larger window as a measured knob (ADR-010: "It stays a measurable knob").

## Problem

In HT (`evals/results/2026-11-05-hybrid-today-re2ob-dev/notes.md`), 5 report attempts were rejected as "the answer was cut at 4096 tokens". In all 5, promptTokens + completionTokens = 8192 = `num-ctx`; no completion came near 4096, and the 29 valid ones had a median of 318 tokens and a maximum of 443.

- **How a cut is detected:** only by the finish reason, `length` or `max_tokens` (`AgentLoop.java:870-874`).
- **What the message names:** always the cap (`:725-731`, ACT note `:540`).
- **What the retry adds:** the error text is appended to the prompt (`Prompts.retry`, `Prompts.java:419-424`), so it has even less room.
- **The context window cannot be set per run:** `replay-agent.sh` has no override for `num-ctx`, and no output records it.

## Why

The plan's cap rule fired on a cause the cap did not have, so the human was pointed at the wrong lever. A window that cannot be set and recorded per run cannot be measured.

## Scope

- **T2:** the loop names a full context window. When a cut answer's promptTokens + completionTokens reach the route's context window, the error says the context window filled. Otherwise it names the cap, as today. The ACT step note follows the same rule. Routes with no known window (cloud) keep today's text.
- **T3, the `--num-ctx` option:** `replay-agent.sh --num-ctx N` exports `KINTSUGI_AGENT_MODEL_NUM_CTX`, and the script unsets it otherwise. `summary.tsv` gains a `numCtx` column for local routes, `-` elsewhere. The scorer keeps reading old TSVs.
- **T3, documentation:** the living specs and `docs/setup.md`.
- **T4:** a committed measurement plan, then the HT rerun with `--num-ctx 12288`, scored against 0, 0R, HR and HT.

Out of scope:

- the default `num-ctx`, which stays 8192 (ADR-010, the human's choice);
- the report cap;
- the prompt and the retry text, apart from the cut error's wording.

## Constraints

- **Freeze:** T2 and T3 touch `agent/src/main/`, so they close before 2026-12-01.
- **Defaults unchanged:** with no option, every route's call options are today's (`ModelRouteTest`, `LocalRouteSettingsTest`).
- **Old results still score and regenerate byte for byte:** `numCtx` is an optional column.
- **Measurement rules:** a committed plan before the run, Wilson 95% CIs, Config 0 alongside, no holdout.

## Acceptance criteria

- AC1: a scripted cut with prompt + completion ≥ the route's `numCtx` yields an error naming the full context window. A scripted cut below it still names the cap. The existing cut tests keep passing.
- AC2: `KINTSUGI_AGENT_MODEL_NUM_CTX=12288` reaches the local call options and the warm-up. Without it, the window is 8192.
- AC3: `replay-agent.sh --num-ctx` passes it. Without the option, the variable is unset even when it is set in the shell. `summary.tsv` records `numCtx`.
- AC4: `cd evals && .venv/bin/python -m pytest -q` passes, and `./mvnw test` passes in `agent/`.
- AC5: the rerun follows its committed plan, and its results are committed under `evals/results/`.

## Tasks

- [x] T1 This document. Route: inline (one file).
- [x] T2 Context-full message, test-first (`AgentLoop`, `ModelRoute`/`OllamaRoute`, `ScriptedChatModel`, `AgentLoopTest`). Route: delegated writer, T2 and T3 together (2+ non-trivial files).
- [x] T3 The `--num-ctx` override and the `numCtx` column (`replay-agent.sh`, `ReplayDriver`, `LocalRouteSettingsTest`, `ReplayDriverTest`, evals reader if needed, `openspec/specs/agent/spec.md`, `docs/setup.md`).
- [x] T4 Plan, rerun, score, notes.

## Delivery

Strategy `feature-branch-chain`, the standing preference. The forecast for T2–T3 is about 250 authored lines. Branch `feat/context-full`, from `898e50f`.

## Progress

- T1: created.
- T2: `8207f37` fix(agent): name a full context window instead of the report cap. `ModelRoute.contextWindow()` (empty by default, `num-ctx` on `OllamaRoute`); a cut answer with prompt + completion ≥ the window names the window in the report error and the ACT note, otherwise the cap as before. RED observed first: `aReportCutByAFullContextWindowNamesTheWindowNotTheCap` and `anActAnswerCutByAFullContextWindowNotesTheWindow` failed, the below-window and cloud tests passed; then GREEN, `AgentLoopTest` 62/62, agent suite 780/780 (`./mvnw test`). AC1 met.
- T3: `461e2c5` feat(replay): set and record the context window per run. `--num-ctx N` (1 to 999999) exports `KINTSUGI_AGENT_MODEL_NUM_CTX`, cleared otherwise, refused off the local route (exit 2, checked by hand). `LocalRouteSettingsTest` proves the env var reaches the calls and the warm-up (12288; 8192 without it): Spring's relaxed binding needed no code. `numCtx` sits after `reportTimeout` in `summary.tsv` and `aggregate.tsv` (`-` on cloud and rules). RED: `ReplayDriverTest` did not compile against the 4-field `Settings`; GREEN after. Agent suite 782/782; evals 409 passed, including a new reader test with `numCtx`. AC2, AC3 (script part) and AC4 met.
- T1: `fad68f3`.
- Review of `fad68f3..5399538` (T2 and T3): high risk, four lenses, consent granted by standing preference, approved and acknowledged (`review-5062840a9a5835e0`). Advisories:
  - R2: `docs/setup.md` said "four route-setting columns" for `aggregate.tsv`. Fixed.
  - R2: a test comment named the test's 512 cap as HT's cap. Fixed.
  - R3-001: if Ollama's prompt count left out a cached prefix, a full window would be reported as a cap cut again. The fallback is today's message. In HT all 5 cut attempts, the retries included, summed to exactly 8192. Follow-up.
  - R3-002: the `--num-ctx` bounds and its refusal on other routes are checked by hand only, like the earlier override options. Follow-up.
- T4: plan `evals/plans/2026-11-05-hybrid-ctx12k-re2ob-dev.md` (label HC).
- T4: HC run `replay-20261105T115842Z-local`, results `evals/results/2026-11-05-hybrid-ctx12k-re2ob-dev/`.
  - 30/30 `COMPLETED`, 0 cut report attempts, 24/30 AC@1 (the ceiling). HC − HT is 1 to 0, p = 1.
  - The deadline rule fired: the maximum sample time was 2,540 s, over 1,800 s. That decision is the human's. AC5 met.
- Closed: the human kept the default window at 8192. A dated amendment is in ADR-010. ADR-023's deadline is unchanged, because the rule fired only at 12288.
