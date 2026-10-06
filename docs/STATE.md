# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-06

## Milestone

M1 — The lab (Oct 5–25)

## Last action

`lab-observability` done ([feature document](../odd/tasks/lab-observability.md)). The whole lab runs with `docker compose -f infra/compose.yaml up -d --build --wait`:

- **Containers:** one PostgreSQL, the three services, Prometheus, Loki, Alloy and Grafana. Every host port is bound to `127.0.0.1`.
- **Grafana:** the "Lab — F1" dashboard is at `http://127.0.0.1:13000`.
- **F1 check:** `infra/scripts/verify-f1.sh` proves that fault F1 is visible in metrics and logs: `RESULT: PASS`, 10 checks, about 85 s.
- **Status:** every committed range is reviewed and approved. All work is local and unpushed.

## Next action

Continue the M1 check, "every fault F1–F4 is visible in Grafana". Next is F2, the gateway rejecting with 429; decide first what it means (see Open questions). Then F3 (wrong configuration) and F4 (DB pool exhausted, on inventory, so `inventory-service` is needed). The fault injector with the true-cause table comes after the faults exist.

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`
- `feat/gateway-mock` → `-01-behavior` → `-02-authorize` → `-03-payment-gateway`
- `feat/lab-observability` → `-01-decisions` → `-02-metrics` → `-03-stack`

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.

## Open questions

- When to make the GitHub repository public. It is private for now; the vision assumes public from week 1.
- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service stores an AUTHORIZED payment after order-service has timed out (observed).
- What F2 (gateway 429) means: a declined payment or an unavailable gateway.
- Datasource socket timeout. Without one, a database that stops responding keeps the order-count refresh busy until TCP gives up. The gauge still shows NaN after 10 s. A socket timeout would bound it, but it applies to every query, including the slow queries F4 will inject.

## Follow-ups

Lab services:
- `POST /orders` and `POST /payments` are not idempotent. A retry after a timeout can create duplicates, which makes this a candidate fault.
- gateway-mock: a reset does not shorten in-flight requests.
- The order-service 400 safety net is untested by design.
- No test proves that the `gateway.authorize.inflight` gauge keeps its counter after a GC.
- `verify-f1.sh`: `pending_count` still parses with a pipeline under pipefail (review suggestion). The reset-on-failure path has not been observed.
- The order-service spec mixes operational details (Hikari 30 s default) into a normative paragraph (review suggestion).

Smoke-test harness (non-blocking review findings):
- The report is skipped on a partial run.
- There is no per-prompt timeout.
- The validity rule does not check the zone.
- The report omits `think=false`, misses 12-hour formats, and breaks on backticks.
- ADR-003 omits P7's minimum call count.

Carried over:
- `demo` profile test.
- `DemoRunner` with `HttpConnectTimeoutException`.
- Non-ASCII output in the Windows console.

## Blockers

None.
