# Gateway Mock Specification

## Purpose

`lab/gateway-mock` simulates the external payment gateway that payment-service calls to authorize a payment. Its latency can be changed at runtime through a REST admin API, so the future fault injector can slow the lab down on purpose and produce fault F1: orders stuck in `PAYMENT_PENDING`. Design: [feature document](../../../odd/tasks/gateway-mock.md). Verified by `./mvnw test` from `lab/gateway-mock`.

The gateway has no database. Its only state is the current behavior, which holds the latency in milliseconds (`latencyMs`, default 0, allowed range 0 to 60000). It listens on port 8082.

## Requirements

### Requirement: Authorize a payment

`POST /authorize` with body `{"orderId": "<uuid>"}` MUST wait for the configured latency and then return 200 with body `{"status": "AUTHORIZED"}`.

#### Scenario: Payment is authorized
- GIVEN the default behavior (latency 0)
- WHEN `POST /authorize` is called with a body whose `orderId` is a UUID
- THEN the response status is 200
- AND the body is `{"status": "AUTHORIZED"}`

### Requirement: Configured latency delays the answer

The gateway MUST wait at least the configured latency before it answers `POST /authorize`. The wait happens on the request thread, as a slow upstream would.

#### Scenario: Slow gateway
- GIVEN the latency is set to 300 ms through `PUT /admin/behavior`
- WHEN `POST /authorize` is called with a valid body
- THEN the response status is 200
- AND the response arrives no sooner than 300 ms after the request

### Requirement: Interrupted latency is a 503

If the simulated latency is interrupted before it ends, for example when the gateway shuts down, `POST /authorize` MUST return 503 and MUST NOT authorize. payment-service treats the 503 as "gateway unavailable", not as a decline.

#### Scenario: Sleep interrupted during shutdown
- GIVEN the latency is set to 60000 ms
- WHEN the thread serving `POST /authorize` is interrupted while it waits
- THEN the response status is 503

### Requirement: Admin API stays responsive under load

The admin API MUST answer promptly while many `POST /authorize` requests are waiting for their latency, more than Tomcat's platform thread pool (200 threads by default) could hold. Requests run on virtual threads (`spring.threads.virtual.enabled=true`), so a sleeping request does not hold a platform thread. This keeps the fault injector able to restore the lab while fault F1 is active.

#### Scenario: Reset while the gateway is saturated
- GIVEN the latency is 3000 ms and 250 `POST /authorize` requests are in flight
- WHEN `GET /admin/behavior` and then `DELETE /admin/behavior` are called
- THEN each answers 200 in under 1 second

### Requirement: Expose the in-flight authorize gauge

The service MUST expose the Micrometer gauge `gateway.authorize.inflight`: the number of `POST /authorize` requests currently waiting on the simulated latency. A request MUST stop counting when it ends, whether it is authorized or interrupted. The gauge makes fault F1 visible as requests piling up.

#### Scenario: Gauge returns to zero
- GIVEN the default behavior
- WHEN a `POST /authorize` call completes
- THEN `gateway.authorize.inflight` is 0

#### Scenario: Gauge counts sleeping requests
- GIVEN the latency is 3000 ms
- WHEN 250 `POST /authorize` requests are sent at once
- THEN `gateway.authorize.inflight` reaches 250 while they wait

### Requirement: Change, read and reset the behavior

- `PUT /admin/behavior` with body `{"latencyMs": <n>}` MUST replace the behavior and return 200 with the new behavior (`{"latencyMs": <n>}`).
- `GET /admin/behavior` MUST return 200 with the current behavior.
- `DELETE /admin/behavior` MUST reset the latency to 0 and return 200 with the reset behavior.
- Every change and reset MUST be logged at `INFO` with the old and the new latency, so the fault injector and the observability stack can see when the lab was broken and restored.
- Changes MUST be safe under concurrent requests: a reader sees either the old or the new behavior, never a mix.

#### Scenario: Set the latency
- GIVEN the default behavior
- WHEN `PUT /admin/behavior` is called with `{"latencyMs": 10000}`
- THEN the response status is 200 and the body is `{"latencyMs": 10000}`
- AND `GET /admin/behavior` returns `{"latencyMs": 10000}`

#### Scenario: Read the default behavior
- GIVEN no change since startup
- WHEN `GET /admin/behavior` is called
- THEN the response status is 200 and the body is `{"latencyMs": 0}`

#### Scenario: Reset the behavior
- GIVEN the latency is 10000 ms
- WHEN `DELETE /admin/behavior` is called
- THEN the response status is 200 and the body is `{"latencyMs": 0}`
- AND `POST /authorize` answers without the extra wait

### Requirement: Reject invalid input

- `POST /authorize` MUST return 400 when the `orderId` is missing, null or not a UUID, or when the body is not well-formed JSON.
- `PUT /admin/behavior` MUST return 400 and MUST keep the current behavior when `latencyMs` is missing, null, negative, above 60000 or not a number, or when the body is not well-formed JSON.

#### Scenario: Invalid authorize body
- GIVEN a body that is `{}`, `{"orderId": null}`, `{"orderId": "not-a-uuid"}` or malformed JSON
- WHEN `POST /authorize` is called with each body
- THEN each response status is 400

#### Scenario: Invalid latency
- GIVEN a body that is `{}`, `{"latencyMs": null}`, `{"latencyMs": -1}`, `{"latencyMs": 60001}`, `{"latencyMs": "slow"}` or malformed JSON
- WHEN `PUT /admin/behavior` is called with each body
- THEN each response status is 400
- AND `GET /admin/behavior` still returns the previous behavior

#### Scenario: Latency bounds are accepted
- GIVEN the bodies `{"latencyMs": 0}` and `{"latencyMs": 60000}`
- WHEN `PUT /admin/behavior` is called with each body
- THEN each response status is 200

## Known limitations

- **Latency only.** The gateway always authorizes. Forced errors and the 429 response belong to fault F2 and are not built yet.
- **No authentication, all interfaces.** The admin endpoints have no auth and listen on all interfaces on purpose, so the Docker network and the fault injector can reach them. This is a local lab; do not expose it.
- **A reset does not shorten requests already sleeping.** A `POST /authorize` reads the latency once when it starts; after a reset it still waits the old latency, and only new requests answer fast.
- **In-memory state.** The behavior lives in memory: a restart resets the latency to 0, and several instances do not share it.
