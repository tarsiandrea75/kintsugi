# Spec: verdict (the H1–H3 decision)

The H1–H3 verdict ([ADR-031](../../../docs/adr/ADR-031-h1-h3-verdict-procedure.md), `docs/vision.md` §1) is computed by the scorer from the holdouts' summaries. It is run as `python -m kintsugi_evals verdict` from `evals/`:

```
python -m kintsugi_evals verdict \
  --input <TRACK> <LABEL> <DIR or summary.tsv> ...   # LABEL is 0R, A or D; repeatable
  --service-only <TRACK> ...                          # RCAEval tracks; repeatable
  --h1-track <TRACK> ...                              # the RCAEval holdouts H1 pools; repeatable
  --seed <N> --iterations 10000 --out <DIR>
```

A track is one holdout (e.g. `holdout-2`, `A`, `B`, `TT`). C is not an input: it is computed from each track's A and D under ADR-030's fixed rule.

## Requirements

### REQ-1 One one-sided condition per margin
The verdict MUST test these conditions, each against its margin:

| Condition | Statistic | Holds when | Cases | Tracks |
|---|---|---|---|---|
| H1 | D − 0R, AC@1 on the service | > 0 | fault cases both ran | `--h1-track` |
| H2a | A − D, AC@1 | > −10 points | fault cases both ran | tracks with A and D |
| H2b | false-alarm rate A − D | < +10 points | healthy cases both ran | tracks with A and D |
| H2c | median of A's time to report | < 15 minutes | every A case | tracks with A |
| H3a | C − D, AC@1 | > −5 points | fault cases A and D both ran | tracks with A and D |
| H3b | C's escalation rate | < 50% | the same cases | tracks with A and D |

AC@1 MUST be scored as `score` scores it: on the service for a track named by `--service-only`, on the full answer otherwise. An H1 track MUST be scored on the service, and MUST have D and 0R. A false alarm MUST be the scorer's `false_alarm` outcome (REQ-7 of the evals spec). A's time to report MUST be the summary's `seconds` column, the replay driver's wall time of the agent loop; a missing or non-numeric value MUST be refused.

#### Scenario: The lab is scored on the full answer
- Given a lab track without `--service-only`, where A names the right service with the wrong category
- When the verdict runs
- Then A's answer is not correct in H2a

#### Scenario: An H1 track not scored on the service
- Given `--h1-track B` and no `--service-only B`
- When the verdict runs
- Then it refuses

### REQ-2 The bootstrap p-value
Each condition's p-value MUST be the share of bootstrap resamples whose statistic falls on the null side of its margin, a resample equal to the margin counting on the null side. The resampling unit MUST be the case: a case's repeats MUST move together, each case weighing once by the mean of its repeats. Pooling over tracks MUST resample within each track, keeping its size. The bootstrap MUST take the recorded seed, and every condition MUST start from it. A condition with no case MUST have p = 1 and not hold.

#### Scenario: Every resample clears the margin
- Given a condition whose every case clears its margin
- When the p-value is computed
- Then it is 0

#### Scenario: A tie with the margin
- Given a condition whose every resample equals its margin
- When the p-value is computed
- Then it is 1

#### Scenario: Pooling keeps track sizes
- Given track 1 with 3 cases all +1 and track 2 with 7 cases all 0
- When the pooled statistic is resampled
- Then every resample is exactly 0.3

#### Scenario: Repeats move together
- Given a case D answered right on one of two repeats
- When H1 is computed
- Then that case counts once, as 0.5

#### Scenario: Deterministic
- Given the same inputs, seed and number of resamples
- When the verdict runs twice
- Then both are identical

### REQ-3 Alpha
A condition MUST pass, unadjusted, when its p-value is below 0.025, one-sided.

#### Scenario: The CI reading
- Given a condition whose two-sided 95% percentile CI lies wholly on the alternative side of its margin
- Then its p-value is below 0.025

### REQ-4 Intersection-union
A hypothesis MUST take the largest p-value among its conditions: H1 has H1; H2 has H2a, H2b and H2c; H3 has H3a and H3b.

#### Scenario: One condition fails
- Given H3a has p = 0 and H3b has p = 1
- Then H3's p-value is 1

### REQ-5 Holm over the family
The verdict MUST sort the three hypotheses' p-values in ascending order and compare them with 0.025/3, 0.025/2 and 0.025 in turn. A hypothesis MUST hold only when its p-value and every smaller one are below their thresholds; at the first that is not, it and every larger one MUST not hold.

#### Scenario: The step-down stops
- Given p-values 0.001, 0.02 and 0.03
- Then the hypothesis with 0.001 holds (below 0.00833), and 0.02 fails against 0.0125, so it and 0.03 are not supported

#### Scenario: The smallest fails
- Given p-values 0.009, 0.02 and 0.024
- Then no hypothesis holds, though 0.024 is below 0.025

### REQ-6 Reading and output
Every condition MUST report its cases, point estimate, two-sided 95% bootstrap percentile CI and unadjusted p-value. Every hypothesis MUST report its intersection-union p-value, its Holm-adjusted p-value and `holds` or `not supported`. H1 MUST also report "rules are enough" when its 95% CI upper bound is below +5 points. The report MUST name the seed, the resamples, C's rule and each input with its sha256.

The verdict MUST print the report and write `verdict.txt` (the report) and `verdict.tsv` (one row per condition, then one per hypothesis) to `--out`. It MUST refuse, writing nothing, when either file exists. It MUST refuse a label other than 0R, A or D, and a label given twice on one track.

#### Scenario: Rules are enough
- Given D and 0R answer every H1 case alike
- When the verdict runs
- Then H1 is not supported, and the report says rules are enough

#### Scenario: Never overwritten
- Given `verdict.tsv` exists in `--out`
- When the verdict runs
- Then it refuses and both files are unchanged

### REQ-7 C under the fixed rule
C MUST be computed per track from A and D under ADR-030's fixed `k = L2, t = off` (config-c REQ-2): on a trigger C takes every D repeat of the case, otherwise A's answer. A case A holds twice MUST be refused. H3b's escalation rate MUST be the share of these cases C takes from D.

#### Scenario: Escalation with repeats
- Given A answers case y at L1 and D ran y twice
- When C is computed
- Then C takes both of D's repeats of y, and y counts as escalated

### REQ-8 Outside the family
Secondary results (A − 0R, D − 0, A − 0, per-track splits, exact McNemar) MUST NOT enter the verdict. They are scored with `score` and labelled secondary or exploratory (ADR-022).

#### Scenario: A secondary config
- Given `--input lab 0 <dir>`
- When the verdict runs
- Then it refuses: Config 0 is scored with `score`
