# Plan: hybrid-today-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Configs: ADR-019 (the hybrid), ADR-021 (Config 0R, HR). Model: ADR-018. Deadline: ADR-023.

## Status

Exploratory: the first hybrid run with today's agent: the latency pairs, the resource sentence and verification levels. The hybrid report is not ranked, so the ranked report does not apply to it. Committed before the run: this plan's commit time precedes the start time in the HT run directory's name.

What is already known: HR, the hybrid with the old agent, 23/30 against 0R's 20/30, p = 0.25 (`evals/results/2026-11-01-hybrid-rules-v2-re2ob-dev/`). 0R's rules are unchanged since, so HT reads the same candidate lists. Under 0R, the first candidate equals 0R's verdict in every non-empty list.

| 0R's candidate list (`candidates` column, `evals/results/2026-11-03-a-settled-re2ob-dev/inputs/0R-summary.tsv`) | Cases | What HT can do |
|---|---|---|
| empty | 3 | `UNKNOWN|UNKNOWN`, no model call (ADR-019, "No fallback") |
| truth absent | 3 | nothing right |
| truth first | 20 | the 20 cases 0R has right |
| truth second | 2 | the only cases where the model can beat the rules |
| truth third | 2 | the same |

- **Ceiling:** HT can reach at most 24/30 against 0R's 20/30. HT − 0R has at most 4 discordant cases in HT's favour, and then the exact McNemar test gives at best p = 0.125. HT cannot win a significant paired comparison against 0R on RE2-OB dev, whatever the model does.
- HR's 23 right answers all lie within those 24 cases, so HT − HR has at most 1 discordant case in HT's favour, and at best p = 1.0.

## Date and commit

- Written: 2026-11-05
- Run on: the commit that adds this plan on `feat/hybrid-today`. Nothing under `agent/` changes during the run, and no other CPU-heavy job runs beside it.
- Recordings: `evals/out/rcaeval/re2-ob-rep1-resources/`; its `recordings.tsv` must equal `evals/results/2026-11-02-a-deadline-re2ob-dev/inputs/recordings.tsv`, the most recent committed copy (the 2026-11-03 results do not commit one). Checked equal before this plan was committed.

## Hypothesis

Descriptive, three questions:

1. What does HT score against 0R and against HR, paired, with the exact McNemar test?
2. How many of HT's answers are Verified-correct, and what is its L1 rate (the `level` column)?
3. How often does HT keep 0R's first candidate, on the 27 non-empty lists and on the 6 with more than one entry?

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one sample each. No holdout.
- Rerun rule: there is no resume. A run that stops before 30 samples, or fails before writing them, is discarded and rerun whole on the same commit; the discarded directory is named in the notes. A complete run is never rerun, whatever its timeouts.

## Configs

Decision mode, `tables` view.

| Label | Config | How | Source |
|---|---|---|---|
| 0 | rules v1 (ADR-016) | `replay-agent.sh`, no model | a fresh run beside HT; it must equal `evals/results/2026-11-03-a-settled-re2ob-dev/inputs/0-summary.tsv` (sha1 `1abfe9da…`) |
| 0R | rules v2 (ADR-021) | `replay-agent.sh`, no model | a fresh run beside HT; it must equal `evals/results/2026-11-03-a-settled-re2ob-dev/inputs/0R-summary.tsv` (sha1 `cf1a4414…`) |
| HR | hybrid, the old agent | - | `evals/results/2026-11-01-hybrid-rules-v2-re2ob-dev/inputs/HR-summary.tsv` |
| HT | hybrid with today's agent: route `local`, `qwen3.5:9b`, think `on`, report `hybrid`, rules `v2`, cap 4096, report deadline 2100 s | `replay-agent.sh` | a fresh run |

The fresh 0 and 0R equal the committed summaries when every column matches except `seconds`, `ranked` and `level`, which the committed files predate or which vary by run.

Cost: 27 model calls, about 8 h of CPU (HR's median report took 958 s); no API calls.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with Wilson 95% CIs; coverage and selective accuracy.
- Paired HT − 0R and HT − HR: discordant cases each way, exact McNemar (as the scorer reports them).
- `level`: how many of HT's answers are Verified-correct, and the L1 rate.
- How often HT keeps 0R's first candidate, on the 27 non-empty lists and on the 6 with more than one entry.
- `TIMED_OUT`, `INVALID_REPORT`, reports cut at 4096 tokens.
- Report time per sample: median and maximum.

## Decision rule

- None on the result: it is recorded whatever it shows, and no setting changes on it.
- Mechanics, as in HR's and AS's plans:
  - a run that is not 30 rows with the configuration above and the recordings above is not scored, and the human decides;
  - every hybrid incident's `rules` field is `v2` (the `model` column cannot tell HT from H);
  - the 3 empty lists end `UNKNOWN|UNKNOWN` with no model call, and no call is unrecorded;
  - **the deadline is revisited by the human** (ADR-023) if any sample ends `TIMED_OUT`, or the maximum report time passes 1,800 s;
  - **the cap is revisited by the human** if more than 3 reports are cut at 4096 tokens.

## Command

From the repository root, then from `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v1
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route rules --mode decision --rules v2

REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision \
  --view tables --report hybrid --rules v2 --model qwen3.5:9b --think on --report-cap 4096 --report-timeout 2100

cd evals
python3 -m kintsugi_evals score --service-only \
  --topology ../agent/src/main/resources/topology/re2-ob.json \
  --label 0 ../infra/scripts/out/<0 run> --label 0R ../infra/scripts/out/<0R run> \
  --label HR results/2026-11-01-hybrid-rules-v2-re2ob-dev/inputs/HR-summary.tsv \
  --label HT ../infra/scripts/out/<HT run> \
  --out results/2026-11-05-hybrid-today-re2ob-dev
```

Check the fresh HT `summary.tsv` first: 30 samples, split `dev`, view `tables`, report `hybrid`, model `qwen3.5:9b`, think `on`, cap 4096, deadline 2100 s, and every hybrid incident's `rules` field is `v2`.
