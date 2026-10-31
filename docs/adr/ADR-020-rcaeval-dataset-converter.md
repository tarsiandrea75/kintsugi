# ADR-020: RCAEval dataset revision, converter boundary and category map

## Status

Accepted. It amends ADR-001's boundary for the converter. The vision (§2, "Offline track: RCAEval") named this decision "ADR-017", but that number went to the qwen experiment, so this record is ADR-020.

## Date

2026-10-31

## Context

The offline track (vision §2) needs RCAEval cases in a form the agent reads, and every measurement on them needs a fixed dataset and a fixed answer key. Research on 2026-10-31 (`odd/tasks/rcaeval-converter.md`) found:

- **Where the data is published:**
  - Zenodo record 14590730 (DOI 10.5281/zenodo.14590730), one zip per system;
  - a Figshare bundle and a Hugging Face Parquet copy.
- **Code:** the RCAEval code is tagged `1.11.0` and holds the evaluator (`RCAEval/benchmark/evaluation.py`, `scoring.py`).
- **Case layout:** `RE2-<SYS>/<service>_<fault>/<rep>/` with `simple_metrics.csv` (1 s, one `<service>_<indicator>` column each), `logs.csv`, `inject_time.txt` and, for Online Boutique only, `traces.csv`.
  - Each system has 5 root-cause services × 6 faults (cpu, mem, disk, delay, loss, socket) × 3 repetitions = 90 cases.
  - The truth is the directory name. RE2-OB has a stray `checkoutservice_cpu/multi-source-data/` folder.
- **No topology** ships with either the data or the code.
- **Licenses:** the README says MIT for code and data. The Zenodo record says CC-BY-4.0. The Figshare and Hugging Face copies say MIT.

ADR-001 keeps Python to evaluation code. A converter that writes the agent's input sits on that boundary.

## Decision

1. **Dataset revision:** Zenodo record 14590730.

   | File | md5 |
   |---|---|
   | `RE2-OB.zip` | `b9e23f8842c404b396ffd2becff15de4` |
   | `RE2-SS.zip` | `bd747a8fc7c5be00c613e13fbf9dd74b` |

   - The data lives outside the repository (`KINTSUGI_RCAEVAL_DIR`, default `~/data/rcaeval`), checked against these md5s.
   - The evaluator's semantics are those of RCAEval tag `1.11.0`.
   - The dataset is cited under both licenses as stated by its publishers.
2. **Boundary.** The converter is Python under `evals/`. It only writes files in the agent's existing replay format: one incident JSON per case and a `recordings.tsv` manifest. It never runs inside the agent and adds no Python to the system. ADR-001 is amended to allow this one producer of agent input.
3. **Windows.** `sinceMinutes` is 10 and `startedAt` is `inject_time` + 10 minutes. The agent's previous window is then the 10 minutes before the injection, and its incident window the 10 minutes after, as the published baselines get them (vision §2).
4. **Leakage.**
   - The recordings carry no case name, fault word or truth. The truth stays in `recordings.tsv`, which the agent never reads, and recording file names are blinded hashes.
   - The symptom is one fixed sentence per system.
   - A test checks every converted recording for the fault words and the case names.
5. **Category map** (pre-registered, used only for category accuracy; AC@1 on RCAEval is service-level):

   | RCAEval fault | Kintsugi category |
   |---|---|
   | `cpu`, `mem`, `disk`, `socket` | `RESOURCE_EXHAUSTION` |
   | `delay`, `loss` | `DEPENDENCY_LATENCY` |

   Packet loss shows as TCP retransmissions, which are latency, not refusals.
6. **Topology files.**
   - One per system, in the agent's topology format (`odd/tasks/topology-data.md`).
   - Edges come from the upstream application manifests at a pinned commit. The pinned commits are recorded in the files' feature-document entry.
   - For Online Boutique the edges are cross-checked against the traces of repetition 1 only.
   - Service names follow the metric prefixes, normalized the way the evaluator normalizes them (`split("_")[0]`, `-db` folded, lowercase).
7. **Split hygiene.**
   - The converter is tested on synthetic fixtures.
   - Only RE2-OB repetition 1 (dev) is converted until a holdout plan says otherwise.
   - Repetitions 2–3 (holdout A) and RE2-SS (holdout B) are read once, each under its own committed plan.

## Alternatives considered

- **The Hugging Face Parquet copy**: one file per modality, with a `cases.parquet` that lists the truth next to the data. It is easier to read, but the truth sits beside the inputs, and the Zenodo zips are what the RCAEval scripts download.
- **A new dossier source inside the agent** (Java reading CSV): more code in the system, and a second path the lab never exercises. The replay format is already proven by every lab measurement.
- **Edges from traces only**: Sock Shop has no traces, and reading traces of every repetition would read the holdouts.

## Consequences

### Positive

- The agent, the rules and the scorer run on RCAEval without a separate code path, so a difference between the tracks is a difference in the data, not in the pipeline.
- The answer key and the dataset bytes are fixed before any run.

### Negative

- The dossier has no resource facts yet (vision M3). cpu, mem, disk and socket faults reach the agent only through latency and logs, so Config 0 and the models start weak on them.
- The agent's prompt still holds lab wording ("a small online shop", the "Order states" headings). These are follow-ups in `odd/tasks/topology-data.md`.
- About 11 GB of extracted data on the local disk.
