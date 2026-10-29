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

## `pg-load-data` (`src/py_tools/pg_load_data.py`)

### One schema analysis

`Mapper` builds a `linkml_pg_ddl.Generator` in enums-as-varchar mode and reads
its `Table`s: class tables (`class_name` set) and join tables (`class_name =
None`, owner from the first FK's target, slot from the first column). It maps
`(table, slot)` to a column or a join table. Table and column names, array
columns, `<slot>_id` columns and join tables therefore always match the DDL,
including shortened (`pg_name`) and quoted names. Varchar mode is used because
the data's enum values are CURIEs, not the schema's permissible value names,
so they must be stored as text.

### Plan, then load

All files are read and mapped first (`Mapper.plan()`), into a list of
`RowOp`s per record: nested objects first (so FKs to them are satisfied),
then the record's own row, then its join rows. A record that can't be mapped
(no id, a list for a single-valued slot, a nested object whose class can't be
told) is kept as a failure. Planning everything first gives the full set of
target tables to check and truncate before anything is written.

- A nested object's table: the single table of its range class (via
  `tables_for_class()`), or the range class's own table when it's concrete but
  has subclasses. Anything else is a record failure: there's no type field to
  pick a subclass table (bdchm data doesn't need one).
- YAML is read with `CSafeLoader` (the 4 MB file parses in about 0.3 s, against
  2.8 s with the pure-Python loader), node by node, so each record keeps
  the line its document starts on.

### Loading

- **Duplicates.** `seen[table]` holds the keys (the PK columns from the DDL:
  `id`, or owner and target for join tables) present in the table at the start
  (empty after truncation) plus everything inserted in this run. A record whose
  own key is in it is skipped whole. A nested object or join row whose key is
  in it is skipped individually and counted per table. This works with or
  without PKs in the database. A failed record's keys are removed again,
  because its savepoint was rolled back.
- **Transactions.** The connection isn't in autocommit mode. Each file runs in
  `conn.transaction()`, and each record in a nested one (a savepoint). psycopg
  rolls back to the savepoint on an error and re-raises it, and the loop
  records it and goes on. Per-record commits were avoided because 30,000+
  commits are slow. With savepoints, the full load takes about 30 seconds.
- **Inserts** are built per row from the keys present in the record
  (`sql.Identifier`, placeholders). psycopg prepares repeated statements by
  itself. Values go in as YAML gave them; PostgreSQL casts strings to the
  column type and rejects bad ones (`22P02`), which is how type errors reach
  the log.
- **Truncate.** A single `TRUNCATE` of all target tables, because tables
  referencing each other must be truncated together. `referencing_tables()`
  walks `pg_constraint` for FKs into that set, transitively, and adds the
  referencing tables if they're all empty. Otherwise it stops. `CASCADE` was
  rejected because it would silently empty tables the load doesn't own.
- **File order.** `load_order()` sorts the files' classes so that a class
  comes after those its single-valued slots reference (a cycle falls back to
  name order). Multivalued references are join rows, inserted after their
  record, so they don't affect the order.

### Verifying changes

Use a throwaway server built from the backend's `pg/` image (it has
pgvector, which `pg-bootstrap` creates). Then: `pg-bootstrap`; apply
`apply_01_tables__enums_as_varchar.py`; run `pg-load-data` (all files load with
0 failed). Run it again (same counts), and once with `--no-truncate` (every
record duplicate). Apply 02 and 03 and load again (it should truncate the
referencing tables, and refuse once one of them has a row). Then load a small
file of bad records (bad integer, no id, unknown key, bad nested value,
dangling reference with FKs on) and check the log. Finally, Ctrl-C during
`SYNTH1-MeasurementObservation` should leave that table empty and earlier
files loaded.

## `pg-export-participants` (`src/py_tools/pg_export_participants.py`)

### Reading

All tables (from the `Mapper`'s `Generator` tables, enums-as-varchar) are read
into memory once: `rows[table][id]` for class tables, and
`joins[(owner table, slot)][owner id] = [target ids]`. With the SYNTH1 data
that's about 35,000 rows, and all 500 documents are built in under a second.
Much larger data would need per-participant queries instead. Reading once
also lets post-processors look anything up through `ctx.rows`.

`referrers[target id]` lists every `(table, id, slot)` that references an id
(reference columns and join tables), which drives the reverse lookups.

### Homes (`Exporter.homes()`)

- Members: `referrers[participant id]` entries whose slot's range tables
  include the participant table (so `Entity`-range slots count). This is
  decided per record, not per class.
- Indirect records: breadth-first from the members, through `nestable()`. Only
  records of non-member classes (no slot whose range can be a Participant) are
  nested, through a slot whose range is exactly the container's table. Each
  record gets one home: the first found, members first.
- Why so narrow: every class that can be nested under another needs its
  fields in the Elasticsearch mapping under that path. Allowing member-class
  records (orphans such as a Condition with only `associated_visit`) took the
  mapping from ~840 to ~3,200 fields and ~200 nested fields. Allowing
  polymorphic slots (`Entity`-range `focus`) made it unbounded. Records of
  member classes with no participant reference are counted in the log
  (`homed_all` against all their rows).
- Ids are assumed to be unique across tables, as bdchm's UUIDs are. Lookups by
  id use the slot's range tables to tell where an id can live.

### JSON type per slot (`id_slot()`)

Elasticsearch maps a field from the first value it sees, and then rejects
documents where the same field has another type. A rule that picked a string
or an object depending on the data (homed here or not, polymorphic target
class, cycle cut) gave mixed types: `associated_evidence` had an object and a
string in the same array. So the type is fixed per slot, from the schema:

- id slot: a single range table, whose class is the root or a "member class"
  (a class with a slot whose range can be a Participant). Always the id
  string, even if the record isn't in this document.
- object slot: everything else. Always an object: inlined, or a stub
  `{<identifier slot>: id}` when the record is homed here or is on the current
  inlining path (`path`, which ends cycles such as `ResearchStudy.part_of`).
  On a polymorphic slot, objects and stubs get `_class`.

Accepted cost: through an id slot, a member-class record that isn't homed in
this document (another participant's visit, or a Document with no focus) is
only its id.

The path guard compares classes (tables), not records: a record of a class
already on the inlining path becomes a stub. That keeps the documents within
the mapping's depth. The mapping has to stop recursing at a repeated class,
and the exporter must not go deeper than the mapping.

Full-union polymorphic slots (`union_slot()`): when no class of the range can
belong to a participant, the objects are always inlined in full, so the
mapping holds the union of the classes' fields. A slot whose range differs
between the classes gets the range as a key suffix (`union_key()`), computed
from the schema and applied to the inlined object's keys.

To check it, export all participants, then walk every document and collect
the JSON types seen at each path (`$.condition[].associated_visit`, ...). Each
path should have one type, counting int and float as one.

### Left out

`OMIT` covers references to the root, references from a nested record to its
container (redundant; it's the parent object), and ids that resolve to no
table, which are counted in `unresolved` for the log. Null column values and
empty arrays are skipped while building. `Decimal` becomes `float`, and
dates and times become ISO strings (`json_value()`).

### Post-processors (`participant_postprocess.py`)

- Kept in their own module, so adding one doesn't touch the exporter.
  `POST_PROCESSORS` holds functions or classes. `--post-processor module:name`
  imports more by name (`load()`), so experiments don't need code changes.
- Classes are instantiated once per run (`instantiate()`), so they can cache
  lookups in `__init__` and hold state. `finish(summary)` is optional and
  duck-typed.
- A processor may mutate `doc` or return a replacement. Anything else
  (non-dict) is a `TypeError`. Exceptions are caught per participant: that
  file isn't written, its previous file is kept (stale removal only removes
  files of ids that are no longer in the participant table), and the run exits
  1.
- `Context.extra` is a per-participant dict for passing values between
  processors.
- `add_condition_keys` (built in) builds `_conditions` from the document's
  `condition` array, not from `ctx.rows`, so it sees exactly what's indexed.
  The fields and separator are module constants (`CONDITION_KEY_FIELDS`,
  `CONDITION_KEY_SEPARATOR`). None of the SYNTH1 values contain `|`; if a
  future value could, the separator would need to change, because there's no
  escaping.

### Verifying changes

On a throwaway server from the backend's `pg/` image, run `pg-bootstrap`,
apply `01_tables__enums_as_varchar`, run `pg-load-data`, then
`pg-export-participants`. All 500 should be written, and the log should list
the three unresolved slots above. Then add rows for the cases SYNTH1 doesn't
have, and export one participant:
- a `research_study` 'Example Study One' with `part_of_id` pointing at itself
  (inlined, `part_of` becomes a stub);
- `condition__associated_evidence` rows pointing at a quantity (inlined with
  `_class`) and at a visit (a `_class` stub);
- a `questionnaire_response` pointing at a visit (nested under it, with no
  `associated_visit`);
- a `document` whose `focus_id` is an observation (nested under that
  observation).

Run with `--post-processor` modules on `PYTHONPATH`: a function, a class with
`finish`, and one that raises for some participants (those fail, and their
earlier files are kept). Finally, check that each JSON path has a single type
over all documents.

## `es-participant-mapping` (`src/py_tools/es_participant_mapping.py`)

- `MappingBuilder` walks the schema with the exporter's own decisions:
  `Exporter.id_slot()`, `is_polymorphic()`, `union_slot()`, `union_key()`,
  `nestable()`, `array_key()`, the slot order and `member_classes`. It's built
  on an `Exporter` with empty rows, so no database is needed. A change to
  the exporter's rules is therefore picked up by the mapping. Anything the
  exporter decides from data (stubs for homed records) is covered because a
  stub's fields (`id`, `_class`) are a subset of the full mapping.
- Paths: the mapping's `path` is the set of tables inlined so far. A member
  array, the root and each nested record start a new path, as in
  `Exporter.build()`. Indirect nesting uses a separate `chain` to stop cycles.
- `dynamic: strict` at the root is inherited by every object except the
  polymorphic `_class`/`id` objects, which set `dynamic: false` because the
  exporter can inline whole `Entity` objects there.
- Union slots are merged class by class. A key mapped differently by two
  classes without being renamed raises `MappingError`, rather than letting one
  silently win.
- Post-processor fields come from `es_properties` on each processor. A clash
  with a mapped field is an error.
- `measure()` counts fields the way Elasticsearch does for
  `total_fields.limit` (objects and multi-fields included), plus nested fields
  and depth. The limits are set to 1.25 times those, rounded up to 50, and
  never below the defaults. `depth.limit` is only written when the default of
  20 isn't enough.
- File names: `<YYYYMMDD-HHMMSS>__participant.json`. A second run in the same
  second fails instead of adding a suffix, because `-2` sorts before `_` and
  would break the order.

### Verifying changes

Generate a file. Export from a test database that also has rows for the cases
SYNTH1 lacks: a questionnaire response on a visit with boolean, decimal,
string and TimePoint answers; a study that is `part_of` itself; polymorphic
evidence pointing at a quantity and at a visit; and a condition with only a
visit. Then start a throwaway Elasticsearch of the backend's version:

```sh
docker run -d --rm --name es-map-es -p 127.0.0.1:59201:9200 -e discovery.type=single-node \
  -e xpack.security.enabled=false -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
  docker.elastic.co/elasticsearch/elasticsearch:9.5.3
```

Create an index from the file, bulk-index every document, and expect 0 errors
(strict mapping). Run nested, `_conditions`, range, `value_boolean` and `.text`
queries, and check that a document with an extra field is rejected
(`strict_dynamic_mapping_exception`).

## `es-recreate-index` (`src/py_tools/es_recreate_index.py`)

- Uses the official `elasticsearch` client (9.x, matching the server major
  version; the client sends compatibility headers for its major version). It
  handles basic auth, API keys and TLS options, so the same command works
  against a secured or hosted cluster. Connection defaults come from `ES_*`
  variables, like the `PG*` defaults of the database tools.
- The definition file's `settings` (the mapping limits) are merged with
  `index.number_of_shards` and `index.number_of_replicas` from the options.
  The options win if the file ever sets them too.
- Checks before deleting: `exists_alias(name=index)` stops a delete through
  an alias name, and an `--alias` that is an existing index is refused (ES
  would reject it anyway, but only after the index had been recreated).
- The alias is moved with a single `update_aliases` call (removes, then add),
  which ES applies atomically (`point_alias()`, shared with the loader).
- `add_es_options()`, `client()`, `log_folder()` and `new_log_path()` are shared
  with `es-load-participants`. Logs go to `es/<index>/recreate/` and
  `es/<index>/load/`, so each index's history is in one place.
- Health: waits for green with 0 replicas, otherwise yellow, for up to 30
  seconds, and logs a timeout rather than failing.
- Confirmation follows `backend/bin/clean-*.sh`: ask on a terminal, `-y` to
  skip, refuse (exit 1) without a terminal.

### Verifying changes

Run against a throwaway ES of the backend's version (see
`es-participant-mapping` above). Cases:
- a fresh index;
- an existing index with documents, without a terminal and without `-y`
  (refuses, unchanged), then with `-y`;
- `--alias` moving from `participant_1` to `participant_2`;
- `--index` naming an alias, and `--alias` naming an index (both refused);
- a missing `--mapping` file;
- index settings showing the limits, 1 shard and 0 replicas.

For authentication, start the container with `-e xpack.security.enabled=true
-e xpack.security.http.ssl.enabled=false -e ELASTIC_PASSWORD=...`, wait for
green health (right after boot, `.security-7` isn't ready and auth returns
503), then try no credentials (401), `ES_USERNAME`/`ES_PASSWORD`, and an API
key from `POST /_security/api_key`. TLS (`--ca-cert`, `--insecure`) hasn't
been tested against an HTTPS cluster yet.

## `es-load-participants` (`src/py_tools/es_load_participants.py`)

- `helpers.streaming_bulk` with `raise_on_error=False`, so a rejected document
  is reported and the rest go on. `max_retries=3` retries 429s with back-off.
  Documents are read lazily from the files (a generator), so memory doesn't
  grow with the number of files.
- `_op_type: index`, so a load replaces documents with the same `_id`; the
  `_id` is the document's `id`. Files that can't be read or have no string
  `id` are failures without an `_id`. Two files with the same `id` are
  reported, and the later one wins.
- The index must exist (`indices.exists`, which is also true for an alias).
  Writing to a missing index would auto-create it with dynamic mappings,
  which defeats the generated mapping.
- After loading: `refresh`, so counts and searches see the documents at once.
  `--delete-missing` scans the index ids (`_source: false`) and bulk-deletes
  those not among the files read. Files that failed to index count as
  present, so their earlier version isn't deleted.
- `--alias` is moved only when nothing failed, so a partial load doesn't go
  live.

### Verifying changes

Against a throwaway ES (see `es-participant-mapping`), with exported documents
in a scratch folder:
- load before the index exists (exit 2, with the hint);
- `es-recreate-index`, then load (all loaded, `_id` = participant id);
- load again (same count; documents at `_version` 2);
- a folder that also has a document with an extra field (strict rejection),
  a non-JSON file and a document without `id`, with `--alias` (3 failures,
  the rest loaded, alias not moved);
- a clean subset with `--delete-missing --alias` (the others deleted, alias
  moved);
- no arguments (loads `data/participants/`).

## `bin/kibana-start.sh`, `bin/kibana-stop.sh`

- Plain `docker run`, not compose: one container with settings that vary per
  run. Bash 3.2 and BSD tools (macOS): arrays, `${var:-}`, `$SECONDS`, `sed`,
  `awk`, no `readlink -f`. `--help` prints the header comment.
- `host.docker.internal` comes from `--add-host
  host.docker.internal:host-gateway`, which native Docker on Linux/WSL needs
  (Docker Desktop has it anyway). It's the bridge gateway, so ES must be
  published on an address the bridge reaches (`0.0.0.0`, as the backend stack
  does), not only `127.0.0.1`.
- The version comes from ES's `GET /` (parsed with `sed`, no `jq`). That's
  also the connectivity check from the host.
- Port check and readiness probe use `curl` with connect timeouts. Bash's
  `/dev/tcp` was tried first and hung: on WSL2 with a VPN a closed port didn't
  refuse, and `/dev/tcp` has no timeout. curl exit 7 (refused) or 28
  (timed out) means nothing listens.
- After `docker run`, `docker exec ... curl` from inside the container
  checks that ES is reachable (any HTTP answer counts, even 401). Otherwise
  Kibana just waits for ES and only its log says so.
- The wait uses `$SECONDS` (elapsed time). Counting loop rounds understated
  the time when each probe took several seconds.
- Kibana 9.5's `/api/status` without credentials returns only
  `{"status":{"overall":{"level":"available"}}}`; the script waits for HTTP
  200.
- Ownership: containers get the label `py-tools.kibana=true`. Start removes a
  stopped container of its own with the same name, and refuses one that
  isn't its own. Stop does the same, so a typo in `--name` can't remove an
  unrelated container.
- Configuration goes in through the Kibana image's environment variables
  (`ELASTICSEARCH_HOSTS`, `ELASTICSEARCH_USERNAME`/`PASSWORD`,
  `ELASTICSEARCH_SERVICEACCOUNTTOKEN`, `ELASTICSEARCH_SSL_*`). A CA file is
  mounted read-only at `/usr/share/kibana/config/es-ca.crt`.

### The VPN problem found while testing

The first tests timed out everywhere: the container IP, published ports, and
`host.docker.internal` from Kibana. Elasticsearch answered inside its own
container. The cause was the VPN: with WSL in `mirrored` mode, its interface
`eth0` had `172.17.21.33/18` and gateway `172.17.0.1`, the same as
`docker0`. Its `/18` route was more specific than docker0's `/16`, so
container traffic went into the VPN. Moving Docker to `192.168.200.0/24` and
pools in `192.168.208.0/20` (`daemon.json`, see README) fixed it. Compose
networks (`172.18+`) had kept working only because their own `/16` routes
were more specific than the VPN's `172.16.0.0/12`.

### Verifying changes

With a throwaway ES published on all interfaces (`-p 59201:9200`):
- `bin/kibana-start.sh --es-port 59201`: detects 9.5.3 and is ready in about a
  minute; `/api/status` answers `available`, and `.kibana*` indices appear in ES.
- Again: refused, already running.
- `--name kib2`: refused, port taken.
- `--name <a container it didn't create>`: both scripts refuse.
- `bin/kibana-stop.sh`: removed, with the `.kibana*` indices kept. Again:
  nothing to do.
- `--version 9.5.3 --es-port 1 --no-wait`: the reachability warning.
- Not tested yet: an ES with security on (user or service token) and https.

