# Spec: evals (the scorer)

The scorer `kintsugi_evals` under `evals/` turns replay results into the protocol's numbers ([ADR-015](../../../docs/adr/ADR-015-evaluation-protocol.md), `docs/vision.md` §6). It is run as `python -m kintsugi_evals score` (and `benchmark`, and `convert-rcaeval`, which writes the agent's replay input from RCAEval, REQ-11) from `evals/`.

## Requirements

### REQ-1 Standard library only
The scorer MUST import only the Python standard library. pytest MUST be a dev dependency only, pinned in `evals/requirements-dev.txt`.

#### Scenario: Bare interpreter
- Given a Python 3.12 with no package installed
- When `python3 -m kintsugi_evals score <dir>` runs in `evals/`
- Then it prints the report

### REQ-2 Wilson interval
Every rate MUST carry a Wilson 95% interval, z = 1.959964, clipped to [0, 1]. An empty sample MUST be an error.

#### Scenario: The vision's quoted intervals
- When the interval of 4/5, 5/15 and 14/15 is computed
- Then it is [38%, 96%], [15%, 58%] and [70%, 99%] respectively, rounded to whole percents

### REQ-3 Exact McNemar
A paired comparison MUST use the exact two-sided McNemar test: twice the binomial tail of min(b, c) in b + c trials at 1/2, capped at 1, and 1 when there is no discordant case. It MUST be computed only when every case has one outcome per config.

#### Scenario: Nine against zero
- Given 9 cases right only under D and none right only under A
- When the paired comparison runs
- Then the p-value is 0.00390625, printed as 0.0039

#### Scenario: Repeated cases
- Given a case with more than one row under a config
- When the paired comparison runs
- Then the McNemar p-value and both discordant counts are reported as n/a (empty in `paired.tsv`), never 0, and the cluster bootstrap still runs

### REQ-4 Cluster bootstrap
The paired difference, second minus first, MUST be the mean over cases of each case's mean accuracy, so each case weighs once. Its 95% interval MUST come from resampling whole cases with an explicit seed, recorded in the report. No case, fewer than 1 resample, a level outside (0, 1), or a case with no repetition under a config MUST be an error.

#### Scenario: Deterministic
- Given the same cases, seed and number of resamples
- When the bootstrap runs twice
- Then both intervals are identical

### REQ-5 Holm correction
When a run makes more than one paired test, the report MUST also give Holm-adjusted p-values, in input order.

#### Scenario: Three tests
- When p-values 0.01, 0.04 and 0.03 are adjusted
- Then the result is 0.03, 0.06 and 0.06

### REQ-6 The dev keep rule
`dev_keep` MUST keep a variant only with a net gain of at least 3 cases over the baseline and no fault class losing more than 1 case net.

#### Scenario: A class loses two
- Given a variant that gains 5 cases on F1 and loses 2 on F2
- Then it is not kept

### REQ-7 Scoring a row
Each row MUST be rescored from `status`, `truth` and `reported`, ignoring `verdict`:
- on a fault truth, a COMPLETED run reporting the truth is correct, `UNKNOWN|UNKNOWN` is an abstention, anything else is a miss;
- on the healthy truth `NONE|NONE` (provisional until follow-up 10, lab-realism), `UNKNOWN|UNKNOWN` is correct, any cause is a false alarm, and no report is a miss.

AC@1 MUST count an abstention as not correct. Coverage MUST be the share of fault rows with an answer (ADR-015): an abstention, a row whose status is not `COMPLETED` (`TIMED_OUT`, `FAILED`, `INVALID_REPORT`) and a row with no report have none. Selective accuracy MUST be the share correct among the answered rows. Healthy rows MUST NOT enter AC@1 or the paired comparison.

#### Scenario: Abstention on a fault
- Given a COMPLETED fault row reporting `UNKNOWN|UNKNOWN`
- Then it is an abstention, AC@1 counts it as not correct, and coverage drops

#### Scenario: Fault row with no answer
- Given a fault row with status `TIMED_OUT` or `INVALID_REPORT` and report `none`
- Then AC@1 counts it as not correct, coverage drops, and selective accuracy leaves it out

#### Scenario: Healthy case answered UNKNOWN
- Given a COMPLETED row with truth `NONE|NONE` reporting `UNKNOWN|UNKNOWN`
- Then it is correct and not a false alarm

#### Scenario: Healthy case answered with a cause
- Given a COMPLETED row with truth `NONE|NONE` reporting `gateway-mock|UNKNOWN`
- Then it is a false alarm

### REQ-7b Service-only AC@1 (rcaeval-converter T5)
`score --service-only` MUST score every fault row on the service part of its truth: a COMPLETED row is correct when the service before `|` in `reported` is the truth's, whatever the category, because RCAEval's AC@1 names the root-cause service only (vision §5, ADR-020). `UNKNOWN|UNKNOWN` MUST stay an abstention, healthy rows MUST keep REQ-7's rule, and candidate recall (REQ-7a) MUST count a row whose candidates hold the truth's service. AC@1 MUST still be reported per fault and pooled with its Wilson 95% interval, coverage and selective accuracy, and each config's first report line MUST end with `; AC@1 on the service only`. Without the flag, scoring and the report MUST be unchanged.

#### Scenario: The right service with another category
- GIVEN a COMPLETED row with truth `checkoutservice|RESOURCE_EXHAUSTION` reporting `checkoutservice|DEPENDENCY_LATENCY`
- WHEN `score --service-only` runs
- THEN the row is correct
- AND without `--service-only` it is a miss

#### Scenario: Service-only AC@1 with its Wilson interval
- GIVEN three fault rows: `cpu` reporting the truth's service with another category, `cpu` reporting another service, and `delay` reporting the truth
- WHEN `score --service-only --label 0` runs
- THEN it prints `0  pooled   2/3   AC@1  67%  [21%, 94%]` and `0  cpu      1/2   AC@1  50%`, and the config's line ends with `; AC@1 on the service only`

#### Scenario: Abstention under service-only
- GIVEN a COMPLETED fault row reporting `UNKNOWN|UNKNOWN`
- WHEN `score --service-only` runs
- THEN it is an abstention, as in REQ-7

### REQ-7a Candidate recall
`summary.tsv` MAY end with a `candidates` column: `service|category` pairs joined by `;`, an empty string being an empty list. It MUST be read only on rows whose `route` is `rules` or whose `report` is `hybrid`; on other rows, and in a file without the column, candidate recall MUST NOT apply. Where it applies, candidate recall MUST be, per fault and pooled, the share of fault rows whose truth is among the candidates (ADR-019: H's ceiling). Healthy rows MUST NOT enter it. Where it does not apply, the report and `scores.tsv` MUST show nothing for it, so an older input scores exactly as before.

#### Scenario: Rules rows
- Given a rules `summary.tsv` whose F1 rows list the truth in 2 of 3 candidate lists, one of them empty
- When `score` runs
- Then the F1 line ends with `recall 67%` and `scores.tsv` has `candidate_recall` 0.6667 for F1

#### Scenario: A plain report
- Given a `summary.tsv` with route `local`, report `plain` and a `candidates` column
- When `score` runs
- Then no fault line shows recall and its `candidate_recall` cells are empty

#### Scenario: An older TSV without candidates
- Given a `summary.tsv` without the `candidates` column
- When `score` runs
- Then recall does not apply and the report is the one written before the column existed

#### Scenario: The scores.tsv column
- When `score --out DIR` runs
- Then `scores.tsv` ends with a `candidate_recall` column, four decimals where recall applies and empty otherwise, healthy rows included

### REQ-8 TSV input
The scorer MUST read the replay driver's `summary.tsv`, given as the file or its run directory. A case MUST be keyed by (`split`, `recording`). The config MUST be the `--label` given for that input, else `route-mode-view`. A missing `view` column MUST read as `-`. A file lacking any of `fault`, `mode`, `route`, `status`, `truth`, `reported`, `split`, `recording` MUST be an error naming the columns. A path holding no `summary.tsv` MUST be an error naming it.

#### Scenario: An older TSV without view
- Given a `summary.tsv` with 17 columns and no `view`
- When it is read unlabelled
- Then its config is `<route>-<mode>--`

### REQ-9 Output
The CLI MUST print, per config, AC@1 per fault and pooled with Wilson intervals, coverage and selective accuracy on the pooled line, healthy false alarms when there are healthy rows, and one line per pair of configs (later minus earlier) with the discordant counts, the McNemar p-value and the bootstrap interval. With `--out DIR` it MUST write `report.txt` (identical to the printed report), `scores.tsv`, `paired.tsv`, `inputs.tsv` (label, source, sha256, rows) and a copy of each input as `inputs/<label>-summary.tsv`.

The CLI MUST exit with a one-line message, not a traceback, when an input is missing or malformed, when two configs share no fault case, when two inputs resolve to the same label or the same copy name, and, at parse time, when `--iterations` is below 1.

#### Scenario: Two inputs, one label
- Given `--label A` on two different dirs
- When `score` runs
- Then it exits naming the label, and writes nothing

#### Scenario: M0 on dev
- Given `--label A` on the local M0 replay dir and `--label D` on the cloud one
- When `score` runs
- Then it prints `A  pooled   5/15  AC@1  33%  [15%, 58%]`, `D  pooled  14/15  AC@1  93%  [70%, 99%]` and `D - A: 15 cases, D only 9, A only 0, exact McNemar p = 0.0039`

#### Scenario: Regenerate from committed files
- Given a committed `evals/results/<name>/inputs/` directory
- When `score` runs on its copies with the same labels and seed
- Then the report is the same as the committed `report.txt`

### REQ-10 Benchmark document
`python -m kintsugi_evals benchmark --out FILE DIR...` MUST render one Markdown file from result directories written by `score --out`, reading their `inputs.tsv`, `scores.tsv`, `paired.tsv` and `report.txt`. It MUST compute nothing: every number is copied from those files and formatted as `report.txt` formats it. Per directory, in the order given, it MUST write a table of configs by fault and pooled (matches/n, AC@1, Wilson interval) with coverage and selective accuracy, healthy false alarms when there are healthy rows, a table of the paired rows, the bootstrap seed and resamples, and each input's label, source and sha256. The header MUST name the exact regenerate command with the paths as given and say not to edit by hand. The same inputs MUST give the same bytes: no timestamp, file order preserved. A missing directory or file MUST exit with status 2 and a one-line message, not a traceback, and write nothing.

#### Scenario: The M0 dev result
- Given `evals/results/2026-10-25-m0-dev/`
- When `benchmark --out FILE results/2026-10-25-m0-dev` runs
- Then FILE has the row `| D | 4/5, 80% [38%, 96%] | 5/5, 100% [57%, 100%] | 5/5, 100% [57%, 100%] | 14/15, 93% [70%, 99%] | 100% | 93% |` and the paired row `| D - A | 15 | 9 | 0 | 0.0039 | 0.0039 | +60 | [+33, +87] |`

#### Scenario: Regenerate byte for byte
- Given a committed `docs/benchmark.md`
- When the command in its header runs again from `evals/`
- Then the file has no diff

#### Scenario: A missing result
- Given a directory with no `paired.tsv`
- When `benchmark` runs on it
- Then it exits with status 2, prints one line naming the missing file, and writes nothing

### REQ-11 RCAEval converter
`python -m kintsugi_evals convert-rcaeval --system re2-ob|re2-ss --rep N` MUST turn each case directory `<root>/RE2-<SYS>/<service>_<fault>/<N>/` into one replay recording the unchanged agent reads, plus one `recordings.tsv` row (`fault mode route truth file incident split`), under `--out` (default `evals/out/rcaeval/<system>-rep<N>/`, git-ignored) ([ADR-020](../../../docs/adr/ADR-020-rcaeval-dataset-converter.md)). The root MUST default to `$KINTSUGI_RCAEVAL_DIR`, else `~/data/rcaeval`. A recording MUST hold `id`, the system's one fixed `symptom`, `sinceMinutes` 10, `startedAt` = inject time + 10 minutes, and one `DOSSIER` step whose calls are exactly those `DossierGatherer` makes for the system's topology file, with the arguments it sends, plus its resource calls of [ADR-021](../../../docs/adr/ADR-021-resource-rules-config.md):
- `query_metric latency_avg` per service from `<service>_latency-50` (seconds), resampled to the lab's 21 s step for 20 minutes, each point the mean of the rows in (t - 21 s, t], `NaN` when none has a value, and no series for a service without the column;
- `query_metric` `processor_usage`, `memory_used` and `open_files` per service, `sinceMinutes` 20 as for latency, from `<service>_cpu`, `<service>_mem` and `<service>_socket` in the exporter's unit, resampled as latency is, one series labelled with `service` only, and no series for a service without the column. `_diskio` MUST NOT be mapped. The column names MUST NOT appear in a recording;
- `count_logs` and `search_logs` per service and level (WARN, ERROR) from `logs.csv` in [inject, inject + 10 min): the level column when it names one, else a whole word of the message (error, exception, traceback, fatal, panic: ERROR; warn, warning: WARN); `search_logs` gives the newest 20 lines, each cut at 1000 characters with `[truncated]`;
- `traffic_counts` for the previous window [inject - 10 min, inject) and the incident window [inject, inject + 10 min), for RE2-OB from `traces.csv`, each row counted in the window holding its own span's start time. A client span (its `operationName` is `hipstershop.<Name>Service/<Method>`, naming a service other than its own) adds 1 to a client row (`service` its service, `clientName` the named service, its own status, `exception` `none`); a span whose parent span belongs to another service adds 1 to a server row (`service` its service, its own status, no `clientName` or `exception`). No span is both. Both rows have `method` `CALL` and `uri` `/` as the topology's edges, in mcp-tools' row order. The gRPC `statusCode` maps to the HTTP status of `google.rpc.Code` (0 or blank 200, 2 500, 4 504, 13 500, 14 503, ...) and Spring's outcome of it; traces show no exception class, so none is invented. Trace names go through the one alias table `trace_edges` uses (`frontendservice` is `frontend`); a call naming a service outside the topology is dropped and counted in `converter-report.tsv`. RE2-SS has no traces: both answers have no items;
- `service_restarts` with no items.

The truth, `<service>|<category>` by ADR-020's map, MUST appear only in `recordings.tsv`; the file name MUST be a 16-hex-digit sha256 of `RE2-<SYS>/<case>/<N>` and the incident id a UUID from the same hash. The same input MUST give the same bytes. Only RE2-OB repetition 1 is `dev`; any other repetition, and RE2-SS, is a holdout the converter MUST refuse, before reading any case file or creating `--out`, unless `--allow-holdout A` (RE2-OB repetitions 2-3) or `--allow-holdout B` (RE2-SS) names it; its rows then carry the split `holdout`.

The converter MUST write into a staging directory beside `--out` and move the files into `--out` only after the scan below passed, so a run that fails for any reason leaves no recording, manifest or report behind. After writing, the converter MUST scan every recording it wrote. A fault word (cpu, mem, disk, delay, loss, socket, whole word, case-insensitive, `_` breaking words) outside `untrustedText`, or a case directory name or a category anywhere, MUST fail the conversion and remove its recordings. Fault words inside `untrustedText`, the system's own log text, MUST be counted in `converter-report.tsv`, not fail: rewriting a log line would falsify the evidence, and the leak guarded against is the converter's own. The report also lists metric prefixes and log containers outside the topology.

#### Scenario: The windows
- Given a case whose `inject_time.txt` holds 1700000000
- When it is converted
- Then `startedAt` is `2023-11-14T22:23:20Z`, `sinceMinutes` is 10, and the latency points run from 1699999400 to 1700000597 every 21 s, 58 points

#### Scenario: Resource series
- Given an RE2-OB case whose `simple_metrics.csv` has `checkoutservice_cpu` 0.2 before the injection and 0.9 after, `checkoutservice_mem`, `checkoutservice_socket` and `checkoutservice_diskio`, and no resource column for `redis`
- When it is converted
- Then `query_metric` `processor_usage` for `checkoutservice` with `sinceMinutes` 20 holds one series labelled `{service: checkoutservice}` with the latency's 58 points, 0.2 at 1699999988 and 0.9 at 1700000597
- And `memory_used` and `open_files` for `checkoutservice` hold the `_mem` and `_socket` values, every resource answer for `redis` has no items, no call names a disk metric, and the recording passes the leakage guard and holds no column name

#### Scenario: The truth only in the manifest
- Given the case `checkoutservice_cpu`
- When it is converted
- Then its `recordings.tsv` row has fault `cpu`, truth `checkoutservice|RESOURCE_EXHAUSTION` and split `dev`, and its recording holds neither the case name nor the category

#### Scenario: A holdout is refused
- Given `--system re2-ob --rep 2` without `--allow-holdout A`
- When `convert-rcaeval` runs
- Then it exits naming holdout A, reads no case file and creates no output directory

#### Scenario: The leakage guard
- Given a symptom that says "disk"
- When a case is converted
- Then the conversion fails naming the fault word and keeps no recording
- And a log line saying "delay" is kept as is in `untrustedText` and counted in `converter-report.tsv`

#### Scenario: Traffic from the traces
- Given an RE2-OB case whose `traces.csv` has a `frontendservice` span calling `checkoutservice` 20 s after the injection, with status 14 on the caller's span and 0 on the callee's
- When it is converted
- Then the incident `traffic_counts` holds a client row `frontend` -> `checkoutservice` `CALL /` status `503` `SERVER_ERROR` exception `none` and a server row `checkoutservice` `CALL /` status `200` `SUCCESS`, and the previous window does not count that call
- And a `frontendservice` client span `hipstershop.CartService/GetCart` with status 14 and no child span gives a client row `frontend` -> `cartservice` status `503`, with no server row
- And an RE2-SS case's `traffic_counts` answers have no items

#### Scenario: A failed run leaves nothing
- Given a run whose second case has no `traces.csv`, or whose recording leaks a fault word
- When the conversion fails
- Then neither `--out` nor a staging directory exists afterwards

#### Scenario: The agent replays a converted recording
- Given the recording `agent/src/test/resources/rcaeval/` holds, converted from the synthetic fixture and checked byte for byte against a fresh conversion
- When `DossierGatherer` gathers with the RE2-OB topology at its `startedAt`
- Then no call is answered `not recorded in this replay`, the incident traffic is not empty and its client rows match the topology's edges, and `RulesDiagnosis.diagnose` returns a verdict
