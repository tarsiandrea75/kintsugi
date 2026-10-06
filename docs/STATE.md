# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-06

## Milestone

M1 — The lab (Oct 5–25)

## Last action

`payment-service` done, and order → payment wired ([feature document](../odd/tasks/payment-service.md)). `POST /orders` creates the order, calls payment-service with explicit timeouts, and ends in `PAID` or `PAYMENT_FAILED`. A timeout or an unreachable payment-service leaves the order in `PAYMENT_PENDING`, which is the fault F1 symptom. Tests: order-service 97, payment-service 29. Every committed range is reviewed and approved. All work is local and unpushed.

## Next action

Thursday task from the vision: `gateway-mock`, with latency configurable via REST (fault F1). It replaces the `AlwaysAuthorizeGateway` fake in payment-service with an HTTP client. Before that, decide on pushing the three local feature-branch chains.

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`

Each chain starts from the tip of the previous one.

## Open questions

- When to make the GitHub repository public (private for now; the vision assumes public from week 1).
- Order/payment reconciliation: a future feature, planned with the fault injector. It could fix the inconsistency or inject it as a fault (see the order-service spec, "Known limitations").

## Follow-ups

Lab services:
- `POST /orders` and `POST /payments` are not idempotent. A retry after a timeout can create duplicates, which is a candidate fault for the catalog.
- The manual run guide for the two services in `docs/setup.md` was never run by hand.
- The order-service 400 safety net (`OrderController.toOrder`) is untested by design.

Smoke-test harness (non-blocking review findings):
- The `@AfterAll` report is skipped on a partial run, there is no per-prompt timeout, and the max latencies over 60 s are attributed to cold start without a warm-up prompt.
- The validity rule does not check the zone. The report omits `think=false`, misses 12-hour formats, and breaks on backticks.
- ADR-003's validity rule omits the per-prompt minimum call count (P7 needs 2).

Carried over:
- An automated test for the `demo` profile activation.
- `DemoRunner` does not treat `HttpConnectTimeoutException` as a connection failure.
- The Windows console prints `?` for non-ASCII model output.

## Blockers

None.
