# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-16 (session 01, overnight, run autonomously with the human's go-ahead)

## Milestone

**M2 — First agent: in progress.** Done: the MCP tools server, RAG on the runbooks, and the agent loop through T5c14. Open: the M2 check, "F1–F3 correctly diagnosed with a local model" (`agent-loop` T5c3). The best run is 2/3; the check needs the human's decisions below.

## Last action

- **Host back to speed after the restart:** an Ollama probe read prompts at 10.6-11.1 tokens/s and generated at 4.1-4.2 tokens/s; latencies stayed level through runs 11-13.
- **Runs 11-13** (`infra/scripts/out/verify-agent-20261015T192159Z/`, `-20261016T024609Z/`, `-20261016T042636Z/`): 2/3, 0/3, 1/3.
  - F1: 1 MATCH, 2 timeouts on a phase's first answer (over the 300 s `ACT` deadline).
  - F2: 2 MATCH, 1 report invalidated by an evidence item citing the wrong tool, with the right cause.
  - F3: 0 of 3; the model always blames gateway-mock and never looks at order-side evidence.
- **T5c13** (`f29401f`): a repeat of a failed call is refused with the first call's outcome and reason. Review approved.
- **T5c14** (`fc3a00c`, decided by the agent overnight, extending the human's T5c11 rule): an evidence item citing the wrong tool is dropped, not fatal. Review approved.
- Branch `feat/agent-loop-04-check`, clean tree, nothing pushed. The next review base is `4801fb7`; later commits are docs only.

## Next action

1. **Decided 2026-10-16: the `ACT` deadline is 420 s (T5c15, `767acdd`, reviewed); the incident budget stays 45 min.** The human still decides, with runs 11-13 as evidence (details in `odd/tasks/agent-loop.md`, Next step):
   - F3: a prompt or tool change toward order-side evidence (close to teaching the test), or record F3 as a known limit of the local model;
   - the replay harness, to measure several samples per fault without the lab.
2. Then the fourteenth full run (T5c3).
3. Waiting for the human: add `AGENT_DB_PASSWORD=agent_app` to the protected `infra/.env.example`, and the other lines listed in the 2026-10-08 state:
   - `KINTSUGI_REPO_DIR=`
   - `DOCKER_GID=`
   - `POSTGRES_BOOTSTRAP_PASSWORD=postgres`
   - `LAB_APP_DB_PASSWORD=lab_app`
   - `FAULTS_DB_PASSWORD=faults`
   - `KINTSUGI_AGENT_DB_PASSWORD=kintsugi_agent`
   - `KNOWLEDGE_BOOTSTRAP_PASSWORD=postgres`
   - `KNOWLEDGE_INDEXER_DB_PASSWORD=knowledge_indexer`
   - `KNOWLEDGE_AGENT_DB_PASSWORD=knowledge_agent`
   - `OLLAMA_PROXY_BIND_IP=172.17.0.1`
4. Waiting for the human (needs sudo): `sudo apt-get install --only-upgrade docker-compose-plugin=5.6.0-1~ubuntu.24.04~noble`, then record "host Compose = v5.6.0" in ADR-009 and `docs/setup.md`.
5. Waiting for the human: the "to review" decisions in `odd/tasks/runbook-rag.md` and `odd/tasks/mcp-tools.md`.

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
- `feat/agent-loop` → `-01-state` → `-02-tools` → `-03-loop` → `-04-check` (current, clean)

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
