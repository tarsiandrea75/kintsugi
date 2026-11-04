# ADR-028: Verification levels L2 and L3 as annotations on a per-line fact index

## Status

Accepted

## Date

2026-11-04

## Deciders

The human (decisions taken on 2026-11-04 and recorded here; the feature document is [verification-levels](../../odd/tasks/verification-levels.md)).

## Context

Vision §3 defines five verification levels and says "L2 and L3 are annotations, not retries, so a check never steers the model toward an answer. Every report states its level." Vision §6 scores "verified-correct" (correct at L3 or above) and the "L1 rate" (every cited line exists). Today only L0 and L1 exist, and no report states a level (`docs/benchmark-limits.md`, line 26).

What the code does today:

- **L0, schema.** `ReportCheck.check` (`agent/.../loop/ReportCheck.java`:183) rejects a report that breaks the schema; the loop gives it one retry, and a second invalid report ends the run `INVALID_REPORT`.
- **L1, the cited line exists.** An evidence item whose line is not in the dossier or its step's output is dropped, not fatal (`ReportCheck.item`, :528); the report stays valid while one verified item is left. The verified item gets its `quote` (:608).
- **Graph rules on the assessed report.** `ReportCheck.graphRules` (:396-427) checks One origin, Graph and Answer on the assessed report (SHEETS view) and sends every breach to the retry. ADR-014 left this as an open question ("Open question: the Graph rule's retry") and the agent spec repeats it (`openspec/specs/agent/spec.md`, "The assessed report"): the Graph rule is an L2 check, and a retry lets the check reshape the answer. Vision-revision follow-up 13 was to decide it.
- **Line ownership** exists only in the sheets: `ServiceSheets.Rendered.owner(n)` gives the service, or `header`, of each numbered line. The tables view has no owner per line, and neither view records whether a line's fact changed between the windows.
- **Change thresholds** exist in Config 0's rules: R-DOWN's slow latency, an incident mean at least 2× the previous mean and at least 0.5 s (`RulesDiagnosis.SLOW_RATIO`, `SLOW_SECONDS`), and R-RES's resource-abnormal, an incident mean at least 2× the previous mean and above the previous max, both windows usable (`ResourceRule.RATIO`, `abnormal`).

The freeze tag `freeze-m3` covers `agent/src/main/` from 2026-12-01 (`infra/scripts/check-freeze.sh`, `FROZEN_PATHS`), so the agent's part must land and be checked before then.

## Decision

### 1. A level is an annotation, never a retry

Only L0 keeps triggering the single retry. L1 keeps dropping an unverifiable item, unchanged. L2 and L3 are computed on the report the loop accepts and stored with it; they never reach the prompt, never reject a report and never cause a retry.

A report's **level** is the highest `k` such that every check at levels 0 to `k` passes. L1's check passes when no evidence item was dropped (a summary cut is not a dropped item), so the L1 rate measures the reports whose every citation existed, which is the old "Real evidence". A report with a dropped item is stored as today at level L0.

### 2. The assessed report's graph rules become L2 annotations

One origin, Graph and Answer (`ReportCheck.graphRules`) stop being errors. They become L2 checks of the assessed report, so they no longer trigger the retry. Coverage, the assessments' schema and Citation stay L0/L1 errors as today, since the derived evidence needs them.

Why: the retry feeds the broken rule's text, which names services and their roles on the call graph, back into the prompt, and so steers the model toward a graph-consistent answer. That is the leakage principle's logic (vision §3): a check must not teach the answer. This closes ADR-014's open question and vision-revision follow-up 13.

Scope of the change: the SHEETS view is not on the measured path. The default view is `tables` (`agent/src/main/resources/application.yaml`:91), and no measurement plan uses `sheets` with `assessed`. Committed SHEETS/assessed results stay valid as historical results, measured under the retry behaviour; a new assessed run is not comparable to them without saying so.

When Answer fails, the report keeps the model's `rootCauseService` as its answer (it is what is scored), and the derived evidence is still the ORIGIN and AFFECTED lines.

### 3. A per-line fact index beside the rendered text

Both renderers, the tables (`DossierRenderer`) and the sheets (`ServiceSheets`), return, beside the text, one **line fact** per numbered line `Dn`:

| Field | Meaning |
|---|---|
| `owner` | the service the fact is about, or `header` for the header's lines, a section heading's line and any line with no single service |
| `section` | one of `header`, `status`, `states`, `traffic`, `latency`, `resources`, `logs`, `events` |
| `changed` | `YES`, `NO` or `NOT_COMPARED`, by the rules of Decision 5 |

The rendered text stays byte-identical in both views: the goldens and the prompt do not change. The index is computed from the typed `Dossier`, never parsed back from the text.

Owner per section (the sheets' existing owners, `ServiceSheets.Rendered.owners`, extended to the tables; where the sheets have no rule, a new design choice):

- `status`, `latency`, `resources`, `logs`: the row's service.
- `traffic`: the row's `service` label, so a client row belongs to the caller that measured it and a server row to the service that answered.
- `states` (orders, payments tables) and `events` (order, payment, reservation sources): the service `Topology.ownerOfTable` or `Topology.ownerOfSource` names, as the sheets already do (`ServiceSheets.java`:112-117); `header` when the topology names none.

### 4. L2: the claims fit the topology

For the plain and the hybrid report, L2 passes when every stored evidence item cites a dossier line whose owner is the claimed cause service or lies on its call path: the cause itself, or a transitive caller or a transitive callee of it in the topology (`Topology.calls`, `calledBy`). A citation of a tool step (explorer or dossier mode) has no line fact, so its L2 check fails with a detail saying so. The alternatives being topology causes is already an L0 rule (`ReportCheck.alternatives` checks the enum of `Topology.causes()`), so L2 records it as passed by construction rather than re-checking it.

"On its call path" as the transitive closure in both directions is a new design choice: in the lab a cause's callers show its symptoms (an F1 timeout is visible at payment-service and order-service), and its callees can show the caller's misconfiguration (F3), while a service on another branch cannot explain the cause.

For the assessed report, L2 is the three former graph rules (Decision 2) plus the same owner rule over its derived evidence.

### 5. L3: the cited fact belongs to the cause and changed

L3 passes when **at least one** stored evidence item cites a line whose `owner` is the claimed cause service and whose `changed` is `YES`.

Per section, `changed` is decided as follows. Where an existing Config 0 rule decides change, its test is reused with the same constants; elsewhere the rule is a new design choice.

| Section | `changed` is `YES` when | Source |
|---|---|---|
| `latency` (one row: service, uri) | incident mean ≥ 2 × previous mean and ≥ 0.5 s, previous mean > 0 | reused: R-DOWN's slow test (`RulesDiagnosis`:241) |
| `resources` (one row: service, metric) | both windows usable, incident mean ≥ 2 × previous mean and > previous max | reused: R-RES (`ResourceRule`:76-91) |
| `traffic`, `states`, `events` (one row with a previous and an incident count) | the larger of the two counts is ≥ 3 and ≥ 2 × the smaller, so a row that appears, grows, shrinks or disappears counts; a line of "Seen only in the previous window" is its row with an incident count of 0 | **new design choice**; the 3 mirrors R-DOWN's `REFUSING_ANSWERS` and `SLOW_TIMEOUTS` |
| `status` | the service restarted inside the incident window (`restartsInWindow` not empty) | reused: the rules' `restarted` (`RulesDiagnosis`:289) |
| `logs` | never: logs are gathered for the incident window only, so there is nothing to compare; `NOT_COMPARED` | **new design choice** |
| `header`, a failed fact (`Gathered` not ok), a line with no value | never; `NOT_COMPARED` | **new design choice** |

`NOT_COMPARED` never satisfies L3. The constants are copied into the dossier package rather than shared, so Config 0's code does not change; a test pins each copy to its rule's constant.

**Alternative rejected: every item must pass.** Citing a caller is legitimate evidence: an F1 gateway latency is visible as payment-service's timeouts, and a report that cites both the cause's latency and the callers' timeouts is a better report, not a worse one. "Every item belongs to the cause" would score it below a report that cites one line. L2 already bounds the other items to the cause's call path.

### 6. Abstention

A report naming `UNKNOWN|UNKNOWN` claims no cause, so L2 and L3 do not apply: its checks record them as not applicable (`pass` = null) and its level is at most L1. Verified-correct is a rate over **fault** cases only; on a healthy control a correct `UNKNOWN|UNKNOWN` is scored by the false-alarm rule (ADR-015), as today, and does not enter verified-correct. An abstention on a fault case is not correct, so it is never verified-correct.

### 7. Storage and scoring

- The stored report (`AgentLoop.stored`, :773-788) gains `verification: {level, checks[]}`, each check `{level, name, pass, detail}`, after `evidence` and before `stepsUsed`. A run with no stored report has no level.
- `ReplayDriver` adds a `level` column to `summary.tsv`, after every existing column: `L0` to `L3`, or `-` for a run with no stored report and for a `rules` line (Config 0 writes no cited evidence).
- The scorer reads `level` as an optional column, so older TSVs still score and regenerate byte for byte. When it is present it adds **Verified-correct** (fault rows correct at L3 or above, over fault rows) and the **L1 rate** (rows at L1 or above, over rows with a stored report), each with its Wilson 95% CI like the other rates.

### 8. The freeze

Everything under `agent/src/main/` freezes at the tag `freeze-m3` on 2026-12-01. The index, the checks and the stored field land and pass their checks before then. `ReplayDriver` (`agent/src/test`), `evals/` and `infra/` are not frozen.

## Alternatives considered

- **Keep the graph rules as retries.** Rejected (Decision 2): the retry steers the model with topology hints, against vision §3.
- **L2/L3 as retries, like L0.** Rejected: vision §3; a level must describe the answer, not shape it.
- **Parse owners and values back from the rendered text.** Rejected: brittle, and it would tie the checks to the wording of the prompt; the typed `Dossier` already holds the facts.
- **L3 on every evidence item.** Rejected (Decision 5).
- **One generic "changed" rule, such as any difference between the windows.** Rejected: latency and traffic always differ a little between two windows, so every fact would count as changed and L3 would mean nothing.
- **Share the thresholds by moving them out of `RulesDiagnosis` and `ResourceRule`.** Rejected: it would touch Config 0's code, whose verdicts must stay unchanged; pinned copies give the same values.
- **Treat a WARN/ERROR log line as changed.** Rejected for now: a steady background warning would pass L3 with nothing to compare it to. Recorded as a known limit.

## Consequences

### Positive

- Every stored report states its level, and the benchmark can report verified-correct and the L1 rate (vision §6), with CIs.
- The assessed report's checks follow vision §3: no check steers the model.
- Config C's escalation on "an L2/L3 failure" (vision §5) becomes computable from stored outputs.

### Negative

- **The SHEETS/assessed behaviour changes.** A report that broke One origin, Graph or Answer was retried and is now accepted with a lower level. Committed assessed results are historical; any comparison with a new assessed run must say so.
- **Verified-correct is only as strong as the line-fact index's "changed" rules.** The latency and resource rules are Config 0's thresholds, so L3 partly measures agreement with the rules' notion of change; the traffic, states and events rule is new and untested on RCAEval; logs never count. A rate measured under these rules is reported with them named.
- The stored report and `summary.tsv` gain a field and a column; incident JSON readers that check fields strictly must accept `verification`.
- The renderers carry a second output that must stay in step with the text; a test checks one fact per numbered line in both views.
