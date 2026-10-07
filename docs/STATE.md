# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-07 (session 05)

## Milestone

**M1 — The lab: complete.** Every M1 item is done, and the check "every fault F1–F4 is visible in Grafana" passes through four verify scripts. Next milestone: M2 — First agent.

## Last action

`fault-injector` is done ([feature document](../odd/tasks/fault-injector.md), [ADR-009](adr/ADR-009-fault-injector-docker-control.md), [spec](../openspec/specs/fault-injector/spec.md)).
- **What it is:** `lab/fault-injector` on `127.0.0.1:18084`. F1 and F2 go through the gateway-mock admin API; F3 and F4 are real compose redeploys through the Docker socket.
- **Truth:** the true cause is stored in the `faults` database, owned by a non-superuser `faults` role. No API ever returns it.
- **Database roles:** the services moved to a non-superuser `lab_app` role. `postgres` is the bootstrap superuser, used only by the init scripts.
- **Invisible to the agent:** Alloy drops the injector's logs and Prometheus does not scrape it.
- **Checks:** `verify-f1.sh` … `verify-f4.sh` inject through the injector. They assert the truth row, that `lab_app` is not a superuser and cannot connect to `faults`, and that the injector is absent from Loki and Prometheus. All pass.
- **Tests:** fault-injector 89, order-service 175, payment-service 86, inventory-service 70, gateway-mock 76, agent 37.
- **Status:** every range is reviewed and approved. Nothing is pushed (local only).
- **Running the lab by hand now needs** `export DOCKER_GID=$(stat -c %g /var/run/docker.sock)` and `export KINTSUGI_REPO_DIR=$(git rev-parse --show-toplevel)` before any `docker compose` command. An old lab volume needs `down -v` once (new roles).

## Next action

1. Waiting for the human: align Docker Compose versions. The injector ships v5.6.0 and the host has v5.1.3; the two write different image-digest labels, so the host's next `up` recreates the services the injector touched once. That is noise in the future deploy history.
2. Waiting for the human: add the new variables to the protected `infra/.env.example` (`KINTSUGI_REPO_DIR`, `DOCKER_GID`, `POSTGRES_BOOTSTRAP_PASSWORD`, `LAB_APP_DB_PASSWORD`, `FAULTS_DB_PASSWORD`).
3. M2, in this order:
   - the MCP server with read-only tools: Loki logs, Prometheus metrics, `queryDb` on the event tables through a non-superuser read-only role with no access to `faults`, and deploy history;
   - RAG on `docs/runbooks/` with pgvector (ADR-002);
   - the agent loop with `IncidentState` and a JSON report with cited evidence;
   - the Python eval harness, which reads the truth with the `faults` role, for the M2 check (F1–F3 correctly diagnosed).

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
