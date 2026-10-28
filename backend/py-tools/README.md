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

Generates PostgreSQL DDL from any LinkML schema, split into one file per kind of
statement.

#### Running it

```sh
uv run linkml-pg-ddl schema/linkml/bdchm.yaml                     # native enum types
uv run linkml-pg-ddl schema/linkml/bdchm.yaml --enums-as-varchar  # varchar + CHECK
```

| Argument / option    | Meaning                                                                 |
|----------------------|-------------------------------------------------------------------------|
| `SCHEMA`             | Path to the LinkML schema YAML, relative to the current directory or absolute. Its imports are resolved too. |
| `--enums-as-varchar` | Store enum slots as `varchar` columns, with CHECK constraints listing the permissible values, instead of native PostgreSQL enum types. |
| `--out-root DIR`     | Parent folder for the output. Default: this project's `schema/pg-ddl/`. |

Output goes to `<out-root>/<schema-file-stem>/`. For example,
`schema/linkml/bdchm.yaml` goes to `schema/pg-ddl/bdchm/`. The script prints
each file it writes. Every run replaces the previously generated files, so
don't edit them by hand. Change the schema and regenerate instead.

#### Output files

| File                  | Contents                                                                |
|-----------------------|-------------------------------------------------------------------------|
| `00_enum_types.sql`   | `CREATE TYPE ... AS ENUM`, one per enum. Native enum mode only.         |
| `01_tables.sql`       | `CREATE TABLE`, plus `COMMENT ON` for each table and column giving its LinkML class or slot and description. |
| `02_primary_keys.sql` | Primary keys.                                                           |
| `03_foreign_keys.sql` | Foreign keys, plus a comment for each reference that has none (see below). |
| `04_constraints.sql`  | CHECK constraints (enum values in varchar mode, `minimum_value`, `maximum_value`, `pattern`) and UNIQUE (`unique_keys`). |
| `05_not_null.sql`     | `SET NOT NULL` for `required` slots. Optional.                          |

Apply them in name order. Each file only depends on the ones before it.
`05_not_null.sql` comes last so you can leave it out, for example while loading
partial data.

#### Applying the DDL

Names in the DDL are not schema-qualified. Objects are created in the first
schema on the session's `search_path`, so you choose the PostgreSQL schema when
you apply the files.

With `psql`, into a `bdchm` schema:

```sh
psql -c 'CREATE SCHEMA IF NOT EXISTS bdchm'
for f in schema/pg-ddl/bdchm/*.sql; do
  PGOPTIONS='-c search_path=bdchm' psql -v ON_ERROR_STOP=1 -f "$f"
done
```

As init files in the official `postgres` Docker image: the image runs the
`*.sql` and `*.sh` files at the top level of `/docker-entrypoint-initdb.d/`
in name order, once, when the data directory is empty. It does not look in
subfolders. Copy the output folder in as a subfolder, and add a script that
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
are applied, `05_not_null.sql` included.

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
| `required: true`                             | `SET NOT NULL` in `05_not_null.sql`                     |

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
  `03_foreign_keys.sql` lists each of these as a comment.
- **Parent tables don't contain subclass rows.** `observation` holds only plain
  `Observation` objects. A `MeasurementObservation` is in
  `measurement_observation` only.
- **Required multivalued references aren't enforced.** They become join tables,
  and NOT NULL can't require at least one row. `01_tables.sql` marks these with
  a `-- note:`.
- **Reserved words are quoted.** A name that's a PostgreSQL reserved word, such
  as the column `order` in bdchm's `cause_of_death`, is written as `"order"`, and
  your SQL must quote it too. All other names are unquoted lowercase.
- **Long names are shortened.** PostgreSQL limits names to 63 characters, so a
  longer name is cut and given an 8-character hash suffix.
- **Every class needs an identifier.** Each concrete class, and each class
  used as a range, must have an `identifier: true` (or `key: true`) slot.
  Otherwise the script stops with an error.
