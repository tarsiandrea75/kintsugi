# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-07

## Milestone

M1 — The lab (Oct 5–25)

## Last action

`fault-f2` is done ([feature document](../odd/tasks/fault-f2.md)).
- **Behavior:** when gateway-mock answers 429 (`PUT /admin/behavior {"errorStatus":429}`), payment-service stores a `FAILED` payment and order-service ends the order in `PAYMENT_FAILED`. This is a burst of failed payments, the symptom the vision describes for F2. F1 still shows a different symptom: orders stuck in `PAYMENT_PENDING`.
- **Dashboard:** the Grafana dashboard is now "Lab — Faults". It has two new panels: payments by status, and gateway rejections from Loki.
- **Checks:** `infra/scripts/verify-f1.sh` and `verify-f2.sh` both pass on the live lab.
- **Tests:** gateway-mock 71, payment-service 74, order-service 116.
- **Status:** every committed range is reviewed and approved. All work is local and unpushed.

## Next action

Continue the M1 check ("every fault F1–F4 is visible in Grafana") with F3, a wrong configuration: a wrong service URL via env, with the symptom "connection refused in logs" (`docs/vision.md:33`). Decide which service URL to break. The candidate is order-service's `kintsugi.payment.base-url`, which makes orders stay pending with a connection-refused WARN; this needs a way to tell it apart from F1. Then F4 (DB pool exhausted on inventory), which needs `inventory-service`.

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`
- `feat/gateway-mock` → `-01-behavior` → `-02-authorize` → `-03-payment-gateway`
- `feat/lab-observability` → `-01-decisions` → `-02-metrics` → `-03-stack`
- `feat/fault-f2` → `-01-gateway` → `-02-payment-failed` → `-03-payment-gauge` → `-04-order` → `-05-check`

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.

## Open questions

- When to make the GitHub repository public. It is private for now; the vision assumes it is public from week 1.
- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service still stores an AUTHORIZED payment after order-service has timed out (observed).
- Datasource socket timeout. Without one, a database that stops responding keeps the count refresh busy until TCP gives up. The gauges still show NaN after 10 s. A socket timeout would apply to every query, including F4's slow queries.
- F3: how to make it diagnosable apart from F1, given that both leave orders `PAYMENT_PENDING`. The logs differ ("Connection refused" versus "Request cancelled").

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
- `place_order` aborts silently when curl fails.
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
