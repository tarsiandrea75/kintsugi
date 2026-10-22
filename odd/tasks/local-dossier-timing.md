# Investigation: dossier mode on the local route

## Objective

Find out why dossier runs on the local route (`granite4.1:8b`, CPU) time out, before choosing a fix; the M2 check ("F1–F3 correctly diagnosed with a local model") depends on it.

## Findings (2026-10-22, no cloud cost)

- **Replay, local route, F1 dossier, 1 sample** (`infra/scripts/out/replay-20261022T181853Z-local`, on `9131c47`): `TIMED_OUT`, "model call ACT in INVESTIGATE took longer than 420 s". The very first model call after the dossier never answered, so no token counts were recorded.
- **Ollama probe on the rendered dossier alone** (6,269 characters, `raw`, `num_ctx` 8192, `num_predict` 1): 2,148 prompt tokens read in 189.9 s, **11.3 tokens/s**, the same reading speed as the 2026-10-16 host probe (10.6-11.1).
- **Whole prompts are three times the dossier.** In the cloud recording of the same round (`agent/src/test/resources/replay/F1/verify-agent-20261019T214629Z-F1.json`), every dossier-mode call holds 6,254-7,764 prompt tokens (Anthropic's tokenizer; the system message, tool definitions, dossier and task), and the first `ACT` answer 384 completion tokens.
- **Estimate for the first call on this CPU:** about 6,500 tokens at 11.3 tokens/s is about 575 s to read, plus 384 tokens at about 4 tokens/s (2026-10-16 probe) is about 95 s to write: **about 670 s against the 420 s `ACT` deadline.** No prompt wording can fix this; the call is too large for the deadline on this host.
- Later calls share the prompt's start (system message, then the dossier, built to change last), so Ollama's prompt cache could make them much cheaper; not yet measured.

## Options (for the human)

1. A longer deadline for the run's first call (about 12 minutes), relying on the prompt cache for the later ones; needs a measurement of the cached calls and of the whole run against the 45-minute incident budget.
2. A shorter dossier for the local route (fewer sections or rows), measured on the replay for accuracy.
3. A smaller or faster local model.
4. Revisit the M2 criterion (a decision on `docs/vision.md`).

## Next step

The human chooses an option.
