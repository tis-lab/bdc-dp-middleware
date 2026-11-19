# Loading JSON objects into PostgreSQL

The service jar has a `load` command. It reads files of JSON objects (YAML or JSON)
and stores each object as one row of a PostgreSQL table:

| column | type    | value                                                        |
|--------|---------|--------------------------------------------------------------|
| `type` | `text`  | from the file name: the part between `-` and `--`            |
| `id`   | `text`  | the object's top-level `id`                                  |
| `data` | `jsonb` | the whole object                                             |
| `file_path` | `text` | absolute path of the file the object was loaded from      |

The primary key is `(type, id, file_path)`. The same object loaded from two different
files gives two rows. Loading the same file again skips its rows. For example, every object in
`SYNTH1-Participant--data.yaml` gets type `Participant`.

## Quick start

```bash
# 1. Build the jar (once, and after code changes)
./mvnw -DskipTests package

# 2. Load a directory, recursively, into a database you name, as a user you name
java -jar target/app.jar load --db.name=mydb --db.user=me src/main/resources/data/study_one
```

With `PGPASSWORD` set, or `--db.password=...` given, that loads the 8 files of `study_one`
(19,080 objects) into `dp_data.json_object` in database `mydb`.

## Prerequisites

- **Java 21+**.
- **A PostgreSQL database and a user** to load as. The command doesn't create
  databases or users. It connects to the database you name as the user you name,
  and only works inside that database.
- **Privileges for that user.** If the schema and table don't exist yet, the user needs
  `CREATE` on the database. If they already exist (made for it by someone else), it
  needs `USAGE` on the schema and `INSERT` on the table.

## What the command creates

Only things inside the given database, and only when they're missing:

1. the schema `--load.schema` (default `dp_data`),
2. the table `--load.table` (default `json_object`) in it, with the columns above and
   primary key `(type, id, file_path)`,
3. on an existing table, any missing column, or the primary key if it's different.

It checks the catalog first, so when everything already exists it runs no DDL at all.
Objects it creates are owned by the user it connects as.

## Usage

```
java -jar target/app.jar load --db.name=DATABASE --db.user=USER [OPTIONS] PATH...
```

Each `PATH` is either a file or a directory:

- **A file** is loaded whatever its extension. The format is guessed from the content
  when the extension isn't known.
- **A directory** is searched recursively, and the files with a supported extension are
  loaded in sorted path order. Symbolic links to directories aren't followed.

You can give several paths: `load ... dirA dirB/one-file.yaml`.

### Connection

`--db.name` and `--db.user` are **required**, with no defaults. If either is missing,
the command prints what's missing and exits with status 2, without connecting.

| option | default | meaning |
|---|---|---|
| `--db.name=DATABASE` | *(required)* | database to load into |
| `--db.user=USER` | *(required)* | user to connect as |
| `--db.password=PASSWORD` | `PGPASSWORD` env var, else none | the user's password |
| `--db.host=HOST` | `localhost` | |
| `--db.port=PORT` | `5432` | |

These are read only from the command line. Environment variables such as `DB_NAME`
don't fill them in. The one exception is the password. If `--db.password` isn't given,
the standard `PGPASSWORD` variable is used if it's set. That keeps the password out of
your shell history and the process list:

```bash
PGPASSWORD='s3cret' java -jar target/app.jar load --db.name=mydb --db.user=me path/to/data
```

If neither is set, no password is sent. That works for servers that trust the
connection.

### Load options

| option | default | meaning |
|---|---|---|
| `--load.schema=NAME` | `dp_data` | schema of the table |
| `--load.table=NAME` | `json_object` | table name |
| `--load.batch-size=N` | `1000` | rows per batch; each batch is committed |
| `--logging.level.gov.nih.nhlbi.bdc=LEVEL` | `INFO` | `DEBUG` also logs when a batch is split |

Other commands:

```bash
java -jar target/app.jar help          # list the commands
java -jar target/app.jar load --help   # the load options
```

Without arguments, or when the first argument is an option (`--key=value`), the jar
starts the web service instead.

### Without building the jar

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="load --db.name=mydb --db.user=me src/main/resources/data/study_one"
```

### Actual example for the BDC data
export PGPASSWORD=***
java -jar target/app.jar load --db.port=5433 --db.name=bdc_cohort_builder_dev --db.user=userC0S backend/py-tools/data/harmonized

## Supported files

| extension | format |
|---|---|
| `.yaml`, `.yml` | YAML, one object per document (documents separated by `---`) |
| `.json` | one object, an array of objects, or several objects one after another |
| `.ndjson`, `.jsonl` | one object per line |
| any of these + `.gz` | the same, gzipped (detected from the content, not only the name) |

A top-level array is unwrapped, so each element is one object. Empty YAML documents
are ignored.

**File names must contain the type** between a `-` and a `--`:
`<anything>-<Type>--<anything>.<ext>`, for example `SYNTH1-Person--data.yaml`. The
`-` used is the last one before the first `--`. A file without a type fails, and its
error is logged. Other files are still loaded.

## What happens during a load

- **Batches.** Rows are inserted in batches of `--load.batch-size`, and each batch is
  committed. If a load stops partway, the batches committed before that point stay in
  the table.
- **Bad rows are skipped, not fatal.** When a batch fails because of its data, it's
  rolled back and split in halves, again and again, until the failing rows are found
  on their own. Those rows are skipped and the rest are inserted. Data failures
  include a duplicate `(type, id, file_path)`, a value PostgreSQL's `jsonb` rejects (such as a
  `\u0000` character), or a row that's too large.
- **Objects without an id are skipped.** The top-level `id` must be a non-empty
  string or a number. Values that aren't objects are skipped too.
- **Existing rows are kept.** If a `(type, id, file_path)` is already in the table, the
  new row is skipped and logged. Loading the same files again, from the same location,
  skips every row. See [Reloading](#reloading). `file_path` is the absolute path
  without symbolic links resolved, so the same file reached through another path
  (a moved checkout, a symlink) counts as a different file.
- **Each skipped row is logged** at `WARN`, with its position in the file, its type
  and id, the reason, and the full JSON:

  ```
  WARN ... JsonObjectLoader : Skipped object #3 in data/S-Thing--data.ndjson (type Thing, id r1): [23505] ERROR: duplicate key value violates unique constraint "json_object_pkey" ...: {"id":"r1",...}
  ```

- **A failing file doesn't stop the others.** If a file can't be read or has no type,
  the error is logged and the next file is loaded.
- **Losing the database does stop the load.** If no connection can be made (server
  down, wrong credentials, missing database or user), or the connection is lost, the
  load stops at that file.

Each file ends with a summary line, and the whole load with a total:

```
INFO ... JsonObjectLoader     : Loaded .../SYNTH1-Person--data.yaml: read 500, inserted 500, skipped 0
INFO ... JsonObjectPathLoader : Loaded 8 files: inserted 19080, skipped 0; 0 failed
```

### Exit status

| status | meaning |
|---|---|
| `0` | every file loaded (some rows may have been skipped; check the log) |
| `1` | at least one file failed (see the `ERROR` lines) |
| `2` | usage error (missing `--db.name`, `--db.user` or paths, bad number), or PostgreSQL unavailable |

## Keeping and searching the log

The log goes to standard output.

```bash
java -jar target/app.jar load --db.name=mydb --db.user=me src/main/resources/data/study_one 2>&1 | tee load.log

grep 'Skipped object' load.log     # the rows that weren't inserted, with their JSON
grep ' ERROR ' load.log            # files that failed
```

## Checking the result

```bash
psql -h localhost -U me -d mydb
```

```sql
-- rows per type
SELECT type, count(*) FROM dp_data.json_object GROUP BY type ORDER BY type;

-- rows per file
SELECT file_path, count(*) FROM dp_data.json_object GROUP BY file_path ORDER BY file_path;

-- one object
SELECT jsonb_pretty(data) FROM dp_data.json_object
WHERE type = 'Participant' AND id = 'ded1d538-866f-5c18-8aa2-0b24f1f08a5a';

-- query inside the JSON
SELECT id, data->>'condition_concept' AS concept
FROM dp_data.json_object
WHERE type = 'Condition' AND data->>'condition_status' = 'PRESENT'
LIMIT 10;

-- follow a reference: participants and their person
SELECT p.id, p.data->>'identity' AS identity, pe.data AS person
FROM dp_data.json_object p
JOIN dp_data.json_object pe ON pe.type = 'Person' AND pe.id = p.data->>'associated_person'
WHERE p.type = 'Participant'
LIMIT 5;
```

## Reloading

Rows whose `(type, id, file_path)` already exists are skipped, not replaced. To replace
data, delete it first, then load again:

```sql
DELETE FROM dp_data.json_object WHERE file_path LIKE '%/study_one/%';  -- one study folder
DELETE FROM dp_data.json_object WHERE type = 'Participant';           -- one type
TRUNCATE dp_data.json_object;                                         -- everything
```

### Tables from before `file_path`

Tables created before the `file_path` column existed have primary key `(type, id)`.
The loader adds the column and switches the primary key to `(type, id, file_path)`.
If the table already has rows, those rows have no `file_path`, so the load stops with
`... has rows without type, id or file_path ... Nothing was changed.` Either delete
them (`TRUNCATE`) and load again, or fill in their `file_path` with an `UPDATE`.

To keep different loads apart, use a different table or schema, for example
`--load.table=json_object_test`.

## Troubleshooting

| message | cause / fix |
|---|---|
| `load: missing --db.name, --db.user ...` | Give the database and user: `--db.name=... --db.user=...`. |
| `Stopped: PostgreSQL is unavailable: ... Connection refused` | Nothing is listening on that host and port. Check `--db.host` and `--db.port`. |
| `Stopped: PostgreSQL is unavailable: ... FATAL: database "..." does not exist` / `role "..." does not exist` | The command doesn't create databases or users. Create them (or ask your DBA), or pass the right `--db.name` / `--db.user`. |
| `... password authentication failed for user ...` / `... no password was provided` | Pass `--db.password=...` or set `PGPASSWORD`. |
| `permission denied for database ...` | The user can't create the schema. Have it created for the user, or use one that exists (`--load.schema=...`). |
| `permission denied for schema ...` | The user can't create the table there, or can't use the schema. Grant `USAGE` and `CREATE` on the schema, or have the table created for the user. |
| `File name has no type between '-' and '--'` | Rename the file to `<prefix>-<Type>--<suffix>.<ext>`, or load it through code with a `JsonObjectLoader` subclass that overrides `typeOf`. |
| `Unknown command: ...` | The first argument isn't a command. Run `java -jar target/app.jar help`. |
| Many `Skipped object ... duplicate key` lines | Those objects were already loaded from that file. See [Reloading](#reloading). |
| `... has rows without type, id or file_path ...` | The table is from before `file_path`. See [Tables from before `file_path`](#tables-from-before-file_path). |

## Using it from code

The command is a thin wrapper around two classes in
`gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load`:

```java
// any javax.sql.DataSource, e.g. org.postgresql.ds.PGSimpleDataSource
JsonObjectLoader loader = new JsonObjectLoader(dataSource, "dp_data", "json_object", 1000);
JsonObjectLoader.LoadResult r = loader.load(Path.of("SYNTH1-Person--data.yaml"));

// files and directories, a loader per file
JsonObjectPathLoader.Result all = new JsonObjectPathLoader(file -> loader)
        .load(Path.of("src/main/resources/data/study_one"));
```

`JsonObjectLoader` can be subclassed to change how a file's type is found (`typeOf`),
which files are picked from directories (`supports`), how formats are detected
(`formatOf`), how objects are read (`read`), how ids are taken (`idOf`), and what's
stored in `file_path` (`filePathOf`).

New command-line commands go in `cli/Commands` (see the `CliCommand` Javadoc).

## Running the PostgreSQL tests

The tests that need a database are opt-in, so a build never writes to a server nobody
chose. Point them at a database where the user can create schemas. Each test creates
a throwaway schema there and drops it afterwards.

```bash
PG_TEST_DB=mydb PG_TEST_USER=me PG_TEST_PASSWORD='...' ./mvnw test
# optional: PG_TEST_HOST (default localhost), PG_TEST_PORT (default 5432)
```

Without `PG_TEST_DB` and `PG_TEST_USER`, those tests are skipped.
