# Feature: replay harness

## Objective

Score a prompt or loop change on many samples per fault in minutes, without the lab: record each fault's tool outputs once from a real run, then run the real agent loop against them N times and score every sample against the recorded truth.

## Problem

One lab round takes about 27 minutes per run on the cloud route (11 minutes of spacing between faults) and hours on the local route, so every comparison so far rests on one to three samples per fault and mode. Rates swing between rounds of the same code (`f3-caller-config` T5 explorer 5/6, `evidence-settling` T3 explorer 1/3), so a change cannot be told from noise. ADR-012 (line 37) names the replay harness as the complement of the cloud route: the cloud route removes the model's wall-clock, the replay removes the lab's.

## Why

The project rule is "the LLM proposes, deterministic code verifies": a prompt change is accepted on a measured rate, and a rate needs samples. Replaying only the model calls over fixed evidence also removes the lab's variance (timing, leftovers), so what remains is the model's.

## Design

- **Seam: `LabTools`** (`agent/.../lab/LabTools.java`). A new `ReplayLabTools implements LabTools` serves recorded outputs; `AgentLoop`, `DossierGatherer`, `Prompts`, `ReportCheck` and the routes run unchanged, so the replay measures the real agent.
- **Recording:** a fault's recording is one or more incident JSON files saved by `verify-agent.sh` (`GET /incidents/{id}?full=true`), which hold every `TOOL_CALL` step's post-cap args and full output and the `DOSSIER` step's array of calls, plus the truth (`service|category`, from `F?-score.txt`) and the tool definitions (`tools-list.json`). A script copies them into `agent/src/test/resources/replay/<recording>/`.
- **Keying:** exact match on (tool, canonical JSON of the post-cap args), with the same caps `McpLabTools` applies. The dossier's calls are deterministic given `sinceMinutes`, so dossier gathering replays fully. Calls the model chooses (explorer mode, dossier `INVESTIGATE`/`KNOWLEDGE`) may be unrecorded.
- **Unrecorded call:** answered as a `CLIENT_ERROR` "not recorded in this replay" and counted. Each sample reports its coverage (recorded calls / calls); a low-coverage sample is flagged, never silently scored. No nearest-match guessing.
- **Clock:** a `Clock.offset` so that "now" is the recorded incident's start: windows and restart times computed from it stay consistent with the recorded timestamps, and the loop's time budget still runs.
- **Driver:** a JUnit test tagged `replay`, excluded from the default build, run on demand with the route, mode, recordings and N as system properties; it uses the existing Testcontainers Postgres (`AgentDatabase`) and the real `ChatModel` for the route. It writes per-sample JSON and a `summary.tsv` (fault, mode, route, sample, verdict, coverage, tokens) under `infra/scripts/out/replay-<UTC time>/`.
- **Truth and verdict:** the same rule as `agent_verdict` in `lib.sh`: the reported `service|category` equals the truth, else MISS.

Out of scope: Python aggregation in `evals/` (the TSV is enough at first); matching free-text `search_runbooks` queries by similarity; replaying model calls (only tools are replayed).

## Constraints

- No API key read, printed or committed; every cloud batch needs the human's go-ahead and a recorded cost.
- The default `./mvnw test` stays lab-free and model-free: the replay test runs only when asked.
- Recordings are lab output, not secrets; check them for keys before committing.
- Tests first; living spec in the agent spec.

## Tasks

- [x] **T1 — This document** and its Engram mirror; route: inline.
- [x] **T2 — `ReplayLabTools`.** Loads recordings (top-level `TOOL_CALL` steps and the `DOSSIER` array), keys by tool and canonical capped args, answers unrecorded calls as `CLIENT_ERROR` "not recorded" and counts them; `toolsFor` from the recorded `tools-list.json`. Unit tests first over a small fixture. Living spec: agent spec, a "Replay" requirement. Route: delegated writer (2+ files).
- [x] **T3 — Record script.** `infra/scripts/record-replay.sh <verify-agent run dir>...` copies each `F?-incident.json` with its truth into `agent/src/test/resources/replay/` (the tool listing is the recorded `mcp-outputs/tools-list.json`, which holds the 8 tools mcp-tools serves), refusing a file that holds a key-like string. Tests in `test-lib.sh` where pure. Then record F1-F3 from existing cloud runs (dossier and explorer). Route: delegated writer.
- [x] **T4 — Replay driver.** The `replay`-tagged test, the clock offset, the per-sample output and `summary.tsv`; excluded from the default build (Maven surefire groups). Smoke check: one sample per fault on the cloud route, with the human's go-ahead. Route: delegated writer.
- [ ] **T5 — First measurement.** With the human's go-ahead: N=5 per fault per mode on the cloud route on the current prompt (about 30 samples, an estimated $0.10–0.15); compare the replayed rates and coverage with the lab rounds. Route: parent (runs).

## Acceptance criteria

- T2: a recorded call returns its recorded output and outcome; an unrecorded one returns `CLIENT_ERROR` and raises the miss count; caps match `McpLabTools`; `./mvnw test` passes.
- T3: the script copies a run's three faults with their truth and refuses a key-like string.
- T4: `./mvnw test` without the tag runs no replay; with it, one sample per fault produces a scored line with coverage; dossier gathering has full coverage.
- T5: rates per fault and mode with N=5, coverage per mode, cost recorded.

## Checks

- `cd agent && ./mvnw test` (Testcontainers needs Docker).
- `bash infra/scripts/test-lib.sh`.
- Per-commit native review assessment.

## Delivery

Forecast about 700 authored lines (Java, tests, script, spec, recordings excluded as data): over the 400-line budget, strategy `feature-branch-chain` (standing choice). Slices: T2 (with its spec), T3, T4+T5. Branch `feat/replay-harness`, from `feat/evidence-settling` (`2a57ac5`).

## Progress

- 2026-10-19: T1, after a read-only mapping: no earlier replay design exists beyond ADR-012:37; `LabTools` is the seam; recorded incidents hold post-cap args and full outputs; truth lives only in `F?-score.txt`; the cloud route sets no temperature, so samples measure its sampling variance.
- 2026-10-19: T2, route delegated writer (2+ non-trivial files). `ReplayLabTools` indexes top-level `TOOL_CALL` steps with an output and the `DOSSIER` array by tool and sorted-key args, after `McpLabTools`' caps; it replays only mcp-tools' own outcomes (a refused step or a recorded `CLIENT_ERROR` is not indexed); unrecorded calls are `CLIENT_ERROR` and counted (`recordedCalls`, `unrecordedCalls`, `coverage`, `conflicts`). `McpLabTools` gained package-private `sentArgs`/`notAnObject` and a package-private `DefinitionOnly`, behaviour unchanged. RED: `ReplayLabToolsTest` against a stub, 15 run, 14 failures, 1 error; GREEN: `ReplayLabToolsTest` 15/15, `McpLabToolsTest` 28/28; full `./mvnw test` 397 run, 0 failures, 0 errors, 0 skipped. Spec: agent spec, "Replay of recorded tool outputs".

- 2026-10-19: T2 done in `45f56bb`. Native assessment `2a57ac5..45f56bb`: medium, `slice_budget_reached`; review (1 lens, reliability, standing consent granted) approved and acknowledged. Two WARNINGs fixed inline (route: inline, one class and its test): recordings are now capped like calls, so a recorded call without the cap still matches; an unreadable dossier step fails with the incident's id instead of a bare parse error. The SUGGESTION (no test that a recorded `CLIENT_ERROR` is not replayed) is covered too. RED: the cap case failed without the fix (18 tests, 1 failure); GREEN: 18/18; full suite 400/0/0.

- 2026-10-19: T3, route delegated writer. `infra/scripts/record-replay.sh <run dir>...` copies each `F?-incident.json` to `agent/src/test/resources/replay/<fault>/<run dir>-<fault>.json` and keeps `replay/recordings.tsv` (fault, recorded mode and route, truth, file, incident), idempotent on re-run; it skips a fault with no score or truth and refuses a key-like or non-object incident (exit 1). Pure parts in `lib.sh`: `holds_key_like_string`, `score_fields`. RED: `test-lib.sh` failed on `holds_key_like_string: command not found`; GREEN: `PASS 61 cases`. End-to-end on a temporary `REPLAY_DIR`: three faults recorded, a `sk-ant-` incident refused, a fault without score and one without truth skipped, the re-run added no manifest line. Recorded F1-F3 from `verify-agent-20261019T170520Z-explorer-cloud` and `verify-agent-20261019T173217Z-dossier-cloud` (the two cloud runs after the leftover-evidence fix): six incidents, 28-58 KB each, none refused. Tool listing: `mcp-outputs/tools-list.json` holds the same eight tools `verify-mcp.sh` expects, so the replay uses it and no listing is copied.

- 2026-10-19: T3 split into `fe52a54` (script, tests, docs) and `8d3cd71` (recordings, data): the single commit's evidence exceeded the reviewers' context budget (`lens_context_budget_exceeded`). Native assessment of `45f56bb..fe52a54`: high; 4-lens review (standing consent granted) approved and acknowledged. Two WARNINGs fixed inline: the script now exits 2 when `python3` is missing instead of refusing every incident as not JSON (checked with a `PATH` without python3: exit 2 and the message); T3's text no longer says the tool listing is copied. SUGGESTIONs left: a re-run keeps an old manifest row for a re-recorded file; no end-to-end test of the script; a dossier `toolOutput` that is JSON but not an array is silently empty; duplicated trap in `test-lib.sh`. The recordings commit is data only and is not reviewed.

- 2026-10-19: T4 code, route delegated writer (2+ non-trivial files). Test-side `ReplayDriver` (package `dev.kintsugi.agent.loop`, test sources) builds one `AgentLoop` per sample, as `AgentLoopTest` does, over a fresh `ReplayLabTools` of all the fault's recordings (both modes pooled) and a `Clock.offset` to the same-mode recording's `startedAt`, opens the incident with that recording's symptom and `sinceMinutes`, runs `run(UUID)` synchronously, reads the incident back as the full view, scores it by `agent_verdict`'s rule and writes `<fault>-<mode>-<n>.json`, `summary.tsv` and `aggregate.tsv`. `ReplayRunTest` (`@Tag("replay")`, `@SpringBootTest` with `kintsugi.agent.route=${replay.route:cloud}`) takes the app's own `ModelRouter` and `LoopSettings`, so the route's real model and settings, with no bean override. Building the loop directly needed no production change beyond making `ReplayLabTools.NOT_RECORDED` public (to count unrecorded dossier calls). `pom.xml`: `excludedGroups` `live,replay` plus a `replay` profile mirroring `live` (a bare `-Dgroups=replay` cannot override the configured `excludedGroups`). `lib.sh`: `env_file_read` (value into a named variable, never printed) and `export_cloud_key`, so `replay-agent.sh` passes the key to Maven through the environment only. RED: `ReplayDriverTest` against a stub, 5 run, 5 errors; `test-lib.sh` with HEAD's `lib.sh` failed on `export_cloud_key: command not found`. GREEN: `ReplayDriverTest` 5/5 (dossier sample: 29 dossier calls, none unrecorded, coverage 1.00; explorer sample: 1 recorded, 1 unrecorded); `test-lib.sh` PASS 66 cases; full `./mvnw test` 405 run, 0 failures, 0 errors, `ReplayRunTest` not run. Selection check without a model: `./mvnw test -Preplay -Dtest=ReplayRunTest` with no key ran `ReplayRunTest` and stopped at the context start (`needs ANTHROPIC_API_KEY`). Spec: agent spec, the driver paragraph and two scenarios; `docs/setup.md`, "Replay the recorded faults".

- 2026-10-19: T4 code committed as `33dbf9d` (driver, tests, surefire `replay` profile, spec) and `b16034a` (`replay-agent.sh`, `export_cloud_key`, docs), split so each fits a review. Both high; reviews (standing consent granted) approved and acknowledged. WARNINGs fixed inline: `ReplayRunTest` writes `aggregate.tsv` in a `finally`, so a paid partial run keeps its totals; `replay-agent.sh` refuses more than 60 cloud samples in one run (`MAX_CLOUD_SAMPLES`); its options are assigned explicitly instead of through `declare`, and a fault listed twice is refused (the third WARNING's text was not retrievable, so these are the parsing faults found on reading lines 43-62). Checked: each bad option exits 2 with its message, 66 samples exit 2 before Maven; `ReplayDriverTest` green; `test-lib.sh` PASS. SUGGESTIONs left (summary append on a reused out dir, second-resolution out dir, cost constants, doc wording).

- 2026-10-19: T4 smoke check, with the human's go-ahead: `replay-agent.sh --samples 1` on `718986f` (`infra/scripts/out/replay-20261019T190356Z-cloud`), 6 samples, about 16 s each, exit 0.
  - **Dossier mode replays:** every sample's `DOSSIER` step served all 29 calls from the recording (acceptance met); coverage 0.91 overall, the misses being model-chosen calls (mostly free-text `search_runbooks`). Scores: F1 MISS (`payment-service|DEPENDENCY_LATENCY`), F2 MATCH, F3 MATCH.
  - **Explorer mode does not replay with exact matching:** coverage 0.38, 0.00 and 0.25; every sample ended `UNKNOWN|UNKNOWN`, the model having been told most of its calls were not recorded. Checked on F2: no systematic key bug, the model chose different arguments (no `sinceMinutes` on `status_summary`, other `recent_events` filters, other queries). Explorer replay scores measure the replay's gaps, not the agent: they are not valid measurements under this design.
  - Tokens: 246,064 prompt and 12,055 completion, **$0.0306**. Total cloud spend about $0.34 of the human's $5.

## Next step

T5, after the human decides how to treat explorer mode (measure dossier mode only, or change the design for explorer).
