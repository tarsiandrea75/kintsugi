# Investigation: dossier mode on the local route

## Objective

Find out why dossier runs on the local route (`granite4.1:8b`, CPU) time out, before choosing a fix; the M2 check ("F1–F3 correctly diagnosed with a local model") depends on it.

## Findings (2026-10-22, no cloud cost)

- **Replay, local route, F1 dossier, 1 sample** (`infra/scripts/out/replay-20261022T181853Z-local`, on `9131c47`): `TIMED_OUT`, "model call ACT in INVESTIGATE took longer than 420 s". The very first model call after the dossier never answered, so no token counts were recorded.
- **Ollama probe on the rendered dossier alone** (6,269 characters, `raw`, `num_ctx` 8192, `num_predict` 1): 2,148 prompt tokens read in 189.9 s, **11.3 tokens/s**, the same reading speed as the 2026-10-16 host probe (10.6-11.1).
- **Whole prompts are three times the dossier.** In the cloud recording of the same round (`agent/src/test/resources/replay/F1/verify-agent-20261019T214629Z-F1.json`), every dossier-mode call holds 6,254-7,764 prompt tokens (Anthropic's tokenizer; the system message, tool definitions, dossier and task), and the first `ACT` answer 384 completion tokens.
- **Estimate for the first call on this CPU:** about 6,500 tokens at 11.3 tokens/s is about 575 s to read, plus 384 tokens at about 4 tokens/s (2026-10-16 probe) is about 95 s to write: **about 670 s against the 420 s `ACT` deadline.** No prompt wording can fix this; the call is too large for the deadline on this host.
- Later calls share the prompt's start (system message, then the dossier, built to change last), so Ollama's prompt cache could make them much cheaper; not yet measured.

- **Replay with longer deadlines** (measurement only: `KINTSUGI_AGENT_MODEL_CALL_TIMEOUT_ACT=900s`, `..._REPORT=900s`, `KINTSUGI_AGENT_LOOP_INCIDENT_TIMEOUT=90m`; `infra/scripts/out/replay-20261022T183259Z-local`): **F1 MATCH, COMPLETED in 1,672 s (28 minutes)**, inside the 45-minute budget. granite's prompts are about 3,800-4,600 tokens (its tokenizer, not Anthropic's).
  - Calls that only add to the end of the prompt took **26-118 s**: the prompt cache works.
  - The calls that pay: the first INVESTIGATE answer 325 s (384 tokens, cut), the first KNOWLEDGE answer **406 s** (357 tokens), the REPORT **447 s** (468 tokens). Each starts a phase, so its prompt changes early and is read again, and it writes a long answer at about 3 tokens/s. The REPORT would have timed out at today's 420 s, KNOWLEDGE was 14 s short of it; the first sample's first call ran past 420 s.
  - So the estimate above (670 s) was too pessimistic: granite's token counts are about a third smaller than Anthropic's. The deadline, not the incident budget, is what stops local dossier runs.

## Options (for the human)

1. Longer `ACT` and `REPORT` deadlines on the local route (600 s, against the 447 s measured), keeping the 45-minute incident budget: measured above for F1; F2 and F3 to measure.
2. A shorter dossier for the local route (fewer sections or rows), measured on the replay for accuracy.
3. A smaller or faster local model.
4. Revisit the M2 criterion (a decision on `docs/vision.md`).

## Progress

- 2026-10-22: the human chose option 1. The local `ACT` and `REPORT` deadlines are 600 s (`application.yaml`), the incident budget stays 45 minutes (route: inline; config, `DefaultBudgetsTest`, spec "Budgets" and its scenarios, the `lib.sh` comment). RED: `anActCallAndTheReportMayTake600Seconds` failed on 420 s; GREEN: full suite 421/0/0 (the loop's HTTP read timeout follows to 600 s), `test-lib.sh` PASS.

- 2026-10-22: local replay of F1-F3 with the 600 s deadlines (`infra/scripts/out/replay-20261022T193305Z-local`, on `944d590`, one sample each, no cloud cost). **No timeout: every run COMPLETED inside the 45-minute budget** (F1 1,791 s, F2 2,114 s, F3 1,720 s).
  - Slowest model calls per run: F1 463 s and 472 s (ACT), 441 s (REPORT); F2 510 s (ACT), 473 s (REPORT); F3 412 s (ACT), 417 s (REPORT). The slowest, 510 s, leaves 90 s under the deadline.
  - Verdicts: **F2 MATCH; F1 MISS** (payment-service instead of gateway-mock, the type right; the long-deadline run before was a MATCH); **F3 MISS** (payment-service, DEPENDENCY_REJECTING instead of order-service, CONFIGURATION). Coverage 0.85-0.92: granite asks for calls outside the dossier (repeated `recent_events`, `search_runbooks`), which the replay does not hold and the loop refuses as duplicates.
  - So the timing problem is solved; what remains is accuracy, which one sample per fault cannot measure.

- 2026-10-23: local replay, three samples per fault (`infra/scripts/out/replay-20261022T220256Z-local`, on `91e3f0d`, no cloud cost). **No timeout** (1,535-1,961 s per run). Matches: **F1 2/3, F2 1/3, F3 0/3**.
  - **5 of 9 runs ended `INVALID_REPORT`** ("report invalid twice"). The cause is the quotes: in almost every report, of every run, each `evidence[].quote` fails the check that it is a verbatim substring of the cited output. granite rebuilds a dossier row in its own format instead of copying it (for example `gateway-mock | client | POST /authorize | status: 429 | exception: TooManyRequests`) or shortens a log line. A run survives only when one item happens to be exact.
  - 4 report attempts were cut at the 512-token cap.
  - Every valid F1 and F2 report named the right cause; the misses there are invalid reports, not wrong diagnoses. F3 is a wrong diagnosis as well: its one valid report and the invalid ones name gateway-mock, DEPENDENCY_REJECTING, instead of order-service, CONFIGURATION.

## Next step

The timing problem is solved. The human chooses how to make local reports valid. The recommendation: evidence cites what to quote by reference (a dossier row or a tool step and a line number), and code fills in the verbatim text, so that verification stays exact while the model no longer has to copy text. The alternative, looser quote matching, would let through evidence the outputs do not hold. F3 on the local route then remains a separate reasoning problem.
