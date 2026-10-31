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
  - `traffic_counts` comes, for OB, from the spans in `traces.csv`: client and server rows per caller→callee and operation, with status and error counts. SS has no traces, so its rows are empty.
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
- [ ] T2 The topology files for RE2-OB and RE2-SS, from the pinned upstream manifests, with the OB cross-check on repetition 1's traces recorded. `TopologyTest` loads both. Route: delegated writer.
- [ ] T3 The converter core, tests first on a synthetic fixture: case discovery, windows, metrics → `query_metric`, logs → `count_logs`/`search_logs`, empty restarts, the incident JSON and `recordings.tsv`, the leakage grep. Route: delegated writer.
- [ ] T4 Traces → `traffic_counts` (OB), tests first. Route: delegated writer.
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

## Next step

T2: the topology files, once `RE2-OB.zip` is downloaded and verified.
