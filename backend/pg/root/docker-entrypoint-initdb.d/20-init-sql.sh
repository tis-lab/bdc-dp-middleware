#!/usr/bin/env bash
# Runs the *.sql files from pg/init-sql (bind-mounted at /init-sql) in filename
# order, as role dp in database dp, after 10-dp.sql has created them. Like all
# init scripts, this runs only when the cluster is first created.
set -euo pipefail

# C collation so that the glob sorts by byte value, whatever the locale.
export LC_ALL=C
shopt -s nullglob

for f in /init-sql/*.sql; do
  echo "$0: running $f as dp"
  psql -v ON_ERROR_STOP=1 --no-psqlrc --username dp --dbname dp --file "$f"
done
