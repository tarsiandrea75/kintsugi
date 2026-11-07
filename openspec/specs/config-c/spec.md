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
For the selected rule, `escalate` MUST write `C-summary.tsv` in the scorer's summary format, one row per paired case taken from A or D, so that `score` reports C like any other config. It MUST also write `escalation.tsv`, with one row per case giving the case, the source (A or D) and the trigger (`-` when C keeps A's row), `grid.tsv`, with one row per rule, and `escalate.txt`, the printed report. It MUST refuse, writing nothing, when any of these four files already exists in `--out`: the calibration is pre-registered and its result is never silently replaced.

#### Scenario: C scores like a config
- Given `C-summary.tsv` written by `escalate`
- When `score --label C C-summary.tsv` runs
- Then the report shows C's AC@1 with a Wilson CI, like any other label

### REQ-5 Fixed rule
With `--rule k=<L2|L3>,t=<off|number>`, `escalate` MUST compute C under that rule alone, with no grid and no selection, and write the same four outputs: `grid.tsv` holds that one rule, marked selected, with its eligibility shown but not applied. The holdout reads MUST use ADR-030's fixed `k = L2, t = off`. A rule that names another `k`, a `t` that is neither `off` nor a number from 0 to 1, a missing or repeated part, or an unknown part MUST be refused, writing nothing.

#### Scenario: The holdout rule
- Given A and D summaries of a holdout
- When `escalate --rule k=L2,t=off` runs
- Then `grid.tsv` has one row, `k = L2, t = off`, selected, and the report says the rule is fixed with no grid selection

#### Scenario: A fixed rule over the cap
- Given the fixed rule escalates more than 50% of the paired cases
- When `escalate --rule` runs
- Then C is still computed under that rule, and the rule is reported as over the cap

#### Scenario: A malformed rule
- Given `--rule k=L1,t=off`
- When `escalate` runs
- Then it refuses and writes nothing

### REQ-6 Provenance of a holdout read
`escalate` MUST take `--holdout NAME` and `--plan PATH`. On A or D rows of a holdout split it MUST refuse without both, writing nothing; with them it MUST append its `escalate` row to `provenance.tsv` in `--out`, after its four outputs, as the evals spec REQ-18 states. A dev run needs neither and writes no `provenance.tsv`. Why: the holdout reads run `escalate` and then `score` into one result directory, and each leaves its own record of the holdout and the plan (ADR-015).

#### Scenario: The holdout read records itself
- Given A and D summaries of holdout A and `--rule k=L2,t=off --holdout A --plan plans/2026-11-06-holdout-a-re2ob.md`
- When `escalate --out R` runs
- Then `R/provenance.tsv` holds one `escalate` row naming `A`, the plan, its sha256 and the commit

#### Scenario: A holdout read without the flags
- Given A and D summaries whose rows have the split `holdout`
- When `escalate` runs without `--holdout` and `--plan`
- Then it refuses with one line naming the file and the split, and writes nothing
