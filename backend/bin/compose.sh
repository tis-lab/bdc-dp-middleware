#!/usr/bin/env bash
# Runs `docker compose` for this project as the invoking user, so files the
# containers write under data/ are owned by (and deletable by) that user.
# Accepts any docker compose command/options, e.g.:
#   bin/compose.sh up -d
#   bin/compose.sh logs -f es
#   bin/compose.sh --profile foo run --rm es bash
set -euo pipefail

export UID
export GID="$(id -g)"

# Project dir is the parent of this bin/ dir. Portable (no `readlink -f`,
# which macOS < 12.3 lacks); keep Bash 3.2 compatible.
project_dir="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

exec docker compose --project-directory "$project_dir" "$@"
