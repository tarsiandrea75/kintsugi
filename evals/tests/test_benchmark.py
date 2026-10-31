"""Tests of `python -m kintsugi_evals benchmark` (openspec/specs/evals REQ-10)."""

from kintsugi_evals.cli import main

SCORES = """config	fault	n	correct	abstained	ac1	wilson_low	wilson_high	coverage	selective	false_alarms	false_alarm_rate	fa_wilson_low	fa_wilson_high
0	F1	5	5	0	1.0000	0.5655	1.0000	1.0000	1.0000
0	F2	5	0	5	0.0000	0.0000	0.4345	0.0000
0	F3	5	5	0	1.0000	0.5655	1.0000	1.0000	1.0000
0	pooled	15	10	5	0.6667	0.4171	0.8482	0.6667	1.0000
D	F1	5	4	0	0.8000	0.3755	0.9638	1.0000	0.8000
D	F2	5	5	0	1.0000	0.5655	1.0000	1.0000	1.0000
D	F3	5	5	0	1.0000	0.5655	1.0000	1.0000	1.0000
D	pooled	15	14	0	0.9333	0.7018	0.9881	1.0000	0.9333
"""

PAIRED = """first	second	cases	only_first	only_second	mcnemar_p	holm_p	diff	boot_low	boot_high
0	D	15	1	5	0.21875	0.21875	0.2667	0.0000	0.5333
"""

INPUTS = """label	source	sha256	rows
0	../infra/scripts/out/replay-rules	067ab8406c960c599ea3d80aee8a598f83a75f3b48cfacb68371d0d8864e78e0	15
D	results/m0/inputs/D-summary.tsv	98f18e8902e0f1f49cc99777998a324dc05a16d6863fb84332d76340d7170d4b	15
"""

REPORT = """0: route rules, mode decision, view tables; 15 fault cases, 0 healthy
D - 0: 15 cases, D only 5, 0 only 1, exact McNemar p = 0.22; difference +27 points, cluster bootstrap 95% [+0, +53] (seed 1, 10000 resamples)
"""


def result_dir(root, name="2026-10-30-lab", scores=SCORES, paired=PAIRED):
    path = root / "results" / name
    path.mkdir(parents=True)
    (path / "scores.tsv").write_text(scores)
    (path / "paired.tsv").write_text(paired)
    (path / "inputs.tsv").write_text(INPUTS)
    (path / "report.txt").write_text(REPORT)
    return path


def render(tmp_path, monkeypatch, *names):
    monkeypatch.chdir(tmp_path)
    for name in names:
        result_dir(tmp_path, name)
    dirs = [f"results/{name}" for name in names]
    assert main(["benchmark", "--out", "out/benchmark.md", *dirs]) == 0
    return (tmp_path / "out" / "benchmark.md").read_text()


def test_benchmark_renders_a_config_by_fault_table(tmp_path, monkeypatch):
    text = render(tmp_path, monkeypatch, "2026-10-30-lab")
    assert "## 2026-10-30-lab\n" in text
    assert "| Config | F1 | F2 | F3 | pooled | Coverage | Selective |" in text
    assert ("| 0 | 5/5, 100% [57%, 100%] | 0/5, 0% [0%, 43%] | 5/5, 100% [57%, 100%] | "
            "10/15, 67% [42%, 85%] | 67% | 100% |") in text
    assert ("| D | 4/5, 80% [38%, 96%] | 5/5, 100% [57%, 100%] | 5/5, 100% [57%, 100%] | "
            "14/15, 93% [70%, 99%] | 100% | 93% |") in text


def test_benchmark_renders_the_paired_rows(tmp_path, monkeypatch):
    text = render(tmp_path, monkeypatch, "2026-10-30-lab")
    assert "| Pair | Cases | Left only | Right only | McNemar p | Holm p | Difference (points) | Bootstrap 95% |" in text
    assert "| D - 0 | 15 | 5 | 1 | 0.22 | 0.22 | +27 | [+0, +53] |" in text
    assert "Cluster bootstrap: seed 1, 10000 resamples." in text


def test_benchmark_names_the_sources_with_their_sha256(tmp_path, monkeypatch):
    text = render(tmp_path, monkeypatch, "2026-10-30-lab")
    assert ("Sources: 0 `../infra/scripts/out/replay-rules` sha256 "
            "`067ab8406c960c599ea3d80aee8a598f83a75f3b48cfacb68371d0d8864e78e0`; "
            "D `results/m0/inputs/D-summary.tsv` sha256 "
            "`98f18e8902e0f1f49cc99777998a324dc05a16d6863fb84332d76340d7170d4b`.") in text


def test_benchmark_header_names_the_regenerate_command(tmp_path, monkeypatch):
    text = render(tmp_path, monkeypatch, "a", "b")
    lines = text.splitlines()
    assert lines[0] == "# Benchmark"
    assert ("`cd evals && python3 -m kintsugi_evals benchmark --out out/benchmark.md results/a results/b`"
            in text)
    assert "Do not edit by hand" in text
    assert text.index("## a\n") < text.index("## b\n")


def test_benchmark_output_is_byte_identical_on_two_renders(tmp_path, monkeypatch):
    first = render(tmp_path, monkeypatch, "2026-10-30-lab")
    assert main(["benchmark", "--out", "out/benchmark.md", "results/2026-10-30-lab"]) == 0
    assert (tmp_path / "out" / "benchmark.md").read_text() == first
    assert "\r" not in first and first.endswith("\n") and not first.endswith("\n\n")


def test_benchmark_marks_missing_values(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    scores = SCORES + "D\thealthy\t2\t1\t\t\t\t\t\t\t1\t0.5000\t0.0945\t0.9055\n"
    paired = PAIRED.replace("0.21875\t0.21875", "\t").replace("\t1\t5\t", "\t\t\t")
    result_dir(tmp_path, "r", scores=scores, paired=paired)
    assert main(["benchmark", "--out", "b.md", "results/r"]) == 0
    text = (tmp_path / "b.md").read_text()
    assert "| Config | F1 | F2 | F3 | pooled | Coverage | Selective | Healthy false alarms |" in text
    assert "| 0/5, 0% [0%, 43%] | 5/5, 100% [57%, 100%] | 10/15, 67% [42%, 85%] | 67% | 100% | - |" in text
    assert "| 93% | 1/2, 50% [9%, 91%] |" in text
    assert "| D - 0 | 15 | n/a | n/a | n/a | n/a | +27 | [+0, +53] |" in text


def test_benchmark_reports_a_missing_dir_with_exit_2(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    assert main(["benchmark", "--out", "b.md", "results/nowhere"]) == 2
    err = capsys.readouterr().err
    assert err.startswith("benchmark: ") and "results/nowhere" in err and err.count("\n") == 1
    assert not (tmp_path / "b.md").exists()


def test_benchmark_reports_a_missing_file_with_exit_2(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    path = result_dir(tmp_path, "r")
    (path / "paired.tsv").unlink()
    assert main(["benchmark", "--out", "b.md", "results/r"]) == 2
    assert "paired.tsv" in capsys.readouterr().err


def test_benchmark_ignores_the_candidate_recall_column(tmp_path, monkeypatch):
    # scores.tsv gained a trailing candidate_recall column (hybrid T5); the document does not show it yet.
    monkeypatch.chdir(tmp_path)
    lines = SCORES.splitlines()
    extended = "\n".join([lines[0] + "\tcandidate_recall"] + [line + "\t" * (14 - line.count("\t")) + "\t0.8000"
                                                             for line in lines[1:]]) + "\n"
    result_dir(tmp_path, "old")
    result_dir(tmp_path, "new", scores=extended)
    main(["benchmark", "--out", "old.md", "results/old"])
    main(["benchmark", "--out", "new.md", "results/new"])
    old = (tmp_path / "old.md").read_text().replace("old", "X")
    assert (tmp_path / "new.md").read_text().replace("new", "X") == old
