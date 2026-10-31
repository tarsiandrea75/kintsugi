# ADR-018: qwen3.5:9b with thinking (Qon) as the M3 hybrid's local model

## Status

Accepted. Scope: the local model of the M3 hybrid (rules propose, the LLM picks and explains). The local route's default model stays `granite4.1:8b` (ADR-003, ADR-012); changing the default is a separate decision.

## Date

2026-10-31

## Context

ADR-017 ran `qwen3.5:9b` (digest `56671c2ab938`, Q4_K_M, Apache 2.0, Ollama 0.31.1) as a dev experiment. With thinking on, report cap 4096 and report deadline 1200 s (Qon), it passed ADR-015's dev keep rule against granite (8/15 against 5/15) and then read the lab holdout-1 once (`evals/plans/2026-10-31-qwen-holdout1.md`):

- Qon 12/15, AC@1 80% [55%, 93%]; granite 2/15; rules 10/15; cloud 15/15 (`docs/benchmark.md`).
- Qon − granite +67 points, Holm p = 0.032; Qon − rules +13, p = 0.62; Qon − cloud −20, p = 0.5.

The hybrid needs a local model that can choose among the rules' candidates and explain the choice. Granite fails at that reasoning on both dev and holdout-1; Qon is the only local model measured that does not.

The human chose Qon for the hybrid on 2026-10-31.

## Decision

The M3 hybrid's local config uses `qwen3.5:9b`, thinking on, with Qon's settings as the starting point: report cap 4096, report deadline 1200 s. The hybrid's own plan may recalibrate the cap and deadline on `stress` recordings before its first dev run, as `qwen-dev` T4 did, because its prompt differs.

## Alternatives considered

- **Granite (`granite4.1:8b`)**: the current default; 2/15 on holdout-1. A hybrid on it would mostly measure granite's weakness.
- **Qwen with thinking off (Qoff)**: same dev score (8/15), about a third of the time, but it failed the keep rule (F2 −2 net) and was not read on a holdout.
- **A 14B model**: vision §6 keeps it for overnight runs; unmeasured here, and slower still.

## Consequences

### Positive

- The hybrid starts from the only local model with a measured lift over granite.
- The replay overrides of `qwen-dev` T3 run it with no agent change.

### Negative

- About 13 to 20 minutes per sample on this CPU, against about 7 to 9 for granite. RCAEval dev runs for the hybrid take many hours and compete for the nights in vision §7's budget.
- The evidence is 15 holdout cases on the lab; the per-fault numbers moved a lot between dev and holdout (F1 1/5 against 4/5). The hybrid's own measurement, not this ADR, says whether it works.
- Config A in the benchmark stays granite, so the hybrid's local row and Config A differ in model as well as method; the hybrid's plan must name that.
