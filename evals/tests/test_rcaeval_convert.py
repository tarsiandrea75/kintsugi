"""Tests of the RCAEval converter (odd/tasks/rcaeval-converter.md T3, ADR-020), on the synthetic case tree under
`tests/fixtures/rcaeval/`: no real data is read."""

import csv
import json
import shutil
from pathlib import Path

import pytest

from kintsugi_evals.cli import main
from kintsugi_evals.rcaeval import convert, leakage

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


# The calls, exactly as DossierGatherer makes them for a topology without owners


def gatherer_calls(services):
    expected = [("service_restarts", {"sinceMinutes": 10}),
                ("traffic_counts", {"sinceMinutes": 20, "untilMinutesAgo": 10}),
                ("traffic_counts", {"sinceMinutes": 10})]
    expected += [("query_metric", {"metric": "latency_avg", "service": s, "sinceMinutes": 20}) for s in services]
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
    assert len(made) == 3 + 5 * len(OB_SERVICES)
    for call in calls(incident):
        assert list(call) == ["tool", "args", "outcome", "output"]
        assert list(json.loads(call["output"])) == ["outcome", "returned", "truncated", "items"]


def test_restarts_and_traffic_are_empty_answers(converted):
    out, _ = converted
    _, incident = recording(out, "checkoutservice_cpu")
    empty = {"outcome": "OK", "returned": 0, "truncated": False, "items": []}
    assert output(incident, "service_restarts", sinceMinutes=10) == empty
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


@pytest.mark.parametrize("system, rep, name", [("re2-ob", 2, "A"), ("re2-ob", 3, "A"), ("re2-ss", 1, "B")])
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


def test_the_converter_fails_when_a_recording_leaks(tmp_path, monkeypatch):
    monkeypatch.setitem(convert.SYMPTOMS, "re2-ob", "Users see slow disk responses from frontend.")
    with pytest.raises(leakage.LeakageError, match="disk"):
        convert.convert(FIXTURE, "re2-ob", 1, tmp_path)


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
