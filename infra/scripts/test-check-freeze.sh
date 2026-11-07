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

# A fixed test seed, never the real one, and the generator condition 3 reruns (as of the tag).
seed_hex=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
generator="$real_repo/infra/scripts/holdout2-schedule.py"
holdout2_file=F1/verify-agent-20261202T100000Z-r1-F1.json

# Appends to repository $1's manifest one holdout-2 row for file $2 naming case $3, and writes that recording, started
# at $4. Commits nothing.
add_holdout2_row() {
  printf 'F1\tdecision\tcloud\tgateway-mock|DEPENDENCY_LATENCY\t%s\tid-2\tholdout-2\t%s\n' "$2" "$3" \
    >>"$1/agent/src/test/resources/replay/recordings.tsv"
  echo "{\"startedAt\":\"$4\"}" >"$1/agent/src/test/resources/replay/$2"
}

# Builds a repository at $1 for condition 3 (ADR-026): frozen sources, the generator (unless $no_tag_generator is set)
# and a manifest with a case column, committed on 2026-11-20 and tagged freeze-m3; then the seed alone at $2, the
# schedule on 2026-12-01, and holdout-2 recordings committed on 2026-12-02: one started at $3 naming schedule case $4,
# then one started at 2026-12-02T10:00:00Z for each case in $5 (default cases 2-75, so case 1 completes the schedule).
# With $schedule_before_seed set, the same schedule is committed on 2026-11-30, after the tag but before the seed.
make_holdout2_repo() {
  local repo=$1 seed_at=$2 started=$3 case=$4 others=${5-$(seq -s ' ' 2 75)} other
  mkdir -p "$repo/agent/src/main/java" "$repo/agent/src/test/resources/replay/F1" "$repo/infra/scripts" \
    "$repo/infra/holdout2"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.name test
  git -C "$repo" config user.email test@example.invalid
  echo 'class Loop {}' >"$repo/agent/src/main/java/Loop.java"
  [[ -n "${no_tag_generator-}" ]] || cp "$generator" "$repo/infra/scripts/holdout2-schedule.py"
  printf '%s\tcase\n' "$manifest_header" >"$repo/agent/src/test/resources/replay/recordings.tsv"
  commit_at "$repo" 2026-11-20T10:00:00Z "before the freeze"
  git -C "$repo" tag freeze-m3
  if [[ -n "${schedule_before_seed-}" ]]; then
    printf '%s\n' "$seed_hex" >"$WORK/early-seed.txt"
    python3 -I "$generator" "$WORK/early-seed.txt" --out "$repo/infra/holdout2/schedule.tsv"
    commit_at "$repo" 2026-11-30T10:00:00Z "the schedule, before the seed"
  fi
  printf '%s\n' "$seed_hex" >"$repo/infra/holdout2/seed.txt"
  commit_at "$repo" "$seed_at" "the seed"
  if [[ -z "${schedule_before_seed-}" ]]; then
    python3 -I "$generator" "$repo/infra/holdout2/seed.txt" --out "$repo/infra/holdout2/schedule.tsv"
    commit_at "$repo" 2026-12-01T10:00:00Z "the schedule"
  fi
  add_holdout2_row "$repo" "$holdout2_file" "$case" "$started"
  for other in $others; do
    add_holdout2_row "$repo" "F1/h2-$other.json" "$other" 2026-12-02T10:00:00Z
  done
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
expect_line "no committed seed fails condition 3 closed" "$out" \
  "FAIL  holdout-2: infra/holdout2/seed.txt is not committed at HEAD, so no seed can be shown to postdate freeze-m3"
expect_line "no committed schedule fails condition 3" "$out" \
  "FAIL  holdout-2: infra/holdout2/schedule.tsv is not committed at HEAD"
expect_line "holdout-2 recordings in a manifest with no case column fail condition 3 closed" "$out" \
  "FAIL  holdout-2: agent/src/test/resources/replay/recordings.tsv has no case column, so its holdout-2 recordings name no schedule case"
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
  "FAIL  holdout-2: agent/src/test/resources/replay/recordings.tsv has no holdout-2 recording: holdout-2 is not recorded yet"

# AC3 (ADR-026, Decision 4): a seed after the tag, its schedule and a later recording naming a case pass condition 3.
repo="$WORK/h2-pass"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1
out=$(run_check --repo "$repo")
expect_line "a seed after the tag, its schedule and one later recording per case pass condition 3" "$out" \
  "PASS  holdout-2: infra/holdout2/seed.txt descends from and postdates freeze-m3, infra/holdout2/schedule.tsv follows from it, and each of its 75 case(s) is recorded once, after freeze-m3 and the schedule"
echo 'print("a generator changed after the tag")' >>"$repo/infra/scripts/holdout2-schedule.py"
commit_at "$repo" 2026-12-02T13:00:00Z "change the generator"
out=$(run_check --repo "$repo")
expect_no_line "condition 3 reruns the generator as of the tag, not as of HEAD" "$out" "FAIL  holdout-2"
printf '%s\n' "${seed_hex//0/1}" >"$repo/infra/holdout2/seed.txt"
commit_at "$repo" 2026-12-02T14:00:00Z "edit the seed"
first=$(git -C "$repo" log --format=%h --reverse -- infra/holdout2/seed.txt | head -n 1)
out=$(run_check --repo "$repo")
expect_line "a seed edited after its first commit fails condition 3" "$out" \
  "FAIL  holdout-2: infra/holdout2/seed.txt was changed after its first commit $first, last at $(git -C "$repo" rev-parse --short HEAD)"
expect_line "a schedule that does not follow from the seed at HEAD fails condition 3" "$out" \
  "FAIL  holdout-2: infra/holdout2/schedule.tsv differs from infra/scripts/holdout2-schedule.py's output on infra/holdout2/seed.txt, first at line 2"
repo="$WORK/h2-early-seed"
make_holdout2_repo "$repo" 2026-11-19T10:00:00Z 2026-12-02T10:00:00Z 1
out=$(run_check --repo "$repo")
expect_line "a seed committed before the tag fails condition 3" "$out" \
  "FAIL  holdout-2: infra/holdout2/seed.txt was first committed at $(git -C "$repo" log --format=%h -- infra/holdout2/seed.txt) (2026-11-19T10:00:00Z), not after freeze-m3 (2026-11-20T10:00:00Z)"
repo="$WORK/h2-edited-schedule"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1
python3 -I -c 'import sys; lines = open(sys.argv[1]).read().split("\n")
lines[3] = lines[3].rsplit("\t", 1)[0] + "\t2000"
open(sys.argv[1], "w").write("\n".join(lines))' "$repo/infra/holdout2/schedule.tsv"
commit_at "$repo" 2026-12-02T13:00:00Z "edit the schedule"
out=$(run_check --repo "$repo")
expect_line "an edited schedule fails condition 3, naming its first changed line" "$out" \
  "FAIL  holdout-2: infra/holdout2/schedule.tsv differs from infra/scripts/holdout2-schedule.py's output on infra/holdout2/seed.txt, first at line 4"
repo="$WORK/h2-early-recording"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-11-19T10:00:00Z 1
out=$(run_check --repo "$repo")
expect_line "a holdout-2 recording started before the tag fails condition 3 with a seed and schedule" "$out" \
  "FAIL  holdout-2: $holdout2_file started 2026-11-19T10:00:00Z, not after freeze-m3 (2026-11-20T10:00:00Z)"
expect_no_line "a seed after the tag and its schedule pass their part of condition 3" "$out" "FAIL  holdout-2: infra/"
repo="$WORK/h2-no-case"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z ""
out=$(run_check --repo "$repo")
expect_line "a holdout-2 recording naming no case fails condition 3" "$out" \
  "FAIL  holdout-2: $holdout2_file names no schedule case"
repo="$WORK/h2-unknown-case"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 76
out=$(run_check --repo "$repo")
expect_line "a holdout-2 recording naming an unknown case fails condition 3" "$out" \
  "FAIL  holdout-2: $holdout2_file names case 76, which infra/holdout2/schedule.tsv does not hold"

# Seed ancestry (ADR-026, amendment): a seed committed later than the tag, on a branch that does not contain it, fails.
repo="$WORK/h2-seed-off-tag"
mkdir -p "$repo/agent/src/main/java" "$repo/agent/src/test/resources/replay/F1" "$repo/infra/scripts" "$repo/infra/holdout2"
git -C "$repo" init -q -b main
git -C "$repo" config user.name test
git -C "$repo" config user.email test@example.invalid
echo 'class Loop {}' >"$repo/agent/src/main/java/Loop.java"
cp "$generator" "$repo/infra/scripts/holdout2-schedule.py"
printf '%s\tcase\n' "$manifest_header" >"$repo/agent/src/test/resources/replay/recordings.tsv"
commit_at "$repo" 2026-11-19T10:00:00Z "before the tag"
git -C "$repo" branch side
echo 'notes' >"$repo/notes.txt"
commit_at "$repo" 2026-11-20T10:00:00Z "the tagged commit"
git -C "$repo" tag freeze-m3
git -C "$repo" checkout -q side
printf '%s\n' "$seed_hex" >"$repo/infra/holdout2/seed.txt"
commit_at "$repo" 2026-12-01T09:00:00Z "the seed, off the tag"
seed_commit=$(git -C "$repo" rev-parse --short HEAD)
git -C "$repo" checkout -q main
GIT_AUTHOR_DATE=2026-12-01T09:30:00Z GIT_COMMITTER_DATE=2026-12-01T09:30:00Z git -C "$repo" merge -q --no-ff -m merge side
out=$(run_check --repo "$repo")
expect_line "a seed on a branch that does not contain the tag fails condition 3" "$out" \
  "FAIL  holdout-2: infra/holdout2/seed.txt was first committed at $seed_commit, which does not descend from freeze-m3"
expect_no_line "a seed off the tag but later than it passes the time part" "$out" \
  "FAIL  holdout-2: infra/holdout2/seed.txt was first committed at $seed_commit ("

# Coverage (ADR-026, amendment): every schedule case is recorded exactly once, after the schedule's first commit.
repo="$WORK/h2-missing-few"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1 "$(seq -s ' ' 2 70)"
out=$(run_check --repo "$repo")
expect_line "schedule cases with no recording fail condition 3, named" "$out" \
  "FAIL  holdout-2: 5 of 75 schedule case(s) have no holdout-2 recording: 71, 72, 73, 74, 75"
repo="$WORK/h2-missing-many"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1 ""
out=$(run_check --repo "$repo")
expect_line "many missing cases fail condition 3, naming only the first ones" "$out" \
  "FAIL  holdout-2: 74 of 75 schedule case(s) have no holdout-2 recording: 2, 3, 4, 5, 6, ..."
repo="$WORK/h2-duplicate"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1 "1 $(seq -s ' ' 2 75)"
out=$(run_check --repo "$repo")
expect_line "two recordings of one case fail condition 3, naming both" "$out" \
  "FAIL  holdout-2: case 1 is named by 2 holdout-2 recordings: $holdout2_file, F1/h2-1.json"
expect_no_line "a duplicated case is not also missing" "$out" "FAIL  holdout-2: 1 of 75"
repo="$WORK/h2-before-schedule"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-01T09:30:00Z 1
out=$(run_check --repo "$repo")
expect_line "a recording started before the schedule's first commit fails condition 3" "$out" \
  "FAIL  holdout-2: $holdout2_file started 2026-12-01T09:30:00Z, not after infra/holdout2/schedule.tsv was first committed at $(git -C "$repo" log --format=%h -- infra/holdout2/schedule.tsv) (2026-12-01T10:00:00Z)"

# Order (ADR-026): a schedule first committed before the seed's first commit means the seed was known earlier, so it
# fails even though both postdate the tag and the schedule follows from the seed.
repo="$WORK/h2-schedule-before-seed"
schedule_before_seed=1 make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1
out=$(run_check --repo "$repo")
expect_line "a schedule committed before its seed fails condition 3" "$out" \
  "FAIL  holdout-2: infra/holdout2/schedule.tsv was first committed at $(git -C "$repo" log --format=%h -- infra/holdout2/schedule.tsv), which does not descend from infra/holdout2/seed.txt's first commit $(git -C "$repo" log --format=%h -- infra/holdout2/seed.txt)"
expect_no_line "a schedule committed before its seed still follows from it" "$out" \
  "FAIL  holdout-2: infra/holdout2/schedule.tsv differs"

# A short row: a manifest with a case column, and a holdout-2 row with fewer fields than the header, names no case.
repo="$WORK/h2-short-row"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1
printf 'F1\tdecision\tcloud\tgateway-mock|DEPENDENCY_LATENCY\tF1/h2-short.json\tid-3\tholdout-2\n' \
  >>"$repo/agent/src/test/resources/replay/recordings.tsv"
echo '{"startedAt":"2026-12-02T10:00:00Z"}' >"$repo/agent/src/test/resources/replay/F1/h2-short.json"
commit_at "$repo" 2026-12-02T13:00:00Z "a short row"
out=$(run_check --repo "$repo")
expect_line "a holdout-2 row shorter than the header fails condition 3" "$out" \
  "FAIL  holdout-2: F1/h2-short.json names no schedule case"
expect_no_line "a short row is not reported as a missing case column" "$out" \
  "FAIL  holdout-2: agent/src/test/resources/replay/recordings.tsv has no case column"

# The generator at the tag: absent, or refusing the committed seed.
repo="$WORK/h2-no-generator"
no_tag_generator=1 make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1
out=$(run_check --repo "$repo")
expect_line "a generator absent at the tag fails condition 3" "$out" \
  "FAIL  holdout-2: infra/scripts/holdout2-schedule.py is not committed at freeze-m3, so infra/holdout2/schedule.tsv cannot be regenerated"
repo="$WORK/h2-bad-seed"
make_holdout2_repo "$repo" 2026-12-01T09:00:00Z 2026-12-02T10:00:00Z 1
printf 'xyz\n' >"$repo/infra/holdout2/seed.txt"
commit_at "$repo" 2026-12-02T13:00:00Z "a malformed seed"
out=$(run_check --repo "$repo")
expect_line "a generator that exits non-zero on the committed seed fails condition 3, with its reason" "$out" \
  "FAIL  holdout-2: infra/scripts/holdout2-schedule.py as of freeze-m3 refused infra/holdout2/seed.txt: seed.txt does not hold a seed of 64 hex characters"

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

# Condition 4 finds lab holdout-2 and holdout-1 rows by the manifest at HEAD, not by a split column or a name prefix.
repo="$WORK/manifest-splits"
make_repo "$repo" 2026-12-02T10:00:00Z
mkdir -p "$repo/evals/results/h2-no-split" "$repo/evals/results/h2-by-file" "$repo/evals/results/not-holdout1" \
  "$repo/evals/results/no-recording"
printf 'fault\ttruth\treported\trecording\nF1\tx|y\tx|y\tverify-agent-20261202T100000Z-r1-F1\n' \
  >"$repo/evals/results/h2-no-split/scores.tsv"
printf 'fault\tfile\nF1\tF1/verify-agent-20261202T100000Z-r1-F1.json\n' >"$repo/evals/results/h2-by-file/copy.tsv"
printf '%s\nF1\tx|y\tx|y\tholdout\tverify-agent-20261024T162555Z-r4-F1\n' "$summary_header" \
  >"$repo/evals/results/not-holdout1/A-summary.tsv"
printf 'label\tsource\nF1\tverify-agent-20261202T100000Z-r1-F1\n' >"$repo/evals/results/no-recording/inputs.tsv"
commit_at "$repo" 2026-12-04T10:00:00Z "read holdout-2 with no split column"
commit=$(git -C "$repo" rev-parse --short HEAD)
out=$(run_check --repo "$repo")
expect_line "a holdout-2 recording in a table with no split column fails condition 4" "$out" \
  "FAIL  results: evals/results/h2-no-split/scores.tsv at $commit (2026-12-04): 1 row(s) of lab holdout-2, first verify-agent-20261202T100000Z-r1-F1"
expect_line "a holdout-2 recording named by its file fails condition 4" "$out" \
  "FAIL  results: evals/results/h2-by-file/copy.tsv at $commit (2026-12-04): 1 row(s) of lab holdout-2, first verify-agent-20261202T100000Z-r1-F1"
expect_line "a holdout row on a verify-agent- recording that is not a manifest holdout-1 file fails condition 4" "$out" \
  "FAIL  results: evals/results/not-holdout1/A-summary.tsv at $commit (2026-12-04): 1 row(s) of split holdout on a recording that is not a lab holdout-1 one (an RCAEval holdout), first verify-agent-20261024T162555Z-r4-F1"
expect_no_line "a row on a manifest holdout-1 file still passes condition 4" "$out" \
  "FAIL  results: evals/results/m2/"
expect_no_line "a table with no recording or file column is not read for lab recordings" "$out" \
  "FAIL  results: evals/results/no-recording/"

# Condition 4 fails closed when it cannot read the manifest at HEAD: with no holdout-2 recordings known, it could find none.
git -C "$repo" rm -q agent/src/test/resources/replay/recordings.tsv
commit_at "$repo" 2026-12-04T11:00:00Z "drop the manifest"
out=$(run_check --repo "$repo")
expect_line "a manifest HEAD does not hold fails condition 4 in one line" "$out" \
  "FAIL  results: cannot read agent/src/test/resources/replay/recordings.tsv at HEAD, so no lab holdout row can be found"

# Condition 4 finds holdout TT (RE2-TT, ADR-032) rows by recording id, and exempts them only in BARO's reproduction.
repo="$WORK/holdout-tt"
make_repo "$repo" 2026-12-02T10:00:00Z
tt=$(blinded RE2-TT ts-auth-service_cpu 2)
baro=evals/results/2026-11-03-baro-re2tt-reproduction
mkdir -p "$repo/evals/results/tt-read/inputs" "$repo/$baro/inputs"
printf '%s\ncpu\tts-auth-service|RESOURCE_EXHAUSTION\tts-auth-service|UNKNOWN\treference\t%s\n' \
  "$summary_header" "$tt" >"$repo/evals/results/tt-read/inputs/D-summary.tsv"
cp "$repo/evals/results/tt-read/inputs/D-summary.tsv" "$repo/$baro/inputs/BARO-summary.tsv"
commit_at "$repo" 2026-11-03T10:00:00Z "BARO on RE2-TT, and a TT read elsewhere"
commit=$(git -C "$repo" rev-parse --short HEAD)
out=$(run_check --repo "$repo")
expect_line "an RE2-TT row before M4 fails condition 4" "$out" \
  "FAIL  results: evals/results/tt-read/inputs/D-summary.tsv at $commit (2026-11-03): 1 row(s) of RCAEval holdout TT (RE2-TT repetition 2), first $tt"
expect_no_line "an RE2-TT row in BARO's committed reproduction is exempt (ADR-032)" "$out" "FAIL  results: $baro/"
git -C "$repo" rm -q -r evals/results/tt-read
printf '%s\ncpu\tcheckoutservice|RESOURCE_EXHAUSTION\tcheckoutservice|RESOURCE_EXHAUSTION\tholdout\t%s\n' \
  "$summary_header" "$rep2" >>"$repo/$baro/inputs/BARO-summary.tsv"
commit_at "$repo" 2026-11-04T10:00:00Z "an RE2-OB repetition 2 row in BARO's reproduction"
commit=$(git -C "$repo" rev-parse --short HEAD)
out=$(run_check --repo "$repo")
expect_line "the exemption covers only RE2-TT rows: an RE2-OB repetition 2 row in BARO's reproduction fails" "$out" \
  "FAIL  results: $baro/inputs/BARO-summary.tsv at $commit (2026-11-04): 1 row(s) of RCAEval holdout A (RE2-OB repetition 2), first $rep2"
mkdir -p "$repo/${baro}-copy"
printf '%s\ncpu\tts-auth-service|RESOURCE_EXHAUSTION\tts-auth-service|UNKNOWN\treference\t%s\n' \
  "$summary_header" "$tt" >"$repo/${baro}-copy/BARO-summary.tsv"
commit_at "$repo" 2026-11-05T10:00:00Z "a TT read in a directory that shares the exempt prefix"
commit=$(git -C "$repo" rev-parse --short HEAD)
out=$(run_check --repo "$repo")
expect_line "the exemption covers exactly one directory, not a name that starts like it" "$out" \
  "FAIL  results: ${baro}-copy/BARO-summary.tsv at $commit (2026-11-05): 1 row(s) of RCAEval holdout TT (RE2-TT repetition 2), first $tt"

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
