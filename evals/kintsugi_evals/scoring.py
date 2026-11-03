"""Reads the replay driver's summary.tsv and scores each row (ADR-015, openspec/specs/evals).

The verdict column is not trusted: each row is rescored from status, truth and reported,
so abstentions and healthy cases follow the protocol's rule rather than MATCH/MISS.
"""

import csv
import json
from dataclasses import dataclass
from pathlib import Path

from .stats import cluster_bootstrap_diff, mcnemar_exact, wilson

CORRECT = "correct"
MISS = "miss"
ABSTAIN = "abstain"
FALSE_ALARM = "false_alarm"

UNKNOWN_ANSWER = "UNKNOWN|UNKNOWN"
# Provisional until follow-up 10 (lab-realism) fixes the format of healthy recordings.
HEALTHY_TRUTH = "NONE|NONE"
NO_REPORT = "none"

# Rows whose candidates column is meaningful (hybrid T4): the rules' list, and the list a hybrid report chose from.
CANDIDATE_ROUTE = "rules"
CANDIDATE_REPORT = "hybrid"

# The service parts that are not an answer, so never enter a ranking.
NO_SERVICE = ("", NO_REPORT, "UNKNOWN")
# The longest list the ranked metrics read: AC@1 to AC@5, and their mean Avg@5.
RANK_DEPTH = 5

# The split whose runs the benchmark asserts executed no risky call (ADR-027, Decision 3).
HOLDOUT_SPLIT = "holdout"

REQUIRED = ("fault", "mode", "route", "status", "truth", "reported", "split", "recording")


def is_healthy(truth):
    return truth == HEALTHY_TRUTH


def service_of(answer):
    """The service part of a service|category pair."""
    return answer.split("|", 1)[0]


def names_truth(truth, answer, service_only=False):
    """Whether an answer names the truth: the whole pair, or with service_only its service part alone."""
    return service_of(answer) == service_of(truth) if service_only else answer == truth


def outcome(status, truth, reported, service_only=False):
    """The outcome of one row: correct, miss, abstain (fault answered UNKNOWN) or false_alarm.

    With service_only (rcaeval-converter T5), a fault row is correct when the reported service is the truth's,
    whatever the category: RCAEval's AC@1 names the root-cause service only. Abstentions and healthy rows keep
    their rule.
    """
    completed = status == "COMPLETED"
    has_cause = completed and reported not in ("", NO_REPORT, UNKNOWN_ANSWER)
    if is_healthy(truth):
        if completed and reported == UNKNOWN_ANSWER:
            return CORRECT
        return FALSE_ALARM if has_cause else MISS
    if completed and reported != UNKNOWN_ANSWER and names_truth(truth, reported, service_only):
        return CORRECT
    if completed and reported == UNKNOWN_ANSWER:
        return ABSTAIN
    return MISS


@dataclass(frozen=True)
class Row:
    config: str
    fault: str
    mode: str
    route: str
    view: str
    split: str
    recording: str
    status: str
    truth: str
    reported: str
    outcome: str
    report: str = "-"
    # The candidate (service|category) pairs, or None where candidate recall does not apply.
    candidates: tuple = None
    # Scored on the service part of the truth only (--service-only).
    service_only: bool = False
    # The services this row names, best first (ranking_of); whichever route wrote it.
    ranking: tuple = ()
    # What the policy did in the row's run (ADR-027); None when the file lacks the column or the row holds "-".
    risky_executed: int = None
    policy_refused: int = None

    @property
    def case(self):
        return (self.split, self.recording)

    @property
    def healthy(self):
        return is_healthy(self.truth)


def summary_path(path):
    path = Path(path)
    return path / "summary.tsv" if path.is_dir() else path


def parse_candidates(raw, route, report):
    """The candidates of a rules or hybrid row; None for other rows or a file from before the column."""
    if raw is None or (route != CANDIDATE_ROUTE and report != CANDIDATE_REPORT):
        return None
    return tuple(raw.split(";")) if raw else ()


def parse_count(raw):
    """A policy count of summary.tsv (ADR-027): its number, or None, unknown, for a missing column or a "-"."""
    return int(raw) if raw is not None and raw.isdigit() else None


def ranking_of(status, reported, raw_candidates, raw_ranked=None):
    """The services a row names, best first: reported, ranked, then candidates, each once, and none unless it answered.

    Why not Row.candidates: that one governs candidate recall and is None off the rules and hybrid rows, while a
    ranking has one rule for every route, so a single-answer route ranks its one answer. The dedupe comes before any
    cut at k, as in RCAEval's evaluator: a service repeated with another category does not push the truth down.
    A row that abstains has no ranking even with candidates or ranked, so ranked AC@1 cannot differ from REQ-7's AC@1.
    ranked is the model's own alternatives (ADR-025), so they come before the rules' candidates; a file from before
    the column reads as an empty ranked and ranks as it did.
    """
    if status != "COMPLETED" or service_of(reported) in NO_SERVICE:
        return ()
    answers = [reported] + [answer for raw in (raw_ranked, raw_candidates) if raw for answer in raw.split(";")]
    services = (service_of(answer) for answer in answers)
    return tuple(dict.fromkeys(service for service in services if service not in NO_SERVICE))


def read_summary(path, label=None, service_only=False):
    """Rows of one summary.tsv (a file or the run directory holding it).

    The config is the label when given, else route-mode-view, with -report appended when the file has a report
    column (added after view in service-assessment T2r). A missing view or report column reads as "-", so a file
    from before either column keeps its config name. A trailing candidates column (hybrid T4) is parsed for rules
    and hybrid rows only, so candidate recall applies to them alone. A trailing ranked column (ranked-report T3)
    enters the ranking only, and a file without it ranks as before. The trailing riskyExecuted and policyRefused
    columns (policy-gate T3) are read as numbers; a file without them, or a "-", reads as unknown, never 0. With
    service_only, each row and its candidate recall are scored on the service part of the truth only.
    """
    path = summary_path(path)
    if not path.is_file():
        raise ValueError(f"{path} does not exist: expected a replay summary.tsv or its run directory")
    with path.open(newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        missing = [column for column in REQUIRED if column not in (reader.fieldnames or [])]
        if missing:
            raise ValueError(f"{path} lacks the columns {', '.join(missing)}")
        rows = []
        for raw in reader:
            view = raw.get("view") or "-"
            report = raw.get("report") or "-"
            config = label or f"{raw['route']}-{raw['mode']}-{view}" + (f"-{report}" if "report" in raw else "")
            rows.append(Row(config, raw["fault"], raw["mode"], raw["route"], view, raw["split"], raw["recording"],
                            raw["status"], raw["truth"], raw["reported"],
                            outcome(raw["status"], raw["truth"], raw["reported"], service_only), report,
                            parse_candidates(raw.get("candidates"), raw["route"], report), service_only,
                            ranking_of(raw["status"], raw["reported"], raw.get("candidates"), raw.get("ranked")),
                            parse_count(raw.get("riskyExecuted")), parse_count(raw.get("policyRefused"))))
    return rows


@dataclass(frozen=True)
class FaultScore:
    fault: str
    n: int
    correct: int
    abstained: int
    # Fault rows with no answer at all: not COMPLETED, or no report (ADR-015, "Coverage").
    unanswered: int = 0
    # Fault rows whose truth is among the candidates; None when candidate recall does not apply (ADR-019).
    recalled: int = None

    @property
    def candidate_recall(self):
        return None if self.recalled is None else self.recalled / self.n

    @property
    def ac1(self):
        return self.correct / self.n

    @property
    def ci(self):
        return wilson(self.correct, self.n)

    @property
    def coverage(self):
        return self.answered / self.n

    @property
    def answered(self):
        return self.n - self.abstained - self.unanswered

    @property
    def selective(self):
        return self.correct / self.answered if self.answered else None


@dataclass(frozen=True)
class HealthyScore:
    n: int
    correct: int
    false_alarms: int

    @property
    def false_alarm_rate(self):
        return self.false_alarms / self.n

    @property
    def ci(self):
        return wilson(self.false_alarms, self.n)


@dataclass(frozen=True)
class ConfigScore:
    config: str
    faults: list
    pooled: FaultScore
    healthy: HealthyScore


def _recalled(rows):
    """Fault rows whose truth is a candidate, when every row carries candidates; else None."""
    if any(r.candidates is None for r in rows):
        return None
    return sum(any(names_truth(r.truth, c, r.service_only) for c in r.candidates) for r in rows)


def _fault_score(name, rows):
    return FaultScore(name, len(rows), sum(r.outcome == CORRECT for r in rows),
                      sum(r.outcome == ABSTAIN for r in rows),
                      sum(r.outcome == MISS and (r.status != "COMPLETED" or r.reported in ("", NO_REPORT))
                          for r in rows),
                      _recalled(rows))


def summarize(rows):
    """Per config, in first-seen order: AC@1 per fault and pooled over fault rows, and healthy false alarms."""
    configs = list(dict.fromkeys(r.config for r in rows))
    scores = []
    for config in configs:
        mine = [r for r in rows if r.config == config]
        faulty = [r for r in mine if not r.healthy]
        healthy = [r for r in mine if r.healthy]
        faults = [_fault_score(f, [r for r in faulty if r.fault == f])
                  for f in sorted(dict.fromkeys(r.fault for r in faulty))]
        pooled = _fault_score("pooled", faulty) if faulty else None
        health = HealthyScore(len(healthy), sum(r.outcome == CORRECT for r in healthy),
                              sum(r.outcome == FALSE_ALARM for r in healthy)) if healthy else None
        scores.append(ConfigScore(config, faults, pooled, health))
    return scores


@dataclass(frozen=True)
class PolicyTotals:
    """What the policy did over one config's rows (ADR-027): totals over the rows that know both counts.

    The totals are None when no row knows them, so an old file is never read as a clean one.
    """
    config: str
    rows: int
    known: int
    risky_executed: int
    policy_refused: int
    # Rows of the holdout split, on which the benchmark asserts no risky call ran.
    holdout: int


def summarize_policy(rows):
    """Per config, in first-seen order: the policy's totals, healthy rows included, since every run passes the gate."""
    totals = []
    for config in dict.fromkeys(r.config for r in rows):
        mine = [r for r in rows if r.config == config]
        known = [r for r in mine if r.risky_executed is not None and r.policy_refused is not None]
        totals.append(PolicyTotals(config, len(mine), len(known),
                                   sum(r.risky_executed for r in known) if known else None,
                                   sum(r.policy_refused for r in known) if known else None,
                                   sum(r.split == HOLDOUT_SPLIT for r in mine)))
    return totals


@dataclass(frozen=True)
class RankedScore:
    """AC@k on the service over fault rows; hits[k - 1] is the rows whose truth is in the ranking's first k."""

    fault: str
    n: int
    hits: tuple
    # The services named over all the rows: mean_length shows a one-answer route for what it is.
    listed: int

    def ac(self, k):
        return self.hits[k - 1] / self.n

    def ci(self, k):
        return wilson(self.hits[k - 1], self.n)

    @property
    def avg5(self):
        """A point value: a mean of nested proportions on the same cases, so a Wilson interval does not apply."""
        return sum(self.hits) / (RANK_DEPTH * self.n)

    @property
    def mean_length(self):
        return self.listed / self.n


@dataclass(frozen=True)
class RankedConfig:
    config: str
    faults: list
    pooled: RankedScore


def _ranked_score(name, rows):
    def hit(row, k):
        return service_of(row.truth) in row.ranking[:k]

    return RankedScore(name, len(rows), tuple(sum(hit(r, k) for r in rows) for k in range(1, RANK_DEPTH + 1)),
                       sum(len(r.ranking) for r in rows))


def summarize_ranked(rows):
    """Per config, in first-seen order: AC@1 to AC@5 per fault and pooled, on fault rows as in summarize."""
    scores = []
    for config in dict.fromkeys(r.config for r in rows):
        faulty = [r for r in rows if r.config == config and not r.healthy]
        faults = [_ranked_score(f, [r for r in faulty if r.fault == f])
                  for f in sorted(dict.fromkeys(r.fault for r in faulty))]
        scores.append(RankedConfig(config, faults, _ranked_score("pooled", faulty) if faulty else None))
    return scores


def chance_ac(k, n):
    """AC@k of a uniformly random list of the n services: the list holds min(k, n) of them."""
    return min(k, n) / n


def chance_avg5(n):
    return sum(chance_ac(k, n) for k in range(1, RANK_DEPTH + 1)) / RANK_DEPTH


def read_service_count(path):
    """The number of services of a topology file: chance's n."""
    try:
        with Path(path).open(encoding="utf-8") as handle:
            services = json.load(handle)
    except OSError as error:
        raise ValueError(f"cannot read the topology {path}: {error.strerror}") from None
    except ValueError as error:
        raise ValueError(f"the topology {path} is not JSON: {error}") from None
    services = services.get("services") if isinstance(services, dict) else None
    if not isinstance(services, list) or not services or not all(isinstance(s, str) and s for s in services):
        raise ValueError(f"the topology {path} needs a non-empty services list of names")
    if len(set(services)) != len(services):
        raise ValueError(f"the topology {path} lists a service twice")
    return len(services)


@dataclass(frozen=True)
class Paired:
    first: str
    second: str
    cases: int
    only_first: int
    only_second: int
    mcnemar_p: object
    diff: float
    boot_low: float
    boot_high: float


def _by_case(rows):
    cases = {}
    for r in rows:
        if not r.healthy:
            cases.setdefault(r.case, []).append(1 if r.outcome == CORRECT else 0)
    return cases


def paired(first_rows, second_rows, seed, iterations=10000):
    """Paired AC@1 difference, second minus first, on the fault cases both configs ran.

    Exact McNemar needs one outcome per case and config, so it is None when any case has
    repetitions; the cluster bootstrap always runs, with the case as the unit.
    """
    first, second = _by_case(first_rows), _by_case(second_rows)
    common = sorted(set(first) & set(second))
    if not common:
        raise ValueError("the two configs share no fault case")
    cases = {key: (first[key], second[key]) for key in common}
    single = all(len(a) == 1 and len(b) == 1 for a, b in cases.values())
    # Discordant counts exist only with one outcome per case and config; with repetitions they are None.
    only_first = only_second = p = None
    if single:
        only_first = sum(1 for a, b in cases.values() if a[0] == 1 and b[0] == 0)
        only_second = sum(1 for a, b in cases.values() if a[0] == 0 and b[0] == 1)
        p = mcnemar_exact(only_second, only_first)
    diff, low, high = cluster_bootstrap_diff(cases, seed=seed, iterations=iterations)
    name = lambda rows: rows[0].config
    return Paired(name(first_rows), name(second_rows), len(common), only_first, only_second, p, diff, low, high)
