# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-07 (session 02, autonomous)

## Milestone

M1 — The lab (Oct 5–25)

## Last action

`fault-f3` is done ([feature document](../odd/tasks/fault-f3.md)), built autonomously while the user was away.
- **What F3 breaks:** a wrong order-service payment URL, set with `ORDER_PAYMENT_BASEURL=http://payment-service:9999` and a recreate of order-service. Orders stay `PAYMENT_PENDING` but answer in about 0.3 s instead of 3 s.
- **Evidence:**
  - the client metric `http_client_requests_seconds_count{client_name="payment-service",exception="ConnectException"}`;
  - zero gateway in-flight;
  - a WARN line naming `ConnectException` and the wrong URL.
- **How F1 and F3 differ:** under F1 the same metric has `exception="HttpTimeoutException"`.
- **Dashboard:** a new panel, "order-service → payment calls by outcome".
- **Checks:** `verify-f1.sh`, `verify-f2.sh` and `verify-f3.sh` all pass on one fresh stack.
- **Tests:** order-service 122.
- **Status:** every range is reviewed and approved. Nothing is pushed.

**To review with the human (decided autonomously):** see "To review with the human" in `odd/tasks/fault-f3.md`. In short: the F3 evidence is `ConnectException` plus the URL, not the literal "connection refused" text in the vision table, so the vision line may need rewording.

## Next action

Last fault of the M1 check: F4, "DB pool exhausted", with the symptom "500 errors on inventory" (`docs/vision.md:34`). It needs a new `inventory-service`, an order → inventory call, which also prepares F8, and a pool of 2 plus a slow query.

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`
- `feat/gateway-mock` → `-01-behavior` → `-02-authorize` → `-03-payment-gateway`
- `feat/lab-observability` → `-01-decisions` → `-02-metrics` → `-03-stack`
- `feat/fault-f2` → `-01-gateway` → `-02-payment-failed` → `-03-payment-gauge` → `-04-order` → `-05-check`
- `feat/fault-f3` → `-01-client-metrics` → `-02-check`

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.

## Open questions

- When to make the GitHub repository public. It is private for now; the vision assumes it is public from week 1.
- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service still stores an AUTHORIZED payment after order-service has timed out (observed).
- Datasource socket timeout. Without one, a database that stops responding keeps the count refresh busy until TCP gives up. The gauges still show NaN after 10 s. A socket timeout would apply to every query, including F4's slow queries.
- F3 decisions taken autonomously (see `odd/tasks/fault-f3.md`): whether to accept `ConnectException` as the evidence and reword `docs/vision.md:33`.

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
