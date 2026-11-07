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


# A verdict directory (ADR-031): verdict.tsv and verdict.txt as `verdict --out` writes them, and its provenance.

VERDICT = """kind	name	hypothesis	statistic	side	margin	cases	estimate	ci_low	ci_high	p	holm_p	decision	reading
condition	H1	H1	D - 0R service AC@1, fault cases	above	0.0000	240	0.2500	0.1800	0.3200	0.0000		clears	
condition	H2a	H2	A - D AC@1, fault cases	above	-0.1000	225	-0.0400	-0.0900	0.0100	0.0004		clears	
condition	H2b	H2	false-alarm rate A - D, healthy cases	below	0.1000	165	0.0200	-0.0100	0.0500	0.0000		clears	
condition	H2c	H2	median A time to report (s), all A cases outside H2b-only tracks	below	900.0000	225	420.0000	390.0000	450.0000	0.0000		clears	
condition	H3a	H3	C - D AC@1, A and D fault cases	above	-0.0500	210	-0.0800	-0.1300	-0.0300	0.8100		fails	
condition	H3b	H3	C escalation rate, A and D fault cases	below	0.5000	0				1.0000		fails	
hypothesis	H1	H1	max of H1							0.0000	0.0000	holds	-
hypothesis	H2	H2	max of H2a H2b H2c							0.0004	0.0008	holds	
hypothesis	H3	H3	max of H3a H3b							1.0000	1.0000	not supported	
"""

VERDICT_TXT = ("H1-H3 verdict (ADR-031): seed 20261106, 10000 resamples; C under k=L2, t=off (ADR-030); alpha 0.025 "
               "one-sided, Holm over H1-H3\n")

PROVENANCE = """command	holdout	plan	plan_sha256	git_commit	written_utc
verdict	A,B,TT,holdout-2	evals/plans/2026-11-06-h1-h3-verdicts.md	ab12	0123abcd	2026-12-08T10:00:00Z
"""


def verdict_dir(root, name="2026-12-08-h1-h3-verdicts", verdict=VERDICT, provenance=PROVENANCE):
    path = root / "results" / name
    path.mkdir(parents=True)
    (path / "verdict.tsv").write_text(verdict)
    (path / "verdict.txt").write_text(VERDICT_TXT)
    if provenance is not None:
        (path / "provenance.tsv").write_text(provenance)
    return path


def render_verdict(tmp_path, monkeypatch, **kwargs):
    monkeypatch.chdir(tmp_path)
    result_dir(tmp_path, "2026-10-30-lab")
    verdict_dir(tmp_path, **kwargs)
    assert main(["benchmark", "--out", "b.md", "results/2026-10-30-lab", "results/2026-12-08-h1-h3-verdicts"]) == 0
    return (tmp_path / "b.md").read_text()


def test_benchmark_renders_a_verdict_dir_after_the_score_dirs_in_command_order(tmp_path, monkeypatch):
    text = render_verdict(tmp_path, monkeypatch)
    assert text.index("## 2026-10-30-lab\n") < text.index("## 2026-12-08-h1-h3-verdicts\n")
    assert "### H1–H3 verdicts\n" in text
    assert "results/2026-10-30-lab results/2026-12-08-h1-h3-verdicts`" in text


def test_benchmark_renders_each_hypothesis_with_its_iu_and_holm_p(tmp_path, monkeypatch):
    text = render_verdict(tmp_path, monkeypatch)
    assert "| Hypothesis | Conditions | Intersection-union p | Holm-adjusted p | Decision |" in text
    assert "| H1 | H1 | 0.0000 | 0.0000 | holds |" in text
    assert "| H2 | H2a, H2b, H2c | 0.0004 | 0.0008 | holds |" in text
    assert "| H3 | H3a, H3b | 1.0000 | 1.0000 | not supported |" in text
    assert "H1 rules are enough: no (H1's 95% upper bound below +5 pts)." in text


def test_benchmark_renders_each_condition_with_its_estimate_ci_and_unadjusted_p(tmp_path, monkeypatch):
    text = render_verdict(tmp_path, monkeypatch)
    assert "| Condition | Statistic | Margin | Cases | Estimate | 95% CI | One-sided p (unadjusted) | At 0.025 |" in text
    assert "| H1 | D - 0R service AC@1, fault cases | > +0.0 pts | 240 | +25.0 pts | [+18.0 pts, +32.0 pts] | 0.0000 | clears |" in text
    assert ("| H2c | median A time to report (s), all A cases outside H2b-only tracks | < 15.0 min | 225 | 7.0 min | "
            "[6.5 min, 7.5 min] | 0.0000 | clears |") in text
    assert "| H3a | C - D AC@1, A and D fault cases | > -5.0 pts | 210 | -8.0 pts | [-13.0 pts, -3.0 pts] | 0.8100 | fails |" in text
    assert "| H3b | C escalation rate, A and D fault cases | < 50.0% | 0 | - | - | 1.0000 | fails |" in text


def test_benchmark_renders_the_verdict_seed_and_resamples(tmp_path, monkeypatch):
    text = render_verdict(tmp_path, monkeypatch)
    assert "Bootstrap: seed 20261106, 10000 resamples; alpha 0.025 one-sided, Holm over H1–H3 (ADR-031)." in text


def test_benchmark_renders_the_provenance_when_present(tmp_path, monkeypatch):
    text = render_verdict(tmp_path, monkeypatch)
    assert ("Provenance: verdict read holdout `A,B,TT,holdout-2` under plan "
            "`evals/plans/2026-11-06-h1-h3-verdicts.md` sha256 `ab12` at commit `0123abcd`, "
            "2026-12-08T10:00:00Z.") in text


def test_benchmark_renders_a_verdict_without_provenance_without_the_line(tmp_path, monkeypatch):
    text = render_verdict(tmp_path, monkeypatch, provenance=None)
    assert "Provenance" not in text


def test_benchmark_renders_a_score_dirs_provenance_after_its_sources(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    path = result_dir(tmp_path, "r")
    (path / "provenance.tsv").write_text(PROVENANCE.replace("verdict\tA,B,TT,holdout-2", "score\tA"))
    assert main(["benchmark", "--out", "b.md", "results/r"]) == 0
    text = (tmp_path / "b.md").read_text()
    assert text.index("Sources: ") < text.index("Provenance: score read holdout `A`")


def test_benchmark_refuses_a_verdict_tsv_without_a_column(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    broken = "\n".join(line.rsplit("\t", 1)[0] for line in VERDICT.splitlines()) + "\n"
    verdict_dir(tmp_path, "v", verdict=broken)
    assert main(["benchmark", "--out", "b.md", "results/v"]) == 2
    assert "verdict.tsv" in capsys.readouterr().err and "reading" not in (tmp_path / "b.md").name
    assert not (tmp_path / "b.md").exists()


def test_benchmark_refuses_a_verdict_dir_without_verdict_txt(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    (verdict_dir(tmp_path, "v") / "verdict.txt").unlink()
    assert main(["benchmark", "--out", "b.md", "results/v"]) == 2
    assert "verdict.txt" in capsys.readouterr().err


def test_benchmark_renders_what_verdict_out_wrote(tmp_path, monkeypatch, capsys):
    from test_cli import verdict_inputs

    args, _ = verdict_inputs(tmp_path)
    assert main(args + ["--out", str(tmp_path / "results" / "v")]) == 0
    capsys.readouterr()
    monkeypatch.chdir(tmp_path)
    assert main(["benchmark", "--out", "b.md", "results/v"]) == 0
    text = (tmp_path / "b.md").read_text()
    assert "| H1 | H1 | 0.0000 | 0.0000 | holds |" in text
    assert "Bootstrap: seed 3, 300 resamples;" in text
    assert "Provenance: verdict read holdout `H1-H3` under plan `evals/plans/2026-11-06-h1-h3-verdicts.md`" in text
