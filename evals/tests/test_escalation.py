"""Tests of Config C's escalation from A to D (openspec/specs/config-c; ADR-030)."""

import pytest

from kintsugi_evals.escalation import (
    Evaluation,
    Rule,
    compose,
    evaluate,
    grid,
    pair,
    parse_rule,
    select,
    thresholds,
    trigger,
)
from kintsugi_evals.scoring import Row, UNKNOWN_ANSWER, outcome

TRUTH = "checkoutservice|RESOURCE_EXHAUSTION"
WRONG = "frontend|RESOURCE_EXHAUSTION"


def row(recording, status="COMPLETED", reported=TRUTH, level="L3", confidence=None, config="A", truth=TRUTH):
    return Row(config, "cpu", "decision", "local", "-", "dev", recording, status, truth, reported,
               outcome(status, truth, reported), level=level, confidence=confidence)


def test_only_cases_both_configs_ran_enter_c():
    # REQ-1 "A case only A ran".
    a = [row("x"), row("y")]
    d = [row("x", config="D")]
    assert [(first.case, second.case) for first, second in pair(a, d)] == [(("dev", "x"), ("dev", "x"))]


def test_healthy_cases_do_not_enter_c():
    a = [row("x"), row("h", truth="NONE|NONE", reported=UNKNOWN_ANSWER)]
    d = [row("x", config="D"), row("h", config="D", truth="NONE|NONE", reported=UNKNOWN_ANSWER)]
    assert [first.case for first, _ in pair(a, d)] == [("dev", "x")]


def test_a_case_twice_on_one_side_is_refused():
    with pytest.raises(ValueError, match="twice"):
        pair([row("x"), row("x")], [row("x", config="D")])


def test_no_shared_case_is_refused():
    with pytest.raises(ValueError, match="share no fault case"):
        pair([row("x")], [row("y", config="D")])


def test_a_verified_answer_stays_local():
    # REQ-2 "A verified answer stays local".
    assert trigger(row("x", level="L3", confidence=0.9), Rule("L3", None)) is None


def test_a_timeout_escalates_under_any_rule():
    # REQ-2 "A timeout escalates".
    timed_out = row("x", status="TIMED_OUT", reported="none", level="-", confidence=None)
    for rule in (Rule("L2", None), Rule("L3", None), Rule("L2", 0.875)):
        assert trigger(timed_out, rule) == "no-answer"


def test_an_abstention_escalates():
    assert trigger(row("x", reported=UNKNOWN_ANSWER, level="L1", confidence=0.0), Rule("L2", None)) == "abstain"


def test_a_low_level_escalates():
    # REQ-2 "A low level escalates".
    assert trigger(row("x", level="L2"), Rule("L3", None)) == "level"


def test_no_level_escalates_on_level():
    assert trigger(row("x", level="-"), Rule("L2", None)) == "level"
    assert trigger(row("x", level=None), Rule("L2", None)) == "level"


def test_a_low_confidence_escalates():
    # REQ-2 "A low confidence escalates".
    assert trigger(row("x", level="L3", confidence=0.85), Rule("L2", 0.875)) == "confidence"


def test_a_missing_confidence_never_triggers():
    assert trigger(row("x", level="L3", confidence=None), Rule("L2", 0.875)) is None


def test_the_first_trigger_in_order_is_recorded():
    assert trigger(row("x", reported=UNKNOWN_ANSWER, level="L0", confidence=0.1), Rule("L3", 0.5)) == "abstain"
    assert trigger(row("x", level="L1", confidence=0.1), Rule("L3", 0.5)) == "level"


def test_compose_takes_d_on_escalation_and_a_otherwise():
    pairs = pair([row("x", level="L3"), row("y", level="L1")], [row("x", config="D"), row("y", config="D")])
    choices = compose(pairs, Rule("L2", None))
    assert [(c.case, c.source, c.trigger, c.row.config) for c in choices] == [
        (("dev", "x"), "A", None, "A"), (("dev", "y"), "D", "level", "D")]


def test_thresholds_are_the_midpoints_between_distinct_a_confidences():
    pairs = pair([row(str(i), confidence=c) for i, c in enumerate([0.95, 0.85, 0.9, 0.85, None])],
                 [row(str(i), config="D") for i in range(5)])
    assert thresholds(pairs) == pytest.approx([0.875, 0.925])
    rules = grid(pairs)
    assert [(r.k, r.t) for r in rules] == [("L2", None), ("L2", pytest.approx(0.875)), ("L2", pytest.approx(0.925)),
                                           ("L3", None), ("L3", pytest.approx(0.875)), ("L3", pytest.approx(0.925))]


def test_evaluate_counts_escalations_triggers_and_c_against_d():
    a = [row("x", level="L3"), row("y", level="L1", reported=WRONG), row("z", status="FAILED", reported="none"),
         row("w", level="L3", reported=WRONG)]
    d = [row("x", config="D", reported=WRONG), row("y", config="D"), row("z", config="D"), row("w", config="D")]
    result = evaluate(pair(a, d), Rule("L2", None), seed=1, iterations=100)
    assert (result.cases, result.escalations, result.c_correct) == (4, 2, 3)
    assert result.triggers == {"no-answer": 1, "abstain": 0, "level": 1, "confidence": 0}
    # D is right on y, z, w; C is right on x, y, z: one discordant case each way, so C - D is 0.
    assert (result.pair.only_first, result.pair.only_second, result.pair.diff) == (1, 1, 0)
    assert result.eligible
    assert result.rate_ci[0] < 0.5 < result.rate_ci[1]


def scored(k, t, escalations, correct, cases=30):
    return Evaluation(Rule(k, t), cases, escalations, {}, correct, None)


def test_the_cap_makes_a_rule_ineligible():
    # REQ-3 "Cap binds".
    assert not scored("L3", None, 20, 20).eligible
    assert scored("L2", None, 11, 15).eligible
    assert scored("L2", None, 15, 15).eligible
    chosen, cap_fails = select([scored("L2", None, 11, 15), scored("L3", None, 20, 20)])
    assert (chosen.rule, cap_fails) == (Rule("L2", None), False)


def test_a_tie_on_accuracy_goes_to_the_fewer_escalations():
    # REQ-3 "Tie on accuracy".
    chosen, _ = select([scored("L2", 0.875, 13, 15), scored("L2", None, 11, 15)])
    assert chosen.escalations == 11


def test_further_ties_go_to_the_higher_k_then_to_t_off():
    chosen, _ = select([scored("L2", None, 11, 15), scored("L3", 0.875, 11, 15), scored("L3", None, 11, 15)])
    assert chosen.rule == Rule("L3", None)


def test_the_highest_accuracy_wins_over_fewer_escalations():
    chosen, _ = select([scored("L2", None, 5, 12), scored("L3", None, 14, 16)])
    assert chosen.rule == Rule("L3", None)


def test_nothing_fits_selects_the_literal_rule_and_says_the_cap_fails():
    # REQ-3 "Nothing fits".
    chosen, cap_fails = select([scored("L2", None, 16, 20), scored("L3", None, 20, 22), scored("L3", 0.9, 25, 23)])
    assert (chosen.rule, cap_fails) == (Rule("L3", None), True)


# REQ-5: a fixed rule, parsed from `--rule`.


@pytest.mark.parametrize("text, rule", [("k=L2,t=off", Rule("L2", None)), ("k=L3,t=0.875", Rule("L3", 0.875)),
                                        (" t=off , k=L3 ", Rule("L3", None))])
def test_parse_rule_reads_k_and_t(text, rule):
    assert parse_rule(text) == rule


@pytest.mark.parametrize("text", ["k=L1,t=off", "L2", "k=L2", "t=off", "k=L2,t=high", "k=L2,t=1.5", "k=L2,t=off,x=1",
                                  "k=L2,k=L3,t=off", "k=L2,t=nan"])
def test_parse_rule_refuses_anything_else(text):
    with pytest.raises(ValueError, match="rule"):
        parse_rule(text)
