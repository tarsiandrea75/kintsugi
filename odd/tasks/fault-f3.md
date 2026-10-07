# Feature: fault-f3

Locator: `odd/tasks/fault-f3.md` · Engram mirror: `odd/fault-f3/tasks` · Branch: `feat/fault-f3` (from `feat/fault-f2-05-check` at `08239be`)

## Objective

Make fault F3, "wrong configuration", injectable and visible in Grafana. Per the vision it is a wrong service URL set through the environment, and its symptom is "connection refused" in the logs (`docs/vision.md:33`). This is the third fault of the M1 check (`docs/vision.md:152`).

## Problem and why

A misconfigured service URL is one of the most common real incidents. The agent must be able to tell it apart from F1, because both leave orders in `PAYMENT_PENDING`. Today the lab has no metric for order-service's outgoing payment calls, so in Grafana the two faults would look almost the same.

## Scope

- In:
  - outgoing HTTP client metrics on order-service's payment client;
  - a compose variable for order-service's payment URL;
  - `verify-f3.sh`;
  - a dashboard panel;
  - living specs and docs.
- Out:
  - client metrics on payment-service's gateway client;
  - the fault injector, which will later set the variable;
  - F4.

## Design (decided autonomously on 2026-10-07 while the user was away; **to be reviewed by the human**)

- **What breaks:** order-service's `kintsugi.payment.base-url`. In compose it becomes `KINTSUGI_PAYMENT_BASEURL: ${ORDER_PAYMENT_BASEURL:-http://payment-service:8081}`. F3 sets `ORDER_PAYMENT_BASEURL=http://payment-service:9999`, a port where nothing listens, so the connection is refused at once. This is "a wrong service URL via env", which is a deploy-time misconfiguration.
- **Injection:** recreate order-service with the variable set: `ORDER_PAYMENT_BASEURL=... docker compose up -d --no-deps --wait order-service`. Restore by recreating it without the variable. Until the fault injector exists, this is the injection mechanism, and it is realistic: a bad config arrives through a redeploy.
- **Why this target:** the alternative was payment-service's gateway URL. It would produce a payment-service 503, so the order would again stay pending, but the evidence would be one hop further from the symptom. Breaking order → payment keeps the evidence (the WARN line and the client metrics) in the service where the symptom shows.
- **How F3 differs from F1:**
  - F3: `POST /orders` answers fast (connection refused, well under the 3 s read timeout). The client metric shows the exception `ConnectException` or an equivalent. payment-service receives no `/payments` requests, and `gateway_authorize_inflight` stays at 0. The WARN line contains "Connection refused".
  - F1: `POST /orders` takes about 3 s, the client metric shows a timeout or cancellation, payment-service and the gateway still receive requests, and the in-flight gauge rises above 0.
- **Client metrics:**
  - `PaymentClient` builds its `RestClient` with the application's `ObservationRegistry`, so Micrometer records `http.client.requests` (Prometheus `http_client_requests_seconds_*`). The tags include `outcome`, `status`, `exception` and `client_name`.
  - Verify the exact tag names and values in Boot 4.1.1 / Spring Framework 7 before using them in the dashboard and the script.
- **Dashboard:** add a panel "order-service → payment calls by outcome", showing the rate of `http_client_requests_seconds_count` by `outcome` and `exception`.
- **Check:** `infra/scripts/verify-f3.sh`, which uses `lib.sh`. It:
  1. places a healthy order and checks it is `PAID`;
  2. injects F3 by recreating order-service;
  3. places an order and checks it returns 201 `PAYMENT_PENDING` in under 2 s;
  4. checks in Prometheus that the client-request series with the connect exception rose and that `gateway_authorize_inflight` stayed 0;
  5. checks in Loki that the order-service WARN line contains the order id and "Connection refused";
  6. restores, always, through a trap, by recreating order-service without the variable;
  7. places a final order and checks it is `PAID`.

## Acceptance criteria

- order-service exposes `http_client_requests_seconds_*` for its payment calls, with outcome/exception tags, covered by a test.
- With `ORDER_PAYMENT_BASEURL` unset, the lab behaves exactly as before: `verify-f1.sh` and `verify-f2.sh` still pass.
- `verify-f3.sh` passes, and order-service is always restored, even on failure.
- `./mvnw test` passes in order-service.

## Delivery

- `auto-chain` with `feature-branch-chain`. Forecast: about 450 lines.
- RDD: on; consent is a standing grant. Reviews are run per range.

## Tasks

- [ ] **T1 — order-service client metrics.** Observation on `PaymentClient`, tests first, and the spec. Route: delegated writer.
- [ ] **T2 — Compose variable, check, dashboard and docs.** `verify-f3.sh` run for real, with F1 and F2 rerun for regressions. Route: delegated writer.

## Checks

- `cd lab/order-service && ./mvnw test`
- `infra/scripts/verify-f1.sh`, `verify-f2.sh`, `verify-f3.sh`

## Progress

- 2026-10-07: design decided autonomously (user away, autonomy authorized until the quota ends); branch created.

## Next step

T1 + T2 via one delegated writer.
