# Evidence dossier, by service

D1 - Incident window: the last 10 min, 2026-10-16T09:50:00Z to 2026-10-16T10:00:00Z.
D2 - Previous window: the 10 min before it, 2026-10-16T09:40:00Z to 2026-10-16T09:50:00Z.
D3 - Gathered at 2026-10-16T10:00:00Z. All times are UTC.
D4 - Counts are written "incident N (previous M)", N in the incident window and M in the previous one. "-" means no value.
D5 - Text copied from the lab is quoted as untrustedText="...".
D6 - One section per service, each after the services it calls; then the rows seen only in the previous window, under their service.
D7 - inbound: the requests a service answered; outbound: the calls it made; both from traffic_counts, which leaves out rows of count 0.
D8 - latency: seconds per request on the server side, from query_metric latency_avg; the mean and max of the points with a value in each window. /actuator/* uris are left out.
D9 - resources: processor_usage, memory_used and open_files from query_metric, in the units of the source; the mean and max of the points with a value in each window.

## gateway-mock

D10 - calls: none; called by: payment-service.
D11 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 58.8 s.
D12 - inbound POST /authorize status=200 outcome=SUCCESS: incident 5 (previous 4).
D13 - latency /authorize: incident mean 8.898 s, max 10.015 s (previous mean 0.012 s).
D14 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D15 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D16 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

## inventory-service

D17 - calls: none; called by: order-service.
D18 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 58.1 s.
D19 - inbound POST /reservations status=201 outcome=SUCCESS: incident 5 (previous 4).
D20 - latency /reservations: incident mean 0.021 s, max 0.022 s (previous mean 0.021 s).
D21 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D22 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D23 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D24 - reservation events: total incident 5 (previous 4).
D25 - reservation events type=RESERVATION_ATTEMPT outcome=RESERVED: incident 5 (previous 4).

## payment-service

D26 - calls: gateway-mock; called by: order-service.
D27 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 49.3 s.
D28 - inbound POST /payments status=201 outcome=SUCCESS: incident 5 (previous 4).
D29 - latency /payments: incident mean 8.917 s, max 10.033 s (previous mean 0.045 s).
D30 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D31 - outbound to gateway-mock status=200 outcome=SUCCESS exception=none: incident 5 (previous 4).
D32 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D33 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D34 - payments table status=AUTHORIZED: incident 5 (previous 4).
D35 - payment events: total incident 5 (previous 4).
D36 - payment events type=PAYMENT_ATTEMPT outcome=AUTHORIZED: incident 5 (previous 4).

## order-service

D37 - calls: inventory-service, payment-service; called by: external client.
D38 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 39.7 s.
D39 - inbound POST /orders status=201 outcome=SUCCESS: incident 5 (previous 4).
D40 - latency /orders: incident mean 0.080 s, max 0.083 s (previous mean 0.080 s).
D41 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D42 - outbound to inventory-service status=201 outcome=SUCCESS exception=none: incident 5 (previous 4).
D43 - outbound to payment-service status=201 outcome=SUCCESS exception=none: incident 5 (previous 4).
D44 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D45 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D46 - orders table status=PAID: incident 5 (previous 4).
D47 - order events: total incident 15 (previous 12).
D48 - order events type=INVENTORY_RESERVED outcome=CREATED: incident 5 (previous 4).
D49 - order events type=ORDER_RECEIVED outcome=CREATED: incident 5 (previous 4).
D50 - order events type=PAYMENT_AUTHORIZED outcome=PAID: incident 5 (previous 4).

## Seen only in the previous window

D51 - no rows.
