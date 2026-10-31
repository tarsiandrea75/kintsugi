"""The caller -> callee service pairs in RCAEval `traces.csv` files (odd/tasks/rcaeval-converter.md T2 and T4, AC3).

A call shows in two ways, and a pair comes from either:

- a client span: its `operationName` is `hipstershop.<Name>Service/<Method>` (`callee_of`) naming a service other
  than the span's own, so the span's service calls that one. This sees calls to services that emit no spans
  (`cartservice`, `adservice`, `shippingservice`) and calls that failed before reaching the callee;
- a server span: its parent span belongs to another service, so the parent's service called the span's. This sees
  callers whose spans carry no `operationName` (`recommendationservice`).

The union of those pairs over some case directories is the call graph the traces saw, which the topology file's
edges must cover.

The names live here only: `service_of` (the evaluator's normalization, then `ALIASES`, the trace names that differ
from the metric prefixes the topology uses) and `callee_of` (the operation pattern). The converter's traffic builder
(`traffic.py`) uses both, so this check and the converted traffic agree on every name.

The files are large (about 70 MB each), so each is streamed twice: once to map every span to its service, once to
join each span to its parent's service. Memory holds only that map and the pairs. The module uses the standard
library only and imports nothing from the package, so it runs as a plain script under `python3 -I`:

    python3 -I evals/kintsugi_evals/rcaeval/trace_edges.py \\
        --topology agent/src/main/resources/topology/re2-ob.json ~/data/rcaeval/RE2-OB/*/1

It exits 1 when it finds no pair at all, or, with `--topology`, when a pair is not an edge of that file.
"""

import argparse
import csv
import json
import re
import sys
from pathlib import Path

# Trace service name -> topology (metric prefix) name, after `normalize`. RE2-OB repetition 1 (T2, T4): the seven
# services that emit spans are named as their metric prefixes, except the frontend.
ALIASES = {"frontendservice": "frontend"}

# A client span's operation: `hipstershop.<Name>Service/<Method>`, anchored, so the callee's server spans
# (`grpc.hipstershop...`), the HTTP roots (`frontend`), exporters and health checks do not match.
CLIENT_OPERATION = re.compile(r"^hipstershop\.(?P<service>[A-Za-z]+Service)/[A-Za-z]+$")


def normalize(name):
    """The service a name belongs to, as RCAEval's evaluator names it (`RCAEval/benchmark/scoring.py`
    `to_service`, tag 1.11.0): lowercase, the part before the first underscore, a database container folded into its
    service (`carts-db` -> `carts`)."""
    return name.lower().split("_")[0].replace("-db", "")


def service_of(trace_name):
    """The topology service of a trace's `serviceName`: normalized, then aliased."""
    name = normalize(trace_name)
    return ALIASES.get(name, name)


def callee_of(operation_name):
    """The service a client span's operation names (`hipstershop.ProductCatalogService/ListProducts` ->
    `productcatalogservice`), or None. A span is a client span when this names a service other than its own."""
    match = CLIENT_OPERATION.match(operation_name)
    return service_of(match.group("service")) if match else None


def pairs_in(traces_csv):
    """The (caller, callee) service pairs in one `traces.csv`, from client spans and from parent links; self-pairs and
    spans whose parent is absent are ignored."""
    services = {}
    pairs = set()
    with open(traces_csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            # Interning keeps one string per service instead of one per span.
            service = services[row["spanID"]] = sys.intern(service_of(row["serviceName"]))
            callee = callee_of(row["operationName"])
            if callee is not None and callee != service:
                pairs.add((service, callee))
    with open(traces_csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            caller = services.get(row["parentSpanID"])
            callee = services[row["spanID"]]
            if caller is not None and caller != callee:
                pairs.add((caller, callee))
    return pairs


def topology_edges(path):
    """The (caller, target) pairs of a topology file's edges, without the entry edge."""
    edges = json.loads(Path(path).read_text(encoding="utf-8"))["edges"]
    return {(e["caller"], e["target"]) for e in edges if e["caller"] is not None}


def main(argv=None):
    """Print the union of the pairs in each case directory's `traces.csv`, one `caller -> callee` per line, sorted,
    then a count line. With `--topology`, mark each pair `edge` or `NOT AN EDGE` of that file."""
    parser = argparse.ArgumentParser(prog="trace_edges.py", description=__doc__.splitlines()[0])
    parser.add_argument("--topology", help="a topology file whose edges must cover every pair")
    parser.add_argument("case_dirs", nargs="+", metavar="CASE_DIR")
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    pairs = set()
    for case_dir in args.case_dirs:
        pairs |= pairs_in(Path(case_dir) / "traces.csv")
    if not pairs:
        print(f"no caller -> callee pair in {len(args.case_dirs)} case directories", file=sys.stderr)
        return 1
    edges = topology_edges(args.topology) if args.topology else None
    missing = 0
    for caller, callee in sorted(pairs):
        if edges is None:
            print(f"{caller} -> {callee}")
        elif (caller, callee) in edges:
            print(f"{caller} -> {callee}  edge")
        else:
            missing += 1
            print(f"{caller} -> {callee}  NOT AN EDGE")
    summary = f"{len(pairs)} pairs from {len(args.case_dirs)} case directories"
    if edges is not None:
        name = Path(args.topology).name
        summary += f"; all are edges of {name}" if not missing else f"; {missing} not an edge of {name}"
    print(summary)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
