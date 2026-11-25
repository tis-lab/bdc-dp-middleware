# BDC Data Portal databases

# AI Generated README - has not been fully curated by a human yet!!!

Two Postgres databases on one Azure Database for PostgreSQL server support the
BDC Data Portal Cohort Builder. Every account that connects is an Entra
identity; no role has a password.

These files support querying only: term search, public and signed-in
summaries, and counts for approved researchers. Participant-level access
(provisioning), Azure AI Search (the document generator) and stored cohorts
are future additions, kept apart in [`future/`](future/README.md) and not
deployed.

| File | What it holds |
|---|---|
| `common.yaml` | Conventions, slots and enums shared by both databases |
| `metadata.yaml`, `metadata.sql` | `bdc_dp_metadata`: schemas `catalog` and `access` |
| `hm_index.yaml`, `hm_index.sql` | `bdc_dp_hm_index`: schema `hm`, and the release partition functions |
| `future/` | Future additions, not deployed: persisted cohorts, provisioning and the document generator |

Each `.yaml` is the LinkML model and each `.sql` the DDL, with that database's
grants at the end. They must agree; see [Validation](#validation).

## Design

### Two databases

| Database | Schema | Holds | Participant-level data? |
|---|---|---|---|
| `bdc_dp_metadata` | `catalog` | Releases, terms and their closure, studies, variable mappings, consent groups and eligibility, the stigmatizing-concept list, term availability, the term resolution cache | No |
| | `access` | Accounts, entitlements, the query ledger | No, but sensitive |
| `bdc_dp_hm_index` | `hm` | The participant-level projection of BDC-HM: participant numbers, attributes, events, posting lists, release checks; and the fixed public table (`hm.public_summary`) | Yes, except `hm.public_summary` |

The `cohort` schema (stored cohorts and the overlap check's released sets) is
a future addition; see [`future/COHORTS.md`](future/COHORTS.md).

The database boundary keeps search-facing identities away from participant
data: a role without `CONNECT` on `bdc_dp_hm_index` cannot reach it at all.
Postgres cannot query across databases unless `dblink` or `postgres_fdw` is
enabled, and neither is.

### Releases

Each data release is a pinned Delta version, recorded in `catalog.release`
with its schema and BDC-KG versions. Release-scoped tables in `hm` are
partitioned by release:

1. The rebuild job calls `hm.create_release_partitions('2026.10')`, which adds
   one partition per table.
2. It loads the release, writes `hm.release_check` rows, and activates the
   release in `catalog.release` only if every check passed.
3. When a release is retired, it is marked retired in `catalog.release`, then
   `hm.retire_release_partitions('2026.10')` detaches and drops its partitions.
   `bdc_dp_hm_index` cannot check the first step, so the caller must do it.
   Retiring also drops the release's `hm.public_summary` rows.

`hm.release_check` is not partitioned. It is the permanent record of what was
verified before each release was served, and the rebuild job cannot delete
from it.

### Consent groups and eligibility

`catalog.consent_group` holds versioned, curated meaning.
`catalog.consent_group_eligibility` holds versioned decisions on exact counts
and sharing, each naming the meaning version it was made against.
`catalog.consent_group_current` pairs the latest of each; if the meaning has
changed since the decision, the group reads as ineligible until eligibility is
decided again.

### The fixed public table

`hm.public_summary` holds every public answer before anyone asks: one row per
filter of one or two terms combined with And, built by the rebuild job from
`hm.term_member`. Demographic values and age bins are terms too, so a one-term
filter's facets are the two-term rows that include it. Rows hold released
values only: totals already obfuscated, studies listed without counts,
below-threshold studies counted. Because every row is known at build time, the
job withholds any pair of rows whose difference would fall below `min_count`
before the release is activated. `api_public` reads this table and nothing
else in `bdc_dp_hm_index`, so it never sees an exact count or a participant.

### Ownership

`bdc_dp_owner` owns every schema, table, view and function, and no one logs in
as it. `deploy_migrations` (the Jenkins migration job) acts as the owner to
change schemas: run each `.sql` connected to its database as
`deploy_migrations`; each file starts with `SET ROLE bdc_dp_owner`. The rebuild job owns nothing; it adds and
retires release partitions through two `SECURITY DEFINER` functions that run
with the owner's rights, so it cannot change grants or table definitions.

## Roles and Entra identities

Databases, roles and who may connect to each database are created once per
server by a member of the Entra administrators group, not by the CI/CD
pipeline: the pipeline's identity must not be able to create roles. Each login
role is created with `pgaadauth_create_principal_with_oid` from its Entra
object ID (see [How sign-in works](#how-sign-in-works)). The DDL here assumes
the roles exist.

Role names are the same in every environment; each environment has its own
Entra identities. Workloads in ARO and Jenkins sign in as **app
registrations** with certificates, because the ARO cluster cannot federate its
service accounts with Entra ID. Workloads in Databricks or other Azure
services use **managed identities**, which need no stored credential. The
table shows dev.

| Postgres role | Logs in? | Entra identity (dev) | Runs in | `bdc_dp_metadata` | `bdc_dp_hm_index` |
|---|---|---|---|---|---|
| `bdc_dp_owner` | No | None | — | Owns everything | Owns everything |
| `deploy_migrations` | Yes | App registration `app-bdcdp-deploy-dev` | Jenkins | Acts as owner | Acts as owner |
| `middleware` | Yes | App registration `app-bdcdp-middleware-dev` | ARO | Nothing until it switches role | Nothing until it switches role |
| `api_search` | No; reached by `middleware` | — | ARO, via `middleware` | Reads `catalog`; writes the resolution cache and query ledger | No connection |
| `api_counting` | No; reached by `middleware` | — | ARO, via `middleware` | Reads releases, consent groups, eligibility, the stigmatizing list and entitlements; writes the query ledger | Reads `hm` except participant IDs; creates temporary tables for one transaction (stage 5) |
| `api_public` | No; reached by `middleware` | — | ARO, via `middleware` | Reads releases, terms, closure, studies, term availability, the stigmatizing list; records public queries in the query ledger, without reading it | Reads the fixed public table only: released values, no participants |
| `clearinghouse` | Yes | App registration `app-bdcdp-clearinghouse-dev` | ARO | Writes accounts and entitlements | No connection |
| `rebuild_job` | Yes | Managed identity `id-bdcdp-rebuild-dev` | Databricks | Writes `catalog` | Writes `hm` (not deletes from `release_check`); runs the partition functions |

Two identities have no Postgres role: the Unity Catalog access connector
(`id-bdcdp-uc-connector-dev`), which reads storage only, and the server's
administrators, an Entra group (`grp-bdcdp-pg-admins-dev`) that creates the
databases and roles. The `provisioning` and `doc_generator` roles are future
additions; see [`future/`](future/README.md).

### How the middleware switches role

The middleware has one app registration and logs in as `middleware`, which
holds no privileges of its own (`NOINHERIT`). Each connection pool runs
`SET ROLE` to one of `api_search`, `api_counting` or `api_public` when a
connection opens, and sets that role's statement timeout; from then on,
Postgres enforces that role's grants. Which pool a request uses is decided by
the caller's tier, from the RAS token or its absence, never by the request.

This keeps the database separation between search, public and authenticated
queries with one credential instead of three. The trade-off: a SQL injection
flaw in middleware code could switch a connection to another of the three
roles, so every query must be parameterized. The clearinghouse keeps its own
app registration and is never reachable from the middleware's credential; so
will provisioning, when it is added.

Role settings and connection limits apply to the login role, so the
middleware's connection limit covers all its pools together, and per-role
timeouts are set by the pools:

| Role | `statement_timeout` | Connection limit |
|---|---|---|
| `api_public` (pool) | 2s, set by the pool | Shares `middleware`'s |
| `api_search` (pool) | 5s, set by the pool | Shares `middleware`'s |
| `api_counting` (pool) | 30s (stage 4 temporal queries), set by the pool | Shares `middleware`'s |
| `middleware` (login) | — | 100 (the three pools together) |
| `clearinghouse` | 10s | 5 |
| `rebuild_job` | None; it runs off-peak | 10 |

### Public summaries

Every `hm` table other than `hm.public_summary` is participant-level and keyed
by `ordinal`, so any two of them join into a row-level profile:
`term_member`, `participant_attribute` and `group_range` together give each
participant's study, consent group, demographics and concepts. `api_public`
therefore gets `SELECT` on `hm.public_summary` only; see
[The fixed public table](#the-fixed-public-table). `hm_index.sql` states the
full contract for what that table may hold.

### Stage 5 cohorts

Stage 5 (signed-in breakdowns, and counts for approved researchers) runs on
the refined cohort, but cohorts are not stored. Each stage 5 request evaluates
the cohort expression once into a `CREATE TEMPORARY TABLE ... ON COMMIT DROP`
table, computes the whole response from it in the same transaction, and
commits, which drops it. No participant list is kept per researcher and
nothing needs to expire. Exact scope is computed per request from current
entitlements. `api_counting` is granted `TEMPORARY` on `bdc_dp_hm_index` for
this, since the bootstrap revokes it from `PUBLIC`. Session-scoped temporary
tables are not used: connections are pooled and shared between users. Stored
cohorts are a future addition; see [`future/COHORTS.md`](future/COHORTS.md).

The overlap check on stage 5 responses (refusing a fresh count that differs
from an earlier released set by 1 to `min_count − 1` participants) is also a
future addition: it stores each response's cohort in `cohort.released_set`
and runs through `SECURITY DEFINER` functions; see
[`future/COHORTS.md`](future/COHORTS.md#the-overlap-check).

### The query ledger

`access.query_ledger` records every answered query, for budgets, overlap
checks and audit. A public query has no user, so a public row names no account
and is identified by `client_key`, a hashed network address. Every
signed-in or approved row names the caller's account and has no `client_key`.
A `CHECK` constraint enforces this. `api_public` may insert every column except
`ras_subject`, so it can record only public queries, and it cannot read the
ledger.

### How sign-in works

1. The Azure team creates the app registration and its certificate, or the
   managed identity.
2. An Entra administrator creates a Postgres role tied to the identity's
   object ID with `pgaadauth_create_principal_with_oid`. For an app
   registration, use its **service principal's** object ID (Enterprise
   applications in the Entra portal), not the app registration's own.
3. The grant sections of `metadata.sql` and `hm_index.sql` give the role its
   privileges.
4. At run time, the workload uses its certificate (or its managed identity) to
   get a short-lived Entra token, and uses the token as the password.

Disabling the identity, or revoking its certificate, ends its database access.
Researchers never connect: they are identified by their NIH RAS subject in
`access.account`, and the middleware connects on their behalf.

### Credentials

Three credentials exist, one certificate per app registration; managed
identities need none.

| Certificate | Stored in | Readable by |
|---|---|---|
| `app-bdcdp-middleware-dev` | OpenShift Secret `bdc-data-portal-middleware-cert` | The middleware pod only |
| `app-bdcdp-clearinghouse-dev` | Its own OpenShift Secret | The clearinghouse pod only |
| `app-bdcdp-deploy-dev` | Jenkins credential store | The migration job only |

Key Vault holds the master copy of each certificate. Whoever owns rotation
renews certificates before they expire, updates the Secrets, and is alerted
well ahead of expiry; an expired certificate stops that workload from
connecting. Reading a Secret is equivalent to holding that role's database
access, so access to Secrets in the namespace should be limited to the people
who deploy and rotate. Sign-ins by these service principals should be
monitored. Provisioning will add a fourth certificate; see
[`future/`](future/README.md#roles).

## Changing the schema

After any change, re-run the grant section of the changed `.sql`; it is safe
to re-run. New tables get schema-wide default grants automatically, but grants
limited to specific objects, such as `api_public`'s, must be added by hand. Grant on partitioned parents only: queries go through the
parent, where privileges are checked, so partitions created later need no
grants of their own. Functions are not executable by `PUBLIC`: revoke that
explicitly for each new function, and grant `EXECUTE` to the roles that need
it.

## What the counts mean

These rules are defined in `hm_index.yaml` and implemented by the rebuild job
and the middleware.

### A participant is one enrollment in one study

Each `participant_id` is one BDC-HM `Participant` record: one person's
enrollment in one research study, under one consent group. BDC-HM has no
identifier that links the same person across studies, so someone enrolled in
two studies is two participants. A count over several studies counts
enrollments, not distinct people. Participants are numbered in consent-group
order, so each consent group is a contiguous range in `hm.group_range`, and
counts per group are range filters.

### Conflicting demography records

A participant can have several `Demography` records (one per visit).
`hm.participant_attribute` reduces them to one row per participant:

| Value | Rule |
|---|---|
| `sex`, `ethnicity` | Set only when all the participant's non-NULL values agree; otherwise NULL, so the participant is counted under no value and not as recorded |
| `race` | Union of the records' values, so race buckets can add up to more than the participant count |

The `demography_conflict` release check reports, for each release, how many
participants have records that disagree.

### Age summaries

Age summaries bin `age_at_baseline_days`: `Visit.age_at_visit_start` of the
study's curated baseline visit (`hm.study_baseline`). A participant without a
baseline visit is not counted as having an age. `age_at_index_days` is kept
for fidelity to BDC-HM, but harmonization does not populate it and summaries
do not use it.

Harmonized ages are whole years × 365, so binning converts with the same
factor: age in years is `floor(age_at_baseline_days / 365)`, and a band
`[low, high)` in years holds days from `365 * low` inclusive to `365 * high`
exclusive. Converting with 365.25 instead would move everyone whose age is
exactly a band's lower bound into the band below (45 years is stored as 16425,
below `ceil(45 * 365.25)` = 16437).

### Measurement values

`hm.event.value_numeric` holds `value_quantity.value_decimal` when set,
otherwise `value_quantity.value_integer`, so integer- and decimal-valued
observations are compared and binned together. Records with both set to
different values fail the `quantity_value_conflict` release check.

### Condition ages

`age_start_days` for a condition is `Condition.age_at_condition_start`, NULL
when not recorded. The pinned BDC-HM has no other slot for the age at which a
condition was reported.

## Validation

`schema/tests/check_schema.py` compares each model with its DDL (columns,
partitioning, keys, nullability, types, enum and pattern checks) and checks
that every `bdchm:` mapping names a class or slot in the pinned BDC-HM
(`synthetic/bdchm.yaml`). Every table and view in the DDL needs a model class
naming it in a `table` or `view` annotation. The future additions are checked
too, so they keep matching the deployed models until they are applied. Run it after changing a model or
its DDL:

```sh
pip install -r schema/tests/requirements.txt
python schema/tests/check_schema.py                                         # metadata and hm_index
python schema/tests/check_schema.py schema/db/hm_index.yaml schema/db/hm_index.sql   # one pair
gen-yaml schema/db/hm_index.yaml > /dev/null                                # loads as the generators load it
```

It can be run from any directory; with no arguments it finds the files from the
repository root. It prints one line per problem and exits 1 if there are any.

`check_schema.py` loads models with SchemaView, which resolves each import
relative to the file that contains it and, when two modules define the same
name, silently keeps one. The generators (`gen-java`, `gen-typescript`) resolve
a nested import relative to the root schema unless it starts with `./` or
`../`, and stop with `Conflicting URIs` on a duplicate name. So write nested
imports as `./name`, and load each model with `gen-yaml` as well; it prints
`slot_usage for undefined slot` errors for the `*Event` subclasses and for
BDC-HM's `category`, which do not stop generation.

CI (`.github/workflows/db-schema-validation.yaml`) runs both on every pull
request and on pushes to `dev` and `main`.
