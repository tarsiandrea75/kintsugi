"""Tests of the H1-H3 verdict (openspec/specs/verdict; ADR-031)."""

import pytest

from kintsugi_evals.escalation import Rule
from kintsugi_evals.scoring import UNKNOWN_ANSWER, Row, outcome
from kintsugi_evals.verdict import (
    ALPHA,
    FIXED_RULE,
    Track,
    compose_c,
    holm_step_down,
    intersection_union,
    test_condition as run_condition,
    verdict,
)

TRUTH = "checkoutservice|RESOURCE_EXHAUSTION"
WRONG = "frontend|RESOURCE_EXHAUSTION"
HEALTHY = "NONE|NONE"


def mean(xs):
    return sum(xs) / len(xs)


def row(config, recording, right=True, truth=TRUTH, reported=None, status="COMPLETED", level="L3"):
    if reported is None:
        reported = truth if right else WRONG
    return Row(config, "cpu", "decision", "-", "-", "holdout", recording, status, truth, reported,
               outcome(status, truth, reported), level=level)


# Decision 2: the one-sided bootstrap p-value.


def test_a_condition_every_resample_clears_has_p_zero():
    c = run_condition("H1", [[0.5, 0.5, 0.5]], mean, margin=0.0, above=True, seed=1, iterations=500)
    assert (c.p, c.estimate, c.low, c.high, c.cases) == (0.0, 0.5, 0.5, 0.5, 3)
    assert c.passes


def test_ties_with_the_margin_count_on_the_null_side():
    above = run_condition("H1", [[0.0, 0.0]], mean, margin=0.0, above=True, seed=1, iterations=500)
    below = run_condition("H3b", [[0.5, 0.5]], mean, margin=0.5, above=False, seed=1, iterations=500)
    assert (above.p, below.p) == (1.0, 1.0)
    assert not above.passes and not below.passes


def test_a_fixed_seed_is_reproducible():
    values = [[1, 0, 0, 1, 1, 0, 1, 1, -1, 0]]
    first = run_condition("H1", values, mean, 0.0, True, seed=7, iterations=1000)
    second = run_condition("H1", values, mean, 0.0, True, seed=7, iterations=1000)
    other = run_condition("H1", values, mean, 0.0, True, seed=8, iterations=1000)
    assert first == second
    assert 0 < first.p < 1
    assert other.p != first.p


def test_pooling_resamples_within_each_track_and_keeps_its_size():
    # Track 1 is all 1, track 2 all 0: within-track resampling keeps 3 of 10, so every resample is exactly 0.3.
    c = run_condition("H1", [[1, 1, 1], [0] * 7], mean, margin=0.29, above=True, seed=1, iterations=500)
    assert (c.estimate, c.low, c.high, c.p, c.cases) == (0.3, 0.3, 0.3, 0.0, 10)


def test_a_condition_with_no_case_is_not_supported():
    c = run_condition("H2b", [[], []], mean, margin=0.1, above=False, seed=1, iterations=100)
    assert (c.cases, c.estimate, c.p) == (0, None, 1.0)
    assert not c.passes


def test_alpha_is_one_sided_0025():
    assert ALPHA == 0.025


# Decisions 4 and 5: intersection-union and Holm.


def test_intersection_union_takes_the_largest_p():
    conditions = [run_condition("a", [[1.0]], mean, 0.0, True, 1, 10),
                  run_condition("b", [[0.0]], mean, 0.0, True, 1, 10)]
    assert intersection_union(conditions) == 1.0
    assert intersection_union([conditions[0]]) == 0.0


def test_holm_step_down_stops_at_the_first_failure():
    # Sorted: 0.001 < 0.025/3 passes; 0.02 >= 0.025/2 fails, so it and 0.03 fail.
    assert holm_step_down([0.001, 0.02, 0.03]) == [True, False, False]
    # Input order is kept.
    assert holm_step_down([0.03, 0.001, 0.02]) == [False, True, False]


def test_holm_step_down_fails_everything_when_the_smallest_fails():
    # 0.024 alone would pass at 0.025, but the step-down stopped at 0.009 >= 0.025/3.
    assert holm_step_down([0.009, 0.02, 0.024]) == [False, False, False]


def test_holm_step_down_passes_all_when_each_clears_its_threshold():
    assert holm_step_down([0.02, 0.001, 0.012]) == [True, True, True]


def test_holm_threshold_is_strict():
    # Equal to the threshold is not below it, so it fails.
    assert holm_step_down([0.025, 0.0, 0.0]) == [False, True, True]
    assert holm_step_down([0.025 / 3, 0.5, 0.5]) == [False, False, False]


# The whole family on synthetic tracks.


def rcaeval_track(name, d_right, r_right):
    """A service-only track where D and 0R answer each case as the lists say."""
    rows = {"D": [row("D", f"c{i}", right) for i, right in enumerate(d_right)],
            "0R": [row("0R", f"c{i}", right) for i, right in enumerate(r_right)]}
    return Track(name, rows, service_only=True)


def lab_track(a, d, healthy_a=(), healthy_d=(), seconds=None):
    """A lab track: a and d are (right, level) per fault case; healthy_* are false-alarm flags per healthy case."""
    a_rows = [row("A", f"f{i}", right, level=level) for i, (right, level) in enumerate(a)]
    d_rows = [row("D", f"f{i}", right) for i, (right, _) in enumerate(d)]
    for i, alarm in enumerate(healthy_a):
        a_rows.append(row("A", f"h{i}", truth=HEALTHY, reported=WRONG if alarm else UNKNOWN_ANSWER, level="-"))
    for i, alarm in enumerate(healthy_d):
        d_rows.append(row("D", f"h{i}", truth=HEALTHY, reported=WRONG if alarm else UNKNOWN_ANSWER, level="-"))
    seconds = seconds or {r.case: [300.0] for r in a_rows}
    return Track("holdout-2", {"A": a_rows, "D": d_rows}, seconds=seconds)


def by_name(result):
    return {c.name: c for c in result.conditions}, {h.name: h for h in result.hypotheses}


def test_h1_holds_when_d_beats_0r_everywhere():
    track = rcaeval_track("A", [True] * 10, [False] * 10)
    result = verdict([track], h1_tracks=["A"], seed=1, iterations=500)
    conditions, hypotheses = by_name(result)
    assert (conditions["H1"].estimate, conditions["H1"].p) == (1.0, 0.0)
    assert hypotheses["H1"].holds
    assert not result.rules_enough


def test_rules_are_enough_when_h1_upper_bound_is_below_five_points():
    track = rcaeval_track("A", [True, False] * 10, [True, False] * 10)
    result = verdict([track], h1_tracks=["A"], seed=1, iterations=500)
    conditions, hypotheses = by_name(result)
    assert (conditions["H1"].high, conditions["H1"].p) == (0.0, 1.0)
    assert result.rules_enough
    assert not hypotheses["H1"].holds


def test_d_repeats_of_a_case_move_together():
    # c0 has D right on one of two repeats: the case mean is 0.5, weighed once.
    rows = {"D": [row("D", "c0", True), row("D", "c0", False), row("D", "c1", True)],
            "0R": [row("0R", "c0", False), row("0R", "c1", False)]}
    result = verdict([Track("A", rows, service_only=True)], h1_tracks=["A"], seed=1, iterations=200)
    conditions, _ = by_name(result)
    assert (conditions["H1"].cases, conditions["H1"].estimate) == (2, 0.75)


def test_h1_pools_its_tracks():
    a = rcaeval_track("A", [True] * 4, [False] * 4)
    b = rcaeval_track("B", [False] * 6, [False] * 6)
    result = verdict([a, b], h1_tracks=["A", "B"], seed=1, iterations=300)
    h1 = by_name(result)[0]["H1"]
    # Within-track resampling: 4 of 10 cases always gain a point, so the statistic never moves.
    assert (h1.cases, h1.estimate, h1.low, h1.high) == (10, 0.4, 0.4, 0.4)


def test_h1_refuses_a_track_without_d_or_0r():
    with pytest.raises(ValueError, match="H1 track B has no 0R"):
        verdict([Track("B", {"D": [row("D", "c0")]}, service_only=True)], h1_tracks=["B"], seed=1, iterations=10)


def test_h1_refuses_a_track_not_scored_on_the_service():
    with pytest.raises(ValueError, match="service"):
        verdict([Track("B", {"D": [row("D", "c0")], "0R": [row("0R", "c0")]})], h1_tracks=["B"], seed=1,
                iterations=10)


def test_h2_and_h3_hold_when_a_matches_d_locally():
    # A right and at L3 everywhere: C keeps A, so C - D = A - D = 0, and C escalates nothing.
    lab = lab_track([(True, "L3")] * 8, [(True, None)] * 8, healthy_a=[False] * 5, healthy_d=[False] * 5)
    r = rcaeval_track("A", [True] * 8, [False] * 8)
    result = verdict([r, lab], h1_tracks=["A"], seed=1, iterations=500)
    conditions, hypotheses = by_name(result)
    assert [conditions[n].p for n in ("H2a", "H2b", "H2c", "H3a", "H3b")] == [0.0] * 5
    assert conditions["H3b"].estimate == 0.0
    assert conditions["H2c"].estimate == 300.0
    assert all(h.holds for h in hypotheses.values())


def test_h2b_reads_the_false_alarm_rate_on_healthy_cases():
    # A raises a false alarm on every healthy case and D on none: A - D = +100 points, over the +10 margin.
    lab = lab_track([(True, "L3")] * 4, [(True, None)] * 4, healthy_a=[True] * 4, healthy_d=[False] * 4)
    result = verdict([lab], h1_tracks=[], seed=1, iterations=200)
    conditions, hypotheses = by_name(result)
    assert (conditions["H2b"].cases, conditions["H2b"].estimate, conditions["H2b"].p) == (4, 1.0, 1.0)
    assert not hypotheses["H2"].holds


def test_h2_is_not_supported_without_healthy_cases():
    lab = lab_track([(True, "L3")] * 4, [(True, None)] * 4)
    conditions, hypotheses = by_name(verdict([lab], h1_tracks=[], seed=1, iterations=200))
    assert (conditions["H2b"].cases, conditions["H2b"].p) == (0, 1.0)
    assert not hypotheses["H2"].holds


def test_h2c_bootstraps_the_median_of_a_own_times():
    fault = [(True, "L3")] * 2
    # Two cases at 600 s and 1200 s: a resample's median is 600 (1 in 4), 1200 (1 in 4) or 900, a tie (1 in 2).
    seconds = {("holdout", "f0"): [600.0], ("holdout", "f1"): [1200.0]}
    lab = lab_track(fault, fault, seconds=seconds)
    h2c = by_name(verdict([lab], h1_tracks=[], seed=1, iterations=10000))[0]["H2c"]
    assert h2c.estimate == 900.0
    assert h2c.p == pytest.approx(0.75, abs=0.02)


def test_h2c_case_time_is_the_mean_of_its_repeats():
    fault = [(True, "L3")]
    lab = lab_track(fault, fault, seconds={("holdout", "f0"): [600.0, 1000.0]})
    assert by_name(verdict([lab], h1_tracks=[], seed=1, iterations=50))[0]["H2c"].estimate == 800.0


def pseudo_track(name, alarms_a, alarms_d, seconds=60.0):
    """An h2b-only track of healthy pseudo-cases: A and D raise a false alarm where the flags say."""
    a_rows = [row("A", f"p{i}", truth=HEALTHY, reported=WRONG if alarm else UNKNOWN_ANSWER, level="-")
              for i, alarm in enumerate(alarms_a)]
    d_rows = [row("D", f"p{i}", truth=HEALTHY, reported=WRONG if alarm else UNKNOWN_ANSWER, level="-")
              for i, alarm in enumerate(alarms_d)]
    return Track(name, {"A": a_rows, "D": d_rows}, service_only=True, seconds={r.case: [seconds] for r in a_rows},
                 h2b_only=True)


def test_an_h2b_only_track_enters_h2b_and_nothing_else():
    lab = lab_track([(True, "L3")] * 4, [(True, None)] * 4, healthy_a=[False] * 3, healthy_d=[False] * 3,
                    seconds=None)
    lab.seconds = {case: [600.0] for case in lab.seconds}
    alone = by_name(verdict([lab], h1_tracks=[], seed=1, iterations=200))[0]
    # Five fast pseudo-cases, one false alarm by A: H2b gains their cases, H2c's median does not move.
    pooled = by_name(verdict([lab, pseudo_track("A-healthy", [True] + [False] * 4, [False] * 5)], h1_tracks=[],
                             seed=1, iterations=200))[0]
    assert (alone["H2b"].cases, pooled["H2b"].cases) == (3, 8)
    assert pooled["H2b"].estimate == pytest.approx(1 / 8)
    assert (pooled["H2c"].cases, pooled["H2c"].estimate) == (alone["H2c"].cases, 600.0) == (7, 600.0)
    for name in ("H1", "H2a", "H3a", "H3b"):
        assert (pooled[name].cases, pooled[name].estimate) == (alone[name].cases, alone[name].estimate)


def test_an_h2b_only_track_with_a_fault_row_is_refused():
    pseudo = pseudo_track("A-healthy", [False] * 2, [False] * 2)
    pseudo.rows["D"].append(row("D", "f0"))
    with pytest.raises(ValueError, match="H2b-only track A-healthy holds a fault case"):
        verdict([pseudo], h1_tracks=[], seed=1, iterations=10)


def test_an_h2b_only_track_cannot_be_an_h1_track():
    with pytest.raises(ValueError, match="A-healthy is H2b-only"):
        verdict([pseudo_track("A-healthy", [False], [False])], h1_tracks=["A-healthy"], seed=1, iterations=10)


def test_an_h2b_only_track_needs_a_and_d():
    pseudo = pseudo_track("A-healthy", [False], [False])
    del pseudo.rows["A"]
    with pytest.raises(ValueError, match="H2b track A-healthy has no A"):
        verdict([pseudo], h1_tracks=[], seed=1, iterations=10)


def test_h3b_reads_c_escalation_rate():
    # One of four A answers is below L2, so C escalates 1/4; a resample reaches 50% when it draws it twice or more.
    a = [(True, "L1"), (True, "L3"), (True, "L3"), (True, "L3")]
    lab = lab_track(a, [(True, None)] * 4)
    h3b = by_name(verdict([lab], h1_tracks=[], seed=1, iterations=10000))[0]["H3b"]
    assert h3b.estimate == 0.25
    # P(Binomial(4, 1/4) >= 2) = 1 - 0.75^4 - 4 * 0.25 * 0.75^3 = 0.2617.
    assert h3b.p == pytest.approx(0.2617, abs=0.02)


def test_h3b_fails_when_c_escalates_everything():
    lab = lab_track([(False, "L1")] * 4, [(True, None)] * 4)
    conditions, hypotheses = by_name(verdict([lab], h1_tracks=[], seed=1, iterations=200))
    assert (conditions["H3b"].estimate, conditions["H3b"].p) == (1.0, 1.0)
    # C takes D's rows on every case, so C - D is 0, above the -5 margin.
    assert (conditions["H3a"].estimate, conditions["H3a"].p) == (0.0, 0.0)
    assert not hypotheses["H3"].holds


def test_compose_c_uses_adr_030_rule_and_carries_d_repeats():
    assert FIXED_RULE == Rule("L2", None)
    a = [row("A", "x", True, level="L3"), row("A", "y", False, level="L1"), row("A", "only-a")]
    d = [row("D", "x", False), row("D", "y", True), row("D", "y", False)]
    cases = compose_c(a, d, FIXED_RULE)
    assert sorted(cases) == [("holdout", "x"), ("holdout", "y")]
    assert cases[("holdout", "x")] == (False, [1], [0])
    assert cases[("holdout", "y")] == (True, [1, 0], [1, 0])


def test_compose_c_refuses_a_repeated_a_case():
    with pytest.raises(ValueError, match="twice"):
        compose_c([row("A", "x"), row("A", "x")], [row("D", "x")], FIXED_RULE)


def test_holm_decides_the_family():
    # H1 holds on its own; H2 and H3 have no A, so their conditions have no case and p = 1.
    result = verdict([rcaeval_track("A", [True] * 10, [False] * 10)], h1_tracks=["A"], seed=1, iterations=200)
    hypotheses = by_name(result)[1]
    assert (hypotheses["H1"].p, hypotheses["H2"].p, hypotheses["H3"].p) == (0.0, 1.0, 1.0)
    assert [hypotheses[n].holds for n in ("H1", "H2", "H3")] == [True, False, False]
    assert hypotheses["H1"].holm_p == 0.0


def test_an_unadjusted_pass_is_exactly_the_95_ci_clearing_the_margin():
    # Decision 3: p < 0.025 one-sided exactly when the two-sided 95% percentile CI lies above the margin.
    values = [[1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1]]
    for margin in [x / 100 for x in range(0, 100, 3)]:
        c = run_condition("H1", values, mean, margin, True, seed=2, iterations=2000)
        assert c.passes == (c.low > margin), margin
