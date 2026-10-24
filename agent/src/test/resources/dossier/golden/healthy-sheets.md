# Evidence dossier, by service

D1 - Incident window: the last 10 min, 2026-10-16T09:50:00Z to 2026-10-16T10:00:00Z.
D2 - Previous window: the 10 min before it, 2026-10-16T09:40:00Z to 2026-10-16T09:50:00Z.
D3 - Gathered at 2026-10-16T10:00:00Z. All times are UTC.
D4 - Counts are written "incident N (previous M)", N in the incident window and M in the previous one. "-" means no value.
D5 - Text copied from the lab is quoted as untrustedText="...".
D6 - One section per service, each after the services it calls; then the rows seen only in the previous window, under their service.
D7 - inbound: the requests a service answered; outbound: the calls it made; both from traffic_counts, which leaves out rows of count 0.
D8 - latency: seconds per request on the server side, from query_metric latency_avg; the mean and max of the points with a value in each window. /actuator/* uris are left out.

## gateway-mock

D9 - calls: none; called by: payment-service.
D10 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 58.8 s.
D11 - inbound POST /authorize status=200 outcome=SUCCESS: incident 5 (previous 4).
D12 - latency /authorize: incident mean 0.012 s, max 0.013 s (previous mean 0.011 s).
D13 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D14 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

## inventory-service

D15 - calls: none; called by: order-service.
D16 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 58.1 s.
D17 - inbound POST /reservations status=201 outcome=SUCCESS: incident 5 (previous 4).
D18 - latency /reservations: incident mean 0.021 s, max 0.022 s (previous mean 0.021 s).
D19 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D20 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D21 - reservation events: total incident 5 (previous 4).
D22 - reservation events type=RESERVATION_ATTEMPT outcome=RESERVED: incident 5 (previous 4).

## payment-service

D23 - calls: gateway-mock; called by: order-service.
D24 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 49.3 s.
D25 - inbound POST /payments status=201 outcome=SUCCESS: incident 5 (previous 4).
D26 - latency /payments: incident mean 0.045 s, max 0.046 s (previous mean 0.046 s).
D27 - outbound to gateway-mock status=200 outcome=SUCCESS exception=none: incident 5 (previous 4).
D28 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D29 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D30 - payments table status=AUTHORIZED: incident 5 (previous 4).
D31 - payment events: total incident 5 (previous 4).
D32 - payment events type=PAYMENT_ATTEMPT outcome=AUTHORIZED: incident 5 (previous 4).

## order-service

D33 - calls: inventory-service, payment-service; called by: external client.
D34 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 39.7 s.
D35 - inbound POST /orders status=201 outcome=SUCCESS: incident 5 (previous 4).
D36 - latency /orders: incident mean 0.080 s, max 0.083 s (previous mean 0.080 s).
D37 - outbound to inventory-service status=201 outcome=SUCCESS exception=none: incident 5 (previous 4).
D38 - outbound to payment-service status=201 outcome=SUCCESS exception=none: incident 5 (previous 4).
D39 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D40 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D41 - orders table status=PAID: incident 5 (previous 4).
D42 - order events: total incident 15 (previous 12).
D43 - order events type=INVENTORY_RESERVED outcome=CREATED: incident 5 (previous 4).
D44 - order events type=ORDER_RECEIVED outcome=CREATED: incident 5 (previous 4).
D45 - order events type=PAYMENT_AUTHORIZED outcome=PAID: incident 5 (previous 4).

## Seen only in the previous window

D46 - no rows.
