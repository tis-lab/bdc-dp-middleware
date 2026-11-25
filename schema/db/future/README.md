# Future additions: persisted cohorts, provisioning and the document generator

# AI Generated README - has not been fully curated by a human yet!!!

The BDC Data Portal databases currently support querying only. Three
additions are planned but not deployed, and everything specific to them is
kept here so the deployed schema stays limited to what querying needs. Each is
a separate implementation:

- **Persisted cohorts** store a cohort beyond one request. Querying
  materializes each cohort in a transaction-scoped temporary table instead.
- **Provisioning** shares participant-level data with an approved researcher.
- **The document generator** builds search documents from the catalog for
  Azure AI Search. It is needed only if Azure AI Search is kept.

| File | Database | Adds |
|---|---|---|
| `cohort_hm_index.yaml`, `cohort_hm_index.sql` | `bdc_dp_hm_index` | The `cohort` schema: `cohort.cohort`, `cohort.cohort_member`, and `cohort.released_set` for the overlap check; `api_counting`'s grants on them |
| `COHORTS.md` | — | Why querying does without stored cohorts, when they are needed, and what to decide first |
| `provisioning_metadata.yaml`, `provisioning_metadata.sql` | `bdc_dp_metadata` | `access.share_record`; provisioning's grants on `catalog` and `access` |
| `provisioning_hm_index.yaml`, `provisioning_hm_index.sql` | `bdc_dp_hm_index` | Builds on persisted cohorts. `cohort.cohort_evidence`; the `cohort.cohort_member_in_scope` view; provisioning's grants on `hm` and `cohort` |
| `doc_generator_metadata.sql` | `bdc_dp_metadata` | doc_generator's grants on `catalog` (no tables, so no model) |
| `PROVISIONING.md` | — | How the provisioning pieces are meant to be used, under consent-group or participant-list shares |

Each model imports the model it extends: `../metadata.yaml`,
`../hm_index.yaml`, or, for `provisioning_hm_index.yaml`,
`cohort_hm_index.yaml`. `schema/tests/check_schema.py` and CI check these pairs
with the deployed ones, so they keep matching the deployed schema until they
are applied.

The deployed schema already holds what these additions build on:
`hm.participant_ordinal` (participant numbers to participant IDs),
`hm.group_range`, `access.account` and `access.entitlement`.

## Applying an addition

1. For provisioning or the document generator, an Entra administrator creates
   the role on the server (see [Roles](#roles)), grants it `CONNECT` on the
   databases it uses, and sets its limits. Persisted cohorts need no new role.
2. `deploy_migrations`, acting as `bdc_dp_owner`, runs the addition's `.sql`
   files after the deployed ones:
   - persisted cohorts: `cohort_hm_index.sql` in `bdc_dp_hm_index`;
   - provisioning: `provisioning_metadata.sql` in `bdc_dp_metadata`, and, for
     participant-list shares, `provisioning_hm_index.sql` in
     `bdc_dp_hm_index` after `cohort_hm_index.sql`;
   - the document generator: `doc_generator_metadata.sql` in
     `bdc_dp_metadata`.
3. Move the files up into `schema/db/` (merging each model into the model it
   imports, and each `.sql` into its database's file), and add the role's rows
   back to the tables in `../README.md`.

The grant sections are safe to re-run and, like the deployed ones, must be
re-run after every schema change.

## Roles

| Postgres role | Entra identity (dev) | Runs in | `bdc_dp_metadata` | `bdc_dp_hm_index` | `statement_timeout` | Connection limit |
|---|---|---|---|---|---|---|
| `provisioning` | App registration `app-bdcdp-provisioning-dev` | ARO | Reads releases, consent groups, eligibility, the stigmatizing list, entitlements and accounts; writes share records | Reads the participant mapping, the only role that sees participant IDs, and in-scope cohort members | 30s | 5 |
| `doc_generator` | Managed identity `id-bdcdp-docgen-dev`; only if Azure AI Search is kept | Azure Function or job | Reads `catalog` | No connection | 60s | 2 |

Provisioning logs in with its own app registration, never through the
`middleware` role. Its certificate is kept in its own OpenShift Secret,
readable by the provisioning pod only, with the master copy in Key Vault like
the others (see [Credentials](../README.md#credentials)).

## Persisted cohorts

[COHORTS.md](COHORTS.md) describes how stage 5 works without stored cohorts,
when stored cohorts would be needed, and the decisions to make first:
lifetime, who removes expired cohorts and how, and whether to store
`in_exact_scope`.

## Provisioning

[PROVISIONING.md](PROVISIONING.md) describes how provisioning is meant to
work, the rules every share follows, and which of these pieces each sharing
model needs. The pieces in this folder were written for participant-list
shares, and `provisioning_hm_index.sql` builds on persisted cohorts. Under
consent-group shares, neither is needed.

Provisioning reads cohort membership only through
`cohort.cohort_member_in_scope`, which lists the members whose consent group
was in the researcher's exact scope when the cohort was created: covered by
one of their dbGaP visas, with `exact_count_eligible` in
`catalog.consent_group_current`. It never reads `cohort.cohort_member`, so it
never sees out-of-scope members. It maps those members' numbers back to
participant IDs through `hm.participant_ordinal`.

`cohort.cohort_evidence` records which BDC-HM records satisfied each rule for
each cohort member, so a share can be limited to the matching records. The
counting API would write it when it stores a cohort. It is not deployed
because nothing in querying needs it: the disclosure policy shows records to no
tier, and the qualifying records can be recovered from `hm.event` with the
cohort's expression and release.

Every share is recorded in `access.share_record`, with the consent groups and
visas that entitled it. `access.share_record.cohort_id` names a
`cohort.cohort` row in the other database, so it has no foreign key: Postgres
cannot query across databases unless `dblink` or `postgres_fdw` is enabled, and
neither is.

## Document generator

The document generator reads every `catalog` table, including tables added
later (through a default privilege). It never connects to `bdc_dp_hm_index`
and gets nothing in `access`, so search documents can only contain catalog
metadata, never participant-level data.
