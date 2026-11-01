# Evidence dossier, by service

D1 - Incident window: the last 4 min, 2026-10-24T16:44:02.663991Z to 2026-10-24T16:48:02.663991Z.
D2 - Previous window: the 4 min before it, 2026-10-24T16:40:02.663991Z to 2026-10-24T16:44:02.663991Z.
D3 - Gathered at 2026-10-24T16:48:02.663991Z. All times are UTC.
D4 - Counts are written "incident N (previous M)", N in the incident window and M in the previous one. "-" means no value.
D5 - Text copied from the lab is quoted as untrustedText="...".
D6 - One section per service, each after the services it calls; then the rows seen only in the previous window, under their service.
D7 - inbound: the requests a service answered; outbound: the calls it made; both from traffic_counts, which leaves out rows of count 0.
D8 - latency: seconds per request on the server side, from query_metric latency_avg; the mean and max of the points with a value in each window. /actuator/* uris are left out.
D9 - resources: processor_usage, memory_used and open_files from query_metric, in the units of the source; the mean and max of the points with a value in each window.

## gateway-mock

D10 - calls: none; called by: payment-service.
D11 - restarts in the incident window: 0; uptime at gathering: 21 min 50.078 s.
D12 - inbound POST /authorize status=200 outcome=SUCCESS: incident 12 (previous 5).
D13 - latency /authorize: incident mean 1.177 s, max 9.905 s (previous mean 0.002 s).
D14 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D15 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D16 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

## inventory-service

D17 - calls: none; called by: order-service.
D18 - restarts in the incident window: 0; uptime at gathering: 21 min 42.901 s.
D19 - inbound POST /reservations status=201 outcome=SUCCESS: incident 12 (previous 5).
D20 - latency /reservations: incident mean 0.007 s, max 0.013 s (previous mean 0.006 s).
D21 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D22 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D23 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D24 - reservation events: total incident 12 (previous 5).
D25 - reservation events type=RESERVATION_ATTEMPT outcome=RESERVED: incident 12 (previous 5).

## payment-service

D26 - calls: gateway-mock; called by: order-service.
D27 - restarts in the incident window: 0; uptime at gathering: 21 min 42.322 s.
D28 - inbound POST /payments status=201 outcome=SUCCESS: incident 12 (previous 5).
D29 - latency /payments: incident mean 1.229 s, max 9.979 s (previous mean 0.015 s).
D30 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D31 - outbound to gateway-mock status=200 outcome=SUCCESS exception=none: incident 12 (previous 5).
D32 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D33 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D34 - payments table status=AUTHORIZED: incident 12 (previous 5).
D35 - payment events: total incident 12 (previous 5).
D36 - payment events type=PAYMENT_ATTEMPT outcome=AUTHORIZED: incident 12 (previous 5).

## order-service

D37 - calls: inventory-service, payment-service; called by: external client.
D38 - restarts in the incident window: 0; uptime at gathering: 21 min 23.784 s.
D39 - inbound POST /orders status=201 outcome=SUCCESS: incident 12 (previous 5).
D40 - latency /orders: incident mean 0.513 s, max 3.034 s (previous mean 0.051 s).
D41 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D42 - outbound to inventory-service status=201 outcome=SUCCESS exception=none: incident 12 (previous 5).
D43 - outbound to payment-service status=201 outcome=SUCCESS exception=none: incident 6 (previous 5).
D44 - outbound to payment-service status=CLIENT_ERROR outcome=UNKNOWN exception=HttpTimeoutException: incident 6 (previous 0).
D45 - WARN: count_logs=6; search_logs returned=6 truncated=false signatures=1
D46   - count=6 newest=2026-10-24T16:47:30.621298750Z untrustedText="Payment of order 35d62368-08b3-4286-8402-1c86db01804b unavailable, leaving it in PAYMENT_PENDING: event=PAYMENT_UNAVAILABLE order=35d62368-08b3-4286-8402-1c86db01804b outcome=PAYMENT_PENDING duration_ms=3001 url=\"http://payment-service:8081/payments\" exception=java.net.http.HttpTimeoutException caus"(cut)
D47 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D48 - orders table status=PAID: incident 6 (previous 5).
D49 - orders table status=PAYMENT_PENDING: incident 6 (previous 0).
D50 - order events: total incident 36 (previous 15).
D51 - order events type=INVENTORY_RESERVED outcome=PAYMENT_PENDING: incident 12 (previous 5).
D52 - order events type=ORDER_RECEIVED outcome=CREATED: incident 12 (previous 5).
D53 - order events type=PAYMENT_AUTHORIZED outcome=PAID: incident 6 (previous 5).
D54 - order events type=PAYMENT_UNAVAILABLE outcome=PAYMENT_PENDING: incident 6 (previous 0).

## Seen only in the previous window

D55 - no rows.
