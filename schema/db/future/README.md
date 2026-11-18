# Future additions: provisioning and the document generator

# AI Generated README - has not been fully curated by a human yet!!!

The BDC Data Portal databases currently support querying only. Two workloads
are planned but not deployed, and everything specific to them is kept here so
the deployed schema stays limited to what querying needs:

- **Provisioning** shares participant-level data from a materialized cohort
  with an approved researcher.
- **The document generator** builds search documents from the catalog for
  Azure AI Search. It is needed only if Azure AI Search is kept.

| File | Database | Adds |
|---|---|---|
| `provisioning_metadata.yaml`, `provisioning_metadata.sql` | `bdc_dp_metadata` | `access.share_record`; provisioning's grants on `catalog` and `access` |
| `provisioning_hm_index.yaml`, `provisioning_hm_index.sql` | `bdc_dp_hm_index` | `cohort.cohort_evidence`; the `cohort.cohort_member_in_scope` view; provisioning's grants on `hm` and `cohort` |
| `doc_generator_metadata.sql` | `bdc_dp_metadata` | doc_generator's grants on `catalog` (no tables, so no model) |
| `PROVISIONING.md` | — | How the provisioning pieces are meant to be used, under consent-group or participant-list shares |

Each model imports the deployed model it extends (`../metadata.yaml` or
`../hm_index.yaml`). `schema/tests/check_schema.py` and CI check these pairs
with the deployed ones, so they keep matching the deployed schema until they
are applied.

The deployed schema already holds what these workloads build on:
`hm.participant_ordinal` (participant numbers to participant IDs),
`cohort.cohort_member.in_exact_scope`, `access.account` and
`access.entitlement`.

## Applying an addition

1. An Entra administrator creates the role on the server (see
   [Roles](#roles)), grants it `CONNECT` on the databases it uses, and sets
   its limits.
2. `deploy_migrations`, acting as `bdc_dp_owner`, runs the addition's `.sql`
   files after the deployed ones: `provisioning_metadata.sql` in
   `bdc_dp_metadata` and `provisioning_hm_index.sql` in `bdc_dp_hm_index`, or
   `doc_generator_metadata.sql` in `bdc_dp_metadata`.
3. Move the files up into `schema/db/` (merging each model into the model it
   imports, and each `.sql` into its database's file), and add the role's rows
   back to the tables in `../README.md`.

The grant sections are safe to re-run and, like the deployed ones, must be
re-run after every schema change.

## Roles

| Postgres role | Entra identity (dev) | Hosted on | `bdc_dp_metadata` | `bdc_dp_hm_index` | `statement_timeout` | Connection limit |
|---|---|---|---|---|---|---|
| `provisioning` | `id-bdcdp-provisioning-dev` | Container App or Function | Reads releases, consent groups, eligibility, the stigmatizing list, entitlements and accounts; writes share records | Reads participant numbers and in-scope cohort members only | 30s | 5 |
| `doc_generator` | `id-bdcdp-docgen-dev` | Function or job; only if Azure AI Search is kept | Reads `catalog` | No connection | 60s | 2 |

## Provisioning

[PROVISIONING.md](PROVISIONING.md) describes how provisioning is meant to
work, the rules every share follows, and which of these pieces each sharing
model needs. The pieces in this folder were written for participant-list
shares; under consent-group shares, most of `provisioning_hm_index.sql` is not
needed.

Provisioning reads cohort membership only through
`cohort.cohort_member_in_scope`, which lists the members whose consent group
was in the researcher's exact scope when the cohort was created: covered by
one of their dbGaP visas, with `exact_count_eligible` in
`catalog.consent_group_current`. It never reads `cohort.cohort_member`, so it
never sees out-of-scope members. It maps those members' numbers back to
participant IDs through `hm.participant_ordinal`.

`cohort.cohort_evidence` records which BDC-HM records satisfied each rule for
each cohort member, so a share can be limited to the matching records. The
counting API writes it when it materializes a cohort. It is not deployed
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
