#!/usr/bin/env bash
# Tests for holdout2-schedule.py (odd/tasks/lab-realism-min.md T2, ADR-026): determinism, counts, declared values,
# the header, refused seeds and a golden. Needs python3 only; it writes only under a temporary directory. Run:
# infra/scripts/test-holdout2-schedule.sh. Exits non-zero on the first failing case.
set -uo pipefail

schedule="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/holdout2-schedule.py"

cases=0
expect() {
  cases=$((cases + 1))
  if [[ "$2" != "$3" ]]; then
    printf 'FAIL  %s\n  expected: %q\n  actual:   %q\n' "$1" "$3" "$2"
    exit 1
  fi
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Two fixed test seeds; never the real one, which is drawn only after the freeze tag.
seed_a=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
seed_b=ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100
printf '%s\n' "$seed_a" >"$WORK/seed-a.txt"
printf '%s\n' "$seed_b" >"$WORK/seed-b.txt"

# Runs the generator with the arguments given; prints its output, then "exit <status>" on the last line.
run() {
  local output status
  output=$(python3 -I "$schedule" "$@" 2>&1)
  status=$?
  printf '%s\nexit %s\n' "$output" "$status"
}

# Same seed, same bytes: twice to stdout, and once through --out.
python3 -I "$schedule" "$WORK/seed-a.txt" >"$WORK/a1.tsv"
expect "the generator exits 0 on a valid seed" "$?" "0"
python3 -I "$schedule" --seed-file "$WORK/seed-a.txt" >"$WORK/a2.tsv"
python3 -I "$schedule" "$WORK/seed-a.txt" --out "$WORK/a3.tsv"
expect "the same seed gives the same bytes" "$(cmp -s "$WORK/a1.tsv" "$WORK/a2.tsv"; echo $?)" "0"
expect "--out writes the same bytes as stdout" "$(cmp -s "$WORK/a1.tsv" "$WORK/a3.tsv"; echo $?)" "0"
python3 -I "$schedule" "$WORK/seed-b.txt" >"$WORK/b.tsv"
expect "two seeds give two schedules" "$(cmp -s "$WORK/a1.tsv" "$WORK/b.tsv"; echo $?)" "1"

# The header, the cases 1..75 in order, the counts per class, and only declared values.
expect "the header" "$(head -n 1 "$WORK/a1.tsv")" $'case\tclass\tparameter\tvalue'
for file in a1 b; do
  summary=$(python3 -I - "$WORK/$file.tsv" <<'EOF'
import collections, sys
rows = [line.split("\t") for line in open(sys.argv[1]).read().splitlines()[1:]]
allowed = {"F1": ("gateway_latency_ms", {"2000", "5000", "10000", "20000"}),
           "F2": ("gateway_error_status", {"429", "500", "503"}),
           "F3": ("wrong_port", {"9999"}),
           "healthy": ("-", {"-"})}
bad = [r for r in rows if len(r) != 4 or r[1] not in allowed or r[2] != allowed[r[1]][0] or r[3] not in allowed[r[1]][1]]
counts = collections.Counter(r[1] for r in rows)
print("cases=%s" % ("ok" if [r[0] for r in rows] == [str(i) for i in range(1, 76)] else "wrong"))
print("counts=" + " ".join("%s:%d" % (c, counts[c]) for c in ("F1", "F2", "F3", "healthy")))
print("undeclared=%d" % len(bad))
EOF
)
  mapfile -t lines <<<"$summary"
  expect "seed $file: cases are numbered 1..75 in order" "${lines[0]}" "cases=ok"
  expect "seed $file: 20 F1, 20 F2, 20 F3, 15 healthy" "${lines[1]}" "counts=F1:20 F2:20 F3:20 healthy:15"
  expect "seed $file: every value is declared" "${lines[2]}" "undeclared=0"
done

# Other counts are honoured.
out=$(python3 -I "$schedule" "$WORK/seed-a.txt" --f1 1 --f2 0 --f3 2 --healthy 1 | tail -n +2 | cut -f2 | sort | tr '\n' ' ')
expect "--f1 --f2 --f3 --healthy set the counts" "$out" "F1 F3 F3 healthy "

# The golden: the exact first 5 rows for seed_a. A change in the draws, their order or the values changes it.
golden=$'case\tclass\tparameter\tvalue
1\tF2\tgateway_error_status\t429
2\tF1\tgateway_latency_ms\t10000
3\tF2\tgateway_error_status\t500
4\tF3\twrong_port\t9999
5\tF2\tgateway_error_status\t503'
expect "the golden first 5 rows for the test seed" "$(head -n 6 "$WORK/a1.tsv")" "$golden"

# Refused seeds: malformed, short, empty, and a missing file.
printf 'xyz\n' >"$WORK/bad.txt"
printf '%s\n' "${seed_a:0:63}" >"$WORK/short.txt"
: >"$WORK/empty.txt"
for name in bad short empty; do
  out=$(run "$WORK/$name.txt")
  expect "a $name seed exits 2" "$(tail -n 1 <<<"$out")" "exit 2"
  expect "a $name seed is refused in one line" "$(head -n -1 <<<"$out")" \
    "$WORK/$name.txt does not hold a seed of 64 hex characters"
done
out=$(run "$WORK/missing.txt")
expect "a missing seed file exits 2" "$(tail -n 1 <<<"$out")" "exit 2"
expect "a missing seed file is refused in one line" "$(head -n -1 <<<"$out")" "No seed file: $WORK/missing.txt"
out=$(run)
expect "no seed file exits 2" "$(tail -n 1 <<<"$out")" "exit 2"

echo "PASS  $cases cases"
