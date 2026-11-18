# Provisioning: how the future additions are meant to be used

# AI Generated - has not been curated by a human yet!!!

Provisioning gives an approved researcher access to participant-level data
through a share. It is not deployed; the portal currently supports querying
only. This document describes how the provisioning pieces in this folder are
meant to work, so that whoever builds the provisioning service, or decides
its scope, has one place that says what each piece is for.

Which participants a share covers has not been decided. Two models are
described:

- **Consent-group shares** (the current intent): a share covers whole consent
  groups.
- **Participant-list shares** (possible later decision): a share covers only
  the members of the researcher's cohort.

The two models need different schema pieces. [Which pieces each model
needs](#which-pieces-each-model-needs) lists them, and [When the decision is
made](#when-the-decision-is-made) says what to change in each case.

## Rules every share follows

These hold in both models. The service enforces them; they are checked when
the share is created, never carried over from an earlier decision.

1. **The researcher is entitled now.** For each consent group in the share,
   `access.entitlement` has an unexpired row for the researcher's
   `ras_subject`. The clearinghouse writes entitlements from dbGaP visas;
   provisioning only reads them.
2. **The group may be shared now.** `catalog.consent_group_current` shows
   `sharing_eligible` for the group. That view reports a group eligible only
   when its eligibility decision was made against the current version of its
   consent meaning, so a changed consent meaning blocks sharing until
   eligibility is decided again. `exact_count_eligible` is a different
   decision (exact counts in the cohort builder) and never authorizes a share.
3. **The release is active.** The share's release has status `active` in
   `catalog.release`.
4. **The share expires no later than its entitlements.** `expires_at` is no
   later than the earliest `expires_at` of the entitlements that authorized
   it, which is itself never later than the visa's expiry.
5. **Every share is recorded.** Provisioning writes one `access.share_record`
   row per share, naming the consent groups and visas that authorized it,
   before the share is made available.
6. **Shares are dropped when their authority ends.** When a share expires, or
   an entitlement or eligibility decision it relied on is withdrawn, the share
   is removed and `dropped_at` is set. Provisioning checks live shares
   against rules 1 to 3 on a schedule as well as at creation.

A share is a Delta Sharing share in Unity Catalog. `share_name` names the
share and `recipient_name` the Delta Sharing recipient it is granted to.

## Consent-group shares (current intent)

A share covers every participant in each consent group it names. Under dbGaP,
a visa grants a whole consent group, so sharing exactly the groups the
researcher is entitled to adds no exposure beyond what the visa already
authorizes.

### Flow

1. The researcher, signed in through NIH RAS, asks to share data for a set of
   consent groups. The request can come from a cohort ("the groups this cohort
   spans"); working out which groups a cohort spans is a counting question,
   answered by the counting API before the request reaches provisioning.
2. Provisioning checks rules 1 to 3 for each requested group and drops any
   group that fails. It does not trust the request's list: it re-checks every
   group itself.
3. It creates the share over the governed data for the remaining groups,
   filtered by consent group, and records it in `access.share_record`
   (`consent_groups`, `visa_ids`, `expires_at` per rule 4, `bytes_shared`).

### What it reads and writes

| Database | Reads | Writes |
|---|---|---|
| `bdc_dp_metadata` | `access.account`, `access.entitlement`, `catalog.consent_group_current` (and the tables behind it), `catalog.release` | `access.share_record` |
| `bdc_dp_hm_index` | Nothing | Nothing |

Provisioning never connects to the participant-level database. The share is
defined by consent group IDs, not by participant numbers.

`access.share_record.cohort_id` only records which cohort prompted the
share, if one did. It is `NOT NULL` today; in this model it should become
optional, since a researcher can share consent groups without a cohort.

## Participant-list shares (possible later decision)

A share covers only the members of the researcher's materialized cohort whose
consent groups pass rules 1 to 3, and optionally only the records that
satisfied the cohort's rules.

### Flow

This model needs [persisted cohorts](COHORTS.md): querying keeps a cohort only
for the length of one request, and a share needs the member list to exist when
the share is made.

1. The researcher builds a cohort in the cohort builder. At the final
   refinement, the counting API stores it: one `cohort.cohort` row (with
   the researcher's `ras_subject`, the release, the expression and an
   expiry) and one `cohort.cohort_member` row per member. If record-level
   shares are wanted, it also writes `cohort.cohort_evidence`.
2. The researcher asks to share the cohort. Provisioning checks that the
   cohort's `ras_subject` is the requester, that the cohort has not expired,
   and that its release is still active.
3. Provisioning selects the members to share: those whose consent group passes
   rules 1 to 3 at share time (see [the in-scope view](#the-in-scope-view-uses-the-wrong-flag)).
4. It maps those members' `ordinal`s to `participant_id`s through
   `hm.participant_ordinal`, creates the share over the governed data filtered
   to those participants (and, with evidence, to the matching records), and
   records it in `access.share_record`.

### What it reads and writes

| Database | Reads | Writes |
|---|---|---|
| `bdc_dp_metadata` | As for consent-group shares | `access.share_record` |
| `bdc_dp_hm_index` | `cohort.cohort`, the in-scope cohort members, `hm.participant_ordinal`, optionally `cohort.cohort_evidence` | Nothing |

### Problems to solve before using this model

#### The in-scope view uses the wrong flag

`cohort.cohort_member_in_scope` selects members whose `in_exact_scope` is
true. `in_exact_scope` means the member's group was covered by a visa and
`exact_count_eligible` when the cohort was created. That is the scope for
exact counts, not for sharing:

- A group can be eligible for exact counts but not for sharing. The view
  would let provisioning share it.
- The value is fixed at cohort creation. An entitlement that has expired, or
  an eligibility decision withdrawn since then, would not be noticed.

Sharing needs a scope computed at share time from `sharing_eligible` and live
entitlements. Either replace the view with a `SECURITY DEFINER` function that
takes the cohort and the list of groups provisioning has already checked, or
give provisioning `cohort.cohort_member` filtered by consent group.

#### Provisioning would read every participant ID

`GRANT SELECT ON hm.participant_ordinal TO provisioning` exposes every
participant ID in every release, not just the cohort's. A `SECURITY DEFINER`
function that returns the IDs of one cohort's members in the checked groups
would limit provisioning to what it is sharing.

#### A share cannot be audited after its cohort expires

`access.share_record` names consent groups, not participants. Cohort rows
expire (and are deleted with their members and evidence), so after that
nothing records which participants a share covered. This model needs either
a per-share participant list kept for as long as share records are kept, or
cohorts that outlive every share made from them.

#### Shares outlive the release partitions

Retiring a release drops its `hm` partitions, so a cohort built on it can no
longer be resolved. Live shares on a release must be dropped, or re-created on
the new release, before it is retired.

## Which pieces each model needs

| Piece | Where | Consent-group shares | Participant-list shares |
|---|---|---|---|
| Persisted cohorts (`cohort.cohort`, `cohort.cohort_member`) | `cohort_hm_index.sql` | No | Yes |
| `access.share_record` | `provisioning_metadata.sql` | Yes (`cohort_id` made optional) | Yes, plus a per-share participant list |
| Provisioning's grants on `catalog` and `access` | `provisioning_metadata.sql` | Yes | Yes |
| `cohort.cohort_member_in_scope` view | `provisioning_hm_index.sql` | No | Replaced by a share-time scope (see above) |
| `cohort.cohort_evidence` | `provisioning_hm_index.sql` | No | Only for record-level shares |
| Provisioning's SELECT on `hm.participant_ordinal` | `provisioning_hm_index.sql` | No | Better replaced by a function |
| Provisioning's access to `cohort.cohort` | `provisioning_hm_index.sql` | No | Yes |
| `CONNECT` on `bdc_dp_hm_index` for provisioning | Server bootstrap | No | Yes |
| `cohort.cohort_member.in_exact_scope` | `cohort_hm_index.sql` | Not used | Not enough on its own (see above) |

## When the decision is made

**If shares are consent-group only:**

1. Delete `provisioning_hm_index.sql` and `provisioning_hm_index.yaml`, and
   their pair in `schema/tests/check_schema.py`. Persisted cohorts are then
   needed only if stage 5 is too slow without them (see [COHORTS.md](COHORTS.md)).
2. Make `access.share_record.cohort_id` optional in
   `provisioning_metadata.sql` and `.yaml`.
3. Give provisioning no `CONNECT` on `bdc_dp_hm_index`, and update the roles
   table in `README.md`.

**If shares are participant lists (or both):**

1. Apply [persisted cohorts](COHORTS.md) first, deciding cohort lifetime with
   share lifetime in mind.
2. Replace `cohort.cohort_member_in_scope` with a share-time scope based on
   `sharing_eligible` and live entitlements.
3. Replace provisioning's SELECT on `hm.participant_ordinal` with a function
   limited to one cohort's checked members.
4. Add a per-share participant list, or tie cohort expiry to share lifetime.
5. Keep `cohort.cohort_evidence` only if shares are limited to matching
   records.
6. Decide what happens to live shares when their release is retired.

In either case, apply the files as described in [README.md](README.md#applying-an-addition).
