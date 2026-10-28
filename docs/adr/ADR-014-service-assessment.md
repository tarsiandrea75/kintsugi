# ADR-014: Facts arranged per service, the assessed report, and the stop rule

## Status

Accepted. Amends ADR-011's "facts only" rule for the per-service sheets and the assessed report. It records no M2 bar: the feature's plan for this ADR, which had included one, was re-scoped on 2026-10-25 by the vision revision (`odd/tasks/vision-revision.md`). The ADR itself was written on 2026-10-28.

## Date

2026-10-28

## Context

ADR-011 gives the model a deterministic dossier and bans everything in it but facts: no judgments, hints, anomaly markers or thresholds. In decision mode the local model still misses along the call graph (`odd/tasks/service-assessment.md`, "Problem"):
- on M0, the `dev` recordings with today's prompt, `granite4.1:8b` scored F1 0/5, F2 5/5 and F3 0/5, while the cloud route scored 4/5, 5/5 and 5/5;
- on F1 it names payment-service, one hop before gateway-mock; on F3 it names payment-service, one hop after order-service;
- a caller's latency contains its callee's. In F1's first `dev` recording, payment-service's `/payments` mean is 1.229 s against gateway-mock's `/authorize` 1.177 s. Magnitudes cannot find the origin; the call graph can.

The dossier is organized by data source: one service's facts are spread over six tables. Feature service-assessment rearranges the same facts per service and asks the model for each service's role before the cause (T1, T2). Both changes touch what ADR-011 allows in front of the model, so they need a decision of their own.

The feature first planned an M2 bar: at least 4 of 5 per fault on F1-F3 on `holdout`. The vision revision dropped it, and this ADR records why.

## Decision

### The amendment to "facts only"

ADR-011's rule still holds for every line the model reads as evidence: numbers, names, labels, counts and verbatim log signatures, with no judgments, anomaly markers, thresholds or interpreting words. The renderer's banned-words test (`DossierRendererTest.BANNED`) runs on the sheets as it runs on the tables.

The amendment allows two things the rule did not foresee.

1. **The sheets arrange the facts by the call graph** (`kintsugi.agent.decision.view=sheets`). These are allowed:
   - one sheet per lab service, leaf first (gateway-mock, inventory-service, payment-service, order-service), in the order of `CallGraph`, the one call graph in code;
   - a "calls: …; called by: …" line per sheet. It states the topology, which the system message already names, as a fact;
   - header lines that explain the layout;
   - a mechanical split: the rows whose incident count is 0 and whose previous count is above 0 go to a last section, "Seen only in the previous window". The split compares a count with 0. It is no threshold, and it is the same for every fault and every service;
   - dropping the `/actuator/*` latency rows, which are the lab's own probes, for every fault alike.

   No line says which service is slow, failing or the cause. The order, the grouping and the split come from the graph and the counts, never from the fault.

2. **The assessed report asks for roles** (`kintsugi.agent.decision.report=assessed`, sheets only). The report task defines three roles: ORIGIN, AFFECTED and UNAFFECTED. Code checks the model's claims with fault-agnostic rules: Coverage, One origin, Graph, Answer and Citation (agent spec, "The assessed report"). Code then derives the evidence from the lines the ORIGIN and AFFECTED assessments cite. The roles live in the report task, not in the facts. They name a method that holds for any call graph, never a cause: the checks test the report against its own roles and the graph, and none names a service or a category. Why: this applies the core rule to the diagnosis itself. The LLM proposes the roles, and deterministic code verifies that they fit the graph.

Still out: rule-generated hypotheses or causes in front of the model. Code that diagnoses with rules is Config 0, a baseline that is never the agent's answer (vision §3, §5; ADR-016, follow-up 3).

### No M2 bar

This ADR sets no score bar for M2. The M2 check is vision §7's: `docs/benchmark.md` v0 holds the lab holdout-1 table for Configs 0, A and D, with AC@1, Wilson CIs and paired differences. One command regenerates it from committed results, and its plan was committed before the run. It passes whatever the local model scores.

Why the planned bar was dropped. The bar was at least 4 of 5 per fault on F1-F3. With 5 cases, 4/5 has a Wilson 95% CI of [38%, 96%], so one fault's score says little. A model that is truly 80% accurate scores at least 4 of 5 on one fault with probability 0.737, and on all three with 0.737³ ≈ 0.40. So the bar fails such a model about 60% of the time. The data in hand already shows the gap the bar was meant to judge: on M0, on the same `dev` recordings, local scored 5/15 [15%, 58%] and cloud 14/15 [70%, 99%], exact McNemar p ≈ 0.004 (`evals/results/2026-10-25-m0-dev/`).

The variants V1 (sheets, plain report) and V2 (sheets, assessed report) are measured on `dev` only, local and cloud, under a measurement plan committed before the run. ADR-015's dev keep rule decides (`evals/plans/2026-10-28-service-assessment-v1-v2-dev.md`). `holdout` is not read in this feature: holdout-1 is read once in follow-up feature 4, next to Config 0.

### The stop rule

This ADR records the stop rule of vision §5: **local-only adaptations end with service-assessment.** After it, a change must be track-agnostic and win a paired comparison on RCAEval dev. A local miss on the lab is documented, not chased.

Why: nine features gave no local accuracy gain on F1 or F3, and M0 measured the gap above. More lab-only tuning for the local model would fit the three faults, not diagnosis.

### Open question: the Graph rule's retry

The assessed report sends every broken rule, Graph included, to the loop's single retry with the exact error. Vision §3 makes L2 ("the claims fit the topology") and L3 annotations, not retries, "so a check never steers the model toward an answer". The Graph rule is an L2 check. The error names the model's own claims and no cause, but a retry still lets the check reshape the answer.

It stays a retry in this feature, so V2 is measured as built in T2. Follow-up 13 (verification-levels) decides whether the Graph rule, and the other graph rules, become annotations. Whatever it decides, the measured V2 result stays attributable to the retry version.

## Alternatives considered

- **Keep the tables and tune the prompt toward the leaf**: rejected. The prompt already says to follow the calls down (`Prompts.java`), and one generation over tables does not do it. More wording would teach the faults.
- **Mark the slow or failing rows in the facts**: rejected. This is the threshold and marker ADR-011 bans. It encodes the answer.
- **Rule-generated candidate hypotheses for the model to pick from**: moved to Config 0, a baseline (vision §3). In front of the model, they would credit the LLM with the rules' work.
- **Keep the 4-of-5 bar**: rejected, for the failure rate above.
- **Annotate graph violations now instead of retrying**: deferred to follow-up 13. Changing it now would leave V2 unmeasured as built.

## Consequences

### Positive

- The facts the model needs for one service sit in one place, next to the graph. The sheets are no larger than the tables (F3: about 1,600 against 1,820 tokens).
- The assessed report's claims are checked by code against the graph, with exact, fault-agnostic errors, and its evidence is derived by code.
- Both variants sit behind settings whose defaults are today's dossier and report, so M0 and every earlier result stay reproducible.
- The stop rule bounds the local-model work. What follows must hold on RCAEval too.

### Negative

- Two views and two reports to keep working, with tests for each pair. The variants that lose on `dev` are deleted before the feature closes.
- The sheets depend on `CallGraph` being right. A wrong edge would mis-order the sheets and mis-judge Graph. Follow-up 5 (topology as data) replaces the hard-coded copies.
- The Graph retry may conflict with vision §3 until follow-up 13 decides.
- `dev` is 15 cases, 5 per fault. The keep rule's margin of 3 cases is coarse, and a variant that helps one fault by 2 is not kept.
