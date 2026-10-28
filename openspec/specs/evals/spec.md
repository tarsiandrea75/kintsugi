# Spec: evals (the scorer)

The scorer `kintsugi_evals` under `evals/` turns replay results into the protocol's numbers ([ADR-015](../../../docs/adr/ADR-015-evaluation-protocol.md), `docs/vision.md` §6). It is run as `python -m kintsugi_evals score` from `evals/`.

## Requirements

### REQ-1 Standard library only
The scorer MUST import only the Python standard library. pytest MUST be a dev dependency only, pinned in `evals/requirements-dev.txt`.

#### Scenario: Bare interpreter
- Given a Python 3.12 with no package installed
- When `python3 -m kintsugi_evals score <dir>` runs in `evals/`
- Then it prints the report

### REQ-2 Wilson interval
Every rate MUST carry a Wilson 95% interval, z = 1.959964, clipped to [0, 1]. An empty sample MUST be an error.

#### Scenario: The vision's quoted intervals
- When the interval of 4/5, 5/15 and 14/15 is computed
- Then it is [38%, 96%], [15%, 58%] and [70%, 99%] respectively, rounded to whole percents

### REQ-3 Exact McNemar
A paired comparison MUST use the exact two-sided McNemar test: twice the binomial tail of min(b, c) in b + c trials at 1/2, capped at 1, and 1 when there is no discordant case. It MUST be computed only when every case has one outcome per config.

#### Scenario: Nine against zero
- Given 9 cases right only under D and none right only under A
- When the paired comparison runs
- Then the p-value is 0.00390625, printed as 0.0039

#### Scenario: Repeated cases
- Given a case with more than one row under a config
- When the paired comparison runs
- Then the McNemar p-value is reported as not applicable and the cluster bootstrap still runs

### REQ-4 Cluster bootstrap
The paired difference, second minus first, MUST be the mean over cases of each case's mean accuracy, so each case weighs once. Its 95% interval MUST come from resampling whole cases with an explicit seed, recorded in the report.

#### Scenario: Deterministic
- Given the same cases, seed and number of resamples
- When the bootstrap runs twice
- Then both intervals are identical

### REQ-5 Holm correction
When a run makes more than one paired test, the report MUST also give Holm-adjusted p-values, in input order.

#### Scenario: Three tests
- When p-values 0.01, 0.04 and 0.03 are adjusted
- Then the result is 0.03, 0.06 and 0.06

### REQ-6 The dev keep rule
`dev_keep` MUST keep a variant only with a net gain of at least 3 cases over the baseline and no fault class losing more than 1 case net.

#### Scenario: A class loses two
- Given a variant that gains 5 cases on F1 and loses 2 on F2
- Then it is not kept

### REQ-7 Scoring a row
Each row MUST be rescored from `status`, `truth` and `reported`, ignoring `verdict`:
- on a fault truth, a COMPLETED run reporting the truth is correct, `UNKNOWN|UNKNOWN` is an abstention, anything else is a miss;
- on the healthy truth `NONE|NONE` (provisional until follow-up 10, lab-realism), `UNKNOWN|UNKNOWN` is correct, any cause is a false alarm, and no report is a miss.

AC@1 MUST count an abstention as not correct. Coverage MUST be the share of fault rows that are not abstentions; selective accuracy the share correct among them. Healthy rows MUST NOT enter AC@1 or the paired comparison.

#### Scenario: Abstention on a fault
- Given a COMPLETED fault row reporting `UNKNOWN|UNKNOWN`
- Then it is an abstention, AC@1 counts it as not correct, and coverage drops

#### Scenario: Healthy case answered UNKNOWN
- Given a COMPLETED row with truth `NONE|NONE` reporting `UNKNOWN|UNKNOWN`
- Then it is correct and not a false alarm

#### Scenario: Healthy case answered with a cause
- Given a COMPLETED row with truth `NONE|NONE` reporting `gateway-mock|UNKNOWN`
- Then it is a false alarm

### REQ-8 TSV input
The scorer MUST read the replay driver's `summary.tsv`, given as the file or its run directory. A case MUST be keyed by (`split`, `recording`). The config MUST be the `--label` given for that input, else `route-mode-view`. A missing `view` column MUST read as `-`. A file lacking any of `fault`, `mode`, `route`, `status`, `truth`, `reported`, `split`, `recording` MUST be an error naming the columns.

#### Scenario: An older TSV without view
- Given a `summary.tsv` with 17 columns and no `view`
- When it is read unlabelled
- Then its config is `<route>-<mode>--`

### REQ-9 Output
The CLI MUST print, per config, AC@1 per fault and pooled with Wilson intervals, coverage and selective accuracy on the pooled line, healthy false alarms when there are healthy rows, and one line per pair of configs (later minus earlier) with the discordant counts, the McNemar p-value and the bootstrap interval. With `--out DIR` it MUST write `report.txt` (identical to the printed report), `scores.tsv`, `paired.tsv`, `inputs.tsv` (label, source, sha256, rows) and a copy of each input as `inputs/<label>-summary.tsv`.

#### Scenario: M0 on dev
- Given `--label A` on the local M0 replay dir and `--label D` on the cloud one
- When `score` runs
- Then it prints `A  pooled   5/15  AC@1  33%  [15%, 58%]`, `D  pooled  14/15  AC@1  93%  [70%, 99%]` and `D - A: 15 cases, D only 9, A only 0, exact McNemar p = 0.0039`

#### Scenario: Regenerate from committed files
- Given a committed `evals/results/<name>/inputs/` directory
- When `score` runs on its copies with the same labels and seed
- Then the report is the same as the committed `report.txt`
