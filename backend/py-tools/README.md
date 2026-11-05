# py-tools

Python command-line and other tools for the backend. A packaged
[uv](https://docs.astral.sh/uv/) project (`src/py_tools/`), Python 3.14.

| Document | Contents |
|---|---|
| [README-DEV.md](README-DEV.md) | Implementation notes |
| [ES-EXAMPLES.md](ES-EXAMPLES.md) | Elasticsearch query examples, with explanations |
| [README-AZURE.md](README-AZURE.md) | Issues in migrating to Azure AI Search, including which query results can be reproduced |
| [DOCKER-VPN.md](DOCKER-VPN.md) | Docker networking problems with a VPN, and `oc port-forward` |

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

## Typical workflow

From the harmonized data to a searchable Elasticsearch index. PostgreSQL and
Elasticsearch must be running, with the data in `data/harmonized/<study>/`.

```sh
D=schema/pg-ddl/bdchm/enums-as-varchar

uv run pg-bootstrap                                # drops the database (asks first), then creates it, the role,
                                                   # extension and schema
uv run python $D/apply_01_tables__enums_as_varchar.py
uv run harmonized-add-studies                      # the study records and participant references
uv run harmonized-add-ages                         # a made-up age_at_index for every participant
uv run pg-load-data                                # every study; empties the tables first
uv run python $D/apply_02_primary_keys__enums_as_varchar.py
uv run python $D/apply_03_foreign_keys__enums_as_varchar.py   # one FK fails, as expected (see pg-load-data)

uv run pg-export-participants
uv run es-participant-mapping                      # only when the schema, exporter or post-processors changed
uv run es-recreate-index                           # asks before deleting an existing index
uv run es-load-participants --delete-missing
```

To load only some studies, give their folders:
`uv run pg-load-data data/harmonized/study_one`. Query examples are in
[ES-EXAMPLES.md](ES-EXAMPLES.md).

To rebuild the database and the index in one command, without the data preparation
and foreign keys, use [`bin/full-reload.sh`](#binfull-reloadsh-rebuild-postgresql-and-the-index).

## Tools

- [`linkml-pg-ddl`](#linkml-pg-ddl-postgresql-ddl-from-a-linkml-schema): PostgreSQL DDL from a LinkML schema
- [`pg-apply-ddl`](#pg-apply-ddl-apply-ddl-files-to-postgresql): apply DDL files to PostgreSQL
- [`pg-bootstrap`](#pg-bootstrap-start-the-database-from-scratch): start the database from scratch
- [`harmonized-add-studies`](#harmonized-add-studies-add-a-researchstudy-to-each-study-folder): add a ResearchStudy to each study folder
- [`harmonized-add-ages`](#harmonized-add-ages-give-participants-a-random-age): give participants a random age
- [`pg-load-data`](#pg-load-data-load-harmonized-yaml-data-into-the-tables): load harmonized data into the tables
- [`pg-export-participants`](#pg-export-participants-one-json-document-per-participant): one JSON document per participant
- [`es-participant-mapping`](#es-participant-mapping-elasticsearch-index-definition-for-the-participant-documents): the Elasticsearch index definition
- [`es-recreate-index`](#es-recreate-index-drop-and-recreate-an-index-from-the-definition): drop and recreate an index
- [`es-load-participants`](#es-load-participants-load-the-participant-documents-into-the-index): load the documents into the index
- [`bin/full-reload.sh`](#binfull-reloadsh-rebuild-postgresql-and-the-index): rebuild PostgreSQL and the index
- [`bin/kibana-start.sh`, `bin/kibana-stop.sh`](#binkibana-startsh-and-binkibana-stopsh-a-kibana-for-any-elasticsearch): a Kibana for any Elasticsearch

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
[`pg-bootstrap`](#pg-bootstrap-start-the-database-from-scratch) to create
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

### `pg-bootstrap`: start the database from scratch

Gives you an empty database, ready for the DDL, connecting as an administrative
role. **By default, if the database exists, it's dropped with everything in it**,
after you confirm. Then the role, database, extension and schema are each created
if they're missing.

```sh
uv run pg-bootstrap                     # database dp, role dp, extension vector, schema bdchm
uv run pg-bootstrap -y                  # the same, without asking
uv run pg-bootstrap --no-drop           # only create what's missing, and keep the data
uv run pg-bootstrap --user app --dbname appdb --schema s1 --extensions ''
```

```
Server: localhost:5432, as postgres
Drop database dp on localhost:5432 (owner dp, 312 MB, open connections: 0) with everything in it, and create it again? [y/N] y
database   dp                       dropped (was 312 MB, owner dp)
role       dp                       exists
database   dp                       created (owner dp)
extension  vector                   created (version 0.8.7)
schema     bdchm                    created (owner dp)
```

| Option             | Default                               | Meaning                               |
|--------------------|---------------------------------------|---------------------------------------|
| `--drop` / `--no-drop` | `--drop`                          | Drop the database first if it exists, with everything in it. |
| `-y`, `--yes`      | ask                                   | Drop without asking. |
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

About dropping:

- Open connections to the database are ended, including an application or database
  client using it.
- It asks only if the database exists. With no terminal and no `-y`, it changes
  nothing and exits 1, so a script must pass `-y` or `--no-drop` on purpose.
- It won't drop `postgres`, `template0`, `template1` or the `--admin-db`.
- The role isn't dropped, because other databases can use it.
- After a drop, apply the DDL and load the data again: see the
  [typical workflow](#typical-workflow).

An existing role is reported but never changed. With `--no-drop`, the same goes
for an existing database, extension and schema, even if they differ (for example,
a database owned by another role). Fix those by hand. Exit status: 0 on success,
1 if dropping wasn't confirmed (nothing changed), 2 on any error. The steps before
the error have already been done.

### `harmonized-add-studies`: add a ResearchStudy to each study folder

Each folder under `data/harmonized/` holds one study. This tool writes the
folder's study record and makes its participants reference it, so that
`Participant.member_of_research_study` conforms to the schema (a ResearchStudy
id, not a study name). Run it before `pg-load-data`, and again whenever the data
has been regenerated.

```sh
uv run harmonized-add-studies --dry-run   # report what would change
uv run harmonized-add-studies
```

| Option       | Default            | Meaning                                  |
|--------------|--------------------|------------------------------------------|
| `--data-dir` | `data/harmonized/` | The folder holding the study folders.    |
| `--dry-run`  | off                | Report what would change, without writing. |

For each study folder, for example `study_one/`:

- It writes `SYNTH1-ResearchStudy--data.yaml`, one `ResearchStudy` record. The
  prefix comes from the folder's `SYNTH1-Participant--data.yaml`.
- It sets `member_of_research_study` of every participant in that file to the
  study's id. Only those lines change.

| Folder      | Study               | Study id                               |
|-------------|---------------------|----------------------------------------|
| `study_one` | `Example Study One` | `16f3ee0b-da11-50be-8192-105affbfdaa3` |
| `study_two` | `Example Study Two` | `56fb5011-8e17-5cfa-a4f3-068837ea4b50` |

The studies are made-up example data, defined in `STUDIES` in
`src/py_tools/harmonized_add_studies.py`, because `data/harmonized/` isn't
committed. **Change a study there, not in its YAML file**: the next run replaces
a study file that differs. The ids are derived from the folder names, so they're
the same on every run. A new study folder needs a new `STUDIES` entry.

Running it again changes nothing once the data is up to date. A participant can
have the study's name (new data) or its id (already done). Any other value stops
the run, as does a folder with no `STUDIES` entry or without exactly one
participant file. Everything is checked first, so in those cases nothing is
written and the exit status is 2. Folders starting with a dot are ignored, and a
`STUDIES` entry with no folder only gets a warning. If writing a file fails, the
exit status is also 2 and the message lists the files already written. Fix the
cause and run it again.

### `harmonized-add-ages`: give participants a random age

The harmonized participants have no `age_at_index`. This tool sets it in every
`*-Participant--data.yaml` under `data/harmonized/`, to a value from 18 to 99
years. The schema defines the slot in days, so the values range from 6575 to
36524. Run it before `pg-load-data`, and again whenever the data has been
regenerated.

```sh
uv run harmonized-add-ages --dry-run   # report what would change
uv run harmonized-add-ages
```

| Option       | Default            | Meaning                                                 |
|--------------|--------------------|---------------------------------------------------------|
| `--data-dir` | `data/harmonized/` | The folder to look for participant files in, including subfolders. |
| `--dry-run`  | off                | Report what would change, without writing.              |

- **The ages are roughly uniform** over the range. Each run prints how many
  participants fall in each decade.
- **Each age comes from a hash of the participant's id**, so it looks random but
  is the same on every run.
- **The ages are made up, and independent of the rest of the data.** An
  `age_at_index` can be later than the participant's visits, whose ages run from
  45 to 85 years. Two participants of the same `Person` get unrelated ages.

Running it again changes nothing once the data is up to date. A participant can
have no age, a null age, or the age the tool would give it. Any other value
stops the run with exit status 2, and nothing is written. If writing a file
fails, the exit status is also 2 and the message lists the files already written.
Fix the cause and run it again.

### `pg-load-data`: load harmonized YAML data into the tables

Loads LinkML instance data into the tables from `linkml-pg-ddl`, in
**enums-as-varchar** mode: values of enum-range slots are loaded as plain
strings, as they are in the data (for example `OMOP:8507` for `sex`).

```sh
uv run pg-bootstrap                                                               # drops the database first; asks
uv run python schema/pg-ddl/bdchm/enums-as-varchar/apply_01_tables__enums_as_varchar.py
uv run pg-load-data                                  # all *--data.yaml under data/harmonized/, every study
uv run pg-load-data data/harmonized/study_two --no-truncate   # add one study, keeping what's loaded
uv run pg-load-data path/to/other-dir                # all *--data.yaml under that directory
uv run pg-load-data data/harmonized/study_one/SYNTH1-Person--data.yaml --no-truncate
```

Each data file holds records of one class, as YAML documents separated by
`---`. The class comes from the file name, `<anything>-<ClassName>--data.yaml`,
for example `SYNTH1-Person--data.yaml` → `Person` → table `person`. A class can
have several files, such as one per study folder. They all load into the class's
table.

Without `--no-truncate`, loading a single study empties the tables first, so
the other study's rows in those tables are gone.

| Argument / option | Default                                        | Meaning                         |
|-------------------|------------------------------------------------|---------------------------------|
| `DATA ...`        | `data/harmonized/`                             | Data files and/or directories. A directory means every `*--data.yaml` in it and its subdirectories. |
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
  `body_site` = `Right arm` goes into a `*_id` column although no such row
  exists, and `associated_evidence` text goes into its join table.
- **Duplicate ids** are skipped: a record whose id is already in its table, or
  was already loaded in this run, isn't inserted again. The first record loaded
  wins, even if a later one differs. The same goes for a nested object (the 2
  assays repeated across 2,916 observations are stored once). 25 `Person`
  records are in both study folders, with identical content, so 975 people are
  stored for 1,000 participants.
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

logs/localhost-5432/dp/bdchm/load/study_one/SYNTH1-Person--data/20261029-002537.log
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

#### With the bdchm DDL and the study data

Load after `01_tables`; then apply the rest one step at a time and read the logs:

- `02_primary_keys`: applies (no duplicate ids).
- `03_foreign_keys`: one FK fails, as expected from the as-is references:
  `measurement_observation.body_site_id` → `body_site` (`Right arm` is text,
  not a BodySite id). `participant.member_of_research_study_id` →
  `research_study` applies once
  [`harmonized-add-studies`](#harmonized-add-studies-add-a-researchstudy-to-each-study-folder)
  has been run.
- `04_constraints`: in enums-as-varchar mode its CHECKs allow only the
  schema's permissible value names (`MALE`, `ALIVE`, ...), while the data has
  CURIEs (`OMOP:8507`), so those CHECKs fail. Skip this file for this data.

### `pg-export-participants`: one JSON document per participant

Reads the loaded tables (enums-as-varchar) and writes
`data/participants/<participant id>.json` for every participant, with all of
the participant's data in one object. The documents are meant to be indexed
into Elasticsearch with nested fields that follow the bdchm model.

```sh
uv run pg-export-participants                                    # all participants
uv run pg-export-participants --participant ded1d538-866f-5c18-8aa2-0b24f1f08a5a
uv run pg-export-participants --post-processor my_module:add_fields
```

| Option             | Default                       | Meaning                                         |
|--------------------|-------------------------------|-------------------------------------------------|
| `--host`, `--port`, `--dbname`, `--user`, `--password`, `--schema`, `--log-dir` | as for `pg-apply-ddl` | |
| `--linkml-schema`  | `schema/linkml/bdchm.yaml`    | The schema the tables were generated from.      |
| `--out-dir`        | `data/participants/`          | Where the `.json` files go.                     |
| `--participant ID` | all                           | Export only these participants (repeatable).    |
| `--post-processor MODULE:NAME` | none              | Extra post-processor, after the built-in ones (repeatable). |

#### What goes where

Every record has one home in a document:

| Record | Where it goes |
|---|---|
| The participant | The document itself. |
| **Members**: records that reference the participant directly (`associated_participant`, `Specimen.source_participant`, or an `Entity`-range slot holding the participant's id) | A top-level array named after the table: `visit`, `condition`, `measurement_observation`, ... |
| **Indirect records**: records of classes that can't belong to a participant (no slot whose range can be a Participant), referencing a record with a home here through a single-class slot: `QuestionnaireResponse.associated_visit` | Nested inside that record, in an array named after their table (`visit[].questionnaire_response`), recursively. |
| A record of a participant-capable class that doesn't reference the participant (e.g. a Condition with only `associated_visit`) | In no document. The log counts these per table. |
| Anything else that's referenced (Quantity, Assay, Person, CauseOfDeath, Organization, ResearchStudy, ...) | Inlined where it's referenced. |

References are handled like this:

- A reference to the participant itself, or to the record a nested record
  sits in, is **left out**.
- A reference whose id isn't in any table the slot's range can live in is
  **left out**. The log counts these per `class.slot`. With the study data
  that's the evidence text in `Condition.associated_evidence` and `Right arm` in
  `MeasurementObservation.body_site`.
- Otherwise each reference slot has **one JSON type**, the same in every
  document, as Elasticsearch requires:
  - **id slots**: the range is a single class whose records belong to
    participants (Visit, Condition, ...) or Participant. Always the id string:
    `"associated_visit": "75122f58-..."`. Use it to find the record in its
    array.
  - **object slots**: all other reference slots. Always an object: the record
    inlined, with its own references handled the same way, or a stub
    `{"id": "..."}` when the record has a home in this document, or a record
    of the same class is already being inlined further up the same path. That
    ends circular references and limits the depth to what the Elasticsearch
    mapping describes.
- Objects in a **polymorphic** slot (one whose range can be in several tables,
  such as `Entity`) get `"_class": "<ClassName>"`, stubs included. The id is
  looked up in every table of the range. For `QuestionnaireResponseItem.response_value`
  (`QuestionnaireResponseValue`), whose `value` is a boolean, decimal,
  integer, string or TimePoint depending on the subclass, the key gets the
  type: `value_boolean`, `value_decimal`, `value_integer`, `value_string`,
  `value_time_point`. Elasticsearch can't hold them in one field.

Keys are LinkML slot names. Null values and empty arrays are left out. Arrays
of records are sorted by id, so exporting again gives identical files.

```json
{
  "id": "ded1d538-866f-5c18-8aa2-0b24f1f08a5a",
  "associated_person": {
    "id": "ddbe1e3e-...", "identity": ["900001"], "species": "NCBITaxon:9606",
    "cause_of_death": [ { "id": "90723e22-...", "cause": "ICD10CM:R99", "order": 1 } ]
  },
  "identity": ["100001"],
  "member_of_research_study": {
    "id": "16f3ee0b-...", "identity": ["SYNTH1"], "name": "Example Study One",
    "research_project_type": "STUDY", "program": "Example Research Program",
    "principal_investigator": ["Example Investigator A"]
  },
  "age_at_index": 32366,
  "visit": [
    { "id": "75122f58-...", "visit_category": "STUDY_SITE_VISIT", "age_at_visit_start": 27375,
      "questionnaire_response": [ { "id": "qr1", "age_at_response": 27375 } ] }
  ],
  "condition": [
    { "id": "1bacbc56-...", "associated_visit": "75122f58-...", "condition_status": "ABSENT",
      "associated_evidence": [ { "_class": "Visit", "id": "75122f58-..." } ] }
  ],
  "measurement_observation": [
    { "id": "520eee1a-...", "associated_visit": "75122f58-...",
      "associated_assay": { "id": "25bf7403-...", "method": "MMO:0000533" },
      "value_quantity": { "id": "071da59d-...", "value_decimal": 6.0, "unit": "10*3/uL" } }
  ],
  "measurement_observation_set": [ { "id": "a38f7d4c-...", "observations": ["a0071d0b-...", "bd46c4e5-..."] } ]
}
```

The log also counts records of participant-capable classes that are in no
document, because they reference no participant.

A full export removes `.json` files of participants that are no longer in the
database. A participant that fails keeps its previous file. With
`--participant`, other files are left alone.

#### Post-processing

Before a document is saved, it goes through the post-processors in
`POST_PROCESSORS` in `src/py_tools/participant_postprocess.py`, then through
those given with `--post-processor module:name`.

Built in:

| Post-processor       | Adds |
|----------------------|------|
| `add_condition_keys` | `_conditions`: one `condition_status\|relationship_to_participant\|condition_concept` string per entry of `condition`, in order, for keyword indexing, e.g. `PRESENT\|ONESELF\|MONDO:0005148`. No spaces. A missing field is an empty part (`PRESENT\|\|MONDO:0005148`), so positions stay fixed. `[]` when there are no conditions. |

Use post-processors to add derived top-level fields for indexing; start their
names with `_` so they can't clash with slot names. The Elasticsearch index is
`dynamic: strict`, so a post-processor that adds fields must declare their
mappings in an `es_properties` attribute, and `es-participant-mapping` must be
run again (see below):

```python
condition_count.es_properties = {"condition_count": {"type": "integer"}}
```

A post-processor is either:

- a function `(doc, ctx)` that changes `doc` in place (returns `None`) or
  returns a new `dict`, or
- a class, which is instantiated once and then called like the function
  (`__call__`). It can keep state across participants, and an optional
  `finish(summary)` method is called after the last one.

`ctx` has `participant_id`, `schema_view` (the LinkML schema), `rows` (every
table's rows by id, for lookups beyond the document) and `extra` (a dict for
passing values to later post-processors). A module given with
`--post-processor` must be importable, so it must be in this package or on
`PYTHONPATH`.

```python
# src/py_tools/participant_postprocess.py
def condition_count(doc, ctx):
    doc["condition_count"] = len(doc.get("condition", []))

POST_PROCESSORS = [condition_count]
```

If a post-processor raises an error, that participant's file isn't written,
the error is printed and logged, and the export continues. Exit status: 0 if
every participant was written, 1 if any failed, 2 if the run couldn't start,
130 if interrupted.

#### Log

`logs/<host>-<port>/<db>/<schema>/export/participants/<timestamp>.log` has
the counts (participants, records per top-level array, stale files removed),
the references left out per `class.slot`, the post-processors used, and each
failed participant with its error.

### `es-participant-mapping`: Elasticsearch index definition for the participant documents

Generates the index definition from the LinkML schema, with the same rules the
exporter uses to build the documents, so the two match:

```sh
uv run es-participant-mapping            # writes schema/es/<YYYYMMDD-HHMMSS>__participant.json
uv run es-recreate-index                 # creates the index from the newest file
```

| Option                         | Default                    | Meaning                          |
|--------------------------------|----------------------------|----------------------------------|
| `--linkml-schema`              | `schema/linkml/bdchm.yaml` |                                  |
| `--out-dir`                    | `schema/es/`               |                                  |
| `--post-processor MODULE:NAME` | none                       | Extra post-processors whose `es_properties` to include. Give the same ones as to the export. |

Each run writes a new file named with the time it was generated, so the
names sort oldest to newest. The newest file is the current definition.
Generate a new one after changing the LinkML schema, the exporter or the
post-processors. The file is the body for `PUT /<index>`:

- `settings`: only the mapping limits the index needs. With bdchm, that's
  `total_fields.limit` 1300 (the mapping has 1,011 fields; the default is
  1000) and `nested_fields.limit` 100 (53 nested fields; the default is 50),
  with room to spare. The index name, shards and replicas are chosen when you
  create the index.
- `mappings._meta`: when and from what it was generated (schema name and
  version, post-processors), and the field counts.
- `mappings`: `dynamic: strict`, so a document with a field that isn't in the
  mapping is rejected. Drift between the exporter and the mapping shows up at
  once.

How fields are mapped:

| In the document | Mapping |
|---|---|
| Member arrays (`condition`, `visit`, ...), indirect records (`visit.questionnaire_response`), multivalued inlined objects (`associated_person.cause_of_death`) | `nested`. Query them with `nested` queries, so conditions on one record's fields apply to the same record. |
| A single inlined object (`value_quantity`, `associated_assay`) | `object` |
| Enum slots, ids, id references (`associated_visit`), uri/curie types | `keyword` |
| LinkML `string` (`description`, `name`, ...) | `keyword`, plus an analyzed `<field>.text` for word search |
| `integer` | `integer` |
| `float`, `double`, `decimal` | `double` |
| `boolean` | `boolean` |
| `date`, `datetime` | `date` |
| `time`, `date_or_datetime` | `keyword` |
| Polymorphic slot that can hold a participant-capable class (`associated_evidence`, `focus` → `Entity`; `derived_from` → `File`; `observations` → `Observation`) | Only `_class` and `id`, with `dynamic: false`: any other fields the exporter inlines there are returned in `_source` but aren't searchable. |
| `response_value` (`QuestionnaireResponseValue`) | All fields of its subclasses, with `value_boolean`, `value_decimal`, `value_integer`, `value_string`, `value_time_point` |
| `_conditions` (post-processor) | `keyword` |

Example queries:

```json
{ "query": { "term": { "_conditions": "PRESENT|ONESELF|MONDO:0005148" } } }

{ "query": { "nested": { "path": "condition", "query": { "bool": { "filter": [
    { "term": { "condition.condition_status": "PRESENT" } },
    { "term": { "condition.condition_concept": "MONDO:0005148" } } ] } } } } }

{ "query": { "nested": { "path": "measurement_observation", "query": { "bool": { "filter": [
    { "term":  { "measurement_observation.value_quantity.unit": "kg/m2" } },
    { "range": { "measurement_observation.value_quantity.value_decimal": { "gt": 30 } } } ] } } } } }
```

Query examples to paste into Kibana Dev Tools, with explanations, are in
[ES-EXAMPLES.md](ES-EXAMPLES.md).

To change the mapping of an existing index, create a new index from the new
file and reindex (or export and load again). Adding a field is the only
change Elasticsearch allows in place.

### `es-recreate-index`: drop and recreate an index from the definition

Deletes an Elasticsearch index if it exists, then creates it from a file
written by `es-participant-mapping`. It doesn't load documents.

```sh
uv run es-recreate-index                                  # participant_1, newest schema/es file, localhost:9200
uv run es-recreate-index --index participant_2 --alias participant
uv run es-recreate-index -y --mapping schema/es/20261029-022946__participant.json
```

| Option | Default | Meaning |
|---|---|---|
| `--index` | `participant_1` | The index to recreate. |
| `--mapping FILE` | the newest `schema/es/*__participant.json` | The index definition (`settings` + `mappings`). |
| `--shards`, `--replicas` | `1`, `0` | Added to the file's settings. With 0 replicas a single-node cluster is green; ES's default of 1 would leave it yellow. |
| `--alias NAME` | none | After creating, point this alias at the new index and remove it from any other index, in one atomic step. |
| `-y`, `--yes` | ask | Delete an existing index without asking. |
| `--host`, `--port`, `--scheme` | `$ES_HOST`/`$ES_PORT`/`$ES_SCHEME`, else `localhost`, `9200`, `http` | |
| `--username`, `--password` | `$ES_USERNAME`, `$ES_PASSWORD` | Basic authentication. Not needed for the local stack, which has security off. |
| `--api-key` | `$ES_API_KEY` | An API key (encoded), instead of a user. |
| `--ca-cert FILE` | `$ES_CA_CERT`, else the system's CAs | For `https`. |
| `--insecure` | off | `https` without verifying the certificate. |
| `--log-dir` | `logs/` | |

What it does:

1. Reads the definition file and connects. It stops if `--index` is the name
   of an alias rather than an index, or if `--alias` is the name of an index.
2. If the index exists, shows its document count and aliases, and asks
   before deleting it (`-y` skips the question; with no terminal and no `-y`
   it changes nothing and exits 1).
3. Creates the index, and waits up to 30 seconds for it to be green (yellow
   when it has replicas).
4. With `--alias`, moves the alias to it.

**Versioned indexes with an alias.** Applications can query an alias
(`participant`) while each schema version gets its own index (`participant_1`,
`participant_2`, ...). With `--alias`, the alias moves to the new index as soon
as it's created, while it's still empty:

```sh
uv run es-recreate-index --index participant_2 --alias participant
```

To move the alias only after the new index has been loaded, leave `--alias`
off here and pass it to `es-load-participants` instead (below). Recreating an index also removes its
aliases, so pass `--alias` again when you recreate the index an alias points at.

Exit status: 0 on success, 1 if deleting wasn't confirmed, 2 on any error
(no definition file, can't connect or authenticate, Elasticsearch error).
Each run's log, with the definition file used, what existed and what was
done, is at `logs/<host>-<port>/es/<index>/recreate/<timestamp>.log`.

### `es-load-participants`: load the participant documents into the index

Bulk-loads the files written by `pg-export-participants` into an existing
index, with each document's `id` (the participant id) as the document `_id`.
A document that's already in the index is replaced, so loading again is safe.

```sh
uv run es-load-participants                                   # data/participants/*.json -> participant_1
uv run es-load-participants --index participant_2 --alias participant --delete-missing
```

| Argument / option | Default | Meaning |
|---|---|---|
| `PARTICIPANTS ...` | `data/participants/` | `.json` files and/or directories (every `*.json` directly in them). |
| `--index` | `participant_1` | The index (or an alias for one index) to load into. It must exist. |
| `--delete-missing` | off | Afterwards, delete documents whose id isn't among the files, e.g. participants no longer in the database. |
| `--alias NAME` | none | If every document loaded, point this alias at the index and remove it from any other (atomically). If anything failed, the alias isn't moved. |
| `--chunk-size` | `500` | Documents per bulk request. |
| connection and `--log-dir` | as for `es-recreate-index` | `--host`, `--port`, `--scheme`, `--username`, `--password`, `--api-key`, `--ca-cert`, `--insecure` (and `ES_*`). |

The index isn't created automatically: Elasticsearch would guess the
mappings. Create it with `es-recreate-index` first. Because the index is
`dynamic: strict`, a document that doesn't match the mapping (for example a
field added by a post-processor that wasn't declared) is rejected and
reported, and the other documents still load. Busy-cluster rejections (429)
are retried with back-off.

The Elasticsearch part of the [typical workflow](#typical-workflow), with a new
versioned index and an alias:

```sh
uv run pg-export-participants
uv run es-participant-mapping                # only when the schema, exporter or post-processors changed
uv run es-recreate-index --index participant_2
uv run es-load-participants --index participant_2 --alias participant --delete-missing
```

Exit status: 0 if every document loaded, 1 if any failed, 2 if the run
couldn't start (no files, no such index, can't connect), 130 if interrupted.
Documents sent before an interruption stay in the index. Each run's log, with
the counts and each failed document's id, file and error, is at
`logs/<host>-<port>/es/<index>/load/<timestamp>.log`.

### `bin/full-reload.sh`: rebuild PostgreSQL and the index

A Bash script that rebuilds everything from the harmonized data, using the tools
above. It works from any folder.

```sh
bin/full-reload.sh            # asks about the primary keys, and before replacing the index
bin/full-reload.sh --es-yes   # replaces the index without asking
```

| Step | Command | Asks? |
|---|---|---|
| 1. Drop and recreate the database | `pg-bootstrap -y` | No: **the database is dropped straight away.** |
| 2. Create the enums-as-varchar tables | `apply_01_tables__enums_as_varchar.py` | |
| 3. Load the harmonized data, every study | `pg-load-data` | |
| 4. Apply the primary keys | `apply_02_primary_keys__enums_as_varchar.py` | `[y/N]`: the default is to skip them. With no terminal, they're skipped. |
| 5. Write the participant documents | `pg-export-participants` | |
| 6. Recreate the index from the newest `schema/es` file | `es-recreate-index` | Yes, if the index exists, unless `--es-yes`. With no terminal and no `--es-yes`, the script stops here. |
| 7. Load the documents | `es-load-participants --delete-missing` | |

| Option | Default | Meaning |
|---|---|---|
| `--es-yes` | ask | Replace an existing index without asking. |
| `--index NAME` | `participant_1` | The index to recreate and load. |
| `--out-dir DIR` | `data/participants/` | Where the participant documents go. |

- The script prints the PostgreSQL and Elasticsearch targets first. They come from
  the tools' environment variables (`PGHOST`, `PGDATABASE`, `ES_HOST`, `ES_PORT`,
  ...). **Check them when `localhost:9200` is a port-forward to a remote cluster.**
- It doesn't run `harmonized-add-studies` or `harmonized-add-ages`. Run those first
  if the data has been regenerated.
- It doesn't apply the foreign keys, or run `es-participant-mapping`. Generate a new
  index definition first if the schema, exporter or post-processors changed.
- It stops at the first step that fails, with exit status 1, and says which. Run it
  again after fixing the cause. Exit status 2 means a bad option or no `uv`.

### `bin/kibana-start.sh` and `bin/kibana-stop.sh`: a Kibana for any Elasticsearch

Bash scripts that start and remove a Kibana container pointed at an
Elasticsearch, for example to look at the indexes these tools create. They
need Docker and curl, and work on Linux (including WSL2) and macOS.

```sh
bin/kibana-start.sh                         # ES at localhost:9200, Kibana at http://localhost:5601
bin/kibana-start.sh --es-host 10.1.2.3 --es-port 9200 --port 5602
bin/kibana-stop.sh
```

`kibana-start.sh`:

| Option | Default | Meaning |
|---|---|---|
| `--es-host`, `--es-port`, `--es-scheme` | `$ES_HOST`/`$ES_PORT`/`$ES_SCHEME`, else `localhost`, `9200`, `http` | The Elasticsearch. `localhost`/`127.0.0.1` mean this machine; inside the container they become `host.docker.internal`. |
| `--port` | `$KIBANA_PORT`, else `5601` | Port to publish Kibana on. |
| `--bind` | `127.0.0.1` | Publish on this machine only; `0.0.0.0` for every interface. |
| `--name` | `$KIBANA_CONTAINER`, else `py-tools-kibana` | Container name, so several can run (with different ports). |
| `--version` | the Elasticsearch version | Kibana must match ES; the script asks ES (`GET /`). |
| `--es-username`, `--es-password` | `$ES_USERNAME`, `$ES_PASSWORD` | For an ES with security on. Use `kibana_system` (Kibana refuses `elastic`). |
| `--service-token` | `$ES_SERVICE_TOKEN` | A service account token (`elastic/kibana`), instead of a user. |
| `--ca-cert`, `--insecure` | `$ES_CA_CERT`, off | For `https`. |
| `--timeout`, `--no-wait` | `180` | How long to wait for Kibana to be ready, or don't wait. |

It refuses to start when a container of that name is already running, when
the port is taken (the backend stack's Kibana also uses 5601; use `--port
5602` next to it), or when ES can't be reached. After starting, it checks that
the container itself can reach ES and prints a warning if not, then waits until
`/api/status` answers 200 and prints the URL. First start takes a minute or two.

`kibana-stop.sh [--name NAME]` stops and removes the container and its
anonymous volumes. Kibana keeps its saved objects (data views, dashboards) in
`.kibana*` indices in Elasticsearch; those are kept, so the next start has
them. Both scripts only touch containers that `kibana-start.sh` created (they
carry the label `py-tools.kibana=true`).

If the container can't reach Elasticsearch, see [DOCKER-VPN.md](DOCKER-VPN.md).
