#!/usr/bin/env bash
# Replays the recorded faults through the real agent loop and scores every sample (odd/tasks/replay-harness.md T4,
# odd/tasks/decision-instrument.md T3, odd/tasks/service-assessment.md T1 and T2r, odd/tasks/rcaeval-converter.md T5,
# odd/tasks/resource-facts.md T5).
# Run with --help.
set -euo pipefail

usage() {
  cat <<'EOF'
Replays recorded faults through the agent loop, without the lab (odd/tasks/replay-harness.md, agent spec "Replay of
recorded tool outputs").

  infra/scripts/replay-agent.sh                       # one dossier sample of F1, F2 and F3, cloud route
  infra/scripts/replay-agent.sh --samples 5 --faults F1,F3
  infra/scripts/replay-agent.sh --route local         # the local model on Ollama, 127.0.0.1:11434
  infra/scripts/replay-agent.sh --mode decision       # each dev recording once: the dossier, then one report call
  infra/scripts/replay-agent.sh --mode decision --split holdout --repeats 3
  infra/scripts/replay-agent.sh --route rules --mode decision --split holdout-2   # F1-F3 and healthy; read once
  infra/scripts/replay-agent.sh --mode decision --view sheets   # the dossier as one fact sheet per service
  infra/scripts/replay-agent.sh --mode decision --view sheets --report assessed   # each service's role first
  infra/scripts/replay-agent.sh --route local --mode decision --report hybrid   # Config H: the model picks a candidate
  infra/scripts/replay-agent.sh --route rules --mode decision   # Config 0: the rules alone, no model (ADR-016)
  infra/scripts/replay-agent.sh --route rules --mode decision --rules v2   # Config 0R: Config 0 plus R-RES (ADR-021)
  infra/scripts/replay-agent.sh --route local --mode decision --report hybrid --rules v2   # HR: H over 0R's candidates
  infra/scripts/replay-agent.sh --route local --mode decision --model qwen3.5:9b --think on \
    --report-cap 2048 --report-timeout 1800   # another local model, thinking, with its own report cap and deadline
  REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1 infra/scripts/replay-agent.sh --dataset re2-ob --route rules \
    --mode decision   # Config 0 on the RCAEval converter's RE2-OB recordings

  --dataset lab|re2-ob           the recorded system (default lab); re2-ob needs REPLAY_DIR and decision mode
  --route cloud|local|rules      the model's route (default cloud), or rules: Config 0, decision mode only
  --mode MODE                    the loop's mode: dossier (default), explorer, decision, or both (explorer and dossier)
  --samples N                    explorer and dossier: samples per fault and mode, 1 to 999 (default 1)
  --split NAME                   decision: the recordings to replay, dev (default), holdout, stress, probe or
                                 holdout-2
  --repeats M                    decision: runs of each recording, 1 to 999 (default 1)
  --view tables|sheets           decision: how the dossier is written for the model (default tables)
  --report plain|assessed|hybrid decision: which report the model writes (default plain); assessed needs --view
                                 sheets; hybrid (Config H) picks among the rules' candidates, local or cloud only
  --rules v1|v2                  decision, --route rules or --report hybrid: the rules version (default v1), v1
                                 Config 0 (ADR-016), v2 Config 0R (ADR-021)
  --faults F1,F2,F3              the recorded faults to replay (default: the dataset's, F1,F2,F3 for lab and
                                 cpu,mem,disk,delay,loss,socket for re2-ob); lab also takes healthy, the healthy
                                 controls, in decision mode, and on --split holdout-2 the default adds it
  --model NAME                   local: the Ollama model (default granite4.1:8b, application.yaml)
  --think on|off                 local: send Ollama's think field with this value (default: no think field)
  --report-cap N                 local: the report's answer cap, num_predict, 1 to 99999 tokens (default 512)
  --report-timeout SECONDS       local: the report call's deadline, 1 to 2700 s (default 600)

It runs ReplayRunTest (./mvnw test -Preplay) in agent/: the real loop and the route's real model over the recorded
tool outputs in agent/src/test/resources/replay/, each sample scored against the recorded truth. It writes
infra/scripts/out/replay-<UTC time>-<route>/: <fault>-<mode>-<recording>-<n>.json (the incident with its steps),
summary.tsv (one line per sample: verdict, coverage, conflicts, tokens, seconds, then its split, recording, view and
report) and aggregate.tsv (per fault, mode, split, view and report: the distinct recordings, the samples, matches out
of samples, mean coverage, tokens and, on the cloud route, the estimated cost at $0.10 and $0.50 per million prompt and
completion tokens; then the covered samples, coverage at least 0.8, and the matches among them, and the samples whose
dossier was fully recorded). A sample with coverage below 1 had calls no recording holds: read it as such. The view and
the report are a decision sample's, and - for the others.

--route rules is Config 0 (ADR-016; agent spec, "Rules-only diagnosis (Config 0)"): it runs RulesReplayTest instead,
with no Spring context, no model and no database. Each decision case's dossier is gathered from its recording as the
loop gathers it and diagnosed by RulesDiagnosis; its lines are those of a model's run, with route rules, 0 tokens and,
in aggregate.tsv, a cost of 0, so the scorer reads it as one more config. It needs no key, no Ollama and no docker, and
takes decision mode only. Production has no such route: Config 0 is a baseline, never the agent's answer.

A decision run (agent spec, "Decision mode") has one case per recording of the split: each line of recordings.tsv
for a chosen fault, in that split, recorded in decision or dossier mode. Each case replays its own recording alone,
which holds every call the dossier makes, --repeats times: the dossier, then the report call, with no phase and no
drill-down call. Before Maven, the script lists the cases with ReplayCases, the class ReplayRunTest picks them with,
run from its source by the JDK (about a second): an unknown split, a chosen fault with no recording in the split, or a
manifest line the driver refuses stops the run there. The splits: dev to tune on, holdout read once, stress for the
2026-10-19 recordings whose windows hold other faults, probe for faults never tuned on, holdout-2 for the cases of
holdout-2's schedule (ADR-026), recorded after the freeze.

The class healthy (lab-realism-min T7) is the lab's healthy controls: recordings with no fault injected, truth
NONE|NONE (ADR-015). Each is a decision case like a fault's; only a COMPLETED report naming UNKNOWN|UNKNOWN is a
MATCH, and a named cause a MISS, which the evals scorer counts as a false alarm. Only holdout-2 records them, so a
--split holdout-2 run takes healthy by default after F1, F2 and F3, and every other split's default stays F1,F2,F3.
Explorer and dossier mode refuse it: it has no stress recording to pool.

--view sets kintsugi.agent.decision.view for the run (agent spec, "Decision mode"; odd/tasks/service-assessment.md):
tables, the default, is the dossier of dossier mode, by data source; sheets writes the same facts as one sheet per
service, each after the services it calls, with the rows seen only in the previous window apart. --report sets
kintsugi.agent.decision.report: plain, the default, is the report of every mode; assessed first asks for the role of
each service (ORIGIN, AFFECTED or UNAFFECTED), each citing a line of its own sheet, and code checks the roles against
the call graph. assessed with the tables stops the run here, before Maven, since its checks read the sheets' line
owners. hybrid, Config H (ADR-019; odd/tasks/hybrid.md), lists the rules' candidates (RulesDiagnosis.candidates) and
asks the model to pick one or UNKNOWN; it takes either view and is refused on the rules route, which calls no model.
Every line of summary.tsv and aggregate.tsv names the view and the report, so a result is attributable to its
variant.

--rules sets kintsugi.agent.rules (ADR-021, "Selection"; odd/tasks/resource-facts.md): v1, the default, is Config 0;
v2 is Config 0R, Config 0's rules unchanged plus R-RES, which names a service RESOURCE_EXHAUSTION from its resource
facts only where Config 0 abstains, and whose candidates are Config 0's followed by the resource-abnormal services. It
applies to the rules route, whose model column then reads rules-v1 or rules-v2, and to the hybrid report, which lists
that version's candidates (HR is --report hybrid --rules v2; its incidents name the version under rules). Any other
run refuses it, since it would change nothing. The summary.tsv column candidates holds the rules' candidates as service|category pairs in order,
joined by ';': on rules lines those of the dossier the rules diagnosed, on hybrid lines the list the report task gave
the model (empty when it had none); it is empty on every other line. The last column, level, is the stored report's
verification level, L0 to L3 (ADR-028), or - on rules lines and runs with no report.

--model, --think, --report-cap and --report-timeout override the local route for this run only (agent spec, "Routes",
"Answer caps" and "Budgets"; ADR-017; odd/tasks/qwen-dev.md), and are refused on any other route. They reach the agent
as KINTSUGI_AGENT_MODEL_NAME, KINTSUGI_AGENT_MODEL_THINK (true or false), KINTSUGI_AGENT_MODEL_NUM_PREDICT_REPORT and
KINTSUGI_AGENT_MODEL_CALL_TIMEOUT_REPORT; an option not given clears its variable, so the run never takes a value the
shell left, and the agent's default applies. With thinking on, Ollama counts the thinking tokens against the cap, so
set the cap and the deadline from a calibration. The report deadline stays within the incident's 45-minute budget. The
last four columns of aggregate.tsv, and the four before candidates in summary.tsv, record each sample's model, think
(on, off, or default when no field was sent; off on the cloud route, which disables thinking; - for rules), report cap
and report deadline.

--dataset re2-ob replays the RCAEval converter's RE2-OB recordings (ADR-020; odd/tasks/rcaeval-converter.md;
docs/setup.md, "Replay RCAEval cases"). Their faults are RCAEval's fault types, cpu, mem, disk, delay, loss and socket,
and their truth is <service>|<category>. The recordings stay outside the repository, so REPLAY_DIR must name the
converter's output directory (convert-rcaeval --out); there is no default. The test JVM gets
-Dkintsugi.agent.topology=classpath:topology/re2-ob.json, so the gatherer, the rules and the loop read Online
Boutique's topology; lab runs get classpath:topology/lab.json, the agent's default, whatever KINTSUGI_AGENT_TOPOLOGY
holds. The converter writes decision cases only (dev, or holdout under its plan), so re2-ob takes decision mode only.
RCAEval's AC@1 names the service only: score its runs with python -m kintsugi_evals score --service-only.

Explorer and dossier samples pool the fault's stress recordings, the ones they were measured on, the first one in the
sample's mode served first, so recordings imported into dev, holdout or probe never change them; --split, --repeats,
--view and --report are refused there, as --samples is in decision mode.

EXPLORER REPLAYS ARE NOT VALID MEASUREMENTS: under exact matching the explorer's model-chosen calls are mostly not
recorded (coverage 0.00-0.38 in the T4 smoke check), so its samples measure the replay's gaps, not the agent. Explorer
and both stay available to inspect that, not to score the agent.

COST: on the cloud route every sample is paid model calls. Run it only with the human's go-ahead, and record the cost.
The cloud route needs ANTHROPIC_API_KEY, from the shell or infra/.env; it reaches Maven through the environment only
and is never printed. The local route needs Ollama serving granite4.1:8b, or the --model given. Both need docker (Testcontainers' Postgres).
REPLAY_DIR, when set, names the replay directory instead (used to try the options on a temporary manifest); re2-ob
needs it.
Exit codes: 0 when every sample ran (a MISS is a measurement), 1 when the run failed, 2 on a bad option, a missing
key or a missing model, and in decision mode on a missing recording or a manifest line the driver refuses.
EOF
}

OLLAMA=http://127.0.0.1:11434
MODEL=granite4.1:8b
# The recorded faults and the topology of each dataset: the lab's, and RCAEval RE2-OB's fault types (ADR-020).
LAB_FAULTS=(F1 F2 F3)
# A healthy control (ADR-015): a lab class with no fault, truth NONE|NONE, recorded in the holdout-2 split only
# (ADR-026; lab-realism-min T7). It replays in decision mode, by default only in that split.
HEALTHY=healthy
HEALTHY_SPLIT=holdout-2
RE2_FAULTS=(cpu mem disk delay loss socket)
LAB_TOPOLOGY=classpath:topology/lab.json
RE2_OB_TOPOLOGY=classpath:topology/re2-ob.json
# The most samples one cloud run may make (about $0.005 each on claude-haiku-5-5, ADR-012).
MAX_CLOUD_SAMPLES=60

dataset=lab
route=cloud
mode=dossier
# Unset until given: decision mode refuses --samples, and the other modes refuse --split, --repeats, --view and
# --report; every route but local refuses --model, --think, --report-cap and --report-timeout.
# --faults defaults to the dataset's faults.
unset samples split repeats view report rules model think report_cap report_timeout faults
while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --dataset|--route|--mode|--samples|--split|--repeats|--view|--report|--rules|--faults|--model|--think\
    |--report-cap|--report-timeout)
      [[ $# -ge 2 ]] || { echo "$1 needs a value; see --help" >&2; exit 2; }
      set -- "$1=$2" "${@:3}" ;;
    --dataset=*) dataset=${1#*=}; shift ;;
    --route=*) route=${1#*=}; shift ;;
    --mode=*) mode=${1#*=}; shift ;;
    --samples=*) samples=${1#*=}; shift ;;
    --split=*) split=${1#*=}; shift ;;
    --repeats=*) repeats=${1#*=}; shift ;;
    --view=*) view=${1#*=}; shift ;;
    --report=*) report=${1#*=}; shift ;;
    --rules=*) rules=${1#*=}; shift ;;
    --faults=*) faults=${1#*=}; shift ;;
    --model=*) model=${1#*=}; shift ;;
    --think=*) think=${1#*=}; shift ;;
    --report-cap=*) report_cap=${1#*=}; shift ;;
    --report-timeout=*) report_timeout=${1#*=}; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
case "$dataset" in
  lab) ALL_FAULTS=("${LAB_FAULTS[@]}" "$HEALTHY"); DEFAULT_FAULTS=("${LAB_FAULTS[@]}"); topology=$LAB_TOPOLOGY ;;
  re2-ob) ALL_FAULTS=("${RE2_FAULTS[@]}"); DEFAULT_FAULTS=("${RE2_FAULTS[@]}"); topology=$RE2_OB_TOPOLOGY ;;
  *) echo "Unknown dataset '$dataset'; choose lab or re2-ob" >&2; exit 2 ;;
esac
[[ "$route" == cloud || "$route" == local || "$route" == rules ]] \
  || { echo "Unknown route '$route'; choose cloud, local or rules" >&2; exit 2; }
[[ "$mode" == explorer || "$mode" == dossier || "$mode" == decision || "$mode" == both ]] \
  || { echo "Unknown mode '$mode'; choose explorer, dossier, decision or both" >&2; exit 2; }
[[ "$route" != rules || "$mode" == decision ]] || { echo "--route rules replays decision mode only, not $mode:" \
  "Config 0 diagnoses the gathered dossier and has no explorer or dossier loop" >&2; exit 2; }
[[ "$dataset" == lab || "$mode" == decision ]] || { echo "--dataset $dataset replays decision mode only, not $mode:" \
  "the converter writes one decision case per RCAEval case and no stress recordings" >&2; exit 2; }
# The local route's overrides (odd/tasks/qwen-dev.md T3), checked before anything runs.
for option in model think report_cap report_timeout; do
  [[ -z "${!option+given}" || "$route" == local ]] || { echo "--${option//_/-} applies to --route local only, not" \
    "$route: it overrides the local Ollama model's settings" >&2; exit 2; }
done
[[ -z "${model+given}" || "$model" =~ ^[A-Za-z0-9][A-Za-z0-9._:/-]*$ ]] \
  || { echo "--model needs an Ollama model name such as qwen3.5:9b, not '$model'" >&2; exit 2; }
[[ -z "${think+given}" || "$think" == on || "$think" == off ]] \
  || { echo "Unknown think '$think'; choose on or off" >&2; exit 2; }
[[ -z "${report_cap+given}" || "$report_cap" =~ ^[1-9][0-9]{0,4}$ ]] \
  || { echo "--report-cap needs an integer from 1 to 99999 tokens, not '$report_cap'" >&2; exit 2; }
# The incident's budget, kintsugi.agent.loop.incident-timeout (45 min), bounds every call: a longer deadline would
# never be reached.
[[ -z "${report_timeout+given}" || ( "$report_timeout" =~ ^[1-9][0-9]{0,3}$ && "$report_timeout" -le 2700 ) ]] \
  || { echo "--report-timeout needs seconds from 1 to 2700, the incident's 45-minute budget, not" \
    "'$report_timeout'" >&2; exit 2; }
# At most 3 digits for --samples and --repeats, so the bound on cloud samples below cannot wrap around in bash's 64-bit
# arithmetic.
if [[ "$mode" == decision ]]; then
  [[ -z "${samples+given}" ]] || { echo "--samples does not apply to decision mode, which replays each recording of" \
    "the split once: use --repeats M to run each one M times" >&2; exit 2; }
  # The split is checked with the cases below, by the driver's own rule.
  split=${split-dev}
  repeats=${repeats-1}
  [[ "$repeats" =~ ^[1-9][0-9]{0,2}$ ]] \
    || { echo "--repeats needs an integer from 1 to 999, not '$repeats'" >&2; exit 2; }
  # The agent's own default, DossierView.DEFAULT (test-lib.sh checks that they agree).
  view=${view-tables}
  [[ "$view" == tables || "$view" == sheets ]] || { echo "Unknown view '$view'; choose tables or sheets" >&2; exit 2; }
  # The agent's own default, DecisionReport.DEFAULT (test-lib.sh checks that they agree).
  report=${report-plain}
  [[ "$report" == plain || "$report" == assessed || "$report" == hybrid ]] \
    || { echo "Unknown report '$report'; choose plain, assessed or hybrid" >&2; exit 2; }
  # Config H (ADR-019): the model picks among the rules' candidates, so the rules route, with no model, has no use for it.
  [[ "$report" != hybrid || "$route" != rules ]] || { echo "--report hybrid applies to --route local or cloud only," \
    "not rules: Config H has a model choose among the rules' candidates, and Config 0 calls no model" >&2; exit 2; }
  # The agent's own default, RulesVersion.DEFAULT (test-lib.sh checks that they agree). It is read by the rules route
  # and the hybrid report only (ADR-021), so any other run refuses it rather than ignore it.
  [[ -z "${rules+given}" || "$route" == rules || "$report" == hybrid ]] || { echo "--rules applies to --route rules" \
    "or --report hybrid only: the other reports list no candidate, and the rules choose none" >&2; exit 2; }
  rules=${rules-v1}
  [[ "$rules" == v1 || "$rules" == v2 ]] || { echo "Unknown rules '$rules'; choose v1 or v2" >&2; exit 2; }
  rules_note=$([[ "$route" == rules || "$report" == hybrid ]] && echo ", rules $rules" || true)
  # The agent's own rule (DecisionReport.checkView), checked here so the run stops before Maven and docker.
  [[ "$report" != assessed || "$view" == sheets ]] || { echo "--report assessed needs --view sheets, got $view: the" \
    "assessed report's checks read the service that owns each line of the sheets" >&2; exit 2; }
else
  [[ -z "${split+given}" ]] || { echo "--split applies to decision mode only: explorer and dossier samples pool the" \
    "fault's stress recordings" >&2; exit 2; }
  [[ -z "${repeats+given}" ]] \
    || { echo "--repeats applies to decision mode only: explorer and dossier replays take --samples" >&2; exit 2; }
  [[ -z "${view+given}" ]] || { echo "--view applies to decision mode only: explorer has no dossier, and dossier mode" \
    "writes the tables" >&2; exit 2; }
  [[ -z "${report+given}" ]] || { echo "--report applies to decision mode only: explorer and dossier mode always ask" \
    "for the plain report" >&2; exit 2; }
  [[ -z "${rules+given}" ]] || { echo "--rules applies to decision mode only: the rules route and the hybrid report" \
    "are decision mode's" >&2; exit 2; }
  samples=${samples-1}
  [[ "$samples" =~ ^[1-9][0-9]{0,2}$ ]] \
    || { echo "--samples needs an integer from 1 to 999, not '$samples'" >&2; exit 2; }
fi
# The default faults: the dataset's, and on the lab's holdout-2 split its healthy controls too, so a holdout-2 run
# scores every case of its schedule. No other split holds a healthy row, so none asks for one.
if [[ -z "${faults+given}" ]]; then
  [[ "$dataset" != lab || "$mode" != decision || "$split" != "$HEALTHY_SPLIT" ]] || DEFAULT_FAULTS+=("$HEALTHY")
  faults=$(IFS=,; echo "${DEFAULT_FAULTS[*]}")
fi
IFS=, read -r -a selected <<<"$faults"
(( ${#selected[@]} )) || { echo "--faults needs at least one fault" >&2; exit 2; }
seen=" "
for fault in "${selected[@]}"; do
  [[ " ${ALL_FAULTS[*]} " == *" $fault "* ]] || { echo "Unknown fault '$fault'; choose from ${ALL_FAULTS[*]}" >&2; exit 2; }
  [[ "$seen" != *" $fault "* ]] || { echo "Fault '$fault' is listed twice" >&2; exit 2; }
  seen+="$fault "
  [[ "$fault" != "$HEALTHY" || "$mode" == decision ]] || { echo "--faults $HEALTHY applies to decision mode only: a" \
    "healthy control is a $HEALTHY_SPLIT case, with no stress recording to pool" >&2; exit 2; }
done

# The converted recordings live outside the repository (ADR-020), so an RCAEval run names its directory.
[[ "$dataset" == lab || -n "${REPLAY_DIR:-}" ]] || { echo "--dataset $dataset needs REPLAY_DIR, the converter's" \
  "output directory (convert-rcaeval --out); the recordings stay outside the repository" >&2; exit 2; }

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REPLAY_DIR="${REPLAY_DIR:-$REPO_DIR/agent/src/test/resources/replay}"
MANIFEST="$REPLAY_DIR/recordings.tsv"
[[ -f "$MANIFEST" ]] || { echo "No recordings manifest $MANIFEST" >&2; exit 2; }
# Maven runs in agent/, so the test gets the directory as an absolute path.
REPLAY_DIR=$(cd "$REPLAY_DIR" && pwd)
[[ "$dataset" == lab ]] || info "dataset $dataset: topology $topology, recordings $REPLAY_DIR"

if [[ "$mode" == decision ]]; then
  # The cases, one line each, listed by ReplayCases, the class ReplayRunTest picks them with, run from its source by the
  # JDK's launcher (about a second; no Maven, no docker). Counted here to bound the run. On an unknown split, a chosen
  # fault with no case, or a manifest line the driver refuses, it says why and exits 2, and so does the run, before
  # Maven; any other failure of the listing fails the run.
  java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
  listing=$("$java_bin" "$REPO_DIR/agent/src/test/java/dev/kintsugi/agent/loop/ReplayCases.java" "$REPLAY_DIR" \
    "$split" "$faults") || exit $(( $? == 2 ? 2 : 1 ))
  # A here-string always supplies a line, so an empty listing (no fault to list, which the checks above already
  # refuse) would count as one case: it is not read at all.
  cases=()
  [[ -z "$listing" ]] || mapfile -t cases <<<"$listing"
  recordings=${#cases[@]}
  total=$((recordings * repeats))
  info "decision mode, split $split, view $view, report $report$rules_note: $recordings recording(s) of" \
    "${selected[*]}," \
    "each replayed $repeats time(s): $total samples"
else
  modes=$([[ "$mode" == both ]] && echo 2 || echo 1)
  total=$(( ${#selected[@]} * modes * samples ))
fi

if [[ "$route" == cloud ]]; then
  # The key goes to Maven's environment, never to its command line, and its value is never printed.
  export_cloud_key || { echo "$CLOUD_KEY_MISSING" >&2; exit 2; }
  # A typo in --samples or --repeats must not turn into an unbounded paid run: a larger batch is divided into several
  # runs.
  (( total <= MAX_CLOUD_SAMPLES )) \
    || { echo "$total samples exceed the $MAX_CLOUD_SAMPLES a cloud run may make; divide the batch" >&2; exit 2; }
  echo "COST WARNING: $total samples on the cloud route, each one paid model calls."
  echo "Run this only with the human's go-ahead, and record the cost from aggregate.tsv."
elif [[ "$route" == rules ]]; then
  # Config 0 calls no model: no key, no Ollama, no cost.
  unset ANTHROPIC_API_KEY
else
  # The local route never needs the key: keep it out of the test JVM.
  unset ANTHROPIC_API_KEY
  local_model=${model-$MODEL}
  if ! curl -fsS --max-time "$CURL_MAX_SECONDS" "$OLLAMA/api/tags" 2>/dev/null \
      | grep -qF "\"name\":\"$local_model\""; then
    echo "Ollama on $OLLAMA does not serve $local_model (ollama pull $local_model)" >&2
    exit 2
  fi
fi
# The local route's overrides reach the agent through its environment (ModelRouteProperties, by Spring's relaxed
# binding). Each is set when its option is given and cleared otherwise, so the run never takes a value the shell left
# and the agent's own default, application.yaml's, applies. The summary records the effective values either way.
unset KINTSUGI_AGENT_MODEL_NAME KINTSUGI_AGENT_MODEL_THINK KINTSUGI_AGENT_MODEL_NUM_PREDICT_REPORT \
  KINTSUGI_AGENT_MODEL_CALL_TIMEOUT_REPORT
overrides=""
if [[ -n "${model+given}" ]]; then export KINTSUGI_AGENT_MODEL_NAME=$model; overrides+=" model $model;"; fi
if [[ -n "${think+given}" ]]; then
  export KINTSUGI_AGENT_MODEL_THINK=$([[ "$think" == on ]] && echo true || echo false)
  overrides+=" think $think;"
fi
if [[ -n "${report_cap+given}" ]]; then
  export KINTSUGI_AGENT_MODEL_NUM_PREDICT_REPORT=$report_cap; overrides+=" report cap $report_cap;"
fi
if [[ -n "${report_timeout+given}" ]]; then
  export KINTSUGI_AGENT_MODEL_CALL_TIMEOUT_REPORT=${report_timeout}s; overrides+=" report timeout ${report_timeout}s;"
fi
[[ -z "$overrides" ]] || info "local route overrides:${overrides%;}"

OUT_DIR="$REPO_DIR/infra/scripts/out/replay-$(date -u +%Y%m%dT%H%M%SZ)-$route"
if [[ "$mode" == decision ]]; then
  info "replaying ${selected[*]}; mode: decision; split: $split; repeats: $repeats; view: $view; report: $report;" \
    "rules: $rules; route: $route; out: $OUT_DIR"
  # Always passed, so the run's view, report and rules are the ones asked for, never ones
  # KINTSUGI_AGENT_DECISION_VIEW, KINTSUGI_AGENT_DECISION_REPORT or KINTSUGI_AGENT_RULES leave in the shell.
  options=("-Dreplay.split=$split" "-Dreplay.repeats=$repeats" "-Dkintsugi.agent.decision.view=$view"
    "-Dkintsugi.agent.decision.report=$report" "-Dkintsugi.agent.rules=$rules")
else
  info "replaying ${selected[*]}; mode: $mode; route: $route; samples: $samples; out: $OUT_DIR"
  options=("-Dreplay.samples=$samples")
fi
# Config 0 runs its own entry, with no Spring context, so no context ever selects the rules route.
replay_test=$([[ "$route" == rules ]] && echo RulesReplayTest || echo ReplayRunTest)
status=0
(cd "$REPO_DIR/agent" && ./mvnw -q test -Preplay "-Dtest=$replay_test" \
  "-Dreplay.route=$route" "-Dreplay.mode=$mode" "-Dreplay.faults=$faults" "${options[@]}" \
  "-Dreplay.recordings=$REPLAY_DIR" "-Dreplay.out=$OUT_DIR" "-Dkintsugi.agent.topology=$topology") || status=$?

echo "out: $OUT_DIR"
if [[ -f "$OUT_DIR/summary.tsv" ]]; then
  echo "--- summary.tsv"
  cat "$OUT_DIR/summary.tsv"
fi
if [[ -f "$OUT_DIR/aggregate.tsv" ]]; then
  echo "--- aggregate.tsv"
  cat "$OUT_DIR/aggregate.tsv"
fi
if (( status != 0 )); then
  echo "The replay run failed (exit $status): see agent/target/surefire-reports/" >&2
  exit 1
fi
