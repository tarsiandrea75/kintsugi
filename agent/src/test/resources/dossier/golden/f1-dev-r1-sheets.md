# Evidence dossier, by service

D1 - Incident window: the last 4 min, 2026-10-24T16:44:02.663991Z to 2026-10-24T16:48:02.663991Z.
D2 - Previous window: the 4 min before it, 2026-10-24T16:40:02.663991Z to 2026-10-24T16:44:02.663991Z.
D3 - Gathered at 2026-10-24T16:48:02.663991Z. All times are UTC.
D4 - Counts are written "incident N (previous M)", N in the incident window and M in the previous one. "-" means no value.
D5 - Text copied from the lab is quoted as untrustedText="...".
D6 - One section per service, each after the services it calls; then the rows seen only in the previous window, under their service.
D7 - inbound: the requests a service answered; outbound: the calls it made; both from traffic_counts, which leaves out rows of count 0.
D8 - latency: seconds per request on the server side, from query_metric latency_avg; the mean and max of the points with a value in each window. /actuator/* uris are left out.

## gateway-mock

D9 - calls: none; called by: payment-service.
D10 - restarts in the incident window: 0; uptime at gathering: 21 min 50.078 s.
D11 - inbound POST /authorize status=200 outcome=SUCCESS: incident 12 (previous 5).
D12 - latency /authorize: incident mean 1.177 s, max 9.905 s (previous mean 0.002 s).
D13 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D14 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

## inventory-service

D15 - calls: none; called by: order-service.
D16 - restarts in the incident window: 0; uptime at gathering: 21 min 42.901 s.
D17 - inbound POST /reservations status=201 outcome=SUCCESS: incident 12 (previous 5).
D18 - latency /reservations: incident mean 0.007 s, max 0.013 s (previous mean 0.006 s).
D19 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D20 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D21 - reservation events: total incident 12 (previous 5).
D22 - reservation events type=RESERVATION_ATTEMPT outcome=RESERVED: incident 12 (previous 5).

## payment-service

D23 - calls: gateway-mock; called by: order-service.
D24 - restarts in the incident window: 0; uptime at gathering: 21 min 42.322 s.
D25 - inbound POST /payments status=201 outcome=SUCCESS: incident 12 (previous 5).
D26 - latency /payments: incident mean 1.229 s, max 9.979 s (previous mean 0.015 s).
D27 - outbound to gateway-mock status=200 outcome=SUCCESS exception=none: incident 12 (previous 5).
D28 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D29 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D30 - payments table status=AUTHORIZED: incident 12 (previous 5).
D31 - payment events: total incident 12 (previous 5).
D32 - payment events type=PAYMENT_ATTEMPT outcome=AUTHORIZED: incident 12 (previous 5).

## order-service

D33 - calls: inventory-service, payment-service; called by: external client.
D34 - restarts in the incident window: 0; uptime at gathering: 21 min 23.784 s.
D35 - inbound POST /orders status=201 outcome=SUCCESS: incident 12 (previous 5).
D36 - latency /orders: incident mean 0.513 s, max 3.034 s (previous mean 0.051 s).
D37 - outbound to inventory-service status=201 outcome=SUCCESS exception=none: incident 12 (previous 5).
D38 - outbound to payment-service status=201 outcome=SUCCESS exception=none: incident 6 (previous 5).
D39 - outbound to payment-service status=CLIENT_ERROR outcome=UNKNOWN exception=HttpTimeoutException: incident 6 (previous 0).
D40 - WARN: count_logs=6; search_logs returned=6 truncated=false signatures=1
D41   - count=6 newest=2026-10-24T16:47:30.621298750Z untrustedText="Payment of order 35d62368-08b3-4286-8402-1c86db01804b unavailable, leaving it in PAYMENT_PENDING: event=PAYMENT_UNAVAILABLE order=35d62368-08b3-4286-8402-1c86db01804b outcome=PAYMENT_PENDING duration_ms=3001 url=\"http://payment-service:8081/payments\" exception=java.net.http.HttpTimeoutException caus"(cut)
D42 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D43 - orders table status=PAID: incident 6 (previous 5).
D44 - orders table status=PAYMENT_PENDING: incident 6 (previous 0).
D45 - order events: total incident 36 (previous 15).
D46 - order events type=INVENTORY_RESERVED outcome=PAYMENT_PENDING: incident 12 (previous 5).
D47 - order events type=ORDER_RECEIVED outcome=CREATED: incident 12 (previous 5).
D48 - order events type=PAYMENT_AUTHORIZED outcome=PAID: incident 6 (previous 5).
D49 - order events type=PAYMENT_UNAVAILABLE outcome=PAYMENT_PENDING: incident 6 (previous 0).

## Seen only in the previous window

D50 - no rows.
