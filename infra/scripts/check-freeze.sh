#!/usr/bin/env bash
# Checks the M3 freeze (docs/vision.md §7, odd/tasks/check-freeze.md). Run with --help.
set -euo pipefail

# The freeze, named once. The tag the human creates on 2026-12-01, and the date M4 starts (vision §7).
TAG=freeze-m3
M4_DATE=2026-12-07
# The agent's main sources and resources: the loop, the rules, the prompt and the taxonomy.
FROZEN_PATHS=(agent/src/main/)
# The lab recordings' manifest (record-replay.sh) and its directory; holdout-2 is the split vision §7 records after the
# tag (ADR-015, evals/plans/TEMPLATE.md name it holdout-2).
REPLAY_DIR=agent/src/test/resources/replay
MANIFEST=$REPLAY_DIR/recordings.tsv
HOLDOUT2_SPLIT=holdout-2
# Holdout-2's seed, its schedule and the generator that turns one into the other (ADR-026). The seed and the schedule
# are read at HEAD; the generator at the tag, so a generator changed after the freeze cannot fit a schedule.
HOLDOUT2_SEED=infra/holdout2/seed.txt
HOLDOUT2_SCHEDULE=infra/holdout2/schedule.tsv
HOLDOUT2_GENERATOR=infra/scripts/holdout2-schedule.py
# The committed results condition 4 reads.
RESULTS_DIR=evals/results/

usage() {
  cat <<'EOF'
Checks that the M3 freeze holds (docs/vision.md §7, odd/tasks/check-freeze.md).

  infra/scripts/check-freeze.sh [--tag NAME] [--m4-date YYYY-MM-DD] [--repo DIR]

  --tag NAME           the freeze tag (default freeze-m3)
  --m4-date DATE       the day M4 starts, YYYY-MM-DD, midnight UTC (default 2026-12-07)
  --repo DIR           the repository to check (default: the one holding this script; used by the tests)

It checks four conditions and prints one PASS line per condition that holds and one FAIL line per failure, naming
the file or commit, then a RESULT line:
1. tag: the tag exists.
2. unchanged: nothing under agent/src/main/ (the loop, the rules, the prompt, the taxonomy) changed between the tag
   and HEAD, or has uncommitted or untracked changes.
3. holdout-2 (ADR-026): the seed (infra/holdout2/seed.txt) was first committed in a commit that descends from the tag
   and is later than the tag's commit time, and never changed since; the generator as of the tag
   (infra/scripts/holdout2-schedule.py), run on the seed, gives exactly the committed schedule
   (infra/holdout2/schedule.tsv), whose first commit descends from the seed's first commit (or is it); and the
   manifest (agent/src/test/resources/replay/recordings.tsv) names every schedule case in the case column of exactly one holdout-2 row, whose incident startedAt is later than the tag's
   commit time and than the schedule's first commit. All are read at HEAD. Commit times are written by the committer,
   so this proves the order of the history as written, not that it was written honestly (ADR-026, amendment).
4. results: no .tsv under evals/results/, in any commit of HEAD's history before the M4 date, holds a row of lab
   holdout-2 or of an RCAEval holdout: holdout A (RE2-OB repetitions 2-3) or holdout B (RE2-SS). A row is found by
   its split (holdout-2; holdout on a recording that is not one of the manifest's holdout-1 files at HEAD), by its
   recording (or its file's name with no .json) being one of the manifest's holdout-2 files at HEAD, or by its
   recording id, recomputed from the row's fault and truth as the converter blinds it. Every table with a recording,
   file or split column is read, split column or not. Lab holdout-1 rows (split holdout on one of the manifest's
   holdout-1 files) are allowed: they were read once in M2 by plan.
Exits 0 only when all four hold, 1 when one fails, 2 on a wrong argument or an older python3. Read-only: it never
writes to the repository. Needs git and python3 3.11 or later.
EOF
}

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --tag|--m4-date|--repo)
      [[ $# -ge 2 ]] || { echo "$1 needs a value; see --help" >&2; exit 2; }
      set -- "$1=$2" "${@:3}" ;;
    --tag=*) TAG=${1#*=}; shift ;;
    --m4-date=*) M4_DATE=${1#*=}; shift ;;
    --repo=*) repo=${1#*=}; shift ;;
    *) echo "Unknown argument: $1; see --help" >&2; exit 2 ;;
  esac
done
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
# Condition 3 reads startedAt and git's commit time with datetime.fromisoformat, which reads a trailing Z only from 3.11.
python3 -c 'import sys; sys.exit(sys.version_info < (3, 11))' 2>/dev/null \
  || { echo "python3 3.11 or later is required (datetime.fromisoformat reads a trailing Z from 3.11)" >&2; exit 2; }
[[ -n "$TAG" ]] || { echo "--tag needs a name" >&2; exit 2; }
python3 -c 'import datetime, sys; datetime.date.fromisoformat(sys.argv[1])' "$M4_DATE" 2>/dev/null \
  && [[ "$M4_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
  || { echo "--m4-date needs a date YYYY-MM-DD, not '$M4_DATE'" >&2; exit 2; }
[[ -d "$repo" ]] && repo=$(git -C "$repo" rev-parse --show-toplevel 2>/dev/null) \
  || { echo "Not a git repository: $repo" >&2; exit 2; }
# No git command below may write the index, not even git status's stat refresh.
export GIT_OPTIONAL_LOCKS=0
git() { command git -C "$repo" "$@"; }

# Condition 1: the tag exists.
check_tag() {
  local commit
  if commit=$(git rev-parse --verify --quiet --short "refs/tags/$TAG^{commit}"); then
    echo "PASS  tag: $TAG is $commit"
  else
    echo "FAIL  tag: no tag $TAG"
    return 1
  fi
}

# Condition 2: nothing under the frozen paths changed since the tag, committed or not.
check_unchanged() {
  if ! git rev-parse --verify --quiet "refs/tags/$TAG^{commit}" >/dev/null; then
    echo "FAIL  unchanged: no tag $TAG to compare ${FROZEN_PATHS[*]} against"
    return 1
  fi
  local failed=0 file
  while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    echo "FAIL  unchanged: $file changed since $TAG"
    failed=1
  done < <(git diff --name-only "refs/tags/$TAG" HEAD -- "${FROZEN_PATHS[@]}")
  while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    echo "FAIL  unchanged: ${file:3} has uncommitted changes"
    failed=1
  done < <(git status --porcelain --untracked-files=all -- "${FROZEN_PATHS[@]}")
  (( failed )) && return 1
  echo "PASS  unchanged: ${FROZEN_PATHS[*]} unchanged since $TAG"
}

# Condition 3: the seed descends from and postdates the tag, the schedule follows from the seed and descends from it, and
# every schedule case
# has exactly one holdout-2 recording, which postdates the tag and the schedule (ADR-026, Decision 4 and amendment).
check_holdout2() {
  local tag_time
  if ! tag_time=$(git log -1 --format=%cI "refs/tags/$TAG^{commit}" 2>/dev/null); then
    echo "FAIL  holdout-2: no tag $TAG to date the recordings against"
    return 1
  fi
  python3 -I - "$repo" "$TAG" "$tag_time" "$MANIFEST" "$REPLAY_DIR" "$HOLDOUT2_SPLIT" \
    "$HOLDOUT2_SEED" "$HOLDOUT2_SCHEDULE" "$HOLDOUT2_GENERATOR" <<'EOF'
import csv
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

repo, tag, tag_time, manifest, replay_dir, split, seed, schedule, generator = sys.argv[1:]
# At most this many missing cases are named in one failure.
MISSING_SHOWN = 5


def shown(at):
    """A time, as a datetime or ISO 8601 text, shown in UTC with a trailing Z."""
    at = datetime.fromisoformat(at) if isinstance(at, str) else at
    return at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


tag_at = datetime.fromisoformat(tag_time)
failures = []


def show_bytes(path, rev="HEAD"):
    """The file at rev as bytes, or None when rev has none."""
    done = subprocess.run(["git", "-C", repo, "show", "%s:%s" % (rev, path)], capture_output=True)
    return done.stdout if done.returncode == 0 else None


def show(path):
    """The file at HEAD, or None when HEAD has none."""
    data = show_bytes(path)
    return None if data is None else data.decode("utf-8", errors="replace")


def commits_of(path):
    """HEAD's commits that changed path, newest first, as [short hash, commit time]; git's times are self-asserted."""
    log = subprocess.run(["git", "-C", repo, "log", "--format=%h %cI", "--", path], capture_output=True, text=True,
                         check=True).stdout.split("\n")
    return [line.split() for line in log if line]


def check_seed():
    """The seed's failures: it must be committed at HEAD, first in a commit that descends from the tag and is later
    than it, and never changed since."""
    if show_bytes(seed) is None:
        return ["%s is not committed at HEAD, so no seed can be shown to postdate %s" % (seed, tag)]
    commits = commits_of(seed)
    first, first_time = commits[-1]
    found = []
    ancestry = subprocess.run(["git", "-C", repo, "merge-base", "--is-ancestor", "refs/tags/" + tag, first],
                              capture_output=True).returncode
    if ancestry != 0:
        found.append("%s was first committed at %s, which %s %s" % (
            seed, first, "does not descend from" if ancestry == 1 else "git cannot compare with", tag))
    if datetime.fromisoformat(first_time) <= tag_at:
        found.append("%s was first committed at %s (%s), not after %s (%s)" % (
            seed, first, shown(first_time), tag, shown(tag_at)))
    if len(commits) > 1:
        found.append("%s was changed after its first commit %s, last at %s" % (seed, first, commits[0][0]))
    return found


def check_schedule():
    """The schedule's failures: the tag's generator, run on the seed at HEAD, must give exactly the committed one."""
    committed, seed_bytes = show_bytes(schedule), show_bytes(seed)
    if committed is None:
        return ["%s is not committed at HEAD" % schedule]
    if seed_bytes is None:
        return ["%s cannot be regenerated with no %s" % (schedule, seed)]
    code = show_bytes(generator, "refs/tags/" + tag)
    if code is None:
        return ["%s is not committed at %s, so %s cannot be regenerated" % (generator, tag, schedule)]
    # Run in the temporary directory with relative paths, so the generator's messages name no temporary path.
    with tempfile.TemporaryDirectory() as work:
        for name, data in (("generator.py", code), ("seed.txt", seed_bytes)):
            with open(os.path.join(work, name), "wb") as file:
                file.write(data)
        done = subprocess.run([sys.executable, "-I", "generator.py", "seed.txt"], cwd=work, capture_output=True)
    if done.returncode != 0:
        reason = (done.stderr.decode("utf-8", errors="replace").strip().splitlines() or ["no message"])[-1]
        return ["%s as of %s refused %s: %s" % (generator, tag, seed, reason)]
    if done.stdout != committed:
        ours, theirs = done.stdout.split(b"\n"), committed.split(b"\n")
        line = next((i for i, (a, b) in enumerate(zip(ours, theirs), 1) if a != b), min(len(ours), len(theirs)) + 1)
        return ["%s differs from %s's output on %s, first at line %d" % (schedule, generator, seed, line)]
    return []


def check_order():
    """The order's failure: the schedule's first commit must descend from the seed's first commit (the same commit
    counts), since a schedule committed before its seed means the seed was known earlier."""
    if show_bytes(seed) is None or show_bytes(schedule) is None:
        return []
    seed_first, schedule_first_commit = commits_of(seed)[-1][0], commits_of(schedule)[-1][0]
    ancestry = subprocess.run(["git", "-C", repo, "merge-base", "--is-ancestor", seed_first, schedule_first_commit],
                              capture_output=True).returncode
    if ancestry == 0:
        return []
    return ["%s was first committed at %s, which %s %s's first commit %s" % (
        schedule, schedule_first_commit, "does not descend from" if ancestry == 1 else "git cannot compare with", seed,
        seed_first)]


def schedule_cases():
    """The case numbers the committed schedule holds, as text, in its order."""
    text = show(schedule)
    return [] if text is None else [row.get("case") for row in csv.DictReader(text.splitlines(), delimiter="\t")]


def schedule_first():
    """The schedule's first commit as (short hash, time), or None when HEAD has none."""
    commits = commits_of(schedule) if show_bytes(schedule) is not None else []
    return (commits[-1][0], datetime.fromisoformat(commits[-1][1])) if commits else None


def recording_time(path):
    """The recording's start, the incident's startedAt as a time, or a text that says why there is none."""
    text = show(path)
    if text is None:
        return "is not committed at HEAD"
    try:
        started = json.loads(text).get("startedAt")
        if not isinstance(started, str):
            return "has no startedAt"
        at = datetime.fromisoformat(started)
        return at if at.tzinfo else "has a startedAt with no time zone"
    except (ValueError, AttributeError):
        return "has no readable startedAt"


failures += check_seed() + check_schedule() + check_order()
cases, schedule_at = schedule_cases(), schedule_first()
text = show(manifest)
reader = csv.DictReader([] if text is None else text.splitlines(), delimiter="\t")
rows = [row for row in reader if row.get("split") == split]
has_case = "case" in (reader.fieldnames or [])
if text is None:
    failures.append("%s is not committed at HEAD" % manifest)
elif not rows:
    failures.append("%s has no %s recording: %s is not recorded yet" % (manifest, split, split))
elif not has_case:
    failures.append("%s has no case column, so its %s recordings name no schedule case" % (manifest, split))
recorded = {}
for row in rows:
    file = row.get("file") or "?"
    started = recording_time(replay_dir + "/" + file)
    if isinstance(started, str):
        failures.append("%s %s, so it cannot be shown to postdate %s" % (file, started, tag))
    else:
        if started <= tag_at:
            failures.append("%s started %s, not after %s (%s)" % (file, shown(started), tag, shown(tag_at)))
        if schedule_at and started <= schedule_at[1]:
            failures.append("%s started %s, not after %s was first committed at %s (%s)"
                            % (file, shown(started), schedule, schedule_at[0], shown(schedule_at[1])))
    if not has_case:
        continue
    # A row shorter than the header has no value for case (None), as an empty field has "".
    case = row.get("case")
    if not case:
        failures.append("%s names no schedule case" % file)
    elif case not in cases:
        failures.append("%s names case %s, which %s does not hold" % (file, case, schedule))
    else:
        recorded.setdefault(case, []).append(file)
# Every schedule case is recorded exactly once: a missing case or a second recording of one fails (ADR-026, amendment).
if rows and has_case and cases:
    missing = [case for case in cases if case not in recorded]
    if missing:
        failures.append("%d of %d schedule case(s) have no %s recording: %s" % (
            len(missing), len(cases), split,
            ", ".join(missing[:MISSING_SHOWN] + (["..."] if len(missing) > MISSING_SHOWN else []))))
    for case in cases:
        if len(recorded.get(case, [])) > 1:
            failures.append("case %s is named by %d %s recordings: %s"
                            % (case, len(recorded[case]), split, ", ".join(recorded[case])))
for failure in failures:
    print("FAIL  holdout-2: " + failure)
if failures:
    sys.exit(1)
print("PASS  holdout-2: %s descends from and postdates %s, %s follows from it, and each of its %d case(s) is recorded "
      "once, after %s and the schedule" % (seed, tag, schedule, len(cases), tag))
EOF
}

# Condition 4: no result committed before the M4 date read a holdout other than lab holdout-1.
check_results() {
  python3 -I - "$repo" "$M4_DATE" "$RESULTS_DIR" "$HOLDOUT2_SPLIT" "$MANIFEST" <<'EOF'
import csv
import hashlib
import subprocess
import sys

repo, m4_date, results_dir, holdout2, manifest = sys.argv[1:]


def git(*args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, check=True).stdout


def stem(file):
    """A recording's id as result rows name it: its file name with no directory and no .json."""
    return file.rsplit("/", 1)[-1].removesuffix(".json")


def manifest_recordings():
    """The manifest's recordings at HEAD by split, as {split: {recording id}}.

    A manifest that cannot be read fails the condition: with no holdout-2 recordings known, it could find none.
    """
    done = subprocess.run(["git", "-C", repo, "show", "HEAD:" + manifest], capture_output=True, text=True)
    if done.returncode != 0:
        print("FAIL  results: cannot read %s at HEAD, so no lab holdout row can be found" % manifest)
        sys.exit(1)
    by_split = {}
    for row in csv.DictReader(done.stdout.splitlines(), delimiter="\t"):
        if row.get("file"):
            by_split.setdefault(row.get("split"), set()).add(stem(row["file"]))
    return by_split


recordings = manifest_recordings()
# Lab holdout-2 and holdout-1 rows are found by the manifest at HEAD, not by a split column or a name prefix.
holdout2_recordings, holdout1_recordings = recordings.get(holdout2, set()), recordings.get("holdout", set())


def blinded(system, service, fault, rep):
    """The converter's recording id of RCAEval case <service>_<fault> repetition rep
    (evals/kintsugi_evals/rcaeval/convert.py blinded)."""
    return hashlib.sha256(("%s/%s_%s/%d" % (system, service, fault, rep)).encode()).hexdigest()[:16]


def rcaeval_holdout(row):
    """The RCAEval holdout a row's recording id belongs to, recomputed from its fault and truth service, or None. The
    truth folds a database container into its service (carts-db -> carts), so both names are tried."""
    fault, truth, recording = row.get("fault"), row.get("truth"), row.get("recording")
    if not fault or not truth or not recording:
        return None
    service = truth.split("|", 1)[0]
    for name in (service, service + "-db"):
        for rep in (2, 3):
            if blinded("RE2-OB", name, fault, rep) == recording:
                return "RCAEval holdout A (RE2-OB repetition %d)" % rep
        for rep in (1, 2, 3):
            if blinded("RE2-SS", name, fault, rep) == recording:
                return "RCAEval holdout B (RE2-SS repetition %d)" % rep
    return None


def holdout_of(row):
    """The holdout a result row read, or None for dev, reference and lab holdout-1 rows."""
    found = rcaeval_holdout(row)
    if found:
        return found
    split, recording = row.get("split"), row.get("recording") or ""
    if split == holdout2 or recording in holdout2_recordings:
        return "lab " + holdout2
    if split == "holdout" and recording not in holdout1_recordings:
        return "split holdout on a recording that is not a lab holdout-1 one (an RCAEval holdout)"
    return None


# Every .tsv version committed under results_dir before midnight UTC of the M4 date, oldest first. -z keeps each path
# raw and NUL-terminated (no C-quoting of non-ASCII or special characters); a \x01 starts each commit's header.
log = git("log", "-z", "--reverse", "--before=%sT00:00:00Z" % m4_date, "--diff-filter=AMR", "--name-only",
          "--format=%x01%h %cs", "--", results_dir)
versions, commit = [], None
for field in log.split("\0"):
    field = field.lstrip("\n")
    if field.startswith("\x01"):
        commit = field[1:].split()
    elif field.endswith(".tsv") and commit:
        versions.append((commit[0], commit[1], field))

failures = 0
for short, day, path in versions:
    text = git("show", "%s:%s" % (short, path))
    reader = csv.DictReader(text.splitlines(), delimiter="\t")
    columns = set(reader.fieldnames or [])
    # The recording checks (RCAEval ids, lab recordings) need a recording or file column; the split checks a split.
    if not {"recording", "file", "split"} & columns:
        continue
    rows = list(reader)
    found = {}
    for row in rows:
        if not row.get("recording") and row.get("file"):
            row["recording"] = stem(row["file"])
        holdout = holdout_of(row)
        if holdout:
            found.setdefault(holdout, []).append(row.get("recording") or "?")
    for holdout, recordings in found.items():
        failures += 1
        print("FAIL  results: %s at %s (%s): %d row(s) of %s, first %s"
              % (path, short, day, len(recordings), holdout, recordings[0]))
if failures:
    sys.exit(1)
print("PASS  results: no result under %s committed before %s read a holdout" % (results_dir, m4_date))
EOF
}

failed=0
for condition in check_tag check_unchanged check_holdout2 check_results; do
  "$condition" || failed=$((failed + 1))
done
if (( failed )); then
  echo "RESULT: FAIL ($failed of 4 conditions failed)"
  exit 1
fi
echo "RESULT: PASS (the freeze holds: $TAG, ${FROZEN_PATHS[*]} unchanged, holdout-2 after it, no holdout read before $M4_DATE)"
