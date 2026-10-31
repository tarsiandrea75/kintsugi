"""Tests of the RCAEval trace cross-check (odd/tasks/rcaeval-converter.md T2): caller -> callee service pairs from
the parent links of `traces.csv` spans, with names normalized as RCAEval's evaluator does."""

import csv
import json

from kintsugi_evals.rcaeval.trace_edges import main, normalize, pairs_in

HEADER = ["time", "traceID", "spanID", "serviceName", "methodName", "operationName", "startTimeMillis", "startTime",
          "duration", "statusCode", "parentSpanID"]


def write_traces(case_dir, spans):
    """A case directory whose traces.csv holds `spans`, each (spanID, serviceName, parentSpanID)."""
    case_dir.mkdir(parents=True)
    with open(case_dir / "traces.csv", "w", newline="") as f:
        out = csv.writer(f)
        out.writerow(HEADER)
        for span, service, parent in spans:
            out.writerow(["00:00", "t1", span, service, "M", "op", "0", "0", "1", "0.0", parent])
    return case_dir


def test_normalize_folds_indicators_case_and_db_containers():
    assert normalize("frontend") == "frontend"
    assert normalize("CartService") == "cartservice"
    assert normalize("carts-db") == "carts"
    assert normalize("orders_latency-50") == "orders"


def test_a_child_span_pairs_its_parents_service_with_its_own(tmp_path):
    # The child (b) comes before its parent (a) in the file: the join must not depend on the order.
    case = write_traces(tmp_path / "c1", [
        ("b", "checkoutservice", "a"),
        ("a", "frontend", ""),
        ("c", "paymentservice", "b"),
    ])
    assert pairs_in(case / "traces.csv") == {("frontend", "checkoutservice"), ("checkoutservice", "paymentservice")}


def test_self_pairs_unknown_parents_and_root_spans_are_ignored(tmp_path):
    case = write_traces(tmp_path / "c1", [
        ("a", "frontend", ""),
        ("b", "frontend", "a"),
        ("c", "cartservice", "missing"),
        ("d", "carts-db", "e"),
        ("e", "carts", "a"),
    ])
    # frontend -> frontend is a self-pair; c's parent is not in the file; carts -> carts-db folds into carts.
    assert pairs_in(case / "traces.csv") == {("frontend", "carts")}


def test_main_prints_the_union_of_the_cases_pairs(tmp_path, capsys):
    one = write_traces(tmp_path / "c1", [("a", "frontend", ""), ("b", "adservice", "a")])
    two = write_traces(tmp_path / "c2", [("a", "frontend", ""), ("b", "adservice", "a"), ("c", "cartservice", "a")])
    assert main([str(one), str(two)]) == 0
    assert capsys.readouterr().out.splitlines() == [
        "frontend -> adservice",
        "frontend -> cartservice",
        "2 pairs from 2 case directories",
    ]


def test_pairs_use_the_alias_table(tmp_path):
    case = write_traces(tmp_path / "c1", [("a", "frontendservice", ""), ("b", "checkoutservice", "a")])
    assert pairs_in(case / "traces.csv") == {("frontend", "checkoutservice")}


def test_no_pair_at_all_exits_non_zero(tmp_path, capsys):
    empty = write_traces(tmp_path / "c1", [("a", "frontend", "")])
    assert main([str(empty)]) == 1
    assert "no caller -> callee pair" in capsys.readouterr().err


def topology(tmp_path, edges):
    path = tmp_path / "topology.json"
    path.write_text(json.dumps({"services": [], "edges": [
        {"caller": caller, "target": target, "method": "CALL", "uri": "/"} for caller, target in edges]}))
    return path


def test_topology_check_passes_when_every_pair_is_an_edge(tmp_path, capsys):
    case = write_traces(tmp_path / "c1", [("a", "frontendservice", ""), ("b", "adservice", "a")])
    path = topology(tmp_path, [(None, "frontend"), ("frontend", "adservice"), ("frontend", "cartservice")])
    assert main(["--topology", str(path), str(case)]) == 0
    assert capsys.readouterr().out.splitlines() == [
        "frontend -> adservice  edge",
        "1 pairs from 1 case directories; all are edges of topology.json",
    ]


def test_topology_check_fails_on_a_pair_that_is_not_an_edge(tmp_path, capsys):
    case = write_traces(tmp_path / "c1", [("a", "frontend", ""), ("b", "adservice", "a"), ("c", "redis", "b")])
    path = topology(tmp_path, [(None, "frontend"), ("frontend", "adservice")])
    assert main(["--topology", str(path), str(case)]) == 1
    assert capsys.readouterr().out.splitlines() == [
        "adservice -> redis  NOT AN EDGE",
        "frontend -> adservice  edge",
        "2 pairs from 1 case directories; 1 not an edge of topology.json",
    ]


def test_a_client_span_pairs_its_service_with_the_callee_its_operation_names(tmp_path):
    case = tmp_path / "c1"
    case.mkdir()
    with open(case / "traces.csv", "w", newline="") as f:
        out = csv.writer(f)
        out.writerow(HEADER)
        # A client span to cartservice, which emits no spans: no child, yet a pair.
        out.writerow(["00:00", "t1", "a", "frontendservice", "M", "frontend", "0", "0", "1", "", ""])
        out.writerow(["00:00", "t1", "b", "frontendservice", "M", "hipstershop.CartService/GetCart", "0", "0", "1",
                      "14.0", "a"])
        # A server span named after its own service is not a client span.
        out.writerow(["00:00", "t1", "c", "checkoutservice", "M", "hipstershop.CheckoutService/PlaceOrder", "0", "0",
                      "1", "0.0", "a"])
    assert pairs_in(case / "traces.csv") == {("frontend", "cartservice"), ("frontend", "checkoutservice")}
