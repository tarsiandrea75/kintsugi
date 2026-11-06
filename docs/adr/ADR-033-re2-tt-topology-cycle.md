# ADR-033: RE2-TT's topology drops the seat-to-travel back edges

## Status

Accepted (2026-11-06). The human made this decision before holdout TT was read. Feature: [`odd/tasks/m4-plans.md`](../../odd/tasks/m4-plans.md) T4.

## Date

2026-11-06

## Context

- Holdout TT (ADR-032) needs a topology file, `agent/src/main/resources/topology/re2-tt.json`, written by the method of the RE2-OB and RE2-SS files ([ADR-020](ADR-020-rcaeval-dataset-converter.md) Decision 6; `odd/tasks/rcaeval-converter.md` T2):
  - the services are the `simple_metrics.csv` header prefixes, normalized as RCAEval's `to_service` does;
  - the edges come from the upstream sources and manifests at a pinned commit, one `CALL /` edge per caller → target pair, plus the entry edge.
- The pin is Train Ticket `v0.1.0` (`56b95d722806f608a212ab5dfc5005f2130d814d`), whose service set matches the data's.
- Train Ticket's call graph has a real cycle at that tag:
  - `ts-travel-service` → `ts-seat-service`: `ts-travel-service/src/main/java/travel/service/TravelServiceImpl.java:369` (`http://ts-seat-service:18898/api/v1/seatservice/seats/left_tickets`). `ts-travel2-service` → `ts-seat-service` likewise, `Travel2ServiceImpl.java:386`.
  - `ts-seat-service` → `ts-travel-service`: `ts-seat-service/src/main/java/seat/service/SeatServiceImpl.java:49` and `:71` (`travelservice/routes/`, `travelservice/train_types/`).
  - `ts-seat-service` → `ts-travel2-service`: `SeatServiceImpl.java:85` and `:107` (`travel2service/routes/`, `travel2service/train_types/`).
  - A second method repeats the four calls (`:190`, `:213` to travel; `:228`, `:251` to travel2).
- `Topology.validate` rejects a file with a cycle, because the leaf-first order, the gather order and the rules' walk down the call path need an acyclic graph.
- `agent/src/main/resources/topology/` freezes on 2026-12-01 (vision §7, M4). A change to `Topology` would land in the agent's rules-ordering code just before the freeze, for one holdout.

## Decision

- `re2-tt.json` keeps `ts-travel-service` → `ts-seat-service` and `ts-travel2-service` → `ts-seat-service`, the main request direction: a travel query asks the seat service for the tickets left.
- It drops the back edges `ts-seat-service` → `ts-travel-service` and `ts-seat-service` → `ts-travel2-service`, as a documented modelling choice. No other edge is dropped: with these two removed the graph is acyclic.
- `Topology` and every other agent class stay unchanged.
- `TopologyTest` pins the file: 68 services, 153 edges, and the two back edges absent.

Two related choices of the same file:

- Four measured prefixes, `ts-food-mysql`, `ts-notification-mongo`, `ts-preserve-mongo` and `ts-preserve-other-mongo`, are services with no edge. No source at `v0.1.0` connects to them: the last three appear only in commented-out `host:` lines of their services' `application.yml`, and `ts-food-mysql` appears nowhere. They carry metrics, so they stay services, as `redis` and `session` do in the RE2-OB and RE2-SS files; the method takes edges from the sources only, so none is added for them.
- `istio-init`, Istio's init container, is no component of the application and is left out, as in the RE2-OB file.

## Alternatives considered

- **An SCC-tolerant `Topology`**, which collapses a strongly connected component or orders it by file order. Rejected: it changes the rules' ordering code in the agent shortly before the freeze, for one holdout, and every lab and RE2 result would rest on a changed class.
- **Drop `ts-travel-service` → `ts-seat-service` instead.** Rejected: it is the main flow of a ticket query, and a seat fault would then no longer sit below the travel services on the call path.

## Consequences

### Positive

- The agent replays RE2-TT with the unchanged `Topology`, before the freeze.
- Config 0, Config 0R and D all read the same file, so the simplification affects them alike and D − 0R stays fair on TT.

### Negative

- Errors that flow from `ts-seat-service` back to the travel services are unexplained by the graph.
- The L2 owners-on-path check (ADR-028, Decision 4) may misfire when only the dropped direction connects two services: for example a claimed cause `ts-seat-service` with evidence owned by a service that only the travel services call.
- How many TT cases have their root cause at the travel or seat services is not inspected, because that would read holdout truth. The effect of the simplification is therefore unknown and is stated in `docs/benchmark-limits.md`.
