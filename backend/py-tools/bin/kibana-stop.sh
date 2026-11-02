#!/usr/bin/env bash
# Stops and removes a Kibana container started by bin/kibana-start.sh, with its
# anonymous volumes. Kibana's saved objects live in Elasticsearch (.kibana*
# indices) and are kept, so the next start has the same data views and
# dashboards.
#
# Usage: bin/kibana-stop.sh [options]
#   --name NAME   container name (default: $KIBANA_CONTAINER or py-tools-kibana)
#   -h, --help    show this help
#
# Only removes containers that kibana-start.sh started (label py-tools.kibana).
set -euo pipefail
# Keep Bash 3.2 compatible (macOS); no GNU-only tools.

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }
die() { echo "kibana-stop.sh: $*" >&2; exit 2; }

name="${KIBANA_CONTAINER:-py-tools-kibana}"
while [ $# -gt 0 ]; do
  case "$1" in
    --name) name="${2:?--name needs a value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done
command -v docker >/dev/null 2>&1 || die "docker not found"

if ! docker inspect "$name" >/dev/null 2>&1; then
  echo "No container named $name; nothing to do."
  exit 0
fi
ours="$(docker inspect -f '{{index .Config.Labels "py-tools.kibana"}}' "$name")"
[ "$ours" = "true" ] || die "$name wasn't started by kibana-start.sh; not removing it"

echo "Stopping and removing $name"
docker rm -f -v "$name" >/dev/null
echo "Removed. Kibana's saved objects in Elasticsearch are kept."
