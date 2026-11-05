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
- AC4: `cd evals && .venv/bin/python -m pytest -q` passes, and `./gradlew test` passes in `agent/`.
- AC5: the rerun follows its committed plan, and its results are committed under `evals/results/`.

## Tasks

- [ ] T1 This document. Route: inline (one file).
- [ ] T2 Context-full message, test-first (`AgentLoop`, `ModelRoute`/`OllamaRoute`, `ScriptedChatModel`, `AgentLoopTest`). Route: delegated writer, T2 and T3 together (2+ non-trivial files).
- [ ] T3 The `--num-ctx` override and the `numCtx` column (`replay-agent.sh`, `ReplayDriver`, `LocalRouteSettingsTest`, `ReplayDriverTest`, evals reader if needed, `openspec/specs/agent/spec.md`, `docs/setup.md`).
- [ ] T4 Plan, rerun, score, notes.

## Delivery

Strategy `feature-branch-chain`, the standing preference. The forecast for T2–T3 is about 250 authored lines. Branch `feat/context-full`, from `898e50f`.

## Progress

- T1: created.
