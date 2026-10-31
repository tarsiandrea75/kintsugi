"""`traffic_counts` answers from an RCAEval `traces.csv` (odd/tasks/rcaeval-converter.md T4, ADR-020).

The lab counts HTTP requests per edge from Micrometer counters; Online Boutique's traces give the same facts per
gRPC call, as two kinds of rows, as mcp-tools' `TrafficCounts.Row` prints them:

- a client row per client span: a span whose `operationName` is `hipstershop.<Name>Service/<Method>` naming a service
  other than its own (`trace_edges.callee_of`). `service` is the span's service, `clientName` the named callee, the
  status the client span's own, `exception` `none`. Other spans (server spans, HTTP roots, exporters, health checks,
  and the spans of `recommendationservice` and `emailservice`, which carry no `operationName`) give no client row.
  Client spans see calls to services that emit no spans (`cartservice`, `adservice`, `shippingservice`) and calls that
  failed before reaching the callee, which leave no server span.
- a server row per server span: a span whose parent span belongs to another service. `service` is the span's service,
  the status its own, no `clientName` and no `exception`, as the lab's server series carry neither label. The caller
  may be outside the topology: the callee answered either way.

The two rules never count one span twice: a client span's parent is its own service's span, and a server span's
operation names its own service or no `hipstershop.` service, so neither is the other. One call is one client row
(the caller's span) plus, when the callee emits spans, one server row (the callee's).

`method` is `CALL` and `uri` `/`, the convention of the RCAEval topology files' edges, so `Topology.Edge.callerRow` and
`serverRow` match the rows. A row counts in the window that holds its own span's start time; windows are half-open,
[from, until). A row naming a service outside the topology after aliasing is dropped and reported as
`unknown_trace_service`, one count per dropped row.

`statusCode` is the span's gRPC status code (RE2-OB repetition 1 holds 0, 2, 4, 13, 14 and blank). It maps to the
HTTP status of Google's `google.rpc.Code` table and to Spring's outcome of that status (`status_of`). Traces carry
no exception class, so `exception` is always `none`: UNAVAILABLE (14) becomes 503, an error answer for the rules'
"refusing" test, not a connection failure for R-REACH, and DEADLINE_EXCEEDED (4) becomes 504, not a timeout.
"""

import csv
import sys
from collections import Counter
from pathlib import Path

from .trace_edges import callee_of, service_of

METHOD = "CALL"
URI = "/"
MAX_OUTPUT_BYTES = 8192  # mcp-tools ToolCallRunner.MAX_OUTPUT_BYTES: a longer answer would be cut by the lab

# google.rpc.Code -> HTTP status (googleapis `google/rpc/code.proto`, as grpc-gateway maps them).
GRPC_TO_HTTP = {0: "200", 1: "499", 2: "500", 3: "400", 4: "504", 5: "404", 6: "409", 7: "403", 8: "429", 9: "400",
                10: "409", 11: "400", 12: "501", 13: "500", 14: "503", 15: "500", 16: "401"}

# Spring's `Outcome.forStatus`, by the status's hundreds.
OUTCOMES = {1: "INFORMATIONAL", 2: "SUCCESS", 3: "REDIRECTION", 4: "CLIENT_ERROR", 5: "SERVER_ERROR"}


def status_of(code_text):
    """`(status, outcome)` of a span's `statusCode`. Blank is OpenTelemetry's unset status, which marks no error, so
    it reads as OK; anything else must be a gRPC code (0-16), written as an integer or a float."""
    text = code_text.strip()
    if not text:
        return GRPC_TO_HTTP[0], OUTCOMES[2]
    try:
        value = float(text)
    except ValueError:
        value = None
    if value is None or not value.is_integer() or int(value) not in GRPC_TO_HTTP:
        raise ValueError(f"statusCode {code_text!r} is not a gRPC status code")
    status = GRPC_TO_HTTP[int(value)]
    return status, OUTCOMES[int(status) // 100]


def _row_order(key):
    """mcp-tools' `TrafficCounts` order: side, service, clientName (absent first), then status and outcome."""
    side, service, client, status, outcome = key
    return side, service, (client is not None, client or ""), status, outcome


def _items(counts):
    items = []
    for key in sorted(counts, key=_row_order):
        side, service, client, status, outcome = key
        item = {"side": side, "service": service}
        if side == "client":
            item["clientName"] = client
        item.update(method=METHOD, uri=URI, status=status, outcome=outcome)
        if side == "client":
            item["exception"] = "none"
        item["count"] = counts[key]
        items.append(item)
    return items


def counts(traces_csv, services, windows, report):
    """The `traffic_counts` items for each window `(from_seconds, until_seconds)` of epoch seconds, in order.

    The file is streamed twice: once to map every span id to its service (interned, so memory is the span-id map),
    once to make the rows."""
    known = set(services)
    spans = {}
    with Path(traces_csv).open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            spans[row["spanID"]] = sys.intern(service_of(row["serviceName"]))
    bounds = [(start * 10**6, until * 10**6) for start, until in windows]
    per_window = [Counter() for _ in windows]
    unknown = Counter()

    def add(window, key, names):
        outside = [name for name in names if name not in known]
        for name in outside:
            unknown[name] += 1
        if not outside:
            per_window[window][key] += 1

    with Path(traces_csv).open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            start = int(row["startTime"])
            window = next((i for i, (lo, hi) in enumerate(bounds) if lo <= start < hi), None)
            if window is None:
                continue
            service = spans[row["spanID"]]
            callee = callee_of(row["operationName"])
            if callee is not None and callee != service:
                add(window, ("client", service, callee) + status_of(row["statusCode"]), (service, callee))
            parent = spans.get(row["parentSpanID"])
            if parent is not None and parent != service:
                add(window, ("server", service, None) + status_of(row["statusCode"]), (service,))
    for name, n in sorted(unknown.items()):
        report.append(("unknown_trace_service", name, n))
    return [_items(c) for c in per_window]


def check_size(rendered):
    """Fails on an answer the lab would have cut: the converter writes whole answers only."""
    size = len(rendered.encode("utf-8"))
    if size > MAX_OUTPUT_BYTES:
        raise ValueError(f"a traffic_counts answer of {size} bytes is over the lab's {MAX_OUTPUT_BYTES}-byte cap")
    return rendered

