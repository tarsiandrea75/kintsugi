#!/usr/bin/env bash
# Merges several replay runs' summary.tsv files of one config into one, for score, escalate and verdict
# (odd/tasks/m4-gaps.md T1). Run with --help.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: infra/scripts/merge-summaries.sh OUT IN IN...

Writes OUT, one summary.tsv holding IN's shared header line once and then every IN's rows, in the order given. The
holdout plans merge each config's runs with it (repeats and repetitions), since score, escalate and verdict take one
summary per label:

  infra/scripts/merge-summaries.sh $R/merged/D.tsv <D rep2 r1>/summary.tsv <D rep2 r2>/summary.tsv

It fails, writing nothing, when:
  - fewer than two inputs are given (exit 2: one run needs no merge, pass its summary.tsv itself);
  - OUT exists, or its directory does not;
  - an input is missing, unreadable or holds no rows;
  - two inputs' header lines differ;
  - one input is given twice (the same file by its resolved path);
  - a row appears twice across the inputs, byte for byte: the same sample merged twice.

Why not refuse a repeated (split, recording, sample): the plans run D's repeats as separate `--repeats 1` runs, and
each run numbers its samples from 1, so two genuine repeats of a case share that key. Two genuine samples differ at
least in their seconds and tokens; a run merged twice repeats them exactly. A refusal names files and line numbers,
never a row: a holdout recording's name can name its case.

Exit status: 0 on success, 1 on a refused merge, 2 on a usage error.
USAGE
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac
if [[ $# -lt 3 ]]; then
  usage >&2
  exit 2
fi

fail() {
  echo "merge-summaries: $1; nothing written" >&2
  exit 1
}

out=$1
shift
[[ -e "$out" ]] && fail "$out exists: merge-summaries never overwrites"
out_dir=$(dirname "$out")
[[ -d "$out_dir" ]] || fail "$out_dir is not a directory"

declare -A seen=()
header=
for input in "$@"; do
  [[ -f "$input" && -r "$input" ]] || fail "$input is not a readable file"
  resolved=$(realpath -e "$input")
  [[ -z "${seen[$resolved]:-}" ]] || fail "$input is ${seen[$resolved]} again"
  seen[$resolved]=$input
  IFS= read -r first <"$input" || [[ -n "$first" ]] || fail "$input holds no header"
  if [[ -z "$header" ]]; then
    header=$first
    header_file=$input
  elif [[ "$first" != "$header" ]]; then
    fail "$input has another header than $header_file"
  fi
done

# awk reads an operand like name=value as an assignment and - as stdin, so each relative input goes to awk as ./name,
# and stdin is empty. Messages name each input as given, from MERGE_NAMES, one per line in order: every input has a
# header line, so each one starts a file in awk.
operands=()
for input in "$@"; do
  if [[ "$input" == /* ]]; then operands+=("$input"); else operands+=("./$input"); fi
done
names=$(printf '%s\n' "$@")
# Every row once, in input order; the first repeated row or an input with no rows fails the merge. awk runs END
# after an exit too, so END checks the last input only when nothing failed before.
tmp=$(mktemp "$out_dir/.merge-summaries.XXXXXX")
trap 'rm -f "$tmp"' EXIT
if ! problem=$(MERGE_NAMES=$names awk '
  function refuse(message) { print message; failed = 1; exit 1 }
  BEGIN { split(ENVIRON["MERGE_NAMES"], names, "\n") }
  FNR == 1 { if (NR > 1 && !rows) refuse(previous " holds no rows")
             name = names[++file]; rows = 0; previous = name; if (NR == 1) print > out; next }
  $0 in first { refuse(name " line " FNR " repeats " first[$0] " byte for byte: the same sample merged twice") }
  { first[$0] = name " line " FNR; rows++; print > out }
  END { if (!failed && !rows) refuse(previous " holds no rows") }
' out="$tmp" "${operands[@]}" </dev/null); then
  fail "$problem"
fi
mv "$tmp" "$out"
trap - EXIT
echo "merged $(($(wc -l <"$out") - 1)) rows from $# files into $out"
