# Setting up the database schemas in PostgreSQL

The service jar has a `setup-db` command. It runs SQL scripts bundled in the jar
against a database you name. Each script creates its schemas: tables, indexes, views,
functions and grants.

| script | file in the jar | schemas | database in deployment |
|---|---|---|---|
| `hm` | `db/hm_index.sql` | `hm`: participant-level projection, partitioned by release, and the release partition functions | `bdc_dp_hm_index` |
| `metadata` | `db/metadata.sql` | `catalog`: releases, terms, studies, consent meaning and eligibility; `access`: accounts, entitlements, the query ledger | `bdc_dp_metadata` |

The scripts are exact copies of `schema/db/hm_index.sql` and `schema/db/metadata.sql`.
They're for a **new database**: they create their objects without checking whether
they exist. On PostgreSQL 10 the command runs variants of them instead (see
[PostgreSQL 10](#postgresql-10)). The command takes the same connection options as
[`load`](README-LOAD.md).

## Quick start

```bash
# 1. Build the jar (once, and after code changes)
./mvnw -DskipTests package

# 2. Set up each database with its script, as a user you name
export PGPASSWORD='...'
java -jar target/app.jar setup-db --db.name=bdc_dp_hm_index --db.user=bdc_dp_owner hm
java -jar target/app.jar setup-db --db.name=bdc_dp_metadata --db.user=bdc_dp_owner metadata

# For development, both can go into one database (see the note on bdc_dp_hm_index below)
java -jar target/app.jar setup-db --db.name=mydb --db.user=me hm metadata
```

## Prerequisites

- **Java 21+**.
- **PostgreSQL 10 or later.** 11 and later run the `schema/db` scripts unchanged; 10
  runs the `-pg10.sql` variants.
- **The user exists.** The command doesn't create users.
- **The database is new.** If it doesn't exist, the command creates it, owned by the
  user it connects as; that needs `CREATEDB` (see
  [Creating the database](#creating-the-database)). If it exists, it mustn't have the
  scripts' schemas yet.
- **`CREATEROLE`, if a role is missing.** The scripts grant to `bdc_dp_owner`,
  `rebuild_job`, `api_counting` and `api_public` (`hm`), and also `api_search` and
  `clearinghouse` (`metadata`). The command creates missing ones (see
  [Creating the roles](#creating-the-roles)). In deployment an Entra administrator
  creates them beforehand, so nothing is created. To prepare a server the way
  deployment expects (roles, both databases, `CONNECT` grants, objects owned by
  `bdc_dp_owner`), run [`schema/db/bootstrap.sql`](schema/db/bootstrap.sql) as an
  administrator first and connect as `deploy_migrations`; see
  [schema/db/README-bootstrap.md](schema/db/README-bootstrap.md).
- **The user is the database's owner** (`bdc_dp_owner` in deployment), or a superuser
  on a development server. The scripts change `bdc_dp_owner`'s default privileges and
  revoke `CREATE` on schema `public`. Objects created are owned by the user the command
  connects as.
- **`hm` needs a database named `bdc_dp_hm_index`.** `hm_index.sql` grants `TEMPORARY`
  on that database by name. If you set up `hm` in a database with another name, the
  grant still goes to `bdc_dp_hm_index` if it exists, and the script fails if it
  doesn't.

## What the command does

If the database doesn't exist, it's created first, then any missing roles (see below).
Then the command runs the scripts in the order named; a name given twice runs once.
Each script is sent to PostgreSQL as one statement, which the server runs as **one
implicit transaction**: a script that fails changes nothing. If one fails, the scripts after it
don't run, and the ones before it stay committed.

Running a script again on a database that already has its schemas fails at the first
`CREATE SCHEMA` and changes nothing.

## PostgreSQL 10

On a server older than 11, `setup-db` runs `db/hm_index-pg10.sql` and
`db/metadata-pg10.sql` instead of `db/hm_index.sql` and `db/metadata.sql`, because
PostgreSQL 10 lacks two things the `schema/db` scripts use.

**`hm`**: PostgreSQL 10 can't put a primary key, unique constraint or index on a
partitioned table (`primary key constraints are not supported on partitioned tables`).
In `hm_index-pg10.sql`:

- The seven partitioned tables carry only their columns (key columns `NOT NULL`) and
  `CHECK`s.
- `hm.create_release_partitions` adds the primary keys, unique constraint and indexes to
  each partition it creates, with the same columns and index types.
- Everything else is the same as `schema/db/hm_index.sql`.

Every key includes `release`, the partition key, and each partition holds one release,
so a key per partition enforces exactly what the parent's key does. A release's
partitions end up with the same keys and indexes as on 11 and later.

**`metadata`**: PostgreSQL 10 has no `starts_with()`
(`function starts_with(text, text) does not exist`). `metadata-pg10.sql` writes the
`catalog.consent_group` check as
`left(consent_group_id, length(study_accession) + 2) = study_accession || '.c'` instead of
`starts_with(consent_group_id, study_accession || '.c')`, which accepts and rejects the
same values. Nothing else differs.

## Creating the database

When the server says the database named by `--db.name` doesn't exist, the command
connects to the maintenance database (`--setup.maintenance-db`, default `postgres`,
which every PostgreSQL server has) as the same user and runs
`CREATE DATABASE "DATABASE"`, then connects to the new database and runs the scripts.

- The new database is owned by the user, with the server's defaults (template, encoding,
  locale).
- The name is used exactly as given, including case: `--db.name=MyDb` creates `MyDb`.
- The user needs `CREATEDB` (or to be a superuser). Without it, the command stops with
  `can't create database ...: permission denied to create database` and exit status 2.
- If another session creates the database at the same moment, that's fine.
- An existing database is used as it is; nothing about it is changed.

## Creating the roles

Before running the scripts, the command checks the roles they grant to and creates each
missing one as `CREATE ROLE name NOLOGIN`: a placeholder, not a user. It can't log in and
has no password; it only gives the grants something to name. In deployment, each is
mapped to an Entra managed identity instead.

- If the command creates `bdc_dp_owner` and the user isn't a superuser, it also runs
  `GRANT bdc_dp_owner TO CURRENT_USER`. The scripts change `bdc_dp_owner`'s default
  privileges, which only its members may do.
- Roles belong to the server, not the database, and each is created on its own: they
  stay if a script then fails, and later runs, for any database, reuse them.
- The user needs `CREATEROLE` (or to be a superuser). Without it, the command stops with
  `can't create the roles the scripts grant to: ... permission denied to create role`
  and exit status 2, before running any script.
- Existing roles are left as they are.

## Usage

```
java -jar target/app.jar setup-db --db.name=DATABASE --db.user=USER [OPTIONS] SCRIPT...
```

Each `SCRIPT` is `hm` or `metadata`. At least one is required.

### Connection

The same as for `load`. `--db.name` and `--db.user` are **required**, with no defaults.
If either is missing, or no script is named, the command prints what's missing and
exits with status 2, without connecting.

| option | default | meaning |
|---|---|---|
| `--db.name=DATABASE` | *(required)* | database to set up |
| `--db.user=USER` | *(required)* | user to connect as |
| `--db.password=PASSWORD` | `PGPASSWORD` env var, else none | the user's password |
| `--db.host=HOST` | `localhost` | |
| `--db.port=PORT` | `5432` | |

These are read only from the command line, except the password, which falls back to
`PGPASSWORD`. That keeps it out of your shell history and the process list.

### Options

| option | default | meaning |
|---|---|---|
| `--setup.maintenance-db=NAME` | `postgres` | database to connect to for creating `--db.name` when it doesn't exist |
| `--logging.level.gov.nih.nhlbi.bdc=LEVEL` | `INFO` | |

### Without building the jar

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="setup-db --db.name=mydb --db.user=me hm metadata"
```

## Output

The log goes to standard output.

```
INFO ... SetupDbCommand : Setting up database mydb on localhost:5432 as me with hm, metadata
INFO ... SetupDbCommand : Running hm (db/hm_index.sql)
INFO ... SetupDbCommand : Ran hm (db/hm_index.sql)
INFO ... SetupDbCommand : Running metadata (db/metadata.sql)
INFO ... SetupDbCommand : Ran metadata (db/metadata.sql)
INFO ... SetupDbCommand : Set up database mydb on localhost:5432 as me with hm, metadata
```

When the database or roles don't exist yet, more lines come first:

```
INFO ... SetupDbCommand : Database mydb does not exist; creating it from database postgres
INFO ... SetupDbCommand : Created database mydb
INFO ... SetupDbCommand : Created missing role bdc_dp_owner (NOLOGIN placeholder)
INFO ... SetupDbCommand : Made the user a member of bdc_dp_owner
INFO ... SetupDbCommand : Created missing role rebuild_job (NOLOGIN placeholder)
```

```
ERROR ... SetupDbCommand : Script metadata failed; its transaction was rolled back, it changed nothing: ERROR: schema "catalog" already exists
```

### Exit status

| status | meaning |
|---|---|
| `0` | every script ran |
| `1` | a script failed and was rolled back (see the `ERROR` line); scripts before it stay committed, scripts after it didn't run |
| `2` | usage error (missing `--db.name`, `--db.user` or `SCRIPT`, an unknown script, bad number), PostgreSQL unavailable, or the database or a role couldn't be created |

## Troubleshooting

| message | cause / fix |
|---|---|
| `setup-db: missing --db.name, --db.user, SCRIPT` | Give the database, the user and the scripts: `--db.name=... --db.user=... hm`. |
| `setup-db: unknown scripts: ...` | Name `hm`, `metadata` or both. |
| `Stopped: PostgreSQL is unavailable: ...` | Wrong host, port, database, user or password, or the server is down. See [load's troubleshooting](README-LOAD.md#troubleshooting). |
| `can't create database ...: permission denied to create database` | The database doesn't exist and the user lacks `CREATEDB`. Have it created for the user, or grant `CREATEDB`. |
| `can't create database ...: ... database "postgres" does not exist` | The server has no `postgres` database. Name another one the user can connect to: `--setup.maintenance-db=template1`. |
| `schema "..." already exists` | The database was already set up with that script. Use a new database, or drop the schemas first on a development database (`DROP SCHEMA hm CASCADE;`). |
| `can't create the roles the scripts grant to: ... permission denied to create role` | A role is missing and the user lacks `CREATEROLE`. Grant it, or have the roles created, e.g. `CREATE ROLE rebuild_job NOLOGIN;`. |
| `database "bdc_dp_hm_index" does not exist` | `hm` grants `TEMPORARY` on that database by name. Set up `hm` in `bdc_dp_hm_index`, or create a database with that name. |
| `permission denied ...` / `must be owner of ...` / `must be member of role "bdc_dp_owner"` | Connect as the database's owner (`bdc_dp_owner`), or a superuser on a development server. |

## Re-runnable versions

`src/main/resources/db/hm_index-enhanced.sql` and `metadata-enhanced.sql` are versions
of the two scripts that are safe to run again: each is one explicit transaction,
creates only what's missing, adds missing columns, creates missing roles `NOLOGIN`,
grants `TEMPORARY` on the current database instead of `bdc_dp_hm_index`, and stops
without changing anything when an existing table or index differs. `setup-db` doesn't
run them yet. To try one by hand:

```bash
psql -v ON_ERROR_STOP=1 -d mydb -f src/main/resources/db/hm_index-enhanced.sql
```

## Running the PostgreSQL tests

`SetupDbCommandPgTest` creates a throwaway database for each test, runs the command
against it and drops it afterwards. The command creates the roles when they're missing,
and they're left behind; the test also creates a `bdc_dp_hm_index` database when it's
missing, for `hm`'s grant, and drops it afterwards. So it needs one more opt-in than the
other tests, and a user that can create databases and roles. Use a disposable server:

```bash
PG_TEST_SETUP_DB=true PG_TEST_DB=postgres PG_TEST_USER=postgres PG_TEST_PASSWORD='...' ./mvnw test
```

`SetupDbCommandTest` checks that the bundled scripts are still identical to the ones
in `schema/db`, that the `-pg10.sql` variants still have the same tables, columns and
grants, and that the roles the command creates are the ones the scripts grant to, so a
change to one without the others fails the build. The variants are edited by hand: when
`schema/db` changes a key, index or check, make the same change in them.

The PostgreSQL tests pass on 10 and on 16; point `PG_TEST_*` at a PostgreSQL 10 server
to test the variants.
