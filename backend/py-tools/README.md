# py-tools

Python command-line and other tools for the backend. A packaged
[uv](https://docs.astral.sh/uv/) project (`src/py_tools/`), Python 3.14.
Implementation notes are in [README-DEV.md](README-DEV.md).

```sh
uv sync                 # create the venv and install the project
uv run python -m ...    # run a module
uv add <package>        # add a dependency (updates uv.lock)
```

To add a command-line tool, write a `main()` function in a module under
`src/py_tools/` and register it in `pyproject.toml`:

```toml
[project.scripts]
my-tool = "py_tools.my_tool:main"
```

Then run it with `uv run my-tool`.

## Tools

### `linkml-pg-ddl`: PostgreSQL DDL from a LinkML schema

Generates PostgreSQL DDL from any LinkML schema in two complete sets, one per
way of storing enums, split into one file per kind of statement. Each file gets
a script that applies it on its own.

#### Running it

```sh
uv run linkml-pg-ddl schema/linkml/bdchm.yaml
```

| Argument / option | Meaning                                                                 |
|-------------------|-------------------------------------------------------------------------|
| `SCHEMA`          | Path to the LinkML schema YAML, relative to the current directory or absolute. Its imports are resolved too. |
| `--out-root DIR`  | Parent folder for the output. Default: this project's `schema/pg-ddl/`. |

Output goes to `<out-root>/<schema-file-stem>/<mode>/`. For example,
`schema/linkml/bdchm.yaml` goes to `schema/pg-ddl/bdchm/enum-types/` and
`schema/pg-ddl/bdchm/enums-as-varchar/`. The script prints each file it
writes. Every run replaces the previously generated files, so don't edit them
by hand. Change the schema and regenerate instead.

#### Output files

| Mode folder         | Enums are stored as                                               |
|---------------------|-------------------------------------------------------------------|
| `enum-types/`       | Native PostgreSQL enum types (`CREATE TYPE ... AS ENUM`).         |
| `enums-as-varchar/` | `varchar` columns, with CHECK constraints listing the permissible values. |

Each file name ends with its mode (`__enum_types` or `__enums_as_varchar`), so
it's self-describing even outside its folder:

| File (`<mode>` = `enum_types` or `enums_as_varchar`) | Contents                    |
|------------------------------|-----------------------------------------------------|
| `00_enum_types__enum_types.sql` | `CREATE TYPE ... AS ENUM`, one per enum. `enum-types` only. |
| `01_tables__<mode>.sql`       | `CREATE TABLE`, plus `COMMENT ON` for each table and column giving its LinkML class or slot and description. |
| `02_primary_keys__<mode>.sql` | Primary keys.                                       |
| `03_foreign_keys__<mode>.sql` | Foreign keys, plus a comment for each reference that has none (see below). |
| `04_constraints__<mode>.sql`  | CHECK constraints (enum values in `enums-as-varchar`, `minimum_value`, `maximum_value`, `pattern`) and UNIQUE (`unique_keys`). |
| `05_not_null__<mode>.sql`     | `SET NOT NULL` for `required` slots. Optional.      |
| `apply_<file>.py`             | Next to each `.sql` file: applies that one file (see [Applying the DDL](#applying-the-ddl)). |

Use one mode's folder, not a mix. Apply its files in name order; each file only
depends on the ones before it. In `enum-types`, `01_tables` needs the types
from `00_enum_types`. `05_not_null` comes last so you can leave it out, for
example while loading partial data.

Every statement is idempotent and additive, so you can re-run a file, or run
it against a schema that already has some of the objects:

| Object      | Statement                                                         |
|-------------|-------------------------------------------------------------------|
| Enum type   | Created only if no type of that name is in the target schema, then `ADD VALUE IF NOT EXISTS` for each value. |
| Table       | `CREATE TABLE IF NOT EXISTS`, then `ADD COLUMN IF NOT EXISTS` for each column. |
| Primary key | Added only if the table has no primary key (under any name).      |
| FK, CHECK, UNIQUE | Added only if the table has no constraint of that name.     |
| `COMMENT ON`, `SET NOT NULL` | Safe to repeat as they are.                      |

A statement that finds its object already there raises a NOTICE ending in
"already exists, skipping", which `pg-apply-ddl` logs as SKIPPED.

Nothing is ever dropped or changed. An existing column keeps its type even if
the schema now says otherwise, an existing constraint keeps its definition, and
enum values removed from the schema stay in the type. The DO-block checks are
not proof against two runs at the same moment.

#### Applying the DDL

First, if the database isn't set up yet, run
[`pg-bootstrap`](#pg-bootstrap-set-up-the-role-database-and-schema) to create
the `dp` role, the `dp` database, the `vector` extension and the `bdchm`
schema.

Apply the files one at a time with the generated scripts, and check each log
before going on. Stop wherever you like: because every statement runs in its
own transaction, whatever succeeded stays, and you can fix the data and run the
same step again. Run them from this folder, so the project's environment is
used:

```sh
D=schema/pg-ddl/bdchm/enum-types
uv run python $D/apply_00_enum_types__enum_types.py
uv run python $D/apply_01_tables__enum_types.py
#   ... load data ...
uv run python $D/apply_02_primary_keys__enum_types.py   # e.g. fails on duplicate ids
#   ... look at the log, fix the data, run it again ...
uv run python $D/apply_03_foreign_keys__enum_types.py
uv run python $D/apply_04_constraints__enum_types.py
uv run python $D/apply_05_not_null__enum_types.py
```

On Windows, use `\` in the paths if your shell needs it. Each script takes the
connection options of [`pg-apply-ddl`](#pg-apply-ddl-apply-ddl-files-to-postgresql)
(below), for example `--dbname mydb --schema test`; `--help` lists them. By
default they connect to `dp` on `localhost:5432` as `dp`, schema `bdchm`.

To apply the files with `psql` instead: names in the DDL are not
schema-qualified, so objects are created in the first schema on the session's
`search_path`. Into a `bdchm` schema:

```sh
psql -c 'CREATE SCHEMA IF NOT EXISTS bdchm'
for f in schema/pg-ddl/bdchm/enum-types/*.sql; do
  PGOPTIONS='-c search_path=bdchm' psql -v ON_ERROR_STOP=1 -f "$f"
done
```

As init files in the official `postgres` Docker image: the image runs the
`*.sql` and `*.sh` files at the top level of `/docker-entrypoint-initdb.d/`
in name order, once, when the data directory is empty. It does not look in
subfolders. Copy one mode's folder in as a subfolder, and add a script that
applies it with the schema set:

```sh
# /docker-entrypoint-initdb.d/30-bdchm.sh  (with the DDL in .../bdchm/)
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -c 'CREATE SCHEMA IF NOT EXISTS bdchm'
for f in /docker-entrypoint-initdb.d/bdchm/*.sql; do
  PGOPTIONS='-c search_path=bdchm' \
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$f"
done
```

If you copy the `.sql` files straight into `/docker-entrypoint-initdb.d/`
instead, they land in the default schema (usually `public`), and all of them
are applied, `05_not_null` included.

#### How the schema maps to tables

| LinkML                                       | PostgreSQL                                              |
|----------------------------------------------|---------------------------------------------------------|
| Concrete class `MeasurementObservation`      | Table `measurement_observation`, with all own and inherited slots |
| Abstract class or mixin                      | No table                                                |
| Identifier (or key) slot                     | Primary key `<table>__pk`                               |
| Slot `associated_visit` with range `Visit`   | Column `associated_visit_id` + FK `<table>__associated_visit_id__fk_visit` |
| Multivalued slot with a class range          | Join table `<table>__<slot>` (`<table>_id`, `<slot>_id`), FKs to both sides |
| Multivalued slot with a type or enum range   | Array column, e.g. `text[]`                              |
| Enum `VitalStatusEnum` (listed values)       | Type `vital_status_enum`, or `varchar` + CHECK `<table>__<column>__ck_vital_status_enum` |
| Dynamic enum (`reachable_from` an ontology)  | `varchar`, no value check (the values aren't in the schema) |
| `required: true`                             | `SET NOT NULL` in `05_not_null__<mode>.sql`             |

Types: `string`, `uri`, `uriorcurie`, `curie` → `text`; `integer` →
`integer`; `float`, `double` → `double precision`; `decimal` → `numeric`;
`boolean` → `boolean`; `date` → `date`; `datetime` → `timestamp`; `time` →
`time`. Custom types use the type they're derived from (`typeof`).

Things to know:

- **References without a foreign key.** A foreign key can point at only one
  table. When the range class is abstract or has subclasses, the referenced
  object can be in any of several tables, so no FK is created. In bdchm, for
  example, `Condition.associated_evidence` points to `Entity` (52 tables) and
  `File.derived_from` to `File` (`file` or `imaging_file`).
  `03_foreign_keys__<mode>.sql` lists each of these as a comment.
- **Parent tables don't contain subclass rows.** `observation` holds only plain
  `Observation` objects. A `MeasurementObservation` is in
  `measurement_observation` only.
- **Required multivalued references aren't enforced.** They become join tables,
  and NOT NULL can't require at least one row. `01_tables__<mode>.sql` marks these with
  a `-- note:`.
- **Reserved words are quoted.** A name that's a PostgreSQL reserved word, such
  as the column `order` in bdchm's `cause_of_death`, is written as `"order"`, and
  your SQL must quote it too. All other names are unquoted lowercase.
- **Long names are shortened.** PostgreSQL limits names to 63 characters, so a
  longer name is cut and given an 8-character hash suffix.
- **Every class needs an identifier.** Each concrete class, and each class
  used as a range, must have an `identifier: true` (or `key: true`) slot.
  Otherwise the script stops with an error.

### `pg-apply-ddl`: apply DDL files to PostgreSQL

Applies DDL files, such as the ones `linkml-pg-ddl` writes, to a database and
schema. The generated `apply_*.py` scripts run it for their one file; this
command takes any list of files. It works on Linux, macOS and Windows, with no
`psql` needed.

```sh
uv run pg-apply-ddl schema/pg-ddl/bdchm/enums-as-varchar/*.sql      # all files, in the order given
uv run pg-apply-ddl --dbname mydb --schema test FILE                 # another database and schema
```

What it does:

1. Connects to the database. If it doesn't exist, it connects to
   `--maintenance-db` and creates it, which needs the `CREATEDB` privilege.
2. Creates the schema if it doesn't exist, then sets the `search_path` to only
   that schema, so the DDL's unqualified names are created there.
3. Runs each statement in its own transaction. A failing statement is reported
   and logged, and the rest still run. Ctrl-C stops it; the log is kept.

| Argument / option  | Default                                   | Meaning                          |
|--------------------|-------------------------------------------|----------------------------------|
| `DDL ...`          | (required; the `apply_*.py` scripts pass their own file) | Files to apply, in the order given. |
| `--host`           | `$PGHOST`, else `localhost`               |                                  |
| `--port`           | `$PGPORT`, else `5432`                    |                                  |
| `--dbname`         | `$PGDATABASE`, else `dp`                  | Created if missing.              |
| `--user`           | `$PGUSER`, else `dp`                      |                                  |
| `--password`       | `$PGPASSWORD`, else the password file     | Not needed with trust authentication. |
| `--schema`         | `bdchm`                                   | Created if missing.              |
| `--maintenance-db` | `postgres`                                | Used only to create the database. |
| `--log-dir`        | this project's `logs/` (ignored by git)   | Root folder for the logs.        |

Exit status: 0 if no statement failed, 1 if any failed, 2 if the run couldn't
start or the connection broke (file not found, no connection, or the database
or schema couldn't be created), 130 if interrupted.

#### Logs

Each run of each DDL file writes its own log, grouped by where it was applied,
then by DDL set, mode and file:

```
logs/<host>-<port>/<db>/<schema>/<ddl-set>/<mode>/<ddl-file>/<timestamp>.log

logs/localhost-5432/dp/bdchm/bdchm/enum-types/02_primary_keys__enum_types/
    20261029-101742.log
    20261029-103010.log     <- the same step, run again after fixing data
```

`<ddl-set>/<mode>` are the names of the DDL file's grandparent and parent
folders, which for `linkml-pg-ddl` output are the schema and the mode.

A log has one line per statement, written as it runs, then the totals, then
the details of each failure:

```
STATUS   LINE   SECONDS  STATEMENT
FAILED        6    0.002  ALTER TABLE person ADD CONSTRAINT person__pk PRIMARY KEY (id);
                         SQLSTATE 23505: could not create unique index "person__pk"
OK           19    0.003  ALTER TABLE person__cause_of_death ADD CONSTRAINT ...
SKIPPED      32    0.001  ALTER TABLE demography ADD CONSTRAINT demography__pk PRIMARY KEY (id);
                         primary key on demography already exists, skipping
...
finished 2026-10-29T10:17:43: 88 ok, 1 skipped, 1 failed

============================== failures ==============================

line 6: SQLSTATE 23505: could not create unique index "person__pk"
DETAIL: Key (id)=(p1) is duplicated.

<the full statement>
```

| Status    | Meaning                                                              |
|-----------|----------------------------------------------------------------------|
| `OK`      | Ran without error, and didn't report that its object already existed. |
| `SKIPPED` | Ran, but its object already existed ("already exists, skipping").     |
| `FAILED`  | Raised an error. Nothing from that statement was applied.             |

`COMMENT ON` and `SET NOT NULL` give no such notice, so they show as `OK` even
when nothing changed. On a fresh schema, the `ADD COLUMN IF NOT EXISTS` and
`ADD VALUE IF NOT EXISTS` statements after each new table or type show as
`SKIPPED`, because `CREATE` already added them.

Failures are also printed as they happen, and the log's path is printed at
the end of each file.

Things to know:

- **One failure can cause others.** If a table can't be created, each later
  statement on it fails too, and an FK fails if the table it references has no
  primary key yet. Look at the first failure for each table.
- **No transaction across statements.** A run that stops part way, or is
  interrupted, keeps what it applied. Because the DDL is idempotent, run the
  same file again after fixing the cause.

### `pg-bootstrap`: set up the role, database and schema

Creates what the DDL needs before it can be applied, connecting as an
administrative role. Each item is created only if it's missing:

```sh
uv run pg-bootstrap                     # role dp, database dp, extension vector, schema bdchm
uv run pg-bootstrap --user app --dbname appdb --schema s1 --extensions ''
```

```
Server: localhost:5432, as postgres
role       dp                       created (LOGIN)
database   dp                       created (owner dp)
extension  vector                   created (version 0.8.7)
schema     bdchm                    created (owner dp)
```

| Option             | Default                               | Meaning                               |
|--------------------|---------------------------------------|---------------------------------------|
| `--host`, `--port` | `$PGHOST`/`$PGPORT`, else `localhost:5432` |                                  |
| `--user`           | `$PGUSER`, else `dp`                  | Login role to create. It owns the database and schema. |
| `--password`       | none                                  | Password for the role, if it's created. Not needed with trust authentication. |
| `--dbname`         | `$PGDATABASE`, else `dp`              | Database to create, owned by `--user`. |
| `--extensions`     | `vector`                              | Comma-separated extensions to create in the database; `''` for none. |
| `--schema`         | `bdchm`                               | Schema to create in the database, owned by `--user`. |
| `--admin-user`     | `postgres`                            | Role to connect as. Needs the rights to create roles, databases and the extensions (a superuser). |
| `--admin-password` | `$PGPASSWORD`, else the password file |                                       |
| `--admin-db`       | `postgres`                            | Database to connect to while creating the role and database. |

The defaults match `pg-apply-ddl` and the `apply_*.py` scripts, so after
`pg-bootstrap` they run as `dp` without options, and everything they create
is owned by `dp`.

Existing objects are reported but never changed, even if they differ (for
example, a database owned by another role). Fix those by hand. Exit status: 0
on success, 2 on any error. The steps before the error have already been done.

### `pg-load-data`: load harmonized YAML data into the tables

Loads LinkML instance data into the tables from `linkml-pg-ddl`, in
**enums-as-varchar** mode: values of enum-range slots are loaded as plain
strings, as they are in the data (for example `OMOP:8507` for `sex`).

```sh
uv run pg-bootstrap                                                               # once
uv run python schema/pg-ddl/bdchm/enums-as-varchar/apply_01_tables__enums_as_varchar.py
uv run pg-load-data                                  # all data/harmonized/*--data.yaml
uv run pg-load-data path/to/other-dir                # all *--data.yaml in that directory
uv run pg-load-data data/harmonized/SYNTH1-Person--data.yaml --no-truncate
```

Each data file holds records of one class, as YAML documents separated by
`---`. The class comes from the file name, `<anything>-<ClassName>--data.yaml`,
for example `SYNTH1-Person--data.yaml` → `Person` → table `person`.

| Argument / option | Default                                        | Meaning                         |
|-------------------|------------------------------------------------|---------------------------------|
| `DATA ...`        | `data/harmonized/`                             | Data files and/or directories, at most one file per class. A directory means every `*--data.yaml` directly in it (not in subdirectories). |
| `--host`, `--port`, `--dbname`, `--user`, `--password`, `--schema`, `--log-dir` | as for `pg-apply-ddl` | |
| `--linkml-schema` | `schema/linkml/bdchm.yaml`                     | The schema the tables were generated from. |
| `--truncate` / `--no-truncate` | `--truncate`                      | Empty the tables that receive rows before loading. |

How records become rows:

- **Scalar slots** go into their columns. A multivalued type or enum slot is an
  array column (`race` → `{OMOP:8516}`). A single value for it is wrapped in a
  one-element array (`identity: "900001"` → `{900001}`).
- **References** go into `<slot>_id` (single-valued) or the join table
  `<table>__<slot>` (multivalued). **Nested objects** (a mapping instead of an
  id) are loaded into their own class's table first, and their id is used:
  `value_quantity` → `quantity`, `associated_assay` → `assay` (and its limits
  → `quantity`), `MeasurementObservationSet.observations` →
  `measurement_observation`, `Person.cause_of_death` → `cause_of_death`.
- **Values are stored as they are.** References aren't checked against other
  tables, and enum values aren't checked against the schema. For example,
  `member_of_research_study` = `Example Study One` and `body_site` =
  `Right arm` go into `*_id` columns although no such rows exist, and
  `associated_evidence` text goes into its join table.
- **Duplicate ids** are skipped: a record whose id is already in its table, or
  was already loaded in this run, isn't inserted again. The same goes for a
  nested object (the 2 assays repeated across 2,916 observations are stored
  once).
- **Keys that aren't slots** of the class are ignored and listed in the log.

Files are loaded so that referenced classes come first (Person before
Participant, and so on), so loading also works after the FKs have been applied.

**Truncating.** With `--truncate` (the default), the tables that will receive
rows, including join tables and the tables of nested objects, are emptied
first. Other tables that reference them through foreign keys are truncated
with them if they're empty. If any of those has rows, the run stops without
changing anything, rather than delete data it didn't load.

**Failures.** Each record is loaded together with its nested objects and join
rows, in a savepoint: if any of its rows fails, none of them are stored, and
loading continues with the next record. Each file is one transaction,
committed when the file is done. Ctrl-C rolls back the file being loaded;
files loaded before it stay.

Exit status: 0 if no record failed, 1 if any failed, 2 if the run couldn't
start (bad file name, unknown class, tables missing, no connection, can't
truncate), 130 if interrupted.

#### Load logs

One log per data file per run, next to the DDL logs for the same target:

```
logs/<host>-<port>/<db>/<schema>/load/<data-folder>/<data-file>/<timestamp>.log

logs/localhost-5432/dp/bdchm/load/harmonized/SYNTH1-Person--data/20261029-002537.log
```

A log has the totals, the rows inserted and duplicates skipped per table, keys
that were ignored, and each failed record with its number, starting line,
error (SQLSTATE, message, DETAIL) and the record itself:

```
finished 2026-10-29T00:28:15: 2 loaded, 0 duplicate, 3 failed records

rows inserted / duplicate rows skipped, per table:
  person                                               2        0

============================== failures ==============================

record 2 (line 5), id 'bad-int':
SQLSTATE 22P02: invalid input syntax for type integer: "abc"

id: bad-int
year_of_birth: abc
```

#### With the bdchm DDL and the SYNTH1 data

Load after `01_tables`; then apply the rest one step at a time and read the logs:

- `02_primary_keys`: applies (no duplicate ids).
- `03_foreign_keys`: two FKs fail, as expected from the as-is references:
  `participant.member_of_research_study_id` → `research_study` (no study data)
  and `measurement_observation.body_site_id` → `body_site` (`Right arm` is
  text, not a BodySite id).
- `04_constraints`: in enums-as-varchar mode its CHECKs allow only the
  schema's permissible value names (`MALE`, `ALIVE`, ...), while the data has
  CURIEs (`OMOP:8507`), so those CHECKs fail. Skip this file for this data.

