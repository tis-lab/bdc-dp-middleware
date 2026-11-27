# Bootstrapping a PostgreSQL server

`bootstrap.sql` prepares a new PostgreSQL server for the two schema scripts,
`metadata.sql` and `hm_index.sql`. An administrator runs it once per server.
It creates the roles, the two databases and the database-level grants. After it
has run, the schema scripts run without errors as `deploy_migrations`, and
everything they create is owned by `bdc_dp_owner`.

The split between the two steps follows [README.md](README.md#roles-and-managed-identities):
creating roles and databases needs administrator rights, which the CI/CD
pipeline's identity must not have. So this script is run by hand, and the
pipeline runs only the schema scripts.

| Step | Script | Run by | Connected to |
|---|---|---|---|
| 1 | `bootstrap.sql` | A server administrator | `postgres` |
| 2 | `metadata.sql` | `deploy_migrations` | `bdc_dp_metadata` |
| 3 | `hm_index.sql` | `deploy_migrations` | `bdc_dp_hm_index` |

## Quick start: plain PostgreSQL

```bash
# 1. As an administrator (a superuser such as postgres)
psql -v ON_ERROR_STOP=1 -h HOST -U postgres -d postgres -f schema/db/bootstrap.sql

# 2. Give the deployment role a password (and any app role that will log in)
psql -h HOST -U postgres -d postgres -c '\password deploy_migrations'

# 3. Create the schemas, as deploy_migrations
psql -v ON_ERROR_STOP=1 -h HOST -U deploy_migrations -d bdc_dp_metadata -f schema/db/metadata.sql
psql -v ON_ERROR_STOP=1 -h HOST -U deploy_migrations -d bdc_dp_hm_index -f schema/db/hm_index.sql
```

On PostgreSQL 10, run `src/main/resources/db/metadata-pg10.sql` and
`hm_index-pg10.sql` in step 3 instead (see
[README-SETUP-DB.md](../../README-SETUP-DB.md#postgresql-10)).

Step 3 can also be done with the jar's `setup-db` command. Both databases and
all the roles already exist, so it creates nothing and only runs the scripts.
It picks the PostgreSQL 10 variants itself:

```bash
export PGPASSWORD='...'   # deploy_migrations' password
java -jar target/app.jar setup-db --db.host=HOST --db.user=deploy_migrations --db.name=bdc_dp_hm_index hm
java -jar target/app.jar setup-db --db.host=HOST --db.user=deploy_migrations --db.name=bdc_dp_metadata metadata
```

## Quick start: Azure Database for PostgreSQL

On Azure, every login role is an Entra principal with no password, and the
administrator is a member of the server's Entra administrators group
(`grp-bdcdp-pg-admins-<env>`), not a superuser.

1. Sign in as an administrator and use a token as the password:

   ```bash
   export PGPASSWORD="$(az account get-access-token --resource-type oss-rdbms --query accessToken -o tsv)"
   ```

2. Connected to `postgres`, create each login role from its managed identity's
   object ID. Use `deploy_migrations`, `rebuild_job`, `api_search`,
   `api_counting`, `api_public` and `clearinghouse`, with the identities listed
   in [README.md](README.md#roles-and-managed-identities):

   ```sql
   SELECT * FROM pgaadauth_create_principal_with_oid('rebuild_job', '<object id>', 'service', false, false);
   ```

   Don't create `bdc_dp_owner`. It isn't an identity, and the script creates it.

3. Run `bootstrap.sql` as in the plain quick start. It keeps the Entra roles as
   they are and only sets their limits, memberships and grants.

4. The pipeline runs the schema scripts as `deploy_migrations`, with its own
   token.

If you skip a role in step 2, the script creates it as an ordinary
password-less role, which can't sign in with Entra. Drop it
(`DROP ROLE name;`), create it with `pgaadauth_create_principal_with_oid`, and
run the script again.

## Prerequisites

- **psql.** The script uses psql's `\gexec` and `\connect`, so it can't be run
  through a JDBC client or a GUI query window.
- **PostgreSQL 10 or later.**
- **An administrator.** A superuser, or a role with `CREATEROLE` and `CREATEDB`
  (on Azure, a member of the Entra administrators group).
- **Connect to `postgres`** (any database other than the two being created
  will do). The script switches to each new database itself near the end.

## What the schema scripts assume

The script provides each of these. Without them, `metadata.sql` and
`hm_index.sql` fail, or run but leave the server set up the wrong way.

| Assumption | Why | If missing |
|---|---|---|
| Roles `bdc_dp_owner`, `rebuild_job`, `api_counting`, `api_public` exist | `hm_index.sql` grants to them | `role "..." does not exist` |
| Roles `api_search`, `clearinghouse` also exist | `metadata.sql` grants to them, too | `role "..." does not exist` |
| A database named exactly `bdc_dp_hm_index` | `hm_index.sql` ends with `GRANT TEMPORARY ON DATABASE bdc_dp_hm_index` | `database "bdc_dp_hm_index" does not exist` |
| The user running the scripts acts as `bdc_dp_owner` | Both run `ALTER DEFAULT PRIVILEGES FOR ROLE bdc_dp_owner`, which needs membership | `permission denied` / `must be member of role "bdc_dp_owner"` |
| ... and creates objects **as** `bdc_dp_owner`, not just as a member | Objects belong to whoever creates them; the `SECURITY DEFINER` partition functions run as their owner | No error, but the objects belong to the login user, and the default privileges (set for `bdc_dp_owner`) don't apply to tables added later |
| The databases belong to `bdc_dp_owner` | It needs `CREATE` on the database for `CREATE SCHEMA`, and the right to grant `TEMPORARY` | `permission denied for database ...` |
| `PUBLIC` has no privileges on either database; each role has `CONNECT` only where it should | Keeps search-facing roles away from participant-level data. The scripts don't do this. | No error, but every role can connect to `bdc_dp_hm_index` |
| On PostgreSQL 14 and earlier, `PUBLIC` can't create in schema `public` | The scripts revoke this too, but on 14 and earlier `public` belongs to the superuser, so their revoke does nothing | Only the warning `no privileges could be revoked for "public"` |

`metadata.sql` doesn't name its own database, so `bdc_dp_metadata` is a
naming convention, not a requirement. No extensions are needed.

## What the script does

### Roles

It creates any of these that don't exist yet. Existing roles, including Entra
principals, are kept. Each time it runs, it sets the connection limit and
`statement_timeout` again.

| Role | Created as | Connection limit | `statement_timeout` | Connects to |
|---|---|---|---|---|
| `bdc_dp_owner` | `NOLOGIN` (also enforced on existing roles) | — | — | Owns both databases |
| `deploy_migrations` | `LOGIN`, member of `bdc_dp_owner` | — | — | Both |
| `rebuild_job` | `LOGIN` | 10 | none (runs off-peak) | Both |
| `api_search` | `LOGIN` | 20 | 5s | `bdc_dp_metadata` only |
| `api_counting` | `LOGIN` | 40 | 30s | Both |
| `api_public` | `LOGIN` | 40 | 2s | Both (reads nothing in `hm`) |
| `clearinghouse` | `LOGIN` | 5 | 10s | `bdc_dp_metadata` only |

Login roles are created **without a password**. Until you set one with
`\password ROLE`, they can't log in with password authentication. Limits come
from [README.md](README.md#roles-and-managed-identities). The `provisioning`
and `doc_generator` roles are future additions (see [`future/`](future/README.md))
and aren't created.

If the administrator isn't a superuser, the script also runs
`GRANT bdc_dp_owner TO CURRENT_USER`. Since PostgreSQL 16, creating a role no
longer makes its creator a member, and `CREATE DATABASE ... OWNER bdc_dp_owner`
needs membership.

### Databases

- Creates `bdc_dp_metadata` and `bdc_dp_hm_index` owned by `bdc_dp_owner`, with
  the server's default template, encoding and locale. If they already exist,
  their owner is changed to `bdc_dp_owner`.
- `REVOKE ALL ON DATABASE ... FROM PUBLIC` on both. This removes `CONNECT`
  and `TEMPORARY` from everyone. `hm_index.sql` then grants `TEMPORARY` on
  `bdc_dp_hm_index` to `api_counting`, for the stage 5 temporary tables.
- Grants `CONNECT` as in the table above, and revokes it on `bdc_dp_hm_index`
  from `api_search` and `clearinghouse`, in case an earlier grant gave it to
  them.

### Acting as the owner

```sql
ALTER ROLE deploy_migrations IN DATABASE bdc_dp_metadata SET role = 'bdc_dp_owner';
ALTER ROLE deploy_migrations IN DATABASE bdc_dp_hm_index SET role = 'bdc_dp_owner';
```

Every session `deploy_migrations` opens in either database starts as if it had
run `SET ROLE bdc_dp_owner`. `session_user` is `deploy_migrations` and
`current_user` is `bdc_dp_owner`. This means psql, `setup-db` and the pipeline
all create objects owned by `bdc_dp_owner` without running `SET ROLE`
themselves (`setup-db` doesn't). The setting applies only in these two
databases. In any other database, `deploy_migrations` acts as itself.

### Schema `public`

It connects to each database and runs `REVOKE CREATE ON SCHEMA public FROM PUBLIC`.
On PostgreSQL 15 and later this is already the default. On 14 and earlier, only
an administrator can do it, so the schema scripts' own revoke prints
`WARNING: no privileges could be revoked for "public"`. That warning is
harmless once the script has run.

## Running it again

The script is safe to re-run. It creates only what's missing and sets the
attributes and grants again. A second run prints

```
NOTICE:  role "deploy_migrations" has already been granted membership in role "bdc_dp_owner" by role "postgres"
```

(`... is already a member of role ...` before PostgreSQL 16), which is expected.
Re-run it after changing it, for example to change a role's limits. It doesn't
touch the schemas in the databases, and it never drops anything.

The schema scripts are **not** safe to re-run: a second run fails at
`CREATE SCHEMA` and changes nothing (see
[README-SETUP-DB.md](../../README-SETUP-DB.md)).

## Checking the result

As the administrator:

```sql
-- Roles, limits and timeouts
SELECT rolname, rolcanlogin, rolconnlimit, rolconfig
FROM pg_roles
WHERE rolname IN ('bdc_dp_owner', 'deploy_migrations', 'rebuild_job', 'api_search',
                  'api_counting', 'api_public', 'clearinghouse')
ORDER BY 1;

-- Database owners and privileges
SELECT datname, pg_get_userbyid(datdba) AS owner, datacl
FROM pg_database WHERE datname LIKE 'bdc_dp_%';

-- deploy_migrations' per-database role setting
SELECT d.datname, s.setconfig
FROM pg_db_role_setting s JOIN pg_database d ON d.oid = s.setdatabase
WHERE s.setrole = 'deploy_migrations'::regrole;
```

As `deploy_migrations`, connected to either database:

```sql
SELECT session_user, current_user;   -- deploy_migrations | bdc_dp_owner
```

After the schema scripts, every object in `hm`, `catalog` and `access` should
belong to `bdc_dp_owner`:

```sql
SELECT DISTINCT n.nspname, pg_get_userbyid(c.relowner)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('hm', 'catalog', 'access');
```

## Removing everything (development servers only)

Dropping the databases also removes the objects in them and the
`deploy_migrations` per-database settings. After that the roles can be dropped:

```sql
DROP DATABASE bdc_dp_metadata;
DROP DATABASE bdc_dp_hm_index;
DROP ROLE deploy_migrations, rebuild_job, api_search, api_counting, api_public,
          clearinghouse, bdc_dp_owner;
```

If other databases on the server have objects or grants that belong to these
roles, `DROP ROLE` fails. Run `REASSIGN OWNED BY ... TO ...` and `DROP OWNED BY ...`
in each of those databases first.

## Troubleshooting

| Message | Cause / fix |
|---|---|
| `invalid command \gexec` | The script wasn't run with psql. Use `psql -f`. |
| `syntax error at or near "\"` at `\set ON_ERROR_STOP on` (`SQL state: 42601`) | The script was run in pgAdmin's Query Tool or another GUI, which sends psql's backslash commands to the server. Use `psql -f`, or in pgAdmin open **PSQL Tool** on the `postgres` database and run `\i /path/to/schema/db/bootstrap.sql`. |
| `permission denied to create role` | The administrator lacks `CREATEROLE`. |
| `permission denied to create database` | The administrator lacks `CREATEDB`. |
| `must be able to SET ROLE "bdc_dp_owner"` / `must be member of role "bdc_dp_owner"` (in the script) | A non-superuser administrator isn't a member of `bdc_dp_owner`. The script grants this itself; if it still fails, run `GRANT bdc_dp_owner TO CURRENT_USER;` and run the script again. |
| `password authentication failed for user "deploy_migrations"` | Login roles are created without passwords. Run `\password deploy_migrations` as the administrator. |
| `permission denied for database bdc_dp_hm_index` when a role connects | Expected for `api_search` and `clearinghouse`. For the others, run the script again. |
| Objects owned by `deploy_migrations` instead of `bdc_dp_owner` | The scripts were run in a database the `role` setting doesn't cover, or before the script ran. Check with `SELECT current_user;` in that session. |
| `WARNING: no privileges could be revoked for "public"` (from the schema scripts) | PostgreSQL 14 or earlier; harmless after this script has run (see [Schema `public`](#schema-public)). |

## Tested

On PostgreSQL 16 and 10, in throwaway containers: the script was run twice,
then both schema scripts as `deploy_migrations` (the `-pg10` variants on 10).
The scripts ran without errors, all objects belonged to `bdc_dp_owner`, and
`hm.create_release_partitions` worked when called by `deploy_migrations`. The
teardown commands were checked on 16. Running `setup-db` after the bootstrap,
and the Azure steps, haven't been tested yet.
