#!/usr/bin/env bash
# Rebuilds PostgreSQL and the Elasticsearch index from the harmonized data:
#
#   1. drops and recreates the PostgreSQL database (pg-bootstrap -y), without asking
#   2. creates the enums-as-varchar tables
#   3. loads data/harmonized, every study (pg-load-data)
#   4. asks whether to apply the primary keys (default: no)
#   5. writes the participant documents (pg-export-participants)
#   6. recreates the index from the newest schema/es file (es-recreate-index), which
#      asks first if the index exists, unless --es-yes is given
#   7. loads the documents into the index (es-load-participants --delete-missing)
#
# It stops at the first step that fails. The targets come from the tools' defaults
# and environment variables ($PGHOST, $PGPORT, $PGDATABASE, $PGUSER, $ES_HOST, ...).
#
# Usage: bin/full-reload.sh [options]
#   --es-yes        recreate an existing index without asking
#   --index NAME    the index to recreate and load (default: participant_1)
#   --out-dir DIR   where the participant documents go (default: data/participants)
#   -h, --help      show this help
#
set -euo pipefail
# Keep Bash 3.2 compatible (macOS); no GNU-only tools.

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }
die() { echo "full-reload.sh: $*" >&2; exit 2; }

es_yes=false
index="participant_1"
out_dir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --es-yes) es_yes=true; shift ;;
    --index) index="${2:?--index needs a value}"; shift 2 ;;
    --out-dir) out_dir="${2:?--out-dir needs a value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done
command -v uv >/dev/null 2>&1 || die "uv not found"

# Tools and paths are relative to the project, wherever this is run from. A relative
# --out-dir keeps meaning the folder it was given from.
case "$out_dir" in
  ""|/*) ;;
  *) out_dir="$PWD/$out_dir" ;;
esac
cd -P "$(dirname "$0")/.."
ddl=schema/pg-ddl/bdchm/enums-as-varchar

total=7
number=0
heading() {
  number=$((number + 1))
  printf '\n==> %s/%s %s\n' "$number" "$total" "$1"
}
# Runs a command; a failure stops the script and names the step.
run() {
  if ! "$@"; then
    echo "full-reload.sh: stopped at step $number of $total. Nothing after it was done." >&2
    exit 1
  fi
}

echo "PostgreSQL:    ${PGHOST:-localhost}:${PGPORT:-5432}, database ${PGDATABASE:-dp} (it will be dropped)"
echo "Elasticsearch: ${ES_SCHEME:-http}://${ES_HOST:-localhost}:${ES_PORT:-9200}, index $index"

heading "Drop and recreate the PostgreSQL database"
run uv run pg-bootstrap -y

heading "Create the enums-as-varchar tables"
run uv run python "$ddl/apply_01_tables__enums_as_varchar.py"

heading "Load the harmonized data"
run uv run pg-load-data

heading "Primary keys"
answer=""
if [ -t 0 ]; then
  printf 'Apply the primary keys to the database? [y/N] '
  read -r answer || answer=""
else
  echo "No terminal to ask on, so taking the default: no."
fi
case "$answer" in
  y|Y|yes|YES|Yes) run uv run python "$ddl/apply_02_primary_keys__enums_as_varchar.py" ;;
  *) echo "Skipped the primary keys." ;;
esac

heading "Write the participant documents"
if [ -n "$out_dir" ]; then
  run uv run pg-export-participants --out-dir "$out_dir"
else
  run uv run pg-export-participants
fi

heading "Recreate the Elasticsearch index from the newest schema/es file"
if $es_yes; then
  run uv run es-recreate-index --index "$index" -y
else
  run uv run es-recreate-index --index "$index"
fi

heading "Load the participant documents into the index"
if [ -n "$out_dir" ]; then
  run uv run es-load-participants --index "$index" --delete-missing "$out_dir"
else
  run uv run es-load-participants --index "$index" --delete-missing
fi

printf '\nDone in %ss.\n' "$SECONDS"
