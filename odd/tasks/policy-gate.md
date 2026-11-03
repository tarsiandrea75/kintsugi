# policy-gate

## Objective

Make "zero risky actions without approval" a property of a tested, deterministic gate (ADR-027), before the freeze on 2026-12-01.

## Problem

No code checks a tool call against a risk; the zero holds only because no write tool exists (ADR-027, Context).

## Why

The core rule says the policy authorizes. A claim with no code behind it cannot be measured, and a write tool added later would slip past nothing.

## Scope

ADR-027's Decision: the risk table, the `Policy`, the `REFUSED_POLICY` outcome and its migration, the counts in the incident and the replay summary, the scorer's totals, the agent's and the evals' living specs.

Out of scope: any write tool, an approval channel, report or prompt changes.

## Constraints

- No change to the report schema, the prompt or the taxonomy: every pin stays.
- Config 0's and 0R's outputs unchanged but for the new columns.
- The lab's goldens and D's and A's dev results stay valid: decision mode makes no tool call.

## Tasks

- [x] T1 ADR-027 and this document. Route: inline (docs).
- [x] T2 The agent: risk table, `Policy`, `REFUSED_POLICY`, migration, counts, summary columns; tests first, including a scripted model that calls an unknown tool and gets `REFUSED_POLICY` with no call made; the agent's spec. Route: delegated writer.
- [x] T3 The scorer's totals and `benchmark.md`'s line; tests first; the evals' spec. Route: delegated writer, own commit.
- [x] T4 The pre-close check: rules v1 and v2 on lab dev, lab holdout and RE2-OB dev unchanged but for the new columns; the agent's full test suite. Route: inline commands.

## Acceptance criteria

- AC1: every current tool is `READ`; an unknown tool is `WRITE` and refused with `REFUSED_POLICY`, its call never reaching the MCP client.
- AC2: a refusal is stored as a step, and the incident's counts show 0 executed and 1 refused.
- AC3: the system prompt and the report schemas are unchanged (the pins).
- AC4: the scorer prints both totals per config, and the benchmark line fails if the executed total is not 0.

## Checks

- T2: `cd agent && ./mvnw -q test`, red first. T3: `cd evals && .venv/bin/python -m pytest -q`, red first. T4: as stated.

## Delivery

Forecast: about 300 authored lines. Branch `feat/policy-gate`. Nothing is pushed.

## Progress

- 2026-11-03: the human chose the minimal version (ADR-027).
- 2026-11-03: T2 done by a delegated writer (`1363fef`): `agent/.../policy/` (`Risk`, `Policy`, `PolicyCounts`), the gate at `AgentLoop.java`:577 after the budget, phase, placeholder and duplicate checks; the gatherer asks it before every call (`DossierGatherer.java`:394); `V8__policy_refusal.sql`; summary columns `riskyExecuted` and `policyRefused`. RED: with the gate's branch off, the fake lab received the call; GREEN `./mvnw -q test` 724 run, 0 failures; parent spot check of the policy, loop and gatherer tests passes. About 460 authored lines. Prompt and schema pins unchanged. AC1–AC3 hold.
  - The writer's choices: a name no phase offers is stopped by the phase check first, so the policy's own test uses an injected empty table; `riskyExecuted` is counted from the steps that ran, not set to 0, so a write that got through would show.
  - **Gap:** a gatherer refusal throws and ends as the existing "gathering failed" message, so it is not counted in `policyRefused`. No current tool triggers it.

- 2026-11-03: native review of `42de768..319f860`: one reliability lens, **approved** and acknowledged (lineage `review-250cfd398e044aad`). WARNINGs: a gatherer refusal not counted in `policyRefused`, nor calls that ran before a gathering timeout in `riskyExecuted`; no loop-level test of a refused gathering call. SUGGESTION: the replay's rules rows used fixed counts.
- 2026-11-03: fixed by a delegated writer (`5420f20`): a `PolicyTally` the gatherer fills before each call and refusal, so the counts hold whether the gathering finished, failed or timed out; loop tests in dossier and decision mode; the rules rows count from the actual tally. RED: counts `[0, 0]` where `[0, 1]` was expected; GREEN 728 run, 0 failures.
- 2026-11-03: T3 done by the same writer (`bd334e1`): the scorer reads both columns (missing or `-` is unknown, never 0), prints a per-config policy line and writes `policy.tsv` only when an input knows the counts, so every committed result regenerates as before; `benchmark.py` renders the line and exits 2 if a config with holdout rows has a `riskyExecuted` total that is not 0 or not known for every row. RED `ImportError`; GREEN 364 passed. REQ-15. AC4 holds.
  - Found on the way: five older results (before `candidate_recall`) do not regenerate `scores.tsv` byte for byte, which was already so; their `report.txt` and `paired.tsv` do.
- 2026-11-03: T4 done on `bd334e1`: rules v1 and v2 on RE2-OB dev (`replay-20261103T232445Z-rules`, `-232449Z-`), lab dev (`-232453Z-`, `-232457Z-`) and lab holdout (`-232501Z-`, `-232506Z-`) equal the earlier runs in every column but `seconds`, `ranked` and the two new ones; all 120 samples record `riskyExecuted` 0 and `policyRefused` 0.

## Next step

The feature is complete. `docs/benchmark-limits.md`: the zero is now the gate's, not structural.
