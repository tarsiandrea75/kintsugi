# Gateway Mock Specification

## Purpose

`lab/gateway-mock` simulates the external payment gateway that payment-service calls to authorize a payment. Its latency and an error status can be changed at runtime through a REST admin API, so the future fault injector can break the lab on purpose: a high latency produces fault F1 (orders stuck in `PAYMENT_PENDING`), and an error status of 429 produces fault F2 (a burst of failed payments). Design: [feature document](../../../odd/tasks/gateway-mock.md) and, for the error status, [fault F2](../../../odd/tasks/fault-f2.md). Verified by `./mvnw test` from `lab/gateway-mock`.

The gateway has no database. Its only state is the current behavior, which holds:

- the latency in milliseconds (`latencyMs`, default 0, allowed range 0 to 60000);
- the error status (`errorStatus`, default `null`, allowed values `429`, `500` and `503`); `null` means no error.

It listens on port 8082.

## Requirements

### Requirement: Authorize a payment

`POST /authorize` with body `{"orderId": "<uuid>"}` MUST wait for the configured latency and then, when no error status is set, return 200 with body `{"status": "AUTHORIZED"}`.

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

### Requirement: Configured error status rejects the payment

When an error status is set, `POST /authorize` MUST wait for the configured latency and then answer with that status and a small JSON body: `{"error": "rate limited"}` for 429, `{"error": "internal error"}` for 500 and `{"error": "unavailable"}` for 503. It MUST NOT authorize. A 429 is how fault F2 reaches payment-service, which records a failed payment; 500 and 503 still read as "gateway unavailable" there.

#### Scenario: Rate-limited gateway
- GIVEN `PUT /admin/behavior` was called with `{"latencyMs": 0, "errorStatus": 429}`
- WHEN `POST /authorize` is called with a valid body
- THEN the response status is 429
- AND the body is `{"error": "rate limited"}`

#### Scenario: Error after the latency
- GIVEN the latency is 200 ms and the error status is 429
- WHEN `POST /authorize` is called with a valid body
- THEN the response status is 429
- AND the response arrives no sooner than 200 ms after the request

#### Scenario: Normal behavior after a reset
- GIVEN the error status is 429
- WHEN `DELETE /admin/behavior` is called
- THEN `POST /authorize` returns 200 with `{"status": "AUTHORIZED"}`

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

### Requirement: Every authorize call is logged

The gateway has no database by design, so its record of each call is a log line, which reaches Loki. Every `POST /authorize` that passes validation MUST log exactly one structured line with `key=value` pairs, readable by Loki's `logfmt` parser: the order id, the configured latency, the status answered and the actual duration, measured from before the latency.

```
Authorize answered: event=AUTHORIZE order=<order id> answered=<200|429|500|503> duration_ms=<actual ms>
```

The line is `INFO`. When the latency is interrupted the one line is `WARN` instead, says so, and reports the 503 answered:

```
Authorize interrupted: event=AUTHORIZE order=<order id> answered=503 duration_ms=<actual ms> interrupted=true
```

A body rejected with 400 never reaches the controller and logs no such line.

#### Scenario: One line per call
- GIVEN any configured latency and error status
- WHEN `POST /authorize` is called with a valid order id
- THEN exactly one `INFO` line contains `event=AUTHORIZE`, `order=<order id>` and `answered=<status returned>`
- AND it does not contain the configured latency (`latency_ms`), a fault parameter the agent must not see
- AND its `duration_ms` is at least the configured latency

#### Scenario: Interrupted call
- GIVEN the latency is interrupted
- WHEN `POST /authorize` is answered with 503
- THEN exactly one `WARN` line contains `Authorize interrupted`, `event=AUTHORIZE`, `order=<order id>`, `answered=503` and `interrupted=true`

### Requirement: Change, read and reset the behavior

- `PUT /admin/behavior` with body `{"latencyMs": <n>, "errorStatus": <s>}` MUST replace the whole behavior and return 200 with the new behavior (`{"latencyMs": <n>, "errorStatus": <s>}`). A missing `errorStatus` means no error, so it clears an error set earlier.
- `GET /admin/behavior` MUST return 200 with the current behavior.
- `DELETE /admin/behavior` MUST reset the latency to 0 and the error status to `null`, and return 200 with the reset behavior.
- Every change and reset MUST be logged as one `INFO` line with `key=value` pairs, readable by Loki's `logfmt` parser, with the old and the new latency and error status (`none` when there is no error), so the fault injector and the observability stack can see when the lab was broken and restored. The `Gateway behavior changed` and `Gateway behavior reset` prefixes are stable, because runbooks search for them:

  ```
  Gateway behavior changed: event=BEHAVIOR_CHANGED previous_latency_ms=<ms> latency_ms=<ms> previous_error_status=<status|none> error_status=<status|none>
  Gateway behavior reset: event=BEHAVIOR_RESET previous_latency_ms=<ms> latency_ms=0 previous_error_status=<status|none> error_status=none
  ```
- Changes MUST be safe under concurrent requests: a reader sees either the old or the new behavior, never a mix.

#### Scenario: Set the latency
- GIVEN the default behavior
- WHEN `PUT /admin/behavior` is called with `{"latencyMs": 10000}`
- THEN the response status is 200 and the body is `{"latencyMs": 10000, "errorStatus": null}`
- AND `GET /admin/behavior` returns `{"latencyMs": 10000, "errorStatus": null}`

#### Scenario: Set the error status
- GIVEN the default behavior
- WHEN `PUT /admin/behavior` is called with `{"latencyMs": 0, "errorStatus": 429}`
- THEN the response status is 200 and the body is `{"latencyMs": 0, "errorStatus": 429}`

#### Scenario: A PUT without errorStatus clears it
- GIVEN the error status is 429
- WHEN `PUT /admin/behavior` is called with `{"latencyMs": 200}`
- THEN the behavior is `{"latencyMs": 200, "errorStatus": null}`

#### Scenario: Read the default behavior
- GIVEN no change since startup
- WHEN `GET /admin/behavior` is called
- THEN the response status is 200 and the body is `{"latencyMs": 0, "errorStatus": null}`

#### Scenario: Reset the behavior
- GIVEN the latency is 10000 ms, or the error status is 503
- WHEN `DELETE /admin/behavior` is called
- THEN the response status is 200 and the body is `{"latencyMs": 0, "errorStatus": null}`
- AND `POST /authorize` answers without the extra wait

### Requirement: Reject invalid input

- `POST /authorize` MUST return 400 when the `orderId` is missing, null or not a UUID, or when the body is not well-formed JSON.
- `PUT /admin/behavior` MUST return 400 and MUST keep the current behavior when `latencyMs` is missing, null, negative, above 60000 or not a number, when `errorStatus` is not `null`, `429`, `500` or `503`, or when the body is not well-formed JSON.

#### Scenario: Invalid authorize body
- GIVEN a body that is `{}`, `{"orderId": null}`, `{"orderId": "not-a-uuid"}` or malformed JSON
- WHEN `POST /authorize` is called with each body
- THEN each response status is 400

#### Scenario: Invalid latency
- GIVEN a body that is `{}`, `{"latencyMs": null}`, `{"latencyMs": -1}`, `{"latencyMs": 60001}`, `{"latencyMs": "slow"}` or malformed JSON
- WHEN `PUT /admin/behavior` is called with each body
- THEN each response status is 400
- AND `GET /admin/behavior` still returns the previous behavior

#### Scenario: Invalid error status
- GIVEN a body whose `errorStatus` is 200, 404, 502, -1 or `"busy"`
- WHEN `PUT /admin/behavior` is called with each body
- THEN each response status is 400
- AND `GET /admin/behavior` still returns the previous behavior

#### Scenario: Latency bounds are accepted
- GIVEN the bodies `{"latencyMs": 0}` and `{"latencyMs": 60000}`
- WHEN `PUT /admin/behavior` is called with each body
- THEN each response status is 200

## Known limitations

- **Fixed error set.** Only 429, 500 and 503 can be forced, and an error applies to every request; there is no error rate, and a 429 carries no `Retry-After` header.
- **No authentication, all interfaces.** The admin endpoints have no auth and listen on all interfaces on purpose, so the Docker network and the fault injector can reach them. This is a local lab; do not expose it.
- **A reset does not shorten requests already sleeping.** A `POST /authorize` reads the latency once when it starts; after a reset it still waits the old latency and answers with the old error status, and only new requests see the reset.
- **In-memory state.** The behavior lives in memory: a restart resets the latency to 0 and clears the error status, and several instances do not share it.
