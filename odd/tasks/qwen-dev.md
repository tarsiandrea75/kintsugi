# qwen-dev

## Objective

Measure on dev whether a second local model, `qwen3.5:9b`, with and without thinking, does better than Config A (`granite4.1:8b`) and Config 0 (rules) in decision mode. One cheap experiment under a plan committed before the run, agreed with the human on 2026-10-30.

## Problem

On the lab holdout-1 the local model scored 2/15 against the rules' 10/15 (`docs/benchmark.md`). Dev showed the failure is reasoning, not presentation: the rules and the cloud solve the same dossiers. A larger or thinking local model is the cheapest test of that reading. Today the local route cannot run it: the model, thinking, the report's answer cap and its deadline are fixed in `application.yaml`, and the 600 s report deadline already cut 4 of 15 holdout samples on granite.

## Why

If thinking lifts a 9B model toward the rules, the local route has a future without the hybrid; if not, the hybrid (M3) is the only local path, and that is worth knowing before building it.

## Scope

- In: an ADR for the model; replay options for a measurement (local model name, thinking on or off, the report's answer cap and deadline); the scorer counting a timed-out sample as not covered; a timing calibration on the `stress` split; the dev plan; the runs; the scored result.
- Out: the holdout (not read again); the hybrid (M3); any change to the prompt, the dossier or the rules.

## Constraints

- Defaults stay as they are: granite, no thinking option sent, 512 tokens, 600 s. Overrides apply only to the run that sets them.
- Calibration uses `stress` recordings, never dev or holdout, so the deadline and cap are fixed before any dev verdict is seen.
- Each local run takes hours on this CPU; runs never share the CPU.

## Design

- Overrides through environment variables the agent already reads by Spring relaxed binding where possible, passed by `replay-agent.sh` options `--model`, `--think on|off`, `--report-cap N`, `--report-timeout S`, recorded in `summary.tsv` like `view` and `report`.
- Thinking is Ollama's `think` request field. Thinking tokens count against `num_predict`, so the cap and deadline for a thinking run come from the calibration.

## Tasks

- [x] T1 ADR-017: `qwen3.5:9b` as the experiment's second local model (version, quantization, licence, why this one). Route: inline (one doc).
- [x] T2 Scorer: a `TIMED_OUT` sample is a miss and not covered; tests first; evals spec. Route: delegated writer.
- [ ] T3 Replay overrides (`--model`, `--think`, `--report-cap`, `--report-timeout`), recorded per sample; tests first; agent spec "Routes"/"Answer caps". Route: delegated writer.
- [ ] T4 Calibration on `stress`: one decision run per thinking setting; the report's time and answer length set the cap and deadline. Route: inline commands.
- [ ] T5 The dev plan, committed before the dev runs. Route: inline.
- [ ] T6 The dev runs and the scored result; reading; STATE. Route: inline commands.

## Acceptance criteria

- AC1: with no override, a replay sends the same request as today (test).
- AC2: each override reaches the Ollama request and is recorded in `summary.tsv` (tests).
- AC3: a `TIMED_OUT` sample lowers coverage (test).
- AC4: the plan is committed before the first dev run and names cap and deadline from the calibration.
- AC5: the result has Wilson CIs and paired differences against A and 0 on the same 15 dev cases.

## Checks

- `cd evals && .venv/bin/python -m pytest -q`
- The agent's Gradle tests for the route and the replay.

## Delivery

Forecast about 450 authored lines (ADR 60, scorer 60, overrides with tests and spec 250, plan 80): over the 400-line budget, so `feature-branch-chain` (standing preference). Branch `feat/qwen-dev` from `feat/benchmark-v0` (`1f09df9`).

## Progress

- 2026-10-30: feature document created; branch `feat/qwen-dev` at `1f09df9`. Holdout timing: about 540 s per granite sample against about 405 s on dev, with smaller prompts, so the machine was slower that day; the deadline needs a margin either way.

- 2026-10-30: T1 done, `docs/adr/ADR-017-qwen-dev-experiment.md`. Checked: Ollama 0.31.1, digest `56671c2ab938`, Apache 2.0 (`ollama show --license`); Qwen 3.5 small series released 2026-03-01 and listed on Ollama with thinking (web search). Passive doc, structural readback only.

- 2026-10-30: T2 done. Route: inline, against the declared delegated route: three small, understood edits (about 35 lines), cheaper than a writer's handoff. ADR-015 defines coverage as the share of fault cases with an answer; the spec and `scoring.py` counted only abstentions, so a `TIMED_OUT`, `FAILED` or `INVALID_REPORT` row counted as covered. `FaultScore.unanswered` now counts them; AC@1 and the paired rows are unchanged. RED: the new test failed; GREEN: 66 passed. The lab holdout-1 was rescored from the same run directories (same sha256) and `docs/benchmark.md` regenerated: only A changes, coverage 100% → 73%, selective 13% → 18% (F2: 2 of 2 answered right). Earlier committed results keep the old coverage; they have no timeouts except V2 cloud's 4 `INVALID_REPORT` (coverage 87% there would read 60%).

## Next step

T3.
