# Plan: hybrid-re2ob-dev

Copied from `evals/plans/TEMPLATE.md` (ADR-015). Feature: `odd/tasks/hybrid.md`, T9–T10. Config: ADR-019. Model: ADR-018. Data: ADR-020.

## Status

Pre-registered: committed before any H run on RCAEval. The stop-rule outcome is already fixed by the rules' candidates, and this plan says so before the run.

What is already known (`evals/results/2026-11-01-config0-re2ob-dev/`, Config 0 run `replay-20261101T025036Z-rules`, whose `candidates` column the human and the agent have read):

| Rules' candidate list | Cases | What H can do |
|---|---|---|
| empty | 19 | `UNKNOWN|UNKNOWN`, no model call (ADR-019, "No fallback") |
| truth absent | 6 | nothing right |
| truth first | 4 | the 4 cases Config 0 has right |
| truth second | 1 | the only case where the model can beat the rules |

- **Ceiling:** H can reach at most 5/30 against Config 0's 4/30. H − 0 is at most +1 case (+3.3 points). With at most one discordant case in H's favour, the exact McNemar test cannot reach significance.
- **Stop rule (vision §7):** H does not win a paired comparison on RE2-OB dev, whatever the model does. This is recorded as the stop-rule outcome for H on RE2-OB dev, before the run. The cause is the rules' recall (17%), not the model: 19 of 30 lists are empty because the dossier has no resource facts (vision M3).
- **Why run anyway** (the human's decision, 2026-11-01): to observe how Qon chooses when the list has more than one entry. RE2-OB dev has 5 such lists, against none with a real choice on lab dev but F1's.

## Date and commit

- Written: 2026-11-01
- Run on commit: the commit that adds this plan on `feat/hybrid-rcaeval`, or a later one that changes nothing under `agent/src/main/`, `agent/src/test/java/dev/kintsugi/agent/loop/Replay*.java`, `infra/scripts/replay-agent.sh`, `infra/scripts/lib.sh` or `evals/kintsugi_evals/`.
- Recordings: `evals/out/rcaeval/re2-ob-rep1/` as converted for T6 of `odd/tasks/rcaeval-converter.md`; its `recordings.tsv` must equal `evals/results/2026-11-01-config0-re2ob-dev/inputs/recordings.tsv`.

## Settings

H's lab settings (`evals/plans/2026-10-31-hybrid-dev.md`): cap 4096, report deadline 2100 s. No RCAEval calibration was run: the RCAEval dossier is larger (11 services, 58 calls), so a report may take longer than on the lab. A timeout or an invalid report is H's answer as it stands (no fallback), recorded, not rerun.

## Hypothesis

Descriptive. On the 11 cases with a non-empty list, does Qon pick from the list, and on the 5 lists with more than one entry, does it keep the rules' first candidate or choose another?

## Cases and split

- Split: `dev`, RE2-OB repetition 1, 30 cases, one sample each.
- First read of a holdout: none.
- Rerun rule: a run that stops before 30 samples, or fails before writing them, is discarded and rerun whole on the same commit; the discarded directory is named in the feature document. A complete run is never rerun, whatever its timeouts.

## Configs

Decision mode, `tables` view.

| Label | Model | Thinking | Report | Report cap | Report deadline | Source |
|---|---|---|---|---|---|---|
| 0 | rules (ADR-016) | - | plain | - | - | `evals/results/2026-11-01-config0-re2ob-dev/inputs/0-summary.tsv` |
| H | `qwen3.5:9b` | on | hybrid | 4096 | 2100 s | a fresh run |

Cost: 11 model calls, about 2 to 5 h of CPU; no API calls.

## Metric

- AC@1 on the service (`score --service-only`), per fault type and pooled, with Wilson 95% CIs; coverage and selective accuracy; H's candidate recall.
- Paired H − 0: discordant cases each way, exact McNemar, cluster bootstrap (as the scorer reports them).
- Descriptive:
  - how often H answered from the list, UNKNOWN, `INVALID_REPORT` or timed out, on the 11 non-empty lists;
  - on the 5 lists with more than one entry, which rank H picked;
  - report time per sample and the share of samples with a second report attempt.

## Decision rule

- Stop rule: not met, fixed above before the run.
- Mechanics: the run passes when it writes 30 samples, the 19 empty lists end `UNKNOWN|UNKNOWN` with no model call, and no sample ends `FAILED`. Any other outcome is recorded in the feature document.

## Command

From the repository root, then from `evals/`:

```
REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1 infra/scripts/replay-agent.sh --dataset re2-ob --route local --mode decision \
  --view tables --report hybrid --model qwen3.5:9b --think on --report-cap 4096 --report-timeout 2100

python3 -m kintsugi_evals score --service-only \
  --label 0 results/2026-11-01-config0-re2ob-dev/inputs/0-summary.tsv \
  --label H ../infra/scripts/out/<H run> \
  --out results/2026-11-01-hybrid-re2ob-dev
```

Check the fresh `summary.tsv` first: 30 samples, split `dev`, view `tables`, report `hybrid`, model `qwen3.5:9b`, think `on`, cap 4096, deadline 2100 s.
