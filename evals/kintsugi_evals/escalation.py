"""Config C: A's answer, escalated to D's on a stated trigger (ADR-030, openspec/specs/config-c).

C runs nothing. It joins A's and D's summary rows on (split, recording) and, case by case, keeps A's row or takes D's.
The rule is a minimum verification level k and an optional confidence cut t, both set on dev by `select`, so the
choice of rule is a stated procedure and not a judgment made after seeing a holdout.
"""

from dataclasses import dataclass, replace

from .scoring import CORRECT, LEVELS, UNKNOWN_ANSWER, at_least, paired
from .stats import wilson

# The levels a rule may require (ADR-030 Decision 2), lowest first.
FLOORS = ("L2", "L3")
# The triggers, in the order they are tried: the first that fires is the one recorded (REQ-2).
TRIGGERS = ("no-answer", "abstain", "level", "confidence")
# H3's cost limit: a rule is eligible when it escalates at most this share of the paired cases.
CAP = 0.5
C_LABEL = "C"


@dataclass(frozen=True)
class Rule:
    k: str
    # None is "off": no confidence trigger.
    t: float = None

    @property
    def label(self):
        return f"k={self.k}, t={format_t(self.t)}"


def format_t(t):
    return "off" if t is None else f"{t:g}"


@dataclass(frozen=True)
class Choice:
    case: tuple
    source: str
    trigger: str
    row: object


def pair(a_rows, d_rows):
    """The (A, D) rows of each fault case both ran, in case order (REQ-1); healthy rows never enter C.

    A case present twice on one side is refused: C takes one row per case, and a repetition has no single answer.
    """
    sides = []
    for name, rows in (("A", a_rows), ("D", d_rows)):
        cases = {}
        for row in rows:
            if row.healthy:
                continue
            if row.case in cases:
                raise ValueError(f"{name} holds the case {row.split}/{row.recording} twice: C needs one row per case")
            cases[row.case] = row
        sides.append(cases)
    a, d = sides
    common = sorted(set(a) & set(d))
    if not common:
        raise ValueError("A and D share no fault case")
    return [(a[case], d[case]) for case in common]


def trigger(a, rule):
    """The first trigger that fires on A's row under `rule`, or None when C keeps A's answer (REQ-2)."""
    if a.status != "COMPLETED":
        return "no-answer"
    if a.reported == UNKNOWN_ANSWER:
        return "abstain"
    if not at_least(a.level, rule.k):
        return "level"
    if rule.t is not None and a.confidence is not None and a.confidence < rule.t:
        return "confidence"
    return None


def compose(pairs, rule):
    """C under `rule`: one Choice per paired case, holding A's row or, on a trigger, D's."""
    choices = []
    for a, d in pairs:
        fired = trigger(a, rule)
        choices.append(Choice(a.case, "A" if fired is None else "D", fired, a if fired is None else d))
    return choices


def thresholds(pairs):
    """The midpoints between consecutive distinct A confidences on the paired cases, ascending."""
    values = sorted({a.confidence for a, _ in pairs if a.confidence is not None})
    return [(low + high) / 2 for low, high in zip(values, values[1:])]


def grid(pairs):
    """Every rule of ADR-030's grid: k in FLOORS, t off or a midpoint, in that order."""
    return [Rule(k, t) for k in FLOORS for t in [None, *thresholds(pairs)]]


@dataclass(frozen=True)
class Evaluation:
    rule: Rule
    cases: int
    escalations: int
    # The count of each trigger, keyed by TRIGGERS.
    triggers: dict
    c_correct: int
    # C against D on the paired cases, as scoring.paired gives it: first D, second C, so diff is C - D.
    pair: object

    @property
    def rate(self):
        return self.escalations / self.cases

    @property
    def rate_ci(self):
        return wilson(self.escalations, self.cases)

    @property
    def ac1(self):
        return self.c_correct / self.cases

    @property
    def ac1_ci(self):
        return wilson(self.c_correct, self.cases)

    @property
    def eligible(self):
        return self.escalations <= CAP * self.cases


def evaluate(pairs, rule, seed, iterations):
    """C's escalations and AC@1 under `rule`, and C - D paired, on the paired cases.

    Correctness is each row's own outcome, so --service-only scores C exactly as `score` scores A and D.
    """
    choices = compose(pairs, rule)
    counts = {name: sum(c.trigger == name for c in choices) for name in TRIGGERS}
    c_rows = [replace(c.row, config=C_LABEL) for c in choices]
    d_rows = [d for _, d in pairs]
    return Evaluation(rule, len(choices), sum(c.source == "D" for c in choices), counts,
                      sum(r.outcome == CORRECT for r in c_rows), paired(d_rows, c_rows, seed, iterations))


def select(evaluations):
    """The rule ADR-030 Decision 3 selects, and whether the cost cap fails (no rule is eligible).

    Among eligible rules: the highest C AC@1, then the fewer escalations, then the higher k, then t off. Two rules
    still tied escalate the same cases (t only adds escalations), so the lower t is taken to stay deterministic.
    With none eligible, the literal vision rule k = L3, t = off.
    """
    eligible = [e for e in evaluations if e.eligible]
    if not eligible:
        return next(e for e in evaluations if e.rule == Rule("L3", None)), True
    return min(eligible, key=lambda e: (-e.c_correct, e.escalations, -LEVELS.index(e.rule.k), e.rule.t is not None,
                                        e.rule.t or 0.0)), False
