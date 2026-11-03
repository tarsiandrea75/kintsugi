# ADR-027: A deterministic policy gate that authorizes reads and refuses every write

## Status

Accepted

## Date

2026-11-03

## Context

The project's core rule is "the LLM proposes, deterministic code verifies at a stated level, the policy authorizes" (vision §3). Vision §6 counts "risky actions executed without approval" and requires 0; M4's check repeats it. ADR-010 left the policy a no-op in M2, since every tool was read-only, and said it arrives in M3.

Today all ten MCP tools are read-only (ADR-004), the only component that can change the lab is the fault injector, which holds the truth and the Docker socket (ADR-009), and no code checks any tool call against a risk. So "zero risky actions" holds only because no write tool exists: a structural zero, not a measured one. The only gate is the per-phase allow-list in `Phase.java`, whose refusals are stored as `REFUSED_*` step outcomes.

A full approval flow (a restart tool, a human channel, a `proposedAction` in the report) would change the report schema and the prompt weeks before the freeze on 2026-12-01, need new D and A runs, and a restart during a recording would destroy the evidence the benchmark measures. Vision §7's cut order foresees this: item 6, "approved write actions (the policy then refuses every write)". The human chose this minimal version on 2026-11-03.

## Decision

1. **A static risk table** maps each tool name to `READ` or `WRITE`. Every current tool is `READ`. A tool not in the table is `WRITE`, so a new tool is refused until someone classifies it.
2. **A deterministic `Policy`** is consulted for every tool call the loop is about to make, after the phase check and before the call: `READ` is authorized; `WRITE` is refused. A refusal is recorded as a step with the new outcome `REFUSED_POLICY`, the call is never made, and the loop goes on as it does after any refusal.
3. **The incident records what the policy did:** a count of risky calls executed, which the gate makes 0 by construction, and a count of `REFUSED_POLICY` steps. The replay summary writes both, and the scorer reports their totals per config; `benchmark.md` asserts the executed count is 0 on every holdout run.
4. **Nothing else changes:** no write tool, no approval channel, no report field, no prompt text. Decision mode makes no tool call, so its runs pass the gate trivially; explorer and dossier modes exercise it.

## Alternatives considered

- **The full approval flow.** Rejected for now (Context). It can follow M4 as its own feature, behind this gate.
- **No gate, the structural zero stated as such.** Rejected: the project's claim is that code authorizes, and a claim with no code behind it is a promise.
- **Classifying by tool name patterns.** Rejected: an explicit table, with unknown meaning write, fails closed.

## Consequences

### Positive

- "Zero risky actions" becomes a property of a tested gate, with refusals on record.
- A future write tool is refused until classified, so adding one cannot slip past the policy.

### Negative

- The demo shows no human approval; `docs/benchmark-limits.md` says so.
- A Flyway migration widens the `tool_outcome` check, and the replay summary gains columns.
