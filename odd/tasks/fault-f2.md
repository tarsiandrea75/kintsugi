# Feature: fault-f2

Locator: `odd/tasks/fault-f2.md` · Engram mirror: `odd/fault-f2/tasks` · Branch: `feat/fault-f2` (from `feat/lab-observability-03-stack` at `1ac2b9f`)

## Objective

Make fault F2, "gateway rejecting with 429", injectable and visible in Grafana as a burst of failed payments (`docs/vision.md:32`). This is the second fault of the M1 check (`docs/vision.md:152`).

## Problem and why

Today any non-2xx gateway answer becomes a 503 in payment-service, so the order stays `PAYMENT_PENDING`. That is the same symptom as F1. The vision gives F2 a different symptom: failed payments. Distinct symptoms are what make the faults diagnosable: the agent must tell "slow gateway" from "rate-limited gateway".

## Scope

- In:
  - gateway-mock: an `errorStatus` behavior;
  - payment-service: a `FAILED` payment status and a Flyway V2 migration;
  - order-service: maps a `FAILED` payment to `PAYMENT_FAILED`;
  - observability: a payments-by-status gauge and an F2 panel;
  - `verify-f2.sh`;
  - living specs and docs.
- Out:
  - retries and backoff on 429;
  - the `Retry-After` header;
  - the fault injector;
  - faults F3 and F4.

## Design (approved by the human 2026-10-07)

- **Meaning of F2:** a 429 from the gateway is a failed payment, not an unavailable gateway.
- **gateway-mock:**
  - `GatewayBehavior` gains `errorStatus`. It is nullable; allowed values are `429`, `500` and `503`; `null` means no error.
  - When it is set, `POST /authorize` waits for the configured latency, then answers with that status and a small JSON body.
  - `PUT /admin/behavior` takes `{latencyMs, errorStatus}` and replaces the whole behavior. A missing `errorStatus` means no error.
  - `DELETE` resets both values.
  - Behavior changes are logged at INFO, as they are today.
- **payment-service:**
  - A new `PaymentStatus.FAILED`.
  - The gateway outcome gains `FAILED`: `HttpPaymentGateway` maps a gateway `429` to it. Every other non-2xx, an unknown status, a timeout or an unreachable gateway stays "unavailable", which means 503 and nothing stored, as for F1.
  - A `FAILED` payment is stored and returned with 201.
  - Flyway `V2` widens the status CHECK constraint. `V1` is not edited, because Compose volumes may already have applied it.
  - A payment stored as `FAILED` is logged at WARN with the order id and the gateway status.
- **order-service:** the payment client maps the `FAILED` status from payment-service to a new outcome, and `OrderService` moves the order to `PAYMENT_FAILED`. This is the same end state as `DECLINED`; the distinction stays in the payment record.
- **Observability:**
  - payment-service exposes `lab.payments{status}` (`lab_payments` in Prometheus), counted like `lab_orders`. The code may be shared by copying, since each service is a standalone project.
  - Add a panel "Payments by status" to the dashboard. The dashboard title becomes "Lab — Faults", and its uid stays `lab-f1` so links still work. Add the panel "Gateway rejections".
- **Check:** `infra/scripts/verify-f2.sh`. The F1 script's helpers are factored into a small `infra/scripts/lib.sh` only if that is simple. The script:
  - sets `errorStatus` to 429;
  - places orders;
  - asserts `PAYMENT_FAILED` through the API;
  - asserts that `lab_payments{status="FAILED"}` and `lab_orders{status="PAYMENT_FAILED"}` rose in Prometheus;
  - asserts the payment-service WARN line in Loki;
  - always resets the gateway.

## Acceptance criteria

- `PUT /admin/behavior {"latencyMs":0,"errorStatus":429}` makes `POST /authorize` answer 429. Invalid values return 400. `DELETE` restores normal behavior.
- With a gateway 429, `POST /payments` returns 201 with status `FAILED`, and the payment is stored as `FAILED`.
- With a gateway 500 or 503, the behavior is unchanged: a 503 and nothing stored.
- With a gateway 429, `POST /orders` returns 201 with status `PAYMENT_FAILED`.
- `lab_payments{status}` reports one series per status, including 0.
- `infra/scripts/verify-f2.sh` passes, and `infra/scripts/verify-f1.sh` still passes.
- `./mvnw test` passes in all three services.

## Delivery

- Strategy: `auto-chain` with `feature-branch-chain` (the user's standing default). Forecast: about 800 authored lines.
- Slices (feature-branch-chain): tracker `feat/fault-f2` (feature doc `058f524`); PR 1 `-01-gateway` T1 (about 365 lines); PR 2 `-02-payment-failed` T2 status, V2 migration and mapping; PR 3 `-03-payment-gauge` the `lab_payments` gauge (copied pattern, about 560 lines with tests); PR 4 `-04-order` T3 (about 58 lines).
- RDD: on (global); consent is a standing grant. Reviews run per range with `--base-ref`.

## Tasks

- [x] **T1 — gateway-mock error behavior.** The `errorStatus` behavior, the admin API and `/authorize`; tests first; the spec. Route: delegated writer.
- [x] **T2 — payment-service FAILED.** The status, the `V2` migration, the gateway mapping, the WARN log, the `lab_payments` gauge; tests first; the spec. Route: delegated writer.
- [x] **T3 — order-service mapping.** `FAILED` → `PAYMENT_FAILED`; tests first; the spec. Route: delegated writer.
- [ ] **T4 — Observability and check.** The dashboard panels, `verify-f2.sh`, the lab-observability spec and the docs. Run both verify scripts. Route: delegated writer.

## Checks

- `./mvnw test` in each `lab/*` service
- `infra/scripts/verify-f1.sh` and `infra/scripts/verify-f2.sh`

## Progress

- 2026-10-07: meaning of F2 decided (failed payment, `FAILED` status); design approved; branch created.
- 2026-10-07: T1–T3 done by a delegated writer. RED: compile failures in each service. GREEN, parent re-run with mvn exit codes checked: gateway-mock 71, payment-service 74, order-service 116. Notes:
  - `RestClient` raises `HttpClientErrorException.TooManyRequests` on a 429, which is caught first.
  - V2 drops and re-creates `payments_status_check` (PostgreSQL's default name, confirmed on Testcontainers).
  - A 200 response with a `FAILED` body is treated as unavailable.
  - The 429 WARN is logged in `HttpPaymentGateway`, the only place that sees the HTTP status, so it is also logged if the later save fails.

## Next step

T4 (dashboard, `verify-f2.sh`, docs) via a delegated writer.
