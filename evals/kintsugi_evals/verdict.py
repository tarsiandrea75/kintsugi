"""The H1-H3 verdict: one-sided bootstrap tests, intersection-union and Holm (ADR-031, openspec/specs/verdict).

Each condition is a null hypothesis that its margin is not met. Its p-value is the share of case-level bootstrap
resamples whose statistic falls on the null side of the margin, ties included. A hypothesis holds only if all its
conditions hold, so its p-value is the largest of theirs; Holm then decides {H1, H2, H3} at a familywise 0.025,
one-sided. Why this shape: the vision states each hypothesis as a direction and a margin on a 95% CI, and a one-sided
test at 0.025 passes exactly when that CI clears the margin, so the verdict keeps the vision's wording while giving
Holm the p-values it needs.
"""

import statistics
from dataclasses import dataclass, field

from .escalation import Rule, trigger
from .scoring import CORRECT, FALSE_ALARM
from .stats import holm, one_sided_p, percentile_ci, stratified_bootstrap

# Decision 3: one-sided alpha, the one-sided reading of the vision's two-sided 95% CI.
ALPHA = 0.025
FAMILY = ("H1", "H2", "H3")
# ADR-030's rule, fixed for every holdout read (ADR-031 Decision 2).
FIXED_RULE = Rule("L2", None)
# H1's "rules are enough" reading: the 95% CI's upper bound below +5 points (vision §1).
RULES_ENOUGH = 0.05
# H2c's margin: 15 minutes, in the summary's seconds.
MINUTES_15 = 15 * 60.0
LABELS = ("0R", "A", "D")


@dataclass(frozen=True)
class Spec:
    """One row of ADR-031 Decision 1's table: the condition holds when the statistic is above (or below) margin."""
    name: str
    hypothesis: str
    statistic: str
    margin: float
    above: bool


SPECS = (
    Spec("H1", "H1", "D - 0R service AC@1, fault cases", 0.0, True),
    Spec("H2a", "H2", "A - D AC@1, fault cases", -0.10, True),
    Spec("H2b", "H2", "false-alarm rate A - D, healthy cases", 0.10, False),
    Spec("H2c", "H2", "median A time to report (s), all A cases outside H2b-only tracks", MINUTES_15, False),
    Spec("H3a", "H3", "C - D AC@1, A and D fault cases", -0.05, True),
    Spec("H3b", "H3", "C escalation rate, A and D fault cases", 0.50, False),
)


@dataclass(frozen=True)
class Condition:
    name: str
    margin: float
    above: bool
    cases: int
    # None when the condition has no case: it then cannot hold, and p is 1.
    estimate: float
    low: float
    high: float
    p: float

    @property
    def passes(self):
        """Unadjusted: p below ALPHA, which is exactly the 95% percentile CI clearing the margin."""
        return self.p < ALPHA


@dataclass(frozen=True)
class Hypothesis:
    name: str
    conditions: tuple
    # Intersection-union: the largest of its conditions' p-values.
    p: float
    holm_p: float
    holds: bool


@dataclass(frozen=True)
class Verdict:
    conditions: list
    hypotheses: list
    rules_enough: bool
    seed: int
    iterations: int


@dataclass
class Track:
    """One holdout's rows by config label (0R, A, D), A's time per case, and how its AC@1 is scored."""
    name: str
    rows: dict
    service_only: bool = False
    # A's seconds per case, (split, recording) -> one value per repeat; empty when the track has no A.
    seconds: dict = field(default_factory=dict)
    # Healthy pseudo-cases that enter H2b only (ADR-034): their lighter dossiers stay out of H2c's median.
    h2b_only: bool = False


def _mean(values):
    return sum(values) / len(values)


def _median(values):
    return statistics.median(values)


def test_condition(name, strata, statistic, margin, above, seed, iterations):
    """One condition's estimate, 95% percentile CI and one-sided p-value from per-track lists of per-case values.

    A condition with no case cannot show its margin is met, so its p-value is 1 and it does not hold.
    """
    cases = sum(len(s) for s in strata)
    if not cases:
        return Condition(name, margin, above, 0, None, None, None, 1.0)
    draws = stratified_bootstrap(strata, statistic, seed, iterations)
    low, high = percentile_ci(draws)
    pooled = [v for s in strata for v in s]
    return Condition(name, margin, above, cases, statistic(pooled), low, high, one_sided_p(draws, margin, above))


# pytest would collect test_condition as a test; it is the module's API, not a test.
test_condition.__test__ = False


def intersection_union(conditions):
    """Decision 4: a hypothesis holds only if every condition holds, so its p-value is the largest of theirs."""
    return max(c.p for c in conditions)


def holm_step_down(pvalues, alpha=ALPHA):
    """Decision 5: which nulls Holm rejects, in input order.

    Sorted ascending, the i-th p-value (from 0) is compared with alpha / (m - i); the first that is not below its
    threshold fails, and so does every larger one.
    """
    m = len(pvalues)
    rejected = [False] * m
    for rank, i in enumerate(sorted(range(m), key=lambda i: pvalues[i])):
        if not pvalues[i] < alpha / (m - rank):
            break
        rejected[i] = True
    return rejected


def _by_case(rows, keep, measure):
    """Each kept row's 0/1 measure, grouped by case: a case's repeats move together (ADR-015)."""
    cases = {}
    for r in rows:
        if keep(r):
            cases.setdefault(r.case, []).append(measure(r))
    return cases


def _paired(first_rows, second_rows, keep, measure):
    """Per-case differences, second minus first, over the cases both ran, in case order."""
    first, second = _by_case(first_rows, keep, measure), _by_case(second_rows, keep, measure)
    return [_mean(second[c]) - _mean(first[c]) for c in sorted(set(first) & set(second))]


def _fault(r):
    return not r.healthy


def _healthy(r):
    return r.healthy


def _correct(r):
    return 1 if r.outcome == CORRECT else 0


def _false_alarm(r):
    return 1 if r.outcome == FALSE_ALARM else 0


def compose_c(a_rows, d_rows, rule=FIXED_RULE):
    """C on the fault cases A and D both ran: case -> (escalated, C's 0/1 outcomes, D's 0/1 outcomes).

    C keeps A's answer or, when a trigger fires on A's row (escalation.trigger), takes D's rows, every repeat of them,
    so a repeated D case moves together. A case twice under A is refused, as escalation.pair refuses it.
    """
    a = {}
    for r in a_rows:
        if r.healthy:
            continue
        if r.case in a:
            raise ValueError(f"A holds the case {r.split}/{r.recording} twice: C needs one A row per case")
        a[r.case] = r
    d = _by_case(d_rows, _fault, _correct)
    cases = {}
    for case in sorted(set(a) & set(d)):
        escalated = trigger(a[case], rule) is not None
        cases[case] = (escalated, list(d[case]) if escalated else [_correct(a[case])], list(d[case]))
    return cases


def _need(track, label, hypothesis):
    if label not in track.rows:
        raise ValueError(f"{hypothesis} track {track.name} has no {label}")
    return track.rows[label]


def _h2b_only(track):
    """An H2b-only track's per-case false-alarm differences, A minus D, after refusing any fault row."""
    for label, rows in sorted(track.rows.items()):
        fault = next((r for r in rows if not r.healthy), None)
        if fault is not None:
            raise ValueError(f"H2b-only track {track.name} holds a fault case under {label}: "
                             f"{fault.split}/{fault.recording}")
    return _paired(_need(track, "D", "H2b"), _need(track, "A", "H2b"), _healthy, _false_alarm)


def strata(tracks, h1_tracks, rule=FIXED_RULE):
    """Each condition's per-track lists of per-case values, keyed by condition name.

    H1 pools the named tracks, each scored on the service. H2 and H3 pool every track that has both A and D;
    H2c reads A's times on every track that has A. An H2b-only track holds healthy rows only, needs A and D, and
    enters H2b alone.
    """
    names = {t.name: t for t in tracks}
    values = {spec.name: [] for spec in SPECS}
    for name in h1_tracks:
        if name not in names:
            raise ValueError(f"H1 track {name} has no input")
        track = names[name]
        if track.h2b_only:
            raise ValueError(f"H1 track {name} is H2b-only: its pseudo-cases are healthy, and H1 reads fault cases")
        if not track.service_only:
            raise ValueError(f"H1 track {name} is not scored on the service: H1 is service AC@1, give --service-only")
        values["H1"].append(_paired(_need(track, "0R", "H1"), _need(track, "D", "H1"), _fault, _correct))
    for track in tracks:
        if track.h2b_only:
            values["H2b"].append(_h2b_only(track))
            continue
        if "A" in track.rows:
            a_cases = {r.case for r in track.rows["A"]}
            missing = sorted(a_cases - set(track.seconds))
            if missing:
                raise ValueError(f"track {track.name}: A has no time for {missing[0][0]}/{missing[0][1]}")
            values["H2c"].append([_mean(track.seconds[c]) for c in sorted(a_cases)])
        if "A" not in track.rows or "D" not in track.rows:
            continue
        a, d = track.rows["A"], track.rows["D"]
        values["H2a"].append(_paired(d, a, _fault, _correct))
        values["H2b"].append(_paired(d, a, _healthy, _false_alarm))
        c = compose_c(a, d, rule)
        values["H3a"].append([_mean(mine) - _mean(theirs) for _, mine, theirs in c.values()])
        values["H3b"].append([1 if escalated else 0 for escalated, _, _ in c.values()])
    return values


def verdict(tracks, h1_tracks, seed, iterations=10000, rule=FIXED_RULE):
    """ADR-031's verdict on the given tracks: every condition, every hypothesis and H1's "rules are enough" reading.

    Each condition's bootstrap starts from the same recorded seed, so its draws do not depend on the others.
    """
    values = strata(tracks, h1_tracks, rule)
    conditions = []
    for spec in SPECS:
        statistic = _median if spec.name == "H2c" else _mean
        conditions.append(test_condition(spec.name, values[spec.name], statistic, spec.margin, spec.above, seed,
                                         iterations))
    p = [intersection_union([c for c, s in zip(conditions, SPECS) if s.hypothesis == h]) for h in FAMILY]
    rejected = holm_step_down(p)
    hypotheses = [Hypothesis(h, tuple(c.name for c, s in zip(conditions, SPECS) if s.hypothesis == h), p[i],
                             adjusted, rejected[i]) for i, (h, adjusted) in enumerate(zip(FAMILY, holm(p)))]
    h1 = conditions[0]
    return Verdict(conditions, hypotheses, h1.high is not None and h1.high < RULES_ENOUGH, seed, iterations)
