# Spec: evals (the scorer)

The scorer `kintsugi_evals` under `evals/` turns replay results into the protocol's numbers ([ADR-015](../../../docs/adr/ADR-015-evaluation-protocol.md), `docs/vision.md` §6). It is run as `python -m kintsugi_evals score` (and `benchmark`, `convert-rcaeval`, which writes the agent's replay input from RCAEval, REQ-11, and `baro-rcaeval`, which ranks RCAEval cases with the published baseline, REQ-12) from `evals/`.

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
- on the healthy truth `NONE|NONE` (final since lab-realism-min T5, ADR-015's amendment of 2026-11-03), `UNKNOWN|UNKNOWN` is correct, any cause is a false alarm, and no report is a miss.

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
The CLI MUST print, per config, AC@1 per fault and pooled with Wilson intervals, coverage and selective accuracy on the pooled line, healthy false alarms when there are healthy rows, and one line per pair of configs (later minus earlier) with the discordant counts, the McNemar p-value and the bootstrap interval. With `--out DIR` it MUST write `report.txt` (identical to the printed report), `scores.tsv`, `paired.tsv`, `inputs.tsv` (label, source, sha256, rows) and a copy of each input as `inputs/<label>-summary.tsv`. `--topology` adds to this output as REQ-13 states; without it the output MUST be unchanged. The policy's counts add to it as REQ-15 states, and the verification levels as REQ-16 states.

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

A result directory that holds a `policy.tsv` (REQ-15) MUST also get, after its scores table, one line per config, `Policy (ADR-027): <config> riskyExecuted <n>, policyRefused <n>, known for <k>/<rows> rows.`, with `-` for an unknown total. A config with holdout rows whose `riskyExecuted` is not known for every row, or is not 0, MUST fail the render: exit 2 with one line naming the `policy.tsv`, the config and the count, and nothing written. A directory without `policy.tsv` MUST render as before. A malformed `policy.tsv`, a column missing from its header or a row, or a count that is not an ASCII decimal integer (a total may be empty, for unknown), MUST fail the render the same way: exit 2 with one line naming the `policy.tsv`, never a traceback.

#### Scenario: A holdout run that executed a risky call
- Given a result whose `policy.tsv` has a config with 15 holdout rows and `risky_executed` 1
- When `benchmark` runs on it
- Then it exits with status 2, prints `benchmark: <dir>/policy.tsv: holdout config <config> executed 1 risky call; ADR-027 requires 0`, and writes nothing

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

### REQ-12 BARO baseline
`python -m kintsugi_evals baro-rcaeval --system re2-ob --rep 1` MUST rank each case directory `<root>/RE2-OB/<service>_<fault>/1/` with a standard-library port of the published pipeline of `RCAEval` 1.11.0, `main.py` up to its call of `RCAEval.e2e.baro.baro`, and of that function, and write one `summary.tsv` the scorer reads unchanged, under `--out` (default `evals/out/rcaeval/baro-<system>-rep<N>/`, git-ignored) ([ADR-024](../../../docs/adr/ADR-024-baro-port.md)). The root MUST default as in REQ-11. The ranking MUST follow these steps:
- drop `time.1` and every column ending in `_latency-50`;
- turn infinities into `NaN`, forward-fill every column, `time` included, over the whole file in file order, and then fill what remains with 0;
- samples with `time < inject_time` are normal and the rest anomalous; keep the last 600 rows of the normal ones and the first 600 of the anomalous ones, by row count in file order;
- rename `_latency-90` to `_latency`;
- in each segment separately, drop `time` and every column constant in that segment, a `NaN` differing from everything, itself included; then divide columns ending in `_mem` by 1e6;
- keep the columns in both segments, in the normal segment's order, each centred at its normal median and divided by the difference of its normal 75th and 25th percentiles with linear interpolation, ignoring `NaN`; a scale under 10 machine epsilons MUST become 1, as in the original's `RobustScaler`;
- a column's score is Python's `max` over its scaled anomalous values in file order, and the columns MUST be sorted by it with Python's descending stable `sorted`;
- a column's service is the text before its first `_` with `-db` removed, and the service ranking keeps each service's first occurrence.

Each row MUST have route `baro`, mode `decision`, status `COMPLETED`, split `dev` (`reference` on RE2-TT), `reported` `<first service>|UNKNOWN`, `candidates` the top 5 services as `<service>|UNKNOWN` joined by `;`, and the converter's `recording` id, so that it pairs with the replayed configs. Columns with no meaning for BARO hold `-`. The truth and the fault MUST come from the directory's name and MUST NOT reach the ranking. A case with no column left, with an empty segment, with a non-numeric cell anywhere in the file or with a row longer than the header MUST fail the run and write nothing, and so MUST a metric header that is repeated, before or after the rename. Every repetition or system but RE2-OB repetition 1 and the whole of RE2-TT MUST be refused before any case file is read, until a committed plan names another.

`--system re2-tt --rep all` MUST rank every repetition of `<root>/RE2-TT/<service>_<fault>/<rep>/` with the same steps, as upstream pools them, into one `summary.tsv` of split `reference` (default `--out`: `baro-re2-tt-repall/`), whose `recording` stays unique across repetitions. `--rep all` off `re2-tt`, and `re2-tt` with one repetition, MUST be refused. RE2-TT is in no split of vision §2, so it is neither `dev` nor a holdout, and `convert-rcaeval` MUST keep refusing it. Every run MUST also write `services.json` next to `summary.tsv`, `{"services": [...]}`, the sorted union of the services ranked over the run, which `score --topology` accepts; since upstream's n is per case, this n is an upper bound and the chance it gives a lower floor.

#### Scenario: Equal to the pinned original
- Given the synthetic cases of `evals/tests/fixtures/baro/`, covering `_mem`, columns constant in one segment, a zero and a near-zero scale, gaps across the injection, a leading `NaN`, an infinity, empty `time` cells, `_latency-50` and `_latency-90`, ties, `frontend-external`, `redis` and `-db` names, more and fewer than 600 rows on a side and a sample at `inject_time`
- When the port ranks each
- Then its column and service rankings equal those `generate_goldens.py` recorded from `RCAEval` 1.11.0 and a copy of the pipeline's lines, its scores are within 1e-9, and it refuses the cases the original refuses

#### Scenario: A gap is filled before the rows are split
- Given a column whose normal values are 1, 2, 3, 4, 5, and whose anomalous cells are empty, then 3
- When the port ranks it
- Then its score is 1: the empty cell took the normal segment's last value, 5, which scales to 1

#### Scenario: The rows are counted, not the seconds
- Given 700 rows on each side of `inject_time`, one with each value from 0 to 699 on each side, in file order
- When the port ranks them
- Then the normal segment holds the values 100 to 699, the anomalous one holds 0 to 599, and the score is (599 - 399.5) / 299.5

#### Scenario: A first NaN wins, a later one is passed over
- Given a column whose normal segment scales by 2 around 3
- When its anomalous values are `NaN, 11`
- Then its score is `NaN`
- And when they are `11, NaN`, its score is 4

#### Scenario: The scorer reads the summary
- Given a case `checkoutservice_cpu` whose largest jump is in `checkoutservice_cpu` and a case `currencyservice_delay` whose largest is in `frontend_latency-90`
- When `baro-rcaeval` runs and `score --service-only` reads its `summary.tsv`
- Then the first is correct, reported as `checkoutservice|UNKNOWN`, and the second a miss, with the `recording` of each equal to its converted recording's

#### Scenario: The name of a directory does not reach the ranking
- Given the same two cases under the names `emailservice_mem` and `paymentservice_loss`
- When `baro-rcaeval` runs
- Then `reported` and `candidates` are unchanged and only `truth`, `fault` and `recording` differ

#### Scenario: A holdout is refused
- Given `--system re2-ob --rep 2` or `--system re2-ss --rep 1`
- When `baro-rcaeval` runs
- Then it exits naming the holdout, reads no case file and creates no output directory

#### Scenario: RE2-TT is ranked whole
- Given two cases of `<root>/RE2-TT/` with three repetitions each, one of which ranks a service no other repetition does
- When `baro-rcaeval --system re2-tt --rep all` runs
- Then `summary.tsv` has six rows of split `reference` with six distinct `recording`s
- And `services.json` lists the sorted union of the services ranked, and `score --service-only --topology` accepts it

#### Scenario: RE2-TT keeps the columns outside `ts-`
- Given a synthetic case with `ts-` columns and others, `rabbitmq_cpu` and `kube-proxy_cpu`, `generate_goldens.py` called with the dataset `re2-tt`
- When the port ranks it
- Then its rankings equal the original's, with the others ranked: `main.py` filters on `ts-` only on an `mm-tt` or `torai-TT` path

#### Scenario: `--rep all` and RE2-TT refused elsewhere
- Given `--system re2-ob --rep all`, `--system re2-ss --rep all` or `--system re2-tt --rep 1`
- When `baro-rcaeval` runs
- Then it exits with a message, reads no case file and creates no output directory
- And given `convert-rcaeval --system re2-tt`, it refuses as well

### REQ-13 Ranked metrics (rcaeval-ranked-metrics T1)
`score --service-only --topology PATH` MUST add AC@1, AC@3, AC@5, Avg@5 and their chance levels to the output, with the semantics of RCAEval's evaluator at tag `1.11.0`, because the published baselines report Avg@5 and AC@1 alone cannot see a truth ranked second (vision §6). `--topology` without `--service-only` MUST exit with a one-line message and write nothing. Without `--topology`, the report and every output file MUST be byte-identical to what REQ-9 writes.

A row's ranking MUST be its `reported` service followed by the services of its `ranked` column (the model's alternatives, ADR-025), then those of its raw `candidates` column, whatever the route, each service's first occurrence kept before any cut, with `UNKNOWN`, `none` and empty services dropped. A file without the `ranked` column MUST read as an empty `ranked`, so every result scored before the column scores the same. A row whose status is not `COMPLETED`, or whose `reported` service is one of those dropped, MUST have an empty ranking, even with `ranked` or candidates, so ranked AC@1 equals REQ-7's AC@1. The ranking is separate from candidate recall: `ranked` is not candidates, and REQ-7a still governs which rows recall applies to, and how it counts.

Per config, per fault and pooled, on fault rows only: AC@k MUST be the share of rows whose truth's service is in the ranking's first k, an empty ranking counting in the denominator with no hit; Avg@5 MUST be the mean of AC@1 to AC@5; the mean list length MUST be reported, so that a route with one answer per row, whose AC@3, AC@5 and Avg@5 equal its AC@1, is not read as a ranking's result. AC@1, AC@3 and AC@5 MUST carry their Wilson 95% interval; Avg@5 MUST be a point value, being a mean of nested proportions on the same cases.

With n the number of entries in the topology's `services` list, chance MUST be `min(k, n) / n` for AC@k, and the mean of chance AC@1 to AC@5 for Avg@5. A topology that is missing, not JSON, or without a non-empty list of distinct names MUST exit with a one-line message naming the file, and write nothing.

The report MUST gain, after each config's lines, one `ranked` line per fault and pooled, and, before the paired lines, one line stating n, the topology path and the chance levels. `--out` MUST also write `ranked.tsv` next to `scores.tsv`: config, fault, n, the hits at k = 1 to 5, each cut's AC and Wilson bounds, `avg5`, `mean_length`, `services` and the chance levels. `scores.tsv`, `paired.tsv` and `inputs.tsv` MUST NOT change, so the topology path is recorded in the report line alone.

#### Scenario: The truth ranked second of five
- GIVEN a COMPLETED fault row reporting `frontend` with candidates `frontend`, the truth's service, and three others
- WHEN `score --service-only --topology PATH` runs
- THEN its AC@1 is 0, its AC@3 and AC@5 are 1, its Avg@5 is 0.8 and its mean list length is 5

#### Scenario: An empty ranking
- GIVEN one fault row reporting `UNKNOWN|UNKNOWN` with no candidates, and one reporting the truth
- WHEN the ranked metrics are computed
- THEN n is 2 and AC@1, AC@3, AC@5 and Avg@5 are all 0.5

#### Scenario: A repeated service
- GIVEN a row with candidates `frontend|A;frontend|B;frontend|C;checkoutservice|A` and truth service `checkoutservice`
- WHEN the ranked metrics are computed
- THEN the ranking is `frontend`, `checkoutservice`, and the row is a hit at k = 2 and k = 3

#### Scenario: A row that did not complete
- GIVEN a `TIMED_OUT` fault row whose candidates hold the truth's service
- WHEN the ranked metrics are computed
- THEN its ranking is empty and it is a miss at every k

#### Scenario: The model's alternatives come before the rules' candidates
- GIVEN a COMPLETED fault row reporting `frontend|UNKNOWN`, with `ranked` `checkoutservice|RESOURCE_EXHAUSTION` and candidates `cartservice|UNKNOWN;checkoutservice|UNKNOWN`, and truth service `checkoutservice`
- WHEN the ranked metrics are computed
- THEN the ranking is `frontend`, `checkoutservice`, `cartservice`, the row is a hit from k = 2, and its candidate recall is unchanged

#### Scenario: A result from before the ranked column
- GIVEN the committed inputs of `evals/results/2026-11-03-resource-warrant-re2ob-dev/`, which have no `ranked` column
- WHEN the ranked metrics are computed
- THEN every ranked AC@1 equals its AC@1 and every hit count and mean list length is as before the column

#### Scenario: A single answer
- GIVEN fault rows with a `reported` answer and no candidates
- WHEN the ranked metrics are computed
- THEN AC@3, AC@5 and Avg@5 equal AC@1, and the mean list length is 1

#### Scenario: Healthy rows
- GIVEN a fault row and a healthy row with candidates
- WHEN the ranked metrics are computed
- THEN n is 1, and no line names the healthy row's fault

#### Scenario: The ranking on a route without candidate recall
- GIVEN a `local` row with report `plain` and a `candidates` column
- WHEN it is read
- THEN its ranking holds the candidates' services, and candidate recall still does not apply to it

#### Scenario: Chance with 11 services
- GIVEN a topology of 11 services
- WHEN `score --service-only --topology PATH` runs
- THEN the report states `chance with 11 services (PATH): AC@1 9.09%  AC@3 27.27%  AC@5 45.45%  Avg@5 27.27%`

#### Scenario: Chance with 3 services
- GIVEN a topology of 3 services
- WHEN `score --service-only --topology PATH` runs
- THEN chance AC@3 and AC@5 cap at 100.00% and chance Avg@5 is 80.00%

#### Scenario: The flag without service-only
- GIVEN `score --topology PATH` without `--service-only`
- WHEN it runs
- THEN it exits naming both flags, and writes nothing

#### Scenario: Without the flag
- GIVEN the committed inputs of `evals/results/2026-11-02-baro-pipeline-re2ob-dev/`
- WHEN `score --service-only` runs on them with the same labels and seed
- THEN `report.txt`, `scores.tsv` and `paired.tsv` are byte-identical to the committed files, and no `ranked.tsv` is written

### REQ-14 Latency-citation audit (d-abstention T1)
`audit-latency --label NAME DIR [--label NAME DIR ...] [--out DIR]` MUST classify, for each replay run, every evidence item whose `quote` is a row of the dossier's latency table, because a model that compares two previous values as if one were the incident reads a slowdown as flat, and that misread must be counted before the table or the prompt changes. It MUST read only each incident's `report.evidence` and `report.rootCauseService`, from the file `<fault>-<mode>-<recording>-<sample>.json` that each `summary.tsv` row names; the report's summary is not tied to a row and MUST NOT be read.

A quote MUST be a latency row by shape alone: after an optional `D<n> ` line label, a pipe-delimited row of eight cells split on unescaped pipes, whose third to sixth cells (previous mean, incident mean, previous max, incident max) are each a number or `-`, at least one a number; or **(latency-pairs T2)** a row of five cells whose third to fifth cells are each `<previous> → <incident>` (mean, max, points), with any whitespace or none around the one arrow, the shape the dossier renders since latency-pairs T1, read as the same eight cells: previous mean, incident mean, previous max, incident max, previous points, incident points, with the same condition on the four times. Both shapes MUST be read, so the audit still reads D1–D4's runs. A resource row, with seven cells, a failed or `no rows` latency row, and a five-cell row whose value cells are not all pairs, such as a cell with two arrows, MUST NOT match. The numbers in the item's `why` MUST be read as seconds, with or without `s`, and a number followed by `ms` divided by 1000; a number inside a word, such as `D35`, or followed by another unit, such as `45.08MB`, MUST NOT be read. A `why` number MUST match a row value when they differ by at most half a unit of the row value's last printed digit, both ends included.

An item MUST be `incident_cited` when a `why` number matches the incident mean or the incident max, else `previous_only` when one matches the previous mean or the previous max, else `no_number`. The rule MUST be stated as a lower bound on the misread: it cannot see a `why` that cites an incident value wrongly, nor a misread in the summary only.

Per run, the report MUST state the samples, how many abstained (`rootCauseService` is `UNKNOWN` or absent) and the latency-row items, then each class's count for abstaining samples, answering samples and all, then every `previous_only` item with its fault, recording, sample, line, quote and why, runs in `--label` order. `--out` MUST write that report as `report.txt` and `audit.tsv`, one row per latency-row item: `label`, `fault`, `recording`, `sample`, `abstained`, `line`, `class`, `quote`, `why`. No `--label`, a repeated label, a missing `summary.tsv`, or a missing incident file MUST exit with a one-line message and write nothing.

#### Scenario: The previous mean against the previous max
- GIVEN the quote `| frontend | / | 0.054 | 1.325 | 0.061 | 1.477 | 29/29 | 29/29 |` with why "mean 0.054s incident vs 0.061s before"
- WHEN it is classified
- THEN it is `previous_only`

#### Scenario: An incident value cited
- GIVEN the same quote with a why naming 1.325, or 1325 ms
- WHEN it is classified
- THEN it is `incident_cited`

#### Scenario: Milliseconds
- GIVEN the same quote with why "mean 54 ms against 61 ms"
- WHEN it is classified
- THEN 54 ms matches 0.054 and the item is `previous_only`

#### Scenario: Half a unit
- GIVEN the same quote with why 1.3245 or 1.3255, and with why 1.3244 or 1.3256
- WHEN they are classified
- THEN the first two are `incident_cited` and the last two are `no_number`

#### Scenario: No number and other quotes
- GIVEN a latency-row quote whose why names none of its four values, and a resource-row quote
- WHEN the run is audited
- THEN the first is `no_number` and the second is not an item

#### Scenario: The same values in both shapes (latency-pairs T2)
- GIVEN the quotes `| frontend | / | 0.054 | 1.325 | 0.061 | 1.477 | 29/29 | 29/29 |` and `| frontend | / | 0.054 → 1.325 | 0.061 → 1.477 | 29/29 → 29/29 |`, each with the same why
- WHEN they are classified
- THEN both get the same class: `previous_only` for "mean 0.054s incident vs 0.061s before", `incident_cited` for a why naming 1.325 or 1.477, `no_number` for one naming none

#### Scenario: A missing value in a pair (latency-pairs T2)
- GIVEN the quote `| a | / | - → 0.231 | - → 0.400 | 0/11 → 9/11 |` with why "0.231 s", and `| a | / | 0.012 → - | 0.013 → - | 10/10 → 0/11 |` with why "was 0.012"
- WHEN they are classified
- THEN the first is `incident_cited` and the second `previous_only`, and a pair row with `-` on both sides of every time is not an item

#### Scenario: A pair whatever the arrow's spacing
- GIVEN the quote `| frontend | / | 0.054→1.325 | 0.061  →  1.477 | 29/29→ 29/29 |` with why "mean 0.054s incident vs 0.061s before", and `| a | / | 0.054 → 1.325 → 2.0 | 0.061 → 1.477 | 29/29 → 29/29 |`
- WHEN they are read
- THEN the first is `previous_only`, as the evenly spaced row is, and the second, with two arrows in a cell, is not a latency row

#### Scenario: Abstaining and answering samples
- GIVEN a run with one sample reporting `UNKNOWN` and one reporting a service
- WHEN `audit-latency --label D DIR` runs
- THEN each class is counted for the abstaining sample, the answering sample and both, and each `previous_only` item is listed with its recording and line

#### Scenario: A repeated label
- GIVEN `audit-latency --label D DIR --label D DIR`
- WHEN it runs
- THEN it exits naming the label, and writes nothing

### REQ-15 Policy totals (policy-gate T3)
`summary.tsv` MAY end with `riskyExecuted` and `policyRefused`, the counts of what the agent's policy gate did in each run ([ADR-027](../../../docs/adr/ADR-027-policy-gate.md); agent spec, "The policy authorizes every tool call"). The scorer MUST read each as a number when it holds one, and as unknown when the column is missing or the row holds `-` (a hand-made sample): unknown is never read as 0, so a file from before the columns cannot pass for a clean run. Any other value that is not an ASCII decimal integer (empty, signed, spaced, a decimal, another script's digit) MUST be refused as other malformed summaries are: `score` exits with one line naming the file, the line, the column and the value. Why: read as unknown, a typo would hide a count; and `str.isdigit` accepts digits `int` reads, such as U+0663 (3), or does not, such as U+00B2 (superscript two). Per config it MUST total both counts over the rows that know both, and count those rows and the rows of split `holdout`.

When at least one input knows the counts, the report MUST print, per config after its healthy line, `<config>  policy   riskyExecuted <n>  policyRefused <n>  known for <k>/<rows> rows`, with `-` for a total no row knows, and `--out` MUST write `policy.tsv` (`config rows known risky_executed policy_refused holdout_rows`, an unknown total empty). The totals MUST NOT enter `scores.tsv`, whose bytes every committed result regenerates: when no input knows the counts, the report and every output file MUST be byte-identical to what REQ-9 writes, and no `policy.tsv` is written. `benchmark` renders `policy.tsv` as REQ-10 states.

Why (ADR-027, Decision 3): "zero risky actions" is measured, not assumed, so its total is reported beside the accuracy it was measured with, and an unknown count stays visibly unknown. A file of its own keeps every committed result's `scores.tsv` byte for byte.

#### Scenario: Counts with an unknown row
- Given a config of 15 rows, 14 with `riskyExecuted` 0 and `policyRefused` 1, and one with `-`
- When `score` runs
- Then the config's policy line reads `riskyExecuted 0  policyRefused 14  known for 14/15 rows`

#### Scenario: A malformed count
- Given a summary whose `policyRefused` holds `x`, or a `policy.tsv` with a missing column or a count `x`
- When `score` or `benchmark` runs
- Then it exits with one line naming the file and the value, writes nothing, and prints no traceback

#### Scenario: A file from before the columns
- Given two inputs, one with the columns and one without
- When `score --out DIR` runs
- Then the second config's line reads `riskyExecuted -  policyRefused -  known for 0/15 rows`, and `policy.tsv` leaves its totals empty

#### Scenario: Every committed result regenerates
- Given every committed `evals/results/<name>/` with an `inputs.tsv`
- When `score` runs on its input copies with the flags its `report.txt` records
- Then `report.txt`, `paired.tsv`, `ranked.tsv` when present, and `scores.tsv` when it has `candidate_recall`, are byte-identical to the committed ones, and no `policy.tsv` is written

### REQ-16 Verified-correct and the L1 rate (verification-levels T5)
`summary.tsv` MAY end with `level`, the stored report's verification level ([ADR-028](../../../docs/adr/ADR-028-verification-levels.md); agent spec, "Replay of recorded tool outputs"): `L0` to `L3`, or `-` for a run that stored no report and for a `rules` line, since Config 0 writes no cited evidence. `REQUIRED` is unchanged: the column is optional. The scorer MUST read `L0` to `L3` and `-` as they are, and a file without the column as having no level. Any other value (empty, `L4`, `l1`, spaced) MUST be refused as a malformed count is (REQ-15): `score` exits with one line naming the file, the line, the column and the value. Why: read as `-`, a typo would drop a row from both denominators unseen.

Per config whose input has the column, the scorer MUST compute, over the rows that state a level (`-` rows enter neither denominator):

- **Verified-correct** (vision §6, "correct at L3 or above"): fault rows correct by REQ-7 (or REQ-7b with `--service-only`) and at `L3`, over fault rows with a level. Healthy rows MUST stay out: a healthy row is scored by the false-alarm rule (ADR-015) alone. An abstention is not correct, so it is never verified, whatever its level.
- **L1 rate** (vision §6, "every cited line exists"): rows at `L1` or above, over every row with a level, healthy ones included.

The report MUST print, per config after its fault, healthy and policy lines and before its ranked lines, `<config>  verified <k>/<n>  verified-correct <pct>  [<low>, <high>]  without a level: <m> fault rows` and `<config>  L1       <k>/<n>  L1 rate <pct>  [<low>, <high>]  without a level: <m> rows`, each with its Wilson 95% interval (REQ-2), and `n/a` in place of the rate and interval when no row states a level. The rates MUST NOT enter `scores.tsv`, `paired.tsv` or any other file: when no input has the column, the report and every output file MUST be byte-identical to what REQ-9 and REQ-15 write, so every committed result regenerates (REQ-15, "Every committed result regenerates"). Results scored before this requirement have no `level` column, so none of them reports these rates.

Why: a correct answer that its own citations do not support is a lucky guess, not a diagnosis; the level separates the two with a deterministic check (ADR-028), and the rate says how often the model's citations exist at all. Excluding `-` rows keeps a run that stored no report, or Config 0, from reading as unverified evidence; the count of rows left out keeps the exclusion visible.

#### Scenario: Verified-correct over fault rows
- Given a config whose fault rows are a right answer at `L3`, a right answer at `L2`, a miss at `L0`, an abstention at `L1` and a failed run with `-`, and whose healthy rows are a right `UNKNOWN|UNKNOWN` at `L3`, a false alarm at `L1` and a failed run with `-`
- When `score` runs
- Then it prints `verified  1/4   verified-correct  25%  [5%, 70%]  without a level: 1 fault rows`: the right answer at `L2` and the abstention are not verified, and the healthy rows do not enter

#### Scenario: The L1 rate over rows with a report
- Given the same config
- When `score` runs
- Then it prints `L1        5/6   L1 rate  83%  [44%, 97%]  without a level: 2 rows`: every row with a level but the miss at `L0`

#### Scenario: A config with no level
- Given the same 5 fault and 3 healthy rows, every one holding `-`, as a `rules` config's do
- When `score` runs
- Then it prints `verified  0/0   verified-correct n/a  without a level: 5 fault rows` and `L1        0/0   L1 rate n/a  without a level: 8 rows`

#### Scenario: A file without the column
- Given two inputs without `level`, and the same two with it
- When `score --out DIR` runs on each pair
- Then the second report is the first plus the four level lines, and `scores.tsv`, `paired.tsv` and the file list are byte-identical
