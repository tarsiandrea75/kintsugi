# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-06

## Milestone

M1 — The lab (Oct 5–25)

## Last action

`gateway-mock` done ([feature document](../odd/tasks/gateway-mock.md)).
- A simulated payment gateway whose latency is set through `PUT /admin/behavior`. It runs on virtual threads, so the admin API stays responsive under load.
- It exposes a `gateway.authorize.inflight` gauge.
- payment-service calls it over HTTP with timeouts, and answers 503 without storing anything when the gateway is unavailable.
- Fault F1 is now injectable and was reproduced by hand on 2026-10-06: with 10 s of latency the order returned 201 `PAYMENT_PENDING` after 3.01 s, and payment-service later stored an `AUTHORIZED` payment for it.
- Tests: gateway-mock 37, payment-service 48, order-service 97.
- All work is local and unpushed.

## Next action

Friday task from the vision: Docker Compose with OpenTelemetry, Prometheus and Grafana, plus a dashboard that shows F1 while it is active. The first signals are the `gateway.authorize.inflight` gauge and the order states. Use non-default host ports in Compose: other local projects hold 5432, 5433 and 8080.

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`
- `feat/gateway-mock` → `-01-behavior` → `-02-authorize` → `-03-payment-gateway`

Each chain starts from the tip of the previous one.

## Open questions

- When to make the GitHub repository public (private for now; the vision assumes public from week 1).
- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service stores an AUTHORIZED payment after order-service has timed out (observed by hand).
- The meaning of F2 (gateway 429): a declined payment or an unavailable gateway. Decide when F2 is built.

## Follow-ups

Lab services:
- `POST /orders` and `POST /payments` are not idempotent, so a retry after a timeout can create duplicates. This is a candidate fault.
- gateway-mock: a reset does not shorten in-flight requests, and the admin API is open on all interfaces on purpose (see its spec, "Known limitations").
- The order-service 400 safety net is untested by design.
- No test proves that the `gateway.authorize.inflight` gauge keeps its counter after a GC (`strongReference(true)`).

Smoke-test harness (non-blocking review findings):
- The report is skipped on a partial run.
- There is no per-prompt timeout.
- The validity rule does not check the zone.
- The report omits `think=false`, misses 12-hour formats and breaks on backticks.
- ADR-003 omits the P7 minimum call count.

Carried over:
- A test for the `demo` profile activation.
- `DemoRunner` and `HttpConnectTimeoutException`.
- Non-ASCII output in the Windows console.

## Blockers

None.
