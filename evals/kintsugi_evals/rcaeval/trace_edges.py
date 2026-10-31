"""The caller -> callee service pairs in RCAEval `traces.csv` files (odd/tasks/rcaeval-converter.md T2, AC3).

Each span names its service and its parent span; a span whose parent belongs to another service is one call from the
parent's service to the span's. The union of those pairs over some case directories is the call graph the traces
saw, which the topology file's edges must cover.

The files are large (about 70 MB each), so each is streamed twice: once to map every span to its service, once to
join each span to its parent's service. Memory holds only that map and the pairs. The module uses the standard
library only and imports nothing from the package, so it runs as a plain script under `python3 -I`:

    python3 -I evals/kintsugi_evals/rcaeval/trace_edges.py ~/data/rcaeval/RE2-OB/*/1
"""

import csv
import sys
from pathlib import Path


def normalize(name):
    """The service a name belongs to, as RCAEval's evaluator names it (`RCAEval/benchmark/scoring.py`
    `to_service`, tag 1.11.0): lowercase, the part before the first underscore, a database container folded into its
    service (`carts-db` -> `carts`)."""
    return name.lower().split("_")[0].replace("-db", "")


def pairs_in(traces_csv):
    """The (caller, callee) service pairs in one `traces.csv`; self-pairs and spans whose parent is absent are
    ignored."""
    services = {}
    with open(traces_csv, newline="") as f:
        for row in csv.DictReader(f):
            service = normalize(row["serviceName"])
            # Interning keeps one string per service instead of one per span.
            services[row["spanID"]] = sys.intern(service)
    pairs = set()
    with open(traces_csv, newline="") as f:
        for row in csv.DictReader(f):
            caller = services.get(row["parentSpanID"])
            callee = services[row["spanID"]]
            if caller is not None and caller != callee:
                pairs.add((caller, callee))
    return pairs


def main(argv=None):
    """Print the union of the pairs in each case directory's `traces.csv`, one `caller -> callee` per line, sorted,
    then a count line."""
    case_dirs = sys.argv[1:] if argv is None else argv
    if not case_dirs:
        print("usage: trace_edges.py CASE_DIR...", file=sys.stderr)
        return 2
    pairs = set()
    for case_dir in case_dirs:
        pairs |= pairs_in(Path(case_dir) / "traces.csv")
    for caller, callee in sorted(pairs):
        print(f"{caller} -> {callee}")
    print(f"{len(pairs)} pairs from {len(case_dirs)} case directories")
    return 0


if __name__ == "__main__":
    sys.exit(main())
