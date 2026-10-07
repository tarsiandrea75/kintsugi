# State

Overwritten at the end of every session. History lives in `docs/journal/`.

## Updated

2026-10-07 (session 03, autonomous)

## Milestone

M1 — The lab (Oct 5–25). **The M1 check is met: faults F1–F4 are visible in Grafana, and each has a passing verify script.** The remaining M1 bullets are service runbooks in Markdown and the "I built a system designed to break" post.

## Last action

`fault-f4` is done ([feature document](../odd/tasks/fault-f4.md)), built autonomously while the user was away.
- **New service:** `inventory-service`, with its own `inventory` database, a seed of SKU-1…SKU-5, atomic SKU-ordered reservations, and a 500 on pool exhaustion.
- **order-service:** reserves stock before accepting an order. It answers 409 when stock is short and 503 when inventory is unavailable, and stores nothing in either case.
- **F4:** set `INVENTORY_POOL_SIZE=2 INVENTORY_RESERVATION_DELAY=5s`, then recreate inventory-service. Six concurrent orders all answer 503, with Hikari pending 4, timeouts 4 and inventory 500s.
- **Dashboard:** "Lab — Faults" now has 9 panels.
- **Checks:** `verify-f1.sh` … `verify-f4.sh` all pass on one fresh stack.
- **Tests:** order-service 148, inventory-service 58, payment-service 74, gateway-mock 71.
- **Status:** every range is reviewed and approved. Nothing is pushed.

**To review with the human (decided autonomously):**
- The F3 and F4 designs; see "To review with the human" in `odd/tasks/fault-f3.md` and the Design section of `odd/tasks/fault-f4.md`.
- F3 is resolved: the human accepted `ConnectException` as the evidence, and `docs/vision.md:33` was updated.
- That under F4 even reservations that got a connection answer 503, because the 5 s query outlasts the 3 s read timeout. Stock probably leaks; this is documented as a known limitation.

**Upgrading an old lab volume:** run `docker compose -f infra/compose.yaml down -v` once, so the `inventory` database is created.

## Next action

Finish M1:
1. ~~Service runbooks~~: done (`docs/runbooks/`, no fault leakage; see `odd/tasks/service-runbooks.md` for the open points).
2. Prepare the M1 LinkedIn post, "I built a system designed to break", with a dashboard screenshot. This is a human task.
3. Decide on pushing the local chains.

Then M2: the MCP server with read-only tools on logs, metrics and deploys (`docs/vision.md:154`).

## Local branch chains (not pushed)

- `feat/model-smoke-test` → `-01-recorder` → `-02-harness` → `-03-adr`
- `feat/order-service-skeleton` → `-01-domain` → `-02-api`
- `feat/payment-service` → `-01-domain` → `-02-api` → `-03-order-payment` → `-04-order-flow`
- `feat/gateway-mock` → `-01-behavior` → `-02-authorize` → `-03-payment-gateway`
- `feat/lab-observability` → `-01-decisions` → `-02-metrics` → `-03-stack`
- `feat/fault-f2` → `-01-gateway` → `-02-payment-failed` → `-03-payment-gauge` → `-04-order` → `-05-check`
- `feat/fault-f3` → `-01-client-metrics` → `-02-check`
- `feat/fault-f4` → `-01-inventory-data` → `-02-inventory-api` → `-03-order-inventory` → `-04-check`

Each chain starts from the tip of the previous one. The backup bundle is in `C:\Users\andrea.tarsi\kintsugi-backups\`.

## Standing preferences

- Over-budget features use `feature-branch-chain` without asking.
- Native review consent is always granted: run the review and report it in one line.

## Open questions

- When to make the GitHub repository public. It is private for now; the vision assumes it is public from week 1.
- Order/payment reconciliation: a future feature, planned with the fault injector. Under F1, payment-service still stores an AUTHORIZED payment after order-service has timed out (observed).
- Datasource socket timeout. Without one, a database that stops responding keeps the count refresh busy until TCP gives up. The gauges still show NaN after 10 s. A socket timeout would apply to every query, including F4's slow queries.
- F4 design, decided autonomously and waiting for the human: reserve before saving, injection through compose variables, and the stock leak under F4 (see `odd/tasks/fault-f4.md`).
- Reservation release on payment failure or timeout. The stock leak under F4 belongs with reconciliation.

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
