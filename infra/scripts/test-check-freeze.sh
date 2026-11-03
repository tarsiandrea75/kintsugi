#!/usr/bin/env bash
# Tests for check-freeze.sh (odd/tasks/check-freeze.md): on the real repository read-only (AC5), and on throwaway
# git repositories built under a temporary directory (AC1-AC4), never in the real one. Needs git and python3 only; it
# runs no build and no replay. Run: infra/scripts/test-check-freeze.sh. Exits non-zero on the first failing case.
set -uo pipefail

check_freeze="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-freeze.sh"
real_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cases=0
expect() {
  cases=$((cases + 1))
  if [[ "$2" != "$3" ]]; then
    printf 'FAIL  %s\n  expected: %q\n  actual:   %q\n' "$1" "$3" "$2"
    exit 1
  fi
}
# Fails unless line $3 is one of the lines of $2.
expect_line() {
  cases=$((cases + 1))
  if ! grep -qxF -- "$3" <<<"$2"; then
    printf 'FAIL  %s\n  missing line: %s\n  output:\n%s\n' "$1" "$3" "$2"
    exit 1
  fi
}
# Fails when a line of $2 starts with $3.
expect_no_line() {
  cases=$((cases + 1))
  local line
  while IFS= read -r line; do
    if [[ "$line" == "$3"* ]]; then
      printf 'FAIL  %s\n  unexpected line starting: %s\n  output:\n%s\n' "$1" "$3" "$2"
      exit 1
    fi
  done <<<"$2"
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
tab=$'\t'

# Runs check-freeze.sh with the arguments given; prints its output, then "exit <status>" on the last line.
run_check() {
  local output status
  output=$("$check_freeze" "$@" 2>&1)
  status=$?
  printf '%s\nexit %s\n' "$output" "$status"
}

# Commits everything in repository $1 as of time $2 (ISO 8601), with message $3.
commit_at() {
  git -C "$1" add -A
  GIT_AUTHOR_DATE="$2" GIT_COMMITTER_DATE="$2" git -C "$1" commit -q -m "$3"
}

# The RCAEval converter's recording id of system $1 (RE2-OB, RE2-SS), case $2 (<service>_<fault>) and repetition $3,
# computed by the converter's own code (evals/kintsugi_evals/rcaeval/convert.py blinded, stdlib only). Every condition 4
# case below flags a row by this id, so a change in the converter that the script does not follow fails them.
blinded() {
  python3 -I -c 'import sys; sys.path.insert(0, sys.argv[1]); from kintsugi_evals.rcaeval.convert import blinded
print(blinded(sys.argv[2], sys.argv[3], int(sys.argv[4]))[0])' "$real_repo/evals" "$@"
}

summary_header="fault${tab}truth${tab}reported${tab}split${tab}recording"
manifest_header="fault${tab}mode${tab}route${tab}truth${tab}file${tab}incident${tab}split"

# Builds a repository at $1: frozen sources, a lab holdout-1 recording and result, committed on 2026-11-20 and tagged
# freeze-m3; then holdout-2 recordings started at $2 committed on 2026-12-02.
make_repo() {
  local repo=$1 started=$2
  mkdir -p "$repo/agent/src/main/java" "$repo/agent/src/test/resources/replay/F1" "$repo/evals/results/m2/inputs"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.name test
  git -C "$repo" config user.email test@example.invalid
  echo 'class Loop {}' >"$repo/agent/src/main/java/Loop.java"
  printf '%s\nF1\tdecision\tcloud\tgateway-mock|DEPENDENCY_LATENCY\tF1/verify-agent-20261024T162555Z-r2-F1.json\tid-1\tholdout\n' \
    "$manifest_header" >"$repo/agent/src/test/resources/replay/recordings.tsv"
  echo '{"startedAt":"2026-10-24T16:30:00Z"}' >"$repo/agent/src/test/resources/replay/F1/verify-agent-20261024T162555Z-r2-F1.json"
  printf '%s\nF1\tgateway-mock|DEPENDENCY_LATENCY\tgateway-mock|DEPENDENCY_LATENCY\tholdout\tverify-agent-20261024T162555Z-r2-F1\n' \
    "$summary_header" >"$repo/evals/results/m2/inputs/A-summary.tsv"
  commit_at "$repo" 2026-11-20T10:00:00Z "before the freeze"
  git -C "$repo" tag freeze-m3
  printf 'F1\tdecision\tcloud\tgateway-mock|DEPENDENCY_LATENCY\tF1/verify-agent-20261202T100000Z-r1-F1.json\tid-2\tholdout-2\n' \
    >>"$repo/agent/src/test/resources/replay/recordings.tsv"
  echo "{\"startedAt\":\"$started\"}" >"$repo/agent/src/test/resources/replay/F1/verify-agent-20261202T100000Z-r1-F1.json"
  commit_at "$repo" 2026-12-02T12:00:00Z "holdout-2"
}

# The helpers: expect_no_line matches a line's start, not a substring.
(expect_no_line "helper" $'x FAIL  y\nz' "FAIL  y") >/dev/null
expect "expect_no_line ignores a match that does not start a line" "$?" "0"
(expect_no_line "helper" $'x\nFAIL  y z' "FAIL  y") >/dev/null
expect "expect_no_line fails on a line that starts with the text" "$?" "1"

# AC5: a run on the real repository changes nothing in it. Its exit status is not asserted: it changes on the day the
# tag is created.
before=$(git -C "$real_repo" status --porcelain --untracked-files=all)
run_check >/dev/null
after=$(git -C "$real_repo" status --porcelain --untracked-files=all)
expect "a run on the real repository changes nothing in it" "$after" "$before"

# AC1: a repository with no tag fails, naming the missing tag.
repo="$WORK/no-tag"
mkdir -p "$repo/agent/src/main/java"
git -C "$repo" init -q -b main
git -C "$repo" config user.name test
git -C "$repo" config user.email test@example.invalid
echo 'class Loop {}' >"$repo/agent/src/main/java/Loop.java"
commit_at "$repo" 2026-11-20T10:00:00Z "no tag"
out=$(run_check --repo "$repo")
expect_line "a repository with no tag: the missing tag is named" "$out" "FAIL  tag: no tag freeze-m3"
expect_line "a repository with no tag: the run fails" "$out" "exit 1"

# AC2: a tag and no change since it passes condition 2; a change under a frozen path fails it, naming the file.
repo="$WORK/unchanged"
make_repo "$repo" 2026-12-02T10:00:00Z
out=$(run_check --repo "$repo")
expect_line "a tag on HEAD's history passes condition 1" "$out" "PASS  tag: freeze-m3 is $(git -C "$repo" rev-parse --short freeze-m3)"
expect_line "no change under a frozen path passes condition 2" "$out" \
  "PASS  unchanged: agent/src/main/ unchanged since freeze-m3"
echo 'class Loop { int changed; }' >"$repo/agent/src/main/java/Loop.java"
out=$(run_check --repo "$repo")
expect_line "an uncommitted change under a frozen path fails condition 2" "$out" \
  "FAIL  unchanged: agent/src/main/java/Loop.java has uncommitted changes"
commit_at "$repo" 2026-12-03T10:00:00Z "change the loop"
out=$(run_check --repo "$repo")
expect_line "a committed change under a frozen path fails condition 2, naming the file" "$out" \
  "FAIL  unchanged: agent/src/main/java/Loop.java changed since freeze-m3"
expect_line "a failed condition fails the run" "$out" "exit 1"
out=$(run_check --repo "$repo" --tag freeze-other)
expect_line "--tag names another tag" "$out" "FAIL  tag: no tag freeze-other"
expect_line "with no tag, condition 2 cannot be checked" "$out" \
  "FAIL  unchanged: no tag freeze-other to compare agent/src/main/ against"

# AC3: a holdout-2 recording older than the tag fails condition 3; a newer one does not; seeds carry no time.
repo="$WORK/old-holdout2"
make_repo "$repo" 2026-11-19T10:00:00Z
out=$(run_check --repo "$repo")
expect_line "a holdout-2 recording started before the tag fails condition 3" "$out" \
  "FAIL  holdout-2: F1/verify-agent-20261202T100000Z-r1-F1.json started 2026-11-19T10:00:00Z, not after freeze-m3 (2026-11-20T10:00:00Z)"
repo="$WORK/unchanged"
out=$(run_check --repo "$repo")
expect_no_line "a holdout-2 recording started after the tag passes" "$out" "FAIL  holdout-2: F1/"
expect_line "seeds carry no time, so condition 3 fails closed" "$out" \
  "FAIL  holdout-2: no holdout-2 seed carries a time (agent/src/test/resources/replay/recordings.tsv has no seed), so the seeds cannot be shown to postdate freeze-m3"
repo="$WORK/no-holdout2"
mkdir -p "$repo"
git -C "$repo" init -q -b main
git -C "$repo" config user.name test
git -C "$repo" config user.email test@example.invalid
mkdir -p "$repo/agent/src/test/resources/replay"
printf '%s\n' "$manifest_header" >"$repo/agent/src/test/resources/replay/recordings.tsv"
commit_at "$repo" 2026-11-20T10:00:00Z "empty manifest"
git -C "$repo" tag freeze-m3
out=$(run_check --repo "$repo")
expect_line "a manifest with no holdout-2 recording fails condition 3" "$out" \
  "FAIL  holdout-2: agent/src/test/resources/replay/recordings.tsv has no holdout-2 recording"
repo="$WORK/seeded"
make_repo "$repo" 2026-12-02T10:00:00Z
manifest="$repo/agent/src/test/resources/replay/recordings.tsv"
python3 -I -c 'import sys; lines = open(sys.argv[1]).read().splitlines()
lines = [lines[0] + "\tseed"] + [line + "\ts" + str(i) for i, line in enumerate(lines[1:], 1)]
open(sys.argv[1], "w").write("\n".join(lines) + "\n")' "$manifest"
commit_at "$repo" 2026-12-02T13:00:00Z "a seed column"
out=$(run_check --repo "$repo")
expect_line "a seed column the check does not read yet fails condition 3 closed" "$out" \
  "FAIL  holdout-2: agent/src/test/resources/replay/recordings.tsv has a seed column this check does not read yet, so the seeds cannot be shown to postdate freeze-m3"

# AC4: an RE2-OB repetition 2 row committed before the M4 date fails condition 4; lab holdout-1 rows do not.
repo="$WORK/unchanged"
out=$(run_check --repo "$repo")
expect_line "lab holdout-1 rows pass condition 4" "$out" \
  "PASS  results: no result under evals/results/ committed before 2026-12-07 read a holdout"
rep2=$(blinded RE2-OB checkoutservice_cpu 2)
mkdir -p "$repo/evals/results/a-holdout/inputs"
printf '%s\ncpu\tcheckoutservice|RESOURCE_EXHAUSTION\tcheckoutservice|RESOURCE_EXHAUSTION\tholdout\t%s\n' \
  "$summary_header" "$rep2" >"$repo/evals/results/a-holdout/inputs/A-summary.tsv"
commit_at "$repo" 2026-12-04T10:00:00Z "read holdout A"
commit=$(git -C "$repo" rev-parse --short HEAD)
out=$(run_check --repo "$repo")
expect_line "an RE2-OB repetition 2 row before M4 fails condition 4, naming the file and the commit" "$out" \
  "FAIL  results: evals/results/a-holdout/inputs/A-summary.tsv at $commit (2026-12-04): 1 row(s) of RCAEval holdout A (RE2-OB repetition 2), first $rep2"
out=$(run_check --repo "$repo" --m4-date 2026-12-01)
expect_line "a result committed on or after the M4 date is not flagged" "$out" \
  "PASS  results: no result under evals/results/ committed before 2026-12-01 read a holdout"
git -C "$repo" rm -q -r evals/results/a-holdout
commit_at "$repo" 2026-12-05T10:00:00Z "hide the holdout read"
out=$(run_check --repo "$repo")
expect_line "a holdout read deleted later still fails condition 4" "$out" \
  "FAIL  results: evals/results/a-holdout/inputs/A-summary.tsv at $commit (2026-12-04): 1 row(s) of RCAEval holdout A (RE2-OB repetition 2), first $rep2"
repo="$WORK/old-holdout2"
ss=$(blinded RE2-SS carts-db_mem 1)
mkdir -p "$repo/evals/results/mislabelled/inputs"
printf '%s\nmem\tcarts|RESOURCE_EXHAUSTION\tcarts|RESOURCE_EXHAUSTION\tdev\t%s\nF1\tx|y\tx|y\tholdout-2\tverify-agent-20261202T100000Z-r1-F1\n' \
  "$summary_header" "$ss" >"$repo/evals/results/mislabelled/inputs/A-summary.tsv"
commit_at "$repo" 2026-12-04T10:00:00Z "mislabelled"
commit=$(git -C "$repo" rev-parse --short HEAD)
out=$(run_check --repo "$repo")
expect_line "an RE2-SS row is flagged by its recording id even under split dev" "$out" \
  "FAIL  results: evals/results/mislabelled/inputs/A-summary.tsv at $commit (2026-12-04): 1 row(s) of RCAEval holdout B (RE2-SS repetition 1), first $ss"
expect_line "a holdout-2 row before M4 fails condition 4" "$out" \
  "FAIL  results: evals/results/mislabelled/inputs/A-summary.tsv at $commit (2026-12-04): 1 row(s) of lab holdout-2, first verify-agent-20261202T100000Z-r1-F1"
expect_line "the summary counts the failed conditions" "$out" "RESULT: FAIL (2 of 4 conditions failed)"

# Condition 4 reads every committed table: under a path git would quote, and with no split column.
repo="$WORK/fail-open"
make_repo "$repo" 2026-12-02T10:00:00Z
mkdir -p "$repo/evals/results/città/inputs" "$repo/evals/results/no-split/inputs"
printf '%s\ncpu\tcheckoutservice|RESOURCE_EXHAUSTION\tcheckoutservice|RESOURCE_EXHAUSTION\tholdout\t%s\n' \
  "$summary_header" "$rep2" >"$repo/evals/results/città/inputs/A-summary.tsv"
printf 'fault\ttruth\treported\trecording\ncpu\tcheckoutservice|RESOURCE_EXHAUSTION\tcheckoutservice|RESOURCE_EXHAUSTION\t%s\n' \
  "$rep2" >"$repo/evals/results/no-split/inputs/A-summary.tsv"
commit_at "$repo" 2026-12-04T10:00:00Z "read holdout A twice"
commit=$(git -C "$repo" rev-parse --short HEAD)
out=$(run_check --repo "$repo")
expect_line "a holdout read under a non-ASCII directory name fails condition 4" "$out" \
  "FAIL  results: evals/results/città/inputs/A-summary.tsv at $commit (2026-12-04): 1 row(s) of RCAEval holdout A (RE2-OB repetition 2), first $rep2"
expect_line "a holdout read in a table with no split column fails condition 4" "$out" \
  "FAIL  results: evals/results/no-split/inputs/A-summary.tsv at $commit (2026-12-04): 1 row(s) of RCAEval holdout A (RE2-OB repetition 2), first $rep2"

# Options.
out=$(run_check --m4-date 2026-13-01)
expect_line "a wrong --m4-date exits 2" "$out" "exit 2"
out=$(run_check --repo "$WORK/missing")
expect_line "a --repo that is not a git repository exits 2" "$out" "exit 2"
out=$(run_check --bogus)
expect_line "an unknown option exits 2" "$out" "exit 2"
out=$(run_check --help)
expect_line "--help exits 0" "$out" "exit 0"
expect_line "--help states the Python version" "$out" "writes to the repository. Needs git and python3 3.11 or later."
mkdir -p "$WORK/old-python"
printf '#!/usr/bin/env bash\necho "Python 3.9.18"\nexit 1\n' >"$WORK/old-python/python3"
chmod +x "$WORK/old-python/python3"
out=$(PATH="$WORK/old-python:$PATH" run_check --repo "$WORK/unchanged")
expect_line "a python3 older than 3.11 exits 2" "$out" "exit 2"
expect_line "a python3 older than 3.11 is named" "$out" "python3 3.11 or later is required (datetime.fromisoformat reads a trailing Z from 3.11)"

echo "PASS  $cases cases"
