# Feature: revise the vision to measure what the LLM adds, on incidents nobody tuned for

## Objective

Revise `docs/vision.md` so that its headline claims are:
- falsifiable;
- pre-registered by the commit that writes them (H1–H3);
- comparable with published work;
- reachable by Dec 27 on this laptop.

This change is docs only. The follow-up features are listed so they can be sequenced; none is implemented here.

## Problem

Today's numbers cannot tell "it diagnoses" apart from "it recognizes a catalog its own designer tuned it for". Why:
- there is no baseline;
- no incident outside the catalog is ever tested;
- n is 5 per fault;
- the answer space is the catalog itself;
- the prompt contains the F1 and F3 discriminators;
- the checks verify form, not truth.

A review of 8 critiques, checked against the vision, the code and the feature documents, found that 6 hold and 2 hold in practice but not in the vision's stated intent.

### The 8 critiques, verified

| # | Critique | Verdict | Key evidence |
|---|---|---|---|
| 1 | Lab too clean | Holds | One fault at a time (a second gets 409). `ActivateRequest` carries only the id (`FaultResponses.java:24`). No load generator. The harness removes noise on purpose (`lib.sh:667-715`). F4 is not in the agent eval (`verify-agent.sh:56`). |
| 2 | Where is the intelligence | Half | Code doing the heavy lifting is the vision's own thesis (§5). ADR-011:112 refused to let code diagnose, "the LLM would become redundant", yet nothing measures that redundancy: there is no baseline. |
| 3 | Small n, forking paths | Holds | Gate 1 rests on n=5 per fault; 4/5 has a Wilson 95% CI of [38%, 96%]. The project already paid once for this: "every keep-or-revert decision since 2026-10-19 rested on … baselines [that] were wrong" (`decision-instrument.md:47`). |
| 4 | Structural leakage | Holds, worse than stated | The taxonomy is exactly the 4 catalog categories (`Causes.java:15-19`). The prompt encodes the F1 and F3 discriminators (`Prompts.java:60-67`), against its own doc comment (`:15-16`). Topology and order-flow tips are hard-coded (`:23-26`, `:41-45`). |
| 5 | Verifies form, not truth | Holds | `ReportCheck` checks existence only (`ReportCheck.java:422-443`); a success row was cited for a ConnectException. |
| 6 | What "correct" means | Holds | Exact `service\|category`. `confidence` is unused. UNKNOWN counts as a MISS. There are no healthy cases and no calibration, abstention or false-alarm measure. |
| 7 | Local-first deforms | Half | The vision calls it a hypothesis (`vision.md:111`). The M2 check (`:158`) and ADR-012:26 make it a gate. Nine features gave no local accuracy gain on F1 or F3, and no ADR has a stop rule. M0 on `dev`: local 5/15 [15%, 58%] against cloud 14/15 [70%, 99%] on the same recordings, exact McNemar p ≈ 0.004. |
| 8 | The lab becomes the product | Holds | `evals/` is empty and results are git-ignored. There is no `docs/benchmark.md`. About half of the 381 commits are docs. |

Corrections to the critiques' own premises:
- WSL sees 23 GB of RAM, not 32.
- The "API" route is `claude-haiku-5-5`, the cheapest model (ADR-012:29), not a frontier model.
- Decision mode makes one model call, so the vision's old hybrid config C had nothing to split.

## Why

A benchmark whose numbers cannot separate diagnosis from catalog recognition says nothing to an SME, or to a reader of the portfolio. The human chose two tracks:
- the lab, for the live loop;
- RCAEval, offline, for incidents nobody tuned for.

To pay for it, F5–F7, long-term memory and the live page are cut.

## Scope

In:
- `docs/vision.md`: header, §1, §2, §3, §4 (rows that now contradict), §5, §6, §7, §8, §9 (the demo line), §10;
- `odd/tasks/service-assessment.md`: the M2 bar, T2r, and the rewording of T3–T5;
- pointer updates: ADR-012's status, `docs/setup.md`'s M2 check, the fault-injector and payment-service specs;
- `CLAUDE.md`: the core rule and two working conventions.

Out:
- any code, script or test;
- the follow-up features below;
- §11 of the vision, which stays as history.

## Traceability

Each critique, the vision text that answers it, and the follow-up feature that makes it real.

| # | Critique | Answered in `docs/vision.md` | Follow-up |
|---|---|---|---|
| 1 | Lab too clean | §2 "The lab": load generator, healthy controls, fault parameters, decoy categories; the one-fault-at-a-time limit; RCAEval as the second track | 10, 6 |
| 2 | Where is the intelligence | §3 core rule: "A rules-only configuration diagnoses from the same facts, so the LLM's contribution is a number"; §5 Config 0; §1 H1 | 3, 4 |
| 3 | Small n, forking paths | §6 protocol: committed measurement plan, Wilson CIs, exact McNemar, cluster bootstrap, a holdout read once, the dev keep rule; §1 Holm correction | 1, 11 |
| 4 | Structural leakage | §3 leakage principle; §2 decoy categories; §2 RCAEval holdouts, one on a system never seen | 3, 5, 6, 7 |
| 5 | Verifies form, not truth | §3 verification levels L0–L4 and "verified-correct"; §6 L1 rate and verified-correct | 13, 14 |
| 6 | What "correct" means | §6 metrics: AC@1, AC@3, Avg@5, coverage, selective accuracy, false alarms, Brier score; §2 healthy controls and pseudo-cases | 1, 7, 10 |
| 7 | Local-first deforms | §1 H2 as a measured claim; §5 stop rule; §7 M2 check "passes whatever the local model scores" | 2, 4, 15 |
| 8 | The lab becomes the product | §7 checks tied to `docs/benchmark.md`; §8 `evals/` with committed `results/`; §10 risk "The lab becomes the product" | 1, 4 |

## Follow-up features (sequenced, not in this change)

| # | Feature | Milestone | ~Lines | Needs |
|---|---|---|---|---|
| 1 | eval-protocol: ADR-015, the `evals/` scorer (Wilson, exact McNemar, cluster bootstrap), measurement-plan template, `concepts.md` section on measuring honestly | M2 | 350 | — |
| 2 | service-assessment close: `--report`, the WARNING fix, ADR-014, V1/V2 on dev | M2 | 300 | 1 |
| 3 | rules-baseline: `RulesDiagnosis` over Dossier and graph running the prompt's own rules; a `rules` route; ADR-016; correct `Prompts.java:15-16` | M2 | 450 | 2 |
| 4 | m2-measurement: holdout-1 once (0, A, D); `benchmark.md` v0 | M2 | 60 + docs | 1–3 |
| 5 | topology-as-data, replacing the ~7 hard-coded copies; lab goldens byte-identical | M2 | 450 | — |
| 6 | rcaeval-etl: ADR-017 (pinned revision, ADR-001 boundary amendment, category map); converter; healthy pseudo-cases; leakage grep | M2–M3 | 500 Python | 1 |
| 7 | ranked-report (1–5 candidates; NETWORK and CODE_DEFECT) | M3 | 500 | 5 |
| 8 | case-dossier (`DossierSource` seam, `resources` section, `--cases` replay, contract test with 6) | M3 | 450 | 5–7 |
| 9 | rcaeval-baselines (BARO; reproduce its published RE2-TT Avg@5) | M3 | 300 Python | 6 |
| 10 | lab-realism (load generator, healthy, parameters, F8, F4/F8 in the eval) | M3 | 650 | 1 |
| 11 | freeze + holdout-2 (tag, seed after the tag, 75 recordings over 3 nights) | M3 | 60 | 3, 7, 8, 10 |
| 12 | policy-approval (risk levels; restart only after approval) | M3 | 450 | — |
| 13 | verification-levels (L2/L3 annotations; verified-correct) | M3 | 350 | 3, 7 |
| 14 | live-probes (L4), cuttable | M3 | 400 | 12 |
| 15 | config-B (14B ADR, 2 nights), cuttable | M4 | 40 | 8 |

## Acceptance criteria

1. Every one of the 8 critiques has a vision sentence and a follow-up feature in the traceability table.
2. The vision states H1–H3 with their thresholds and the Holm correction, the configuration ladder 0, A, B, C, D and D+, the stop rule, the protocol and the new M2–M4 checks, with the numbers of the approved plan.
3. `rg -n "correctly diagnosed with a local model" docs/ openspec/ CLAUDE.md` matches only historical feature docs and ADR bodies, never `docs/vision.md`, `docs/setup.md` or `CLAUDE.md`.
4. `rg -n "F5|F6|F7" docs/vision.md` matches only the cut note.
5. The live page and long-term memory appear in the vision only as cut or removed.
6. The Wilson 95% intervals and the bar's failure rate quoted here, in the vision and in `service-assessment.md` are recomputed with Python and match.
7. ADR-012, `docs/setup.md` and the two specs point at the revised vision; their bodies keep their history.
8. A whole-file readback of the vision finds no contradiction left, and none with ADR-001, -003, -011 and -012 left unpointed.

## Tasks

- [x] T1 `docs/vision.md`, section by section. Route: delegated writer; trigger: 2+ non-trivial files and the parent's ~150k context backstop (the plan had said inline).
- [x] T2 `odd/tasks/service-assessment.md`: the M2 bar, T2r, and T3–T5 reworded. Route: delegated writer; same trigger.
- [x] T3 Pointer docs (ADR-012, `docs/setup.md`, the fault-injector and payment-service specs) and `CLAUDE.md`. Route: delegated writer; same trigger.
- [x] T4 Verification: the `rg` checks, the Python recomputation of the intervals, `git diff --stat`, and a whole-file readback of the vision. Route: delegated writer, then a parent spot check.
- [ ] T5 Session close: `docs/STATE.md`, `docs/journal/2026-10-28-01.md`, then `infra/scripts/backup-bundle.sh`. Route: parent, inline.

## Delivery

Docs only. One branch, `docs/vision-revision`, from `4db47a9`. The human reads the vision diff before the commit, since it is the source of truth. Planned commits, Conventional Commits with no attribution lines: `docs(vision): …`, `docs(service-assessment): …`, `docs: point ADR-012, setup and specs at the revised vision`. The native risk assessment is expected to be passive, so a structural readback.

## Progress

- 2026-10-25: branch `docs/vision-revision` from `4db47a9`. T1–T4 written by a delegated writer, not committed; the human reads the diff first.
  - Found while writing, for the human: the assessed report's consistency rules (service-assessment T2) send a Graph violation to the single retry. The revised §3 makes L2 and L3 annotations, not retries. Follow-up 13 decides whether that retry stays.

## Next step

The human reads the diff. Then the three commits, the risk assessment, and T5.
