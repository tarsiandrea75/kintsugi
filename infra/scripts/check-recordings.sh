#!/usr/bin/env bash
# Checks that the windows of verify-agent.sh recordings are clean (odd/tasks/decision-instrument.md, acceptance
# criterion 3). Run with --help.
set -euo pipefail

usage() {
  cat <<'EOF'
Checks that verify-agent.sh recordings have clean windows (odd/tasks/decision-instrument.md), before record-replay.sh
imports them.

  infra/scripts/check-recordings.sh infra/scripts/out/verify-agent-<UTC time> [<run dir>...]

For every <fault>-incident.json and <fault>-r<round>-incident.json in each run directory, it reads the dossier's tool
calls, recorded with their arguments and outputs in the incident's DOSSIER step. The incident window is the last N
minutes (N is the incident's sinceMinutes), the previous window the N minutes before it. It checks:
- the previous window: the orders table shows at least 3 PAID orders and no order in any other status, and no traffic
  row, on any edge, has an outcome other than SUCCESS (a client error);
- the incident window: no service restarted, except once for the fault's own recreate: order-service for F3,
  inventory-service for F4. F1 and F2 restart nothing.
It prints one PASS or FAIL line per incident, with the facts that fail it, then a RESULT line. An incident that is not
JSON, has no DOSSIER step or lacks one of those calls fails. Exits 0 when every incident passes, 1 when one fails, and
2 on a wrong argument: no run directory, a missing one, or one with no incident. Needs python3; it never contacts the
lab.
EOF
}

(( $# )) || { usage >&2; exit 2; }
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done
command -v python3 >/dev/null || { echo "python3 is required to read the incidents" >&2; exit 2; }

# Every run directory is checked for incidents before any is read.
incidents=()
for run_dir in "$@"; do
  [[ -d "$run_dir" ]] || { echo "Not a directory: $run_dir" >&2; exit 2; }
  found=0
  shopt -s nullglob
  for file in "${run_dir%/}"/F*-incident.json; do
    [[ "$(basename "$file")" =~ ^F[0-9]+(-r[0-9]+)?-incident\.json$ ]] || continue
    incidents+=("$file")
    found=$((found + 1))
  done
  shopt -u nullglob
  (( found )) || { echo "No F<n>-incident.json or F<n>-r<k>-incident.json in $run_dir" >&2; exit 2; }
done

# Checks incident file $1 of fault $2 and prints its PASS or FAIL line; fails when the incident fails.
check_incident() {
  python3 -I - "$1" "$2" <<'EOF'
import json
import sys

path, fault = sys.argv[1], sys.argv[2]
# The service a fault recreates as it is injected (Fault.java, COMPOSE_RECREATE): one restart of it in the incident
# window is the fault's own. F1 and F2 change gateway-mock's behavior and restart nothing.
RECREATES = {"F3": "order-service", "F4": "inventory-service"}
MIN_PAID = 3


def done(ok, facts):
    print("%s  %s: %s" % ("PASS" if ok else "FAIL", path, "; ".join(facts)))
    sys.exit(0 if ok else 1)


def number(item, name):
    value = item.get(name)
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


try:
    with open(path, encoding="utf-8") as source:
        incident = json.load(source)
except (OSError, ValueError):
    incident = None
if not isinstance(incident, dict):
    done(False, ["not a JSON object"])
n = incident.get("sinceMinutes")
if not isinstance(n, int) or isinstance(n, bool) or n < 1:
    done(False, ["no sinceMinutes"])
steps = incident.get("steps") if isinstance(incident.get("steps"), list) else []
dossier = next((step for step in steps if isinstance(step, dict) and step.get("kind") == "DOSSIER"), None)
if dossier is None:
    done(False, ["no DOSSIER step"])
try:
    calls = json.loads(dossier.get("toolOutput") or "")
except (TypeError, ValueError):
    calls = None
if not isinstance(calls, list):
    done(False, ["the DOSSIER step holds no tool calls"])


def items(window, tool, **subject):
    """The output items of the dossier's call to tool over the window, with the arguments the dossier gives it
    (DossierGatherer.java): sinceMinutes 2N and untilMinutesAgo N for the previous window, sinceMinutes N alone for
    the incident one. A text that says why when there are none."""
    since, until = (2 * n, n) if window == "previous" else (n, None)
    what = tool + "".join(" for %s" % value for value in subject.values())
    for call in calls:
        if not isinstance(call, dict) or call.get("tool") != tool or not isinstance(call.get("args"), dict):
            continue
        args = call["args"]
        if args.get("sinceMinutes") != since or args.get("untilMinutesAgo") != until:
            continue
        if any(args.get(name) != value for name, value in subject.items()):
            continue
        if call.get("outcome") != "OK":
            return "%s answered %s" % (what, call.get("outcome"))
        try:
            output = json.loads(call.get("output") or "")
        except (TypeError, ValueError):
            output = None
        found = output.get("items") if isinstance(output, dict) else None
        if not isinstance(found, list):
            return "%s output unreadable" % what
        return [item for item in found if isinstance(item, dict)]
    return "no %s call" % what


def row(item):
    """A traffic row: side, edge, request, status, outcome, exception (when there is one) and count."""
    edge = str(item.get("service"))
    if item.get("side") == "client":
        edge += " -> " + str(item.get("clientName"))
    parts = [str(item.get("side")), edge, str(item.get("method")), str(item.get("uri")), str(item.get("status")),
             str(item.get("outcome"))]
    if item.get("exception") not in (None, "", "none"):
        parts.append(str(item.get("exception")))
    return " ".join(parts) + " x%d" % number(item, "count")


facts = []
paid = 0
orders = items("previous", "status_summary", table="orders")
if isinstance(orders, str):
    facts.append("previous window: " + orders)
else:
    paid = sum(number(item, "count") for item in orders if item.get("status") == "PAID")
    others = ["%s %d" % (item.get("status"), number(item, "count")) for item in orders
              if item.get("status") != "PAID" and number(item, "count") > 0]
    if paid < MIN_PAID:
        facts.append("previous window: %d PAID orders, at least %d needed" % (paid, MIN_PAID))
    if others:
        facts.append("previous window: orders not PAID: " + ", ".join(others))

traffic = items("previous", "traffic_counts")
if isinstance(traffic, str):
    facts.append("previous window: " + traffic)
else:
    errors = [row(item) for item in traffic if item.get("outcome") != "SUCCESS" and number(item, "count") > 0]
    if errors:
        facts.append("previous window: client errors: " + ", ".join(errors))

own = RECREATES.get(fault)
recreated = False
restarts = items("incident", "service_restarts")
if isinstance(restarts, str):
    facts.append("incident window: " + restarts)
else:
    others = []
    for item in restarts:
        count = number(item, "restartsInWindow")
        if item.get("service") == own and count == 1:
            recreated = True
        elif count > 0:
            others.append("%s %d" % (item.get("service"), count))
    if others:
        allowed = "%s recreates %s once" % (fault, own) if own else "%s restarts nothing" % fault
        facts.append("incident window: restarts besides the fault's own (%s): %s" % (allowed, ", ".join(others)))

if facts:
    done(False, facts)
restarted = "%s restarted once, %s's own recreate" % (own, fault) if recreated else "no restart"
done(True, ["previous window: %d PAID orders, no other state, no client error" % paid, "incident window: " + restarted])
EOF
}

failed=0
for file in "${incidents[@]}"; do
  name=$(basename "$file")
  check_incident "$file" "${name%%-*}" || failed=$((failed + 1))
done
if (( failed )); then
  echo "RESULT: FAIL ($failed of ${#incidents[@]} recording(s) failed)"
  exit 1
fi
echo "RESULT: PASS (${#incidents[@]} recording(s) clean)"
