# README-DEV.md (py-tools)

Developer notes. Usage is in [README.md](README.md).

## `linkml-pg-ddl` (`src/py_tools/linkml_pg_ddl.py`)

### Structure

- `Generator.build()` turns the schema into a list of `Table` objects (columns,
  primary key, foreign keys, checks, uniques, notes). Join tables are `Table`s
  with `class_name = None`.
- The `render_*()` methods turn those into SQL, one method per output file.
  `write()` deletes the known output files and runner scripts of one mode,
  then writes the ones for that mode.
- `main()` builds a separate `Generator` per entry in `MODES` (the build
  differs: column types, CHECKs, which enums get types) and writes each into
  its own folder. It also deletes the files of the old single-mode layout
  (`<schema>/NN_kind.sql`).
- Names are built raw (`snake()`, then `pg_name()` for length) and quoted only
  when rendered (`q()`). Keep that order: composite names such as
  `cause_of_death__order__ck_...` are built from raw parts and must not contain
  quotes.

### Reading the schema: `linkml-runtime`

The script uses `linkml_runtime.SchemaView` instead of reading the YAML
directly. SchemaView resolves `imports` (including `linkml:types`), `is_a`,
mixins, `slot_usage`, and `attributes`. `class_induced_slots()` gives each
class's effective slots. Without it, cases like `slot_usage` changing `value`
to `decimal`/`boolean`/... in each `QuestionnaireResponseValue*` subclass would
have to be re-implemented. It also makes the script work for schemas other than
bdchm.

`linkml-runtime` is the runtime library only, not the full `linkml` package
with its generators. It works on Python 3.14.

### Why not LinkML's own `gen-sqlddl`

The `linkml` package has a SQL DDL generator, but it doesn't give the output
this tool needs: separate files per kind of DDL, an optional NOT NULL file,
native-enum vs varchar+CHECK modes, and self-describing constraint names and
comments. It also pulls in a much larger dependency set.

### Design decisions and their consequences

**Table per concrete class.** Each non-abstract, non-mixin class gets one table
holding all its induced slots. The other options were joined tables (a table per
class, with subclass rows linked by PK = FK to the parent) and one table per
hierarchy (with a discriminator column). This one gives the simplest queries.
The cost is that a class with subclasses spans several tables, and parent
tables don't contain subclass rows.

**Foreign keys only to single-table classes.** `tables_for_class()` returns the
tables of a class and its concrete descendants. An FK is created only when
there is exactly one. Otherwise `03_foreign_keys__<mode>.sql` gets a comment naming the
possible tables, or the count and range class when there are more than
`MAX_LISTED_TARGETS`, as with references to `Entity`. Options considered and
rejected:

- a global `entity(id, entity_class)` table that every table references
- an FK to the range class's own table, which would reject valid subclass ids

**Multivalued slots.** Class ranges become join tables. Their PK is
`(owner_id, target_id)`, so a list can't hold the same reference twice, and
order isn't kept. Type and enum ranges become array columns (`text[]`,
`<enum>[]`, `varchar[]`). In varchar mode an array's CHECK uses
`col <@ ARRAY[...]::varchar[]`. `minimum_value`, `maximum_value` and `pattern`
are skipped for arrays.

`inlined` / `inlined_as_list` are ignored. In a relational layout an inlined
object is still a row in its class's table, so it's referenced the same way.

**Single-valued references** become `<slot>_id` columns with the type of the
target class's identifier slot.

**Identifiers.** The PK is the class's `identifier` slot, or its `key` slot if
there's none (`get_identifier_slot(use_key=True)`). Classes without either are
rejected in `build()`. Supporting them would need a surrogate key, plus a way
for inlined children to point back at their parent. bdchm doesn't need this.

**Enum values** (`enum_values()`): an enum's values are its own
`permissible_values`, plus those of each enum in `inherits`, plus those from
`include`, minus those in `minus`. If any part is dynamic (`reachable_from`,
`matches`, `concepts`), the whole enum is treated as dynamic. The values live
in an ontology, not the schema, so the column is plain `varchar` in both modes.
An enum with no values at all (bdchm's placeholder `BaseEnum`) is treated the
same way, since `CREATE TYPE ... AS ENUM ()` would accept nothing. In bdchm,
`HistoricalStatusEnum` (inherits `StatusEnum` plus an `include`) resolves to a
static enum. `ConditionConceptEnum` (inherits two `reachable_from` enums) is
dynamic.

Native enum type names are `snake(EnumName)`, so `VitalStatusEnum` becomes
`vital_status_enum`. Enum values are the permissible value text. Their
`meaning` CURIEs are not used.

**NOT NULL in its own file.** `required` slots get `ALTER COLUMN ... SET NOT
NULL` in `05_not_null__<mode>.sql`, so it can be skipped. Primary key columns are left
out (the PK already implies NOT NULL), and so are join tables. A required
multivalued class-range slot can't be enforced at all and gets a `-- note:`
in `01_tables__<mode>.sql`.

**No PostgreSQL schema in the DDL.** Names are unqualified, so the target schema
comes from `search_path` at apply time. Each file is a separate `psql` session
under Docker init, so `search_path` has to be set per session. That's why the
README uses `PGOPTIONS`. A `SET search_path` inside one file would not carry
over to the next.

**Both modes, every time.** Each run writes both sets, `enum-types/` and
`enums-as-varchar/`, so they can't drift apart. Each folder is complete on its
own (02, 03 and 05 are the same SQL in both, apart from the header), so one
folder is all you need to apply.

**File names** are `NN_<kind>__<mode>.sql`: the number gives the apply order,
and the mode suffix keeps a file self-describing when it's copied out of its
folder. There's no schema prefix; the folder above names the schema.

**Runner scripts.** `apply_<file>.py` next to each `.sql` file is a few lines
(`RUNNER_TEMPLATE`) that call `pg_apply_ddl.run_ddl_file()` with the file next
to it, found from `__file__`, so the folder can be moved. They import
`py_tools`, so they must run in this project's environment (`uv run python
...`). Plain `python` fails with an import error. They're generated rather than
listed in `[project.scripts]`, so they work for any schema without editing
`pyproject.toml`.

**Idempotent, additive DDL.** Every statement can be re-run, and running a
file against a schema that has some of the objects creates only what's missing.
This lets `pg-apply-ddl` run each statement on its own and keep going after a
failure, and lets an older schema catch up.

- Tables: `CREATE TABLE IF NOT EXISTS` keeps the full column list, so a new
  table gets the schema's column order. It's followed by `ADD COLUMN IF NOT
  EXISTS` for every column (no-ops on a new table). On an existing table, new
  columns are appended at the end.
- `CREATE TYPE` and `ADD CONSTRAINT` have no `IF NOT EXISTS`, so they're
  wrapped in `DO` blocks (`do_unless_exists()`) that check the catalog first.
  Types are looked up in `current_schema()` only, so a same-named type
  elsewhere on the `search_path` doesn't count. Constraints are looked up by
  name on `to_regclass(<table>)`. If the table is missing, that's NULL, the
  check passes, and the `ALTER TABLE` fails with a clear error.
- Primary keys are skipped if the table has any primary key (`contype = 'p'`),
  since a second one would fail whatever its name.
- Enum types get `ALTER TYPE ... ADD VALUE IF NOT EXISTS` for each value
  (PostgreSQL 12+ allows it in a transaction; a new value can't be used in the
  same transaction, which no generated statement does).
- When a `DO` block skips, it raises `NOTICE '<what> already exists, skipping'`
  with `ERRCODE = 'duplicate_object'` (42710). That matches the NOTICEs of
  PostgreSQL's own `IF NOT EXISTS`, so `pg-apply-ddl` can log both as SKIPPED
  by SQLSTATE, whatever the server's message language.
- The `DO` blocks use the tag `$ddl$` rather than `$$`, so a `pattern` CHECK
  containing `$$` can't end the block.
- Deliberately never done: dropping anything, changing a column's type,
  replacing a constraint whose definition changed. Those can lose data and need
  a real migration.

### Naming issues

- **Reserved words.** The first test against PostgreSQL failed on
  `CauseOfDeath.order` because `order` is reserved. `PG_RESERVED` holds the
  keywords that can't be unquoted table, column or type names, from PostgreSQL
  18:

  ```sql
  SELECT string_agg(word, ' ' ORDER BY word)
  FROM pg_get_keywords() WHERE catcode IN ('R', 'T');
  ```

  Update the list when moving to a newer PostgreSQL major version. Only those
  names are quoted, so the rest of the DDL stays easy to type. Constraint names
  contain `__` and so are never reserved.
- **63-character limit.** `pg_name()` cuts longer names to 54 characters and
  adds `_` plus 8 hex digits of their SHA-1, so names stay unique and the same
  across runs. Constraint names on a long join table then all share the
  shortened prefix and differ only in the hash, which is less descriptive. No
  bdchm name is that long.
- **Join table column collision.** The join table's owner column is
  `<owner_table>_id` and its target column is `<slot>_id`. If they would be
  equal (slot named like the class), the target becomes `<slot>_target_id`.
- `snake()` splits CamelCase and acronyms (`HTTPServer` becomes `http_server`)
  and replaces other characters with `_`.

### Other details

- **Default output folder.** `PROJECT_DIR` comes from `__file__`
  (`src/py_tools/` → project root). That's correct for uv's default editable
  install. A non-editable install would put the default output inside
  site-packages, so pass `--out-root` in that case.
- **Union ranges.** Slots with `any_of` or `exactly_one_of` ranges are stored
  as `text`, with a `-- note:` on the table. bdchm has none.
- **Generated headers** name the schema file and the enum mode, so you can
  tell which mode produced a folder.

### Verifying changes

Regenerate, then apply both modes, twice each, to a throwaway PostgreSQL
(same major version as the backend). Each mode goes into its own schema. Every
file should report `0 failed`. On the second run, everything except `COMMENT
ON` and `SET NOT NULL` should be SKIPPED, which shows that the DDL is idempotent:

```sh
S=$(mktemp -d)
uv run linkml-pg-ddl schema/linkml/bdchm.yaml
docker run -d --rm --name ddl-test -p 127.0.0.1:55432:5432 -e POSTGRES_PASSWORD=x postgres:18.6
until docker exec ddl-test pg_isready -U postgres -q; do sleep 1; done; sleep 2
export PGPORT=55432 PGUSER=postgres PGPASSWORD=x PGDATABASE=ddl_test
for i in 1 2; do
  uv run pg-apply-ddl schema/pg-ddl/bdchm/enum-types/*.sql --schema native --log-dir "$S"
  uv run pg-apply-ddl schema/pg-ddl/bdchm/enums-as-varchar/*.sql --schema varchar --log-dir "$S"
done
uv run python schema/pg-ddl/bdchm/enum-types/apply_01_tables__enum_types.py --schema native --log-dir "$S"
docker stop ddl-test
```

The last line checks a generated runner script.

Also check step-by-step use on dirty data: apply 00 and 01, insert duplicate
ids, a dangling reference and a missing required value, then apply 02, 03 and
05 one at a time. Each should fail only on the affected statements, with the
cause (for example `DETAIL: Key (id)=(p1) is duplicated.`) in the log. Fixing
the data and re-running the step should then succeed. Interrupt a run with
Ctrl-C, and check that the log ends with `INTERRUPTED` and the counts so far.

Also check catching up: create a schema with an older version of a table
(fewer columns, its own differently named PK) and of an enum type (fewer
values), apply all files, and check that the columns and values were added and
the PK was kept.

Before stopping the container, also check that bad data is rejected: an
unknown enum value, a dangling reference, and a missing required value should
each fail. If you change something bdchm doesn't use (`unique_keys`,
`minimum_value`/`maximum_value`, `pattern`, custom types, enum
`include`/`minus`, reserved table names, very long names), test it with a small
schema of your own as well.

When bdchm changes, check whether the generated diff under
`schema/pg-ddl/bdchm/` matches what you expected.

## `pg-apply-ddl` (`src/py_tools/pg_apply_ddl.py`)

### Driver: psycopg 3

`psycopg[binary]` ships libpq inside its wheels, including `win_amd64` for
Python 3.14, so the tool needs no `psql` or client install on Windows. Paths use
`pathlib`, and files are read and logs written as UTF-8 whatever the platform
default is.

### Splitting statements

Each statement must run separately (its own transaction, its own error), so the
files are split in Python (`split_sql()`), not sent whole. It's a small scanner
rather than `split(";")`, because semicolons appear inside `DO $ddl$ ... $ddl$`
bodies, strings, quoted identifiers and comments. It handles `'...'` with `''`,
`E'...'` with `\'`, `"..."` with `""`, `$tag$...$tag$` (but not `$1`
parameters), `--` comments and nested `/* */` comments. Comments between
statements are dropped. Each statement keeps its starting line number for the
log. It isn't a full SQL parser: anything that isn't a quote, comment or `;` is
passed through.

### Connection and setup

- The connection is in autocommit mode, so each `execute()` is its own
  transaction and a failure leaves the connection usable. If the connection
  itself breaks (`conn.broken`), the run stops with exit 2.
- A missing database is detected from the connect error: SQLSTATE `3D000`, or
  the message text, because older libpq versions don't pass the SQLSTATE of a
  connection error on. The target database is tried first, so the maintenance
  database is only needed when creating it.
- The schema is looked up in `pg_namespace` before `CREATE SCHEMA IF NOT
  EXISTS`, because that statement checks the CREATE privilege on the database
  even when the schema exists. A role that owns its schema but can't create
  schemas still works.
- `search_path` is set to only the target schema, so unqualified DDL names go
  there and `IF NOT EXISTS` / catalog checks don't see same-named objects in
  `public`. `client_min_messages` is set to `notice`, because the NOTICEs
  are how a skipped statement is told apart.
- Defaults read the standard `PG*` variables, so the tool behaves like `psql`
  where it can. `--schema` has no standard variable.
- `sys.stdout` is line-buffered so progress and failure lines (stderr) stay in
  order when piped.

### Outcomes and logs

- **SKIPPED** is decided from the NOTICEs a statement raises, by SQLSTATE
  (`SKIP_SQLSTATES`: 42P04, 42P06, 42P07, 42701, 42710, the duplicate_* codes),
  not by message text, so it works with any server `lc_messages`. A notice
  handler collects them per statement. It must copy `sqlstate` and
  `message_primary` inside the callback: psycopg's `Diagnostic` is only valid
  during the call. Storing the object and reading it later gives `None`, which
  at first made every statement look OK.
- **One log per DDL file per run** (`Log`), at
  `<log-dir>/<host>-<port>/<db>/<schema>/<grandparent>/<parent>/<file-stem>/<timestamp>.log`.
  Grouping by target first keeps everything done to one schema together. The
  per-file folder keeps reruns of a step side by side. `safe_part()` replaces
  characters Windows doesn't allow (an IPv6 host's `:`). A second run in the
  same second gets `-2`, `-3`, and so on.
- The log is written and flushed line by line, so it can be read during a long
  run and is complete up to the point of a Ctrl-C or a broken connection.
  `apply_file()` updates the `FileResult` it's given rather than returning a
  new one, so the totals written after an interruption are the real ones.
- A statement's one-line summary is the whole statement with its whitespace
  collapsed. For a generated `DO` block it's the guarded statement between
  `THEN` and `ELSE`, because `DO $ddl$` alone says nothing.
- Exit status: 0, 1 (some failed), 2 (couldn't start, or the connection
  broke), 130 (Ctrl-C). `run()` returns it, and `main()` / `run_ddl_file()` pass
  it to `sys.exit`.

## `pg-bootstrap` (`src/py_tools/pg_bootstrap.py`)

- It replaces a `docker-entrypoint-initdb.d` script, which only runs when a
  cluster is created. A command can be run at any time, against any server,
  and from Windows too.
- It does two connections as the admin role: one to `--admin-db` for the role
  and database (`CREATE DATABASE` can't run in the target database, nor in a
  transaction, hence autocommit), then one to the new database for extensions
  and the schema, because both are per-database.
- Each step checks the catalog (`pg_roles`, `pg_database`, `pg_extension`,
  `pg_namespace`) and only creates what's missing. It never alters what exists
  (owner, LOGIN, extension version), so a hand-made setup is never changed
  behind your back. The output says what it found, including the owner.
- Extensions and the schema are created as the admin, because `CREATE
  EXTENSION` usually needs a superuser. The schema is created `AUTHORIZATION
  <role>`, so the role owns it and can create objects in it.
- Schema `public` is left alone. Since PostgreSQL 15 it's owned by
  `pg_database_owner`, which is the role when it owns the database, so the old
  `ALTER SCHEMA public OWNER TO dp` isn't needed.
- Names go through `psycopg.sql.Identifier`, and the password through
  `sql.Literal`, because DDL can't take bind parameters.
- To verify: start a throwaway server with the extensions installed (for
  pgvector, build the backend's `pg/` image) and run it twice. The first run
  should report `created` and the second `exists`. Then run an `apply_*.py`
  script without options, and check that the new objects are owned by the role.

