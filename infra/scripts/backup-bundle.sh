#!/usr/bin/env bash
# Writes a full backup of the repository (every branch and tag) as a git bundle plus its sha256 checksum, outside the
# WSL filesystem. The repository is local only (no remote), so this bundle is the only copy that survives the laptop's
# WSL distribution. Run it at the end of every session and whenever a milestone or feature closes.
# The bundle is named kintsugi-YYYY-MM-DD-full.bundle; a second run on the same day replaces that day's bundle.
# The destination is $KINTSUGI_BACKUP_DIR, by default the Windows folder C:\Users\andrea.tarsi\kintsugi-backups.
set -euo pipefail

usage() {
  echo "Usage: $0"
  echo "  KINTSUGI_BACKUP_DIR  destination folder (default: /mnt/c/Users/andrea.tarsi/kintsugi-backups)"
}

case "${1:-}" in
  "") ;;
  -h | --help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

repo_dir="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
backup_dir="${KINTSUGI_BACKUP_DIR:-/mnt/c/Users/andrea.tarsi/kintsugi-backups}"
name="kintsugi-$(date +%F)-full.bundle"

[[ -d "$backup_dir" ]] || { echo "Backup folder '$backup_dir' does not exist." >&2; exit 1; }

if [[ -n "$(git -C "$repo_dir" status --porcelain)" ]]; then
  echo "Warning: the working tree has uncommitted changes; the bundle holds commits only." >&2
fi

# Build in a temporary file next to the target, so a failed run never replaces the previous good bundle.
tmp_bundle="$backup_dir/.$name.tmp"
trap 'rm -f "$tmp_bundle"' EXIT
git -C "$repo_dir" bundle create "$tmp_bundle" --all
git -C "$repo_dir" bundle verify --quiet "$tmp_bundle" >/dev/null

# Every local branch tip must be in the bundle.
bundle_refs="$(git bundle list-heads "$tmp_bundle")"
while read -r sha ref; do
  grep -qx "$sha $ref" <<<"$bundle_refs" || { echo "Ref $ref ($sha) is missing from the bundle." >&2; exit 1; }
done < <(git -C "$repo_dir" for-each-ref --format='%(objectname) %(refname)' refs/heads refs/tags)

mv -f "$tmp_bundle" "$backup_dir/$name"
(cd "$backup_dir" && sha256sum "$name" > "$name.sha256" && sha256sum --quiet -c "$name.sha256")

echo "Backup written: $backup_dir/$name"
echo "Refs: $(wc -l <<<"$bundle_refs"), HEAD: $(git -C "$repo_dir" rev-parse --short HEAD), size: $(du -h "$backup_dir/$name" | cut -f1)"
