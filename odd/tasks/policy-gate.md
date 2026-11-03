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
- [ ] T2 The agent: risk table, `Policy`, `REFUSED_POLICY`, migration, counts, summary columns; tests first, including a scripted model that calls an unknown tool and gets `REFUSED_POLICY` with no call made; the agent's spec. Route: delegated writer.
- [ ] T3 The scorer's totals and `benchmark.md`'s line; tests first; the evals' spec. Route: delegated writer, own commit.
- [ ] T4 The pre-close check: rules v1 and v2 on lab dev, lab holdout and RE2-OB dev unchanged but for the new columns; the agent's full test suite. Route: inline commands.

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

## Next step

T2.
