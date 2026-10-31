# rcaeval-converter

## Objective

Turn RCAEval RE2 cases into replay recordings that the unchanged agent reads, with one topology file per system. Then run Config 0 on RCAEval dev (RE2-OB repetition 1, 30 cases) as a mechanics check. This is vision M2's "The RCAEval converter started" (follow-up 6, `rcaeval-etl`) and the prerequisite of ADR-019's stop-rule measurement, H − 0 on RCAEval dev.

## Problem

- **The agent only reads lab data.** It reads either the live lab through MCP tools or replay recordings of those tool calls. RCAEval ships raw files: a wide 1 s metrics CSV, raw logs, Jaeger traces (Online Boutique only), and `inject_time.txt`. The truth is the case directory's name.
- **There is no call graph for these systems.** Neither the dataset nor the RCAEval repository ships a topology, and Sock Shop has no traces.
- **The replay path is still lab-bound** in three places:
  - `ReplayDriver` uses the test `LabTopology.LAB` (`:297`, `:378`), not `kintsugi.agent.topology`;
  - `replay-agent.sh` accepts only `F1 F2 F3` (`:103`);
  - the scorer compares the full `service|category`, but RCAEval's AC@1 is service-only (vision §5).

## Why

Without the converter, every number is on a lab one person built and tuned against. RCAEval gives incidents on systems written by others, the published baselines, and the place where ADR-014's stop rule and ADR-019's H − 0 are decided.

## Scope

- In:
  - ADR-020: the dataset revision, the ADR-001 boundary amendment, the category map, the windows and the symptom.
  - Topology files for RE2-OB and RE2-SS.
  - The converter under `evals/` (metrics, logs and, for OB, traces).
  - The replay plumbing (topology from the property, RCAEval case ids, a recordings directory outside the repository).
  - Service-only AC@1 in the scorer.
  - The specs.
  - A Config 0 smoke on RCAEval dev.
- Out:
  - **Resource facts** (cpu, mem, diskio, socket) in the dossier. The dossier has no field for them; that is vision M3's "case dossier with resource facts". Without them, cpu, mem and disk faults are visible only through latency.
  - **Healthy pseudo-cases**: a follow-up in this feature's line, after the smoke.
  - **BARO and any model route on RCAEval**: follow-up 9 and the H − 0 plan.
  - **RE2-TT and RE3.**
  - **Any read of RE2-OB repetitions 2–3 or of RE2-SS**: they are holdouts A and B. The converter is tested on synthetic fixtures and run only on dev.

## Constraints

- **Leakage.**
  - The case directory name, the fault type and the root-cause service never reach the agent.
  - The recordings carry no case name. The symptom is one fixed sentence per system.
  - A test greps every converted recording for the fault words.
- **Holdouts are read once.**
  - The converter's tests use synthetic fixtures.
  - Only RE2-OB repetition 1 is converted in this feature.
  - The topology files are built from upstream manifests and from repetition 1's traces only.
  - Amended at T2 (review R3-holdout-read-contradicts-constraints): one structural read outside dev was made, the header line of `RE2-SS/carts_cpu/1/simple_metrics.csv`, to confirm Sock Shop's metric prefixes. It holds column names only, no values and no outcome, and it is disclosed here and in the T2 entry.
- **Data stays outside the repository.**
  - The raw data is in `~/data/rcaeval` (property or env `KINTSUGI_RCAEVAL_DIR`), verified against the Zenodo md5s.
  - Converted recordings go to a git-ignored directory and are reproducible from the pinned data and the converter.
- **Config 0 stays frozen.** The rules are unchanged; their weakness on RCAEval is the point of H1.

## Design

- **Dataset (ADR-020):**
  - Zenodo record 14590730: `RE2-OB.zip` md5 `b9e23f8842c404b396ffd2becff15de4`, `RE2-SS.zip` md5 `bd747a8fc7c5be00c613e13fbf9dd74b`.
  - The RCAEval code reference is tag `1.11.0`, for the evaluator's semantics.
  - Licenses: the README says MIT, the Zenodo record CC-BY-4.0; both are permissive and are cited.
  - Layout: `RE2-<SYS>/<service>_<fault>/<rep>/`. The stray `checkoutservice_cpu/multi-source-data/` folder is skipped.
- **The seam: replay recordings.** Each case becomes one incident JSON in today's replay format:
  - `startedAt` = inject_time + 10 min and `sinceMinutes` = 10, so the gatherer's previous window is the 10 minutes before the injection and the incident window the 10 after (vision §2);
  - a fixed symptom;
  - one `DOSSIER` step whose array holds exactly the calls `DossierGatherer` makes, each `{tool, args, outcome: "OK", output}`.

  A `recordings.tsv` row carries the truth, which never enters the JSON. `ReplayLabTools` and `ReplayCases` already accept this; the gatherer, the rules and the loop run unchanged.
- **Tool outputs from case files:**
  - `query_metric latency_avg` per service comes from `<svc>_latency-50`, converted to seconds, with uri `/`. It is resampled to the lab's step.
  - `count_logs` and `search_logs` (WARN and ERROR) come from `logs.csv`, with the level parsed from the message.
  - `traffic_counts` comes, for OB, from the spans in `traces.csv`: client and server rows per caller→callee pair (`CALL /`, the edge convention), with the gRPC status mapped to an HTTP status (T4). SS has no traces, so its rows are empty.
  - `service_restarts` is empty items.
  - No status tables and no event sources: the topology files have no owners.
- **Topology files** (`agent/src/main/resources/topology/re2-ob.json`, `re2-ss.json`):
  - services as RCAEval names them (metric prefixes, lowercase, `-db` folded per the evaluator);
  - OB edges from the upstream `microservices-demo` manifests at a pinned commit, cross-checked against the traces of repetition 1;
  - SS edges from the upstream Sock Shop manifests at a pinned commit;
  - the entry edge to the frontend.
- **Category map (pre-registered in ADR-020):**

  | RCAEval fault | Kintsugi category |
  |---|---|
  | `cpu`, `mem`, `disk`, `socket` | `RESOURCE_EXHAUSTION` |
  | `delay`, `loss` | `DEPENDENCY_LATENCY` |

  Packet loss shows up as TCP retransmissions, which are latency, not refusals. AC@1 on RCAEval is service-only; the category map feeds only category accuracy.
- **Case ids:** the `fault` column is the RCAEval fault type (`cpu` … `socket`), so the per-fault tables match RCAEval's. The recording file name is a blinded hash.
- **Scorer:** a `--service-only` mode compares the service part of `truth` and `reported`. AC@3 and Avg@5 wait for the ranked report (M3).

## Tasks

- [x] T1 ADR-020, the vision fix ("ADR-017 will pin" → ADR-020) and this document. Route: inline (docs). The human confirmed the design and the category map on 2026-10-31. Passive docs, structural readback only.
- [x] T2 The topology files for RE2-OB and RE2-SS, from the pinned upstream manifests, with the OB cross-check on repetition 1's traces recorded. `TopologyTest` loads both. Route: delegated writer.
- [x] T3 The converter core, tests first on a synthetic fixture: case discovery, windows, metrics → `query_metric`, logs → `count_logs`/`search_logs`, empty restarts, the incident JSON and `recordings.tsv`, the leakage grep. Route: delegated writer.
- [x] T4 Traces → `traffic_counts` (OB), tests first. Route: delegated writer.
- [ ] T5 Replay plumbing and scorer, tests first:
  - `ReplayDriver` reads the topology property;
  - `replay-agent.sh` gets `--dataset re2-ob`, a recordings directory and the topology;
  - the scorer gets `--service-only`;
  - the agent and evals specs, and `docs/setup.md`.

  Route: delegated writer.
- [ ] T6 Convert RE2-OB repetition 1 and run Config 0 on it as a mechanics check, under a plan committed first. Then STATE, the journal and the backup. Route: inline commands.

## Acceptance criteria

- AC1: the converter's recordings replay through the unchanged gatherer. Every gatherer call is recorded, so no `not recorded in this replay` appears (test).
- AC2: no converted recording contains the case directory name, a fault word, or the truth (test, and the T6 grep over the 30 dev recordings).
- AC3: both topology files load, and the OB edges seen in repetition 1's traces are a subset of the file's edges (recorded check).
- AC4: Config 0 runs on all 30 dev cases with no FAILED sample. The scorer reports service-level AC@1 with a Wilson CI.
- AC5: the lab replay and the lab tests are unchanged (Config 0's lab verdicts and candidates identical).

## Checks

- `cd evals && python3 -m pytest -q`.
- `cd agent && ./mvnw -q test`.
- `infra/scripts/test-lib.sh`.
- The Config 0 lab replay at T5. The RCAEval dev smoke at T6.

## Delivery

- **Forecast:** about 1,100 authored lines (converter and tests about 650, replay and scorer about 250, topology and docs about 200).
- **Strategy:** `feature-branch-chain`. Slices: `feat/rcaeval-converter` (T1–T2), `-01-core` (T3–T4), `-02-replay` (T5–T6).
- **Base:** `feat/topology-data-02-derived` at `24af8d5`.

## Progress

- 2026-10-31: research (one worker, primary sources: the RCAEval repository at `dc9f487`/tag `1.11.0`, the Zenodo, Figshare and Hugging Face APIs, arXiv 2412.17015, issues #16, #67 and #70) and exploration of the replay seam (one read-only explorer). The human authorized the dataset download. `RE2-OB.zip` and `RE2-SS.zip` are downloading into `~/data/rcaeval` with an md5 check. The branch `feat/rcaeval-converter` was created from `24af8d5`.
- 2026-10-31, T2 (route: delegated writer; trigger: two non-trivial data files, a script and two test files, with upstream research). `re2-ob.json` and `re2-ss.json` written, `TopologyTest` loads both.
  - **Upstream pins** (shallow clones of the default branches, outside the repository):
    - Online Boutique: `GoogleCloudPlatform/microservices-demo` `main` at `c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712`.
    - Sock Shop deploy: `microservices-demo/microservices-demo` `master` at `9dff06fae4981921caec6a62393a6ebfce4b3e3f`.
    - Sock Shop sources, `master`: `front-end` `52dee656f4423be041619bbb090205f912e00113`, `orders` `546a10c92d1ff9998c21f3849f5b95a0f14430a0`, `shipping` `9c0fbfa31895dd029d44d3434de93f64cd71f87b`, `queue-master` `7dc33721c765e5af415c9d244d84637ff2d510cd`, `carts` `f4e80051668c6a83359c41b03a27baefa590adc8`.
    - RCAEval `1.11.0` (`dc9f487`) for the name normalization: `RCAEval/benchmark/scoring.py` `to_service` = `name.split("_")[0].replace("-db", "")`.
  - **Services** are the `simple_metrics.csv` prefixes, lowercased and normalized by `to_service`, minus three prefixes that name no application component:
    - `frontend-external` (OB): the frontend's LoadBalancer Service, metrics `error` and `workload` only; RCAEval's own `drop_extra` (`RCAEval/io/time_series.py`) drops it.
    - `istio-init` (OB): Istio's init container, in 1 of the 30 repetition-1 headers.
    - `rabbitmq-exporter` (SS): the Prometheus exporter sidecar in the `rabbitmq` pod (`complete-demo.yaml:605`).
    - `redis` (OB, the `redis-cart` pod's container) and `session` (SS, `session-db` folded by `to_service`) carry metrics, so they are services, the targets of their callers' edges. `carts-db`, `catalogue-db`, `orders-db` and `user-db` fold into their services.
  - **Display order:** the entry service first, then the others alphabetically. **Datastores:** `[]`, because every datastore that survives normalization (`redis`, `session`, `rabbitmq`) carries metrics and has a caller, and an edge cannot target a datastore.
  - **Edge convention:** one edge per caller → target pair, every edge `"method": "CALL"`, `"uri": "/"`; the entry edge has `"caller": null`. T4 aggregates the traces per caller → callee pair to match it. Calls to a folded datastore (`carts` → `carts-db`) become self-edges and are dropped. `tableOwners` and `sourceOwners` are `{}`.
  - **OB edges** (15 plus the entry), from the `*_ADDR` env vars of `kubernetes-manifests/`:
    - `frontend` → `productcatalogservice`, `currencyservice`, `cartservice`, `recommendationservice`, `shippingservice`, `checkoutservice`, `adservice` (`frontend.yaml:69-81`). `SHOPPING_ASSISTANT_SERVICE_ADDR` (`:83`) is omitted: no `shoppingassistantservice` prefix in the dataset, a later addition.
    - `checkoutservice` → `productcatalogservice`, `shippingservice`, `paymentservice`, `emailservice`, `currencyservice`, `cartservice` (`checkoutservice.yaml:57-67`).
    - `recommendationservice` → `productcatalogservice` (`recommendationservice.yaml:60`).
    - `cartservice` → `redis` (`cartservice.yaml:50`, `REDIS_ADDR=redis-cart:6379`; the `redis-cart` Deployment at `:90-92`).
  - **SS edges** (11 plus the entry). The manifest states no service calls, so the sources at the pins are the evidence:
    - `front-end` → `catalogue`, `carts`, `orders`, `user` (`front-end/api/endpoints.js:18-26`); → `session` (`front-end/config.js:16`, `RedisStore({host: "session-db"})`).
    - `orders` → `payment` (`OrdersConfigurationProperties.java:11-12`, `/paymentAuth`), → `shipping` (`:15-16`, `/shipping`); → `user` and `carts` through the customer, address, card and items links the front-end puts in the order (`front-end/api/orders/index.js:60-74`) and `OrdersController.java:61-71` fetches.
    - `shipping` → `rabbitmq` and `queue-master` → `rabbitmq` (`spring.rabbitmq.host=rabbitmq` in each `application.properties:2`). Both are client connections to the broker; `queue-master` consumes, so it has no caller.
    - Folded and dropped: `carts` → `carts-db`, `orders` → `orders-db` (`application.properties:2`), `user` → `user-db` (`complete-demo.yaml:789-790`), `catalogue` → `catalogue-db` (not checked in the catalogue source; it folds into a self-edge either way). No edge is unverified.
  - **Holdout reads:** RE2-OB repetition 1 only (the 30 `simple_metrics.csv` headers and the 30 `traces.csv`, service names and parent links only). Outside repetition 1: directory listings, and the first line of exactly one file, `RE2-SS/carts_cpu/1/simple_metrics.csv`. No other RE2-SS or RE2-OB repetition 2–3 file was opened.
  - **AC3, the OB cross-check:** `python3 -I evals/kintsugi_evals/rcaeval/trace_edges.py ~/data/rcaeval/RE2-OB/*/1` (1 min 7 s, 67 MB peak) prints 9 pairs from 30 directories: `checkoutservice` → `currencyservice`, `emailservice`, `paymentservice`, `productcatalogservice`; `frontendservice` → `checkoutservice`, `currencyservice`, `productcatalogservice`, `recommendationservice`; `recommendationservice` → `productcatalogservice`.
    - The traces name the frontend `frontendservice`, the metrics `frontend`. With that alias, all 9 pairs are file edges: **AC3 holds**.
    - 6 file edges are absent from the traces, because only seven services emit spans (`checkoutservice`, `currencyservice`, `emailservice`, `frontendservice`, `paymentservice`, `productcatalogservice`, `recommendationservice`): `cartservice`, `shippingservice`, `adservice` and `redis` do not, so their incoming edges are unseen.
    - T4 must map `frontendservice` to `frontend` when it builds `traffic_counts`.
  - Checks: `evals/.venv/bin/python -m pytest -q tests/test_trace_edges.py` RED (collection error, module missing), then GREEN 4 passed; the full evals suite 76 passed; `./mvnw -q test -Dtest=TopologyTest` exit 0, 37 tests, 0 failures.

  - Commit `ebf3044`. Review of `24af8d5..ebf3044` (T1–T2): medium, `slice_budget_reached`; one lens (reliability), approved and acknowledged. Non-blocking findings:
    - WARNING R3-ac3-check-not-reproducible-from-tool (`trace_edges.py:19-23`): the tool does not apply the `frontendservice` → `frontend` alias, so AC3 needs a manual step. Taken into T4: one alias table shared by `trace_edges` and the traffic builder.
    - WARNING R3-holdout-read-contradicts-constraints: answered by the amended Constraints above.
    - SUGGESTION R3-silent-empty-extraction-passes (`trace_edges.py:52-58`): an empty extraction exits successfully. Taken into T4: a non-zero exit when no pair is found.
    - SUGGESTION R3-ss-services-proved-from-one-header (`TopologyTest.java:289-297`): RE2-OB headers vary across cases (15 distinct sets in repetition 1), so one SS header may miss services. Follow-up for the holdout-B plan: before that read, a header-only check over every RE2-SS case confirms the services, and the converter reports any metric prefix missing from the topology.
- 2026-10-31, T3 (route: delegated writer; trigger: two new modules, the CLI, a test module with a fixture tree, a Java test and the spec). The converter core is written; traffic stays empty for T4.
  - **Files:**
    - `evals/kintsugi_evals/rcaeval/convert.py` (the case → recording conversion);
    - `evals/kintsugi_evals/rcaeval/leakage.py` (the guard);
    - `evals/kintsugi_evals/cli.py` (`convert-rcaeval`);
    - `evals/tests/test_rcaeval_convert.py` and `evals/tests/fixtures/rcaeval/RE2-OB/` (synthetic: `checkoutservice_cpu/1`, `currencyservice_delay/1`, a holdout `checkoutservice_cpu/2` with only `inject_time.txt`, and a stray `multi-source-data/`);
    - `agent/src/test/resources/rcaeval/` (the converter's output for the fixture's `checkoutservice_cpu`) and `RcaevalRecordingTest`;
    - evals spec REQ-11, `docs/setup.md` "Convert RCAEval cases", `.gitignore` `evals/out/`.
  - **Calls:** exactly `DossierGatherer`'s for a topology without owners (58 for RE2-OB):
    - `service_restarts {sinceMinutes: 10}`;
    - `traffic_counts {20, untilMinutesAgo 10}` and `{10}`;
    - per service, `query_metric {metric: latency_avg, service, sinceMinutes: 20}`;
    - per service and level, `count_logs {service, level, sinceMinutes: 10}` and `search_logs` with `limit: 20`.

    They come in topology-file order, not `gatherOrder()`: `ReplayLabTools` matches by tool and sorted arguments, so order does not count.
  - **Latency unit: seconds, factor 1.** `latency-50` is Istio's sidecar latency (`metrics_postprocess.log` names the source `<svc>_istio-latency-50`). Evidence from repetition 1 only, comparing the 10 minutes before and after the injection:
    - In `productcatalogservice_delay`, `recommendationservice`'s own spans (`traces.csv`, µs) rise from 3.5 to 203.7 ms, and its `latency-50` from 0.0075 to 0.175. `frontendservice`'s spans rise from 3.5 to 202.6 ms, its `latency-50` from 0.055 to 1.45.
    - In `currencyservice_delay`, `currencyservice`'s `latency-50` rises from 0.0041 to 0.1004 (+0.096, a ~100 ms injected network delay), while its spans stay at 0.14 ms.
    - Read as milliseconds, the sidecar latency would be shorter than the spans it wraps (0.175 ms against 203 ms), so the unit is seconds.
    - The values are p50, not a mean. The call keeps the lab's name, `latency_avg`, because the gatherer asks for it.
  - **Resampling:**
    - The step is mcp-tools' `MetricCatalog.stepSeconds(20)` = 21 s: 58 points from `startedAt` − 1200 s to `startedAt` − 3 s.
    - Each point is the mean of the 1 s rows in (t − 21 s, t], as a Prometheus point looks back; a bucket with no finite value is `"NaN"`.
    - The first incident point (inject + 9 s) mixes 12 s of the previous window, less than the lab's 60 s rate window does.
    - A service with no `<svc>_latency-50` column (`redis` in RE2-OB) gets `returned: 0`.
    - The data covers inject ± 720 s at 1 s, so both windows are full.
  - **Log level rule.** `logs.csv` has a `level` column (`time,timestamp,container_name,message,level,req_path,error,cluster_id,log_template`), set by most services and blank for `cartservice`, `redis`, tracebacks and some export errors (tally over the 30 repetition-1 `logs.csv`, levels and containers only). The rule:
    - the column decides when set: `warn`/`warning` → WARN; `error`/`err`/`fatal`/`critical`/`crit`/`severe`/`panic` → ERROR; anything else (info, debug) → neither;
    - a blank column is read from the message: a whole word error, exception, traceback, fatal or panic → ERROR, else warn or warning → WARN.

    The window is [inject, inject + 10 min) on the nanosecond `timestamp`. `search_logs` gives the newest 20 lines, each cut at 1000 characters plus `[truncated]`, with `truncated` true when a returned line was cut, as mcp-tools does. `timestamp` is printed as Java's `Instant.toString()`. Containers map to services with `trace_edges.normalize`; others are dropped and reported.
  - **Symptoms** (one per system, in `convert.SYMPTOMS`): RE2-OB "Users of the online shop see degraded responses from frontend."; RE2-SS "Users of the online shop see degraded responses from front-end."
  - **Manifest:**
    - `fault` is the RCAEval fault, `mode` `decision`, `route` `none` (no model route recorded it), and `truth` `<service>|<category>` by ADR-020's map.
    - `file` is `<16 hex of sha256("RE2-<SYS>/<case>/<rep>")>.json`, relative to the output directory, and `incident` a version-4 UUID from the same digest.
    - `split` is `dev` for RE2-OB repetition 1. Otherwise it is `holdout`, and only when `--allow-holdout A` (RE2-OB repetitions 2-3) or `B` (RE2-SS) names it; without that, the converter refuses before reading a case file or creating `--out`.
  - **Leakage rule** (`leakage.py`):
    - Strict, failing the conversion and removing its recordings: a fault word (cpu, mem, disk, delay, loss, socket; whole word, case-insensitive, `_` breaking words, so `x_mem_y` hits and `overcommit_memory` does not) anywhere outside `untrustedText`, with nested JSON strings parsed; and a case directory name or a category anywhere, `untrustedText` included.
    - Fault words inside `untrustedText` are counted in `converter-report.tsv`, never masked and never fatal. That text is the system's own log line, which the agent reads as data; rewriting it would falsify the evidence, and the leak guarded against is the converter's own (a name, a label, a sentence it writes).
    - The report also lists metric prefixes and log containers outside the topology (T2's suggestion), the three known extras excepted.
  - **Determinism:** the same input gives the same bytes (test). The JSON keys are in a fixed order, compact inside, indented outside.
  - **RED/GREEN:**
    - RED: `evals/.venv/bin/python -m pytest -q tests/test_rcaeval_convert.py`, a collection error (`cannot import name 'convert'`).
    - GREEN: 32 passed. The full evals suite: 108 passed (76 + 32).
    - `./mvnw -q test -Dtest=RcaevalRecordingTest,TopologyTest`: exit 0, `RcaevalRecordingTest` 1/1 (58 calls, none unrecorded, no conflict, `RulesDiagnosis.diagnose` returns), `TopologyTest` 37/37.
  - **AC1 check:** `test_the_agents_copy_is_the_converters_output` regenerates the committed recording and `recordings.tsv` from the fixture and compares bytes. To regenerate, run from `evals/`: `.venv/bin/python -m kintsugi_evals convert-rcaeval --root tests/fixtures/rcaeval --system re2-ob --rep 1 --case checkoutservice_cpu --out ../agent/src/test/resources/rcaeval`, then delete the `converter-report.tsv` it writes there.
  - **Real-data sanity** (repetition 1, `--case checkoutservice_cpu --case productcatalogservice_delay`, under `python3 -I`, output in the session scratchpad): 2 recordings of about 40 KB, 58 calls each, 10 latency series of 58 points (no `NaN`, none for `redis`), no WARN or ERROR line in either incident window, no untrusted fault word, no unknown prefix or container; 0.8 s.
  - **Data reads (dev only):**
    - the header and values of `checkoutservice_cpu/1` (`simple_metrics.csv`, `logs.csv`, `metrics.csv`, `metrics_postprocess.log`);
    - the `latency-50` values and `traces.csv` durations of five `*_cpu/1` cases and of `currencyservice_delay/1` and `productcatalogservice_delay/1`;
    - the `level` and `container_name` tally over the 30 repetition-1 `logs.csv`, with one example message per group and an error-word check on blank-level messages.

    No repetition 2-3 or RE2-SS file was opened.

  - The parent re-ran the full evals suite (108 passed) and `RcaevalRecordingTest` (green). Accepted notes: the blinded file name is an unsalted hash, so it can be reversed by enumerating the 90 case names; the agent never reads file names, so this is recorded, not fixed. The regeneration command leaves a `converter-report.tsv` in the fixture directory, which must be deleted by hand (a follow-up for T5's docs).

  - Commit `4b96b26`. Review of `527aeff..4b96b26`: medium, `slice_budget_reached`; one lens (reliability), approved and acknowledged. Non-blocking findings:
    - WARNING R3-failed-run-leaves-unscanned-output (`convert.py:304-317`): a run that fails part way leaves recordings that the leakage guard never scanned. Taken into T4: write to a temporary directory and move it into place only after the scan passes.
    - WARNING R3-leak-removal-unasserted (`test_rcaeval_convert.py:360-363`): the test does not assert that the recordings are removed on a leak. Taken into T4.
    - SUGGESTION R3-log-decoding-depends-on-locale (`convert.py:216`): files are opened without an explicit encoding. Taken into T4: `encoding="utf-8"`.
    - WARNING R3-malformed-input-traceback (`cli.py:229-230`): malformed input ends in a traceback, not a message. Follow-up.
    - SUGGESTION R3-holdout-and-ss-paths-unexercised (`convert.py:309`): the holdout and Sock Shop paths have no test. Follow-up, before the holdout plans.
    - SUGGESTION R3-replay-verdict-assertion-is-weak (`RcaevalRecordingTest.java:54-59`): the test only checks that `diagnose` returns. Follow-up; T6 runs the rules for real.

- 2026-10-31, T4 (route: delegated writer; trigger: a new module, two changed modules, two test modules, fixtures, a Java test and two docs). Traffic from the traces, and the T2 and T3 review items.
  - **Files:**
    - `evals/kintsugi_evals/rcaeval/traffic.py` (new: traces → `traffic_counts`);
    - `trace_edges.py` (the alias table, the client-operation pattern, `--topology`, the non-zero exits), `convert.py` (traffic, staging, encodings);
    - `evals/tests/test_rcaeval_convert.py`, `evals/tests/test_trace_edges.py`, the fixture's two `traces.csv`;
    - the regenerated `agent/src/test/resources/rcaeval/4490c7f4fb1d2bf7.json` and `RcaevalRecordingTest`;
    - evals spec REQ-11 (the traffic bullet, the staging sentence, two scenarios), `docs/setup.md` (the AC3 command).
  - **`traces.csv` shape** (RE2-OB repetition 1, headers and the columns named here only): `time,traceID,spanID,serviceName,methodName,operationName,startTimeMillis,startTime,duration,statusCode,parentSpanID`, `startTime` in µs. Spans cover inject − 720 s to inject + 720 s in every case.
  - **`statusCode` values** over the 30 repetition-1 files: `0.0` (10,721,638 spans), blank (535,270, all the `frontendservice` root HTTP spans), `14.0` (6,032), `13.0` (330), `4.0` (17), `2.0` (4). They are gRPC status codes.
  - **Status mapping** (`traffic.status_of`): the gRPC code's HTTP status from `google.rpc.Code` (0 → 200, 1 → 499, 2 → 500, 4 → 504, 13 → 500, 14 → 503, and the rest of the table), the outcome Spring's (2xx `SUCCESS`, 4xx `CLIENT_ERROR`, 5xx `SERVER_ERROR`). Blank is OpenTelemetry's unset status, read as OK. Anything else in a window fails the conversion.
    - `exception` is always `none`: the traces carry no exception class. So 14 (UNAVAILABLE) is an error answer (`status >= 400`, the rules' "refusing" test), never an R-REACH connection failure, and 4 (DEADLINE_EXCEEDED) is a 504, not an R-DOWN timeout.
  - **Rows** (`method` `CALL`, `uri` `/`, the edge convention):
    - Client row, one per client span: a span whose `operationName` matches `^hipstershop\.<Name>Service/<Method>$` (`trace_edges.CLIENT_OPERATION`, `callee_of`: `ProductCatalogService` → `productcatalogservice`) naming a service other than its own. `service` the span's service, `clientName` the named one, the span's own status, `exception` `none`. Server spans (`grpc.hipstershop...`, or named after their own service), HTTP roots, exporters and health checks do not match.
    - Server row, one per server span: a span whose parent span is in another service. `service` the span's service, its own status, no `clientName` and no `exception` (the lab's `TrafficCounts.Row` is `@JsonInclude(NON_NULL)` with both null on the server side; the brief's "clientName on server rows" did not match the lab, which the converter follows). An unknown caller does not drop a server row: the callee answered either way.
    - No double counting: over the 30 repetition-1 files, 5,429,757 spans are client spans, 4,829,829 are server spans, and 0 are both.
    - Rows are summed by (side, service, clientName, status, outcome), sorted in `TrafficCounts`' order, counts above zero only. An answer over the lab's 8 KB cap fails the conversion rather than being cut (real answers are under 3 KB).
  - **Window counting:** a row counts in the window holding its own span's `startTime`: previous [inject − 600 s, inject), incident [inject, inject + 600 s). The span map holds every span of one file (an interned service per span id), so memory is the span-id map of one file.
  - **Alias table and operation pattern** (`trace_edges.ALIASES` and `CLIENT_OPERATION`, used by `trace_edges.pairs_in` and `traffic.counts` through `service_of` and `callee_of`): `frontendservice` → `frontend`. The other six span-emitting services, and every service an operation names, already carry their metric-prefix names. A row naming a service outside the topology after mapping is dropped and reported as `unknown_trace_service` (none in the sanity run).
  - **Coverage, before and after (parent decision).** The first build took client rows from parent links only. Measured on repetition 1:
    - Client spans to `cartservice`, `adservice` and `shippingservice` (about 952,000) had no server child, since those services emit no spans: 5 edges got no rows.
    - Of 6,198 non-OK client spans, only 166 had a server child: calls that fail before the callee (mostly UNAVAILABLE) leave none, so about 97 % of the error signal was missing.
    - The parent decided to take client rows from the client spans, as an implementation correction inside T4: the approved design says "client and server rows per caller→callee", and the parent-link-only rule was the brief's, not a human product decision. Now every client span is a client row, so all 6,198 non-OK client spans count when they fall in a window.
    - Lost by the new rule: `recommendationservice`'s spans carry no `operationName`, so its calls to `productcatalogservice` give server rows (on `productcatalogservice`) but no client row. The parent-link pairs still show that edge in `trace_edges`.
  - **AC3, reproducible:** `python3 -I evals/kintsugi_evals/rcaeval/trace_edges.py --topology agent/src/main/resources/topology/re2-ob.json ~/data/rcaeval/RE2-OB/*/1`. With client spans and parent links, 78 s, 68 MB peak, exit 0:

    ```
    checkoutservice -> cartservice  edge
    checkoutservice -> currencyservice  edge
    checkoutservice -> emailservice  edge
    checkoutservice -> paymentservice  edge
    checkoutservice -> productcatalogservice  edge
    checkoutservice -> shippingservice  edge
    frontend -> adservice  edge
    frontend -> cartservice  edge
    frontend -> checkoutservice  edge
    frontend -> currencyservice  edge
    frontend -> productcatalogservice  edge
    frontend -> recommendationservice  edge
    frontend -> shippingservice  edge
    recommendationservice -> productcatalogservice  edge
    14 pairs from 30 case directories; all are edges of re2-ob.json
    ```

    All 14 pairs are edges (T2's parent-link-only run found 9). The one file edge not seen is `cartservice` → `redis`: neither emits spans. `trace_edges` exits 1 when it finds no pair, or when a pair is not an edge of `--topology`.
  - **Atomic output:** `convert` writes everything to `mkdtemp` beside `--out` (`.<name>.*.partial`), scans it, then moves each file into `--out` (created if missing, other files in it kept); the staging directory is removed in a `finally`. A leak or any failure leaves no recording, manifest or report, and no staging directory.
  - **Encodings:** every input opens with `encoding="utf-8"`. `logs.csv` uses `errors="replace"` (the system's free text: a bad byte becomes U+FFFD in `untrustedText` instead of failing the case); metrics, traces, the inject time and the topology decode strictly, so a bad byte there fails the run.
  - **RED/GREEN:**
    - First build: `tests/test_trace_edges.py` 4 failed, 4 passed, then 8 passed; `tests/test_rcaeval_convert.py` a collection error (`cannot import name 'traffic'`), then green; the full suite 124 passed.
    - Client-span rule: RED 5 failed, 49 passed (the incident rows, the shared-function check, `callee_of`, the unknown-name report, the `trace_edges` client-span pair); GREEN after the code and the fixture regeneration: the full evals suite 126 passed (108 + 18).
    - `RcaevalRecordingTest` with the new assertion against the T3 recording: RED (`Expecting actual not to be empty`); with the regenerated recording: GREEN.
    - `./mvnw -q test -Dtest=RcaevalRecordingTest,TopologyTest`: exit 0, 1/1 and 37/37.
  - **AC1 fixture:** `checkoutservice_cpu/1/traces.csv` (22 spans: both windows, both boundaries, a client span in the previous window whose callee span falls in the incident one, statuses 13, 14 and 4, a client span to `cartservice` with no child, a client span to an unknown `shoppingassistantservice`, an out-of-topology tracer, an orphan) and a 3-span `currencyservice_delay/1/traces.csv`. The agent copy was regenerated with the T3 command and its `converter-report.tsv` deleted; only the recording changed, `recordings.tsv` did not.
  - **Real-data sanity** (repetition 1, `--case checkoutservice_cpu --case productcatalogservice_delay`, under `python3 -I`, output in the session scratchpad): 2 recordings, 58 calls each, 19 traffic rows per window in both cases (15 under the first build), answers up to 2.9 KB, 0 non-OK client rows in these two cases' windows, no `unknown_trace_service`, no staging directory left; 7.1 s, 72 MB peak.
  - **Data reads (dev only):** the 30 repetition-1 `traces.csv` (`serviceName`, `operationName`, `statusCode`, `startTime` range, parent links) and the two sanity cases. No repetition 2-3 or RE2-SS file was opened.
  - Totals: about 460 added and 44 removed lines in tracked files (this document included), plus `traffic.py` (132) and the two fixture files (27).

  - The parent re-ran the full evals suite: 126 passed. It accepted the new loss: `recommendationservice` and `emailservice` spans have an empty `operationName`, so `recommendationservice` → `productcatalogservice` keeps its server rows but has no client rows. Follow-up: a fallback from the parent link for client spans with an empty `operationName`.

## Next step

T5: the replay plumbing and the scorer (branch `feat/rcaeval-converter-02-replay`).
