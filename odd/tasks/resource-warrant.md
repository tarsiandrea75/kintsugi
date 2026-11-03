# resource-warrant

## Objective

Tell the model, in one sentence, what a resource rise in the dossier is evidence of, and measure whether D abstains less on RE2-OB dev.

## Problem

In 8 of the 12 resource cases D abstained on, it saw the true service's rise and dismissed it, mostly because latency was normal or nothing logged an error (`odd/tasks/d-abstention.md`). The prompt gives latency a warrant ("an answer that takes long is that component's own failure even when it ends in success and logs no error"), but gives resources none, and defines `RESOURCE_EXHAUSTION` as a component that "has run out" of a resource, which the dossier cannot show: it holds use, not limits.

## Why

If the model dismisses a fact because the prompt never says what it means, that is our prompt's gap, not the model's limit. The test says which.

## The risk, stated before the work

The sentence moves the model toward what R-RES does. If it said R-RES's thresholds, D − 0R would measure our rule read aloud by a model. So the sentence gives no number and no threshold, keeps "Prefer UNKNOWN to a guess" as it is, and mirrors the warrant latency already has. Whether that is still the model's judgement is a reading the human makes with the result; the feature document records it either way.

## Scope

- One sentence in `Prompts.java`, after the `RESOURCE_EXHAUSTION` definition; the tests that pin the prompt; the agent's living spec.
- Two more D runs with the current prompt (DP2, DP3), then one with the new sentence (DW). About $0.10 in all.

Out of scope: the resource table's layout, the "Prefer UNKNOWN" line, A runs, any holdout.

## Design

The sentence, after `- RESOURCE_EXHAUSTION: …`:

> The dossier shows how much of a resource a component uses, not its limit: use that rises sharply from the previous window to the incident window is evidence that the component is running out, even when it logs no error and answers quickly.

## Tasks

- [ ] T1 The plan. Route: inline (docs).
- [ ] T2 DP2 and DP3, with the current prompt. Route: inline commands.
- [ ] T3 The sentence, tests first; the living spec. Route: delegated writer.
- [ ] T4 DW's run, the rules check, the score and the result. Route: inline commands.

## Acceptance criteria

- AC1: the rendered system prompt holds the sentence once, after the `RESOURCE_EXHAUSTION` line, and nothing else in the prompt changes.
- AC2: Config 0's and 0R's verdicts are unchanged.
- AC3: DW is scored against DP1–DP3 and 0R under the gain rule, with Wilson CIs.
- AC4: no model reads a holdout.

## Checks

- T3: `cd agent && ./mvnw -q test`, red first.
- T2, T4: the plan's mechanics checks.

## Delivery

Forecast: about 60 authored lines for T3, about 150 for the plan and results. Branch `feat/resource-warrant`, from `feat/latency-pairs`. Nothing is pushed.

## Progress

- 2026-11-03: the human approved the change.

## Next step

T1.
