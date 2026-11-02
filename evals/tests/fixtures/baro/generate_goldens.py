"""Writes the synthetic cases and the original BARO's answers on them (ADR-024). Never touches RCAEval case data.

Each case is `cases/<name>/simple_metrics.csv` and `inject_time.txt`; `goldens.json` holds, per case, what the published
pipeline, `RCAEval.e2e.baro.baro` and `RCAEval.benchmark.scoring.service_ranks` answer, or the exception they raise.
Everything derives from the seeds below, so a rerun writes the same bytes.

Versions used: Python 3.12.3, RCAEval 1.11.0 (the wheel, installed with --no-deps), numpy 2.5.4, pandas 3.1.0,
scikit-learn 1.9.1, scipy 1.18.1.

To run it, from a directory outside any extracted copy of the package:

    python3 -m venv <venv>
    <venv>/bin/pip install numpy==2.5.4 pandas==3.1.0 scikit-learn==1.9.1
    <venv>/bin/pip install --no-deps rcaeval-1.11.0-py3-none-any.whl
    <venv>/bin/python -I evals/tests/fixtures/baro/generate_goldens.py

The package's `__init__` files import dependencies BARO does not use, so `load_original` loads the modules it needs by
their paths. The wheel holds no `main.py`, and importing it would import every method's dependencies, so
`published_pipeline` is a copy of its lines (ADR-024 step 1).
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
from os.path import dirname, join
from pathlib import Path

HERE = Path(__file__).resolve().parent
INJECT = 1700000000
SPAN_SECONDS = 600
STEP = 10
NORMAL = [INJECT - SPAN_SECONDS + STEP * k for k in range(SPAN_SECONDS // STEP)]
ANOMALOUS = [INJECT + STEP * k for k in range(SPAN_SECONDS // STEP + 1)]
PUBLISHED_ARGS = types.SimpleNamespace(length=20, tdelta=0)  # main.py's defaults, lines 111-112
REFUSALS = (ValueError, IndexError, TypeError, KeyError)


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


def published_pipeline(data_path, args):
    """A verbatim copy of `main.py` at tag 1.11.0, commit dc9f48786db5: line 228, lines 236-264 and lines 279-285, as they
    stand; only the imports above them and this function's return are ours. Lines 266-276 count candidates for a report."""
    import numpy as np
    import pandas as pd

    data_dir = dirname(data_path)

    # == Load and Preprocess data ==
    data = pd.read_csv(data_path)

    # some RE1-OB cases repeat the "time" header, which pandas reads as a
    # "time.1" column; it is a timestamp, not a candidate metric
    if "time.1" in data:
        data = data.drop(columns=["time.1"])

    # remove lat-50, only selecte lat-90
    data = data.loc[:, ~data.columns.str.endswith("_latency-50")]
    
    if "mm-tt" in data_path or "torai-TT" in data_path:
        time_col = data["time"]
        data = data.loc[:, data.columns.str.startswith("ts-")]
        data["time"] = time_col
        
    # handle inf
    data = data.replace([np.inf, -np.inf], np.nan)

    # handle na
    data = data.ffill()
    data = data.fillna(0)

    with open(join(data_dir, "inject_time.txt")) as f:
        inject_time = int(f.readlines()[0].strip()) + args.tdelta
    # for metrics, minutes -> seconds // 2
    normal_df = data[data["time"] < inject_time].tail(args.length * 60 // 2)
    anomal_df = data[data["time"] >= inject_time].head(args.length * 60 // 2)

    data = pd.concat([normal_df, anomal_df], ignore_index=True)

    # rename latency
    data = data.rename(
        columns={
            c: c.replace("_latency-90", "_latency")
            for c in data.columns
            if c.endswith("_latency-90")
        }
    )
    return data, inject_time


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
                  "frontend_latency-90": steady_then(rng, 0.1, 0.01, 0.5),
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
                  "y_latency-90": [10 * v for v in shape],
                  "b_cpu": [float(k % 5) for k in range(60)] + [float(k % 12) for k in range(61)],
                  "c_cpu": shape})


def service_names():
    rng = random.Random(6)
    return table({"frontend-external_cpu": steady_then(rng, 1, 0.1, 9),
                  "frontend_latency-90": steady_then(rng, 1, 0.1, 8),
                  "redis_cpu": steady_then(rng, 1, 0.1, 7),
                  "carts-db_mem": steady_then(rng, 1e8, 1e7, 6e7),
                  "carts_cpu": steady_then(rng, 1, 0.1, 5),
                  "catalogue-db_latency-90": steady_then(rng, 1, 0.1, 4),
                  "a-dbx_cpu": steady_then(rng, 1, 0.1, 3),
                  "plain": steady_then(rng, 1, 0.1, 2),
                  "two_under_scores": steady_then(rng, 1, 0.1, 1)})


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
    """Infinities from the first row become NaN and then 0, so the column is constant in the normal segment."""
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


def rows_around(normal, anomalous):
    """One sample a second, `normal` of them before the injection and `anomalous` from it on."""
    return [INJECT - normal + k for k in range(normal)] + [INJECT + k for k in range(anomalous)]


def more_than_600_rows():
    """Outliers in the rows beyond the kept 600 must not count; the last kept row on each side must."""
    rng = random.Random(18)
    size = 700
    outside = noisy(rng, 1, 0.1, range(2 * size))
    outside[:100] = [1000.0] * 100
    outside[-100:] = [5000.0] * 100
    edge = noisy(rng, 1, 0.1, range(2 * size))
    edge[99], edge[100] = 500.0, -40.0  # the row before the kept ones, and the first kept one
    edge[size + 599], edge[size + 600] = 40.0, 400.0  # the last kept row, and the one after it
    return table({"outside_cpu": outside, "edge_cpu": edge, "plain_cpu": noisy(rng, 1, 0.1, range(2 * size))},
                 rows_around(size, size))


def more_than_600_rows_unsorted():
    """The kept rows are the last and the first in the file, whatever their times."""
    rng = random.Random(19)
    size = 700
    header, rows = table({"a_cpu": noisy(rng, 1, 0.1, range(size)) + noisy(rng, 3, 0.1, range(size)),
                          "b_cpu": noisy(rng, 1, 0.1, range(size)) + noisy(rng, 2, 0.5, range(size))},
                         rows_around(size, size))
    rng.shuffle(rows)
    return header, rows


def few_normal_rows():
    rng = random.Random(20)
    return table({"a_cpu": noisy(rng, 1, 0.1, range(100)) + noisy(rng, 2, 0.1, range(700)),
                  "b_cpu": noisy(rng, 1, 0.1, range(100)) + noisy(rng, 3, 0.1, range(700))}, rows_around(100, 700))


def few_anomalous_rows():
    rng = random.Random(21)
    return table({"a_cpu": noisy(rng, 1, 0.1, range(700)) + noisy(rng, 2, 0.1, range(100)),
                  "b_cpu": noisy(rng, 1, 0.1, range(700)) + noisy(rng, 3, 0.1, range(100))}, rows_around(700, 100))


def gaps_across_injection():
    """A gap around the injection is filled forward from the normal segment into the anomalous one."""
    rng = random.Random(22)
    first = len(NORMAL)
    carried = noisy(rng, 1, 0.1) + noisy(rng, 1, 0.1, ANOMALOUS)
    carried[first - 6] = 30.0
    carried[first - 5:first + 5] = [None] * 10
    opening = noisy(rng, 1, 0.1) + noisy(rng, 1, 0.1, ANOMALOUS)
    opening[first - 1] = 25.0
    opening[first] = None
    return table({"carried_cpu": carried, "opening_cpu": opening, "plain_cpu": steady_then(rng, 1, 0.1, 2)})


def gap_at_the_kept_edge():
    """A gap at the start of the kept rows is filled from a row before them, which is itself not kept."""
    rng = random.Random(23)
    values = noisy(rng, 1, 0.1, range(700)) + noisy(rng, 1, 0.1, range(61))
    values[99] = 77.0
    values[100:260] = [None] * 160
    return table({"edge_cpu": values, "plain_cpu": noisy(rng, 1, 0.1, range(700)) + noisy(rng, 2, 0.1, range(61))},
                 rows_around(700, 61))


def leading_nan():
    """What is empty before the first value becomes 0; a column empty until the fault is constant and is dropped."""
    rng = random.Random(24)
    late = steady_then(rng, 1, 0.1, 1)
    late[:20] = [None] * 20
    return table({"late_cpu": late, "empty_until_fault_cpu": [None] * 60 + noisy(rng, 1, 0.1, ANOMALOUS),
                  "plain_cpu": steady_then(rng, 1, 0.1, 1)})


def infinities():
    rng = random.Random(25)
    first = len(NORMAL)
    positive = steady_then(rng, 1, 0.1, 1)
    positive[first + 4] = "inf"
    negative = steady_then(rng, 1, 0.1, 1)
    negative[10] = "-inf"
    spelled = steady_then(rng, 1, 0.1, 1)
    spelled[first] = "Infinity"
    leading = steady_then(rng, 1, 0.1, 1)
    leading[0] = "inf"
    return table({"positive_cpu": positive, "negative_cpu": negative, "spelled_cpu": spelled, "leading_cpu": leading})


def latency_columns():
    """`_latency-50` goes before anything is scored, `_latency-90` loses its suffix, and nothing else matches either."""
    rng = random.Random(26)
    return table({"frontend_latency-50": steady_then(rng, 1, 0.1, 90),
                  "cart_latency-90": steady_then(rng, 1, 0.1, 3),
                  "catalogue-db_latency-90": steady_then(rng, 1, 0.1, 4),
                  "plain-latency-50": steady_then(rng, 1, 0.1, 5),
                  "ad_latency-90_extra": steady_then(rng, 1, 0.1, 6),
                  "email_latency": steady_then(rng, 1, 0.1, 2)})


def redis_column():
    """The columns other datasets drop stay here, as published for a call with `dk_select_useful` false."""
    rng = random.Random(27)
    return table({"redis_cpu": steady_then(rng, 1, 0.1, 9),
                  "main_cpu": steady_then(rng, 1, 0.1, 8),
                  "frontend-external_cpu": steady_then(rng, 1, 0.1, 7),
                  "rabbitmq_cpu": steady_then(rng, 1, 0.1, 6),
                  "frontend_cpu": steady_then(rng, 1, 0.1, 1)})


def time_cells():
    """The time column is filled as every other one: a first empty cell becomes 0, so that row is normal."""
    rng = random.Random(28)
    times = list(NORMAL + ANOMALOUS)
    first = len(NORMAL)
    times[0] = None
    times[20] = None
    times[first] = None  # takes the last normal time, so the first anomalous row is normal
    times[first + 3] = "inf"
    jump = steady_then(rng, 1, 0.1, 1)
    jump[first] = 90.0
    return table({"jump_cpu": jump, "plain_cpu": steady_then(rng, 1, 0.1, 2)}, times)


def word_in_a_column():
    rng = random.Random(29)
    values = steady_then(rng, 1, 0.1, 1)
    values[70] = "high"
    return table({"a_cpu": values, "b_cpu": steady_then(rng, 1, 0.1, 1)})


CASES = (ordinary, mem_column, constant_normal_only, constant_anomalous_only, zero_iqr, near_zero_iqr, nan_values,
         na_tokens, ties, service_names, sample_at_injection, unsorted_rows, repeated_time, short_rows,
         integers, infinity_scored, infinity_constant, no_common_column, one_anomalous_sample, no_anomalous_sample,
         no_normal_sample, more_than_600_rows, more_than_600_rows_unsorted, few_normal_rows, few_anomalous_rows,
         gaps_across_injection, gap_at_the_kept_edge, leading_nan, infinities, latency_columns, redis_column, time_cells,
         word_in_a_column)


def write_case(directory, header, rows):
    directory.mkdir(parents=True)
    with (directory / "simple_metrics.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows([["" if value is None else value for value in row] for row in rows])
    (directory / "inject_time.txt").write_text(f"{INJECT}\n", encoding="utf-8", newline="\n")


def answer(baro, service_ranks, directory):
    """The published answer on a case directory, called as `main.py` does at line 356, or the exception it raises."""
    try:
        data, inject_time = published_pipeline(str(directory / "simple_metrics.csv"), PUBLISHED_ARGS)
        result = baro(data, inject_time, dataset="re2-ob", anomalies=None, dk_select_useful=False)
    except REFUSALS as error:
        return {"error": type(error).__name__}
    return {"ranks": result["ranks"], "scores": [result["scores"][name] for name in result["ranks"]],
            "services": [node.entity for node in service_ranks(result["ranks"])]}


def main():
    baro, service_ranks = load_original()
    cases = HERE / "cases"
    shutil.rmtree(cases, ignore_errors=True)
    goldens = {}
    for case in CASES:
        directory = cases / case.__name__
        write_case(directory, *case())
        goldens[case.__name__] = answer(baro, service_ranks, directory)
    (HERE / "goldens.json").write_text(json.dumps(goldens, indent=1) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
