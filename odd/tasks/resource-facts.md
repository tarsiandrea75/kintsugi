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
- [x] T4 Gatherer and dossier, tests first: the per-service calls, `Dossier.resources`, the renderer and sheets; the RCAEval fixture replays with no unrecorded call; the lab goldens updated where the dossier text changes, and Config 0's verdicts unchanged. Agent spec. Route: delegated writer.
- [x] T5 Config 0R, tests first: R-RES, `candidates()` under v2, the rules-version setting, `replay-agent.sh --rules`; golden proof that v1 is unchanged. Agent spec, `docs/setup.md`. Route: delegated writer.
- [x] T6 Checks and the 0R dev run: Config 0 on lab dev and holdout identical; convert RE2-OB repetition 1 again; a plan committed first; 0 and 0R on RE2-OB dev, scored service-only. Route: inline commands.
- [x] T7 HR on RE2-OB dev under its own plan, HR − 0R (the stop-rule comparison). Then STATE, the journal and the backup. Route: inline commands.

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
- 2026-11-01: native review of `e4b0259..64d1b83` (T1–T3, slice budget reached): one reliability lens, **approved** and acknowledged (lineage `review-3f184c3fbf8f127e`). Advisory only: R3-001 WARNING at `docs/STATE.md:32` (still names `cpu_usage`; fixed when STATE is rewritten at session close), R3-002 SUGGESTION at ADR-021:66. The five untracked root files were added to `.git/info/exclude` (local, the human's choice) so the review could select no untracked files. The next reviewed boundary is `64d1b83`.
- 2026-11-01: T4 done (delegated writer) on `feat/resource-facts-02-dossier`. The gatherer makes one `query_metric` call per service and resource metric after the latency loop; `Dossier.resources` (`ServiceResources`, `MetricSeries`, `ResourceSeries` reusing `LatencyPoints`); a `## Resources` table per service, and one line per service in the sheets; a failed resource fact renders as one line per service. Lab goldens grow by about 950 characters (tables) and 820 (sheets); F3 is about 2,134 tokens, under 2,500. `RulesDiagnosis` does not read `resources`; Config 0's tests pass with unchanged assertions. RCAEval replays `3 + 8*11` calls, none unrecorded; lab replays leave the 12 resource calls unrecorded. RED (compile failures), then GREEN; agent 676 tests pass. Decisions: the stress ceiling rose from 5,500 to 6,500 tokens (the worst case was 33 tokens under the old one; real dossiers are about 2.1k); replay coverage and `dossierCovered` leave the resource calls out (the human's choice), while `unrecorded` and `dossierUnrecorded` still count them. `tools-list.json` not edited: its `query_metric` description reaches the model in explorer TRIAGE (`ReplayLabTools.java:58`, `ReplayDriver.java:88`), so it stays a follow-up. Over the 400-line heuristic (about 560 authored lines, mostly tests).
- 2026-11-01: native review of `64d1b83..fc00008` (T4): one reliability lens, **approved** and acknowledged (lineage `review-1af2deb9f60289b1`). Advisory only: R3-001 WARNING at `DossierRendererTest.java:64` (the raised stress ceiling), SUGGESTIONs R3-002 and R3-004 on the agent spec (lines 953, 1047) and R3-003 at `DossierRenderer.java:90` (`RESOURCE_REASON_CHARS` = 48). The next reviewed boundary is `fc00008`.
- 2026-11-01: T5 done (delegated writer) on `feat/resource-facts-03-rules`. `ResourceRule` (R-RES, ADR-021's thresholds) and `RulesVersion` (V1 = `RulesDiagnosis` untouched; V2 = V1's verdict when not UNKNOWN, else R-RES; candidates appended, no service twice, cap 5); `kintsugi.agent.rules` / `KINTSUGI_AGENT_RULES` (default v1, unknown values fail fast); the hybrid uses the selected version's candidates; `replay-agent.sh --rules v1|v2` (only on `--route rules` or `--report hybrid`); `rules-vN` in the rules route's model column and a `rules` field in hybrid incidents. AC4: existing `RulesDiagnosisTest` assertions unchanged; on the lab goldens V2 equals V1 (resource facts all failed). RED (compile failure) then GREEN for the rules tests; the replay and script tests were written with their code. agent 699 tests, `test-lib.sh` 176 cases pass. R3-002: ADR-021's candidates paragraph clarified in wording only (order, no service twice by service, cap over the whole list, and under 0R the first candidate is the verdict only when Config 0 answers). About 1,050 authored lines, mostly tests and spec.
- 2026-11-01: native review of `fc00008..46fc627` (T5, high risk): four lenses (risk, resilience, readability, reliability), **approved** and acknowledged (lineage `review-fe938c68d52b4763`). Advisory only: WARNINGs R2-001 (`ReplayDriver.java:124`), R2-002 (`replay-agent.sh:87`), R3-001 (`ReplayDriver.java:371`), R3-002 (`replay-agent.sh:337`); SUGGESTIONs R2-003..R2-006, R3-003, R3-004 (`ResourceRule.java`, tests, script). The next reviewed boundary is `46fc627`.
- 2026-11-01: T6 done (inline commands). AC1 on the lab: rules v1 and v2 replays on lab dev and holdout give verdicts identical to `evals/results/2026-10-30-config0-dev/` and `2026-10-30-holdout1-lab/` (15/15 each; `unrecorded` 12, coverage 1.00). Plan `evals/plans/2026-11-01-config0r-re2ob-dev.md` committed (`3c4c765`) before the first conversion with resource series; RE2-OB repetition 1 converted to `evals/out/rcaeval/re2-ob-rep1-resources` (guard: 0 fault words in untrusted text; grep of the 30 recordings for case names and `<svc>_cpu|_mem|_socket|_diskio`: 0 files). AC1 on RE2-OB: Config 0's 30 verdicts identical to `2026-11-01-config0-re2ob-dev`. No FAILED sample, no unrecorded call. Result (`evals/results/2026-11-01-config0r-re2ob-dev/`, service-only, exploratory): 0 4/30, 13% [5%, 30%]; **0R 20/30, 67% [49%, 81%]**; 0R − 0 +53 points, 16 discordant cases all for 0R, exact McNemar p = 3.1e-05, cluster bootstrap [+33, +70]. R-RES gave 16 verdicts, all on the right service. Per fault, 0R: cpu, disk, mem, socket 4/5 each, loss 3/5, delay 1/5. Disk is found although it has no metric of its own (the disk stress moves CPU or memory). 0R abstains on 3 cases (coverage 90%). The plan expected disk, delay and loss not to move; disk (0 → 4/5) and loss (2 → 3/5) did, so that expectation was missed (exploratory, recorded, not acted on).
- 2026-11-02: T7's HR run done (inline commands). Plan `evals/plans/2026-11-01-hybrid-rules-v2-re2ob-dev.md` committed (`a8b5b16`) before the run; it pre-registered that HR cannot win HR − 0R (ceiling 24/30, best p = 0.125). Run `replay-20261101T160425Z-local` (about 8 h of CPU, report median 958 s, max 1,803 s, no timeout). Mechanics pass: 30 samples, the 3 empty lists `UNKNOWN|UNKNOWN` in under 1 s with no model call, no FAILED sample, no unrecorded call, every hybrid incident `rules: v2`, recordings identical to T6's. Two `INVALID_REPORT` (loss), recorded as HR's answer, not rerun. Result (`evals/results/2026-11-01-hybrid-rules-v2-re2ob-dev/`, service-only, descriptive): 0 4/30; 0R 20/30, 67% [49%, 81%]; **HR 23/30, 77% [59%, 88%]**, coverage 83%, selective 92%. HR − 0R: 3 discordant cases for HR, 0 for 0R, exact McNemar p = 0.25, bootstrap [+0, +23]: not a win, as pre-registered. HR − 0 +63 points, p = 3.8e-06. HR kept all 20 of 0R's right answers. On the 6 lists with more than one entry: rank 3 picked and right twice (disk/currencyservice, mem/productcatalogservice), rank 2 right once (socket/productcatalogservice), rank 1 right once (loss/currencyservice), and `INVALID_REPORT` twice (loss/recommendationservice, where the truth was second, and loss/productcatalogservice, where it was absent). Second report attempts are not recorded directly; a proxy (prompt tokens about twice the rest, 14–16k against 7–8k) suggests 7 of 27, both `INVALID_REPORT` among them, an inference, not a measurement. Category accuracy under ADR-020's map (reported, not used), T6 and T7 together: 0 3/30, 0R 18/30, HR 21/30. **Stop rule: the human's reading.** 0R − 0 is the paired win on RE2-OB dev (p = 3.1e-05, rules only); HR − 0R is not. The HR run and its results are done; T7 stays open until STATE, the journal and the backup are done (review R3-001).
- 2026-11-02: native review of `46fc627..c9b81e4` (T6–T7 records, medium, slice budget reached): one reliability lens, **approved** and acknowledged (lineage `review-3a8c1028443f1800`). Its three advisory findings are applied above: T7 unticked until the close (R3-001), T6's missed expectation and the category accuracy (R3-002), the second-attempt proxy (R3-003). The next reviewed boundary is `c9b81e4`.
- 2026-11-02: T7 closed: `docs/STATE.md` rewritten (the stale `cpu_usage` gone, review R3-001 of T1–T3), journal `docs/journal/2026-11-02-01.md`, backup bundle. The feature is complete.

## Follow-ups

- `summary.tsv`'s `model` column cannot tell HR from H (it holds the LLM's model); runs are kept apart by output directory and label, and the hybrid incident JSON carries `rules`. A scorer column would be an `evals/` change.
- `agent/src/test/resources/mcp-outputs/tools-list.json` predates the resource metrics; explorer replays offer the old `query_metric` description.

## Next step

None in this feature. The human reads the stop rule (0R − 0 won on RE2-OB dev; HR − 0R did not) and chooses the next feature.
