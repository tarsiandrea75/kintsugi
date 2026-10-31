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
- [x] T3 Replay overrides (`--model`, `--think`, `--report-cap`, `--report-timeout`), recorded per sample; tests first; agent spec "Routes"/"Answer caps". Route: delegated writer.
- [x] T4 Calibration on `stress`: one decision run per thinking setting; the report's time and answer length set the cap and deadline. Route: inline commands.
- [x] T5 The dev plan, committed before the dev runs. Route: inline.
- [x] T6 The dev runs and the scored result; reading; STATE. Route: inline commands.

## Acceptance criteria

- AC1: with no override, a replay sends the same request as today (test).
- AC2: each override reaches the Ollama request and is recorded in `summary.tsv` (tests).
- AC3: a `TIMED_OUT` sample lowers coverage (test).
- AC4: the plan is committed before the first dev run and names cap and deadline from the calibration.
- AC5: the result has Wilson CIs and paired differences against A and 0 on the same 15 dev cases.

## Checks

- `cd evals && .venv/bin/python -m pytest -q`
- `cd agent && ./mvnw -q test` (excludes the `live` and `replay` tags).

## Delivery

Forecast about 450 authored lines (ADR 60, scorer 60, overrides with tests and spec 250, plan 80): over the 400-line budget, so `feature-branch-chain` (standing preference). Branch `feat/qwen-dev` from `feat/benchmark-v0` (`1f09df9`).

## Progress

- 2026-10-30: feature document created; branch `feat/qwen-dev` at `1f09df9`. Holdout timing: about 540 s per granite sample against about 405 s on dev, with smaller prompts, so the machine was slower that day; the deadline needs a margin either way.

- 2026-10-30: T1 done, `docs/adr/ADR-017-qwen-dev-experiment.md`. Checked: Ollama 0.31.1, digest `56671c2ab938`, Apache 2.0 (`ollama show --license`); Qwen 3.5 small series released 2026-03-01 and listed on Ollama with thinking (web search). Passive doc, structural readback only.

- 2026-10-30: T2 done. Route: inline, against the declared delegated route: three small, understood edits (about 35 lines), cheaper than a writer's handoff. ADR-015 defines coverage as the share of fault cases with an answer; the spec and `scoring.py` counted only abstentions, so a `TIMED_OUT`, `FAILED` or `INVALID_REPORT` row counted as covered. `FaultScore.unanswered` now counts them; AC@1 and the paired rows are unchanged. RED: the new test failed; GREEN: 66 passed. The lab holdout-1 was rescored from the same run directories (same sha256) and `docs/benchmark.md` regenerated: only A changes, coverage 100% → 73%, selective 13% → 18% (F2: 2 of 2 answered right). Earlier committed results keep the old coverage; they have no timeouts except V2 cloud's 4 `INVALID_REPORT` (coverage 87% there would read 60%).

- 2026-10-30: T3 done. Route: delegated writer (agent, script, spec: 2+ non-trivial files). Spring AI 2.0.1 `OllamaChatOptions.thinkOption` carries `think`; unset sends no field (AC1). Env vars by relaxed binding: `KINTSUGI_AGENT_MODEL_NAME`, `_THINK`, `_NUM_PREDICT_REPORT`, `_CALL_TIMEOUT_REPORT`; the HTTP read timeout and the warm-up follow them. Replay options `--model`, `--think on|off`, `--report-cap`, `--report-timeout` (local only, else exit 2; capped at 2700 s, the incident budget). `summary.tsv`/`aggregate.tsv` gain last columns `model think reportCap reportTimeout`. Thinking text is not read into the report (test). RED: ModelRouteTest, LocalRouteOverridesTest and 13 ReplayDriverTest failures; GREEN: `./mvnw -q test` 591 tests, 0 failures; evals 66 passed; `test-lib.sh` 156 PASS. Parent spot check: `--route cloud --think on` exit 2; ModelRouteTest, LocalRouteSettingsTest, ReplayDriverTest green.
  - Open: the 45 min incident budget still bounds a second report attempt after a slow invalid first one; the calibration (T4) shows whether it matters. T6 labels every input, since unlabelled configs would share a name.

- 2026-10-30: native review of `1f09df9..79d5bdd` (high risk): consent granted, four lenses, approved and acknowledged (lineage `review-a98bc8bcdba329de`). Informational findings kept as follow-ups:
  - WARNING R3-think-in-act-modes / R4-think-act-caps-unbounded: `--think` is accepted in explorer and dossier modes, where the ACT caps (384, 160) would be eaten by thinking. This experiment runs decision mode only; restricting `--think` to decision mode is a follow-up.
  - WARNING R3-script-overrides-untested: the four script options have no script-level test (validated by hand).
  - WARNING R2-coverage-mixed-definitions: results committed before `d4de651` use the old coverage; this document says so in T2.
  - SUGGESTIONs: R2-aggregate-positional-key, R2-report-timeout-ms-branch, R2-task-check-names-gradle (the agent's check is `cd agent && ./mvnw -q test`, not Gradle), R2-think-vs-thinking-names, R3-completed-no-report-branch, R3-thinking-cut-path-unproved.

- 2026-10-30: T4 done. Calibration on `stress` (timing and token columns only, no verdicts read): thinking off, longest report call 527 s, 2 of 3 samples needed a second report attempt; thinking on, longest call 801 s, one call each. Numbers in the plan.
- 2026-10-30: T5 done, `evals/plans/2026-10-30-qwen-dev.md`, committed before any dev run: Qoff (cap 512, 1200 s) and Qon (cap 4096, 1200 s) against the committed 0 and A dev inputs; ADR-015 keep rule against A.

- 2026-10-31: T6 done. Both runs on `5a5f9ca`, complete, none discarded: Qoff `replay-20261030T180936Z-local` (about 1.5 h, max sample 466 s, 1 `INVALID_REPORT`), Qon `replay-20261030T194329Z-local` (about 4.5 h, max sample 1252 s, no timeout). Each `summary.tsv`: 15 samples, dev, tables, plain, model/think/cap/deadline as planned. Scored into `evals/results/2026-10-30-qwen-dev/`:
  - 0 10/15; A 5/15 [15%, 58%]; Qoff 8/15, 53% [30%, 75%], coverage 93%; Qon 8/15, 53% [30%, 75%], coverage 100%.
  - Per-fault net against A: Qoff F1 +1, F2 −2, F3 +4 (pooled +3); Qon F1 +1, F2 −1, F3 +3 (pooled +3). McNemar Holm p = 1 for every pair.
  - Keep rule (ADR-015, against A): Qoff fails (F2 −2); **Qon passes** (+3 pooled, no class below −1). By the plan, Qon is the one worth a holdout read.
  - Against 0: both −2 pooled; F1 is still the local route's wall (4 of 5 answer `payment-service|DEPENDENCY_LATENCY`, the caller of the real culprit, as granite does).
  - Complementarity with 0, descriptive: Qon right where 0 is not in 4 cases (all F2), 0 right where Qon is not in 6; an oracle of 0 and Qon reaches 14/15 (0 and Qoff 13/15; 0 and A 15/15 on dev).
  - Thinking bought no accuracy here (Qon − Qoff 0, 3 cases each way) at about 3 times the time.

## Next step

Feature closed. A holdout read of Qon needs its own plan; holdout-1 has not been read by Qon.
