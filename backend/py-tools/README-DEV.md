# README-DEV.md (py-tools)

Developer notes. Usage is in [README.md](README.md).

## `linkml-pg-ddl` (`src/py_tools/linkml_pg_ddl.py`)

### Structure

- `Generator.build()` turns the schema into a list of `Table` objects (columns,
  primary key, foreign keys, checks, uniques, notes). Join tables are `Table`s
  with `class_name = None`.
- The `render_*()` methods turn those into SQL, one method per output file.
  `write()` deletes the known output files, then writes the ones for the
  current mode.
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
there is exactly one. Otherwise `03_foreign_keys.sql` gets a comment naming the
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
NULL` in `05_not_null.sql`, so it can be skipped. Primary key columns are left
out (the PK already implies NOT NULL), and so are join tables. A required
multivalued class-range slot can't be enforced at all and gets a `-- note:`
in `01_tables.sql`.

**No PostgreSQL schema in the DDL.** Names are unqualified, so the target schema
comes from `search_path` at apply time. Each file is a separate `psql` session
under Docker init, so `search_path` has to be set per session. That's why the
README uses `PGOPTIONS`. A `SET search_path` inside one file would not carry
over to the next.

**File names** are numbered and have no schema prefix. The output folder already
names the schema, and the numbers give the apply order.

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

Regenerate both modes and apply them to a throwaway PostgreSQL (same major
version as the backend). Each mode goes into its own schema:

```sh
S=$(mktemp -d)
uv run linkml-pg-ddl schema/linkml/bdchm.yaml
uv run linkml-pg-ddl schema/linkml/bdchm.yaml --enums-as-varchar --out-root "$S"
docker run -d --rm --name ddl-test -e POSTGRES_PASSWORD=x postgres:18.6
until docker exec ddl-test pg_isready -U postgres -q; do sleep 1; done; sleep 2
apply() {
  docker exec ddl-test psql -q -U postgres -c "CREATE SCHEMA $1"
  for f in "$2"/*.sql; do
    docker exec -i -e PGOPTIONS="-c search_path=$1" ddl-test \
      psql -q -v ON_ERROR_STOP=1 -U postgres < "$f" || return 1
  done && echo "$1 ok"
}
apply native schema/pg-ddl/bdchm
apply varchar "$S/bdchm"
docker stop ddl-test
```

Before stopping the container, also check that bad data is rejected: an
unknown enum value, a dangling reference, and a missing required value should
each fail. If you change something bdchm doesn't use (`unique_keys`,
`minimum_value`/`maximum_value`, `pattern`, custom types, enum
`include`/`minus`, reserved table names, very long names), test it with a small
schema of your own as well.

When bdchm changes, check whether the generated diff under
`schema/pg-ddl/bdchm/` matches what you expected.
