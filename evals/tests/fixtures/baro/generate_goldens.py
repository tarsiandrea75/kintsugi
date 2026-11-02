"""Writes the synthetic cases and the original BARO's answers on them (ADR-024). Never touches RCAEval case data.

Each case is `cases/<name>/simple_metrics.csv` and `inject_time.txt`; `goldens.json` holds, per case, what
`RCAEval.e2e.baro.baro` and `RCAEval.benchmark.scoring.service_ranks` answer, or the exception they raise. Everything
derives from the seeds below, so a rerun writes the same bytes.

Versions used: Python 3.12.3, RCAEval 1.11.0 (the wheel, installed with --no-deps), numpy 2.5.4, pandas 3.1.0,
scikit-learn 1.9.1, scipy 1.18.1.

To run it, from a directory outside any extracted copy of the package:

    python3 -m venv <venv>
    <venv>/bin/pip install numpy==2.5.4 pandas==3.1.0 scikit-learn==1.9.1
    <venv>/bin/pip install --no-deps rcaeval-1.11.0-py3-none-any.whl
    <venv>/bin/python -I evals/tests/fixtures/baro/generate_goldens.py

The package's `__init__` files import dependencies BARO does not use, so `load_original` loads the modules it needs by
their paths. The 600 s window is ours, not the original's (ADR-024 step 1): it is applied here before the call.
"""

import collections
import csv
import importlib
import json
import random
import shutil
import sys
import types
from importlib.metadata import distribution
from pathlib import Path

HERE = Path(__file__).resolve().parent
INJECT = 1700000000
WINDOW_SECONDS = 600
STEP = 10
NORMAL = [INJECT - WINDOW_SECONDS + STEP * k for k in range(WINDOW_SECONDS // STEP)]
ANOMALOUS = [INJECT + STEP * k for k in range(WINDOW_SECONDS // STEP + 1)]


def load_original():
    root = Path(distribution("RCAEval").locate_file("RCAEval"))
    for name, path in (("RCAEval", root), ("RCAEval.io", root / "io"), ("RCAEval.e2e", root / "e2e"),
                       ("RCAEval.benchmark", root / "benchmark"), ("RCAEval.classes", root / "classes")):
        package = types.ModuleType(name)
        package.__path__ = [str(path)]
        sys.modules[name] = package
    # The real module needs networkx for its graphs; service_ranks uses only Node's fields and equality on them.
    graph = types.ModuleType("RCAEval.classes.graph")
    graph.Node = collections.namedtuple("Node", "entity metric")
    sys.modules[graph.__name__] = graph
    baro = importlib.import_module("RCAEval.e2e.baro").baro
    service_ranks = importlib.import_module("RCAEval.benchmark.scoring").service_ranks
    return baro, service_ranks


def noisy(rng, level, spread, times=NORMAL):
    return [round(rng.gauss(level, spread), 4) for _ in times]


def steady_then(rng, level, spread, jump):
    """A series around `level` whose anomalous segment moves by `jump`."""
    return noisy(rng, level, spread) + noisy(rng, level + jump, spread, ANOMALOUS)


def table(columns, times=None):
    """Rows of `time` plus `columns`, each a list as long as the times; None is an empty cell."""
    times = times or NORMAL + ANOMALOUS
    return ["time"] + list(columns), [[t] + [column[i] for column in columns.values()] for i, t in enumerate(times)]


def ordinary():
    rng = random.Random(1)
    return table({"frontend_cpu": steady_then(rng, 0.5, 0.05, 0.02),
                  "frontend_latency-50": steady_then(rng, 0.1, 0.01, 0.5),
                  "cartservice_cpu": steady_then(rng, 0.2, 0.02, 0.0),
                  "cartservice_latency-90": steady_then(rng, 0.03, 0.002, 0.01),
                  "checkoutservice_cpu": steady_then(rng, 0.3, 0.02, 0.9),
                  "checkoutservice_socket": steady_then(rng, 40, 2, 3),
                  "emailservice_diskio": steady_then(rng, 5, 0.5, -1)})


def mem_column():
    rng = random.Random(2)
    zero_iqr = [100000000.0] * 59 + [100000001.0] + [100000000.0] * 50 + [130000000.0] * 11
    return table({"a_mem": steady_then(rng, 1e8, 1e6, 5e6),
                  "b_mem": zero_iqr,  # the scale stays 1 after the division, so the score is in MB
                  "c_cpu": steady_then(rng, 0.5, 0.05, 0.1),
                  "d_mem_used": zero_iqr,  # does not end in _mem: never divided
                  "e_mem": [v / 1e6 for v in zero_iqr]})


def constant_normal_only():
    rng = random.Random(3)
    return table({"a_cpu": [0.5] * len(NORMAL) + noisy(rng, 9, 1, ANOMALOUS),
                  "b_cpu": steady_then(rng, 0.2, 0.02, 0.1)})


def constant_anomalous_only():
    rng = random.Random(4)
    return table({"a_cpu": noisy(rng, 0.5, 0.05) + [9.0] * len(ANOMALOUS),
                  "b_cpu": steady_then(rng, 0.2, 0.02, 0.1)})


def zero_iqr():
    return table({"a_cpu": [5.0] * 58 + [4.0, 6.0] + [5.0] * 60 + [8.0],
                  "b_cpu": [1.0] * 59 + [2.0] + [1.0] * 30 + [3.5] * 31,
                  "c_cpu": [float(k % 7) for k in range(60)] + [float(k % 9) for k in range(61)]})


def near_zero_iqr():
    """A scale under 10 machine epsilons becomes 1 in the original; one above it divides."""
    return table({"tiny_cpu": [(k % 5) * 1e-17 for k in range(60)] + [0.5] * 60 + [0.25],
                  "tiny_mem": [(k % 5) * 1e-10 for k in range(60)] + [0.5] * 60 + [0.25],
                  "above_cpu": [(k % 5) * 3e-15 for k in range(60)] + [(k % 5) * 3e-13 for k in range(61)],
                  "plain_cpu": [float(k % 5) for k in range(60)] + [float(k % 8) for k in range(61)]})


def nan_values():
    rng = random.Random(5)
    base = steady_then(rng, 1.0, 0.1, 0.0)

    def with_cells(cells, values=None):
        values = list(values or base)
        for i, value in cells.items():
            values[i] = value
        return values

    first_anomalous = len(NORMAL)
    return table({"later_nan_cpu": with_cells({first_anomalous + 3: None, first_anomalous + 9: 50.0}),
                  "first_nan_cpu": with_cells({first_anomalous: None, first_anomalous + 9: 90.0}),
                  "normal_nan_cpu": with_cells({1: None, 7: None, 20: None, first_anomalous + 2: 30.0}),
                  "normal_first_nan_cpu": [None] + [2.0] * 59 + [2.0] * 60 + [7.0],
                  "constant_then_nan_cpu": [3.0] * 30 + [None] + [3.0] * 29 + noisy(rng, 3, 1, ANOMALOUS),
                  "all_nan_normal_cpu": [None] * 60 + noisy(rng, 1, 1, ANOMALOUS),
                  "all_nan_anomalous_cpu": noisy(rng, 1, 1) + [None] * 61,
                  "all_nan_cpu": [None] * 121,
                  "fine_cpu": steady_then(rng, 1.0, 0.1, 4.0),
                  "second_first_nan_cpu": with_cells({first_anomalous: None}),
                  "small_cpu": steady_then(rng, 1.0, 0.1, 0.1)})


def na_tokens():
    tokens = ["", "NA", "N/A", "n/a", "null", "NULL", "nan", "NaN", "-nan", "None", "<NA>", "#N/A"]
    values = [float(k % 6) for k in range(60)] + [float(k % 10) for k in range(61)]
    for i, token in enumerate(tokens):
        values[i * 9] = token
    return table({"a_cpu": values, "b_cpu": [float(k % 4) for k in range(60)] + [float(k % 6) for k in range(61)]})


def ties():
    shape = [float(k % 5) for k in range(60)] + [float(k % 9) for k in range(61)]
    return table({"z_cpu": shape, "m_cpu": shape, "a_cpu": shape,
                  "y_latency-50": [10 * v for v in shape],
                  "b_cpu": [float(k % 5) for k in range(60)] + [float(k % 12) for k in range(61)],
                  "c_cpu": shape})


def service_names():
    rng = random.Random(6)
    return table({"frontend-external_cpu": steady_then(rng, 1, 0.1, 9),
                  "frontend_latency-50": steady_then(rng, 1, 0.1, 8),
                  "redis_cpu": steady_then(rng, 1, 0.1, 7),
                  "carts-db_mem": steady_then(rng, 1e8, 1e7, 6e7),
                  "carts_cpu": steady_then(rng, 1, 0.1, 5),
                  "catalogue-db_latency-50": steady_then(rng, 1, 0.1, 4),
                  "a-dbx_cpu": steady_then(rng, 1, 0.1, 3),
                  "plain": steady_then(rng, 1, 0.1, 2),
                  "two_under_scores": steady_then(rng, 1, 0.1, 1)})


def window():
    """Samples beyond 600 s hold outliers the score must not see; samples exactly at 600 s count."""
    rng = random.Random(7)
    times = [INJECT - 900, INJECT - 601, INJECT - 600.5] + NORMAL + ANOMALOUS + [INJECT + 600.5, INJECT + 601, INJECT + 900]
    size = len(NORMAL + ANOMALOUS)

    def around(core):
        return [1000.0, -1000.0, 500.0] + core + [1000.0, 2000.0, 3000.0]

    edge = noisy(rng, 1, 0.1) + noisy(rng, 1, 0.1, ANOMALOUS)
    edge[-1] = 40.0
    start_edge = noisy(rng, 1, 0.1) + noisy(rng, 1, 0.1, ANOMALOUS)
    start_edge[0] = -400.0  # exactly -600 s: changes the quantiles only a little, but it is inside
    return table({"edge_cpu": around(edge), "start_edge_cpu": around(start_edge),
                  "plain_cpu": around(noisy(rng, 1, 0.1, range(size)))}, times)


def sample_at_injection():
    rng = random.Random(8)
    spike = noisy(rng, 1, 0.1) + noisy(rng, 1, 0.1, ANOMALOUS)
    spike[len(NORMAL)] = 70.0  # the sample exactly at inject_time is anomalous
    before = noisy(rng, 1, 0.1) + noisy(rng, 1, 0.1, ANOMALOUS)
    before[len(NORMAL) - 1] = 80.0  # one step before it is normal
    return table({"spike_cpu": spike, "before_cpu": before})


def unsorted_rows():
    rng = random.Random(9)
    header, rows = table({"a_cpu": steady_then(rng, 1, 0.1, 3), "b_cpu": steady_then(rng, 1, 0.1, 1)})
    for i in rng.sample(range(len(rows)), 40):
        rows[i][2] = None
    rng.shuffle(rows)
    return header, rows


def repeated_time():
    rng = random.Random(10)
    times = NORMAL + ANOMALOUS
    a, b = steady_then(rng, 1, 0.1, 2), steady_then(rng, 1, 0.1, 1)
    return ["time", "a_cpu", "time", "b_cpu"], [[t, a[i], t, b[i]] for i, t in enumerate(times)]


def short_rows():
    rng = random.Random(11)
    header, rows = table({"a_cpu": steady_then(rng, 1, 0.1, 2), "b_cpu": steady_then(rng, 1, 0.1, 1)})
    return header, [row[:2] if i % 10 == 0 else row for i, row in enumerate(rows)]


def integers():
    return table({"a_socket": [40 + k % 3 for k in range(60)] + [50 + k % 4 for k in range(61)],
                  "b_mem": [100000000 + 1000 * (k % 5) for k in range(60)] + [100000000 + 2000 * (k % 5) for k in range(61)]})


def infinity_scored():
    rng = random.Random(12)
    values = steady_then(rng, 1, 0.1, 1)
    values[70] = "inf"
    return table({"a_cpu": values})


def infinity_constant():
    """A column constant at infinity in one segment is dropped before the scaler could refuse it."""
    rng = random.Random(13)
    return table({"a_cpu": ["inf"] * 60 + noisy(rng, 1, 0.1, ANOMALOUS), "b_cpu": steady_then(rng, 1, 0.1, 1)})


def no_common_column():
    rng = random.Random(14)
    return table({"a_cpu": [1.0] * 60 + noisy(rng, 1, 0.1, ANOMALOUS), "b_cpu": noisy(rng, 1, 0.1) + [1.0] * 61})


def one_anomalous_sample():
    rng = random.Random(15)
    times = NORMAL + ANOMALOUS[:1]
    return table({"a_cpu": noisy(rng, 1, 0.1) + [9.0], "b_cpu": noisy(rng, 1, 0.1) + [None]}, times)


def no_anomalous_sample():
    rng = random.Random(16)
    return table({"a_cpu": noisy(rng, 1, 0.1)}, NORMAL)


def no_normal_sample():
    rng = random.Random(17)
    return table({"a_cpu": noisy(rng, 1, 0.1, ANOMALOUS)}, ANOMALOUS)


CASES = (ordinary, mem_column, constant_normal_only, constant_anomalous_only, zero_iqr, near_zero_iqr, nan_values,
         na_tokens, ties, service_names, window, sample_at_injection, unsorted_rows, repeated_time, short_rows,
         integers, infinity_scored, infinity_constant, no_common_column, one_anomalous_sample, no_anomalous_sample,
         no_normal_sample)


def write_case(directory, header, rows):
    directory.mkdir(parents=True)
    with (directory / "simple_metrics.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows([["" if value is None else value for value in row] for row in rows])
    (directory / "inject_time.txt").write_text(f"{INJECT}\n", encoding="utf-8", newline="\n")


def answer(pandas, baro, service_ranks, directory):
    data = pandas.read_csv(directory / "simple_metrics.csv")
    data = data[(data["time"] - INJECT).abs() <= WINDOW_SECONDS]
    try:
        result = baro(data, inject_time=INJECT, dataset="re2-ob")
    except (ValueError, IndexError) as error:
        return {"error": type(error).__name__}
    return {"ranks": result["ranks"], "scores": [result["scores"][name] for name in result["ranks"]],
            "services": [node.entity for node in service_ranks(result["ranks"])]}


def main():
    import pandas

    baro, service_ranks = load_original()
    cases = HERE / "cases"
    shutil.rmtree(cases, ignore_errors=True)
    goldens = {}
    for case in CASES:
        directory = cases / case.__name__
        write_case(directory, *case())
        goldens[case.__name__] = answer(pandas, baro, service_ranks, directory)
    (HERE / "goldens.json").write_text(json.dumps(goldens, indent=1) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
