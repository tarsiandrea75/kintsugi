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

- [x] T1 The plan. Route: inline (docs).
- [x] T2 DP2 and DP3, with the current prompt. Route: inline commands.
- [x] T3 The sentence, tests first; the living spec. Route: delegated writer.
- [x] T4 DW's run, the rules check, the score and the result. Route: inline commands.

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
- 2026-11-03: T1 done (`9d1b21c`). T2 on `9d1b21c`: DP2 `replay-20261103T132743Z-cloud` 17/30, DP3 `replay-20261103T132932Z-cloud` 15/30, both 30 rows, `COMPLETED`, the plan's configuration, DP1's recordings, the pairs in every dossier. Against DP1: 3 won and 2 lost, 2 won and 3 lost.
- 2026-11-03: T3 done by a delegated writer (`c775fc2`). The sentence is in `SYSTEM_RULES` once, after "disk or threads.", and reaches every route and mode through `AgentLoop.Run` (`AgentLoop.java`:1019, :1035). The two whole-prompt pins changed only by the sentence. RED 1 failed; GREEN `./mvnw -q test` 698 run, 0 failures. 38 authored lines. AC1 holds.
- 2026-11-03: T4 done on `c775fc2`, with a clean tree. Results in `evals/results/2026-11-03-resource-warrant-re2ob-dev/`.
  - Pre-close check passed: rules v1 and v2 (`replay-20261103T133807Z-rules`, `-133811Z-`) equal `-114625Z-` and `-114630Z-` in every column but `seconds`. AC2 holds.
  - DW `replay-20261103T133833Z-cloud`: 30 rows, `COMPLETED`, the plan's configuration, DP1's recordings; about $0.03. The incident JSON does not store the system prompt, but the run's record shows the sentence: on all 30 cases DW's `promptTokens` is exactly 64 above DP1's, DP2's and DP3's on the same recording, so one fixed addition and no other drift (the closing review's SUGGESTION R3-prompt-presence-observable, lineage `review-966a2ef82345b230`, approved and acknowledged). AC3 and AC4 hold.

| Run | AC@1 [Wilson 95%] | Coverage | Selective |
|---|---|---|---|
| DP1–DP3 | 16, 17, 15 of 30 | 50% to 60% | 89% to 100% |
| **DW** | **25/30, 83% [66%, 93%]** | 90% | 93% |
| 0R | 20/30, 67% [49%, 81%] | 90% | 74% |

  - **The gain rule's first part is met:** against DP1, DP2 and DP3, DW wins 10 and loses 1, wins 10 and loses 2, wins 12 and loses 2, so net 9, 8 and 10, against 7 needed.
  - **Its second part is not:** DW − 0R is +17 points, 8 for DW and 3 for 0R, exact McNemar p = 0.23. So **the sentence is not kept as a gain** by the plan's rule. It is the first D run ahead of 0R.
  - On the 20 cpu, mem, disk and socket cases: DW abstains on 2 and gets 16 right; DP1–DP3 abstain on 12, 10 and 13 and get 7, 10 and 7 right.
  - **Reading the risk:** of DW's 16 right resource answers, 0R also gets 13 right and misses 3. So most of the gain is on cases the rule already solves. That fits the worry that the sentence brings the model closer to R-RES. It also fits a model that now reads facts it used to dismiss. These numbers cannot tell the two apart.
  - DW − 0: +70 points, p = 9.5e-07, not a criterion.
  - **Limits:** one run, and the sentence was written after a diagnosis of these same 30 cases, so dev cannot say how much is fitted. The holdout read in M4 can.

## Next step

The plan leaves the decision to the human: keep the sentence, revert it, or neither yet. No further change to D on these 30 cases without its own plan.
