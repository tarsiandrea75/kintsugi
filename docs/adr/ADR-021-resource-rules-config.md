# ADR-021: Config 0R, resource facts and the rule R-RES

## Status

Accepted. The thresholds of R-RES are fixed by this ADR before any converted resource series is read, and before any lab resource series is looked at beyond a check that the metrics exist (`odd/tasks/resource-facts.md`, AC3). A later change needs a revision of this ADR, and a dev result measured after it is labelled exploratory. Config 0 and ADR-016 are unchanged.

## Date

2026-11-01

## Context

- **The rules see no resource fault.** On RE2-OB dev, Config 0 scores 4/30, and 19 of its 30 candidate lists are empty, almost all on cpu, disk and socket faults (`odd/tasks/hybrid.md` T10). The dossier holds latency, traffic, logs and events, not resources (ADR-020, Consequences). ADR-016 records that Config 0 never returns `RESOURCE_EXHAUSTION`.
- **H is capped by the rules' recall.** H reached its ceiling of 5/30, so the stop rule (vision §7) cannot be met by a better model on these facts.
- **The data exists on both tracks.** The four lab services export `process_cpu_usage`, `jvm_memory_used_bytes` and `process_files_open_files` (checked live on 2026-11-01). RE2-OB's `simple_metrics.csv` has `<svc>_cpu`, `<svc>_mem` and `<svc>_socket` for all 11 services, and `_diskio` for 4 (one dev header read).
- **Config 0 cannot change.** `diagnose()` is frozen at `5797d16` (ADR-016).
- **Leakage (ADR-019).** The rules H uses must be exactly those a rules-only config runs. A resource candidate added only to H's list would credit the model with the rule's gain.
- **The converter's leakage guard** (`evals/kintsugi_evals/rcaeval/leakage.py`) rejects the fault words `cpu`, `mem`, `disk`, `delay`, `loss` and `socket` as whole tokens, and an underscore separates tokens: `cpu_usage` would be rejected, `memory_used` would not.

## Decision

### The metrics

`query_metric` gains three metrics, with the same arguments on both tracks as `latency_avg` (`{metric, service, sinceMinutes: 2N}`):

| Metric | Lab (PromQL over the `service` label) | RE2-OB column |
|---|---|---|
| `processor_usage` | `process_cpu_usage` | `<svc>_cpu` |
| `memory_used` | `sum(jvm_memory_used_bytes)` | `<svc>_mem` |
| `open_files` | `process_files_open_files` | `<svc>_socket` |

- **The names pass the guard unchanged.** The feature document's first draft said `cpu_usage`. It is `processor_usage`, so the guard stays as strict as it is and needs no exception. A metric name that every case calls carries no information about the case, but a guard with exceptions is harder to trust than a name.
- **Units differ between the tracks.** Lab CPU is a 0–1 share of the host; RE2-OB's is its exporter's unit. Memory is in bytes on the lab. `open_files` counts every file descriptor on the lab, sockets included, and the socket count on RE2-OB. The tool's description says so. R-RES therefore reads only ratios within one series, never absolute values.
- **Disk is out.** The lab has no disk exporter, and `_diskio` covers 4 of 11 RE2-OB services. A disk fault reaches the agent only through latency and logs. Recorded limit.

### The dossier

After the latency loop, the gatherer makes one `query_metric` call per service and per metric. Each series' points are split at `incident.from()` into the previous and incident windows, as latency is. Each window keeps its point count, its count of points with a value, its mean and its max. A failed call is a failed fact, as everywhere.

The committed lab recordings have no resource calls. They answer them `not recorded in this replay`, so their resource facts are failed and R-RES cannot fire on them: Config 0R equals Config 0 on the lab replays.

### Definitions

- **Usable window:** it holds at least 2 points with a value, and at least half as many points with a value as the fuller of the two windows holds points. A series that started late, stopped, or is mostly `NaN` in one window cannot be compared.
- **Resource ratio of a series:** its incident mean divided by its previous mean, when both windows are usable and the previous mean is above 0. Otherwise the series has no ratio.
- **Resource-abnormal series:** both hold:
  - its ratio is at least **k = 2**;
  - its incident mean is above its previous max. The rise leaves the baseline's own range, which needs no unit and no absolute floor. It guards against a near-idle series whose mean doubles by noise.
- **Resource ratio of a service:** the largest ratio over its resource-abnormal series. A service with none is not resource-abnormal.

**Why k = 2, from first principles.** It is the factor ADR-016 already uses for latency, so the two rules judge a rise the same way. A resource fault is a stress process, a memory hog or a descriptor leak added to a steady load; it is expected to at least double its series within the 10-minute incident window. A steady window's mean moves with GC and request mix, but those rarely double it, and the max guard catches a series that is noisy before the fault. It is a judgement call fixed a priori: a miss caused by it is recorded, not fixed on dev.

### The rule

Config 0R runs Config 0's rules unchanged, plus one:

1. R-REACH, as in ADR-016.
2. R-DOWN, as in ADR-016.
3. **R-RES.** Some service is resource-abnormal. The verdict is the service with the largest resource ratio, category `RESOURCE_EXHAUSTION`. Ties go to the topology's service order, as in R-REACH and R-DOWN. The verdict's facts name the series, both means, the previous max and the ratio.
4. R-NONE, as in ADR-016.

R-RES runs after R-REACH and R-DOWN, so 0R answers exactly where 0 answers and can only replace an abstention. A resource fault that makes its service slow is still found by R-DOWN, with a latency category; the service-level score does not change.

### Candidates

Under 0R, `candidates()` returns Config 0's list unchanged, then the resource-abnormal services, largest resource ratio first (ties as above), each as `service|RESOURCE_EXHAUSTION` with rule `R-RES`. Under 0 it is unchanged.

- **No service twice.** A service already in Config 0's list is not added again, whatever its category there: it keeps Config 0's entry and place.
- **Cap.** The whole list stays capped at 5, Config 0's entries first, so no resource candidate is added when Config 0 already lists 5.
- **The first candidate.** ADR-019's "the first candidate is the verdict" holds under 0R whenever Config 0 answers. When only R-RES answers, Config 0's list may still hold abnormal services that no rule chose, and 0R's verdict then follows them.

### Selection

- A `rules` version selects the config: `v1` is Config 0, `v2` is Config 0R. It is passed like the topology (`kintsugi.agent.rules`, default `v1`) and `replay-agent.sh --rules v1|v2`.
- The `summary.tsv` `model` column records it (`rules-v1`, `rules-v2`), for the `rules` route and for the hybrid's candidates.
- **HR** is the hybrid (ADR-019) with `--rules v2`: Qon picks among 0R's candidates.

### What each difference measures

On the same cases, paired:

- **0R − 0** is the contribution of the resource facts and R-RES, with no model.
- **HR − 0R** is the model's contribution over the rules it is given. This is the stop-rule comparison on RCAEval dev (vision §7).
- **HR − H** mixes the two and is reported, not used for a decision.

## Alternatives considered

- **Resource candidates in H only.** Rejected (the human's choice, 2026-11-01). It breaks ADR-019's leakage principle: H − 0 would credit the model with the rule's gain.
- **Changing Config 0.** Rejected. Its verdicts are recorded on lab dev, holdout-1 and RE2-OB dev; changing them makes a new config (ADR-016), so it is one, with its own name.
- **Absolute thresholds** (a CPU share, a byte count). Rejected. The units differ between the tracks, and a threshold for one would be a guess for the other.
- **A z-score against the previous window.** Rejected for now. A steady previous window has a tiny standard deviation, so almost any change becomes extreme; the ratio and the max guard are easier to check by hand.
- **Keeping `cpu_usage` and exempting it in the guard.** Rejected; see "The names pass the guard unchanged".
- **R-RES before R-DOWN.** Rejected. It could change an answer Config 0 gets right, and 0R − 0 would then mix gains and losses of the order.

## Consequences

### Positive

- `RESOURCE_EXHAUSTION` becomes a reachable answer for the rules, so H's ceiling on RE2-OB is no longer set by missing facts.
- 0R − 0 and HR − 0R keep the rule's and the model's gains apart.
- Config 0 and its results stay valid as recorded.

### Negative

- The lab replays answer the new calls `not recorded`, so their `unrecorded` and `coverage` columns rise and 0R says nothing new on the lab until cases are recorded again.
- `open_files` means different things on the two tracks, and memory under a JVM is a sawtooth. R-RES may miss memory faults whose rise stays inside the previous max.
- Disk faults stay without a resource fact.
- k and the usable-window rule are judgement calls. The dev results are exploratory (ADR-016).
