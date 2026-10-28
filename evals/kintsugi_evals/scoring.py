"""Reads the replay driver's summary.tsv and scores each row (ADR-015, openspec/specs/evals).

The verdict column is not trusted: each row is rescored from status, truth and reported,
so abstentions and healthy cases follow the protocol's rule rather than MATCH/MISS.
"""

import csv
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

REQUIRED = ("fault", "mode", "route", "status", "truth", "reported", "split", "recording")


def is_healthy(truth):
    return truth == HEALTHY_TRUTH


def outcome(status, truth, reported):
    """The outcome of one row: correct, miss, abstain (fault answered UNKNOWN) or false_alarm."""
    completed = status == "COMPLETED"
    has_cause = completed and reported not in ("", NO_REPORT, UNKNOWN_ANSWER)
    if is_healthy(truth):
        if completed and reported == UNKNOWN_ANSWER:
            return CORRECT
        return FALSE_ALARM if has_cause else MISS
    if completed and reported == truth:
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

    @property
    def case(self):
        return (self.split, self.recording)

    @property
    def healthy(self):
        return is_healthy(self.truth)


def summary_path(path):
    path = Path(path)
    return path / "summary.tsv" if path.is_dir() else path


def read_summary(path, label=None):
    """Rows of one summary.tsv (a file or the run directory holding it).

    The config is the label when given, else route-mode-view, with -report appended when the file has a report
    column (added after view in service-assessment T2r). A missing view or report column reads as "-", so a file
    from before either column keeps its config name.
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
                            outcome(raw["status"], raw["truth"], raw["reported"]), report))
    return rows


@dataclass(frozen=True)
class FaultScore:
    fault: str
    n: int
    correct: int
    abstained: int

    @property
    def ac1(self):
        return self.correct / self.n

    @property
    def ci(self):
        return wilson(self.correct, self.n)

    @property
    def coverage(self):
        return (self.n - self.abstained) / self.n

    @property
    def selective(self):
        answered = self.n - self.abstained
        return self.correct / answered if answered else None


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


def _fault_score(name, rows):
    return FaultScore(name, len(rows), sum(r.outcome == CORRECT for r in rows),
                      sum(r.outcome == ABSTAIN for r in rows))


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
