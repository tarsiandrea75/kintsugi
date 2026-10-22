# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-20 (session 01; the session ran from 2026-10-19)

## Milestone

**M2 — First agent: in progress.** Since the last state: the evidence dossier (ADR-011, `odd/tasks/evidence-dossier.md`), the cloud development route (ADR-012, `odd/tasks/cloud-route.md`), and this session's six features. Open: the M2 check, "F1–F3 correctly diagnosed with a local model" (`agent-loop` T5c3). The cloud route now diagnoses F1–F3 in dossier mode (replay, 5 samples per fault: 5/5, 5/5, 4-5/5); the local route (`granite4.1:8b`) does not yet (`f3-caller-config` T6: explorer 1/3, dossier 0/3, timeouts on this CPU).

## Last action

This session (journal `docs/journal/2026-10-20-01.md`), all on local branches, nothing pushed:
- `f3-caller-config`: F3 diagnosed on the cloud route (0/6 → 3/4); not confirmed on the local route.
- `evidence-settling`: the last restore time is kept across runs, so a run's F1 no longer sees the previous run's F3 evidence.
- `replay-harness`: `infra/scripts/replay-agent.sh` replays recorded faults through the real loop; dossier mode replays fully, explorer mode does not (exact matching).
- `dossier-latency`: the dossier shows each service's answer time; dossier F1 0/5 → 4-5/5 on the replay, 3/3 in the lab.
- `fix/cloud-cut-report`: Anthropic's `max_tokens` stop is recognised as a cut report.
- `report-length`: the cloud report cap is 768 (the local one stays 512); asking for at most 3 evidence items cost F3 and was reverted.
- Cloud spend so far about $0.92 of the human's $5. Current branch `feat/report-length` (`2fc57c6`), clean.

## Next action

1. The human decides what follows. Candidates:
   - the local route: why `granite4.1:8b` misses F3 and why its dossier runs time out on this CPU (the M2 check depends on it);
   - an explorer-mode replay design (exact matching cannot serve the model's free arguments);
   - explorer mode's F1, which reads no dossier and so no latency.
2. Waiting for the human (needs sudo): `sudo apt-get install --only-upgrade docker-compose-plugin=5.6.0-1~ubuntu.24.04~noble`, then record "host Compose = v5.6.0" in ADR-009 and `docs/setup.md`.
3. Waiting for the human: the "to review" decisions in `odd/tasks/runbook-rag.md` and `odd/tasks/mcp-tools.md`.

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`
- `feat/gateway-mock` → `-01-behavior` → `-02-authorize` → `-03-payment-gateway`
- `feat/lab-observability` → `-01-decisions` → `-02-metrics` → `-03-stack`
- `feat/fault-f2` → `-01-gateway` → `-02-payment-failed` → `-03-payment-gauge` → `-04-order` → `-05-check`
- `feat/fault-f3` → `-01-client-metrics` → `-02-check`
- `feat/fault-f4` → `-01-inventory-data` → `-02-inventory-api` → `-03-order-inventory` → `-04-check`
- `feat/service-runbooks`
- `feat/event-recording` → `-01-inventory` → `-02-payment` → `-03-order` → `-04-gateway-check`
- `feat/fault-injector` → `-01-core` → `-02-api` → `-03-lab-control` → `-04-wiring`
- `feat/mcp-tools` → `-01-observability-tools` → `-02-evidence-tools` → `-03-wiring`
- `feat/runbook-rag` (one branch)
- `feat/agent-loop` → `-01-state` → `-02-tools` → `-03-loop` → `-04-check`
- `feat/evidence-dossier-01` → `-02` → `-03` → `-03b` → `-03c` → `-03d` → `-04a` → `-04b` → `-05a` → `-05b` → `-06`
- `feat/cloud-route` → `-02` → `-03` → `-04` → `-05`
- `feat/f3-caller-config` → `feat/evidence-settling` → `feat/replay-harness` → `feat/dossier-latency` → `fix/cloud-cut-report` → `feat/report-length` (current, clean)

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- No posts, no pushes (decided 2026-10-07). The repository stays local. Backups are full git bundles plus a sha256 checksum, kept outside the laptop (`kintsugi-YYYY-MM-DD-full.bundle`).

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.
- Review per commit. The stop hook's whole-history candidate (base `49d55bd`) is skipped, because it exceeds the reviewer context budget.

## Open questions

- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service still stores an AUTHORIZED payment after order-service has timed out (observed).
- Datasource socket timeout. Without one, a database that stops responding keeps the count refresh busy until TCP gives up. The gauges still show NaN after 10 s. A socket timeout would apply to every query, including F4's slow queries.
- Reconciliation and reservation release: the evidence now exists in the event tables (order/payment and order/reservation mismatches); deciding what to do with it is a future feature.
- Runbooks avoid naming the dashboard ("Lab — Faults", uid `lab-f1`) because of the leakage grep; a neutral dashboard name would allow linking it.

## Follow-ups

This session (details in each feature document):
- report-length: the local route still asks for 1 to 5 evidence items under a 512 cap; the prompt's evidence wording is not pinned by a test.
- dossier-latency: a window over 180 minutes loses the latency section (the tool's 360-minute limit); latency means are unweighted point means; the dossier stress ceiling is now about two thirds of `num-ctx`.
- replay-harness: explorer replays are not valid measurements; SUGGESTIONs on the driver and scripts (summary append on a reused out dir, second-resolution out dir, script option-parsing tests).
- evidence-settling: the dossier's previous window still reaches about 24 minutes back; `status_summary` counts all orders; windows are not anchored to the injection time.
- f3-caller-config: a `service_config` tool; dossier facts (failing URL); runbook retrieval; `service_restarts` in explorer TRIAGE.
- cloud-route: `AnthropicSchema` edge cases; `KINTSUGI_AGENT_MODE` leaking into tests.

agent-loop (details in `odd/tasks/agent-loop.md`):
- T5c13 review: the branch for an earlier failure with no `reason` is untested (WARNING); the reason is read from the first `reason=` line; "change the arguments" is wrong advice after a transient failure.
- T5c14 review: no test for an item with both a wrong tool and a bad quote.
- A phase's plan `why` over 300 characters makes the plan invalid when the answer is cut at 384 tokens (F3, run 12).
- The 45 min incident budget does not hold the worst case (every `ACT` and `REPORT` call at its deadline).
- A runbook chunk can escape the summary cap.
- A call that failed transiently is refused as a duplicate on retry.
- `<init>`-like search terms count as placeholders.
- `DefaultBudgetsTest` only adds up constants.
- The recorded `tools-list.json` holds old tool descriptions.
- Log templates mask every number, so `answered=429` and `answered=200` merge into one group (review WARNING on T5c10).

runbook-rag (details in the feature document):
- Retrieval: the gateway runbook has no rate-limit section, so a 429-worded query ranks it 2nd.
- `start_lab`'s `knowledge_only_failure` may read a stale indexer state, and it has no test.
- The separator test uses invisible Unicode literals.
- Token counts use cl100k as an approximation of nomic's tokenizer.
- Any container on this host can reach Ollama through the proxy (accepted, recorded in ADR-002).

mcp-tools (details in the feature document):
- "connection pool busy" is also reported when connection attempts are still running at the 2 s timeout (WARNING).
- The level filter depends on the console log format.
- Non-integer arguments are rejected by Spring AI before validation, so they get no call log.
- Restarts less than one step apart merge.
- The `verify-mcp.sh` probe has no wall-clock bound and its failure branches were not exercised.
- Small doc and test suggestions from the T5 reviews.
- No lab service has a restart policy or a memory limit.


Lab services:
- `POST /orders` and `POST /payments` are not idempotent, so a retry after a timeout can create duplicates. This is a candidate fault.
- gateway-mock: a reset does not shorten in-flight requests.
- The order-service 400 safety net is untested by design.
- No test proves that the `gateway.authorize.inflight` gauge keeps its counter after a GC.
- The status-count gauges have a 100 ms wall-clock test bound, which may be flaky on a loaded host. An unknown status row is untested.
- The 429 WARN line is logged in `HttpPaymentGateway`, before the payment is saved.
- No retry or `Retry-After` handling on a 429; this is out of scope for F2.

Verify scripts (review suggestions):
- The fault-variable list in `lib.sh` duplicates the catalog; `start_lab` bypasses the compose helper; the Prometheus invisibility check is a substring match; a 204 race can follow an injector transport failure.
- `LEAK_WAIT_SECONDS` in `verify-f4.sh` is not derived from `F4_RESERVATION_DELAY`.
- The `lib.sh` caller contract does not mention `trap on_exit EXIT`.
- `expect_paid_order` takes an awkward label fragment.
- The rejection phrase is a literal that is not linked to the panel filter, and it is not escaped.
- The reset-on-failure path has not been observed.

Smoke-test harness (non-blocking):
- The report is skipped on a partial run.
- There is no per-prompt timeout.
- The validity rule does not check the zone.
- The report omits `think=false`, misses 12-hour formats and breaks on backticks.
- ADR-003 omits P7's minimum call count.

Carried over:
- A test for the `demo` profile.
- `DemoRunner` with `HttpConnectTimeoutException`.
- Non-ASCII characters in the Windows console.

## Blockers

None.
