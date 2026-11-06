"""Tests of the protocol's statistics (ADR-015). The pinned numbers are the ones quoted in docs/vision.md."""

import pytest

from kintsugi_evals.stats import cluster_bootstrap_diff, dev_keep, holm, mcnemar_exact, wilson


@pytest.mark.parametrize(
    "k, n, lo, hi",
    [
        (4, 5, 0.3755, 0.9638),
        (5, 15, 0.1518, 0.5829),
        (14, 15, 0.7018, 0.9881),
    ],
)
def test_wilson_matches_the_vision_intervals(k, n, lo, hi):
    low, high = wilson(k, n)
    assert low == pytest.approx(lo, abs=1e-4)
    assert high == pytest.approx(hi, abs=1e-4)


def test_wilson_rounds_to_the_quoted_percentages():
    assert [round(100 * x) for x in wilson(4, 5)] == [38, 96]
    assert [round(100 * x) for x in wilson(5, 15)] == [15, 58]
    assert [round(100 * x) for x in wilson(14, 15)] == [70, 99]


def test_wilson_edges_stay_inside_zero_and_one():
    assert wilson(0, 5)[0] == pytest.approx(0.0, abs=1e-12)
    assert wilson(5, 5)[1] == pytest.approx(1.0)


def test_wilson_rejects_an_empty_sample():
    with pytest.raises(ValueError):
        wilson(0, 0)


def test_mcnemar_exact_nine_against_zero():
    assert mcnemar_exact(9, 0) == pytest.approx(0.00390625)
    assert mcnemar_exact(0, 9) == pytest.approx(0.00390625)


def test_mcnemar_exact_is_one_without_discordant_pairs_or_when_balanced():
    assert mcnemar_exact(0, 0) == 1.0
    assert mcnemar_exact(3, 3) == 1.0


def test_mcnemar_exact_two_sided_binomial():
    # b=1, c=5: 2 * P(X <= 1 | n=6, p=1/2) = 2 * 7/64
    assert mcnemar_exact(1, 5) == pytest.approx(14 / 64)


def test_cluster_bootstrap_is_deterministic_for_a_seed():
    cases = {f"c{i}": ([1, 1, 0], [0, 0, 1]) for i in range(10)}
    first = cluster_bootstrap_diff(cases, seed=7, iterations=500)
    second = cluster_bootstrap_diff(cases, seed=7, iterations=500)
    assert first == second


def test_cluster_bootstrap_diff_point_and_interval():
    # M0 shape: 9 cases where only the second config is right, 5 where both are, 1 where neither is.
    cases = {}
    for i in range(9):
        cases[f"b{i}"] = ([0], [1])
    for i in range(5):
        cases[f"s{i}"] = ([1], [1])
    cases["n"] = ([0], [0])
    diff, lo, hi = cluster_bootstrap_diff(cases, seed=1, iterations=2000)
    assert diff == pytest.approx(9 / 15)
    assert 0 < lo < diff < hi <= 1


def test_cluster_bootstrap_weights_each_case_once_whatever_its_repetitions():
    cases = {"a": ([1, 1, 1, 1], [0, 0, 0, 0]), "b": ([0], [1])}
    diff, _, _ = cluster_bootstrap_diff(cases, seed=1, iterations=100)
    assert diff == pytest.approx(0.0)


def test_holm_adjusts_and_keeps_order():
    adjusted = holm([0.01, 0.04, 0.03])
    assert adjusted == pytest.approx([0.03, 0.06, 0.06])


def test_holm_caps_at_one():
    assert holm([0.5, 0.9]) == pytest.approx([1.0, 1.0])


def test_dev_keep_needs_a_net_gain_of_three():
    pairs = [("F1", 0, 1)] * 3
    assert dev_keep(pairs)
    assert not dev_keep([("F1", 0, 1)] * 2)


def test_dev_keep_refuses_a_fault_class_losing_more_than_one():
    pairs = [("F1", 0, 1)] * 5 + [("F2", 1, 0)] * 2
    assert not dev_keep(pairs)
    assert dev_keep([("F1", 0, 1)] * 4 + [("F2", 1, 0)])


@pytest.mark.parametrize("k, n", [(6, 5), (-1, 5)])
def test_wilson_rejects_k_outside_zero_to_n(k, n):
    with pytest.raises(ValueError):
        wilson(k, n)


def test_mcnemar_rejects_negative_counts():
    with pytest.raises(ValueError):
        mcnemar_exact(-1, 2)


@pytest.mark.parametrize(
    "cases, kwargs",
    [
        ({}, {}),
        ({"a": ([1], [0])}, {"iterations": 0}),
        ({"a": ([1], [0])}, {"level": 0.0}),
        ({"a": ([1], [0])}, {"level": 1.0}),
        ({"a": ([], [0])}, {}),
        ({"a": ([1], [])}, {}),
    ],
)
def test_cluster_bootstrap_rejects_invalid_input(cases, kwargs):
    with pytest.raises(ValueError):
        cluster_bootstrap_diff(cases, seed=1, **kwargs)


def test_holm_and_dev_keep_on_empty_lists():
    assert holm([]) == []
    assert not dev_keep([])


# The shared resampling helpers (ADR-031 Decision 2).


def test_stratified_bootstrap_keeps_each_stratum_size():
    from kintsugi_evals.stats import stratified_bootstrap

    # Resampling within each stratum keeps 3 ones and 7 zeros, so every resample's mean is exactly 0.3.
    draws = stratified_bootstrap([[1] * 3, [0] * 7], lambda xs: (len(xs), sum(xs) / len(xs)), seed=1, iterations=200)
    assert set(draws) == {(10, 0.3)}


def test_stratified_bootstrap_of_one_stratum_draws_as_the_cluster_bootstrap():
    from kintsugi_evals.stats import percentile_ci, stratified_bootstrap

    cases = {i: ([0], [i % 3 == 0]) for i in range(12)}
    _, low, high = cluster_bootstrap_diff(cases, seed=5, iterations=300)
    diffs = [int(b[0]) - a[0] for a, b in cases.values()]
    draws = stratified_bootstrap([diffs], lambda xs: sum(xs) / len(xs), seed=5, iterations=300)
    assert percentile_ci(draws) == (low, high)


def test_stratified_bootstrap_skips_empty_strata_and_refuses_no_case():
    from kintsugi_evals.stats import stratified_bootstrap

    assert set(stratified_bootstrap([[], [2, 2]], sum, seed=1, iterations=10)) == {4}
    with pytest.raises(ValueError, match="at least one case"):
        stratified_bootstrap([[], []], sum, seed=1, iterations=10)
    with pytest.raises(ValueError, match="at least one resample"):
        stratified_bootstrap([[1]], sum, seed=1, iterations=0)


def test_one_sided_p_counts_ties_on_the_null_side():
    from kintsugi_evals.stats import one_sided_p

    draws = [-0.1, 0.0, 0.0, 0.1]
    # Alternative "above 0": the null side is <= 0, ties included.
    assert one_sided_p(draws, 0.0, above=True) == 0.75
    # Alternative "below 0": the null side is >= 0, ties included.
    assert one_sided_p(draws, 0.0, above=False) == 0.75
    assert one_sided_p(draws, -0.2, above=True) == 0.0
