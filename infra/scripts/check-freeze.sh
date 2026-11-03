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
3. holdout-2: the manifest (agent/src/test/resources/replay/recordings.tsv, at HEAD) holds holdout-2 recordings, each
   with a startedAt later than the tag's commit time, and each recording's seed carries a later time too. No seed is
   recorded anywhere yet, so this condition fails closed and says so.
4. results: no .tsv under evals/results/, in any commit of HEAD's history before the M4 date, holds a row of lab
   holdout-2 or of an RCAEval holdout: holdout A (RE2-OB repetitions 2-3) or holdout B (RE2-SS). A row is found by
   its split (holdout-2; holdout on a recording that is not a lab verify-agent-* one) or by its recording id, which
   is recomputed from the row's fault and truth as the converter blinds it, in every table with a fault, a truth and
   a recording or file column, split column or not. Lab holdout-1 rows (split holdout on a verify-agent-* recording)
   are allowed: they were read once in M2 by plan.
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

# Condition 3: every holdout-2 recording, and its seed, carries a time later than the tag's commit time.
check_holdout2() {
  local tag_time
  if ! tag_time=$(git log -1 --format=%cI "refs/tags/$TAG^{commit}" 2>/dev/null); then
    echo "FAIL  holdout-2: no tag $TAG to date the recordings against"
    return 1
  fi
  python3 -I - "$repo" "$TAG" "$tag_time" "$MANIFEST" "$REPLAY_DIR" "$HOLDOUT2_SPLIT" <<'EOF'
import csv
import json
import subprocess
import sys
from datetime import datetime

repo, tag, tag_time, manifest, replay_dir, split = sys.argv[1:]
tag_at = datetime.fromisoformat(tag_time)
failures = []


def show(path):
    """The file at HEAD, or None when HEAD has none."""
    done = subprocess.run(["git", "-C", repo, "show", "HEAD:" + path], capture_output=True, text=True)
    return done.stdout if done.returncode == 0 else None


def recording_time(path):
    """The recording's start, the incident's startedAt as (time, text), or a text that says why there is none."""
    text = show(path)
    if text is None:
        return "is not committed at HEAD"
    try:
        started = json.loads(text).get("startedAt")
        if not isinstance(started, str):
            return "has no startedAt"
        at = datetime.fromisoformat(started)
        return (at, started) if at.tzinfo else "has a startedAt with no time zone"
    except (ValueError, AttributeError):
        return "has no readable startedAt"


def seed_failure(columns):
    """Why the seeds cannot be shown to postdate the tag. Vision §7 wants each holdout-2 recording's seed to postdate
    the tag, but no seed is recorded anywhere today (no manifest column, no field in the incident), so there is no time
    to compare and the condition fails closed. The holdout-2 recording feature, which records the seed and its time,
    replaces this function with the comparison; until then a seed column is not read either."""
    if "seed" in columns:
        return "%s has a seed column this check does not read yet, so the seeds cannot be shown to postdate %s" % (
            manifest, tag)
    return "no %s seed carries a time (%s has no seed), so the seeds cannot be shown to postdate %s" % (
        split, manifest, tag)


text = show(manifest)
reader = csv.DictReader([] if text is None else text.splitlines(), delimiter="\t")
rows = [row for row in reader if row.get("split") == split]
if text is None:
    failures.append("%s is not committed at HEAD" % manifest)
elif not rows:
    failures.append("%s has no %s recording" % (manifest, split))
for row in rows:
    file = row.get("file") or "?"
    started = recording_time(replay_dir + "/" + file)
    if isinstance(started, str):
        failures.append("%s %s, so it cannot be shown to postdate %s" % (file, started, tag))
    elif started[0] <= tag_at:
        failures.append("%s started %s, not after %s (%s)" % (file, started[1], tag, tag_time.replace("+00:00", "Z")))
failures.append(seed_failure(reader.fieldnames or []))
for failure in failures:
    print("FAIL  holdout-2: " + failure)
if failures:
    sys.exit(1)
print("PASS  holdout-2: %d recording(s) and their seeds postdate %s" % (len(rows), tag))
EOF
}

# Condition 4: no result committed before the M4 date read a holdout other than lab holdout-1.
check_results() {
  python3 -I - "$repo" "$M4_DATE" "$RESULTS_DIR" "$HOLDOUT2_SPLIT" <<'EOF'
import csv
import hashlib
import subprocess
import sys

repo, m4_date, results_dir, holdout2 = sys.argv[1:]


def git(*args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, check=True).stdout


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
    if split == holdout2:
        return "lab " + holdout2
    if split == "holdout" and not recording.startswith("verify-agent-"):
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
    # The recording-id check needs a fault, a truth and a recording or file; the split checks need a split.
    by_id = {"fault", "truth"} <= columns and bool({"recording", "file"} & columns)
    if not by_id and "split" not in columns:
        continue
    rows = list(reader)
    found = {}
    for row in rows:
        if not row.get("recording") and row.get("file"):
            row["recording"] = row["file"].rsplit("/", 1)[-1].removesuffix(".json")
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
