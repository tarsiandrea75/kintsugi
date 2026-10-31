"""Tests of the RCAEval trace cross-check (odd/tasks/rcaeval-converter.md T2): caller -> callee service pairs from
the parent links of `traces.csv` spans, with names normalized as RCAEval's evaluator does."""

import csv

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
