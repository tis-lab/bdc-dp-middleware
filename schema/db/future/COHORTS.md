# Persisted cohorts

# AI Generated - has not been curated by a human yet!!!

Querying does not store cohorts. This document describes the future addition
that does, `cohort_hm_index.sql` and `cohort_hm_index.yaml`, and what must be
decided before it is applied.

## How querying works without it

Stage 5 of the cohort builder (signed-in breakdowns, and counts for approved
researchers) runs on the refined cohort. Each stage 5 request materializes it
for that request only:

1. `api_counting` evaluates the cohort expression once into a
   `CREATE TEMPORARY TABLE ... ON COMMIT DROP` table.
2. It computes every facet and study contribution of the response from that
   table, in the same transaction, with noise seeded from the participant set.
3. It commits, which drops the table.

This gives:

- **No stored participant lists.** Nothing ties a researcher to specific
  participants after the response is sent.
- **Nothing to expire.** There is no cleanup job, no dead rows to vacuum, and
  no WAL: temporary tables are not written to it.
- **Consistent noise.** The noise depends only on the participant set, and the
  same expression on the same release always yields the same set.
- **Current exact scope.** It is computed per request from the caller's
  entitlements and `catalog.consent_group_current`, never frozen.

The cost is that the expression is evaluated once per request. A researcher
who requests the same cohort again pays for the evaluation again, which
matters only for slow expressions (stage 4 temporal rules, up to
`api_counting`'s 30s timeout).

Session-scoped temporary tables are not used: connections are pooled and shared
between users, so a table left on a connection could be seen by another user's
request.

## When persisted cohorts are needed

- **Reuse across a session.** If evaluating expressions per request is too
  slow, a cohort can be stored once and reused while the researcher works.
- **Participant-list provisioning.** A share limited to a cohort's members
  needs the member list to exist when the share is made.
  `provisioning_hm_index.sql` builds on this addition; consent-group shares do
  not need it (see [PROVISIONING.md](PROVISIONING.md)).

## What the addition contains

| Object | Purpose |
|---|---|
| `cohort` schema | Holds stored cohorts, apart from `hm` |
| `cohort.cohort` | One row per cohort: owner (`ras_subject`), release, expression and its hash, creation and expiry times |
| `cohort.cohort_member` | One row per member, with `in_exact_scope` |
| Index on `cohort.cohort.expires_at` | Finds expired cohorts |
| `cohort.released_set` | The overlap check's record of each released cohort (below) |
| Grants | `api_counting` may read, write and delete cohorts; no role reads `cohort.released_set` directly |

## The overlap check

A live query's released counts must not differ from an earlier released set by
1 to `min_count − 1` participants. Storing every facet and contribution of
every response would be too large, so only the **cohort's** participant
numbers are stored, in `cohort.released_set`. Every facet, value and
contribution in a response is that cohort intersected with a posting list or a
consent-group range, so its difference from the same cell of an earlier
response is the cohort difference intersected with the same posting list or
range.

Still to be written with this addition, as `SECURITY DEFINER` functions:

- `cohort.record_released_set` records a response's cohort.
- `cohort.overlap_violations` computes the check for every cell the middleware
  is about to release, against earlier sets for the same account or, with no
  account, for everyone; identical sets are allowed. It returns the keys of
  cells that must not get a fresh count, and translates earlier sets from
  another release through participant IDs.
- `cohort.purge_released_sets` enforces the retention window.

The API roles can run these functions but cannot read the stored sets.

Facets must therefore be intersections with sets the database holds: terms
(through `hm.term_member`) and consent groups (through `hm.group_range`).
Value bins and demographic values need posting lists of their own before they
can be checked this way. Public queries do not need the check: they read
`hm.public_summary`, whose rows were checked against each other when the
release was built.

## Decisions to make before applying it

1. **Lifetime.** How long a cohort lives: a session's idle timeout (refresh a
   `last_used_at` on each use), a fixed time such as 24 hours, or longer. It
   should never outlast the cohort's release, and if `in_exact_scope` is kept,
   never outlast the entitlements it was computed from.
2. **Who removes expired cohorts.** Nothing deletes them yet. One owner
   should: `api_counting` on a timer, a scheduled job (for example `pg_cron`
   on Azure Flexible Server, if enabled), or a container job.
3. **How they are removed.**
   - `DELETE FROM cohort.cohort WHERE expires_at <= now()`, cascading to the
     members, deletes row by row: every member is written to the WAL and left
     for autovacuum. Fine for small cohorts.
   - Partitioning `cohort_member` by expiry day and dropping whole partitions
     makes removal a metadata operation with no bloat. The cascade then has to
     be replaced by dropping the cohort rows and their partition together.
   - `UNLOGGED` tables remove the WAL cost; cohorts are then lost on a crash or
     failover and the researcher refines again.
4. **Reads ignore expired cohorts.** Rows stay until cleanup runs, so every
   read must filter on `expires_at > now()`, through a view or a rule every
   function follows.
5. **Whether to store `in_exact_scope`.** It is fixed at cohort creation, so an
   entitlement that expires, or an eligibility decision withdrawn, after that
   is not noticed. It can be derived per request instead, from `hm.group_range`
   (participant number to consent group) and current entitlements.

## Size

Each member row, with its primary-key index, takes roughly 100 bytes. At 200
cohorts an hour:

| Typical cohort size | Rows written per hour | Storage per hour | Per day kept (24h expiry) |
|---|---|---|---|
| 1,000 | 200k | ~20 MB | ~0.5 GB |
| 50,000 | 10M | ~1 GB | ~24 GB |
| 500,000 | 100M | ~10 GB | ~240 GB |

In steady state every row written is later deleted, roughly doubling the write
load, and WAL adds to backup and replica storage. Small cohorts are no concern;
cohorts of hundreds of thousands of members need partitioned removal at least.
These are estimates from PostgreSQL's storage layout, not measurements.

## Applying it

Run `cohort_hm_index.sql` in `bdc_dp_hm_index` after `hm_index.sql`, as
`deploy_migrations` acting as `bdc_dp_owner`. It adds grants only for
`api_counting`, an existing role. Then move it into `schema/db/` as described
in [README.md](README.md#applying-an-addition), and add the `cohort` schema
back to the database table in `../README.md`.
