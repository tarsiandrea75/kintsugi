# Service runbooks

One runbook per lab service. Each one tells an operator what the service does, which signals to read, and how to tell the possible causes of a symptom apart.

| Runbook | Service | Host port |
|---------|---------|-----------|
| [order-service.md](order-service.md) | Order entry point: reserves stock, stores orders, takes them through payment | 18080 |
| [payment-service.md](payment-service.md) | Records payments using the gateway's outcome | 18081 |
| [inventory-service.md](inventory-service.md) | Holds and reserves stock per SKU | 18083 |
| [gateway-mock.md](gateway-mock.md) | Stands in for the external payment gateway | 18082 |

Call path: client → order-service → inventory-service, then order-service → payment-service → gateway-mock. Each service except gateway-mock has its own database on the shared `postgres` container.

## How to use them

1. Start from the service where the symptom shows, usually order-service.
2. Find the symptom in section 6 and run the "How to check" queries for every listed cause. Several causes can produce the same symptom; let the evidence choose.
3. When the evidence points at a dependency, continue in that dependency's runbook.
4. Use only the actions in section 7. Each one is safe to run in the lab.

Where to run queries:

| Query | Where |
|-------|-------|
| PromQL | Grafana Explore at <http://localhost:13000>, or `curl -s localhost:19090/api/v1/query --data-urlencode 'query=<expr>'` |
| LogQL | Grafana Explore, Loki datasource (Loki has no host port) |
| Service APIs | `curl` on the host ports above |

Every runbook has the same sections: 1 Purpose, 2 Dependencies, 3 Endpoints, 4 Configuration, 5 Key signals, 6 Symptoms, 7 Safe actions, 8 Where the evidence is (the tables or log lines that record each request, with example queries).

## Authoring rule

Runbooks must never reference the fault catalog, its fault ids (F1 to F8), the fault injector, or any "this symptom means fault X" mapping. They describe services, signals and diagnostic steps, as real SRE runbooks do. Why: in a later milestone these runbooks become the knowledge base the agent searches, and the agent's benchmark is scored against the ground truth of each injected fault. A runbook that names the fault behind a symptom would hand the agent the answer and make the evaluation meaningless. Lab-only hooks are described as neutral "lab controls", never as diagnoses. Every metric, label, log line, property and endpoint cited must exist in the repository; copy the exact strings. Check before committing: `grep -niE '\bF[1-8]\b|fault|inject' docs/runbooks/*.md` must match only this paragraph.
