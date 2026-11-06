"""Tests of the RCAEval converter (odd/tasks/rcaeval-converter.md T3, ADR-020), on the synthetic case tree under
`tests/fixtures/rcaeval/`: no real data is read."""

import csv
import json
import shutil
from pathlib import Path

import pytest

from kintsugi_evals.cli import main
from kintsugi_evals.rcaeval import convert, leakage, trace_edges, traffic

FIXTURE = Path(__file__).parent / "fixtures" / "rcaeval"
REPO = Path(__file__).resolve().parents[2]
OB_TOPOLOGY = REPO / "agent" / "src" / "main" / "resources" / "topology" / "re2-ob.json"
AGENT_COPY = REPO / "agent" / "src" / "test" / "resources" / "rcaeval"
INJECT = 1700000000
OB_SERVICES = json.loads(OB_TOPOLOGY.read_text())["services"]


@pytest.fixture(scope="module")
def converted(tmp_path_factory):
    out = tmp_path_factory.mktemp("re2-ob-rep1")
    summary = convert.convert(FIXTURE, "re2-ob", 1, out)
    return out, summary


def manifest(out):
    with (out / "recordings.tsv").open(newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def recording(out, case):
    row = next(r for r in manifest(out) if r["truth"].startswith(case.split("_")[0] + "|"))
    return row, json.loads((out / row["file"]).read_text())


def calls(incident):
    (step,) = incident["steps"]
    assert (step["kind"], step["phase"], step["stepNo"]) == ("DOSSIER", "DOSSIER", 1)
    return json.loads(step["toolOutput"])


def output(incident, tool, **args):
    matches = [c for c in calls(incident) if c["tool"] == tool and c["args"] == args]
    assert len(matches) == 1, (tool, args)
    assert matches[0]["outcome"] == "OK"
    return json.loads(matches[0]["output"])


# Windows and the incident document


def test_started_at_is_ten_minutes_after_the_injection_and_the_window_is_ten_minutes(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    assert incident["sinceMinutes"] == 10
    assert incident["startedAt"] == "2023-11-14T22:23:20Z"  # 1700000000 + 600 s, as Java's Instant prints it
    assert set(incident) == {"id", "symptom", "sinceMinutes", "startedAt", "steps"}


def test_the_symptom_is_the_one_fixed_sentence_of_the_system(converted):
    out, _ = converted
    symptoms = {recording(out, case)[1]["symptom"] for case in ("checkoutservice_cpu", "currencyservice_delay")}
    assert symptoms == {convert.SYMPTOMS["re2-ob"]}
    assert "frontend" in convert.SYMPTOMS["re2-ob"] and "front-end" in convert.SYMPTOMS["re2-ss"]


# The calls, exactly as DossierGatherer makes them for a topology without owners, plus the resource calls it makes
# from resource-facts T4 on (ADR-021)

RESOURCE_METRICS = ("processor_usage", "memory_used", "open_files")


def gatherer_calls(services):
    expected = [("service_restarts", {"sinceMinutes": 10}),
                ("traffic_counts", {"sinceMinutes": 20, "untilMinutesAgo": 10}),
                ("traffic_counts", {"sinceMinutes": 10})]
    expected += [("query_metric", {"metric": "latency_avg", "service": s, "sinceMinutes": 20}) for s in services]
    expected += [("query_metric", {"metric": metric, "service": s, "sinceMinutes": 20})
                 for s in services for metric in RESOURCE_METRICS]
    for service in services:
        for level in ("WARN", "ERROR"):
            expected.append(("count_logs", {"service": service, "level": level, "sinceMinutes": 10}))
            expected.append(("search_logs", {"service": service, "level": level, "sinceMinutes": 10, "limit": 20}))
    return expected


def test_the_dossier_holds_exactly_the_gatherers_calls_with_its_arguments(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    made = [(c["tool"], c["args"]) for c in calls(incident)]
    assert sorted(map(json.dumps, made)) == sorted(map(json.dumps, gatherer_calls(OB_SERVICES)))
    assert len(made) == 3 + 8 * len(OB_SERVICES)
    for call in calls(incident):
        assert list(call) == ["tool", "args", "outcome", "output"]
        assert list(json.loads(call["output"])) == ["outcome", "returned", "truncated", "items"]


def test_restarts_are_an_empty_answer(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    empty = {"outcome": "OK", "returned": 0, "truncated": False, "items": []}
    assert output(incident, "service_restarts", sinceMinutes=10) == empty


# Traffic from the traces (T4)


def traffic_row(side, service, status, outcome, count, client=None):
    """One item as mcp-tools' TrafficCounts.Row prints it: server rows carry no clientName and no exception."""
    item = {"side": side, "service": service}
    if side == "client":
        item["clientName"] = client
    item.update(method="CALL", uri="/", status=status, outcome=outcome)
    if side == "client":
        item["exception"] = "none"
    item["count"] = count
    return item


def assert_items(result, expected):
    assert result["returned"] == len(expected) and result["truncated"] is False
    assert result["items"] == expected
    assert [list(item) for item in result["items"]] == [list(item) for item in expected]


def test_previous_window_traffic_counts_cross_service_spans_before_the_injection(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    # [inject - 600 s, inject): the span at inject - 600 s counts, the one at inject - 601 s does not.
    assert_items(output(incident, "traffic_counts", sinceMinutes=20, untilMinutesAgo=10), [
        traffic_row("client", "frontend", "200", "SUCCESS", 1, client="checkoutservice"),
        traffic_row("client", "frontend", "200", "SUCCESS", 1, client="currencyservice"),
        traffic_row("server", "checkoutservice", "200", "SUCCESS", 1),
        traffic_row("server", "currencyservice", "200", "SUCCESS", 1),
    ])


def test_incident_window_traffic_has_each_sides_status_and_the_lab_row_order(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    # [inject, inject + 600 s), each row by its own span's start. Client rows come from the client spans (their
    # operationName names the callee), so a call to cartservice, which emits no spans, still counts; the client span
    # started in the previous window counts there, while its callee's span at inject + 599 s counts here as a server
    # row, and the one at inject + 600 s does not. The client row takes the client span's status, the server row the
    # callee's: UNAVAILABLE seen by the caller (503) while the callee answered OK (200). The call from an unknown
    # tracer to currencyservice is still one of currencyservice's answers.
    assert_items(output(incident, "traffic_counts", sinceMinutes=10), [
        traffic_row("client", "checkoutservice", "200", "SUCCESS", 1, client="paymentservice"),
        traffic_row("client", "frontend", "503", "SERVER_ERROR", 1, client="cartservice"),
        traffic_row("client", "frontend", "200", "SUCCESS", 1, client="checkoutservice"),
        traffic_row("client", "frontend", "500", "SERVER_ERROR", 1, client="checkoutservice"),
        traffic_row("client", "frontend", "503", "SERVER_ERROR", 1, client="checkoutservice"),
        traffic_row("server", "checkoutservice", "200", "SUCCESS", 2),
        traffic_row("server", "checkoutservice", "500", "SERVER_ERROR", 1),
        traffic_row("server", "currencyservice", "200", "SUCCESS", 2),
        traffic_row("server", "paymentservice", "504", "SERVER_ERROR", 1),
    ])


def test_every_client_row_is_a_topology_edge_with_its_method_and_uri(converted):
    out, _ = converted
    edges = {(e["caller"], e["target"], e["method"], e["uri"])
             for e in json.loads(OB_TOPOLOGY.read_text())["edges"]}
    _, incident = recording(out, "checkoutservice_cpu")
    for args in ({"sinceMinutes": 20, "untilMinutesAgo": 10}, {"sinceMinutes": 10}):
        for item in output(incident, "traffic_counts", **args)["items"]:
            if item["side"] == "client":
                assert (item["service"], item["clientName"], item["method"], item["uri"]) in edges


def test_the_grpc_status_code_maps_to_an_http_status_and_outcome():
    assert traffic.status_of("0.0") == ("200", "SUCCESS")
    assert traffic.status_of("") == ("200", "SUCCESS")  # unset: no error recorded
    assert traffic.status_of("2.0") == ("500", "SERVER_ERROR")
    assert traffic.status_of("4.0") == ("504", "SERVER_ERROR")
    assert traffic.status_of("13.0") == ("500", "SERVER_ERROR")
    assert traffic.status_of("14.0") == ("503", "SERVER_ERROR")
    assert traffic.status_of("1") == ("499", "CLIENT_ERROR")
    assert traffic.status_of("5.0") == ("404", "CLIENT_ERROR")
    for bad in ("17.0", "-1", "OK", "0.5"):
        with pytest.raises(ValueError, match="statusCode"):
            traffic.status_of(bad)


def test_trace_names_are_aliased_by_the_one_table_trace_edges_uses():
    assert traffic.service_of is trace_edges.service_of
    assert trace_edges.ALIASES == {"frontendservice": "frontend"}
    assert trace_edges.service_of("frontendservice") == "frontend"
    assert trace_edges.service_of("checkoutservice") == "checkoutservice"
    assert traffic.callee_of is trace_edges.callee_of


def test_a_client_span_is_recognized_by_the_service_its_operation_names():
    callee_of = trace_edges.callee_of
    assert callee_of("hipstershop.ProductCatalogService/ListProducts") == "productcatalogservice"
    assert callee_of("hipstershop.CartService/GetCart") == "cartservice"
    # Server spans ("grpc." prefix, or the service's own name), HTTP roots, exporters and health checks are not.
    for operation in ("grpc.hipstershop.CurrencyService/Convert", "frontend", "", "grpc.health.v1.Health/Check",
                      "opentelemetry.proto.collector.trace.v1.TraceService/Export", "hipstershop.CartService"):
        assert callee_of(operation) is None, operation


def test_trace_names_outside_the_topology_are_dropped_and_reported(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    rows = output(incident, "traffic_counts", sinceMinutes=10)["items"]
    assert not [r for r in rows if "mysterytracer" in (r["service"], r.get("clientName"))]
    report = (out / "converter-report.tsv").read_text()
    assert "\tunknown_trace_service\tmysterytracer\t1\n" in report
    assert "\tunknown_trace_service\tshoppingassistantservice\t1\n" in report
    assert not [r for r in rows if "shoppingassistantservice" in (r["service"], r.get("clientName"))]


def test_a_traffic_answer_over_the_labs_8_kb_fails_the_conversion(tmp_path, monkeypatch):
    monkeypatch.setattr(traffic, "MAX_OUTPUT_BYTES", 300)
    with pytest.raises(ValueError, match="traffic_counts"):
        convert.convert(FIXTURE, "re2-ob", 1, tmp_path / "out", cases=["checkoutservice_cpu"])


def ss_case(root):
    """A minimal RE2-SS case: Sock Shop has no traces, and a stray traces.csv must not be read."""
    case = root / "RE2-SS" / "carts_cpu" / "1"
    case.mkdir(parents=True)
    (case / "inject_time.txt").write_text(f"{INJECT}\n")
    (case / "simple_metrics.csv").write_text(f"time,carts_latency-50\n{INJECT},0.01\n")
    (case / "logs.csv").write_text("time,timestamp,container_name,message,level\n")
    (case / "traces.csv").write_text("not,a,trace,file\n")
    return case


def test_sock_shop_traffic_stays_empty(tmp_path):
    ss_case(tmp_path)
    out = tmp_path / "out"
    convert.convert(tmp_path, "re2-ss", 1, out, allow_holdout="B")
    (row,) = manifest(out)
    incident = json.loads((out / row["file"]).read_text())
    empty = {"outcome": "OK", "returned": 0, "truncated": False, "items": []}
    assert output(incident, "traffic_counts", sinceMinutes=20, untilMinutesAgo=10) == empty
    assert output(incident, "traffic_counts", sinceMinutes=10) == empty


# Latency


def latency(incident, service):
    return output(incident, "query_metric", metric="latency_avg", service=service, sinceMinutes=20)


def test_latency_is_resampled_to_the_labs_step_over_both_windows(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    result = latency(incident, "frontend")
    assert result["returned"] == 1 and result["truncated"] is False
    (series,) = result["items"]
    assert list(series) == ["labels", "points"]
    assert series["labels"] == {"service": "frontend", "uri": "/"}
    times = [t for t, _ in series["points"]]
    end = INJECT + 600
    # The lab's step for a 20-minute window: max(5, (1200 + 58) // 59) = 21 s, from end - 1200 to end.
    assert convert.LATENCY_STEP_SECONDS == 21
    assert times == list(range(end - 1200, end + 1, 21))
    assert len(times) == 58 and all(isinstance(t, int) for t in times)
    values = dict(series["points"])
    assert all(isinstance(v, str) for v in values.values())
    # Each point is the mean of the 1 s rows in (t - 21, t]: three fixture rows each.
    assert float(values[INJECT - 12]) == pytest.approx(0.1)
    assert float(values[INJECT + 9]) == pytest.approx((0.1 + 0.4 + 0.4) / 3)
    assert float(values[INJECT + 597]) == pytest.approx(0.4)


def test_latency_is_in_seconds_unchanged(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    values = dict(latency(incident, "checkoutservice")["items"][0]["points"])
    assert float(values[INJECT - 12]) == pytest.approx(0.002)
    assert float(values[INJECT + 597]) == pytest.approx(0.004)
    assert convert.LATENCY_TO_SECONDS == 1.0


def test_a_bucket_without_a_value_is_nan(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    currency = dict(latency(incident, "currencyservice")["items"][0]["points"])
    assert currency[INJECT - 600] == "NaN"  # blank cells before inject - 300 s
    assert float(currency[INJECT + 9]) == pytest.approx(0.05)
    cart = latency(incident, "cartservice")["items"][0]["points"]
    assert {v for _, v in cart} == {"NaN"}


def test_a_service_without_a_latency_column_returns_nothing(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    for service in ("redis", "adservice", "shippingservice"):
        assert latency(incident, service) == {"outcome": "OK", "returned": 0, "truncated": False, "items": []}


def test_metric_prefixes_outside_the_topology_are_reported_except_the_known_extras(converted):
    out, summary = converted
    report = (out / "converter-report.tsv").read_text()
    assert "unknown_metric_prefix\tmysteryservice" in report
    assert "frontend-external" not in report.replace("unknown_log_container\tfrontend-external", "")


# Resources (ADR-021)


def resource(incident, metric, service):
    return output(incident, "query_metric", metric=metric, service=service, sinceMinutes=20)


def test_resource_series_are_resampled_like_latency_and_labelled_with_the_service_only(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    result = resource(incident, "processor_usage", "checkoutservice")
    assert result["returned"] == 1 and result["truncated"] is False
    (series,) = result["items"]
    assert list(series) == ["labels", "points"]
    assert series["labels"] == {"service": "checkoutservice"}  # mcp-tools' `sum by (service)`: no uri
    times = [t for t, _ in series["points"]]
    end = INJECT + 600
    assert times == list(range(end - 1200, end + 1, 21)) and len(times) == 58
    values = dict(series["points"])
    assert all(isinstance(v, str) for v in values.values())
    assert float(values[INJECT - 12]) == pytest.approx(0.2)
    assert float(values[INJECT + 9]) == pytest.approx((0.2 + 0.9 + 0.9) / 3)
    assert float(values[INJECT + 597]) == pytest.approx(0.9)


def test_memory_comes_from_mem_and_open_files_from_socket_in_the_exporters_unit(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    memory = dict(resource(incident, "memory_used", "checkoutservice")["items"][0]["points"])
    assert float(memory[INJECT - 12]) == pytest.approx(100000000.0)
    assert float(memory[INJECT + 597]) == pytest.approx(300000000.0)
    files = dict(resource(incident, "open_files", "checkoutservice")["items"][0]["points"])
    assert float(files[INJECT - 12]) == pytest.approx(40.0)
    assert float(files[INJECT + 597]) == pytest.approx(120.0)
    frontend = dict(resource(incident, "processor_usage", "frontend")["items"][0]["points"])
    assert {float(v) for v in frontend.values()} == {0.5}


def test_a_service_without_a_resource_column_returns_nothing(converted):
    out, _ = converted
    empty = {"outcome": "OK", "returned": 0, "truncated": False, "items": []}
    _, incident = recording(out, "checkoutservice_cpu")
    for metric in RESOURCE_METRICS:
        assert resource(incident, metric, "redis") == empty
    assert resource(incident, "memory_used", "frontend") == empty
    assert resource(incident, "open_files", "frontend") == empty
    _, delay = recording(out, "currencyservice_delay")
    for metric in RESOURCE_METRICS:
        assert resource(delay, metric, "currencyservice") == empty


def test_diskio_is_not_mapped(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    metrics = {c["args"]["metric"] for c in calls(incident) if c["tool"] == "query_metric"}
    assert metrics == {"latency_avg", *RESOURCE_METRICS}
    assert convert.RESOURCE_COLUMNS == {"processor_usage": "{service}_cpu", "memory_used": "{service}_mem",
                                        "open_files": "{service}_socket"}


def test_the_resource_metric_names_pass_the_guard_unchanged():
    for metric in RESOURCE_METRICS:
        assert leakage.scan(json.dumps({"metric": metric}), []).strict == [], metric
    assert leakage.scan(json.dumps({"metric": "cpu_usage"}), []).strict == ["fault word 'cpu'"]


def test_a_recording_with_resource_answers_passes_the_guard_and_names_no_column(converted):
    out, _ = converted
    case_names = [name for name, _ in convert.discover(FIXTURE / "RE2-OB", 1)]
    for row in manifest(out):
        text = (out / row["file"]).read_text()
        assert leakage.scan(text, case_names).strict == [], row["file"]
        for service in OB_SERVICES:
            for suffix in ("cpu", "mem", "socket", "diskio"):
                assert f"{service}_{suffix}" not in text, (row["file"], service, suffix)
    _, incident = recording(out, "checkoutservice_cpu")
    for metric in RESOURCE_METRICS:
        assert resource(incident, metric, "checkoutservice")["returned"] == 1


# Logs


def test_the_level_comes_from_the_column_then_from_the_message():
    assert convert.level_of("warning", "anything") == "WARN"
    assert convert.level_of("WARN", "") == "WARN"
    assert convert.level_of("error", "") == "ERROR"
    assert convert.level_of("Fatal", "") == "ERROR"
    assert convert.level_of("info", "an error in an info line") is None
    assert convert.level_of("debug", "") is None
    assert convert.level_of("", "Exception while reading the cart") == "ERROR"
    assert convert.level_of("", "Traceback (most recent call last):") == "ERROR"
    assert convert.level_of("", "1:M # WARNING overcommit_memory is set to 0") == "WARN"
    assert convert.level_of("", "GetCartAsync called with userId=1") is None
    assert convert.level_of("", "errors=0 warnings=0") is None


def count(incident, service, level):
    result = output(incident, "count_logs", service=service, level=level, sinceMinutes=10)
    (item,) = result["items"]
    assert result["returned"] == 1 and list(item) == ["service", "count"] and item["service"] == service
    return item["count"]


def test_logs_are_counted_per_service_and_level_in_the_incident_window(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    # The previous-window warning, the error 1 ns before the window and the one at its end are not counted.
    assert count(incident, "frontend", "WARN") == 22
    assert count(incident, "frontend", "ERROR") == 2
    assert count(incident, "cartservice", "ERROR") == 1
    assert count(incident, "cartservice", "WARN") == 0
    assert count(incident, "recommendationservice", "ERROR") == 1
    assert count(incident, "checkoutservice", "WARN") == 1
    assert count(incident, "emailservice", "WARN") == 1
    assert count(incident, "redis", "WARN") == 1
    assert count(incident, "paymentservice", "ERROR") == 1
    assert count(incident, "adservice", "WARN") == 0


def test_search_logs_returns_the_newest_twenty_lines_like_the_lab(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    result = output(incident, "search_logs", service="frontend", level="WARN", sinceMinutes=10, limit=20)
    assert result["returned"] == 20 and result["truncated"] is False
    items = result["items"]
    assert list(items[0]) == ["timestamp", "service", "level", "untrustedText"]
    assert items[0] == {"timestamp": "2023-11-14T22:13:51Z", "service": "frontend", "level": "WARN",
                        "untrustedText": "failed to retrieve ads 21"}
    assert items[-1]["untrustedText"] == "failed to retrieve ads 2"
    errors = output(incident, "search_logs", service="frontend", level="ERROR", sinceMinutes=10, limit=20)
    assert [i["timestamp"] for i in errors["items"]] == ["2023-11-14T22:14:10.123456789Z", "2023-11-14T22:13:20Z"]


def test_a_long_line_is_cut_like_the_lab_and_marks_the_result_truncated(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    result = output(incident, "search_logs", service="emailservice", level="WARN", sinceMinutes=10, limit=20)
    assert result["truncated"] is True
    text = result["items"][0]["untrustedText"]
    assert len(text) == 1000 + len("[truncated]") and text.endswith("[truncated]")


def test_java_instant_text():
    assert convert.instant_text(INJECT * 10**9) == "2023-11-14T22:13:20Z"
    assert convert.instant_text(INJECT * 10**9 + 120000000) == "2023-11-14T22:13:20.120Z"
    assert convert.instant_text(INJECT * 10**9 + 500000) == "2023-11-14T22:13:20.000500Z"
    assert convert.instant_text(INJECT * 10**9 + 1) == "2023-11-14T22:13:20.000000001Z"


# The manifest and the truth


def test_the_manifest_has_one_dev_row_per_case_with_the_truth(converted):
    out, summary = converted
    rows = manifest(out)
    assert (out / "recordings.tsv").read_text().splitlines()[0] == "fault\tmode\troute\ttruth\tfile\tincident\tsplit"
    assert sorted((r["fault"], r["truth"]) for r in rows) == [
        ("cpu", "checkoutservice|RESOURCE_EXHAUSTION"), ("delay", "currencyservice|DEPENDENCY_LATENCY")]
    assert {(r["mode"], r["route"], r["split"]) for r in rows} == {("decision", "none", "dev")}
    assert summary.cases == 2
    for row in rows:
        assert json.loads((out / row["file"]).read_text())["id"] == row["incident"]


def test_the_category_map_is_adr_020s():
    assert convert.CATEGORIES == {"cpu": "RESOURCE_EXHAUSTION", "mem": "RESOURCE_EXHAUSTION",
                                  "disk": "RESOURCE_EXHAUSTION", "socket": "RESOURCE_EXHAUSTION",
                                  "delay": "DEPENDENCY_LATENCY", "loss": "DEPENDENCY_LATENCY"}


def test_names_are_blinded_and_the_id_is_derived_from_the_same_hash(converted):
    out, _ = converted
    for row in manifest(out):
        stem = row["file"].removesuffix(".json")
        assert len(stem) == 16 and all(c in "0123456789abcdef" for c in stem)
        assert "/" not in row["file"]
        assert row["incident"].replace("-", "")[:12] == stem[:12]
    assert convert.blinded("RE2-OB", "checkoutservice_cpu", 1) == convert.blinded("RE2-OB", "checkoutservice_cpu", 1)
    assert convert.blinded("RE2-OB", "checkoutservice_cpu", 1) != convert.blinded("RE2-OB", "checkoutservice_cpu", 2)


def test_the_same_input_gives_the_same_bytes(tmp_path):
    first, second = tmp_path / "a", tmp_path / "b"
    convert.convert(FIXTURE, "re2-ob", 1, first)
    convert.convert(FIXTURE, "re2-ob", 1, second)
    names = sorted(p.name for p in first.iterdir())
    assert names == sorted(p.name for p in second.iterdir())
    for name in names:
        assert (first / name).read_bytes() == (second / name).read_bytes(), name


def test_case_discovery_skips_the_stray_folder_and_can_pick_cases():
    cases = convert.discover(FIXTURE / "RE2-OB", 1)
    assert [name for name, _ in cases] == ["checkoutservice_cpu", "currencyservice_delay"]
    assert [name for name, _ in convert.discover(FIXTURE / "RE2-OB", 1, ["currencyservice_delay"])] == [
        "currencyservice_delay"]
    with pytest.raises(ValueError, match="no_such_case"):
        convert.discover(FIXTURE / "RE2-OB", 1, ["no_such_case"])


# Holdouts


@pytest.mark.parametrize("system, rep, name", [("re2-ob", 2, "A"), ("re2-ob", 3, "A"), ("re2-ss", 1, "B"),
                                               ("re2-tt", 1, "TT"), ("re2-tt", 2, "TT"), ("re2-tt", 3, "TT")])
def test_a_holdout_is_refused_unless_named(system, rep, name, tmp_path):
    with pytest.raises(convert.HoldoutRefused, match=f"--allow-holdout {name}"):
        convert.split_of(system, rep, None)
    with pytest.raises(convert.HoldoutRefused):
        convert.split_of(system, rep, "Z")
    assert convert.split_of(system, rep, name) == "holdout"
    assert convert.split_of("re2-ob", 1, None) == "dev"


def test_a_refused_holdout_reads_and_writes_nothing(tmp_path, monkeypatch):
    opened = []
    real_open = Path.open
    monkeypatch.setattr(Path, "open", lambda self, *a, **k: opened.append(self) or real_open(self, *a, **k))
    with pytest.raises(convert.HoldoutRefused):
        convert.convert(FIXTURE, "re2-ob", 2, tmp_path / "out")
    assert not (tmp_path / "out").exists()
    assert not [p for p in opened if "RE2-OB" in str(p)]


def test_the_cli_refuses_a_holdout_and_converts_dev(tmp_path, capsys):
    with pytest.raises(SystemExit, match="holdout"):
        main(["convert-rcaeval", "--root", str(FIXTURE), "--system", "re2-ob", "--rep", "2",
              "--out", str(tmp_path / "refused")])
    assert main(["convert-rcaeval", "--root", str(FIXTURE), "--system", "re2-ob", "--rep", "1",
                 "--out", str(tmp_path / "dev")]) == 0
    assert "2 cases" in capsys.readouterr().out
    assert len(manifest(tmp_path / "dev")) == 2


def test_the_cli_takes_the_root_from_the_environment(tmp_path, monkeypatch):
    monkeypatch.setenv("KINTSUGI_RCAEVAL_DIR", str(FIXTURE))
    assert main(["convert-rcaeval", "--system", "re2-ob", "--rep", "1", "--case", "currencyservice_delay",
                 "--out", str(tmp_path)]) == 0
    assert len(manifest(tmp_path)) == 1


# The leakage guard


def test_the_guard_fails_on_a_fault_word_outside_untrusted_text():
    incident = {"symptom": "The CPU is saturated", "steps": []}
    found = leakage.scan(json.dumps(incident), ["checkoutservice_cpu"])
    assert found.strict == ["fault word 'CPU'"]


def test_the_guard_counts_but_allows_fault_words_in_untrusted_text():
    out = {"outcome": "OK", "returned": 1, "truncated": False,
           "items": [{"untrustedText": "retry after a short delay; packet loss"}]}
    step = {"kind": "DOSSIER", "toolOutput": json.dumps([{"tool": "search_logs", "args": {}, "outcome": "OK",
                                                          "output": json.dumps(out)}])}
    found = leakage.scan(json.dumps({"symptom": "ok", "steps": [step]}), [])
    assert found.strict == []
    assert found.untrusted == {"delay": 1, "loss": 1}


def test_the_guard_fails_on_a_case_name_or_a_category_anywhere():
    out = {"items": [{"untrustedText": "dir checkoutservice_cpu"}]}
    step = {"kind": "DOSSIER", "toolOutput": json.dumps([{"output": json.dumps(out)}])}
    found = leakage.scan(json.dumps({"symptom": "RESOURCE_EXHAUSTION", "steps": [step]}), ["checkoutservice_cpu"])
    assert "case name 'checkoutservice_cpu'" in found.strict
    assert "category 'RESOURCE_EXHAUSTION'" in found.strict


def test_the_guard_reads_underscores_as_word_breaks_but_not_inside_words():
    assert leakage.scan(json.dumps({"a": "x_mem_y"}), []).strict == ["fault word 'mem'"]
    assert leakage.scan(json.dumps({"a": "memory socketed overcommit_memory"}), []).strict == []


def test_the_converter_fails_when_a_recording_leaks_and_leaves_nothing(tmp_path, monkeypatch):
    monkeypatch.setitem(convert.SYMPTOMS, "re2-ob", "Users see slow disk responses from frontend.")
    with pytest.raises(leakage.LeakageError, match="disk"):
        convert.convert(FIXTURE, "re2-ob", 1, tmp_path / "out")
    assert list(tmp_path.iterdir()) == []  # no --out, no staging directory, no recording


# Atomic output and encodings


def test_a_run_that_fails_part_way_leaves_no_output(tmp_path):
    root = tmp_path / "root"
    shutil.copytree(FIXTURE, root)
    # The second case in name order has no traces.csv: the first case is converted before the run fails.
    (root / "RE2-OB" / "currencyservice_delay" / "1" / "traces.csv").unlink()
    with pytest.raises(FileNotFoundError):
        convert.convert(root, "re2-ob", 1, tmp_path / "out")
    assert sorted(p.name for p in tmp_path.iterdir()) == ["root"]


def test_a_successful_run_keeps_unrelated_files_in_an_existing_out(tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "README").write_text("kept")
    convert.convert(FIXTURE, "re2-ob", 1, out)
    assert (out / "README").read_text() == "kept"
    assert len(manifest(out)) == 2
    assert sorted(p.name for p in tmp_path.iterdir()) == ["out"]


def test_every_input_is_read_as_utf_8(tmp_path, monkeypatch):
    opened = []
    real_open = Path.open

    def recording_open(self, mode="r", *args, **kwargs):
        if "r" in mode and "RE2-OB" in str(self):
            opened.append((self.name, kwargs.get("encoding")))
        return real_open(self, mode, *args, **kwargs)

    monkeypatch.setattr(Path, "open", recording_open)
    convert.convert(FIXTURE, "re2-ob", 1, tmp_path / "out", cases=["checkoutservice_cpu"])
    assert {name for name, _ in opened} == {"inject_time.txt", "simple_metrics.csv", "logs.csv", "traces.csv"}
    assert {encoding for _, encoding in opened} == {"utf-8"}


def test_an_undecodable_log_byte_becomes_a_replacement_character(tmp_path):
    root = tmp_path / "root"
    shutil.copytree(FIXTURE, root)
    logs = root / "RE2-OB" / "checkoutservice_cpu" / "1" / "logs.csv"
    logs.write_bytes(logs.read_bytes() + b"00:00,1700000100000000000,adservice,bad byte \xff here,error,,,1,x\n")
    convert.convert(root, "re2-ob", 1, tmp_path / "out", cases=["checkoutservice_cpu"])
    _, incident = recording(tmp_path / "out", "checkoutservice_cpu")
    result = output(incident, "search_logs", service="adservice", level="ERROR", sinceMinutes=10, limit=20)
    assert [i["untrustedText"] for i in result["items"]] == ["bad byte \ufffd here"]


def test_the_converted_fixture_passes_the_guard_and_reports_untrusted_hits(converted):
    out, summary = converted
    assert summary.untrusted_hits == 1
    report = (out / "converter-report.tsv").read_text().splitlines()
    assert report[0] == "file\tkind\tdetail\tcount"
    assert any(line.endswith("\tuntrusted_fault_word\tdelay\t1") for line in report)


# AC1: the copy the Java replay test reads


def test_the_agents_copy_is_the_converters_output(tmp_path):
    convert.convert(FIXTURE, "re2-ob", 1, tmp_path, cases=["checkoutservice_cpu"])
    (row,) = manifest(tmp_path)
    for name in ("recordings.tsv", row["file"]):
        assert (AGENT_COPY / name).read_bytes() == (tmp_path / name).read_bytes(), (
            f"{name} drifted: regenerate it with the converter (see odd/tasks/rcaeval-converter.md T3)")


# Holdout TT (ADR-032 Decision 1): every RE2-TT case, behind --allow-holdout TT


TT_SERVICES = ["ts-ui-dashboard", "ts-auth-service", "ts-auth-mongo", "ts-order-service"]


def tt_topology(path):
    edges = [{"caller": None, "target": "ts-ui-dashboard", "method": "CALL", "uri": "/"},
             {"caller": "ts-ui-dashboard", "target": "ts-auth-service", "method": "CALL", "uri": "/"},
             {"caller": "ts-auth-service", "target": "ts-auth-mongo", "method": "CALL", "uri": "/"}]
    path.write_text(json.dumps({"services": TT_SERVICES, "datastores": [], "edges": edges, "tableOwners": {},
                                "sourceOwners": {}}))
    return path


def tt_case(root, case="ts-auth-service_cpu", rep=1):
    """A minimal RE2-TT case. TT has traces, but the converter answers no traffic for it (see TRACED_SYSTEMS), so a
    broken traces.csv must not be read."""
    case_dir = root / "RE2-TT" / case / str(rep)
    case_dir.mkdir(parents=True)
    (case_dir / "inject_time.txt").write_text(f"{INJECT}\n")
    (case_dir / "simple_metrics.csv").write_text(
        f"time,ts-auth-service_latency-50,ts-auth-mongo_cpu,ts-ui-dashboard_latency-50\n{INJECT},0.01,0.2,0.03\n")
    (case_dir / "logs.csv").write_text("time,timestamp,container_name,message,level,req_path,error\n")
    (case_dir / "traces.csv").write_text("not,a,trace,file\n")
    return case_dir


def test_tt_converts_only_when_named_with_its_symptom_and_no_traffic(tmp_path):
    tt_case(tmp_path)
    topology = tt_topology(tmp_path / "re2-tt.json")
    with pytest.raises(convert.HoldoutRefused, match="--allow-holdout TT"):
        convert.convert(tmp_path, "re2-tt", 1, tmp_path / "refused", topology=topology)
    out = tmp_path / "out"
    summary = convert.convert(tmp_path, "re2-tt", 1, out, allow_holdout="TT", topology=topology)
    assert (summary.split, summary.cases) == ("holdout", 1)
    (row,) = manifest(out)
    assert row["split"] == "holdout" and row["truth"] == "ts-auth-service|RESOURCE_EXHAUSTION"
    incident = json.loads((out / row["file"]).read_text())
    assert incident["symptom"] == convert.SYMPTOMS["re2-tt"]
    assert "ts-ui-dashboard" in convert.SYMPTOMS["re2-tt"]
    empty = {"outcome": "OK", "returned": 0, "truncated": False, "items": []}
    assert output(incident, "traffic_counts", sinceMinutes=10) == empty
    assert output(incident, "query_metric", metric="latency_avg", service="ts-auth-service",
                  sinceMinutes=20)["returned"] == 1


def test_the_cli_offers_tt(tmp_path, capsys):
    tt_case(tmp_path)
    topology = tt_topology(tmp_path / "re2-tt.json")
    with pytest.raises(SystemExit, match="--allow-holdout TT"):
        main(["convert-rcaeval", "--root", str(tmp_path), "--system", "re2-tt", "--rep", "1",
              "--topology", str(topology), "--out", str(tmp_path / "refused")])
    assert main(["convert-rcaeval", "--root", str(tmp_path), "--system", "re2-tt", "--rep", "1", "--allow-holdout",
                 "TT", "--topology", str(topology), "--out", str(tmp_path / "out")]) == 0
    assert "split holdout" in capsys.readouterr().out


# --check-only: discovery and headers, no recording, no truth printed


def checked_tree(root):
    """Four RE2-TT repetition-1 cases: two clean, one with no logs.csv and a log header without level, one whose
    root cause is outside the topology and whose inject time is not a number; plus a stray folder."""
    tt_case(root, "ts-auth-service_cpu")
    tt_case(root, "ts-order-service_delay")
    broken = tt_case(root, "ts-auth-service_mem")
    (broken / "logs.csv").unlink()
    (broken / "simple_metrics.csv").write_text(f"time,ts-auth-service_cpu,ts-mystery-service_mem\n{INJECT},1,2\n")
    outside = tt_case(root, "ts-gone-service_loss")
    (outside / "inject_time.txt").write_text("soon\n")
    (outside / "logs.csv").write_text("time,timestamp,container_name,message\n")
    (root / "RE2-TT" / "ts-auth-service_cpu" / "multi-source-data").mkdir()
    (root / "RE2-TT" / "notes").mkdir()


def test_check_only_counts_cases_per_fault_and_reports_header_anomalies(tmp_path):
    checked_tree(tmp_path)
    topology = tt_topology(tmp_path / "re2-tt.json")
    result = convert.check(tmp_path, "re2-tt", 1, allow_holdout="TT", topology=topology)
    assert (result.split, result.holdout, result.cases) == ("holdout", "TT", 4)
    assert dict(result.faults) == {"cpu": 1, "mem": 1, "delay": 1, "loss": 1}
    assert result.stray == 1
    assert sorted(result.anomalies) == sorted([
        ("missing_file", "logs.csv", 1),
        ("missing_column", "logs.csv:level", 1),
        ("bad_inject_time", "-", 1),
        ("root_cause_outside_topology", "-", 1),
        ("unknown_metric_prefix", "ts-mystery-service", 1),
    ])


def test_check_only_names_no_case_and_no_root_cause(tmp_path):
    checked_tree(tmp_path)
    topology = tt_topology(tmp_path / "re2-tt.json")
    result = convert.check(tmp_path, "re2-tt", 1, allow_holdout="TT", topology=topology)
    text = repr(result)
    assert "ts-gone-service" not in text and "_cpu" not in text and "_delay" not in text


def test_check_only_reads_only_headers_and_writes_nothing(tmp_path, monkeypatch):
    checked_tree(tmp_path)
    topology = tt_topology(tmp_path / "re2-tt.json")
    lines = []
    real_open = Path.open

    class Counting:
        def __init__(self, handle, path):
            self.handle, self.path = handle, path

        def __enter__(self):
            self.handle.__enter__()
            return self

        def __exit__(self, *exc):
            return self.handle.__exit__(*exc)

        def __iter__(self):
            for line in self.handle:
                lines.append(self.path.name)
                yield line

        def readline(self, *a):
            lines.append(self.path.name)
            return self.handle.readline(*a)

        def read(self, *a):
            lines.append(self.path.name)
            return self.handle.read(*a)

    def counting_open(self, mode="r", *args, **kwargs):
        if "w" in mode or "a" in mode or "x" in mode:
            raise AssertionError(f"check wrote {self}")
        handle = real_open(self, mode, *args, **kwargs)
        return Counting(handle, self) if self.suffix == ".csv" else handle

    for name in ("simple_metrics.csv", "logs.csv"):  # make the data rows many, so a full read would show
        for path in (tmp_path / "RE2-TT").glob(f"*/1/{name}"):
            path.write_text(path.read_text() + "x\n" * 50)
    monkeypatch.setattr(Path, "open", counting_open)
    before = sorted(p for p in tmp_path.rglob("*"))
    convert.check(tmp_path, "re2-tt", 1, allow_holdout="TT", topology=topology)
    assert sorted(p for p in tmp_path.rglob("*")) == before
    assert len(lines) <= 2 * 4  # one header line per csv file read, at most two files per case


def test_check_only_refuses_a_holdout_unless_named(tmp_path):
    checked_tree(tmp_path)
    with pytest.raises(convert.HoldoutRefused, match="--allow-holdout TT"):
        convert.check(tmp_path, "re2-tt", 1, topology=tt_topology(tmp_path / "re2-tt.json"))


def test_check_only_on_the_fixture_dev_tree_is_clean():
    result = convert.check(FIXTURE, "re2-ob", 1)
    assert (result.split, result.holdout, result.cases) == ("dev", None, 2)
    assert dict(result.faults) == {"cpu": 1, "delay": 1}
    assert [a for a in result.anomalies if a[0] != "unknown_metric_prefix"] == []


def test_the_cli_check_only_prints_counts_and_no_case(tmp_path, capsys):
    checked_tree(tmp_path)
    topology = tt_topology(tmp_path / "re2-tt.json")
    assert main(["convert-rcaeval", "--root", str(tmp_path), "--system", "re2-tt", "--rep", "1",
                 "--allow-holdout", "TT", "--topology", str(topology), "--check-only"]) == 0
    printed = capsys.readouterr().out
    assert "4 cases" in printed and "cpu 1" in printed and "loss 1" in printed
    assert "missing_column\tlogs.csv:level\t1" in printed
    assert "ts-gone-service" not in printed and "_cpu" not in printed
    with pytest.raises(SystemExit, match="--out"):
        main(["convert-rcaeval", "--root", str(tmp_path), "--system", "re2-tt", "--rep", "1", "--allow-holdout",
              "TT", "--topology", str(topology), "--check-only", "--out", str(tmp_path / "out")])
    assert not (tmp_path / "out").exists()
