#!/usr/bin/env bash
# (Re)starts the stack with an empty Elasticsearch, as if data/es were new.
# Kibana's state lives in ES, so it starts fresh too; data/kibana is kept.
#
# Usage: bin/clean-es.sh [-y|--yes] [docker compose up options...]
#   -y, --yes   don't ask for confirmation
#   Any other arguments go to `docker compose up -d --wait`, e.g.:
#     bin/clean-es.sh --build
#
# Keep Bash 3.2 compatible (macOS).
set -euo pipefail

bin_dir="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -P "$bin_dir/.." && pwd)"
data_dir="$project_dir/data/es"

assume_yes=false
up_args=()
for arg in "$@"; do
  case "$arg" in
    -y|--yes) assume_yes=true ;;
    *) up_args+=("$arg") ;;
  esac
done

if ! $assume_yes; then
  if [ ! -t 0 ]; then
    echo "clean-es.sh: no terminal to confirm on; re-run with -y to proceed." >&2
    exit 1
  fi
  size="$(du -sh "$data_dir" 2>/dev/null | cut -f1)"
  echo "This stops the stack and permanently deletes all Elasticsearch data in:"
  echo "  $data_dir (${size:-empty})"
  echo "including Kibana's saved objects (dashboards, data views, settings)."
  read -r -p "Continue? [y/N] " answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *) echo "Aborted."; exit 1 ;;
  esac
fi

"$bin_dir/compose.sh" down

echo "Deleting Elasticsearch data in $data_dir"
mkdir -p "$data_dir"
if ! find "$data_dir" -mindepth 1 ! -path "$data_dir/.gitkeep" -delete; then
  echo "clean-es.sh: could not delete everything in $data_dir." >&2
  echo "If files are owned by another user, fix with: sudo chown -R $(id -u):$(id -g) \"$data_dir\"" >&2
  exit 1
fi
touch "$data_dir/.gitkeep"

# ${arr[@]+...} avoids an "unbound variable" error for an empty array in Bash 3.2.
exec "$bin_dir/compose.sh" up -d --wait ${up_args[@]+"${up_args[@]}"}
