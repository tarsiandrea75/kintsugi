# Feature: service-runbooks

Locator: `odd/tasks/service-runbooks.md` · Engram mirror: `odd/service-runbooks/tasks` · Branch: `feat/service-runbooks` (from `feat/fault-f4-04-check` at `85408f5`)

## Objective

Write one Markdown runbook per lab service in `docs/runbooks/`. This covers the M1 bullet "Service runbooks in Markdown" (`docs/vision.md:150`). In M2 these runbooks become the knowledge base the agent searches (`searchRunbooks`, RAG on pgvector, `docs/vision.md:70,73,156`).

## Design (decided autonomously on 2026-10-07; **to be reviewed by the human**)

- **No fault leakage.** Runbooks never mention the fault catalog (F1–F8), the fault injector, or "this symptom means fault X". They read like real SRE runbooks:
  - what the service does and what it depends on;
  - its endpoints, configuration and the metrics and log lines that matter;
  - for each symptom, several plausible causes and the diagnostic steps (PromQL/LogQL) that tell them apart.

  Why: the benchmark scores the agent against ground truth. A runbook that maps symptoms to injected faults would hand the agent the answer and make the evaluation meaningless. Lab-only hooks (the reservation delay and the gateway admin API) are described only as "lab controls", not as diagnoses.
- **Grounded in what exists:** every metric name, log phrase, property and endpoint must exist in the code, the specs or the dashboard today. Nothing invented.
- **Format:** one file per service: `order-service.md`, `payment-service.md`, `inventory-service.md`, `gateway-mock.md`. Each has the same sections: Purpose, Dependencies, Endpoints, Configuration, Key signals (metrics, logs), Symptoms → possible causes → how to check, Safe actions. `docs/runbooks/README.md` is the index and states the no-leakage rule for future authors.

## Acceptance criteria

- Four runbooks plus the README exist and follow the same structure.
- `grep -niE '\bF[1-8]\b|fault|inject'` (the same pattern as the README rule) finds nothing in the runbooks, except the README's rule about it.
- Every metric, log phrase, property and endpoint cited exists in the repo (spot-checked by the parent).

## Tasks

- [x] **T1 — Runbooks and index.** Passive documentation: there is no runnable RED, and the checks are structural. Route: delegated writer.

## Progress

- 2026-10-07: design decided autonomously; branch created.
- 2026-10-07: T1 done by a delegated writer. Five files: README with the authoring rule, plus four runbooks of 96–119 lines each. The leakage grep matches only the README's rule. The parent spot-checked 3 signals against the code and the dashboard.
- Open (to review): runbooks avoid the word "default" and the dashboard title/uid ("Lab — Faults", `lab-f1`) because they would trip the leakage grep, so renaming the dashboard to a neutral name would let runbooks link to it. Two cited series (`hikaricp_connections_pending` for order-service and payment-service, the `HttpConnectTimeoutException` tag) come from Spring auto-instrumentation and are not written anywhere in the repo.
- 2026-10-07: the lab-observability spec now uses the real payment panel title, "payment-service /payments latency and 503 rate" (`768ff1c`). All 9 dashboard titles were checked against the spec.
