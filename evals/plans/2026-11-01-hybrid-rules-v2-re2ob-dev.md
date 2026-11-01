# Plan: hybrid-rules-v2-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/resource-facts.md`, T7 (AC6). Configs: ADR-019 (the hybrid), ADR-021 (Config 0R, HR). Model: ADR-018. Data: ADR-020.

## Status

Pre-registered: committed before any HR run. The stop-rule outcome is already fixed by 0R's candidates, and this plan says so before the run.

What is already known (`evals/results/2026-11-01-config0r-re2ob-dev/`, 0R run `replay-20261101T155259Z-rules`, whose `candidates` column the agent has read; under 0R the first candidate equals 0R's verdict in every non-empty list there):

| 0R's candidate list | Cases | What HR can do |
|---|---|---|
| empty | 3 | `UNKNOWN|UNKNOWN`, no model call (ADR-019, "No fallback") |
| truth absent | 3 | nothing right |
| truth first | 20 | the 20 cases 0R has right |
| truth second or third | 4 | the only cases where the model can beat the rules |

- **Ceiling:** HR can reach at most 24/30 against 0R's 20/30. HR − 0R has at most 4 discordant cases in HR's favour, and then the exact McNemar test gives at best p = 0.125.
- **Stop rule (vision §7):** HR does not win a paired comparison against 0R on RE2-OB dev, whatever the model does. This is recorded as the stop-rule outcome for HR before the run. The resource facts and R-RES are the change that won a paired comparison on RE2-OB dev (0R − 0, p = 3.1e-05); whether that meets the stop rule is the human's reading, recorded in the feature document, not in this plan.
- **Why run anyway** (the human's decision, 2026-11-01): to observe how Qon chooses on the 6 lists with more than one entry, and how many of 0R's 20 right answers it keeps.

## Date and commit

- Written: 2026-11-01
- Run on commit: the commit that adds this plan on `feat/resource-facts-03-rules`, or a later one that changes nothing under `agent/src/main/`, `agent/src/test/java/dev/kintsugi/agent/loop/Replay*.java`, `infra/scripts/replay-agent.sh`, `infra/scripts/lib.sh` or `evals/kintsugi_evals/`.
- Recordings: `evals/out/rcaeval/re2-ob-rep1-resources/` as converted for T6; its `recordings.tsv` must equal `evals/results/2026-11-01-config0r-re2ob-dev/inputs/recordings.tsv`.

## Settings

H's settings on RE2-OB dev (`evals/plans/2026-11-01-hybrid-re2ob-dev.md`): cap 4096, report deadline 2100 s. The dossier is larger than for H (the resources section), so a report may take longer. A timeout or an invalid report is HR's answer as it stands (no fallback), recorded, not rerun.

## Hypothesis

Descriptive. On the 27 non-empty lists, does Qon pick from the list, and on the 6 lists with more than one entry, does it keep the rules' first candidate or choose another?

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one sample each.
- First read of a holdout: none.
- Rerun rule: a run that stops before 30 samples, or fails before writing them, is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun, whatever its timeouts.

## Configs

Decision mode, `tables` view.

| Label | Model | Thinking | Report | Rules | Report cap | Report deadline | Source |
|---|---|---|---|---|---|---|---|
| 0R | rules (ADR-021) | - | plain | v2 | - | - | `evals/results/2026-11-01-config0r-re2ob-dev/inputs/0R-summary.tsv` |
| HR | `qwen3.5:9b` | on | hybrid | v2 | 4096 | 2100 s | a fresh run |

Config 0 (`evals/results/2026-11-01-config0r-re2ob-dev/inputs/0-summary.tsv`) is scored alongside (CLAUDE.md: Config 0 runs alongside every measurement).

Cost: 27 model calls, about 2 to 5 h of CPU; no API calls.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with Wilson 95% CIs; coverage and selective accuracy; HR's candidate recall.
- Paired HR − 0R (and HR − 0, reported): discordant cases each way, exact McNemar, cluster bootstrap (as the scorer reports them).
- Descriptive:
  - how often HR answered from the list, UNKNOWN, `INVALID_REPORT` or timed out, on the 27 non-empty lists;
  - on the 6 lists with more than one entry, which rank HR picked;
  - how many of 0R's 20 right answers HR kept;
  - report time per sample and the share of samples with a second report attempt.

## Decision rule

- Stop rule: not met by HR, fixed above before the run.
- Mechanics: the run passes when it writes 30 samples, the 3 empty lists end `UNKNOWN|UNKNOWN` with no model call, no sample ends `FAILED`, and no call is unrecorded. Any other outcome is recorded in the feature document.

## Command

From the repository root, then from `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1-resources infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision \
  --view tables --report hybrid --rules v2 --model qwen3.5:9b --think on --report-cap 4096 --report-timeout 2100

python3 -m kintsugi_evals score --service-only \
  --label 0 results/2026-11-01-config0r-re2ob-dev/inputs/0-summary.tsv \
  --label 0R results/2026-11-01-config0r-re2ob-dev/inputs/0R-summary.tsv \
  --label HR ../infra/scripts/out/<HR run> \
  --out results/2026-11-01-hybrid-rules-v2-re2ob-dev
```

Check the fresh `summary.tsv` first: 30 samples, split `dev`, view `tables`, report `hybrid`, model `qwen3.5:9b`, think `on`, cap 4096, deadline 2100 s, and every hybrid incident's `rules` field is `v2` (the `model` column cannot tell HR from H).
