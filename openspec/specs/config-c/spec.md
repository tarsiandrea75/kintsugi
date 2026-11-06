# Spec: config-c (escalation from A to D)

Config C (`docs/vision.md` §5, [ADR-030](../../../docs/adr/ADR-030-config-c-escalation-rule.md)) is computed by the scorer from the paired outputs of Config A and Config D, with no new agent run. It is run as `python -m kintsugi_evals escalate` from `evals/`. H3 (vision §1) is read on C.

## Requirements

### REQ-1 Paired cases only
C MUST be computed only on the fault cases that have both an A row and a D row, joined on `(split, recording)`. A case missing from either side MUST NOT appear in C.

#### Scenario: A case only A ran
- Given an A summary with cases x and y, and a D summary with case x only
- When C is computed
- Then C has one case, x

### REQ-2 Escalation triggers
For each paired case, C MUST take D's row when A's row:
- has a status other than `COMPLETED`;
- reports `UNKNOWN|UNKNOWN`;
- has a verification level below `k`, or no level;
- has a confidence below `t`, when `t` is not `off`.

Otherwise C MUST take A's row. Each escalation MUST record the first trigger that fired, in the order above. A row without a confidence MUST NOT trigger on `t`.

#### Scenario: A verified answer stays local
- Given A answered `COMPLETED` at L3 with confidence 0.9, and the rule is `k = L3, t = off`
- When C is computed
- Then C keeps A's answer and records no escalation

#### Scenario: A timeout escalates
- Given A's row is `TIMED_OUT`
- When C is computed under any rule
- Then C takes D's row and records the trigger `no-answer`

#### Scenario: A low level escalates
- Given A answered `COMPLETED` at L2, and the rule is `k = L3`
- When C is computed
- Then C takes D's row and records the trigger `level`

#### Scenario: A low confidence escalates
- Given A answered `COMPLETED` at L3 with confidence 0.85, and the rule is `k = L2, t = 0.875`
- When C is computed
- Then C takes D's row and records the trigger `confidence`

### REQ-3 Grid and selection
The `escalate` command MUST evaluate every rule of the grid `k ∈ {L2, L3}` × `t ∈ {off} ∪ {midpoints between consecutive distinct A confidences}`. For each rule it MUST report the escalation count and rate, and C's AC@1, each with a Wilson 95% CI, plus C − D with exact McNemar.

It MUST select the rule with the highest C AC@1 among the rules escalating at most 50% of the paired cases. Ties MUST break by the fewer escalations, then the higher `k`, then `t = off`. When no rule fits, it MUST select `k = L3, t = off` and say that the cost cap fails.

#### Scenario: Cap binds
- Given the rule `k = L3, t = off` escalates 20 of 30 cases and `k = L2, t = off` escalates 11
- When the rules are selected
- Then `k = L3, t = off` is not eligible

#### Scenario: Tie on accuracy
- Given two eligible rules with the same C AC@1, escalating 11 and 13 cases
- When the rules are selected
- Then the rule escalating 11 cases is selected

#### Scenario: Nothing fits
- Given every rule escalates more than 50% of the cases
- When the rules are selected
- Then `k = L3, t = off` is selected and the report says the cost cap fails on this split

### REQ-4 C as an ordinary summary
For the selected rule, `escalate` MUST write `C-summary.tsv` in the scorer's summary format, one row per paired case taken from A or D, so that `score` reports C like any other config. It MUST also write `escalation.tsv`, with one row per case giving the case, the source (A or D) and the trigger, and `grid.tsv`, with one row per rule.

#### Scenario: C scores like a config
- Given `C-summary.tsv` written by `escalate`
- When `score --label C C-summary.tsv` runs
- Then the report shows C's AC@1 with a Wilson CI, like any other label
