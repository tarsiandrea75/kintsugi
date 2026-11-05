# Plan: hybrid-ctx12k-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Configs: ADR-019 (the hybrid) and ADR-021 (Config 0R, HR). Model: ADR-018. Deadline: ADR-023. Context window: ADR-010 ("It stays a measurable knob").

## Status

Exploratory, a measured knob. HT (`evals/results/2026-11-05-hybrid-today-re2ob-dev/`) is rerun with one setting changed: the context window, from 8192 to 12288 tokens. The default stays 8192 (ADR-010, the human's choice). Committed before the run: this plan's commit time precedes the start time in the run directory's name.

What is already known:

- **HT cut 5 report attempts in 3 incidents.** In all 5, prompt + completion = 8192 tokens, so the context window filled, not the 4096-token report cap. The 29 valid reports had a median of 318 completion tokens and a maximum of 443. The longest valid prompt was 7824 tokens.
- **Two of those incidents ended `INVALID_REPORT`:** `loss/cd8184b1` and `loss/d4000574`. `mem/77586d7c` completed on its retry.
- **HT's third `INVALID_REPORT`, `delay/880fd526`, was not cut:** both of its attempts were rejected for other reasons.
- **The ceiling is unchanged:** 0R's candidate lists set it at 24/30, and HT scored 23/30. HC − HT therefore has at most 1 discordant case in HC's favour, so the best p is 1.0. This run cannot show a significant gain. It shows whether the window removes the cuts, and at what cost in time.
- **The run gives new text for the cut error.** Since `8207f37`, a cut that fills the window names the window, not the cap.

## Date and commit

- Written: 2026-11-05.
- Run on: the commit that adds this plan on `feat/context-full`. Nothing under `agent/` changes during the run, and no other CPU-heavy job runs beside it.
- Recordings: `evals/out/rcaeval/re2-ob-rep1-resources/`, the same as HT's.
- Machine: 23 GB of RAM, about 18 GB available before the run, 14 CPUs.

## Hypothesis

Descriptive. Five questions:

1. How many report attempts are cut, and for which cause (the window or the cap)? How many end `INVALID_REPORT`?
2. HC − HT and HC − 0R: paired results, with the exact McNemar test.
3. What do HC's levels show: Verified-correct and the L1 rate?
4. What does the window cost? Report time per sample (median and maximum) against HT's 875 s and 1,553 s.
5. Does any prompt pass 8192 tokens, and how many?

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one sample each. No holdout.
- Rerun rule: there is no resume. A run that stops before 30 samples is discarded and rerun whole on the same commit, and the notes name the discarded directory. A complete run is never rerun.

## Configs

Decision mode, `tables` view.

| Label | Config | Source |
|---|---|---|
| 0 | rules v1 (ADR-016) | a fresh rules replay beside HC; it must give the same verdicts as `evals/results/2026-11-05-hybrid-today-re2ob-dev/inputs/0-summary.tsv` |
| 0R | rules v2 (ADR-021) | a fresh rules replay beside HC; it must give the same verdicts as `.../inputs/0R-summary.tsv` |
| HR | the hybrid, old agent | `evals/results/2026-11-01-hybrid-rules-v2-re2ob-dev/inputs/HR-summary.tsv` |
| HT | the hybrid, today's agent, window 8192 | `evals/results/2026-11-05-hybrid-today-re2ob-dev/inputs/HT-summary.tsv` |
| HC | HT with `--num-ctx 12288` | a fresh run |

For HC, everything else stays as HT ran it:

- route `local`, `qwen3.5:9b`, think `on`;
- report `hybrid`, rules `v2`;
- cap 4096, report deadline 2100 s.

The fresh 0 and 0R must match the committed summaries in every column that both files have, except `seconds`.

Cost: 27 model calls, about 8 h of CPU or more, because a larger window evaluates prompts more slowly. No API calls.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with Wilson 95% CIs; coverage and selective accuracy.
- Paired HC − HT and HC − 0R: the discordant cases each way and the exact McNemar test, as the scorer reports them.
- `level`: Verified-correct and the L1 rate.
- `TIMED_OUT`, `INVALID_REPORT`, and the cut report attempts by cause (window or cap), read from each incident's steps.
- Report time per sample, median and maximum. Prompt tokens per report attempt, maximum, and how many attempts pass 8192.

## Decision rule

- **None on the result.** It is recorded whatever it shows, and the default window does not change on it. Changing the default means amending ADR-010, and that decision is the human's.
- **Mechanics:**
  - The run is not scored, and the human decides, if any of these holds:
    - it does not have 30 rows;
    - it does not have `numCtx` 12288 on every row;
    - the configuration differs from the one above.
  - Every hybrid incident's `rules` field is `v2`.
  - The 3 empty lists end `UNKNOWN|UNKNOWN` with no model call, and no call is unrecorded.
  - **The human revisits the deadline** (ADR-023) if any sample ends `TIMED_OUT`, or if the maximum report time passes 1,800 s.
  - **The human revisits the window or the cap** if more than 3 report attempts are cut, for each cause separately.

## Command

From the repository root, then from `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2

REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision \
  --view tables --report hybrid --rules v2 --model qwen3.5:9b --think on --report-cap 4096 --report-timeout 2100 --num-ctx 12288

cd evals
python3 -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 ../infra/scripts/out/<0 run> --label 0R ../infra/scripts/out/<0R run> \
  --label HR results/2026-11-01-hybrid-rules-v2-re2ob-dev/inputs/HR-summary.tsv \
  --label HT results/2026-11-05-hybrid-today-re2ob-dev/inputs/HT-summary.tsv \
  --label HC ../infra/scripts/out/<HC run> \
  --out results/2026-11-05-hybrid-ctx12k-re2ob-dev
```

Before scoring, check the fresh HC `summary.tsv`:

- 30 samples, split `dev`, view `tables`, report `hybrid`;
- model `qwen3.5:9b`, think `on`, cap 4096, deadline 2100 s, `numCtx` 12288;
- every hybrid incident's `rules` field is `v2`.
