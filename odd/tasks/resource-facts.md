# resource-facts

## Objective

Add resource facts (CPU, memory, open files and sockets, per service) to the evidence dossier on both tracks, and a new rules configuration, **Config 0R**, whose verdict can name `RESOURCE_EXHAUSTION` from them. Config 0 stays frozen as recorded. Then measure 0R against 0 on RE2-OB dev, and the hybrid built on 0R's rules (HR) against 0R, the stop-rule comparison (vision §7). This is vision M3's "The case dossier with resource facts".

## Problem

- **On RE2-OB dev the rules see no resource fault.** Config 0 scores 4/30. 19 of the 30 candidate lists are empty, almost all on cpu, disk and socket faults, and H is capped at 5/30 (`odd/tasks/hybrid.md` T10). The dossier holds latency, traffic, logs and events, not resources (ADR-020, Consequences).
- **No tool reads resources.** `query_metric`'s allow-list (`mcp-tools/.../MetricCatalog.java`) has no CPU, memory or file metric, although the lab's services already export them: `process_cpu_usage`, `jvm_memory_used_bytes` and `process_files_open_files` are in Prometheus for all four services (checked live on 2026-11-01).
- **The converter maps latency only.** RE2-OB's `simple_metrics.csv` has `_cpu`, `_mem` and `_socket` for all 11 services and `_diskio` for 4 (one dev header read, `checkoutservice_cpu/1`).
- **Config 0 cannot change.** `diagnose()` is frozen at `5797d16` (ADR-016; `odd/tasks/rules-baseline.md:116`: "changing them makes a new config"). ADR-016:58 records that Config 0 never returns `RESOURCE_EXHAUSTION`.

## Why

- The stop rule needs a track-agnostic change that wins a paired comparison on RCAEval dev. The limit on RE2-OB is the rules' recall, not the model.
- **Why a new config, not candidates only** (the human's choice, 2026-11-01). A resource candidate added only to H's list would give H knowledge Config 0 lacks. ADR-019's leakage principle says the rules H uses are exactly those a rules-only config runs. With 0R, HR − 0R stays the model's contribution, and 0R − 0 is the resource rule's.

## Scope

- In:
  - three `query_metric` metrics in the lab catalog: `processor_usage`, `memory_used`, `open_files`;
  - the converter's answers for them from `_cpu`, `_mem`, `_socket`;
  - the gatherer's per-service resource calls, the dossier section and its rendering;
  - Config 0R (one new rule, R-RES) behind a rules-version setting, and HR using 0R's candidates;
  - ADR-021, the specs, `docs/setup.md`;
  - the RE2-OB dev measurements under committed plans.
- Out:
  - disk: no lab exporter, and RE2-OB's `_diskio` exists for 4 services only. Recorded limit;
  - re-recording the lab's replay cases: the old recordings answer the new calls `not recorded in this replay`, so their resource facts are failed facts (see Design);
  - any holdout read; RE2-SS.

## Constraints

- Config 0's verdicts on lab dev, lab holdout and RE2-OB dev stay identical (goldens and replays).
- The thresholds of R-RES are fixed in ADR-021 **before** any converted resource series is read. The dev results are exploratory (ADR-016:5).
- Leakage: no fault word or case name in a recording; resource metric names pass the converter's leakage guard unchanged (`processor_usage`, not `cpu_usage`, whose `cpu` token the guard rejects; ADR-021). T3 checks the guard on the new names.
- Python runs on downloaded data with `python3 -I`. Holdouts untouched.

## Design

- **Metrics** (`query_metric`, same arguments on both tracks: `{metric, service, sinceMinutes: 2N}`, as for `latency_avg`):

  | Metric | Lab (PromQL over the `service` label) | RE2-OB column |
  |---|---|---|
  | `processor_usage` | `process_cpu_usage` | `<svc>_cpu` |
  | `memory_used` | `sum(jvm_memory_used_bytes)` | `<svc>_mem` |
  | `open_files` | `process_files_open_files` | `<svc>_socket` |

  Units differ between the tracks (lab CPU is a 0–1 share, RE2-OB's is the exporter's), so the rule uses only within-series ratios. `open_files` on the lab counts all file descriptors, sockets included; on RE2-OB it is the socket count. The metric description says so.
- **Gatherer:** after the latency loop, per service and per metric, one `query_metric` call; points are split at `incident.from()` into previous and incident windows, as latency is. A new `Dossier` component `resources` (per service, per metric, `Gathered<Series>`), rendered as one table per service with the previous and incident means. A failed call is a failed fact, as everywhere.
- **Lab recordings:** the committed lab cases have no resource calls, so they replay them as `not recorded`. The resource facts are then failed, R-RES cannot fire, and 0R equals 0 on the lab. The replays' `unrecorded` and `coverage` columns rise; the scorer's AC@1 does not read them.
- **R-RES (Config 0R only):** runs after R-REACH and R-DOWN and before R-NONE, so 0R answers exactly where 0 does and can only replace an abstention. A series is resource-abnormal when both windows are usable, its incident mean is at least k = 2 times its previous mean, and above its previous max (ADR-021, Definitions). The service with the largest ratio is the verdict, category `RESOURCE_EXHAUSTION`; ties go to topology order.
- **candidates():** under 0R, resource-abnormal services join the list after the existing entries, ranked by ratio, not repeated when already listed, still capped at 5. Under 0, unchanged.
- **Selection:** a `rules` version (`v1` = Config 0, `v2` = 0R), passed like the topology (`kintsugi.agent.rules`, default `v1`), recorded in `summary.tsv`'s `model` column (`rules-v1` already appears there), and `replay-agent.sh --rules v1|v2`. HR is the `hybrid` report with `--rules v2`.

## Tasks

- [x] T1 ADR-021 (Config 0R, R-RES and its threshold, the metrics, the leakage check, what 0R − 0 and HR − 0R measure), this document. Route: inline (docs).
- [x] T2 Lab metrics, tests first: `MetricCatalog` gains the three metrics, the tool's description lists them; the mcp-tools spec. Route: delegated writer.
- [x] T3 Converter, tests first: the three metrics from `_cpu`, `_mem`, `_socket`, in the fixture and the regenerated agent recording; the leakage guard; evals spec REQ-11. Route: delegated writer.
- [ ] T4 Gatherer and dossier, tests first: the per-service calls, `Dossier.resources`, the renderer and sheets; the RCAEval fixture replays with no unrecorded call; the lab goldens updated where the dossier text changes, and Config 0's verdicts unchanged. Agent spec. Route: delegated writer.
- [ ] T5 Config 0R, tests first: R-RES, `candidates()` under v2, the rules-version setting, `replay-agent.sh --rules`; golden proof that v1 is unchanged. Agent spec, `docs/setup.md`. Route: delegated writer.
- [ ] T6 Checks and the 0R dev run: Config 0 on lab dev and holdout identical; convert RE2-OB repetition 1 again; a plan committed first; 0 and 0R on RE2-OB dev, scored service-only. Route: inline commands.
- [ ] T7 HR on RE2-OB dev under its own plan, HR − 0R (the stop-rule comparison). Then STATE, the journal and the backup. Route: inline commands.

## Acceptance criteria

- AC1: Config 0's verdicts and candidates are identical to before on lab dev, lab holdout and RE2-OB dev (replays).
- AC2: the RCAEval recordings replay with no unrecorded call; the lab recordings replay with only the new resource calls unrecorded (test).
- AC3: R-RES's threshold is in ADR-021, committed before any converted resource series is read (git order).
- AC4: under v1, `diagnose()` and `candidates()` are byte-identical on the golden dossiers; under v2, R-RES fires only where v1 abstains (tests).
- AC5: no recording leaks a case name, fault word or category (converter guard, T6 grep).
- AC6: 0, 0R and HR on RE2-OB dev, service-level AC@1 with Wilson CIs and paired tests, under committed plans.

## Checks

- `cd evals && .venv/bin/python -m pytest -q`; `cd agent && ./mvnw -q test`; `cd mcp-tools && ./mvnw -q test`; `infra/scripts/test-lib.sh`.
- The Config 0 lab replays (dev, holdout) at T4–T5.

## Delivery

- **Forecast:** about 1,300 authored lines (metrics 120, converter 200, gatherer and dossier 400, rules 350, docs and plans 230).
- **Strategy:** `feature-branch-chain`. Slices: `feat/resource-facts` (T1–T2), `-01-converter` (T3), `-02-dossier` (T4), `-03-rules` (T5–T7).
- **Base:** `feat/hybrid-rcaeval` at `e4b0259`.

## Progress

- 2026-11-01: document created after a read-only exploration (vision M3, the gatherer, `MetricCatalog`, the converter, `RulesDiagnosis`) and a live Prometheus check of the three lab metrics. Only the header line of one RE2-OB dev file was read.
- 2026-11-01: T1 done (inline, docs only). ADR-021 fixes k = 2, the max guard and the usable-window rule before any resource series is read. `cpu_usage` became `processor_usage`, because the converter's guard rejects the `cpu` token. Commit `docs(adr): fix Config 0R and the R-RES threshold in ADR-021`; native assessment medium, under budget (pending in the slice).
- 2026-11-01: T2 done (delegated writer). Three `MetricCatalog` entries (`sum by (service)` over `process_cpu_usage`, `jvm_memory_used_bytes`, `process_files_open_files`), the `query_metric` description with units, two tests (RED 2 failures, then GREEN), the mcp-tools spec. mcp-tools 259 tests and agent 663 tests pass. Left for T3: `agent/src/test/resources/mcp-outputs/tools-list.json` still holds the old `query_metric` description (a static fixture no test compares).
- 2026-11-01: T3 done (delegated writer) on `feat/resource-facts-01-converter`. `convert.py` answers `processor_usage`, `memory_used`, `open_files` from `_cpu`, `_mem`, `_socket` (one `SERIES` table, labels `{service}` only, exporter units, no `_diskio`), one call per service per metric with `sinceMinutes` 20. Synthetic fixture columns; six new tests, including the unchanged guard on every converted recording and no `<svc>_<suffix>` column name in any (RED 6 failures, then GREEN); agent recording regenerated; `RcaevalRecordingTest` checks the new answers. evals 138 and agent 664 tests pass. No real RCAEval data read. `tools-list.json` moved to T4: the replay offers it to the models, so changing it may change their prompt. T4 must raise `RcaevalRecordingTest`'s recorded-call count to `3 + 8*11`.

## Next step

T4, gatherer and dossier (delegated writer), on `feat/resource-facts-02-dossier`; it also decides `tools-list.json` after checking whether its description reaches any prompt.
