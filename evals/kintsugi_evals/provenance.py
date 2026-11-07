"""The provenance of a holdout read: its holdout name, its plan, the plan's sha256 and the commit (ADR-015).

ADR-015 has a result directory record the holdout name and the plan's path. `score`, `escalate` and `verdict` take
them as --holdout NAME and --plan PATH and append one row to provenance.tsv in --out, so the record is written by the
tool that read the holdout, not by hand. They refuse an input holding a row of a holdout split without both flags.

Why fail closed: a split this module does not know as a non-holdout split (lab dev, stress and probe; RCAEval dev;
BARO's reference over RE2-TT) counts as a holdout, so a new split name cannot slip a holdout read past the record.
A committed result's own input copies (evals/results/<result>/inputs/), tracked by git and unchanged since HEAD, are
exempt: they were read once already, and re-scoring them regenerates that result rather than reading the holdout again.
"""

import csv
import hashlib
import re
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .scoring import summary_path

REPO = Path(__file__).resolve().parents[2]
RESULTS = REPO / "evals" / "results"
NOT_HOLDOUT = ("dev", "stress", "probe", "reference")
HEADER = ("command", "holdout", "plan", "plan_sha256", "git_commit", "written_utc")
COMMIT = re.compile(r"[0-9a-f]{40,64}")


@dataclass(frozen=True)
class Provenance:
    holdout: str
    # The plan's path from the repository root, as committed.
    plan: str
    plan_sha256: str
    git_commit: str


def git_commit(repo=REPO):
    """`git rev-parse HEAD` in `repo`, or `unknown` when git is missing or `repo` is not a checkout."""
    try:
        done = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    commit = done.stdout.strip()
    return commit if done.returncode == 0 and COMMIT.fullmatch(commit) else "unknown"


def resolve(holdout, plan):
    """The Provenance of --holdout and --plan, None when neither is given; refuses one alone or a plan not in the repo."""
    if holdout is None and plan is None:
        return None
    if holdout is None or plan is None:
        raise ValueError("--holdout and --plan go together: a holdout read records both (ADR-015)")
    if not holdout.strip() or any(c in holdout for c in "\t\r\n"):
        raise ValueError(f"--holdout {holdout!r} must be a name on one line")
    path = Path(plan)
    if not path.is_file():
        raise ValueError(f"--plan {plan} does not exist: a holdout read names its committed plan (ADR-015)")
    try:
        relative = path.resolve().relative_to(REPO)
    except ValueError:
        raise ValueError(f"--plan {plan} is outside the repository: the plan is committed under evals/plans/") from None
    return Provenance(holdout, relative.as_posix(), hashlib.sha256(path.read_bytes()).hexdigest(), git_commit())


def committed_copy(path):
    """Whether `path` is an input copy of a committed result, evals/results/<result>/inputs/<label>-summary.tsv, that
    git tracks and that is unchanged since HEAD. Fails closed: when git is missing or cannot tell, it is no copy."""
    resolved = summary_path(path).resolve()
    if resolved.parent.name != "inputs" or resolved.parent.parent.parent != RESULTS:
        return False
    relative = resolved.relative_to(REPO).as_posix()
    for args in (["ls-files", "--error-unmatch", "--", relative], ["diff", "--quiet", "HEAD", "--", relative]):
        try:
            done = subprocess.run(["git", *args], cwd=REPO, capture_output=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            return False
        if done.returncode != 0:
            return False
    return True


def guard(inputs, found):
    """Refuses a holdout read without its provenance: `inputs` are (path, rows); `found` is resolve()'s result.

    The message names the file and the split, never a recording: an RCAEval recording names its case.
    """
    if found is not None:
        return
    for path, rows in inputs:
        splits = sorted({r.split for r in rows} - set(NOT_HOLDOUT))
        if splits and not committed_copy(path):
            raise ValueError(f"{path} holds rows of the split {splits[0]}, a holdout (or a split not known as dev, "
                             f"stress, probe or reference): a holdout read needs --holdout NAME and --plan PATH, "
                             f"recorded in provenance.tsv (ADR-015)")


def write(out, command, found):
    """Appends `command`'s row to out/provenance.tsv, writing the header first; refuses a file with another header.

    One row per command, so escalate and score writing into one result directory each leave their own record.
    """
    path = Path(out) / "provenance.tsv"
    header = "\t".join(HEADER)
    if path.exists():
        with path.open(newline="") as handle:
            first = handle.readline().rstrip("\r\n")
        if first != header:
            raise ValueError(f"{path} does not start with the header {', '.join(HEADER)}; nothing appended")
    written = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with path.open("a", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        if handle.tell() == 0:
            writer.writerow(HEADER)
        writer.writerow([command, found.holdout, found.plan, found.plan_sha256, found.git_commit, written])
