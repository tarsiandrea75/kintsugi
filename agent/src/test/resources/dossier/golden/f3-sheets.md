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
D12 - inbound: no rows in the incident window.
D13 - latency /authorize: incident mean 0.012 s, max 0.012 s (previous mean 0.012 s).
D14 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D15 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D16 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0

## inventory-service

D17 - calls: none; called by: order-service.
D18 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 58.1 s.
D19 - inbound POST /reservations status=201 outcome=SUCCESS: incident 6 (previous 4).
D20 - latency /reservations: incident mean 0.022 s, max 0.022 s (previous mean 0.021 s).
D21 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D22 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D23 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D24 - reservation events: total incident 6 (previous 4).
D25 - reservation events type=RESERVATION_ATTEMPT outcome=RESERVED: incident 6 (previous 4).

## payment-service

D26 - calls: gateway-mock; called by: order-service.
D27 - restarts in the incident window: 0; uptime at gathering: 1 h 59 min 49.3 s.
D28 - inbound: no rows in the incident window.
D29 - latency /payments: incident mean 0.045 s, max 0.046 s (previous mean 0.045 s).
D30 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D31 - outbound to gateway-mock: no rows in the incident window.
D32 - WARN: count_logs=0; search_logs returned=0 truncated=false signatures=0
D33 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D34 - payments table: no rows in the incident window.
D35 - payment events: total incident 0 (previous 4).

## order-service

D36 - calls: inventory-service, payment-service; called by: external client.
D37 - restarts in the incident window: 1 (2026-10-16T09:53:12.400Z); uptime at gathering: 6 min 47.6 s.
D38 - inbound POST /orders status=201 outcome=SUCCESS: incident 6 (previous 4).
D39 - latency /orders: incident mean 0.046 s, max 0.081 s (previous mean 0.080 s).
D40 - resources processor_usage, memory_used, open_files: query_metric failed: outcome=CLIENT_ERROR reason="not recorded in this replay: query_metric {\"metr"(cut)
D41 - outbound to inventory-service status=201 outcome=SUCCESS exception=none: incident 6 (previous 4).
D42 - outbound to payment-service status=CLIENT_ERROR outcome=UNKNOWN exception=ConnectException: incident 6 (previous 0).
D43 - WARN: count_logs=6; search_logs returned=6 truncated=false signatures=1
D44   - count=6 newest=2026-10-16T09:57:52.106123456Z untrustedText="Payment call failed for order 7d3a2b1c-0e9f-4a8b-b7c6-d5e4f3a2b106: I/O error on POST request for \"http://payment-service:9999/payments\": null; exception=java.nio.channels.ClosedChannelException cause=java.net.ConnectException: Connection refused"
D45 - ERROR: count_logs=0; search_logs returned=0 truncated=false signatures=0
D46 - orders table status=PAYMENT_PENDING: incident 6 (previous 0).
D47 - order events: total incident 18 (previous 12).
D48 - order events type=INVENTORY_RESERVED outcome=CREATED: incident 6 (previous 4).
D49 - order events type=ORDER_RECEIVED outcome=CREATED: incident 6 (previous 4).
D50 - order events type=PAYMENT_UNAVAILABLE outcome=PAYMENT_PENDING: incident 6 (previous 0).

## Seen only in the previous window

### gateway-mock

D51 - inbound POST /authorize status=200 outcome=SUCCESS: incident 0 (previous 4).

### payment-service

D52 - inbound POST /payments status=201 outcome=SUCCESS: incident 0 (previous 4).
D53 - outbound to gateway-mock status=200 outcome=SUCCESS exception=none: incident 0 (previous 4).
D54 - payments table status=AUTHORIZED: incident 0 (previous 4).
D55 - payment events type=PAYMENT_ATTEMPT outcome=AUTHORIZED: incident 0 (previous 4).

### order-service

D56 - outbound to payment-service status=201 outcome=SUCCESS exception=none: incident 0 (previous 4).
D57 - orders table status=PAID: incident 0 (previous 4).
D58 - order events type=PAYMENT_AUTHORIZED outcome=PAID: incident 0 (previous 4).
