# d-abstention

## Objective

Find out how much of Config D's abstention on RE2-OB dev comes from a misread latency table and how much from sampling noise, before any change to the agent. Then decide, with numbers, which general change to try before the freeze.

## Problem

D reports `UNKNOWN|UNKNOWN` on 17 of 30 RE2-OB dev cases (`evals/results/2026-11-02-baro-pipeline-re2ob-dev/`), and H1's primary test is D − 0R. A read-only diagnosis on 2026-11-03 (run `infra/scripts/out/replay-20261102T024016Z-cloud/`) found:

- **The model abstains, not our code:** all 17 are valid first-attempt reports. No check, cut, cap or fallback produced them.
- **The dossier held a signal on the true service in all 17:** a resource rise in 12, on which 0R is right in 11; a latency rise of 13 to 33 times in the 5 delay cases.
- **Proved to occur:** in all 5 delay cases the model pairs the wrong columns of the latency table. For example, `880fd526` quotes `| frontend | / | 0.054 | 1.325 | 0.061 | 1.477 |` and writes "mean 0.054s incident vs 0.061s before", which is the previous mean against the previous max. In 8 of the 12 resource cases it sees the rise and dismisses it because latency is normal or there is no error.
- **Likely, not proved:** the prompt gives no warrant for resource evidence. `Prompts.java` says "Prefer UNKNOWN to a guess" and defines `RESOURCE_EXHAUSTION` as a component that "has run out" of a resource.
- **Not proved:** sampling noise. The cloud route sends no temperature, and there is one sample per case.
- **Corrections to earlier readings:** D's 11 of 13 is service-only, and 7 of 13 with the category. A2100 does not read the signal better: it always names a service, and it is wrong on 9 of D's 17.

## Why

Without the noise floor, a later change that moves D by a few cases on 30 could be noise, and that would be fitted to dev. Without the misread count, the table change and the prompt change cannot be ranked.

## Scope

- A latency-citation audit over D's evidence items, as a tested `evals/` command.
- Three repeats of D on RE2-OB dev, about $0.10, approved by the human on 2026-11-03.
- One plan and one result.

Out of scope here: any change under `agent/`, any holdout, A runs. The table and prompt changes are the next feature, chosen from this one's numbers.

## Constraints

- Plans are committed before the runs; Wilson 95% CIs; results under `evals/results/`.
- Config 0 runs alongside: its committed input, deterministic.
- The audit's rule is fixed before it reads the repeats.

## Design

**The audit.** Per D run, for every evidence item whose `quote` is a latency row (`| service | uri | previous mean | incident mean | previous max | incident max | points | points |`):

- Read the four numbers, and the numbers in its `why` (seconds, or milliseconds divided by 1000), matched within half a unit of the row's last digit.
- **Incident cited:** the `why` names the incident mean or the incident max.
- **Previous only:** it names a previous value and neither incident value. This is the misread's signature: the model compares two previous values as if one were the incident.
- **No number:** it names none of the four.

The command prints the counts per run and per case, abstaining or not, and lists every "previous only" item with its case, so a human can read them. It reads only `report.evidence`; the summary text is not tied to a row.

The rule cannot see a misread that cites an incident value wrongly, or one in the summary only, so it gives a lower bound.

## Tasks

- [x] T1 The audit command, tests first, on synthetic incident JSON; the living spec. Route: delegated writer.
- [x] T2 The plan for the repeats and the audit. Route: inline (docs).
- [x] T3 Three D runs, the audit on four runs, the score and the result. Route: inline commands.

## Acceptance criteria

- AC1: on synthetic reports, the audit classifies the `880fd526` pattern as "previous only", an incident value as "incident cited", and milliseconds correctly.
- AC2: the three repeats use the same recordings, route, view, report and model as the original run.
- AC3: the result gives each run's AC@1 with its Wilson CI, the abstention set's stability per case, the discordant counts between runs, and the audit's counts.
- AC4: no holdout is converted or opened, nothing under `agent/` changes.

## Checks

- T1: `cd evals && .venv/bin/python -m pytest -q`, red first.
- T3: the plan's mechanics checks.

## Delivery

Forecast: about 250 authored lines for T1, about 100 for T2–T3. Branch `feat/d-abstention`, from `feat/rcaeval-baro-reproduction`. Nothing is pushed.

## Progress

- 2026-11-03: diagnosis read-only (above). The human approved the audit and the three repeats.
- 2026-11-03: T1 done by a delegated writer (`8b5c488`): `audit-latency`, REQ-14. RED 19 failed against a stub; GREEN 310 passed. About 402 authored lines.
  - **Order, stated plainly:** the writer's smoke check ran the audit on D1 before the plan was committed. The plan's text was written before that output and is committed unchanged. On D1: 31 latency-row items; abstaining samples 7 `incident_cited`, 9 `previous_only`, 2 `no_number`; answering samples 7, 6, 0.
  - The writer's reading, not acted on: about half the `previous_only` items are on rows that barely moved, where naming the previous column changes nothing. The plan's rule counts them anyway; it is not changed after seeing this.
  - Precision on 880fd526: "mean 0.054s incident vs 0.061s before" is in the report's summary; its evidence `why` reads "0.054 mean in incident window, comparable to previous 0.061 max". Both pair the previous mean with the previous max.

- 2026-11-03: native review of `f8d1200..55ce0a3`: one reliability lens, **approved** and acknowledged (lineage `review-6c4ab8bc6ad05287`). WARNING R3-decision-rule-unit-mismatch: the plan's rule counts samples, the audit prints items. Closed in T3 by counting samples from `audit.tsv`, whose rows carry the recording. Not fixed, three SUGGESTIONs on `audit_latency.py`: a file with no report counts as abstained; some malformed JSON shapes end in a traceback; no test asserts that a refusal writes nothing.
- 2026-11-03: T2 done (`55ce0a3`). T3 done on `55ce0a3`: runs `replay-20261103T050213Z-cloud`, `-050408Z-`, `-050551Z-`, each 30 rows, all `COMPLETED`, the plan's configuration, the same recordings as D1. Cost about $0.10 (per-fault `aggregate.tsv`). Results in `evals/results/2026-11-03-d-repeats-re2ob-dev/` (`audit/` holds REQ-14's output). AC2–AC4 hold.

| Run | AC@1 [Wilson 95%] | Coverage | Selective | Run − 0R, discordant, p |
|---|---|---|---|---|
| D1 | 11/30, 37% [22%, 54%] | 43% | 85% | −30, 4 vs 13, 0.049 |
| D2 | 12/30, 40% [25%, 58%] | 43% | 92% | −27, 4 vs 12, 0.077 |
| D3 | 12/30, 40% [25%, 58%] | 47% | 86% | −27, 4 vs 12, 0.077 |
| D4 | 13/30, 43% [27%, 61%] | 43% | 100% | −23, 5 vs 12, 0.14 |

  - **Noise floor: 6 cases**, D4 − D1 (4 against 2). The other pairs have 3 to 5 discordant cases.
  - **The abstentions are mostly stable:** 13 cases abstain in all four runs, 8 in none, 9 vary. Right answers: 8 cases in all four runs, 14 in none. So D's deficit against 0R is not noise: every run is 23 to 30 points behind.
  - **The plan's ordering rule fires:** 26 of 67 abstaining samples (0.39) hold at least one `previous_only` item, against a threshold of a third. So the table change goes first.
  - **A caveat, from the same numbers:** 17 of 53 answering samples (0.32) also hold one. The misread is about as common when D answers as when it abstains. So the misread is real, but this result does not show that it causes the abstentions. The table change will test that.
  - Items, D1–D4: abstaining 38 `incident_cited`, 29 `previous_only`, 2 `no_number`; answering 30, 19, 1.

## Next step

The feature is complete. The human decides on the next one. The plan's rule puts the table change first: latency rows rendered as labelled before → during pairs. It counts only if it beats the D runs here by more than 6 cases and wins the paired test against 0R that the stop rule requires.
