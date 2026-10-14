#!/usr/bin/env bash
# Deletes one of the lab's named volumes, so the next start creates it empty and its service runs its init scripts again.
# Use it when an init script changed after the volume was created (init scripts run only on an empty volume) or when a
# volume's state is broken. Every volume holds lab data only: knowledge-data's runbook index is rebuilt by
# knowledge-indexer at the next start; postgres-data, prometheus-data and loki-data lose their recorded history.
# The containers that use the volume are removed first; the rest of the lab keeps running.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  echo "Usage: $0 <volume> [--yes]"
  echo "  <volume>  a volume key from infra/compose.yaml, for example knowledge-data"
  echo "  --yes     do not ask for confirmation"
}

volume="" assume_yes=false
for arg in "$@"; do
  case "$arg" in
    --yes) assume_yes=true ;;
    -h | --help) usage; exit 0 ;;
    -*) usage >&2; exit 2 ;;
    *) [[ -z "$volume" ]] || { usage >&2; exit 2; }; volume="$arg" ;;
  esac
done
[[ -n "$volume" ]] || { usage >&2; exit 2; }

if ! compose config --volumes | grep -qx "$volume"; then
  echo "Unknown volume '$volume'. The lab's volumes are:" >&2
  compose config --volumes >&2
  exit 2
fi

project=$(compose config | sed -n "s/^name: //p")
full_name="${project}_${volume}"
if ! docker volume inspect "$full_name" >/dev/null 2>&1; then
  echo "Volume $full_name does not exist; nothing to reset."
  exit 0
fi

users=$(docker ps -aq --filter "volume=$full_name")
if ! $assume_yes; then
  # End of input (no terminal, or Ctrl-D) counts as "no".
  read -r -p "Delete volume $full_name and remove the containers that use it? [y/N] " answer || { answer=""; echo; }
  [[ "$answer" == [yY] ]] || { echo "Aborted."; exit 1; }
fi

if [[ -n "$users" ]]; then
  info "removing the containers that use $full_name"
  # shellcheck disable=SC2086 # one id per word
  docker rm -f $users >/dev/null
fi
docker volume rm "$full_name" >/dev/null
echo "Deleted $full_name. Start the lab again (for example: docker compose -f infra/compose.yaml up -d) to recreate it."
