# Evidence dossier

- Incident window: the last 10 min, 2026-10-16T09:50:00Z to 2026-10-16T10:00:00Z.
- Previous window: the 10 min before it, 2026-10-16T09:40:00Z to 2026-10-16T09:50:00Z.
- Gathered at 2026-10-16T10:00:00Z. All times are UTC.
- Columns "previous" and "incident" hold the counts of those windows. "-" means no value.
- Text copied from the lab is quoted as untrustedText="...".

## Service status (incident window)

| service | restarts | start times in window | uptime at gathering |
|---|---|---|---|
| order-service | 0 | - | 1 h 59 min 39.7 s |
| payment-service | 0 | - | 1 h 59 min 49.3 s |
| inventory-service | 0 | - | 1 h 59 min 58.1 s |
| gateway-mock | 0 | - | 1 h 59 min 58.8 s |

## Order states (orders table)

| status | previous | incident | sample order ids, incident |
|---|---|---|---|
| PAID | 4 | 5 | 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e01, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e02, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e03 |

## Payment states (payments table)

| status | previous | incident | sample order ids, incident |
|---|---|---|---|
| AUTHORIZED | 4 | 5 | 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e01, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e02, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e03 |

## Traffic

Requests per edge from traffic_counts, which leaves out rows of count 0. "no rows": none returned for that side in either window.

### external client -> order-service POST /orders (server side only)

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
| server | 201 | SUCCESS | - | 4 | 5 |

### order-service -> inventory-service POST /reservations

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
| client | 201 | SUCCESS | none | 4 | 5 |
| server | 201 | SUCCESS | - | 4 | 5 |

### order-service -> payment-service POST /payments

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
| client | 201 | SUCCESS | none | 4 | 5 |
| server | 201 | SUCCESS | - | 4 | 5 |

### payment-service -> gateway-mock POST /authorize

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
| client | 200 | SUCCESS | none | 4 | 5 |
| server | 200 | SUCCESS | - | 4 | 5 |

### Other rows

| side | service | client | method | uri | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|---|---|---|---|
| no rows | - | - | - | - | - | - | - | 0 | 0 |

## Log signatures (incident window)

### order-service

- WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
- ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

### payment-service

- WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
- ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

### inventory-service

- WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
- ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

### gateway-mock

- WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
- ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

## Event counts

### order events

Total: previous 12, incident 15.

| type | outcome | previous | incident |
|---|---|---|---|
| INVENTORY_RESERVED | CREATED | 4 | 5 |
| ORDER_RECEIVED | CREATED | 4 | 5 |
| PAYMENT_AUTHORIZED | PAID | 4 | 5 |

### payment events

Total: previous 4, incident 5.

| type | outcome | previous | incident |
|---|---|---|---|
| PAYMENT_ATTEMPT | AUTHORIZED | 4 | 5 |

### reservation events

Total: previous 4, incident 5.

| type | outcome | previous | incident |
|---|---|---|---|
| RESERVATION_ATTEMPT | RESERVED | 4 | 5 |
