# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-08 (session 02)

## Milestone

**M2 — First agent: in progress.** M1 is complete. Two M2 items are done: the MCP tools server and RAG on the runbooks.

## Last action

`runbook-rag` is done ([feature document](../odd/tasks/runbook-rag.md), [ADR-002](adr/ADR-002-pgvector-knowledge-base.md), [spec](../openspec/specs/knowledge-base/spec.md)).
- **Embedding model:** `nomic-embed-text`, chosen by the human, at digest `0a109f42…`, 768 dimensions.
  - It is configurable, but the index records the model name, digest, dimensions and prefixes.
  - `search_runbooks` refuses to search on any mismatch. This was verified live with `mxbai-embed-large`.
- **Store:** a separate pgvector container, `knowledge-db` (`0.8.7-pg18-trixie` pinned by digest), not published on the host.
  - `knowledge_indexer` owns the data; `kintsugi_agent` has SELECT only.
  - Neither role is a superuser, and the store holds no `faults` database.
- **Indexer:** `knowledge/`, a one-shot job.
  - It indexes only `docs/runbooks/*.md`, mounted read-only, behind a leakage gate `\b(F[1-8]\b|fault|inject)`.
  - It splits by heading, with at most 800 tokens per chunk, and skips files whose hash is unchanged. A run commits atomically.
  - 51 chunks in about 50 s.
- **Tool:** `search_runbooks` is the 8th mcp-tools tool. Chunks come back as `untrustedText`; the `section` field is sanitized and marked untrusted.
- **Ollama from containers:** an `ollama-proxy` socat container, chosen by the human, binds only to `172.17.0.1:11434`; Ollama stays on loopback. A failed bind leaves the rest of the lab working.
- **Checks:**
  - mcp-tools: 212 tests; knowledge: 31;
  - `verify-rag.sh`, `verify-mcp.sh` and `verify-f1.sh` … `verify-f4.sh` pass.
- **Status:** every range is reviewed and approved. Nothing is pushed.
- **Process deviations:**
  - the feature stayed on one branch, `feat/runbook-rag`, with per-range reviews, instead of a feature-branch chain;
  - T4's tool code was written before its tests; a mutation check replaced RED.

## Next action

1. Waiting for the human: review the decisions marked "to review" in `odd/tasks/runbook-rag.md` and `odd/tasks/mcp-tools.md`.
2. Waiting for the human (approved, needs sudo): `sudo apt-get install --only-upgrade docker-compose-plugin=5.6.0-1~ubuntu.24.04~noble`, then record "host Compose = v5.6.0" in ADR-009 and `docs/setup.md`.
3. Waiting for the human: add these lines to the protected `infra/.env.example`:
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
4. M2 continues:
   - the agent loop with `IncidentState` and a JSON report with cited evidence, calling mcp-tools as an MCP client;
   - then the Python eval harness for the M2 check (F1–F3 correctly diagnosed).

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
- `feat/runbook-rag` (one branch; see the process deviation above)

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- No posts, no pushes (decided 2026-10-07). The repository stays local. Backups are full git bundles plus a sha256 checksum, kept outside the laptop (`kintsugi-YYYY-MM-DD-full.bundle`).

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.

## Open questions

- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service still stores an AUTHORIZED payment after order-service has timed out (observed).
- Datasource socket timeout. Without one, a database that stops responding keeps the count refresh busy until TCP gives up. The gauges still show NaN after 10 s. A socket timeout would apply to every query, including F4's slow queries.
- Reconciliation and reservation release: the evidence now exists in the event tables (order/payment and order/reservation mismatches); deciding what to do with it is a future feature.
- Runbooks avoid naming the dashboard ("Lab — Faults", uid `lab-f1`) because of the leakage grep; a neutral dashboard name would allow linking it.

## Follow-ups

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
