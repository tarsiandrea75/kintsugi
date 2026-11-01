# Evidence dossier

D1 - Incident window: the last 10 min, 2026-10-16T09:50:00Z to 2026-10-16T10:00:00Z.
D2 - Previous window: the 10 min before it, 2026-10-16T09:40:00Z to 2026-10-16T09:50:00Z.
D3 - Gathered at 2026-10-16T10:00:00Z. All times are UTC.
D4 - Columns "previous" and "incident" hold the counts of those windows. "-" means no value.
D5 - Text copied from the lab is quoted as untrustedText="...".

## Service status (incident window)

| service | restarts | start times in window | uptime at gathering |
|---|---|---|---|
D6 | order-service | 0 | - | 1 h 59 min 39.7 s |
D7 | payment-service | 0 | - | 1 h 59 min 49.3 s |
D8 | inventory-service | 0 | - | 1 h 59 min 58.1 s |
D9 | gateway-mock | 0 | - | 1 h 59 min 58.8 s |

## Order states (orders table)

| status | previous | incident | sample order ids, incident |
|---|---|---|---|
D10 | PAID | 4 | 5 | 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e01, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e02, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e03 |

## Payment states (payments table)

| status | previous | incident | sample order ids, incident |
|---|---|---|---|
D11 | AUTHORIZED | 4 | 5 | 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e01, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e02, 9c2e1d3f-4b5a-4c6d-9e8f-1a2b3c4d5e03 |

## Traffic

D12 Requests per edge from traffic_counts, which leaves out rows of count 0. "no rows": none returned for that side in either window.

### external client -> order-service POST /orders (server side only)

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
D13 | server | 201 | SUCCESS | - | 4 | 5 |

### order-service -> inventory-service POST /reservations

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
D14 | client | 201 | SUCCESS | none | 4 | 5 |
D15 | server | 201 | SUCCESS | - | 4 | 5 |

### order-service -> payment-service POST /payments

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
D16 | client | 201 | SUCCESS | none | 4 | 5 |
D17 | server | 201 | SUCCESS | - | 4 | 5 |

### payment-service -> gateway-mock POST /authorize

| side | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|
D18 | client | 200 | SUCCESS | none | 4 | 5 |
D19 | server | 200 | SUCCESS | - | 4 | 5 |

### Other rows

| side | service | client | method | uri | status | outcome | exception | previous | incident |
|---|---|---|---|---|---|---|---|---|---|
D20 | no rows | - | - | - | - | - | - | - | 0 | 0 |

## Latency (server side)

D21 Seconds per request by service and uri, from query_metric latency_avg: one call per service over both windows, its points split at the incident window's start. "mean" and "max" are of the points with a value; "points" counts those with a value and all points in the window. A point has no value when no request ended in its rate window.

| service | uri | previous mean | incident mean | previous max | incident max | previous points | incident points |
|---|---|---|---|---|---|---|---|
D22 | order-service | /orders | 0.080 | 0.080 | 0.083 | 0.083 | 10/10 | 11/11 |
D23 | order-service | /actuator/prometheus | 0.004 | 0.004 | 0.004 | 0.004 | 10/10 | 11/11 |
D24 | payment-service | /payments | 0.046 | 0.045 | 0.047 | 0.046 | 10/10 | 11/11 |
D25 | payment-service | /actuator/prometheus | 0.003 | 0.003 | 0.003 | 0.003 | 10/10 | 11/11 |
D26 | inventory-service | /reservations | 0.021 | 0.021 | 0.021 | 0.022 | 10/10 | 11/11 |
D27 | inventory-service | /actuator/prometheus | 0.003 | 0.003 | 0.004 | 0.004 | 10/10 | 11/11 |
D28 | gateway-mock | /authorize | 0.011 | 0.012 | 0.012 | 0.013 | 10/10 | 11/11 |
D29 | gateway-mock | /actuator/prometheus | 0.002 | 0.002 | 0.002 | 0.002 | 10/10 | 11/11 |

## Resources

D30 Per service, from query_metric processor_usage, memory_used, open_files, in the units of the source: one call per service and metric over both windows, its points split as the latency's. "mean", "max" and "points" as in the latency table.

### order-service

D31 processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)

### payment-service

D32 processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)

### inventory-service

D33 processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)

### gateway-mock

D34 processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)

## Log signatures (incident window)

### order-service

D35 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D36 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

### payment-service

D37 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D38 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

### inventory-service

D39 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D40 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

### gateway-mock

D41 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D42 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

## Event counts

### order events

D43 Total: previous 12, incident 15.

| type | outcome | previous | incident |
|---|---|---|---|
D44 | INVENTORY_RESERVED | CREATED | 4 | 5 |
D45 | ORDER_RECEIVED | CREATED | 4 | 5 |
D46 | PAYMENT_AUTHORIZED | PAID | 4 | 5 |

### payment events

D47 Total: previous 4, incident 5.

| type | outcome | previous | incident |
|---|---|---|---|
D48 | PAYMENT_ATTEMPT | AUTHORIZED | 4 | 5 |

### reservation events

D49 Total: previous 4, incident 5.

| type | outcome | previous | incident |
|---|---|---|---|
D50 | RESERVATION_ATTEMPT | RESERVED | 4 | 5 |
