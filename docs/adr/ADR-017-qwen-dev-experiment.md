# ADR-017: qwen3.5:9b as a second local model, for one dev experiment

## Status

Accepted for the `qwen-dev` experiment only (`odd/tasks/qwen-dev.md`). The local route's default model stays `granite4.1:8b` (ADR-003, ADR-012); a change of default needs its own ADR and a measurement that supports it.

## Date

2026-10-30

## Context

On the lab holdout-1, Config A (`granite4.1:8b`, decision mode) scored 2/15 against Config 0's 10/15 and Config D's 15/15 (`docs/benchmark.md`). Dev showed the same pattern, and the rules and the cloud solve the same dossiers, so the failure is the local model's reasoning, not what it is shown. Two cheap questions follow before the hybrid of M3 is built: does a somewhat larger local model do better, and does letting it think before answering do better?

The laptop has no GPU and WSL sees 23 GB of RAM (vision §6), so the candidate is a model of about 8 to 9B at Q4.

## Decision

Run `qwen3.5:9b` from Ollama (digest `56671c2ab938`, 9.0B parameters, Q4_K_M, 6.6 GB, Apache 2.0, context 262,144 tokens; Ollama 0.31.1) on dev in decision mode, twice: with thinking off and with thinking on. It is the newest small Qwen generation (released 2026-03-01; Ollama lists the 9B with tools and thinking), it is already installed, and it is the largest of the generation that fits this CPU.

Thinking is Ollama's `think` request field. Thinking tokens count against `num_predict` and take CPU time, so the report's answer cap and deadline for each setting are set by a calibration on `stress` recordings before the dev plan is written.

## Alternatives considered

- **`qwen3:8b`** (installed): the previous generation at the same size; a result on it would be stale before it was reported.
- **A 14B model**: vision §6 keeps about 14B for overnight benchmarks; 15 dev samples with thinking would take most of a day, and the cheap question comes first.
- **Prompt changes for granite**: dev already showed the dossier is enough for the rules and the cloud; presentation variants V1 and V2 gave the local route nothing (`odd/tasks/service-assessment.md`).
- **Skip to the hybrid**: possible, but the experiment costs a few hours of CPU and tells whether the hybrid is the only local path.

## Consequences

### Positive

- The local route learns whether size or thinking is the lever, under a committed plan.
- The replay gains per-run overrides for model, thinking, cap and deadline, reusable for any later local model.

### Negative

- Hours of CPU per run; thinking runs are slower still.
- Dev has been read many times, so the result is a dev result: a reason to try qwen on a holdout later, not a claim.
